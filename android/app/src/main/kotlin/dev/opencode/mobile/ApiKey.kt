package dev.opencode.mobile

import android.app.Activity

/**
 * Connecting and disconnecting the profile's own Zen API key from anywhere: the floating menu, the profile menu in
 * settings, the login screen. One implementation so all entrances behave the same: the key lands on the profile, the
 * server restarts and from then on every request of the profile goes under this personal credential.
 */
object ApiKey {
    /**
     * Asks for the profile's own Zen API key and applies it: personal quota without a Console sign-in. With
     * [close] the calling screen finishes afterwards - the login screen has nothing left to offer once the key is in.
     */
    fun ask(activity: Activity, prefill: String = "", close: Boolean = false) {
        val app = activity.application as App

        Ui.input(
            activity,
            tr("Персональный API-ключ профиля", "The profile's personal API key"),
            tr("Ключ из консоли OpenCode", "A key from the OpenCode console"),
            prefill,
            password = true,
        ) { raw ->
            val key = raw.trim()

            if (key.isNotEmpty()) {
                val id = app.profiles.active.id

                // Every request of this profile now goes under this key: the server gets it as OPENCODE_API_KEY on the
                // next start, which replaces the shared anonymous token (verified live on 2.1.4: auth errors instead of
                // the shared free-limit error).
                app.profiles.setZenApiKey(id, key)
                app.guest.markPersonal(id, tr("личный API-ключ", "personal API key"))
                app.prefs.signedIn = true
                UiBus.reloadRequested = true
                ServerService.send(activity, ServerService.ACTION_RESTART)
                Ui.toast(activity, tr("Личный ключ подключён", "Personal key connected"))

                if (close) activity.finish()
            }
        }
    }

    /** Disconnects the key: the profile goes back to the shared anonymous limit. Applies on the next server start. */
    fun remove(activity: Activity) {
        val app = activity.application as App
        val id = app.profiles.active.id

        app.profiles.setZenApiKey(id, null)
        app.guest.markAnonymous(id)
        UiBus.reloadRequested = true
        ServerService.send(activity, ServerService.ACTION_RESTART)
        Ui.toast(activity, tr("Ключ убран: снова общий лимит", "Key removed: back to the shared limit"))
    }
}
