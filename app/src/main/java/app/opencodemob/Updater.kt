package app.opencodemob

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA: the app pulls the newest APK from our GitHub Releases, like
 * `pkg upgrade` in Termux. There is no "core" anymore — the AI stack lives on
 * opencode.ai, not inside the APK.
 */
object Updater {
    data class AppUpdate(val name: String, val code: Int, val url: String)

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "OpenCode-Mobile")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        return c
    }

    fun latestApp(): AppUpdate? {
        val j = JSONObject(open("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest").inputStream.bufferedReader().use { it.readText() })
        val assets = j.getJSONArray("assets")
        val re = Regex("""OpenCode-([\d.]+)-(\d+)\.apk""")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val m = re.matchEntire(a.getString("name")) ?: continue
            return AppUpdate(m.groupValues[1], m.groupValues[2].toInt(), a.getString("browser_download_url"))
        }
        return null
    }

    private fun fetchTo(url: String, dst: File, progress: (Int) -> Unit) {
        val con = open(url)
        val total = con.contentLengthLong.coerceAtLeast(1)
        var done = 0L; var last = -1
        con.inputStream.use { inp ->
            dst.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n); done += n
                    val p = (done * 100 / total).toInt().coerceIn(0, 100)
                    if (p != last) { last = p; progress(p) }
                }
            }
        }
    }

    fun downloadApp(c: Context, u: AppUpdate, progress: (Int) -> Unit): File {
        val f = File(File(c.cacheDir, "updates").apply { mkdirs() }, "update.apk")
        fetchTo(u.url, f, progress)
        return f
    }

    fun installApk(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
