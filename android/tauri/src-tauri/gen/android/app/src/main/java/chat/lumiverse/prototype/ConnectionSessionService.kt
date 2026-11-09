package chat.lumiverse.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Protects the WebView process and its bound local runtime for a user-opened connection.
 * RuntimeService lives in :runtime; promoting that process alone cannot protect TTS or JS. */
class ConnectionSessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wake: PowerManager.WakeLock
    private val renew = object : Runnable {
        override fun run() {
            wake.acquire(10 * 60 * 1000L)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active connections", NotificationManager.IMPORTANCE_LOW))
        wake = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Lumiverse::ConnectionSession").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE_SESSION) {
            active = false
            onClose?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val close = PendingIntent.getService(this, 11, Intent(this, ConnectionSessionService::class.java).setAction(CLOSE_SESSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_lumiverse)
            .setContentTitle("Lumiverse connection active")
            .setContentText("Keeping your connection and audio active in the background.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Close app", close).build()).build()
        try {
            startForeground(NOTIFICATION, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            handler.removeCallbacks(renew)
            renew.run()
        } catch (error: RuntimeException) {
            active = false
            onFailure?.invoke("Android could not protect the connection in the background: ${error.message}")
            stopSelf()
        }
        // A reclaimed UI cannot be reconstructed by restarting an empty service.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        if (wake.isHeld) wake.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "lumiverse-active-connections"
        private const val NOTIFICATION = 23019
        private const val CLOSE_SESSION = "chat.lumiverse.prototype.CLOSE_SESSION"
        var active = false
            private set
        var onFailure: ((String) -> Unit)? = null
        var onClose: (() -> Unit)? = null
        fun start(context: Context) {
            if (active) return
            ContextCompat.startForegroundService(context, Intent(context, ConnectionSessionService::class.java))
            active = true
        }
        fun stop(context: Context) {
            active = false
            context.stopService(Intent(context, ConnectionSessionService::class.java))
        }
    }
}
