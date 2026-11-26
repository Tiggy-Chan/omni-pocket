package com.tiggy.qwenimage

import android.app.Application
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.BenchmarkModality
import com.tiggy.qwenimage.data.DeviceHardwareProbe
import com.tiggy.qwenimage.data.DeviceHardwareSnapshot
import com.tiggy.qwenimage.data.DownloadManagerState
import com.tiggy.qwenimage.data.HfRepoOption
import com.tiggy.qwenimage.data.HfRepoSummary
import com.tiggy.qwenimage.data.HubConnectivityChecker
import com.tiggy.qwenimage.data.HubMirrorMode
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.HubPreferences
import com.tiggy.qwenimage.data.HubProbeResult
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.HubSearchSort
import com.tiggy.qwenimage.data.HubSettings
import com.tiggy.qwenimage.data.HuggingFaceHubClient
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.LiveBoardSource
import com.tiggy.qwenimage.data.LiveChatTier
import com.tiggy.qwenimage.data.LiveLeaderboardClient
import com.tiggy.qwenimage.data.LiveLeaderboardSnapshot
import com.tiggy.qwenimage.data.LiveMusicTrack
import com.tiggy.qwenimage.data.LocalImportPreview
import com.tiggy.qwenimage.data.LocalModelImporter
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
    val searchSort: HubSearchSort = HubSearchSort.Downloads,
    val searching: Boolean = false,
    val searchResults: List<HfRepoSummary> = HuggingFaceHubClient.curatedRecommendations(HubSearchCategory.GgufChat),
    val showingRecommendations: Boolean = true,
    val searchError: String? = null,
    val searchErrorDetail: String? = null,
    val inspectedRepo: String? = null,
    val loadingRepo: Boolean = false,
    val repoOptions: List<HfRepoOption> = emptyList(),
    /** Shared companion bytes already cached on device per option ID. */
    val inspectedReusedBytes: Map<String, Long> = emptyMap(),
    val repoError: String? = null,
    val repoEmptyCategory: HubSearchCategory? = null,
    val installedModels: List<InstalledHubModel> = emptyList(),
    val downloads: DownloadManagerState = DownloadManagerState(),
    val storage: StorageSummary = StorageSummary(),
    val deviceHardware: DeviceHardwareSnapshot? = null,
    val liveBoards: Map<String, LiveLeaderboardSnapshot> = emptyMap(),
    val liveLoading: Set<String> = emptySet(),
    val liveErrors: Map<String, String> = emptyMap(),
    val localImportInspecting: Boolean = false,
    val localImportPreview: LocalImportPreview? = null,
    val localImportError: String? = null
)

