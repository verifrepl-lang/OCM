package app.opencodemob

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/**
 * Lightweight foreground service: keeps the process alive in the background so
 * an in-flight chat stream is not frozen mid-word, and shows the runtime
 * notification. There is no local server anymore — the chat talks to
 * opencode.ai directly through ZenClient.
 */
class OpenCodeService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        Notifier.ensureChannels(this)
        ServiceCompat.startForeground(
            this, 1, Notifier.runtime(this, "Готова к работе"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:chat").apply { acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        super.onDestroy()
    }
}
