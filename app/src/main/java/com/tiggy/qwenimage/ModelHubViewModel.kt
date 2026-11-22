package com.tiggy.qwenimage

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.DownloadManagerState
import com.tiggy.qwenimage.data.HfRepoOption
import com.tiggy.qwenimage.data.HfRepoSummary
import com.tiggy.qwenimage.data.HubConnectivityChecker
import com.tiggy.qwenimage.data.HubMirrorMode
import com.tiggy.qwenimage.data.HubPreferences
import com.tiggy.qwenimage.data.HubProbeResult
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.HubSettings
import com.tiggy.qwenimage.data.HuggingFaceHubClient
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.ModelDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StorageSummary(
    val modelsUsedBytes: Long = 0L,
    val availableBytes: Long = 0L
)

data class ModelHubUiState(
    val hubSettings: HubSettings = HubSettings(),
    val probing: Boolean = false,
    val probeResult: HubProbeResult? = null,
    val settingsError: String? = null,
    val category: HubSearchCategory = HubSearchCategory.GgufChat,
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<HfRepoSummary> = HuggingFaceHubClient.curatedRecommendations(HubSearchCategory.GgufChat),
    val showingRecommendations: Boolean = true,
    val searchError: String? = null,
    val inspectedRepo: String? = null,
    val loadingRepo: Boolean = false,
    val repoOptions: List<HfRepoOption> = emptyList(),
    val repoError: String? = null,
    val installedModels: List<InstalledHubModel> = emptyList(),
    val downloads: DownloadManagerState = DownloadManagerState(),
    val storage: StorageSummary = StorageSummary()
)

class ModelHubViewModel(application: Application) : AndroidViewModel(application) {
    private val hubPreferences = HubPreferences.get(application)
    private val installedRepo = InstalledHubModelsRepository.get(application)
    private val _state = MutableStateFlow(
        ModelHubUiState(
            hubSettings = hubPreferences.current(),
            installedModels = installedRepo.models.value,
            downloads = ModelDownloadManager.state.value
        )
    )
    val state: StateFlow<ModelHubUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var inspectJob: Job? = null

    init {
        viewModelScope.launch {
            hubPreferences.settings.collect { settings ->
                _state.update { it.copy(hubSettings = settings) }
            }
        }
        viewModelScope.launch {
            installedRepo.models.collect { models ->
                _state.update { it.copy(installedModels = models) }
                refreshStorage()
            }
        }
        viewModelScope.launch {
            ModelDownloadManager.state.collect { dl ->
                val prevRev = _state.value.downloads.revision
                _state.update { it.copy(downloads = dl) }
                if (dl.revision != prevRev) {
                    withContext(Dispatchers.IO) { installedRepo.refresh() }
                    refreshStorage()
                }
            }
        }
        refreshStorage()
    }

    fun setMirrorMode(mode: HubMirrorMode) {
        runCatching {
            hubPreferences.update { it.copy(mode = mode) }
        }.onSuccess {
            _state.update { it.copy(probeResult = null, settingsError = null) }
        }.onFailure { error ->
            _state.update { it.copy(settingsError = error.message ?: "无法切换镜像源") }
        }
    }

    fun setCustomMirrorUrl(url: String) {
        val current = hubPreferences.current()
        if (url.isBlank()) {
            hubPreferences.update { it.copy(customBaseUrl = "") }
            _state.update { it.copy(settingsError = null, probeResult = null) }
            return
        }
        runCatching {
            hubPreferences.update { it.copy(mode = HubMirrorMode.Custom, customBaseUrl = url) }
        }.onSuccess {
            _state.update { it.copy(settingsError = null, probeResult = null) }
        }.onFailure { error ->
            _state.update { it.copy(hubSettings = current.copy(customBaseUrl = url), settingsError = error.message) }
        }
    }

    fun setHubToken(token: String) {
        hubPreferences.update { it.copy(token = token) }
        _state.update { it.copy(settingsError = null) }
    }

