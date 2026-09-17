package lab.arl.target.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import lab.arl.target.R
import lab.arl.target.media.WebrtcDiag
import lab.arl.target.presentation.MainActivity

class TargetForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val sessionActive = intent?.getBooleanExtra(EXTRA_SESSION, false) == true
        val projectionReady = intent?.getBooleanExtra(EXTRA_PROJECTION, false) == true
        val notification = buildNotification(sessionActive)
        enterForeground(sessionActive, projectionReady, notification)
        WebrtcDiag.log(
            "foreground_service_started",
            extra = "session=$sessionActive projection=$projectionReady"
        )
        return START_NOT_STICKY
    }

    private fun enterForeground(sessionActive: Boolean, projectionReady: Boolean, notification: Notification) {
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        val preferred = ForegroundSessionTypes.forStart(projectionReady = projectionReady && sessionActive)
        try {
            startForeground(NOTIFICATION_ID, notification, preferred)
        } catch (err: SecurityException) {
            WebrtcDiag.log("foreground_service_fallback", extra = err.javaClass.simpleName)
            startForeground(
                NOTIFICATION_ID,
                notification,
                ForegroundSessionTypes.forStart(projectionReady = false)
            )
        } catch (err: IllegalStateException) {
            WebrtcDiag.log("foreground_service_fallback", extra = err.javaClass.simpleName)
            startForeground(
                NOTIFICATION_ID,
                notification,
                ForegroundSessionTypes.forStart(projectionReady = false)
            )
        }
    }

    private fun buildNotification(sessionActive: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Remote support", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (sessionActive) getString(R.string.session_notification_title) else getString(R.string.connected_notification_title)
        val text = if (sessionActive) getString(R.string.session_notification_text) else getString(R.string.connected_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(launch)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "arl_target_session_v2"
        const val NOTIFICATION_ID = 42
        const val EXTRA_SESSION = "session_active"
        const val EXTRA_PROJECTION = "projection_ready"
        const val ACTION_STOP = "lab.arl.target.STOP_SERVICE"

        @Volatile
        var instance: TargetForegroundService? = null
            private set

        fun start(context: Context, sessionActive: Boolean, projectionReady: Boolean = false) {
            val running = instance
            if (running != null) {
                running.enterForeground(
                    sessionActive,
                    projectionReady,
                    running.buildNotification(sessionActive)
                )
                WebrtcDiag.log(
                    "foreground_service_updated",
                    extra = "session=$sessionActive projection=$projectionReady"
                )
                return
            }
            val intent = Intent(context, TargetForegroundService::class.java)
                .putExtra(EXTRA_SESSION, sessionActive)
                .putExtra(EXTRA_PROJECTION, projectionReady)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, TargetForegroundService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
