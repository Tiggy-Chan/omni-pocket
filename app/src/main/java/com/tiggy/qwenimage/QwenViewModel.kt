package com.tiggy.qwenimage

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.GenerationSettings
import com.tiggy.qwenimage.data.ImageGenerationBackend
import com.tiggy.qwenimage.data.ModelManifest
import com.tiggy.qwenimage.data.ModelRepository
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.MossModelRepository
import com.tiggy.qwenimage.data.MossModelStatus
import com.tiggy.qwenimage.data.MossSettings
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicStudioTab
import com.tiggy.qwenimage.data.MusicTrack
import com.tiggy.qwenimage.data.TaskDomain
import com.tiggy.qwenimage.data.TaskStates
import com.tiggy.qwenimage.data.TaskUiState
import com.tiggy.qwenimage.data.ThemeMode
import com.tiggy.qwenimage.data.VideoBackend
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoJob
import com.tiggy.qwenimage.data.VideoRuntimeStatus
import com.tiggy.qwenimage.data.VideoSettings
import com.tiggy.qwenimage.data.VideoStudioTab
import com.tiggy.qwenimage.data.WorkspaceTab
import com.tiggy.qwenimage.data.hasVae
import com.tiggy.qwenimage.data.isMoss
import com.tiggy.qwenimage.data.validateFor
import com.tiggy.qwenimage.engine.EngineSettings
import com.tiggy.qwenimage.engine.LocalMossEngine
import com.tiggy.qwenimage.engine.MusicEngine
import com.tiggy.qwenimage.engine.QwenEngine
import com.tiggy.qwenimage.engine.VideoEngine
import com.scsonic.qwenimage21.QwenImage21
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class QwenUiState(
    val domain: AppDomain = AppDomain.ImageStudio,
    val tab: WorkspaceTab = WorkspaceTab.Generate,
    val settings: GenerationSettings = GenerationSettings(),
    val imageBackend: ImageGenerationBackend = ImageGenerationBackend.QwenLocal,
    val mossSettings: MossSettings = MossSettings(),
    val jobs: List<GenerationJob> = emptyList(),
    val modelStatus: ModelStatus? = null,
    val mossModelStatus: MossModelStatus? = null,
    val repo: String = "",
    val revision: String = "main",
    val tasks: TaskStates = TaskStates(),
    val alert: String? = null,
    val cpuThreads: Int = 6,
    val keepModelsLoaded: Boolean = false,
    val musicTab: MusicStudioTab = MusicStudioTab.Compose,
    val musicSettings: MusicSettings = MusicSettings(),
    val musicJobs: List<MusicJob> = emptyList(),
    val musicPlaylist: List<MusicTrack> = emptyList(),
    val activeMusicTrack: MusicTrack? = null,
    val nowPlayingTrack: MusicTrack? = null,
    val isMusicPlaying: Boolean = false,
    val musicPositionMs: Int = 0,
    val musicDurationMs: Int = 0,
    val videoTab: VideoStudioTab = VideoStudioTab.Compose,
    val videoSettings: VideoSettings = VideoSettings(),
    val videoJobs: List<VideoJob> = emptyList(),
    val videoGallery: List<VideoItem> = emptyList(),
    val activeVideo: VideoItem? = null,
    val themeMode: ThemeMode = ThemeMode.System
) {
    val imageTask: TaskUiState get() = tasks.imageTask
    val musicTask: TaskUiState get() = tasks.musicTask
    val videoTask: TaskUiState get() = tasks.videoTask
    val modelTask: TaskUiState get() = tasks.modelTask
    val activeTask get() = tasks.activeTask

    val currentTask: TaskUiState
        get() = when (domain) {
            AppDomain.ImageStudio -> imageTask
            AppDomain.MusicStudio -> musicTask
            AppDomain.VideoStudio -> videoTask
            AppDomain.Conversation, AppDomain.Decision -> imageTask
        }

    val isBusy: Boolean get() = currentTask.running
    val progress: Int get() = currentTask.progress ?: 0
    val status: String get() = currentTask.status
    val liveLogs: List<String> get() = currentTask.logs
    val resultPath: String? get() = currentTask.resultPath
    val error: String? get() = currentTask.error
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class QwenViewModel(
    application: Application,
    private val inferenceCoordinator: com.tiggy.qwenimage.engine.LocalInferenceCoordinator
) : AndroidViewModel(application) {
    private val repository = ModelRepository(application)
    private val mossRepository = MossModelRepository(application)
    private val preferences = application.getSharedPreferences("qwen-pocket", Application.MODE_PRIVATE)
    private val restoredJobs = runCatching {
        val array = JSONArray(preferences.getString("jobs", "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching { GenerationJob.fromJson(array.getJSONObject(index)) }.getOrNull()
        }.map { job ->
            if (job.state == "running") job.copy(state = "interrupted", error = "应用退出，任务已中断") else job
        }
    }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(
        QwenUiState(repo = repository.sourceRepo, revision = repository.revision, jobs = restoredJobs,
            modelStatus = repository.status(), mossModelStatus = mossRepository.status())
    )
    val state: StateFlow<QwenUiState> = _state.asStateFlow()
    private val queue = Channel<GenerationJob>(Channel.UNLIMITED)
    private val engineMutex = Mutex()
    private val cancelledJobIds = ConcurrentHashMap.newKeySet<String>()
    private var engine: QwenEngine? = null
    private var engineKey: String? = null
    private val musicEngine = MusicEngine(application)
    private val videoEngine = VideoEngine(application)
    private val localMossEngine = LocalMossEngine(application, mossRepository)
    private val evictionKey = "qwen:${System.identityHashCode(this)}"

    init {
        inferenceCoordinator.registerEvictor(evictionKey) { invalidateEngine() }
        val crash = QwenImage21.readCrashMarker(File(application.filesDir, "generation.marker"))
        val initialStatus = repository.status()
        val restoredMossSettings = runCatching {
            MossSettings.fromJson(org.json.JSONObject(preferences.getString("mossSettings", null) ?: "{}"))
        }.getOrDefault(MossSettings())
        _state.update { state ->
            state.copy(
                domain = runCatching { AppDomain.valueOf(preferences.getString("domain", null).orEmpty()) }
                    .getOrDefault(AppDomain.Conversation),
                themeMode = runCatching { ThemeMode.valueOf(preferences.getString("themeMode", null).orEmpty()) }
                    .getOrDefault(ThemeMode.System),
                mossSettings = restoredMossSettings,
                settings = compatibleSettings(state.settings, initialStatus),
                modelStatus = initialStatus,
                tasks = state.tasks.updateTask(TaskDomain.Image) { task ->
                    task.copy(status = crash?.let { detail -> "上次图像任务中断：$detail" } ?: task.status)
                }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val initialTracks = musicEngine.preloadInitialTracks()
            val initialVideos = videoEngine.preloadInitialVideos()
            _state.update {
                it.copy(
                    musicPlaylist = initialTracks,
                    activeMusicTrack = it.activeMusicTrack ?: initialTracks.firstOrNull(),
                    videoGallery = initialVideos,
                    activeVideo = it.activeVideo ?: initialVideos.firstOrNull()
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            state.map { it.jobs }.distinctUntilChanged().debounce(250).collect { jobs -> persistJobs(jobs) }
        }

        viewModelScope.launch {
            musicEngine.isPlaying.collect { playing -> _state.update { it.copy(isMusicPlaying = playing) } }
        }
        viewModelScope.launch {
            musicEngine.currentPositionMs.collect { pos -> _state.update { it.copy(musicPositionMs = pos) } }
        }
        viewModelScope.launch {
            musicEngine.durationMs.collect { dur -> _state.update { it.copy(musicDurationMs = dur) } }
        }
        viewModelScope.launch {
            musicEngine.activeTrack.collect { track -> _state.update { it.copy(nowPlayingTrack = track) } }
        }
        viewModelScope.launch {
            musicEngine.playbackError.collect { message ->
                if (message != null) {
                    _state.update { it.copy(alert = message) }
                    musicEngine.clearPlaybackError()
                }
            }
        }

        viewModelScope.launch {
            val current = repository.status()
            val taskId = "initial-model-verification"
            if (!current.ready && current.total > 0 && current.present == current.total &&
                beginTask(TaskDomain.Model, taskId, status = "发现完整模型，正在手机端校验 SHA-256…", alertOnConflict = false)
            ) {
                _state.update { it.copy(modelStatus = current) }
                runCatching {
                    repository.verify { file ->
                        updateTask(TaskDomain.Model, taskId) { it.copy(progress = null, status = "校验 $file…") }
                    }
                }.onSuccess {
                    val status = repository.status()
                    _state.update {
                        it.copy(modelStatus = status, settings = compatibleSettings(it.settings, status))
                    }
                    finishTask(TaskDomain.Model, taskId) { it.copy(status = "模型已校验，可以生成") }
                }.onFailure { error ->
                    val message = error.message ?: error.toString()
                    finishTask(TaskDomain.Model, taskId, alert = message) {
                        it.copy(status = "模型校验失败", error = message)
                    }
                }
            }
        }
        viewModelScope.launch {
            restoredJobs.filter { it.state == "queued" }.forEach { queue.send(it) }
            for (job in queue) runJob(job)
        }
    }

    private fun updateTask(domain: TaskDomain, id: String? = null, transform: (TaskUiState) -> TaskUiState) {
        _state.update { state ->
            if (id != null && !state.tasks.isActive(domain, id)) state
            else state.copy(tasks = state.tasks.updateTask(domain, transform))
        }
    }

    private fun appendLog(domain: TaskDomain, line: String, id: String? = null) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _state.update { state ->
            if (id != null && !state.tasks.isActive(domain, id)) state
            else state.copy(tasks = state.tasks.appendLog(domain, "[$time] $line"))
        }
    }

    private fun showTaskError(domain: TaskDomain, message: String) {
        _state.update { state ->
            state.copy(
                alert = message,
                tasks = state.tasks.updateTask(domain) { it.copy(error = message) }
            )
        }
    }

    private fun beginTask(
        domain: TaskDomain,
        id: String,
        backend: VideoBackend? = null,
        status: String,
        progress: Int? = null,
        alertOnConflict: Boolean = true
    ): Boolean {
        if (!inferenceCoordinator.acquire("qwen:$id")) {
            val reason = "另一个任务正在运行，请稍后重试"
            _state.update { state ->
                state.copy(alert = if (alertOnConflict) reason else state.alert,
                    tasks = state.tasks.updateTask(domain) { it.copy(error = reason) })
            }
            return false
        }
        var started = false
        _state.update { state ->
            started = false
            val activated = state.tasks.activate(domain, id, backend)
            if (activated == null) {
                val active = requireNotNull(state.activeTask)
                val reason = if (active.domain == domain) {
                    "已有${domain.displayName}任务正在进行，请等待完成"
                } else {
                    "${active.domain.displayName}任务正在进行，请等待完成后再启动${domain.displayName}任务"
                }
                state.copy(
                    alert = if (alertOnConflict) reason else state.alert,
                    tasks = state.tasks.updateTask(domain) { it.copy(error = reason) }
                )
            } else {
                started = true
                state.copy(
                    tasks = activated.updateTask(domain) {
                        it.copy(running = true, progress = progress, status = status, error = null)
                    }
                )
            }
        }
        if (!started) inferenceCoordinator.release("qwen:$id")
        return started
    }

    private fun finishTask(
        domain: TaskDomain,
        id: String,
        alert: String? = null,
        transform: (TaskUiState) -> TaskUiState
    ) {
        var released = false
        _state.update { state ->
            if (!state.tasks.isActive(domain, id)) {
                state
            } else {
                released = true
                state.copy(
                    alert = alert ?: state.alert,
                    tasks = state.tasks.updateTask(domain) { task -> transform(task.copy(running = false)) }
                        .release(domain, id)
                )
            }
        }
        if (released) inferenceCoordinator.release("qwen:$id")
    }

    private suspend fun acquireImageTask(jobId: String): Boolean {
        while (currentCoroutineContext().isActive) {
            val job = _state.value.jobs.firstOrNull { it.id == jobId } ?: return false
            if (job.state != "queued") return false
            val backend = when (job.backend) {
                ImageGenerationBackend.QwenLocal -> VideoBackend.LocalQwen
                ImageGenerationBackend.MossLocal -> VideoBackend.LocalMoss
            }
            val status = when (job.backend) {
                ImageGenerationBackend.QwenLocal -> "正在初始化图像任务"
                ImageGenerationBackend.MossLocal -> "正在准备 MOSS 本机"
            }
            if (beginTask(
                    TaskDomain.Image,
                    jobId,
                    backend = backend,
                    status = status,
                    progress = 0,
                    alertOnConflict = false
                )
            ) return true
            kotlinx.coroutines.flow.combine(state, inferenceCoordinator.activeOwner) { snapshot, owner ->
                snapshot to owner
            }.first { (snapshot, owner) ->
                (snapshot.activeTask == null && owner == null) ||
                    snapshot.jobs.firstOrNull { it.id == jobId }?.state != "queued"
            }
        }
        return false
    }

    private fun rejectIfBusy(domain: TaskDomain): Boolean {
        var rejected = false
        _state.update { state ->
            rejected = false
            val active = state.activeTask ?: return@update state
            rejected = true
            val reason = if (active.domain == domain) {
                "已有${domain.displayName}任务正在进行，请等待完成"
            } else {
                "${active.domain.displayName}任务正在进行，请等待完成后再启动${domain.displayName}任务"
            }
            state.copy(
                alert = reason,
                tasks = state.tasks.updateTask(domain) { it.copy(error = reason) }
            )
        }
        return rejected
    }

    fun setDomain(domain: AppDomain) {
        if (domain != AppDomain.MusicStudio) musicEngine.pausePlayback()
        preferences.edit().putString("domain", domain.name).apply()
        _state.update { it.copy(domain = domain) }
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString("themeMode", mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    /** Opens the video workspace with a finished image as the first frame. */
    fun useImageAsVideoFirstFrame(path: String) {
        val file = File(path)
        if (!file.isFile) {
            _state.update { it.copy(alert = "图像文件不存在") }
            return
        }
        setVideoInputImage(Uri.fromFile(file))
        setDomain(AppDomain.VideoStudio)
        _state.update { it.copy(videoTab = VideoStudioTab.Compose) }
    }

    /** Starts a Qwen image edit from a finished image. */
    fun editImage(path: String) {
        val file = File(path)
        val status = _state.value.modelStatus
        when {
            !file.isFile -> _state.update { it.copy(alert = "图像文件不存在") }
            status == null || status.total == 0 || "image-edit" !in status.manifest.features ->
                _state.update { it.copy(alert = "当前 Qwen 模型不支持图片编辑") }
            else -> {
                setImageBackend(ImageGenerationBackend.QwenLocal)
                setInput(Uri.fromFile(file))
                _state.update { it.copy(tab = WorkspaceTab.Generate) }
                setDomain(AppDomain.ImageStudio)
            }
        }
    }

    fun selectTab(tab: WorkspaceTab) = _state.update { it.copy(tab = tab) }
    fun selectMusicTab(tab: MusicStudioTab) = _state.update { it.copy(musicTab = tab) }
    fun selectVideoTab(tab: VideoStudioTab) = _state.update { it.copy(videoTab = tab) }

    fun videoRuntimeStatus(): VideoRuntimeStatus = videoEngine.runtimeStatus()

    // Video settings modifiers
    fun setVideoPrompt(prompt: String) = _state.update { it.copy(videoSettings = it.videoSettings.copy(prompt = prompt)) }
    fun setVideoSeed(seed: Long) = _state.update { it.copy(videoSettings = it.videoSettings.copy(seed = seed)) }
    fun setVideoFps(fps: Int) = _state.update { it.copy(videoSettings = it.videoSettings.copy(fps = fps.coerceIn(8, 30))) }
    fun setVideoNumFrames(numFrames: Int) = _state.update { it.copy(videoSettings = it.videoSettings.copy(numFrames = numFrames.coerceIn(5, 81))) }
    fun setVideoSteps(steps: Int) = _state.update { it.copy(videoSettings = it.videoSettings.copy(steps = steps.coerceIn(1, 10))) }
    fun setVideoShift(shift: Float) = _state.update { it.copy(videoSettings = it.videoSettings.copy(shift = shift.coerceIn(1.0f, 10.0f))) }
    fun setVideoInputImage(uri: Uri?) {
        if (uri != null) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        _state.update { it.copy(videoSettings = it.videoSettings.copy(inputImageUri = uri)) }
    }
    fun setActiveVideo(video: VideoItem) = _state.update { it.copy(activeVideo = video) }
    fun deleteVideo(video: VideoItem) {
        val videoFile = File(video.videoPath)
        val deletion = runCatching {
            if (videoFile.exists() && !videoFile.delete() && videoFile.exists()) {
                error("无法删除 ${videoFile.name}")
            }
        }
        deletion.exceptionOrNull()?.let { exception ->
            _state.update { it.copy(alert = "删除视频失败：${exception.message ?: "无法删除视频"}") }
            return
        }

        val sidecar = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.json")
        val cleanupFailed = listOfNotNull(sidecar, video.thumbnailPath?.let(::File)).filter { file ->
            runCatching { file.exists() && !file.delete() && file.exists() }.getOrDefault(true)
        }
        _state.update { state ->
            val nextList = state.videoGallery.filter { it.id != video.id }
            state.copy(
                videoGallery = nextList,
                activeVideo = if (state.activeVideo?.id == video.id) nextList.firstOrNull() else state.activeVideo,
                alert = if (cleanupFailed.isEmpty()) state.alert
                else "视频已删除，但关联文件清理失败：${cleanupFailed.joinToString { it.name }}"
            )
        }
    }

    fun startVideoGeneration() {
        if (rejectIfBusy(TaskDomain.Video)) return
        val settings = _state.value.videoSettings
        val runtime = videoEngine.runtimeStatus()
        if (!runtime.localReady) {
            showTaskError(TaskDomain.Video, "本机 Wan2.2 缺少：${runtime.missingComponents.joinToString("、")}")
            return
        }
        runCatching { settings.validateOnDevice() }.onFailure { error ->
            showTaskError(TaskDomain.Video, error.message ?: "参数校验失败")
            return
        }

        val initialStage = if (settings.inputImageUri != null) "准备 Wan2.2 图生视频..." else "准备 Wan2.2 文生视频..."
        val job = VideoJob(
            id = UUID.randomUUID().toString(),
            settings = settings,
            state = "running",
            stage = initialStage
        )
        if (!beginTask(TaskDomain.Video, job.id, VideoBackend.LocalWan, initialStage)) return

        _state.update { it.copy(videoJobs = it.videoJobs + job) }
        appendLog(TaskDomain.Video, "----------------------------------------", job.id)
        appendLog(TaskDomain.Video, "视频任务启动 [${job.id.take(8)}]：Wan2.2-TI2V-5B-Turbo", job.id)
        appendLog(TaskDomain.Video, "提示词：${settings.prompt.take(60)}...", job.id)
        appendLog(TaskDomain.Video, "模式：${if (settings.inputImageUri != null) "图生视频（App 所选参考首帧）" else "文生视频"}", job.id)
        appendLog(TaskDomain.Video, "规格：${settings.width}×${settings.height} | ${settings.numFrames}帧 | ${settings.fps}fps | ${settings.steps}步 Euler", job.id)
        appendLog(TaskDomain.Video, "执行设备：本机 Wan2.2 模型与原生推理 CLI；diffusion=GPUOpenCL, text encoder=CPU, VAE=CPU", job.id)

        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                inferenceCoordinator.evictOthers("qwen")
                invalidateEngine()
                videoEngine.executeGeneration(job) { update ->
                    updateTask(TaskDomain.Video, job.id) {
                        it.copy(progress = update.progress?.let { value -> (value * 100).toInt() }, status = update.stage)
                    }
                    _state.update { currentState ->
                        currentState.copy(
                            videoJobs = currentState.videoJobs.map { item ->
                                if (item.id == job.id) item.copy(
                                    progress = update.progress ?: item.progress,
                                    stage = update.stage
                                ) else item
                            }
                        )
                    }
                    update.log?.let { appendLog(TaskDomain.Video, it, job.id) }
                }
            }.onSuccess { videoItem ->
                val completedAt = System.currentTimeMillis()
                _state.update { currentState ->
                    currentState.copy(
                        videoJobs = currentState.videoJobs.map { item ->
                            if (item.id == job.id) item.copy(
                                state = "completed",
                                progress = 1f,
                                stage = "已保存",
                                resultPath = videoItem.videoPath,
                                error = null,
                                completedAt = completedAt
                            ) else item
                        },
                        videoGallery = listOf(videoItem) + currentState.videoGallery.filter { old -> old.id != videoItem.id },
                        activeVideo = videoItem
                    )
                }
                appendLog(TaskDomain.Video, "视频已存入作品库：${videoItem.videoPath}", job.id)
                finishTask(TaskDomain.Video, job.id) {
                    it.copy(
                        progress = 100,
                        status = "视频已保存：${File(videoItem.videoPath).name}",
                        error = null,
                        resultPath = videoItem.videoPath
                    )
                }
            }.onFailure { error ->
                val cancelled = error is CancellationException
                val message = if (cancelled) null else (error.message ?: error.toString())
                _state.update { currentState ->
                    currentState.copy(
                        videoJobs = currentState.videoJobs.map { item ->
                            if (item.id == job.id) item.copy(
                                state = if (cancelled) "cancelled" else "failed",
                                stage = if (cancelled) "已取消" else "生成失败",
                                error = message,
                                completedAt = System.currentTimeMillis()
                            ) else item
                        }
                    )
                }
                appendLog(TaskDomain.Video, if (cancelled) "视频生成已取消" else "视频生成异常: ${error.message}", job.id)
                finishTask(TaskDomain.Video, job.id, alert = message) {
                    it.copy(status = if (cancelled) "视频生成已取消" else "视频生成失败", error = message)
                }
            }
        }
    }

    fun cancelVideoGeneration() {
        videoEngine.cancelGeneration()
        val active = _state.value.activeTask ?: return
        if (active.domain == TaskDomain.Video) {
            updateTask(TaskDomain.Video, active.id) { it.copy(status = "正在取消视频生成…") }
            _state.update { state ->
                state.copy(videoJobs = state.videoJobs.map {
                    if (it.id == active.id) it.copy(stage = "正在取消") else it
                })
            }
        }
    }

    // Music settings modifiers
    fun setMusicTitle(title: String) = _state.update { it.copy(musicSettings = it.musicSettings.copy(title = title)) }
    fun setMusicStyle(style: String) = _state.update { it.copy(musicSettings = it.musicSettings.copy(style = style)) }
    fun setMusicLyrics(lyrics: String) = _state.update { it.copy(musicSettings = it.musicSettings.copy(lyrics = lyrics)) }
    fun setMusicBpm(bpm: Int) = _state.update { it.copy(musicSettings = it.musicSettings.copy(bpm = bpm.coerceIn(50, 220))) }
    fun setMusicCotMode(mode: CoTMode) = _state.update { it.copy(musicSettings = it.musicSettings.copy(cotMode = mode)) }
    fun setMusicSeed(seed: Long) = _state.update { it.copy(musicSettings = it.musicSettings.copy(seed = seed)) }
    fun setMusicTargetDuration(sec: Int) = _state.update { it.copy(musicSettings = it.musicSettings.copy(targetDurationSec = sec)) }
    fun setMusicOdeSteps(steps: Int) = _state.update { it.copy(musicSettings = it.musicSettings.copy(odeSteps = steps.coerceIn(4, 32))) }
    fun musicMissingComponents(): List<String> = musicEngine.missingComponents()
    fun setMusicGuidanceScale(scale: Float) = _state.update { it.copy(musicSettings = it.musicSettings.copy(guidanceScale = scale)) }

    fun playMusic(track: MusicTrack) {
        if (_state.value.nowPlayingTrack?.id == track.id) {
            musicEngine.togglePlayPause(track)
        } else {
            _state.update { it.copy(activeMusicTrack = track) }
            musicEngine.playTrack(track)
        }
    }

    fun toggleMusicPlayPause() {
        val track = _state.value.activeMusicTrack ?: _state.value.nowPlayingTrack ?: return
        musicEngine.togglePlayPause(track)
    }

    fun seekMusic(positionMs: Int) = musicEngine.seekTo(positionMs)
    fun stopMusic() = musicEngine.stopPlayback()
    fun cancelMusicGeneration() {
        musicEngine.cancelGeneration()
        val active = _state.value.activeTask ?: return
        if (active.domain == TaskDomain.Music) {
            updateTask(TaskDomain.Music, active.id) { it.copy(status = "正在取消音乐生成…") }
            _state.update { state ->
                state.copy(musicJobs = state.musicJobs.map {
                    if (it.id == active.id) it.copy(stageDescription = "正在取消") else it
                })
            }
        }
    }

    fun setActiveTrack(track: MusicTrack) = _state.update { it.copy(activeMusicTrack = track) }

    fun startMusicGeneration() {
        if (rejectIfBusy(TaskDomain.Music)) return
        val settings = _state.value.musicSettings
        runCatching { settings.validate() }.onFailure { error ->
            showTaskError(TaskDomain.Music, error.message ?: "参数校验失败")
            return
        }
        val missing = musicEngine.missingComponents()
        if (missing.isNotEmpty()) {
            showTaskError(TaskDomain.Music, "本机 YuE2 缺少：${missing.joinToString("、")}")
            return
        }

        val job = MusicJob(
            id = UUID.randomUUID().toString(),
            title = settings.title.ifBlank { "未命名曲目" },
            settings = settings,
            state = "running",
            stageDescription = "正在准备"
        )
        if (!beginTask(TaskDomain.Music, job.id, VideoBackend.LocalYue2, "正在准备本机 YuE2")) return

        _state.update { it.copy(musicJobs = it.musicJobs + job) }
        appendLog(TaskDomain.Music, "音乐任务 ${job.id.take(8)} · ${settings.targetDurationSec} 秒 · ${settings.cotMode.tag} · ${settings.odeSteps} 步", job.id)

        viewModelScope.launch {
            runCatching {
                inferenceCoordinator.evictOthers("qwen")
                invalidateEngine()
                musicEngine.executeGeneration(job) { update ->
                    updateTask(TaskDomain.Music, job.id) {
                        it.copy(progress = update.progress?.let { value -> (value * 100).toInt() }, status = update.stage)
                    }
                    _state.update { currentState ->
                        currentState.copy(
                            musicJobs = currentState.musicJobs.map { item ->
                                if (item.id == job.id) item.copy(
                                    progress = update.progress ?: item.progress,
                                    stageDescription = update.stage
                                ) else item
                            }
                        )
                    }
                    update.log?.let { appendLog(TaskDomain.Music, it, job.id) }
                }
            }.onSuccess { track ->
                _state.update { currentState ->
                    currentState.copy(
                        musicJobs = currentState.musicJobs.map { item ->
                            if (item.id == job.id) item.copy(
                                state = "completed",
                                progress = 1f,
                                stageDescription = "已保存",
                                resultAudioPath = track.audioPath,
                                error = null
                            ) else item
                        },
                        musicPlaylist = listOf(track) + currentState.musicPlaylist.filter { old -> old.id != track.id },
                        activeMusicTrack = track
                    )
                }
                appendLog(TaskDomain.Music, "音频已存入曲库：${track.title}", job.id)
                finishTask(TaskDomain.Music, job.id) {
                    it.copy(progress = 100, status = "音乐已保存：${track.title}", error = null, resultPath = track.audioPath)
                }
                musicEngine.playTrack(track)
            }.onFailure { error ->
                val cancelled = error is CancellationException
                val message = if (cancelled) null else (error.message ?: error.toString())
                _state.update { currentState ->
                    currentState.copy(
                        musicJobs = currentState.musicJobs.map { item ->
                            if (item.id == job.id) item.copy(
                                state = if (cancelled) "cancelled" else "failed",
                                stageDescription = if (cancelled) "已取消" else "生成失败",
                                error = message
                            ) else item
                        }
                    )
                }
                appendLog(TaskDomain.Music, if (cancelled) "音乐生成已取消" else "音乐生成异常：${error.message}", job.id)
                finishTask(TaskDomain.Music, job.id, alert = message) {
                    it.copy(status = if (cancelled) "音乐生成已取消" else "音乐生成失败", error = message)
                }
            }
        }
    }

    fun setMode(mode: GenerationMode) = _state.update { state ->
        val unsupported = mode == GenerationMode.ImageEdit && state.modelStatus?.let { it.total > 0 && "image-edit" !in it.manifest.features } == true
        val message = if (unsupported) "当前模型不支持图片编辑" else null
        state.copy(
            settings = if (unsupported) state.settings else state.settings.copy(mode = mode),
            alert = message,
            tasks = state.tasks.updateTask(TaskDomain.Image) { it.copy(error = message) }
        )
    }
    fun setPrompt(prompt: String) = _state.update { it.copy(settings = it.settings.copy(prompt = prompt)) }
    fun setNegativePrompt(prompt: String) = _state.update { it.copy(settings = it.settings.copy(negativePrompt = prompt)) }
    fun setSteps(steps: Int) = _state.update { it.copy(settings = it.settings.copy(steps = steps.coerceIn(1, 40))) }
    fun setSeed(seed: Int) = _state.update { it.copy(settings = it.settings.copy(seed = seed)) }
    fun setSize(width: Int, height: Int) = _state.update { it.copy(settings = it.settings.copy(width = width, height = height)) }
    fun setTurbo(value: Boolean) = _state.update { state ->
        val enabled = value && (state.modelStatus?.manifest?.features?.contains("turbo") == true)
        state.copy(settings = state.settings.copy(turbo = enabled, officialTurbo = false, steps = if (enabled) 6 else state.settings.steps.coerceAtLeast(20)))
    }
    fun setOfficialTurbo(value: Boolean) = _state.update { state ->
        val enabled = value && (state.modelStatus?.manifest?.features?.contains("official-turbo") == true)
        state.copy(settings = state.settings.copy(officialTurbo = enabled, turbo = false, steps = if (enabled) 8 else state.settings.steps.coerceAtLeast(20)))
    }
    fun setTinyVae(value: Boolean) = _state.update { state ->
        val unsupported = state.modelStatus?.let { it.total > 0 && !it.manifest.hasVae(value) } == true
        val message = if (unsupported) {
            if (value) "当前模型缺少 Tiny VAE" else "当前模型缺少完整 VAE"
        } else {
            null
        }
        state.copy(
            settings = if (unsupported) state.settings else state.settings.copy(useTinyVae = value),
            alert = message,
            tasks = state.tasks.updateTask(TaskDomain.Image) { it.copy(error = message) }
        )
    }
    fun setSecondReference(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(secondReference = value)) }
    fun setInput(uri: Uri?, second: Boolean = false) = _state.update { state ->
        if (uri != null) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (second && uri != null && state.settings.inputUri == null) {
            val message = "请先选择第一张参考图"
            state.copy(
                alert = message,
                tasks = state.tasks.updateTask(TaskDomain.Image) { it.copy(error = message) }
            )
        } else {
            val settings = if (second) state.settings.copy(inputUri2 = uri)
            else state.settings.copy(
                inputUri = uri,
                inputUri2 = if (uri == null) null else state.settings.inputUri2,
                mode = if (uri != null) GenerationMode.ImageEdit else state.settings.mode
            )
            state.copy(
                settings = settings,
                alert = null,
                tasks = state.tasks.updateTask(TaskDomain.Image) { it.copy(error = null) }
            )
        }
    }
    fun setRepo(repo: String) = _state.update { it.copy(repo = repo) }
    fun setRevision(revision: String) = _state.update { it.copy(revision = revision) }
    fun setCpuThreads(threads: Int) = _state.update { it.copy(cpuThreads = threads.coerceIn(2, 8)) }
    fun setKeepModelsLoaded(value: Boolean) = _state.update { it.copy(keepModelsLoaded = value) }

    fun setTransparent(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(transparent = value)) }

    fun useSettings(settings: GenerationSettings) = _state.update { state ->
        val compatible = state.modelStatus?.let { compatibleSettings(settings, it) } ?: settings
        state.copy(imageBackend = ImageGenerationBackend.QwenLocal, settings = compatible, tab = WorkspaceTab.Generate)
    }

    fun reuseJob(job: GenerationJob) {
        if (job.backend.isMoss) {
            val settings = job.mossSettings ?: MossSettings()
            preferences.edit().putString("mossSettings", settings.toJson().toString()).apply()
            _state.update {
                it.copy(
                    imageBackend = job.backend,
                    mossSettings = settings,
                    mossModelStatus = if (job.backend == ImageGenerationBackend.MossLocal) mossRepository.status() else it.mossModelStatus,
                    tab = WorkspaceTab.Generate
                )
            }
        } else {
            useSettings(job.settings)
        }
    }

    fun refreshModelStatus() = _state.update { state ->
        val status = repository.status()
        state.copy(modelStatus = status, settings = compatibleSettings(state.settings, status))
    }

    fun refreshMossModelStatus() = _state.update { state ->
        state.copy(mossModelStatus = mossRepository.status())
    }

    fun checkRuntime() {
        if (rejectIfBusy(TaskDomain.Model)) return
        val taskId = UUID.randomUUID().toString()
        viewModelScope.launch {
            if (!beginTask(TaskDomain.Model, taskId, status = "检查 MNN/OpenCL 推理引擎…")) return@launch
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    inferenceCoordinator.evictOthers("qwen")
                    invalidateEngine()
                    check(repository.status().ready) { "请先完成模型校验" }
                    QwenEngine(repository.modelDir, EngineSettings(false, false, true, false, false,
                        _state.value.cpuThreads, File(getApplication<Application>().filesDir, "diagnostic.marker"))).use { }
                    QwenEngine.availableMemoryMb()
                }
            }
            result.onSuccess { memory ->
                finishTask(TaskDomain.Model, taskId) {
                    it.copy(status = "MNN 推理引擎已初始化，可用内存 ${memory}MB")
                }
            }.onFailure { error ->
                val message = error.message ?: error.toString()
                finishTask(TaskDomain.Model, taskId, alert = message) {
                    it.copy(status = "推理引擎检查失败", error = message)
                }
            }
        }
    }

    fun downloadModels() {
        if (rejectIfBusy(TaskDomain.Model)) return
        val snapshot = _state.value
        val taskId = UUID.randomUUID().toString()
        viewModelScope.launch {
            if (!beginTask(TaskDomain.Model, taskId, status = "读取模型清单…", progress = 0)) return@launch
            repository.sourceRepo = snapshot.repo
            repository.revision = snapshot.revision
            runCatching {
                repository.download { file, done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else null
                    updateTask(TaskDomain.Model, taskId) {
                        it.copy(
                            progress = percent,
                            status = "下载 $file · ${(done / 1_000_000_000.0).format1()} / ${(total / 1_000_000_000.0).format1()} GB"
                        )
                    }
                }
                invalidateEngine()
            }.onSuccess {
                val status = repository.status()
                _state.update {
                    it.copy(modelStatus = status, settings = compatibleSettings(it.settings, status))
                }
                finishTask(TaskDomain.Model, taskId) {
                    it.copy(progress = 100, status = "模型已校验", error = null)
                }
            }.onFailure { error ->
                val message = error.message ?: error.toString()
                finishTask(TaskDomain.Model, taskId, alert = message) {
                    it.copy(status = "模型下载失败", error = message)
                }
            }
        }
    }

    fun importModels(uri: Uri) {
        if (rejectIfBusy(TaskDomain.Model)) return
        val taskId = UUID.randomUUID().toString()
        viewModelScope.launch {
            if (!beginTask(TaskDomain.Model, taskId, status = "导入模型…")) return@launch
            runCatching {
                repository.importTree(uri) { text ->
                    updateTask(TaskDomain.Model, taskId) { it.copy(progress = null, status = text) }
                }
                invalidateEngine()
            }.onSuccess {
                val status = repository.status()
                _state.update {
                    it.copy(modelStatus = status, settings = compatibleSettings(it.settings, status))
                }
                finishTask(TaskDomain.Model, taskId) {
                    it.copy(progress = 100, status = "模型导入并校验完成", error = null)
                }
            }.onFailure { error ->
                val message = error.message ?: error.toString()
                finishTask(TaskDomain.Model, taskId, alert = message) {
                    it.copy(status = "模型导入失败", error = message)
                }
            }
        }
    }

    fun downloadMossModels() = runMossModelTask("下载 MOSS 模型") { taskId ->
        mossRepository.download { file, done, total ->
            val percent = if (total > 0) ((done * 100) / total).toInt() else null
            updateTask(TaskDomain.Model, taskId) {
                it.copy(
                    progress = percent,
                    status = "下载 $file · ${(done / 1_000_000_000.0).format1()} / ${(total / 1_000_000_000.0).format1()} GB"
                )
            }
        }
    }

    fun importMossModels(uri: Uri) = runMossModelTask("导入 MOSS 模型") { taskId ->
        mossRepository.importTree(uri) { text ->
            updateTask(TaskDomain.Model, taskId) { it.copy(progress = null, status = text) }
        }
    }

    fun verifyMossModels() = runMossModelTask("校验 MOSS 模型") { taskId ->
        mossRepository.verify { text ->
            updateTask(TaskDomain.Model, taskId) { it.copy(progress = null, status = "校验 $text") }
        }
    }

    private fun runMossModelTask(initialStatus: String, action: suspend (String) -> Unit) {
        if (rejectIfBusy(TaskDomain.Model)) return
        val taskId = UUID.randomUUID().toString()
        viewModelScope.launch {
            if (!beginTask(TaskDomain.Model, taskId, backend = VideoBackend.LocalMoss, status = initialStatus, progress = 0)) return@launch
            val result = runCatching { action(taskId) }
            val status = mossRepository.status()
            _state.update { it.copy(mossModelStatus = status) }
            result.onSuccess {
                finishTask(TaskDomain.Model, taskId) {
                    it.copy(progress = if (status.ready) 100 else null, status = if (status.ready) "MOSS 模型已校验" else status.message, error = null)
                }
            }.onFailure { error ->
                val message = error.message ?: error.toString()
                finishTask(TaskDomain.Model, taskId, alert = message) {
                    it.copy(status = "MOSS 模型任务失败", error = message)
                }
            }
        }
    }

    fun setImageBackend(backend: ImageGenerationBackend) = _state.update { state ->
        state.copy(
            imageBackend = backend,
            mossModelStatus = if (backend == ImageGenerationBackend.MossLocal) mossRepository.status() else state.mossModelStatus,
            tasks = state.tasks.updateTask(TaskDomain.Image) { it.copy(error = null) }
        )
    }

    fun updateMossSettings(transform: (MossSettings) -> MossSettings) = _state.update {
        val settings = transform(it.mossSettings)
        preferences.edit().putString("mossSettings", settings.toJson().toString()).apply()
        it.copy(mossSettings = settings)
    }

    fun enqueue() {
        val current = _state.value
        val job = when (current.imageBackend) {
            ImageGenerationBackend.QwenLocal -> {
                val modelStatus = current.modelStatus
                if (modelStatus?.ready != true) {
                    showTaskError(TaskDomain.Image, "请先完成模型校验")
                    return
                }
                val prompt = current.settings.prompt.trim()
                val prepared = current.settings.copy(
                    prompt = if (current.settings.transparent && !prompt.contains("transparent", ignoreCase = true))
                        "This is an RGBA image with transparency. $prompt The background is transparent." else prompt
                )
                runCatching { prepared.validateFor(modelStatus.manifest) }.onFailure { error ->
                    showTaskError(TaskDomain.Image, error.message ?: "参数无效")
                    return
                }
                GenerationJob(UUID.randomUUID().toString(), prepared)
            }
            ImageGenerationBackend.MossLocal -> {
                val settings = current.mossSettings
                if (current.mossModelStatus?.ready != true) {
                    showTaskError(TaskDomain.Image, "请先下载并校验 MOSS 本机模型")
                    return
                }
                runCatching { settings.validateLocal() }.onFailure { error ->
                    showTaskError(TaskDomain.Image, error.message ?: "MOSS 参数无效")
                    return
                }
                val displaySettings = GenerationSettings(
                    prompt = settings.prompt,
                    negativePrompt = settings.negativePrompt,
                    steps = settings.local.steps,
                    seed = settings.seed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    width = settings.local.width,
                    height = settings.local.height
                )
                GenerationJob(
                    id = UUID.randomUUID().toString(),
                    settings = displaySettings,
                    backend = current.imageBackend,
                    mossSettings = settings
                )
            }
        }
        var accepted = false
        _state.update { state ->
            accepted = false
            val active = state.activeTask
            if (active != null && active.domain != TaskDomain.Image) {
                val reason = "${active.domain.displayName}任务正在进行，请等待完成后再启动图像任务"
                state.copy(
                    alert = reason,
                    tasks = state.tasks.updateTask(TaskDomain.Image) { it.copy(error = reason) }
                )
            } else {
                accepted = true
                state.copy(
                    jobs = state.jobs + job,
                    alert = null,
                    tasks = state.tasks.updateTask(TaskDomain.Image) { task ->
                        if (task.running) task.copy(error = null)
                        else task.copy(error = null, status = "任务已加入队列")
                    }
                )
            }
        }
        if (accepted) queue.trySend(job)
    }

    fun clearError() = _state.update { state ->
        val domain = when (state.domain) {
            AppDomain.ImageStudio -> TaskDomain.Image
            AppDomain.MusicStudio -> TaskDomain.Music
            AppDomain.VideoStudio -> TaskDomain.Video
            AppDomain.Conversation, AppDomain.Decision -> return@update state.copy(alert = null)
        }
        state.copy(
            alert = null,
            tasks = state.tasks.updateTask(domain) { it.copy(error = null) }
        )
    }

    fun cancelJob(id: String) {
        val job = _state.value.jobs.firstOrNull { it.id == id } ?: return
        if (job.state == "queued") {
            updateJob(id) { it.copy(state = "cancelled", error = "用户取消") }
            updateTask(TaskDomain.Image) { task ->
                if (task.running) task else task.copy(status = "任务已取消", error = null)
            }
        } else if (job.state == "running") {
            cancelledJobIds += id
            when (job.backend) {
                ImageGenerationBackend.QwenLocal -> engine?.cancel()
                ImageGenerationBackend.MossLocal -> localMossEngine.cancelGeneration()
            }
            updateTask(TaskDomain.Image, id) { it.copy(status = "正在取消，将在当前计算阶段结束后停止") }
        }
    }

    fun deleteImageJob(id: String) {
        val job = _state.value.jobs.firstOrNull { it.id == id } ?: return
        if (job.state == "queued" || job.state == "running") return
        val output = job.outputPath?.let(::File)
        val outputsDir = File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "outputs")
        if (output != null) {
            val failure = runCatching {
                val inOutputs = output.canonicalFile.parentFile == outputsDir.canonicalFile
                if (inOutputs && output.exists() && !output.delete() && output.exists()) error("无法删除 ${output.name}")
            }.exceptionOrNull()
            if (failure != null) {
                _state.update { it.copy(alert = "删除图像失败：${failure.message ?: "无法删除文件"}") }
                return
            }
        }
        _state.update { state -> state.copy(jobs = state.jobs.filter { it.id != id }) }
    }

    fun deleteMusicTrack(track: MusicTrack) {
        if (track.id.startsWith("preset_")) return
        if (_state.value.nowPlayingTrack?.id == track.id) musicEngine.stopPlayback()
        val audio = File(track.audioPath)
        val failure = runCatching {
            if (audio.exists() && !audio.delete() && audio.exists()) error("无法删除 ${audio.name}")
        }.exceptionOrNull()
        if (failure != null) {
            _state.update { it.copy(alert = "删除音乐失败：${failure.message ?: "无法删除文件"}") }
            return
        }
        val sidecar = File(audio.parentFile, "${audio.nameWithoutExtension}.json")
        val sidecarFailed = runCatching { sidecar.exists() && !sidecar.delete() && sidecar.exists() }.getOrDefault(true)
        _state.update { state ->
            state.copy(
                musicPlaylist = state.musicPlaylist.filter { it.id != track.id },
                activeMusicTrack = if (state.activeMusicTrack?.id == track.id) null else state.activeMusicTrack,
                alert = if (sidecarFailed) "音乐已删除，但元数据清理失败：${sidecar.name}" else state.alert
            )
        }
    }

    fun exportPreset(): String = _state.value.settings.toJson().toString(2)

    private suspend fun runJob(job: GenerationJob) {
        if (job.backend.isMoss) {
            runMossJob(job)
            return
        }
        if (_state.value.jobs.firstOrNull { it.id == job.id }?.state != "queued") return
        val settings = job.settings
        val outputRoot = getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir
        val output = File(outputRoot, "outputs/${job.id}.png")
        val partialOutput = File(outputRoot, "outputs/${job.id}.part.png")
        if (!acquireImageTask(job.id)) return
        updateJob(job.id) { current ->
            if (current.state == "queued") current.copy(state = "running", progress = 0, error = null) else current
        }
        if (_state.value.jobs.firstOrNull { it.id == job.id }?.state != "running") {
            finishTask(TaskDomain.Image, job.id) { it.copy(status = "图像生成已取消", error = null) }
            return
        }
        val startTime = System.currentTimeMillis()
        var input: File? = null
        var input2: File? = null
        try {
            if (job.id in cancelledJobIds) throw CancellationException("已取消")
            appendLog(TaskDomain.Image, "图像任务 ${job.id.take(8)} · ${settings.width}×${settings.height} · seed ${settings.seed}", job.id)

            inferenceCoordinator.evictOthers("qwen")
            val dimensions = engineMutex.withLock {
                withContext(Dispatchers.IO) {
                    val status = repository.status()
                    check(status.ready) { "模型尚未准备好：${status.message}" }
                    settings.validateFor(status.manifest)
                    val engineSettings = EngineSettings(
                        turbo = settings.turbo,
                        officialTurbo = settings.officialTurbo,
                        tinyVae = settings.useTinyVae,
                        keepModelsLoaded = _state.value.keepModelsLoaded,
                        halfReference = settings.secondReference,
                        cpuThreads = _state.value.cpuThreads,
                        crashMarker = File(getApplication<Application>().filesDir, "generation.marker")
                    )
                    val modelGeneration = status.manifest.artifacts.joinToString("|") { "${it.path}:${it.sha256}" }
                    val requiredKey = "${engineSettings.key}:$modelGeneration"
                    if (engine == null || engineKey != requiredKey) {
                        appendLog(TaskDomain.Image, "正在载入图像引擎", job.id)
                        val previous = engine
                        engine = null
                        engineKey = null
                        previous?.close()
                        val created = QwenEngine(status.directory, engineSettings)
                        engine = created
                        engineKey = requiredKey
                    }
                    val activeEngine = requireNotNull(engine)
                    if (job.id in cancelledJobIds) throw CancellationException("已取消")
                    input = settings.inputUri?.let { copyUriToCache(it, "${job.id}-input") }
                    input2 = settings.inputUri2?.let { copyUriToCache(it, "${job.id}-input2") }
                    partialOutput.parentFile?.mkdirs()

                    var lastReportedStep = 0
                    val totalSteps = if (settings.officialTurbo) 8 else if (settings.turbo) 6 else settings.steps
                    val callback: (Int) -> Unit = { percent ->
                        if (job.id in cancelledJobIds) throw CancellationException("已取消")
                        updateTask(TaskDomain.Image, job.id) {
                            it.copy(progress = percent, status = "图像生成 · $percent%")
                        }
                        updateJob(job.id) { it.copy(progress = percent) }
                        when {
                            percent == 10 -> appendLog(TaskDomain.Image, "开始 $totalSteps 步采样", job.id)
                            percent in 11..90 -> {
                                val currentStep = (((percent - 10).toFloat() / 80f) * totalSteps).toInt().coerceIn(1, totalSteps)
                                if (currentStep > lastReportedStep) {
                                    lastReportedStep = currentStep
                                    appendLog(TaskDomain.Image, "采样 $currentStep/$totalSteps", job.id)
                                }
                            }
                            percent == 100 -> appendLog(TaskDomain.Image, "采样完成，正在解码", job.id)
                        }
                    }
                    val prompt = if (settings.negativePrompt.isBlank()) settings.prompt
                    else "${settings.prompt}\nAvoid: ${settings.negativePrompt}"
                    val bitmap = if (settings.mode == GenerationMode.TextToImage) {
                        activeEngine.generate(prompt, settings.width, settings.height, settings.steps, settings.seed, partialOutput, callback)
                    } else {
                        activeEngine.edit(prompt, requireNotNull(input), input2, settings.width, settings.height, settings.steps, settings.seed, partialOutput, callback)
                    }
                    try {
                        bitmap.width to bitmap.height
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            if (output.exists() && !output.delete()) throw IllegalStateException("无法替换旧结果")
            if (!partialOutput.renameTo(output)) throw IllegalStateException("无法保存图像结果")
            val elapsed = System.currentTimeMillis() - startTime
            appendLog(TaskDomain.Image, "图像已保存 · ${"%.1f".format(elapsed / 1000.0)}s", job.id)
            updateJob(job.id) {
                it.copy(
                    state = "done",
                    progress = 100,
                    outputPath = output.absolutePath,
                    elapsedMs = elapsed,
                    outputWidth = dimensions.first,
                    outputHeight = dimensions.second,
                    error = null
                )
            }
            finishTask(TaskDomain.Image, job.id) {
                it.copy(progress = 100, status = "图像已保存：${output.name}", error = null, resultPath = output.absolutePath)
            }
        } catch (error: CancellationException) {
            val scopeCancelled = !currentCoroutineContext().isActive
            appendLog(TaskDomain.Image, "图像任务已取消", job.id)
            updateJob(job.id) { it.copy(state = "cancelled", error = "用户取消") }
            finishTask(TaskDomain.Image, job.id) { it.copy(status = "图像生成已取消", error = null) }
            if (scopeCancelled) throw error
        } catch (error: Throwable) {
            val errorMsg = error.message ?: error.toString()
            appendLog(TaskDomain.Image, "图像生成失败：$errorMsg", job.id)
            updateJob(job.id) { it.copy(state = "failed", error = errorMsg) }
            finishTask(TaskDomain.Image, job.id, alert = errorMsg) {
                it.copy(status = "图像生成失败", error = errorMsg)
            }
        } finally {
            partialOutput.delete()
            input?.delete()
            input2?.delete()
            cancelledJobIds -= job.id
        }
    }

    private suspend fun runMossJob(job: GenerationJob) {
        if (_state.value.jobs.firstOrNull { it.id == job.id }?.state != "queued") return
        val settings = job.mossSettings
        if (settings == null) {
            updateJob(job.id) { it.copy(state = "failed", error = "缺少 MOSS 设置") }
            return
        }
        if (!acquireImageTask(job.id)) return
        updateJob(job.id) { current ->
            if (current.state == "queued") current.copy(state = "running", progress = 0, error = null) else current
        }
        if (_state.value.jobs.firstOrNull { it.id == job.id }?.state != "running") {
            finishTask(TaskDomain.Image, job.id) { it.copy(status = "图像生成已取消", error = null) }
            return
        }
        val startTime = System.currentTimeMillis()
        try {
            if (job.id in cancelledJobIds) throw CancellationException("已取消")
            inferenceCoordinator.evictOthers("qwen")
            invalidateEngine()
            val local = settings.local
            appendLog(TaskDomain.Image, "MOSS SFT · 本机 · ${local.width}×${local.height} · ${local.steps} 步", job.id)
            val result = localMossEngine.executeGeneration(job.id, settings) { update -> handleMossUpdate(job.id, update) }
            val elapsed = System.currentTimeMillis() - startTime
            updateJob(job.id) {
                it.copy(
                    state = "done",
                    progress = 100,
                    outputPath = result.file.absolutePath,
                    elapsedMs = elapsed,
                    outputWidth = result.width,
                    outputHeight = result.height,
                    error = null
                )
            }
            finishTask(TaskDomain.Image, job.id) {
                it.copy(progress = 100, status = "MOSS 图像已保存", error = null, resultPath = result.file.absolutePath)
            }
        } catch (error: CancellationException) {
            val scopeCancelled = !currentCoroutineContext().isActive
            appendLog(TaskDomain.Image, "MOSS 任务已取消", job.id)
            updateJob(job.id) { it.copy(state = "cancelled", error = "用户取消") }
            finishTask(TaskDomain.Image, job.id) { it.copy(status = "MOSS 生成已取消", error = null) }
            if (scopeCancelled) throw error
        } catch (error: Throwable) {
            val message = error.message ?: error.toString()
            appendLog(TaskDomain.Image, "MOSS 生成失败：$message", job.id)
            updateJob(job.id) { it.copy(state = "failed", error = message) }
            finishTask(TaskDomain.Image, job.id, alert = message) {
                it.copy(status = "MOSS 生成失败", error = message)
            }
        } finally {
            cancelledJobIds -= job.id
        }
    }

    private fun handleMossUpdate(jobId: String, update: com.tiggy.qwenimage.engine.EngineUpdate) {
        updateTask(TaskDomain.Image, jobId) {
            it.copy(progress = update.progress?.let { value -> (value * 100).toInt() }, status = update.stage)
        }
        if (update.progress != null) updateJob(jobId) { it.copy(progress = (update.progress * 100).toInt()) }
        update.log?.let { appendLog(TaskDomain.Image, it, jobId) }
    }

    private fun compatibleSettings(settings: GenerationSettings, status: ModelStatus): GenerationSettings {
        if (status.total == 0) return settings
        val manifest = status.manifest
        val canEdit = "image-edit" in manifest.features
        val officialTurbo = settings.officialTurbo && "official-turbo" in manifest.features
        val turbo = settings.turbo && "turbo" in manifest.features
        val lostTurbo = (settings.officialTurbo && !officialTurbo) || (settings.turbo && !turbo)
        val useTinyVae = when {
            settings.useTinyVae && manifest.hasVae(true) -> true
            !settings.useTinyVae && manifest.hasVae(false) -> false
            else -> manifest.hasVae(true)
        }
        return settings.copy(
            mode = if (canEdit) settings.mode else GenerationMode.TextToImage,
            officialTurbo = officialTurbo,
            turbo = turbo,
            useTinyVae = useTinyVae,
            secondReference = settings.secondReference && canEdit,
            steps = if (lostTurbo) settings.steps.coerceAtLeast(20) else settings.steps
        )
    }

    private suspend fun invalidateEngine() {
        engineMutex.withLock {
            withContext(Dispatchers.IO) {
                val previous = engine
                engine = null
                engineKey = null
                previous?.close()
            }
        }
    }

    private fun persistJobs(jobs: List<GenerationJob>, commit: Boolean = false) {
        val editor = preferences.edit().putString("jobs", JSONArray(jobs.takeLast(100).map { it.toJson() }).toString())
        if (commit) editor.commit() else editor.apply()
    }

    private fun updateJob(id: String, transform: (GenerationJob) -> GenerationJob) = _state.update { state ->
        state.copy(jobs = state.jobs.map { if (it.id == id) transform(it) else it })
    }

    private suspend fun copyUriToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val target = File(getApplication<Application>().cacheDir, "$name.img")
        try {
            getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取参考图" }
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 50L * 1024 * 1024) throw IllegalArgumentException("参考图不能超过 50 MB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    override fun onCleared() {
        persistJobs(_state.value.jobs, commit = true)
        queue.close()
        engine?.cancel()
        val owner = _state.value.activeTask?.id
        Thread({
            kotlinx.coroutines.runBlocking {
                engineMutex.withLock {
                    val closing = engine
                    engine = null
                    engineKey = null
                    closing?.close()
                }
            }
            inferenceCoordinator.unregisterEvictor(evictionKey)
            if (owner != null) inferenceCoordinator.release("qwen:$owner")
        }, "qwen-engine-close").start()
        localMossEngine.close()
        musicEngine.close()
        videoEngine.close()
        super.onCleared()
    }

    private fun Double.format1(): String = "%.1f".format(this)
}
