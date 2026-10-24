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
import com.tiggy.qwenimage.data.ModelManifest
import com.tiggy.qwenimage.data.ModelRepository
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicStudioTab
import com.tiggy.qwenimage.data.MusicTrack
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoJob
import com.tiggy.qwenimage.data.VideoSettings
import com.tiggy.qwenimage.data.VideoStudioTab
import com.tiggy.qwenimage.data.WorkspaceTab
import com.tiggy.qwenimage.data.hasVae
import com.tiggy.qwenimage.data.validateFor
import com.tiggy.qwenimage.engine.EngineSettings
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
    val jobs: List<GenerationJob> = emptyList(),
    val modelStatus: ModelStatus? = null,
    val repo: String = "",
    val revision: String = "main",
    val isBusy: Boolean = false,
    val progress: Int = 0,
    val status: String = "准备就绪",
    val resultPath: String? = null,
    val error: String? = null,
    val cpuThreads: Int = 6,
    val keepModelsLoaded: Boolean = false,
    val liveLogs: List<String> = emptyList(),
    // Music Studio state
    val musicTab: MusicStudioTab = MusicStudioTab.Compose,
    val musicSettings: MusicSettings = MusicSettings(),
    val musicJobs: List<MusicJob> = emptyList(),
    val musicPlaylist: List<MusicTrack> = emptyList(),
    val activeMusicTrack: MusicTrack? = null,
    val nowPlayingTrack: MusicTrack? = null,
    val isMusicPlaying: Boolean = false,
    val musicPositionMs: Int = 0,
    val musicDurationMs: Int = 0,
    // Video Studio state (MobileWan 3-Step Decoupled DMD)
    val videoTab: VideoStudioTab = VideoStudioTab.Compose,
    val videoSettings: VideoSettings = VideoSettings(),
    val videoJobs: List<VideoJob> = emptyList(),
    val videoGallery: List<VideoItem> = emptyList(),
    val activeVideo: VideoItem? = null
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class QwenViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ModelRepository(application)
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
            modelStatus = repository.status())
    )
    val state: StateFlow<QwenUiState> = _state.asStateFlow()
    private val queue = Channel<GenerationJob>(Channel.UNLIMITED)
    private val engineMutex = Mutex()
    private val cancelledJobIds = ConcurrentHashMap.newKeySet<String>()
    private var engine: QwenEngine? = null
    private var engineKey: String? = null
    private val musicEngine = MusicEngine(application)
    private val videoEngine = VideoEngine(application)

    init {
        val crash = QwenImage21.readCrashMarker(File(application.filesDir, "generation.marker"))
        val initialStatus = repository.status()
        _state.update {
            it.copy(
                settings = compatibleSettings(it.settings, initialStatus),
                modelStatus = initialStatus,
                status = crash?.let { detail -> "上次图像任务中断：$detail" } ?: it.status
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
                    _state.update { it.copy(error = message) }
                    musicEngine.clearPlaybackError()
                }
            }
        }

        viewModelScope.launch {
            val current = repository.status()
            if (!current.ready && current.total > 0 && current.present == current.total) {
                _state.update { it.copy(isBusy = true, modelStatus = current, status = "发现完整模型，正在手机端校验 SHA-256…") }
                runCatching { repository.verify { file -> _state.update { it.copy(status = "校验 $file…") } } }
                    .onSuccess { _state.update { it.copy(isBusy = false, modelStatus = repository.status(), status = "模型已校验，可以生成") } }
                    .onFailure { error -> _state.update { it.copy(isBusy = false, status = "模型校验失败", error = error.message ?: error.toString()) } }
            }
        }
        viewModelScope.launch {
            restoredJobs.filter { it.state == "queued" }.forEach { queue.send(it) }
            for (job in queue) runJob(job)
        }
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _state.update {
            it.copy(liveLogs = (it.liveLogs + "[$time] $line").takeLast(80))
        }
    }

    fun setDomain(domain: AppDomain) {
        if (domain != AppDomain.MusicStudio) musicEngine.pausePlayback()
        _state.update { it.copy(domain = domain) }
    }

    fun selectTab(tab: WorkspaceTab) = _state.update { it.copy(tab = tab) }
    fun selectMusicTab(tab: MusicStudioTab) = _state.update { it.copy(musicTab = tab) }
    fun selectVideoTab(tab: VideoStudioTab) = _state.update { it.copy(videoTab = tab) }

    // Video settings modifiers
    fun setVideoPrompt(prompt: String) = _state.update { it.copy(videoSettings = it.videoSettings.copy(prompt = prompt)) }
    fun setVideoSeed(seed: Long) = _state.update { it.copy(videoSettings = it.videoSettings.copy(seed = seed)) }
    fun setVideoFps(fps: Int) = _state.update { it.copy(videoSettings = it.videoSettings.copy(fps = fps.coerceIn(8, 30))) }
    fun setVideoSteps(steps: Int) = _state.update { it.copy(videoSettings = it.videoSettings.copy(steps = steps.coerceIn(1, 10))) }
    fun setVideoShift(shift: Float) = _state.update { it.copy(videoSettings = it.videoSettings.copy(shift = shift.coerceIn(1.0f, 10.0f))) }
    fun setVideoServerUrl(url: String) = _state.update { it.copy(videoSettings = it.videoSettings.copy(serverUrl = url)) }
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
        runCatching { File(video.videoPath).delete() }
        video.thumbnailPath?.let { runCatching { File(it).delete() } }
        _state.update { state ->
            val nextList = state.videoGallery.filter { it.id != video.id }
            state.copy(
                videoGallery = nextList,
                activeVideo = if (state.activeVideo?.id == video.id) nextList.firstOrNull() else state.activeVideo
            )
        }
    }

    fun startVideoGeneration() {
        if (_state.value.isBusy) return
        val current = _state.value
        val settings = current.videoSettings
        runCatching { settings.validate() }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "参数校验失败") }
            return
        }

        val job = VideoJob(
            id = UUID.randomUUID().toString(),
            settings = settings,
            state = "running",
            stage = "准备端侧生成视频..."
        )

        _state.update {
            it.copy(
                isBusy = true,
                error = null,
                videoJobs = it.videoJobs + job,
                status = if (settings.inputImageUri != null) "准备 Wan2.2 图生视频..." else "准备 Wan2.2 文生视频..."
            )
        }
        appendLog("----------------------------------------")
        appendLog("视频任务启动 [${job.id.take(8)}]：Wan2.2-TI2V-5B-Turbo")
        appendLog("提示词：${settings.prompt.take(60)}...")
        appendLog("模式：${if (settings.inputImageUri != null) "图生视频（App 所选参考首帧）" else "文生视频"}")
        appendLog("规格：${settings.width}×${settings.height} | 实际最多25帧 | ${settings.fps}fps | ${settings.steps.coerceIn(1, 6)}步 Euler")
        appendLog("执行设备：本机 Wan2.2 模型与原生推理 CLI；diffusion=GPUOpenCL, text encoder=CPU, VAE=CPU")

        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                videoEngine.executeGeneration(job) { update ->
                    _state.update { currentState ->
                        currentState.copy(
                            progress = update.progress?.let { (it * 100).toInt() } ?: currentState.progress,
                            status = update.stage
                        )
                    }
                    update.log?.let(::appendLog)
                }
            }.onSuccess { videoItem ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        progress = 100,
                        status = "视频已保存：${File(videoItem.videoPath).name}",
                        videoGallery = listOf(videoItem) + it.videoGallery.filter { old -> old.id != videoItem.id },
                        activeVideo = videoItem
                    )
                }
                appendLog("视频已存入作品库：${videoItem.videoPath}")
            }.onFailure { error ->
                val cancelled = error is kotlinx.coroutines.CancellationException
                _state.update {
                    it.copy(
                        isBusy = false,
                        status = if (cancelled) "视频生成已取消" else "视频生成失败",
                        error = if (cancelled) null else error.message ?: error.toString()
                    )
                }
                appendLog(if (cancelled) "视频生成已取消" else "视频生成异常: ${error.message}")
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
    fun setMusicOdeSteps(steps: Int) = _state.update { it.copy(musicSettings = it.musicSettings.copy(odeSteps = steps.coerceIn(8, 64))) }
    fun setMusicGuidanceScale(scale: Float) = _state.update { it.copy(musicSettings = it.musicSettings.copy(guidanceScale = scale)) }
    fun setMusicServerUrl(url: String) = _state.update { it.copy(musicSettings = it.musicSettings.copy(serverUrl = url)) }

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
    fun cancelMusicGeneration() = musicEngine.cancelGeneration()
    fun setActiveTrack(track: MusicTrack) = _state.update { it.copy(activeMusicTrack = track) }

    fun startMusicGeneration() {
        if (_state.value.isBusy) return
        val settings = _state.value.musicSettings
        runCatching { settings.validate() }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "参数校验失败") }
            return
        }

        val job = MusicJob(
            id = UUID.randomUUID().toString(),
            title = settings.title.ifBlank { "未命名曲目" },
            settings = settings,
            state = "running",
            stageDescription = "等待工作站"
        )

        _state.update {
            it.copy(
                isBusy = true,
                error = null,
                musicJobs = it.musicJobs + job,
                status = "正在连接 YuE2 工作站"
            )
        }
        appendLog("音乐任务 ${job.id.take(8)} · ${settings.bpm} BPM · ${settings.cotMode.tag} · ${settings.odeSteps} 步")

        viewModelScope.launch {
            runCatching {
                musicEngine.executeGeneration(job) { update ->
                    _state.update { currentState ->
                        currentState.copy(
                            progress = update.progress?.let { (it * 100).toInt() } ?: currentState.progress,
                            status = update.stage,
                            musicJobs = currentState.musicJobs.map { item ->
                                if (item.id == job.id) item.copy(
                                    progress = update.progress ?: item.progress,
                                    stageDescription = update.stage
                                ) else item
                            }
                        )
                    }
                    update.log?.let(::appendLog)
                }
            }.onSuccess { track ->
                _state.update { currentState ->
                    currentState.copy(
                        isBusy = false,
                        progress = 100,
                        status = "音乐已保存：${track.title}",
                        musicJobs = currentState.musicJobs.map { item ->
                            if (item.id == job.id) item.copy(state = "completed", progress = 1f, stageDescription = "已保存", resultAudioPath = track.audioPath) else item
                        },
                        musicPlaylist = listOf(track) + currentState.musicPlaylist.filter { old -> old.id != track.id },
                        activeMusicTrack = track
                    )
                }
                appendLog("音频已存入曲库：${track.title}")
                musicEngine.playTrack(track)
            }.onFailure { error ->
                val cancelled = error is kotlinx.coroutines.CancellationException
                _state.update { currentState ->
                    currentState.copy(
                        isBusy = false,
                        status = if (cancelled) "音乐生成已取消" else "音乐生成失败",
                        error = if (cancelled) null else error.message ?: error.toString(),
                        musicJobs = currentState.musicJobs.map { item ->
                            if (item.id == job.id) item.copy(
                                state = if (cancelled) "cancelled" else "failed",
                                stageDescription = if (cancelled) "已取消" else "生成失败",
                                error = if (cancelled) null else error.message
                            ) else item
                        }
                    )
                }
                appendLog(if (cancelled) "音乐生成已取消" else "音乐生成异常：${error.message}")
            }
        }
    }

    fun setMode(mode: GenerationMode) = _state.update { state ->
        val unsupported = mode == GenerationMode.ImageEdit && state.modelStatus?.let { it.total > 0 && "image-edit" !in it.manifest.features } == true
        if (unsupported) state.copy(error = "当前模型不支持图片编辑")
        else state.copy(settings = state.settings.copy(mode = mode), error = null)
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
        if (unsupported) state.copy(error = if (value) "当前模型缺少 Tiny VAE" else "当前模型缺少完整 VAE")
        else state.copy(settings = state.settings.copy(useTinyVae = value), error = null)
    }
    fun setSecondReference(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(secondReference = value)) }
    fun setInput(uri: Uri?, second: Boolean = false) = _state.update { state ->
        if (uri != null) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (second && uri != null && state.settings.inputUri == null) {
            state.copy(error = "请先选择第一张参考图")
        } else {
            val settings = if (second) state.settings.copy(inputUri2 = uri)
            else state.settings.copy(
                inputUri = uri,
                inputUri2 = if (uri == null) null else state.settings.inputUri2,
                mode = if (uri != null) GenerationMode.ImageEdit else state.settings.mode
            )
            state.copy(settings = settings, error = null)
        }
    }
    fun setRepo(repo: String) = _state.update { it.copy(repo = repo) }
    fun setRevision(revision: String) = _state.update { it.copy(revision = revision) }
    fun setCpuThreads(threads: Int) = _state.update { it.copy(cpuThreads = threads.coerceIn(2, 8)) }
    fun setKeepModelsLoaded(value: Boolean) = _state.update { it.copy(keepModelsLoaded = value) }

    fun setTransparent(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(transparent = value)) }

    fun useSettings(settings: GenerationSettings) = _state.update { state ->
        val compatible = state.modelStatus?.let { compatibleSettings(settings, it) } ?: settings
        state.copy(settings = compatible, tab = WorkspaceTab.Generate)
    }

    fun refreshModelStatus() = _state.update { state ->
        val status = repository.status()
        state.copy(modelStatus = status, settings = compatibleSettings(state.settings, status))
    }

    fun checkRuntime() {
        if (_state.value.isBusy || _state.value.jobs.any { it.state == "running" }) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, status = "检查 MNN/OpenCL 推理引擎…", error = null) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    check(repository.status().ready) { "请先完成模型校验" }
                    QwenEngine(repository.modelDir, EngineSettings(false, false, true, false, false,
                        _state.value.cpuThreads, File(getApplication<Application>().filesDir, "diagnostic.marker"))).use { }
                    QwenEngine.availableMemoryMb()
                }
            }
            result.onSuccess { memory ->
                _state.update { it.copy(isBusy = false, status = "MNN 推理引擎已初始化，可用内存 ${memory}MB") }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: error.toString(), status = "推理引擎检查失败") }
            }
        }
    }

    fun downloadModels() {
        val snapshot = _state.value
        repository.sourceRepo = snapshot.repo
        repository.revision = snapshot.revision
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, progress = 0, status = "读取模型清单…", error = null) }
            runCatching {
                repository.download { file, done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                    _state.update { it.copy(progress = percent, status = "下载 $file · ${(done / 1_000_000_000.0).format1()} / ${(total / 1_000_000_000.0).format1()} GB") }
                }
                invalidateEngine()
            }.onSuccess {
                val status = repository.status()
                _state.update {
                    it.copy(
                        isBusy = false,
                        progress = 100,
                        modelStatus = status,
                        settings = compatibleSettings(it.settings, status),
                        status = "模型已校验"
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, status = "模型下载失败", error = error.message ?: error.toString()) }
            }
        }
    }

    fun importModels(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, status = "导入模型…", error = null) }
            runCatching {
                repository.importTree(uri) { text -> _state.update { it.copy(status = text) } }
                invalidateEngine()
            }.onSuccess {
                val status = repository.status()
                _state.update {
                    it.copy(
                        isBusy = false,
                        modelStatus = status,
                        settings = compatibleSettings(it.settings, status),
                        status = "模型导入并校验完成"
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, status = "模型导入失败", error = error.message ?: error.toString()) }
            }
        }
    }

    fun enqueue() {
        val current = _state.value
        val modelStatus = current.modelStatus
        if (modelStatus?.ready != true) {
            _state.update { it.copy(error = "请先完成模型校验") }
            return
        }
        val prompt = current.settings.prompt.trim()
        val prepared = current.settings.copy(
            prompt = if (current.settings.transparent && !prompt.contains("transparent", ignoreCase = true))
                "This is an RGBA image with transparency. $prompt The background is transparent." else prompt
        )
        runCatching { prepared.validateFor(modelStatus.manifest) }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "参数无效") }
            return
        }
        val job = GenerationJob(UUID.randomUUID().toString(), prepared)
        _state.update { it.copy(jobs = it.jobs + job, error = null, status = "任务已加入队列") }
        queue.trySend(job)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun cancelJob(id: String) {
        val job = _state.value.jobs.firstOrNull { it.id == id } ?: return
        if (job.state == "queued") {
            updateJob(id) { it.copy(state = "cancelled", error = "用户取消") }
            _state.update { it.copy(status = "任务已取消") }
        } else if (job.state == "running") {
            cancelledJobIds += id
            engine?.cancel()
            _state.update { it.copy(status = "正在取消，将在当前计算阶段结束后停止") }
        }
    }

    fun exportPreset(): String = _state.value.settings.toJson().toString(2)

    private suspend fun runJob(job: GenerationJob) {
        if (_state.value.jobs.firstOrNull { it.id == job.id }?.state != "queued") return
        updateJob(job.id) { it.copy(state = "running", progress = 0, error = null) }
        val settings = job.settings
        val outputRoot = getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir
        val output = File(outputRoot, "outputs/${job.id}.png")
        val partialOutput = File(outputRoot, "outputs/${job.id}.part.png")
        val startTime = System.currentTimeMillis()
        var input: File? = null
        var input2: File? = null
        try {
            if (job.id in cancelledJobIds) throw CancellationException("已取消")
            _state.update { it.copy(isBusy = true, progress = 0, status = "正在初始化图像任务") }
            appendLog("图像任务 ${job.id.take(8)} · ${settings.width}×${settings.height} · seed ${settings.seed}")

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
                        appendLog("正在载入图像引擎")
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
                        _state.update { it.copy(progress = percent, status = "图像生成 · $percent%") }
                        updateJob(job.id) { it.copy(progress = percent) }
                        when {
                            percent == 10 -> appendLog("开始 $totalSteps 步采样")
                            percent in 11..90 -> {
                                val currentStep = (((percent - 10).toFloat() / 80f) * totalSteps).toInt().coerceIn(1, totalSteps)
                                if (currentStep > lastReportedStep) {
                                    lastReportedStep = currentStep
                                    appendLog("采样 $currentStep/$totalSteps")
                                }
                            }
                            percent == 100 -> appendLog("采样完成，正在解码")
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
            appendLog("图像已保存 · ${"%.1f".format(elapsed / 1000.0)}s")
            updateJob(job.id) {
                it.copy(
                    state = "done",
                    progress = 100,
                    outputPath = output.absolutePath,
                    elapsedMs = elapsed,
                    outputWidth = dimensions.first,
                    outputHeight = dimensions.second
                )
            }
            _state.update { it.copy(resultPath = output.absolutePath, progress = 100, status = "图像已保存：${output.name}") }
        } catch (error: CancellationException) {
            val scopeCancelled = !currentCoroutineContext().isActive
            appendLog("图像任务已取消")
            updateJob(job.id) { it.copy(state = "cancelled", error = "用户取消") }
            _state.update { it.copy(status = "图像生成已取消", error = null) }
            if (scopeCancelled) throw error
        } catch (error: Throwable) {
            val errorMsg = error.message ?: error.toString()
            appendLog("图像生成失败：$errorMsg")
            updateJob(job.id) { it.copy(state = "failed", error = errorMsg) }
            _state.update { it.copy(status = "图像生成失败", error = errorMsg) }
        } finally {
            partialOutput.delete()
            input?.delete()
            input2?.delete()
            cancelledJobIds -= job.id
            _state.update { it.copy(isBusy = false) }
        }
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
        Thread({
            kotlinx.coroutines.runBlocking {
                engineMutex.withLock {
                    val closing = engine
                    engine = null
                    engineKey = null
                    closing?.close()
                }
            }
        }, "qwen-engine-close").start()
        musicEngine.close()
        videoEngine.close()
        super.onCleared()
    }

    private fun Double.format1(): String = "%.1f".format(this)
}
