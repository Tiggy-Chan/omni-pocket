package com.tiggy.qwenimage.data

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class ActiveDownloadState(
    val id: String,
    val title: String,
    val file: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long = 0L
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

data class QueuedDownloadInfo(
    val id: String,
    val title: String
)

data class DownloadManagerState(
    val active: ActiveDownloadState? = null,
    val queue: List<QueuedDownloadInfo> = emptyList(),
    val lastError: String? = null,
    val lastCompletedTitle: String? = null,
    val revision: Long = 0L
) {
    val running: Boolean get() = active != null
    fun isPendingOrActive(id: String): Boolean = active?.id == id || queue.any { it.id == id }
}

private data class PendingDownloadTask(
    val id: String,
    val title: String,
    val run: suspend (onProgress: (file: String, downloaded: Long, total: Long) -> Unit) -> Unit,
    val onSuccess: () -> Unit = {}
)

/**
 * Process-wide download coordinator backed by [ModelDownloadService] so multi-GB model downloads
 * survive app backgrounding, show real notification progress, and never block inference.
 */
object ModelDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = ArrayDeque<PendingDownloadTask>()
    private var currentTask: PendingDownloadTask? = null
    private var currentJob: Job? = null

    private val _state = MutableStateFlow(DownloadManagerState())
    val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    fun enqueue(
        context: Context,
        id: String,
        title: String,
        onSuccess: () -> Unit = {},
        run: suspend (onProgress: (file: String, downloaded: Long, total: Long) -> Unit) -> Unit
    ): Boolean {
        val appContext = context.applicationContext
        val startNext: PendingDownloadTask?
        synchronized(lock) {
            if (currentTask?.id == id || pending.any { it.id == id }) return false
            val task = PendingDownloadTask(id, title, run, onSuccess)
            if (currentTask == null) {
                currentTask = task
                startNext = task
            } else {
                pending.addLast(task)
                startNext = null
            }
            publishQueueLocked()
        }
        startForegroundServiceSafe(appContext)
        if (startNext != null) launchTask(appContext, startNext)
        return true
    }

    fun cancelActive() {
        synchronized(lock) {
            currentJob?.cancel()
        }
    }

    fun cancelQueued(id: String) {
        synchronized(lock) {
            if (currentTask?.id == id) {
                currentJob?.cancel()
                return
            }
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == id) {
                    iterator.remove()
                    break
                }
            }
            publishQueueLocked()
        }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    private fun launchTask(appContext: Context, task: PendingDownloadTask) {
        _state.update {
            it.copy(
                active = ActiveDownloadState(task.id, task.title, "准备下载…", 0L, 0L, 0L),
                lastError = null
            )
        }
        var lastTimeMs = System.currentTimeMillis()
        var lastBytes = 0L
        var smoothedSpeed = 0L

        val job = scope.launch {
            var errorMessage: String? = null
            var completed = false
            try {
                task.run { file, done, total ->
                    val now = System.currentTimeMillis()
                    val dt = now - lastTimeMs
                    if (dt >= 400L) {
                        val delta = (done - lastBytes).coerceAtLeast(0L)
                        val instant = (delta * 1000L) / dt
                        smoothedSpeed = if (smoothedSpeed == 0L) instant else (smoothedSpeed * 7 + instant * 3) / 10
                        lastTimeMs = now
                        lastBytes = done
                    }
                    _state.update { current ->
                        current.copy(
                            active = ActiveDownloadState(
                                id = task.id,
                                title = task.title,
                                file = file,
                                downloadedBytes = done,
                                totalBytes = total,
                                bytesPerSecond = smoothedSpeed
                            )
                        )
                    }
                }
                completed = true
                runCatching { task.onSuccess() }
            } catch (_: CancellationException) {
                // User cancelled; keep partial file for future resume.
            } catch (error: Throwable) {
                errorMessage = "${task.title} 下载失败：${error.message ?: error.toString()}"
            } finally {
                val next: PendingDownloadTask?
                synchronized(lock) {
                    currentTask = null
                    currentJob = null
                    next = if (pending.isNotEmpty()) {
                        pending.removeFirst().also { currentTask = it }
                    } else null
                    publishQueueLocked()
                }
                _state.update { current ->
                    current.copy(
                        active = if (next == null) null else current.active,
                        lastError = errorMessage ?: current.lastError,
                        lastCompletedTitle = if (completed) task.title else current.lastCompletedTitle,
                        revision = current.revision + 1
                    )
                }
                if (next != null) {
                    launchTask(appContext, next)
                } else {
                    stopServiceSafe(appContext)
                }
            }
        }
        synchronized(lock) { currentJob = job }
    }

    private fun publishQueueLocked() {
        val queued = pending.map { QueuedDownloadInfo(it.id, it.title) }
        _state.update { it.copy(queue = queued) }
    }

    private fun startForegroundServiceSafe(context: Context) {
        runCatching {
            val intent = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopServiceSafe(context: Context) {
        runCatching {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }
    }
}

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ModelDownloadManager.cancelActive()
            return START_NOT_STICKY
        }
        val active = ModelDownloadManager.state.value.active
        val initial = buildNotification(active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }
        if (collector == null) {
            var lastNotifyMs = 0L
            collector = serviceScope.launch {
                ModelDownloadManager.state.collect { state ->
                    val current = state.active
                    if (current == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= 500L || current.downloadedBytes >= current.totalBytes) {
                            lastNotifyMs = now
                            val manager = getSystemService(NotificationManager::class.java)
                            runCatching { manager.notify(NOTIFICATION_ID, buildNotification(current)) }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        collector = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "模型下载",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "后台下载本地 AI 模型权重"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(active: ActiveDownloadState?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val title = active?.let { "正在下载 ${it.title}" } ?: "模型下载"
        val text = if (active == null) {
            "准备中…"
        } else {
            val doneStr = formatBytesCompact(active.downloadedBytes)
            val totalStr = if (active.totalBytes > 0L) formatBytesCompact(active.totalBytes) else "未知"
            val speedStr = if (active.bytesPerSecond > 0L) " · ${formatBytesCompact(active.bytesPerSecond)}/s" else ""
            "$doneStr / $totalStr$speedStr"
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "取消", cancelIntent).build())
        if (active != null && active.totalBytes > 0L) {
            val pct = (active.fraction * 1000).toInt().coerceIn(0, 1000)
            builder.setProgress(1000, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "omni_model_download"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_CANCEL = "com.tiggy.omnipocket.action.CANCEL_DOWNLOAD"
    }
}

fun formatBytesCompact(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
