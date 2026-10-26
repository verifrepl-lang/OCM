package dev.opencode.mobile

import android.os.Process as AndroidProcess
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Starts, finds and stops the OpenCode server that runs inside the Linux environment. */
class ServerProcess(private val paths: Paths) {
    @Volatile
    var process: Process? = null
        private set

    /** Where the server log stood when the running attempt started, so only that attempt is inspected on failure. */
    private var logMark: Long = 0

    fun config(prefs: Prefs, profile: Profile, bridgeUrl: String, otaDir: File?): ProotConfig {
        val home = Profiles.homeDir(paths.profilesDir, profile)

        return ProotConfig(
            prootBin = paths.prootBin,
            loader = paths.prootLoader,
            nativeLibDir = paths.nativeLibDir,
            rootfs = paths.rootfs.path,
            home = home.path,
            fakeProc = paths.fakeProc.path,
            prootTmp = paths.prootTmp.path,
            port = profile.port,
            password = prefs.password,
            bridgeUrl = bridgeUrl,
            bridgeToken = prefs.bridgeToken,
            compat = prefs.compat,
            bindSharedStorage = prefs.bindStorage,
            timeZone = java.util.TimeZone.getDefault().id,
            otaBinDir = otaDir?.path,
            zenApiKey = profile.zenApiKey,
        )
    }

    fun spawn(config: ProotConfig): Process {
        val launch = ProotCommand.server(config)

        File(config.home).mkdirs()
        File(config.prootTmp).mkdirs()
        rotateLog()
        logMark = paths.serverLog.length()
        paths.serverLog.appendText("--- start ${java.util.Date()} port=${config.port} compat=${config.compat} bin=${ProotCommand.guestBinary(config)}\n")

        val builder = ProcessBuilder(launch.command)

        builder.environment().putAll(launch.env)
        builder.directory(paths.files)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.serverLog))

        val started = builder.start()

        process = started

        return started
    }

    private fun rotateLog() {
        if (paths.serverLog.exists() && paths.serverLog.length() > 1024 * 1024) {
            val old = File(paths.logs, "server.log.1")

            old.delete()
            paths.serverLog.renameTo(old)
        }
    }

    /** Runs one command in the environment and returns its exit code and output, or null if it did not finish in time. */
    fun run(config: ProotConfig, timeoutSeconds: Long, program: String, vararg args: String): Pair<Int, String>? {
        val launch = ProotCommand.exec(config, program, *args)
        val builder = ProcessBuilder(launch.command)

        builder.environment().putAll(launch.env)
        builder.directory(paths.files)
        builder.redirectErrorStream(true)

        val started = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { started.inputStream.bufferedReader().forEachLine { if (output.length < 16_384) output.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }

        if (!started.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            started.destroyForcibly()
            killOrphans()

            return null
        }

        reader.join(1000)

        return started.exitValue() to output.toString()
    }

    /**
     * HTTP status of the health endpoint, or -1 when nothing answers.
     *
     * Two endpoint names across the upstream lines: /api/health on 1.x, /api/info on 2.x. The 1.x server also serves
     * a catch-all UI route that would answer 200 on any other path, so only these two names are asked and the first
     * one that answers with a valid credential wins; a 404 moves on to the other name.
     */
    fun health(baseUrl: String, password: String): Int {
        val root = Urls.withoutTrailingSlash(baseUrl)

        for (path in listOf("/api/health", "/api/info")) {
            val code = try {
                val connection = URL(root + path).openConnection() as HttpURLConnection

                try {
                    connection.connectTimeout = 1500
                    connection.readTimeout = 4000
                    connection.setRequestProperty("Authorization", "Basic " + Urls.basicCredential(password))
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                -1
            }

            if (code != 404) return code
        }

        return -1
    }

    /** Waits until the server answers 200. Returns false when it dies, or the time runs out. */
    fun waitHealthy(baseUrl: String, password: String, timeoutMs: Long, alive: () -> Boolean, onTick: (Long) -> Unit = {}): Boolean {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (health(baseUrl, password) == 200) return true
            if (!alive()) return false

            onTick(System.currentTimeMillis() - start)
            Thread.sleep(500)
        }

        return false
    }

    /** Process ids of every proot this app started, found through /proc so it also works after the app restarted. */
    fun prootPids(): List<Int> {
        val out = ArrayList<Int>()
        val me = AndroidProcess.myPid()

        for (entry in File("/proc").listFiles().orEmpty()) {
            val pid = entry.name.toIntOrNull() ?: continue

            if (pid == me) continue

            val first = try {
                File(entry, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }

            if (first == paths.prootBin) out += pid
        }

        return out
    }

    /** Kills every proot of ours. The tracees go with it, which stops the server and anything it started. */
    fun killOrphans() {
        for (pid in prootPids()) {
            try {
                AndroidProcess.killProcess(pid)
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }

    fun stop() {
        val p = process

        process = null
        p?.destroy()
        killOrphans()
        p?.let { runCatching { it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } }
        p?.let { if (it.isAlive) it.destroyForcibly() }
    }

    /** Reads why the server died, to tell the person something more useful than "failed". */
    fun failureHint(exitCode: Int?): Pair<String, Boolean> {
        val tail = attemptLog()
        val lower = tail.lowercase()
        val seccomp = exitCode == 159 || "bad system call" in lower || "sigsys" in lower || "seccomp" in lower
        val lines = tail.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")

        return when {
            seccomp -> tr(
                "Система Android завершила процесс из-за системного вызова. Включите режим совместимости в настройках и повторите.",
                "Android stopped the process over a system call. Turn on compatibility mode in settings and try again.",
            ) to true
            "address already in use" in lower || "eaddrinuse" in lower -> tr(
                "Порт сервера уже занят. Перезапустите сервер из настроек.",
                "The server port is already in use. Restart the server from settings.",
            ) to false
            else -> lines.ifEmpty { tr("Сервер не ответил вовремя", "The server did not answer in time") } to false
        }
    }

    /** What the current attempt printed, so an earlier attempt in the same log cannot be mistaken for it. */
    private fun attemptLog(): String = Log.since(paths.serverLog, logMark, 6 * 1024)

    /** True when the current attempt died because its port was already in use by something else on the phone. */
    fun portWasBusy(): Boolean {
        val lower = attemptLog().lowercase()

        return "address already in use" in lower || "eaddrinuse" in lower
    }
}
