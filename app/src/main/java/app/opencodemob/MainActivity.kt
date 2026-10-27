package app.opencodemob

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * One WebView with the bundled chat UI (assets/ui). The UI is static assets
 * only; every network call to opencode.ai goes through ZenClient so no CORS
 * applies and the identity headers stay out of the web sandbox.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var fileCb: android.webkit.ValueCallback<Array<Uri>>? = null

    private val loader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        emit("perms", perms())
    }
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); fileCb = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF131010.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        web = WebView(this).apply {
            setBackgroundColor(0xFF131010.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(r.url)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.host == "appassets.androidplatform.net") { v.loadUrl(u.toString()); return true }
                    openExternal(u); return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(w: WebView, cb: android.webkit.ValueCallback<Array<Uri>>, p: android.webkit.WebChromeClient.FileChooserParams): Boolean {
                    fileCb?.onReceiveValue(null); fileCb = cb
                    return try { chooser.launch(p.createIntent()); true } catch (_: Exception) { fileCb = null; false }
                }
            }
            addJavascriptInterface(Bridge(), "OC")
            loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("(window.OCM&&OCM.back&&OCM.back())===true") { r -> if (r != "true") moveTaskToBack(true) }
        }
        // keep the process warm in the background so an ongoing stream survives
        if (Prefs.get(this, "uid").isEmpty()) Prefs.put(this, "uid", "local")
        startService(Intent(this, OpenCodeService::class.java))
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openExternal(u: Uri) {
        if (u.scheme in listOf("http", "https", "tg")) try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
    }

    fun emit(name: String, data: JSONObject) {
        runOnUiThread { web.evaluateJavascript("window.OCEvent&&OCEvent(${JSONObject.quote(name)},$data)", null) }
    }

    private fun perms() = JSONObject().apply {
        put("notifications", Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        put("battery", (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName))
    }

    inner class Bridge {
        @JavascriptInterface fun state(): String = JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME); put("code", BuildConfig.VERSION_CODE)
            put("model", Prefs.get(this@MainActivity, "model", ZenModels.DEFAULT_MODEL))
            put("hasKey", Prefs.get(this@MainActivity, "zenKey").isNotEmpty())
            put("perms", perms())
        }.toString()

        /** Starts a streaming chat: {id, model, messages, key?}. Events: chat{name}. */
        @JavascriptInterface fun chat(payload: String) { ZenClient.chat(this@MainActivity, payload) }

        @JavascriptInterface fun chatCancel(id: String) { ZenClient.cancel(id) }

        /** Model catalog (cached after the first call); emitted as models{list}. */
        @JavascriptInterface fun models() {
            thread {
                try { emit("models", JSONObject().put("list", ZenModels.list(this@MainActivity))) }
                catch (e: Exception) { emit("models", JSONObject().put("error", e.message ?: "failed")) }
            }
        }

        @JavascriptInterface fun setModel(model: String) {
            Prefs.put(this@MainActivity, "model", model)
        }

        @JavascriptInterface fun setKey(key: String) {
            Prefs.put(this@MainActivity, "zenKey", key.trim())
        }

        @JavascriptInterface fun getKey(): String = Prefs.get(this@MainActivity, "zenKey")

        @JavascriptInterface fun minimize() { runOnUiThread { moveTaskToBack(true) } }

        @JavascriptInterface fun grant(kind: String) {
            runOnUiThread {
                val pkg = Uri.parse("package:$packageName")
                when (kind) {
                    "notifications" -> if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    "battery" -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                }
            }
        }

        @JavascriptInterface fun testSound(kind: String) { Notifier.beep(kind) }

        // --- clipboard / share / links ---
        @JavascriptInterface fun copy(text: String) {
            runOnUiThread { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenCode", text)) }
        }

        @JavascriptInterface fun pasteText(): String = try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        } catch (_: Exception) { "" }

        @JavascriptInterface fun share(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(400_000))
                startActivity(Intent.createChooser(send, null))
            }
        }

        @JavascriptInterface fun openLink(url: String) { runOnUiThread { openExternal(Uri.parse(url)) } }

        // --- OTA ---
        @JavascriptInterface fun checkUpdates() {
            thread {
                val o = JSONObject()
                try {
                    val a = Updater.latestApp()
                    if (a != null && a.code > BuildConfig.VERSION_CODE) o.put("app", JSONObject().put("name", a.name))
                } catch (e: Exception) { o.put("error", e.message ?: "offline") }
                emit("update", o)
            }
        }

        @JavascriptInterface fun applyAppUpdate() {
            thread {
                try {
                    if (!packageManager.canRequestPackageInstalls()) {
                        grantInstall(); emit("update", JSONObject().put("error", "Разрешите установку приложений и повторите")); return@thread
                    }
                    val a = Updater.latestApp() ?: throw IllegalStateException("Релиз не найден")
                    val f = Updater.downloadApp(this@MainActivity, a) { emit("update", JSONObject().put("pct", it).put("what", "app")) }
                    runOnUiThread { Updater.installApk(this@MainActivity, f) }
                } catch (e: Exception) { emit("update", JSONObject().put("error", e.message ?: "failed")) }
            }
        }

        @JavascriptInterface fun grantInstall() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
        }
    }
}
