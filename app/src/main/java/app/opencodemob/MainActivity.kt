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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * One WebView with the bundled OpenCode UI (assets/ui). It only ever loads our own assets;
 * every other link opens in the system browser. All talking to the local server goes through Net.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var pendingKind: String? = null
    private var pendingDir = ""
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var lastReady = false
    private var lastErr: String? = null

    private val loader by lazy {
        val resp = File(cacheDir, "resp").apply { mkdirs() }
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/_resp/", WebViewAssetLoader.InternalStoragePathHandler(this, resp))
            .build()
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { onPicked(it) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { emit("perms", JSONObject()) }
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
                    openExternal(r.url); return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
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
        watch()
    }

    override fun onResume() { super.onResume(); emit("perms", JSONObject()) }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        Net.sseStop()
        web.destroy()
        super.onDestroy()
    }

    private fun openExternal(u: Uri) {
        if (u.scheme in listOf("http", "https", "tg")) try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
    }

    fun emit(name: String, data: JSONObject) {
        runOnUiThread { web.evaluateJavascript("window.OCEvent&&OCEvent(${JSONObject.quote(name)},$data)", null) }
    }

    /** Tells the UI when the server comes up or goes down. */
    private fun watch() {
        val r = ServerState.ready
        if (r != lastReady) {
            lastReady = r
            emit("server", JSONObject().put("ready", r))
            if (r) emit("ready", JSONObject())
        }
        val e = ServerState.error
        if (!r && e != null && e != lastErr) { lastErr = e; emit("boot", JSONObject().put("error", e)) }
        if (e == null) lastErr = null
        ui.postDelayed({ watch() }, 1000)
    }

    private fun uid() = Prefs.get(this, "uid")

    private fun perms() = JSONObject().apply {
        put("notifications", Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        put("files", Environment.isExternalStorageManager())
        put("install", packageManager.canRequestPackageInstalls())
        put("battery", (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName))
    }

    private fun flags() = JSONObject().apply {
        for (k in FLAG_KEYS) put(k, Prefs.flag(this@MainActivity, k, k != "noseccomp"))
    }

    private fun cfgFile(): File {
        val dir = File(Runtime.home(this, uid()), ".config/opencode")
        val jsonc = File(dir, "opencode.jsonc")
        return if (jsonc.exists()) jsonc else File(dir, "opencode.json")
    }

    // ---------- Google sign-in ----------

    private fun doSignIn() {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            Prefs.put(this, "uid", "local"); Prefs.put(this, "email", "local profile"); Prefs.put(this, "name", "Local")
            emit("signin", JSONObject().put("ok", true).put("fallback", true)); return
        }
        val opt = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()
        val req = GetCredentialRequest.Builder().addCredentialOption(opt).build()
        lifecycleScope.launch {
            try {
                val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, req).credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val g = GoogleIdTokenCredential.createFrom(cred.data)
                    val sub = JSONObject(String(Base64.decode(g.idToken.split(".")[1], Base64.URL_SAFE))).getString("sub")
                    // one private folder per Google account; the hash cannot be reversed to the account id
                    val uid = MessageDigest.getInstance("SHA-256").digest(sub.toByteArray())
                        .joinToString("") { "%02x".format(it) }.take(24)
                    Prefs.put(this@MainActivity, "uid", uid)
                    Prefs.put(this@MainActivity, "email", g.id)
                    Prefs.put(this@MainActivity, "name", g.displayName ?: g.id)
                    emit("signin", JSONObject().put("ok", true))
                } else emit("signin", JSONObject().put("ok", false).put("error", "Unsupported credential"))
            } catch (e: Exception) {
                emit("signin", JSONObject().put("ok", false).put("error", e.message ?: "Sign-in cancelled"))
            }
        }
    }

    // ---------- uploads: AGENTS.md, agents, commands, plugins ----------

    private fun onPicked(uri: Uri?) {
        val kind = pendingKind ?: return
        pendingKind = null
        if (uri == null) return
        thread {
            try {
                val raw = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file.md"
                var name = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val cfg = File(Runtime.home(this, uid()), ".config/opencode")
                val dest = when (kind) {
                    "rules" -> File(cfg, "AGENTS.md")
                    "project" -> File(Runtime.hostPath(this, uid(), pendingDir.ifEmpty { "/root" }), "AGENTS.md")
                    "plugin" -> File(File(cfg, "plugins"), name)
                    else -> {
                        if (!name.endsWith(".md")) name += ".md"
                        File(File(cfg, if (kind == "command") "commands" else "agents"), name)
                    }
                }
                dest.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
                emit("picked", JSONObject().put("ok", true).put("file", dest.name).put("kind", kind))
            } catch (e: Exception) {
                emit("picked", JSONObject().put("ok", false).put("error", e.message ?: "failed"))
            }
        }
    }

    // ---------- JS bridge ----------

    inner class Bridge {
        @JavascriptInterface fun state(): String = JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME); put("code", BuildConfig.VERSION_CODE)
            put("user", Prefs.get(this@MainActivity, "email")); put("name", Prefs.get(this@MainActivity, "name"))
            put("googleConfigured", BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty())
            put("installed", Runtime.isInstalled(this@MainActivity))
            put("core", Updater.installedCore(this@MainActivity))
            put("perms", perms()); put("flags", flags()); put("ready", ServerState.ready)
        }.toString()

        // --- account / lifecycle ---
        @JavascriptInterface fun signIn() { runOnUiThread { doSignIn() } }

        @JavascriptInterface fun signOut() {
            runOnUiThread {
                Net.sseStop()
                stopService(Intent(this@MainActivity, OpenCodeService::class.java))
                Prefs.put(this@MainActivity, "uid", ""); Prefs.put(this@MainActivity, "email", ""); Prefs.put(this@MainActivity, "name", "")
                lifecycleScope.launch {
                    try { CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest()) } catch (_: Exception) {}
                    emit("signedout", JSONObject())
                }
            }
        }

        @JavascriptInterface fun install() {
            thread {
                try {
                    Runtime.install(this@MainActivity) { emit("install", JSONObject().put("pct", it)) }
                    emit("install", JSONObject().put("done", true))
                } catch (e: Throwable) {
                    emit("install", JSONObject().put("error", e.message ?: e.toString()))
                }
            }
        }

        @JavascriptInterface fun start() {
            runOnUiThread {
                val uid = uid()
                if (uid.isEmpty()) return@runOnUiThread
                ServerState.error = null; lastErr = null
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, OpenCodeService::class.java).putExtra("uid", uid))
                if (ServerState.ready) emit("ready", JSONObject())
            }
        }

        @JavascriptInterface fun restartServer() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, OpenCodeService::class.java))
                ServerState.ready = false
                ui.postDelayed({ start() }, 700)
            }
        }

        @JavascriptInterface fun minimize() { runOnUiThread { moveTaskToBack(true) } }

        @JavascriptInterface fun grant(kind: String) {
            runOnUiThread {
                val pkg = Uri.parse("package:$packageName")
                when (kind) {
                    "notifications" -> if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    "files" -> startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
                    "install" -> startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
                    "battery" -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                }
            }
        }

        @JavascriptInterface fun setFlag(key: String, value: Boolean) {
            if (key in FLAG_KEYS) Prefs.putFlag(this@MainActivity, key, value)
        }

        @JavascriptInterface fun testSound(kind: String) { Notifier.beep(kind) }

        // --- network (local server) ---
        @JavascriptInterface fun http(id: String, method: String, path: String, body: String) {
            Net.request(this@MainActivity, id, method, path, body) { n, d -> emit(n, d) }
        }
        @JavascriptInterface fun drop(id: String) { Net.drop(this@MainActivity, id) }
        @JavascriptInterface fun sseStart() { Net.sseStart { n, d -> emit(n, d) } }
        @JavascriptInterface fun sseStop() { Net.sseStop() }
        @JavascriptInterface fun wsOpen(id: String, path: String) { Net.wsOpen(id, path) { n, d -> emit(n, d) } }
        @JavascriptInterface fun wsSend(id: String, text: String) { Net.wsSend(id, text) }
        @JavascriptInterface fun wsClose(id: String) { Net.wsClose(id) }

        // --- files (paths as the agent sees them) ---
        @JavascriptInterface fun ls(path: String): String = Runtime.ls(this@MainActivity, uid(), path).toString()
        @JavascriptInterface fun fs(op: String, path: String, arg: String): String = Runtime.fs(this@MainActivity, uid(), op, path, arg).toString()

        @JavascriptInterface fun readText(path: String): String = try {
            val f = Runtime.hostPath(this@MainActivity, uid(), path)
            if (!f.isFile) JSONObject().put("ok", false).put("err", "Not a file").toString()
            else if (f.length() > 2_000_000) JSONObject().put("ok", false).put("err", "File is too large").toString()
            else JSONObject().put("ok", true).put("text", f.readText()).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("err", e.message ?: "failed").toString() }

        @JavascriptInterface fun writeText(path: String, text: String): String = try {
            val f = Runtime.hostPath(this@MainActivity, uid(), path)
            f.parentFile?.mkdirs(); f.writeText(text)
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("err", e.message ?: "failed").toString() }

        @JavascriptInterface fun cfgRead(): String = try { cfgFile().takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }

        @JavascriptInterface fun cfgWrite(text: String): String = try {
            val f = cfgFile(); f.parentFile?.mkdirs(); f.writeText(text)
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("err", e.message ?: "failed").toString() }

        @JavascriptInterface fun readLog(): String = try { File(filesDir, "server.log").readText().takeLast(60_000) } catch (_: Exception) { "" }

        @JavascriptInterface fun pickFile(kind: String, dir: String) {
            runOnUiThread { pendingKind = kind; pendingDir = dir; picker.launch(arrayOf("*/*")) }
        }

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
                try {
                    val t = Updater.latestCore()
                    if (t != null && t != Updater.installedCore(this@MainActivity)) o.put("core", t)
                } catch (e: Exception) { o.put("error", e.message ?: "offline") }
                emit("update", o)
            }
        }

        @JavascriptInterface fun applyAppUpdate() {
            thread {
                try {
                    if (!packageManager.canRequestPackageInstalls()) {
                        grant("install"); emit("update", JSONObject().put("error", "Allow installing apps, then try again")); return@thread
                    }
                    val a = Updater.latestApp() ?: throw IllegalStateException("No release found")
                    val f = Updater.downloadApp(this@MainActivity, a) { emit("update", JSONObject().put("pct", it).put("what", "app")) }
                    runOnUiThread { Updater.installApk(this@MainActivity, f) }
                } catch (e: Exception) { emit("update", JSONObject().put("error", e.message ?: "failed")) }
            }
        }

        @JavascriptInterface fun applyCoreUpdate() {
            thread {
                try {
                    val tag = Updater.latestCore() ?: throw IllegalStateException("No 1.x release found")
                    Updater.updateCore(this@MainActivity, tag) { emit("update", JSONObject().put("pct", it).put("what", "core")) }
                    emit("update", JSONObject().put("coreDone", tag))
                    restartServer()
                } catch (e: Exception) { emit("update", JSONObject().put("error", e.message ?: "failed")) }
            }
        }
    }

    companion object {
        val FLAG_KEYS = listOf("snd_done", "snd_action", "snd_quota", "noseccomp")
    }
}
