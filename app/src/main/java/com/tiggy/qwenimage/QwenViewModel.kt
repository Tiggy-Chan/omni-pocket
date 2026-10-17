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
import com.tiggy.qwenimage.data.ModelRepository
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicStudioTab
import com.tiggy.qwenimage.data.MusicStylePreset
import com.tiggy.qwenimage.data.MusicTrack
import com.tiggy.qwenimage.data.WorkspaceTab
import com.tiggy.qwenimage.engine.EngineSettings
import com.tiggy.qwenimage.engine.MusicEngine
import com.tiggy.qwenimage.engine.QwenEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val isMusicPlaying: Boolean = false,
    val musicPositionMs: Int = 0,
    val musicDurationMs: Int = 0
)

class QwenViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ModelRepository(application)
    private val preferences = application.getSharedPreferences("qwen-pocket", Application.MODE_PRIVATE)
    private val restoredJobs = runCatching {
        val array = JSONArray(preferences.getString("jobs", "[]"))
        (0 until array.length()).map { GenerationJob.fromJson(array.getJSONObject(it)) }
            .filter { it.state != "running" }
            .map { if (it.state == "running") it.copy(state = "queued") else it }
    }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(
        QwenUiState(repo = repository.sourceRepo, revision = repository.revision, jobs = restoredJobs,
            modelStatus = repository.status())
    )
    val state: StateFlow<QwenUiState> = _state.asStateFlow()
    private val queue = Channel<GenerationJob>(Channel.UNLIMITED)
    private val engineMutex = Mutex()
    private var engine: QwenEngine? = null
    private var engineKey: String? = null
    private val musicEngine = MusicEngine(application)

    init {
        val initialTracks = musicEngine.preloadInitialTracks()
        _state.update {
            it.copy(
                musicPlaylist = initialTracks,
                activeMusicTrack = initialTracks.firstOrNull()
            )
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
            musicEngine.activeTrack.collect { trk -> if (trk != null) _state.update { it.copy(activeMusicTrack = trk) } }
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

    fun setDomain(domain: AppDomain) = _state.update { it.copy(domain = domain) }
    fun selectTab(tab: WorkspaceTab) = _state.update { it.copy(tab = tab) }
    fun selectMusicTab(tab: MusicStudioTab) = _state.update { it.copy(musicTab = tab) }

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
    fun applyMusicPreset(preset: MusicStylePreset) = _state.update {
        it.copy(
            musicSettings = it.musicSettings.copy(
                title = "《${preset.name}》",
                style = preset.stylePrompt,
                lyrics = preset.sampleLyrics,
                bpm = preset.bpm,
                cotMode = preset.defaultCot
            )
        )
    }

    // Music playback
    fun playMusic(track: MusicTrack) = musicEngine.playTrack(track)
    fun toggleMusicPlayPause() = musicEngine.togglePlayPause()
    fun seekMusic(positionMs: Int) = musicEngine.seekTo(positionMs)
    fun stopMusic() = musicEngine.stopPlayback()
    fun setActiveTrack(track: MusicTrack) = _state.update { it.copy(activeMusicTrack = track) }

    fun startMusicGeneration() {
        if (_state.value.isBusy) return
        val current = _state.value
        val settings = current.musicSettings
        runCatching { settings.validate() }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "参数校验失败") }
            return
        }

        val job = MusicJob(
            id = UUID.randomUUID().toString(),
            title = settings.title.ifBlank { "未命名曲目" },
            settings = settings,
            state = "running",
            stageDescription = "正在规划生成..."
        )

        _state.update {
            it.copy(
                isBusy = true,
                error = null,
                musicJobs = it.musicJobs + job,
                status = "准备端侧生成音乐..."
            )
        }
        appendLog("----------------------------------------")
        appendLog("音乐任务启动 [${job.id.take(8)}]：《${settings.title}》")
        appendLog("曲风描述：${settings.style.take(60)}...")
        appendLog("规格：48kHz 双声道高保真 | CoT: ${settings.cotMode.tag} | 速度: ${settings.bpm} BPM | 步数: ${settings.odeSteps}")
        appendLog("计算调度：Snapdragon 8 Gen 3 (Adreno 750 OpenCL + Cortex-X4 CPU)")

        viewModelScope.launch {
            runCatching {
                musicEngine.executeGeneration(job) { progress, stage, log ->
                    _state.update {
                        it.copy(
                            progress = (progress * 100).toInt(),
                            status = stage
                        )
                    }
                    appendLog(log)
                }
            }.onSuccess { track ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        progress = 100,
                        status = "音乐生成完毕：${track.title}",
                        musicPlaylist = listOf(track) + it.musicPlaylist.filter { old -> old.id != track.id },
                        activeMusicTrack = track
                    )
                }
                appendLog("音频生成全流程完成，已存入曲库：${track.title}")
                musicEngine.playTrack(track)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        status = "音乐生成失败",
                        error = error.message ?: error.toString()
                    )
                }
                appendLog("音乐生成异常: ${error.message}")
            }
        }
    }

    fun setMode(mode: GenerationMode) = _state.update { it.copy(settings = it.settings.copy(mode = mode)) }
    fun setPrompt(prompt: String) = _state.update { it.copy(settings = it.settings.copy(prompt = prompt)) }
    fun setNegativePrompt(prompt: String) = _state.update { it.copy(settings = it.settings.copy(negativePrompt = prompt)) }
    fun setSteps(steps: Int) = _state.update { it.copy(settings = it.settings.copy(steps = steps.coerceIn(6, 40))) }
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
    fun setTinyVae(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(useTinyVae = value)) }
    fun setSecondReference(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(secondReference = value)) }
    fun setInput(uri: Uri?, second: Boolean = false) = _state.update {
        if (uri != null) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        it.copy(settings = if (second) it.settings.copy(inputUri2 = uri)
        else it.settings.copy(inputUri = uri, mode = GenerationMode.ImageEdit))
    }
    fun setRepo(repo: String) = _state.update { it.copy(repo = repo) }
    fun setRevision(revision: String) = _state.update { it.copy(revision = revision) }
    fun setCpuThreads(threads: Int) = _state.update { it.copy(cpuThreads = threads.coerceIn(2, 8)) }
    fun setKeepModelsLoaded(value: Boolean) = _state.update { it.copy(keepModelsLoaded = value) }

    fun setTransparent(value: Boolean) = _state.update { it.copy(settings = it.settings.copy(transparent = value)) }

    fun useSettings(settings: GenerationSettings) = _state.update {
        it.copy(settings = settings, tab = WorkspaceTab.Generate)
    }

    fun refreshModelStatus() = _state.update { it.copy(modelStatus = repository.status()) }

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
            }.onSuccess {
                _state.update { it.copy(isBusy = false, progress = 100, modelStatus = repository.status(), status = "模型已校验") }
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
            }.onSuccess {
                _state.update { it.copy(isBusy = false, modelStatus = repository.status(), status = "模型导入并校验完成") }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, status = "模型导入失败", error = error.message ?: error.toString()) }
            }
        }
    }

    fun enqueue() {
        val current = _state.value
        if (current.modelStatus?.ready != true) {
            _state.update { it.copy(error = "请先完成模型校验") }
            return
        }
        val prompt = current.settings.prompt.trim()
        val prepared = current.settings.copy(
            prompt = if (current.settings.transparent && !prompt.contains("transparent", ignoreCase = true))
                "This is an RGBA image with transparency. $prompt The background is transparent." else prompt
        )
        runCatching { prepared.validate() }.onFailure { error ->
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
            _state.update { it.copy(error = "native 推理已经开始，本次会在当前步骤结束后完成；请等待结果") }
        }
    }

    fun exportPreset(): String = _state.value.settings.toJson().toString(2)

    private suspend fun runJob(job: GenerationJob) {
        if (_state.value.jobs.firstOrNull { it.id == job.id }?.state != "queued") return
        updateJob(job.id) { it.copy(state = "running", progress = 0) }
        val settings = job.settings
        val output = File(getApplication<Application>().getExternalFilesDir(null), "outputs/${job.id}.png")
        val startTime = System.currentTimeMillis()
        try {
            _state.update { it.copy(isBusy = true, progress = 0, status = "正在初始化生成任务…") }
            appendLog("----------------------------------------")
            appendLog("任务启动 [${job.id.take(8)}]：${settings.prompt.take(45)}")
            appendLog("规格：${settings.width}x${settings.height} | 步数：${if (settings.officialTurbo) "8 (官方Turbo)" else if (settings.turbo) "6 (LoRA)" else "${settings.steps}"} | 种子：${settings.seed}")
            appendLog("后端调度：DiT (Adreno 750 OpenCL) + 文本编码 (CPU 6线程)")

            engineMutex.withLock {
                withContext(Dispatchers.IO) {
                    val source = repository.modelDir
                    val status = repository.status()
                    check(status.ready) { "模型尚未准备好：${status.message}" }
                    appendLog("准备模型资源，检查运行时内存...")
                    val engineSettings = EngineSettings(
                        turbo = settings.turbo,
                        officialTurbo = settings.officialTurbo,
                        tinyVae = settings.useTinyVae,
                        keepModelsLoaded = _state.value.keepModelsLoaded,
                        halfReference = settings.secondReference,
                        cpuThreads = _state.value.cpuThreads,
                        crashMarker = File(getApplication<Application>().filesDir, "generation.marker")
                    )
                    if (engine == null || engineKey != engineSettings.key) {
                        appendLog("载入 Native 引擎与着色器缓存 (.mnn_cl_cache)...")
                        engine?.close()
                        engine = QwenEngine(source, engineSettings)
                        engineKey = engineSettings.key
                    }
                    val input = settings.inputUri?.let { copyUriToCache(it, "${job.id}-input") }
                    val input2 = settings.inputUri2?.let { copyUriToCache(it, "${job.id}-input2") }
                    output.parentFile?.mkdirs()

                    appendLog("执行文本编码 (Qwen3-VL-8B CPU int4)...")
                    var lastReportedStep = 0
                    val totalSteps = if (settings.officialTurbo) 8 else if (settings.turbo) 6 else settings.steps
                    val callback: (Int) -> Unit = { percent ->
                        _state.update { it.copy(progress = percent, status = "任务 ${job.id.take(8)} · $percent%") }
                        updateJob(job.id) { it.copy(progress = percent) }
                        when {
                            percent in 3..5 -> {
                                appendLog("文本特征提取完成，卸载释放 CPU 编码器内存")
                                appendLog("载入 DiT 扩散模型 (Adreno GPU / OpenCL)，构建 Prefix KV 缓存...")
                            }
                            percent == 10 -> {
                                appendLog("Prefix 缓存就绪，开始去噪采样 (共 $totalSteps 步)...")
                            }
                            percent in 11..90 -> {
                                val currentStep = (((percent - 10).toFloat() / 80f) * totalSteps).toInt().coerceIn(1, totalSteps)
                                if (currentStep > lastReportedStep) {
                                    lastReportedStep = currentStep
                                    appendLog("DiT 采样 步骤 $currentStep/$totalSteps (进度 $percent%)")
                                }
                            }
                            percent == 100 -> {
                                appendLog("采样完成，Tiny VAE GPU 原生解码 RGBA 并落盘...")
                            }
                        }
                    }
                    if (settings.mode == GenerationMode.TextToImage) {
                        val prompt = if (settings.negativePrompt.isBlank()) settings.prompt
                        else "${settings.prompt}\nAvoid: ${settings.negativePrompt}"
                        engine!!.generate(prompt, settings.width, settings.height, settings.steps, settings.seed, output, callback)
                    } else {
                        val prompt = if (settings.negativePrompt.isBlank()) settings.prompt
                        else "${settings.prompt}\nAvoid: ${settings.negativePrompt}"
                        engine!!.edit(prompt, requireNotNull(input), input2, settings.width, settings.height, settings.steps, settings.seed, output, callback)
                    }
                }
            }
            val elapsed = ((System.currentTimeMillis() - startTime) / 1000.0)
            appendLog("生成完成，总耗时 ${"%.1f".format(elapsed)}s，已保存至 outputs/${output.name}")
            updateJob(job.id) { it.copy(state = "done", progress = 100, outputPath = output.absolutePath, elapsedMs = System.currentTimeMillis() - startTime) }
            _state.update { it.copy(resultPath = output.absolutePath, progress = 100, status = "生成完成：${output.name}") }
        } catch (error: Throwable) {
            val errorMsg = error.message ?: error.toString()
            appendLog("生成失败：$errorMsg")
            updateJob(job.id) { it.copy(state = "failed", error = errorMsg) }
            _state.update { it.copy(status = "生成失败", error = errorMsg) }
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    private fun updateJob(id: String, transform: (GenerationJob) -> GenerationJob) = _state.update { state ->
        val next = state.jobs.map { if (it.id == id) transform(it) else it }
        preferences.edit().putString("jobs", JSONArray(next.takeLast(100).map { it.toJson() }).toString()).apply()
        state.copy(jobs = next)
    }

    private suspend fun copyUriToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val target = File(getApplication<Application>().cacheDir, "$name.img")
        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取参考图" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target
    }

    override fun onCleared() {
        queue.close()
        engine?.close()
        musicEngine.close()
        super.onCleared()
    }

    private fun Double.format1(): String = "%.1f".format(this)
}