class ModelHubViewModel(application: Application) : AndroidViewModel(application) {
    private val hubPreferences = HubPreferences.get(application)
    private val installedRepo = InstalledHubModelsRepository.get(application)
    private val _state = MutableStateFlow(
        ModelHubUiState(
            hubSettings = hubPreferences.current(),
            installedModels = installedRepo.models.value,
            downloads = ModelDownloadManager.state.value,
            deviceHardware = runCatching { DeviceHardwareProbe.read(application) }.getOrNull()
        )
    )
    val state: StateFlow<ModelHubUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var inspectJob: Job? = null
    private val liveJobs = mutableMapOf<String, Job>()

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
                searchErrorDetail = null,
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
                searchErrorDetail = null,
                searchResults = if (trimmed.isEmpty()) HuggingFaceHubClient.curatedRecommendations(state.category) else state.searchResults,
                showingRecommendations = trimmed.isEmpty()
            )
        }
    }

    fun searchInCategory(query: String, category: HubSearchCategory) {
        searchJob?.cancel()
        val trimmed = query.trim()
        _state.update {
            it.copy(
                category = category,
                query = trimmed,
                searching = false,
                searchError = null,
                searchErrorDetail = null,
                searchResults = if (trimmed.isEmpty()) HuggingFaceHubClient.curatedRecommendations(category) else emptyList(),
                showingRecommendations = trimmed.isEmpty()
            )
        }
        if (trimmed.isNotEmpty()) executeSearch()
    }

    fun setSearchSort(sort: HubSearchSort) {
        if (_state.value.searchSort == sort) return
        _state.update { it.copy(searchSort = sort) }
        if (!_state.value.showingRecommendations && _state.value.query.isNotBlank()) {
            executeSearch()
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
        _state.update { it.copy(searching = true, searchError = null, searchErrorDetail = null, showingRecommendations = false) }
        searchJob = viewModelScope.launch {
            try {
                val results = HuggingFaceHubClient.search(
                    query = query,
                    category = snapshot.category,
                    settings = hubPreferences.current(),
                    sort = snapshot.searchSort
                )
                _state.update { it.copy(searching = false, searchResults = results, searchError = null, searchErrorDetail = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val detail = error.message ?: error.javaClass.simpleName
                _state.update {
                    it.copy(
                        searching = false,
                        searchError = detail,
                        searchErrorDetail = detail
                    )
                }
            }
        }
    }

    fun loadLiveLeaderboard(
        source: LiveBoardSource,
        modality: BenchmarkModality,
        chatTier: LiveChatTier = LiveChatTier.Tiny,
        musicTrack: LiveMusicTrack = LiveMusicTrack.Vocals,
        forceRefresh: Boolean = false
    ) {
        val boardId = LiveLeaderboardClient.boardId(source, modality, chatTier, musicTrack)
        if (!forceRefresh && _state.value.liveBoards[boardId] != null && boardId !in _state.value.liveLoading) {
            return
        }
        liveJobs[boardId]?.cancel()
        _state.update {
            it.copy(
                liveLoading = it.liveLoading + boardId,
                liveErrors = it.liveErrors - boardId
            )
        }
        liveJobs[boardId] = viewModelScope.launch {
            try {
                val result = LiveLeaderboardClient.loadBoard(
                    context = getApplication(),
                    source = source,
                    modality = modality,
                    hubSettings = hubPreferences.current(),
                    chatTier = chatTier,
                    musicTrack = musicTrack,
                    forceRefresh = forceRefresh
                )
                result.onSuccess { snap ->
                    _state.update {
                        it.copy(
                            liveBoards = it.liveBoards + (boardId to snap),
                            liveLoading = it.liveLoading - boardId,
                            liveErrors = it.liveErrors - boardId
                        )
                    }
                }.onFailure { err ->
                    _state.update {
                        it.copy(
                            liveLoading = it.liveLoading - boardId,
                            liveErrors = it.liveErrors + (boardId to (err.message ?: err.javaClass.simpleName))
                        )
                    }
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(liveLoading = it.liveLoading - boardId) }
                throw error
            }
        }
    }

    fun openRepoInCategory(repo: String, category: HubSearchCategory) {
        if (_state.value.category != category) {
            searchJob?.cancel()
            _state.update {
                it.copy(
                    category = category,
                    query = "",
                    searching = false,
                    searchError = null,
                    searchErrorDetail = null,
                    searchResults = HuggingFaceHubClient.curatedRecommendations(category),
                    showingRecommendations = true
                )
            }
        }
        inspectRepo(repo, category)
    }

    fun inspectRepo(repo: String, categoryOverride: HubSearchCategory? = null) {
        inspectJob?.cancel()
        val category = categoryOverride ?: _state.value.category
        _state.update {
            it.copy(
                inspectedRepo = repo,
                loadingRepo = true,
                repoOptions = emptyList(),
                inspectedReusedBytes = emptyMap(),
                repoError = null,
                repoEmptyCategory = null
            )
        }
        inspectJob = viewModelScope.launch {
            try {
                val options = HuggingFaceHubClient.listDownloadableOptions(repo, category, hubPreferences.current())
                val reused = withContext(Dispatchers.IO) {
                    options.associate { opt ->
                        val bytes = opt.files.sumOf { file ->
                            val key = file.sharedCompanionKey ?: return@sumOf 0L
                            val existing = runCatching { installedRepo.companionFile(key) }.getOrNull()
                            if (existing != null && existing.isFile && (file.size <= 0L || existing.length() == file.size)) {
                                existing.length()
                            } else 0L
                        }
                        opt.id to bytes
                    }
                }
                _state.update {
                    it.copy(
                        loadingRepo = false,
                        repoOptions = options,
                        inspectedReusedBytes = reused,
                        repoError = null,
                        repoEmptyCategory = if (options.isEmpty()) category else null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loadingRepo = false,
                        repoError = error.message ?: error.javaClass.simpleName,
                        repoEmptyCategory = null
                    )
                }
            }
        }
    }

    fun closeRepoSheet() {
        inspectJob?.cancel()
        _state.update {
            it.copy(
                inspectedRepo = null,
                loadingRepo = false,
                repoOptions = emptyList(),
                inspectedReusedBytes = emptyMap(),
                repoError = null,
                repoEmptyCategory = null
            )
        }
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

    fun clearLocalImport() {
        _state.update {
            it.copy(
                localImportInspecting = false,
                localImportPreview = null,
                localImportError = null
            )
        }
    }

    fun inspectLocalFile(uri: Uri) {
        _state.update {
            it.copy(localImportInspecting = true, localImportPreview = null, localImportError = null)
        }
        viewModelScope.launch {
            runCatching {
                LocalModelImporter.inspectSingleFile(getApplication(), uri, installedRepo)
            }.onSuccess { preview ->
                _state.update {
                    it.copy(localImportInspecting = false, localImportPreview = preview, localImportError = null)
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        localImportInspecting = false,
                        localImportPreview = null,
                        localImportError = err.message ?: err.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun inspectLocalFolder(treeUri: Uri) {
        _state.update {
            it.copy(localImportInspecting = true, localImportPreview = null, localImportError = null)
        }
        viewModelScope.launch {
            runCatching {
                LocalModelImporter.inspectFolder(getApplication(), treeUri)
            }.onSuccess { preview ->
                _state.update {
                    it.copy(localImportInspecting = false, localImportPreview = preview, localImportError = null)
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        localImportInspecting = false,
                        localImportPreview = null,
                        localImportError = err.message ?: err.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun attachPairedMmprojToPreview(uri: Uri) {
        val current = _state.value.localImportPreview ?: return
        viewModelScope.launch {
            runCatching {
                LocalModelImporter.inspectMmprojFile(getApplication(), uri)
            }.onSuccess { (mmUri, mmName, mmSize) ->
                _state.update {
                    val prev = it.localImportPreview ?: return@update it
                    it.copy(
                        localImportPreview = prev.copy(
                            pairedMmprojUri = mmUri,
                            pairedMmprojName = mmName,
                            pairedMmprojBytes = mmSize
                        ),
                        localImportError = null
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(localImportError = err.message ?: err.javaClass.simpleName) }
            }
        }
    }

    fun clearPairedMmprojFromPreview() {
        _state.update {
            val prev = it.localImportPreview ?: return@update it
            it.copy(
                localImportPreview = prev.copy(
                    pairedMmprojUri = null,
                    pairedMmprojName = null,
                    pairedMmprojBytes = 0L
                )
            )
        }
    }

    fun bindMmprojToInstalledModel(
        targetModelId: String,
        mmprojUri: Uri,
        onBound: () -> Unit = {}
    ) {
        val app = getApplication<Application>()
        val targetName = installedRepo.find(targetModelId)?.name ?: targetModelId
        val jobId = "local-mmproj:$targetModelId:${System.currentTimeMillis()}"
        ModelDownloadManager.enqueue(
            context = app,
            id = jobId,
            title = "$targetName · mmproj",
            onSuccess = {
                refreshStorage()
                onBound()
            }
        ) { onProgress ->
            LocalModelImporter.bindLocalMmproj(
                context = app,
                targetModelId = targetModelId,
                mmprojUri = mmprojUri,
                repo = installedRepo,
                onProgress = onProgress
            )
        }
        clearLocalImport()
    }

    fun confirmLocalImport(
        selectedType: HubModelType,
        importAsCompanion: Boolean = false,
        mmprojTargetModelId: String? = null,
        onImported: () -> Unit = {}
    ) {
        val preview = _state.value.localImportPreview ?: return
        if (preview.kind == com.tiggy.qwenimage.data.LocalImportKind.MmprojProjector) {
            val targetId = mmprojTargetModelId ?: return
            bindMmprojToInstalledModel(targetId, preview.uri, onBound = onImported)
            return
        }
        val app = getApplication<Application>()
        val jobId = "local-import:${preview.fileName}:${System.currentTimeMillis()}"
        ModelDownloadManager.enqueue(
            context = app,
            id = jobId,
            title = preview.displayName,
            onSuccess = {
                refreshStorage()
                onImported()
            }
        ) { onProgress ->
            LocalModelImporter.executeImport(
                context = app,
                preview = preview,
                selectedType = selectedType,
                importAsCompanion = importAsCompanion,
                repo = installedRepo,
                onProgress = onProgress
            )
        }
        clearLocalImport()
    }

    fun deleteInstalled(id: String, beforeDelete: (suspend () -> Unit)? = null) {
        viewModelScope.launch {
            if (beforeDelete != null) runCatching { beforeDelete() }
            withContext(Dispatchers.IO) { runCatching { installedRepo.delete(id) } }
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
