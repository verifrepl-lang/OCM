package app.opencodemob

import android.content.Context
import android.system.Os
import android.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun flag(c: Context, k: String, d: Boolean = true): Boolean = sp(c).getBoolean(k, d)
    fun putFlag(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/** Shared between the service (writer) and the activity (reader). */
object ServerState {
    @Volatile var port = 0
    @Volatile var password = ""
    @Volatile var ready = false
    @Volatile var error: String? = null
}

object Runtime {
    fun rootfs(c: Context) = File(c.filesDir, "rootfs")

    /** Every Google account gets its own HOME: sessions, auth.json, config. Nothing is shared. */
    fun home(c: Context, uid: String): File = File(c.filesDir, "users/$uid/home").apply { mkdirs() }

    /** Maps a path as the agent sees it (inside proot) to the real file on the phone. */
    fun hostPath(c: Context, uid: String, guest: String): File {
        val g = File("/", guest).normalize().path
        return when {
            g == "/root" || g.startsWith("/root/") -> File(home(c, uid), g.removePrefix("/root").removePrefix("/"))
            g == "/sdcard" || g.startsWith("/sdcard/") -> File("/storage/emulated/0", g.removePrefix("/sdcard").removePrefix("/"))
            g == "/storage/emulated/0" || g.startsWith("/storage/emulated/0/") -> File(g)
            else -> File(rootfs(c), g.removePrefix("/"))
        }
    }

    fun ls(c: Context, uid: String, guest: String): org.json.JSONObject {
        val l = hostPath(c, uid, guest).listFiles()
            ?: return org.json.JSONObject().put("ok", false).put("err", "Cannot read $guest")
        val arr = org.json.JSONArray()
        l.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach {
            arr.put(org.json.JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", it.length()))
        }
        return org.json.JSONObject().put("ok", true).put("path", guest).put("items", arr)
    }

    fun fs(c: Context, uid: String, op: String, guest: String, arg: String): org.json.JSONObject {
        val r = org.json.JSONObject()
        return try {
            val f = hostPath(c, uid, guest)
            when (op) {
                "mkdir" -> check(f.mkdirs() || f.isDirectory) { "Cannot create folder" }
                "create" -> { f.parentFile?.mkdirs(); check(f.createNewFile() || f.exists()) { "Cannot create file" } }
                "rename" -> {
                    val dst = if (arg.startsWith("/")) hostPath(c, uid, arg) else File(f.parentFile, arg)
                    check(f.renameTo(dst)) { "Cannot rename" }
                }
                "delete" -> rmrf(f)
                else -> error("Unknown operation")
            }
            r.put("ok", true)
        } catch (e: Exception) { r.put("ok", false).put("err", e.message ?: e.toString()) }
    }

    fun isInstalled(c: Context): Boolean {
        val stamp = File(rootfs(c), ".stamp")
        return stamp.exists() && stamp.readText().trim() == assetId(c)
    }

    private fun assetId(c: Context): String =
        c.assets.open("rootfs.id").bufferedReader().use { it.readText().trim() }

    // ---------- install (xz -> rootfs), progress 0..100 ----------

    private class Counting(inp: InputStream) : FilterInputStream(inp) {
        var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) n++ }
        override fun read(b: ByteArray, o: Int, l: Int): Int = super.read(b, o, l).also { if (it > 0) n += it }
    }

    fun install(c: Context, progress: (Int) -> Unit) {
        val id = assetId(c)
        val tmp = File(c.filesDir, "rootfs.new")
        rmrf(tmp); tmp.mkdirs()
        val total = c.assets.openFd("rootfs.tar.xz").use { it.length }.coerceAtLeast(1)
        val cnt = Counting(c.assets.open("rootfs.tar.xz"))
        var last = -1
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(cnt, 1 shl 16))).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                extract(tmp, tar, e as TarArchiveEntry)
                val pct = (cnt.n * 100 / total).toInt().coerceIn(0, 99)
                if (pct != last) { last = pct; progress(pct) }
                e = tar.nextEntry
            }
        }
        File(tmp, "etc/resolv.conf").apply { quietDelete(this); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(tmp, "etc/hosts").apply { quietDelete(this); writeText("127.0.0.1 localhost\n::1 localhost\n") }
        File(tmp, "tmp").apply { mkdirs(); Os.chmod(path, 0x3FF) } // 1777
        File(tmp, "root").mkdirs()
        File(tmp, ".stamp").writeText(id)
        rmrf(rootfs(c))
        if (!tmp.renameTo(rootfs(c))) throw IOException("cannot move rootfs into place")
        progress(100)
    }

    private fun extract(root: File, tar: TarArchiveInputStream, e: TarArchiveEntry) {
        val name = e.name.removePrefix("./").trimEnd('/')
        if (name.isEmpty() || name.split('/').any { it == ".." }) return
        val f = File(root, name)
        when {
            e.isDirectory -> { f.mkdirs(); Os.chmod(f.path, (e.mode and 0x1FF) or 0x1C0) }
            e.isSymbolicLink -> { f.parentFile?.mkdirs(); quietDelete(f); Os.symlink(e.linkName, f.path) }
            e.isLink -> {
                f.parentFile?.mkdirs(); quietDelete(f)
                val target = File(root, e.linkName.removePrefix("./"))
                try { Os.link(target.path, f.path) } catch (_: Exception) { target.copyTo(f, true) }
            }
            e.isFile -> {
                f.parentFile?.mkdirs(); quietDelete(f)
                FileOutputStream(f).use { tar.copyTo(it) }
                Os.chmod(f.path, (e.mode and 0x1FF) or 0x180)
            }
            else -> Unit // devices/fifos are provided by proot binds
        }
    }

    private fun quietDelete(f: File) { try { Files.deleteIfExists(f.toPath()) } catch (_: Exception) {} }

    fun rmrf(f: File) {
        val p = f.toPath()
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, e: IOException?): FileVisitResult {
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }

    // ---------- per-install port + password (never shared between users or phones) ----------

    /** Random port chosen once per install and kept, so the WebView origin (localStorage, IndexedDB) stays stable. */
    fun stablePort(c: Context): Int {
        val saved = Prefs.get(c, "port").toIntOrNull()
        if (saved != null) {
            try { ServerSocket(saved, 0, java.net.InetAddress.getByName("127.0.0.1")).close(); return saved } catch (_: Exception) {}
        }
        val p = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        Prefs.put(c, "port", p.toString())
        return p
    }

    fun stablePassword(c: Context): String {
        var pw = Prefs.get(c, "pw")
        if (pw.isEmpty()) {
            pw = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            Prefs.put(c, "pw", pw)
        }
        return pw
    }

    fun basic(pw: String) = "Basic " + Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)

    // ---------- launch: proot -> opencode serve (1.x) ----------

    /** Common proot invocation (same binds as the server) for a one-shot command like `opencode console login`. */
    private fun prootBase(c: Context, uid: String): MutableList<String> {
        val lib = c.applicationInfo.nativeLibraryDir
        val home = home(c, uid)
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }
        val a = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
            "-r", rootfs(c).path, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${home.path}:/root",
        )
        if (File("/storage/emulated/0").exists()) {
            a += listOf("-b", "/storage/emulated/0:/storage/emulated/0", "-b", "/storage/emulated/0:/sdcard")
        }
        return a
    }

    /** Runs a short-lived command inside the proot env with merged piped output (for `opencode console login/logout`). */
    fun prootCmd(c: Context, uid: String, cmd: List<String>): Process {
        val lib = c.applicationInfo.nativeLibraryDir
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }
        val a = prootBase(c, uid) + cmd
        val pb = ProcessBuilder(a).redirectErrorStream(true).directory(c.filesDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", lib)
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", ptmp.path)
            put("HOME", "/root"); put("USER", "root"); put("SHELL", "/bin/bash")
            put("LANG", "C.UTF-8"); put("TERM", "dumb"); put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("OPENCODE_DISABLE_AUTOUPDATE", "1")
        }
        return pb.start()
    }

    fun startServer(c: Context, uid: String, port: Int, password: String, noSeccomp: Boolean): Process {
        val lib = c.applicationInfo.nativeLibraryDir
        val root = rootfs(c)
        val home = home(c, uid)
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }

        val a = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
            "-r", root.path, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${home.path}:/root",
        )
        if (File("/storage/emulated/0").exists()) {
            a += listOf("-b", "/storage/emulated/0:/storage/emulated/0", "-b", "/storage/emulated/0:/sdcard")
        }
        a += listOf("/usr/local/bin/opencode", "serve", "--hostname", "127.0.0.1", "--port", port.toString())

        val pb = ProcessBuilder(a).redirectErrorStream(true).directory(c.filesDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", lib)
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", ptmp.path)
            if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
            put("HOME", "/root"); put("USER", "root"); put("SHELL", "/bin/bash")
            put("LANG", "C.UTF-8"); put("TERM", "xterm-256color"); put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("OPENCODE_SERVER_USERNAME", "opencode")
            put("OPENCODE_SERVER_PASSWORD", password)
            put("OPENCODE_DISABLE_AUTOUPDATE", "1") // stay on 1.x: no `upgrade --major` behind our back
        }
        return pb.start()
    }

    fun waitReady(port: Int, password: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                val con = URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection
                con.connectTimeout = 800; con.readTimeout = 1500
                con.setRequestProperty("Authorization", basic(password))
                val code = con.responseCode
                con.disconnect()
                if (code in 200..499) return true
            } catch (_: Exception) {}
            Thread.sleep(500)
        }
        return false
    }
}
