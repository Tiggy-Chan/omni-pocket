package com.tiggy.qwenimage

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.GenerationSettings
import com.tiggy.qwenimage.data.ModelRepository
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.WorkspaceTab
import com.tiggy.qwenimage.engine.EngineSettings
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
import java.util.UUID

data class QwenUiState(
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
    val keepModelsLoaded: Boolean = false
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

    init {
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

    fun selectTab(tab: WorkspaceTab) = _state.update { it.copy(tab = tab) }
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
        if (current.isBusy || current.modelStatus?.ready != true) {
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
        _state.update { it.copy(jobs = it.jobs + job, tab = WorkspaceTab.Queue, error = null, status = "任务已加入队列") }
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
        try {
            engineMutex.withLock {
                withContext(Dispatchers.IO) {
                    val source = repository.modelDir
                    val status = repository.status()
                    check(status.ready) { "模型尚未准备好：${status.message}" }
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
                        engine?.close()
                        engine = QwenEngine(source, engineSettings)
                        engineKey = engineSettings.key
                    }
                    val input = settings.inputUri?.let { copyUriToCache(it, "${job.id}-input") }
                    val input2 = settings.inputUri2?.let { copyUriToCache(it, "${job.id}-input2") }
                    output.parentFile?.mkdirs()
                    val callback: (Int) -> Unit = { percent ->
                        _state.update { it.copy(progress = percent, status = "任务 ${job.id.take(8)} · $percent%") }
                        updateJob(job.id) { it.copy(progress = percent) }
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
            updateJob(job.id) { it.copy(state = "done", progress = 100, outputPath = output.absolutePath) }
            _state.update { it.copy(resultPath = output.absolutePath, progress = 100, status = "生成完成：${output.name}") }
        } catch (error: Throwable) {
            updateJob(job.id) { it.copy(state = "failed", error = error.message ?: error.toString()) }
            _state.update { it.copy(status = "生成失败", error = error.message ?: error.toString()) }
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
        super.onCleared()
    }

    private fun Double.format1(): String = "%.1f".format(this)
}
