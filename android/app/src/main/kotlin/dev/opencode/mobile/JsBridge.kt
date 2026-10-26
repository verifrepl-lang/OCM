package dev.opencode.mobile

import android.webkit.JavascriptInterface

/**
 * The object the injected bridge.js talks to, exposed to the web UI as `OpenCodeAndroid`. It is only ever attached to the
 * app's own page, never to preview windows.
 */
class JsBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun saveBegin(name: String, mime: String): String = Downloads.begin(activity, name, mime) ?: ""

    @JavascriptInterface
    fun saveChunk(id: String, base64: String): Boolean = Downloads.chunk(id, base64)

    @JavascriptInterface
    fun saveEnd(id: String) {
        val where = Downloads.end(id)

        activity.runOnUiThread {
            Ui.toast(
                activity,
                if (where != null) tr("Сохранено: ", "Saved: ") + where else tr("Не удалось сохранить файл", "Could not save the file"),
            )
        }
    }

    @JavascriptInterface
    fun saveAbort(id: String) = Downloads.abort(id)

    @JavascriptInterface
    fun setBars(dark: Boolean, color: String) = activity.runOnUiThread { activity.applyBars(dark, color) }

    @JavascriptInterface
    fun terminalFocus(focused: Boolean) = activity.runOnUiThread { activity.keys.setTerminalFocus(focused) }

    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread { Browser.copy(activity, "OpenCode", text) }
    }

    /**
     * The page hit a limit error: rebuild the WARP tunnel on a fresh identity so Cloudflare hands out a different
     * egress address, which is what the shared anonymous free limits are counted against. Without a running tunnel
     * (WARP off) there is nothing to refresh and the page just shows its hint as before. The result is a toast.
     */
    @JavascriptInterface
    fun rotateWarp() {
        val app = activity.applicationContext as App

        if (!app.warp.listening()) return

        app.warp.rotate("page limit error") { ok ->
            activity.runOnUiThread {
                Ui.toast(
                    activity,
                    if (ok) {
                        tr("WARP: адрес сменён, отправьте запрос ещё раз", "WARP: the address changed, send the request again")
                    } else {
                        tr("WARP не смог сменить адрес", "WARP could not change the address")
                    },
                )
            }
        }
    }

    @JavascriptInterface
    fun openPreview(url: String) {
        activity.runOnUiThread { activity.showPreview(url) }
    }

    /**
     * True when this profile itself runs on a personal credential - a Console account or the profile's own
     * API key - so the web UI must not offer the sign-in again under a limit error. Strictly per profile: the
     * old global "signed in once on this install" flag made every other profile claim a personal quota it did
     * not have. Read on the WebView's thread: both sources are cheap.
     */
    @JavascriptInterface
    fun hasAccount(): Boolean {
        val app = activity.applicationContext as App

        return app.guest.isPersonal(app.profiles.active.id) || app.profiles.active.account != null
    }

    /**
     * The guest-account state of the active profile: `personal` when it runs its own Console account or API
     * key, `anonymous` while its requests fall into the world-shared free bucket. Lets the page name the real
     * reason under a limit error instead of guessing. Older app builds without the method read as `anonymous`.
     */
    @JavascriptInterface
    fun guestState(): String = if (hasAccount()) "personal" else "anonymous"

    /**
     * The email of the Console account the active profile is signed in with, or an empty string. The web UI shows it
     * in a small chip, so the sign-in state is visible instead of something only limit errors talk about. Read on
     * the WebView's thread: the profiles list is an in-memory read.
     */
    @JavascriptInterface
    fun accountLabel(): String {
        val app = activity.applicationContext as App

        return app.profiles.active.account ?: ""
    }

    /** The sign-in offer shown instead of a raw quota error opens the native sign-in screen. */
    @JavascriptInterface
    fun openLogin() {
        activity.runOnUiThread {
            try {
                activity.startActivity(android.content.Intent(activity, LoginActivity::class.java))
            } catch (e: Exception) {
                Log.w("bridge", "could not open the sign-in: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        activity.runOnUiThread { activity.openExternal(url) }
    }

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME
}
