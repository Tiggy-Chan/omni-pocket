package com.tiggy.qwenimage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.DocumentTextExtractor
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.KnowledgeBaseSearch
import com.tiggy.qwenimage.data.KnowledgeBaseStore
import com.tiggy.qwenimage.data.KnowledgeBaseSummary
import com.tiggy.qwenimage.data.KnowledgeDocumentSummary
import com.tiggy.qwenimage.data.KnowledgeHit
import com.tiggy.qwenimage.engine.LocalInferenceCoordinator
import com.tiggy.qwenimage.engine.LocalLlamaEmbeddingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KbIndexProgress(
    val fileName: String,
    val currentChunk: Int,
    val totalChunks: Int,
    val usingEmbedding: Boolean
)

data class KnowledgeBaseUiState(
    val bases: List<KnowledgeBaseSummary> = emptyList(),
    val selectedBaseId: String? = null,
    val documents: List<KnowledgeDocumentSummary> = emptyList(),
    val embeddingModels: List<InstalledHubModel> = emptyList(),
    val selectedEmbeddingModelId: String? = null,
    val indexing: KbIndexProgress? = null,
    val error: String? = null,
    val message: String? = null
) {
    val selectedBase: KnowledgeBaseSummary? get() = bases.firstOrNull { it.id == selectedBaseId }
    val activeEmbeddingModel: InstalledHubModel? get() = embeddingModels.firstOrNull { it.id == selectedEmbeddingModelId }
}