    fun probeHub() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true, probeResult = null, settingsError = null) }
        viewModelScope.launch {
            val result = HubConnectivityChecker.probe(hubPreferences.current())
            _state.update { it.copy(probing = false, probeResult = result) }
        }
    }

    fun selectCategory(category: HubSearchCategory) {
        if (_state.value.category == category) return
        searchJob?.cancel()
        val query = _state.value.query.trim()
        _state.update {
            it.copy(
                category = category,
                searching = false,
                searchError = null,
                searchResults = if (query.isEmpty()) HuggingFaceHubClient.curatedRecommendations(category) else emptyList(),
                showingRecommendations = query.isEmpty()
            )
        }
        if (query.isNotEmpty()) executeSearch()
    }

    fun updateQuery(value: String) {
        val trimmed = value.trim()
        _state.update { state ->
            state.copy(
                query = value,
                searchError = null,
                searchResults = if (trimmed.isEmpty()) HuggingFaceHubClient.curatedRecommendations(state.category) else state.searchResults,
                showingRecommendations = trimmed.isEmpty()
            )
        }
    }

    fun executeSearch() {
        val snapshot = _state.value
        val query = snapshot.query.trim()
        // If the user typed an exact owner/repo slug, open it directly!
        if (query.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            inspectRepo(query)
            return
        }
        searchJob?.cancel()
        _state.update { it.copy(searching = true, searchError = null, showingRecommendations = false) }
        searchJob = viewModelScope.launch {
            try {
                val results = HuggingFaceHubClient.search(query, snapshot.category, hubPreferences.current())
                _state.update { it.copy(searching = false, searchResults = results, searchError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        searching = false,
                        searchError = "搜索失败：${error.message ?: error.javaClass.simpleName}（如在国内请切换至 HF-Mirror）"
                    )
                }
            }
        }
    }

    fun inspectRepo(repo: String) {
        inspectJob?.cancel()
        val category = _state.value.category
        _state.update {
            it.copy(
                inspectedRepo = repo,
                loadingRepo = true,
                repoOptions = emptyList(),
                repoError = null
            )
        }
        inspectJob = viewModelScope.launch {
            try {
                val options = HuggingFaceHubClient.listDownloadableOptions(repo, category, hubPreferences.current())
                _state.update {
                    it.copy(
                        loadingRepo = false,
                        repoOptions = options,
                        repoError = if (options.isEmpty()) {
                            when (category) {
                                HubSearchCategory.GgufChat -> "该仓库未找到可直接加载的 GGUF 模型文件"
                                HubSearchCategory.MnnChat -> "该仓库缺少 MNN 完整文件（需包含 config.json、llm_config.json、llm.mnn、llm.mnn.weight 与 tokenizer.mtok/txt）"
                                HubSearchCategory.SdImage -> "该仓库未找到单文件图像权重（.safetensors / .gguf / .ckpt），Diffusers 拆包格式暂不支持"
                            }
                        } else null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loadingRepo = false,
                        repoError = error.message ?: "读取仓库文件失败"
                    )
                }
            }
        }
    }

    fun closeRepoSheet() {
        inspectJob?.cancel()
        _state.update { it.copy(inspectedRepo = null, loadingRepo = false, repoOptions = emptyList(), repoError = null) }
    }

    fun downloadOption(option: HfRepoOption) {
        val settings = hubPreferences.current()
        ModelDownloadManager.enqueue(
            context = getApplication(),
            id = "hub:${option.id}",
            title = option.label,
            onSuccess = { refreshStorage() }
        ) { onProgress ->
            installedRepo.downloadOption(option, settings, onProgress)
        }
        closeRepoSheet()
    }

    fun deleteInstalled(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { installedRepo.delete(id) }
            refreshStorage()
        }
    }

    fun cancelActiveDownload() = ModelDownloadManager.cancelActive()
    fun cancelQueuedDownload(id: String) = ModelDownloadManager.cancelQueued(id)
    fun clearDownloadError() = ModelDownloadManager.clearError()

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val base = getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir
            val modelsDir = File(base, "models")
            val used = directorySize(modelsDir)
            val available = runCatching { StatFs(base.path).availableBytes }.getOrDefault(0L)
            _state.update { it.copy(storage = StorageSummary(used, available)) }
        }
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { directorySize(it) }
    }
}
