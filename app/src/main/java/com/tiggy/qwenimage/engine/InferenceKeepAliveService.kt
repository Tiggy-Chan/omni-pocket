package com.tiggy.qwenimage.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tiggy.qwenimage.MainActivity
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.AppLanguageManager
import com.tiggy.qwenimage.data.LiveUpdateNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActiveInferenceNotification(
    val title: String,
    val detail: String,
    val fraction: Float? = null,
    val shortChip: String? = null
)

/**
 * Foreground keep-alive coordinator for long-running on-device image, video, and music inference.
 * Prevents Android from reclaiming the UI/`:engine` processes while backgrounded and exposes real
 * progress via notifications (and Android 16+ Live Updates status-bar chips).
 *
 * Follows the same promote-first FGS contract as [com.tiggy.qwenimage.data.ModelDownloadManager]:
 * never calls `context.stopService()` externally; [InferenceKeepAliveService] always calls
 * `startForeground()` first and self-stops when [state] becomes `null`.
 */
object InferenceKeepAliveManager {
    private val _state = MutableStateFlow<ActiveInferenceNotification?>(null)
    val state: StateFlow<ActiveInferenceNotification?> = _state.asStateFlow()

    @Volatile
    private var cancelCallback: (() -> Unit)? = null

    fun begin(
        context: Context,
        title: String,
        detail: String,
        onCancel: (() -> Unit)? = null
    ) {
        cancelCallback = onCancel
        _state.value = ActiveInferenceNotification(title = title, detail = detail, fraction = null)
        runCatching {
            val intent = Intent(context.applicationContext, InferenceKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }

    fun update(detail: String, fraction: Float?, shortChip: String? = null) {
        _state.update { current ->
            current?.copy(
                detail = detail.ifBlank { current.detail },
                fraction = fraction?.coerceIn(0f, 1f) ?: current.fraction,
                shortChip = shortChip ?: current.shortChip
            )
        }
    }

    fun end() {
        cancelCallback = null
        _state.value = null
    }

    internal fun triggerCancel() {
        runCatching { cancelCallback?.invoke() }
    }
}

class InferenceKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentBefore = InferenceKeepAliveManager.state.value
        promoteToForeground(currentBefore)

        if (intent?.action == ACTION_CANCEL) {
            InferenceKeepAliveManager.triggerCancel()
            if (InferenceKeepAliveManager.state.value == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        if (InferenceKeepAliveManager.state.value == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (collector == null) {
            var lastNotifyMs = 0L
            collector = scope.launch {
                InferenceKeepAliveManager.state.collect { active ->
                    if (active == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= 500L || (active.fraction != null && active.fraction >= 0.99f)) {
                            lastNotifyMs = now
                            val nm = getSystemService(NotificationManager::class.java)
                            runCatching { nm.notify(NOTIFICATION_ID, buildNotification(active)) }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        InferenceKeepAliveManager.triggerCancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        collector?.cancel()
        collector = null
        super.onDestroy()
    }

    private fun promoteToForeground(active: ActiveInferenceNotification?) {
        val notif = buildNotification(active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel() {
        val localized = AppLanguageManager.localizedContext(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                localized.getString(R.string.inference_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localized.getString(R.string.inference_notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(active: ActiveInferenceNotification?): Notification {
        val localized = AppLanguageManager.localizedContext(this)
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, InferenceKeepAliveService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = active?.title?.takeIf { it.isNotBlank() }
            ?: localized.getString(R.string.inference_notif_channel)
        val detail = active?.detail?.takeIf { it.isNotBlank() }
            ?: localized.getString(R.string.models_inferring)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, localized.getString(R.string.common_stop), cancelIntent).build()
            )

        LiveUpdateNotificationHelper.applyProgressAndLiveUpdate(
            builder = builder,
            fraction = active?.fraction,
            shortChipText = active?.shortChip
        )
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "omni_local_inference"
        private const val NOTIFICATION_ID = 4103
        internal const val ACTION_CANCEL = "com.tiggy.omnipocket.action.CANCEL_INFERENCE"
    }
}