class KnowledgeBaseViewModel(
    application: Application,
    private val coordinator: LocalInferenceCoordinator
) : AndroidViewModel(application) {
    private val store = KnowledgeBaseStore.get(application)
    private val hubRepository = InstalledHubModelsRepository.get(application)
    private val prefs = application.getSharedPreferences("kb-settings", Context.MODE_PRIVATE)
    private val embeddingEngine = LocalLlamaEmbeddingEngine()
    private val evictionKey = "kb-embed:${System.identityHashCode(this)}"

    private val _state = MutableStateFlow(KnowledgeBaseUiState())
    val state: StateFlow<KnowledgeBaseUiState> = _state.asStateFlow()
    private var indexJob: Job? = null

    init {
        coordinator.registerEvictor(evictionKey) {
            embeddingEngine.unload()
        }
        viewModelScope.launch(Dispatchers.IO) {
            refreshAllSync()
        }
        viewModelScope.launch {
            hubRepository.models.collect {
                withContext(Dispatchers.IO) { refreshEmbeddingModelsSync() }
            }
        }
    }

    private fun refreshAllSync() {
        val bases = store.listBases()
        val selectedId = _state.value.selectedBaseId?.takeIf { id -> bases.any { it.id == id } }
            ?: bases.firstOrNull()?.id
        val docs = if (selectedId != null) store.listDocuments(selectedId) else emptyList()
        val embModels = hubRepository.refresh().filter {
            it.type == HubModelType.Embedding && hubRepository.isPresent(it)
        }
        val savedEmbId = prefs.getString("selectedEmbeddingModelId", null)
        val activeEmbId = savedEmbId?.takeIf { id -> embModels.any { it.id == id } }
            ?: embModels.firstOrNull()?.id
        _state.update {
            it.copy(
                bases = bases,
                selectedBaseId = selectedId,
                documents = docs,
                embeddingModels = embModels,
                selectedEmbeddingModelId = activeEmbId
            )
        }
    }

    private fun refreshEmbeddingModelsSync() {
        val embModels = hubRepository.refresh().filter {
            it.type == HubModelType.Embedding && hubRepository.isPresent(it)
        }
        val current = _state.value.selectedEmbeddingModelId ?: prefs.getString("selectedEmbeddingModelId", null)
        val active = current?.takeIf { id -> embModels.any { it.id == id } } ?: embModels.firstOrNull()?.id
        _state.update { it.copy(embeddingModels = embModels, selectedEmbeddingModelId = active) }
    }

    fun selectBase(kbId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val docs = store.listDocuments(kbId)
            _state.update { it.copy(selectedBaseId = kbId, documents = docs, error = null) }
        }
    }

    fun createBase(name: String, description: String = "") {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val created = store.createBase(trimmed, description)
            val bases = store.listBases()
            _state.update {
                it.copy(
                    bases = bases,
                    selectedBaseId = created.id,
                    documents = emptyList(),
                    error = null
                )
            }
        }
    }

    fun deleteBase(kbId: String) {
        if (_state.value.indexing != null) return
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteBase(kbId)
            refreshAllSync()
        }
    }

    fun selectEmbeddingModel(modelId: String) {
        prefs.edit().putString("selectedEmbeddingModelId", modelId).apply()
        _state.update { it.copy(selectedEmbeddingModelId = modelId) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    fun cancelIndexing() {
        embeddingEngine.cancel()
        indexJob?.cancel()
    }

    fun importDocument(uri: Uri) {
        val kbId = _state.value.selectedBaseId ?: return
        if (_state.value.indexing != null) return
        indexJob = viewModelScope.launch {
            try {
                _state.update { it.copy(error = null, message = null) }
                val extracted = withContext(Dispatchers.IO) {
                    DocumentTextExtractor.extractFromUri(getApplication(), uri)
                }
                if (extracted.text.isBlank()) {
                    _state.update {
                        it.copy(error = "该文档未提取到可索引文本（扫描版 PDF 暂不支持直接提取）")
                    }
                    return@launch
                }
                val chunks = KnowledgeBaseSearch.splitDocumentForKnowledgeBase(extracted.text)
                if (chunks.isEmpty()) {
                    _state.update { it.copy(error = "文档内容为空") }
                    return@launch
                }

                val embModel = _state.value.activeEmbeddingModel
                val vectors = ArrayList<FloatArray?>(chunks.size)
                var usedEmbModelId: String? = null

                if (embModel != null && hubRepository.isPresent(embModel)) {
                    val ran = coordinator.withInference("kb-index:${extracted.fileName}") {
                        val loadErr = embeddingEngine.load(
                            modelFile = hubRepository.entryFile(embModel),
                            modelKey = "${embModel.id}:${embModel.installedAt}",
                            threads = 4,
                            contextTokens = 1024
                        )
                        if (loadErr != null) {
                            throw IllegalStateException(loadErr)
                        }
                        usedEmbModelId = embModel.id
                        for ((idx, chunk) in chunks.withIndex()) {
                            _state.update {
                                it.copy(indexing = KbIndexProgress(extracted.fileName, idx + 1, chunks.size, true))
                            }
                            vectors.add(embeddingEngine.embed(chunk))
                        }
                    }
                    if (ran == null) {
                        _state.update { it.copy(error = "另一个推理任务正在运行，请稍后重试") }
                        return@launch
                    }
                } else {
                    _state.update {
                        it.copy(indexing = KbIndexProgress(extracted.fileName, chunks.size, chunks.size, false))
                    }
                    repeat(chunks.size) { vectors.add(null) }
                }

                withContext(Dispatchers.IO) {
                    store.addDocumentWithChunks(
                        kbId = kbId,
                        fileName = extracted.fileName,
                        charCount = extracted.text.length,
                        chunks = chunks,
                        embeddings = vectors,
                        embeddingModelId = usedEmbModelId
                    )
                    refreshAllSync()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: t.toString()) }
            } finally {
                _state.update { it.copy(indexing = null) }
                indexJob = null
            }
        }
    }

    fun reindexSelectedBase() {
        val kbId = _state.value.selectedBaseId ?: return
        val embModel = _state.value.activeEmbeddingModel ?: return
        if (_state.value.indexing != null || !hubRepository.isPresent(embModel)) return
        indexJob = viewModelScope.launch {
            try {
                _state.update { it.copy(error = null, message = null) }
                val chunks = withContext(Dispatchers.IO) { store.loadChunksForBase(kbId) }
                if (chunks.isEmpty()) return@launch
                val ran = coordinator.withInference("kb-reindex:$kbId") {
                    val loadErr = embeddingEngine.load(
                        modelFile = hubRepository.entryFile(embModel),
                        modelKey = "${embModel.id}:${embModel.installedAt}",
                        threads = 4,
                        contextTokens = 1024
                    )
                    if (loadErr != null) throw IllegalStateException(loadErr)
                    for ((idx, chunk) in chunks.withIndex()) {
                        _state.update {
                            it.copy(indexing = KbIndexProgress(chunk.docName, idx + 1, chunks.size, true))
                        }
                        val vec = embeddingEngine.embed(chunk.text)
                        if (vec != null) {
                            withContext(Dispatchers.IO) {
                                store.updateChunkEmbedding(chunk.id, vec, embModel.id)
                            }
                        }
                    }
                }
                if (ran == null) {
                    _state.update { it.copy(error = "另一个推理任务正在运行，请稍后重试") }
                } else {
                    withContext(Dispatchers.IO) { refreshAllSync() }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: t.toString()) }
            } finally {
                _state.update { it.copy(indexing = null) }
                indexJob = null
            }
        }
    }

    fun deleteDocument(docId: String) {
        val kbId = _state.value.selectedBaseId ?: return
        if (_state.value.indexing != null) return
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteDocument(kbId, docId)
            refreshAllSync()
        }
    }

    /**
     * Retrieves Top-K relevant chunks for [query] from [kbId], computing query embedding on-device
     * when matching embeddings are available and falling back seamlessly to lexical recall otherwise.
     */
    suspend fun retrieveForQuery(kbId: String, query: String, topK: Int = 4): List<KnowledgeHit> {
        val chunks = withContext(Dispatchers.IO) { store.loadChunksForBase(kbId) }
        if (chunks.isEmpty()) return emptyList()
        val embModelId = chunks.firstOrNull { !it.embeddingModelId.isNullOrBlank() }?.embeddingModelId
            ?: _state.value.selectedEmbeddingModelId
        val embModel = embModelId?.let { id -> hubRepository.find(id) }
            ?.takeIf { it.type == HubModelType.Embedding && hubRepository.isPresent(it) }

        var queryVec: FloatArray? = null
        if (embModel != null && chunks.any { it.embedding != null }) {
            val err = embeddingEngine.load(
                modelFile = hubRepository.entryFile(embModel),
                modelKey = "${embModel.id}:${embModel.installedAt}",
                threads = 4,
                contextTokens = 1024
            )
            if (err == null) {
                queryVec = embeddingEngine.embed(query)
            }
            // Free small embedding session before loading a large chat LLM if RAM is tight
            embeddingEngine.unload()
        }
        return KnowledgeBaseSearch.rankHybridHits(query, queryVec, chunks, topK = topK)
    }

    override fun onCleared() {
        embeddingEngine.cancel()
        Thread({
            embeddingEngine.close()
            coordinator.unregisterEvictor(evictionKey)
        }, "kb-embed-close").start()
        super.onCleared()
    }
}
