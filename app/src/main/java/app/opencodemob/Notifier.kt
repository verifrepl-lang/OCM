package app.opencodemob

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val DONE = "done"
    const val ACTION = "action"
    const val QUOTA = "quota"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("runtime", "Фоновая служба", NotificationManager.IMPORTANCE_LOW))
        listOf(DONE to "Задача завершена", ACTION to "Требуется действие", QUOTA to "Квота исчерпана").forEach { (id, name) ->
            // Sound is played by Notifier.beep so each event has its own pattern and one on/off switch
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        }
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun runtime(c: Context, text: String): Notification =
        NotificationCompat.Builder(c, "runtime")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OpenCode")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open(c))
            .build()

    fun updateRuntime(c: Context, text: String) {
        try { NotificationManagerCompat.from(c).notify(1, runtime(c, text)) } catch (_: SecurityException) {}
    }

    fun event(c: Context, kind: String, title: String, text: String) {
        if (!Prefs.flag(c, "snd_$kind")) return
        beep(kind)
        val n = NotificationCompat.Builder(c, kind)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(c))
            .build()
        try { NotificationManagerCompat.from(c).notify(10 + kind.hashCode() % 1000, n) } catch (_: SecurityException) {}
    }

    fun beep(kind: String) {
        Thread {
            val g = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (_: Exception) { return@Thread }
            val seq = when (kind) {
                DONE -> listOf(ToneGenerator.TONE_PROP_BEEP to 150, ToneGenerator.TONE_PROP_ACK to 250)
                ACTION -> List(3) { ToneGenerator.TONE_PROP_PROMPT to 200 }
                else -> listOf(ToneGenerator.TONE_SUP_ERROR to 600)
            }
            for ((tone, ms) in seq) { g.startTone(tone, ms); Thread.sleep(ms + 120L) }
            g.release()
        }.start()
    }
}
