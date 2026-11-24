package com.tiggy.qwenimage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.DocumentTextExtractor
import com.tiggy.qwenimage.data.ExtractedDocument
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.KnowledgeBaseSearch
import com.tiggy.qwenimage.data.KnowledgeBaseStore
import com.tiggy.qwenimage.data.LlmChatSnapshot
import com.tiggy.qwenimage.data.LlmChatStore
import com.tiggy.qwenimage.data.LlmCitation
import com.tiggy.qwenimage.data.LlmConversation
import com.tiggy.qwenimage.data.LlmDownloadProgress
import com.tiggy.qwenimage.data.LlmMessage
import com.tiggy.qwenimage.data.LlmMessageStatus
import com.tiggy.qwenimage.data.LlmModelKind
import com.tiggy.qwenimage.data.LlmModelRepository
import com.tiggy.qwenimage.data.LlmModelStatus
import com.tiggy.qwenimage.data.ModelDownloadManager
import com.tiggy.qwenimage.data.formatBytesCompact
import com.tiggy.qwenimage.data.splitReasoning
import com.tiggy.qwenimage.engine.ChatSamplingParams
import com.tiggy.qwenimage.engine.DocumentQuickAction
import com.tiggy.qwenimage.engine.DocumentSummaryPipeline
import com.tiggy.qwenimage.engine.DocumentSummaryProgress
import com.tiggy.qwenimage.engine.GgufBackendCapabilities
import com.tiggy.qwenimage.engine.GgufComputeBackend
import com.tiggy.qwenimage.engine.KvCachePrecision
import com.tiggy.qwenimage.engine.LocalInferenceCoordinator
import com.tiggy.qwenimage.engine.LocalLlamaChatEngine
import com.tiggy.qwenimage.engine.LocalLlamaEmbeddingEngine
import com.tiggy.qwenimage.engine.LocalLlmEngine
import com.tiggy.qwenimage.engine.NativeLlmResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class LlmPhase { Idle, Downloading, Loading, Generating }

enum class ChatEngineBackend(val label: String) {
    Mnn("MNN"),
    LlamaGguf("GGUF")
}

data class ChatModelChoice(
    val id: String,
    val name: String,
    val backend: ChatEngineBackend,
    val totalBytes: Long,
    val ready: Boolean,
    val detail: String,
    val builtIn: Boolean
)

data class LlmUsage(
    val promptTokens: Int,
    val generatedTokens: Int,
    val droppedMessages: Int,
    val prefillUs: Long,
    val decodeUs: Long
)

data class LlmUiState(
    val ready: Boolean = false,
    val conversations: List<LlmConversation> = emptyList(),
    val selectedId: String? = null,
    val drafts: Map<String, String> = emptyMap(),
    val models: List<LlmModelStatus> = emptyList(),
    val chatModels: List<ChatModelChoice> = emptyList(),
    val maxNewTokens: Int = 1024,
    val contextTokens: Int = 4096,
    val threads: Int = 4,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.1f,
    val systemPrompt: String = LlmConversation.SYSTEM_PROMPT,
    val mnnUseOpenCl: Boolean = false,
    val ggufKvPrecision: KvCachePrecision = KvCachePrecision.Fp16,
    val ggufBackend: GgufComputeBackend = GgufComputeBackend.Auto,
    val ggufCapabilities: GgufBackendCapabilities = GgufBackendCapabilities(),
    val loadedBackendLabel: String = "",
    val loadedEffectiveContext: Int = 0,
    val loadedTrainContext: Int = 0,
    val phase: LlmPhase = LlmPhase.Idle,
    val workingModelId: String? = null,
    val download: LlmDownloadProgress? = null,
    val usage: LlmUsage? = null,
    val loadedModelId: String? = null,
    val attachedDocument: ExtractedDocument? = null,
    val documentLoading: Boolean = false,
    val documentProgress: DocumentSummaryProgress? = null,
    val selectedKnowledgeBaseId: String? = null,
    val selectedKnowledgeBaseName: String? = null,
    val showModels: Boolean = false,
    val showConversations: Boolean = false,
    val alert: String? = null,
    val error: String? = null
) {
    val conversation: LlmConversation? get() = conversations.firstOrNull { it.id == selectedId }
    val draft: String get() = drafts[selectedId].orEmpty()
    val busy: Boolean get() = phase != LlmPhase.Idle
    val selectedModelChoice: ChatModelChoice? get() = chatModels.firstOrNull { it.id == conversation?.modelId }
    val modelReady: Boolean get() = selectedModelChoice?.ready == true
}

@OptIn(FlowPreview::class)
class LlmViewModel(
    application: Application,
    private val coordinator: LocalInferenceCoordinator
) : AndroidViewModel(application) {
    private val repository = LlmModelRepository(application)
    private val hubRepository = InstalledHubModelsRepository.get(application)
    private val store = LlmChatStore(application)
    private val prefs = application.getSharedPreferences("llm-settings", Context.MODE_PRIVATE)
    private val mnnEngine = LocalLlmEngine(File(application.cacheDir, "llm-runtime"))
    private val llamaEngine = LocalLlamaChatEngine()
    private val _state = MutableStateFlow(
        LlmUiState(
            maxNewTokens = prefs.getInt("maxNewTokens", DEFAULT_MAX_NEW_TOKENS).coerceIn(MIN_MAX_NEW_TOKENS, MAX_MAX_NEW_TOKENS),
            contextTokens = prefs.getInt("contextTokens", DEFAULT_CONTEXT_TOKENS).coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS),
            threads = prefs.getInt("threads", DEFAULT_THREADS).coerceIn(2, 8),
            temperature = prefs.getFloat("temperature", DEFAULT_TEMPERATURE).coerceIn(0f, 2f),
            topP = prefs.getFloat("topP", DEFAULT_TOP_P).coerceIn(0.1f, 1f),
            repeatPenalty = prefs.getFloat("repeatPenalty", DEFAULT_REPEAT_PENALTY).coerceIn(1f, 1.5f),
            systemPrompt = prefs.getString("systemPrompt", LlmConversation.SYSTEM_PROMPT) ?: LlmConversation.SYSTEM_PROMPT,
            mnnUseOpenCl = prefs.getBoolean("mnnUseOpenCl", false),
            ggufKvPrecision = runCatching {
                KvCachePrecision.valueOf(prefs.getString("ggufKvPrecision", KvCachePrecision.Fp16.name).orEmpty())
            }.getOrDefault(KvCachePrecision.Fp16),
            ggufBackend = GgufComputeBackend.fromPref(
                raw = prefs.getString("ggufBackend", null),
                legacyGpuFlag = prefs.getBoolean("ggufUseVulkanGpu", false)
            ),
            ggufCapabilities = GgufBackendCapabilities()
        )
    )
    val state: StateFlow<LlmUiState> = _state.asStateFlow()
    private var operation: Job? = null
    private var activeConversationId: String? = null
    private var activeMessageId: String? = null
    @Volatile private var activeBackend: ChatEngineBackend? = null
    @Volatile private var cancelRequested = false
    private val evictionKey = "llm:${System.identityHashCode(this)}"
    private var persistenceEnabled = false
    private var storageErrorShown = false

    init {
        coordinator.registerEvictor(evictionKey) {
            mnnEngine.unload()
            llamaEngine.unload()
            _state.update { it.copy(loadedModelId = null) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            LocalLlamaChatEngine.configureCacheDir(File(application.cacheDir, "llama-opencl-cache"))
            val caps = LocalLlamaChatEngine.probeBackends()
            val shouldApplyAutoThreads = !prefs.contains("threads")
            val tunedThreads = caps.recommendedThreads.coerceIn(2, 8)
            _state.update { current ->
                val effectiveBackend = if (caps.isSelectable(current.ggufBackend)) {
                    current.ggufBackend
                } else {
                    GgufComputeBackend.Auto
                }
                current.copy(
                    ggufCapabilities = caps,
                    ggufBackend = effectiveBackend,
                    threads = if (shouldApplyAutoThreads) tunedThreads else current.threads
                )
            }
        }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { runCatching { store.load() } }
            val restoreFailed = restored.isFailure
            val recovered = if (restoreFailed) {
                withContext(Dispatchers.IO) { runCatching { store.preserveUnreadable() } }
            } else Result.success(Unit)
            persistenceEnabled = !restoreFailed || recovered.isSuccess
            val snapshot = restored.getOrElse { LlmChatSnapshot(emptyList(), null) }
            val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
            val preferredModel = choices.firstOrNull { it.ready }?.id ?: DEFAULT_MODEL
            val conversations = snapshot.conversations.ifEmpty { listOf(newConversation(preferredModel)) }
            _state.update {
                it.copy(
                    ready = true,
                    conversations = conversations,
                    selectedId = snapshot.selectedId ?: conversations.first().id,
                    drafts = snapshot.drafts,
                    models = models,
                    chatModels = choices,
                    alert = if (restoreFailed) {
                        if (persistenceEnabled) "旧会话无法读取，原文件已备份" else "会话存档无法读取，原文件未覆盖"
                    } else null
                )
            }
            viewModelScope.launch {
                state.map { LlmChatSnapshot(it.conversations, it.selectedId, it.drafts) }
                    .distinctUntilChanged().debounce(400).collect(::persist)
            }
        }
        viewModelScope.launch {
            hubRepository.models.collect {
                val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                applyModelLists(models, choices)
            }
        }
        viewModelScope.launch {
            var lastRev = ModelDownloadManager.state.value.revision
            ModelDownloadManager.state.collect { dl ->
                if (dl.revision != lastRev) {
                    lastRev = dl.revision
                    val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                    applyModelLists(models, choices)
                }
            }
        }
    }

    fun updateDraft(value: String) {
        if (value.length > MAX_INPUT_CHARACTERS) return
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(drafts = it.drafts + (id to value), error = null) }
    }

    fun setMaxNewTokens(value: Int) {
        val clamped = value.coerceIn(MIN_MAX_NEW_TOKENS, MAX_MAX_NEW_TOKENS)
        prefs.edit().putInt("maxNewTokens", clamped).apply()
        _state.update { it.copy(maxNewTokens = clamped) }
    }

    fun setContextTokens(value: Int) {
        val clamped = value.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)
        prefs.edit().putInt("contextTokens", clamped).apply()
        _state.update { it.copy(contextTokens = clamped) }
    }

    fun setThreads(value: Int) {
        val clamped = value.coerceIn(2, 8)
        prefs.edit().putInt("threads", clamped).apply()
        _state.update { it.copy(threads = clamped) }
    }

    fun setTemperature(value: Float) {
        val clamped = value.coerceIn(0f, 2f)
        prefs.edit().putFloat("temperature", clamped).apply()
        _state.update { it.copy(temperature = clamped) }
    }

    fun setTopP(value: Float) {
        val clamped = value.coerceIn(0.1f, 1f)
        prefs.edit().putFloat("topP", clamped).apply()
        _state.update { it.copy(topP = clamped) }
    }

    fun setRepeatPenalty(value: Float) {
        val clamped = value.coerceIn(1f, 1.5f)
        prefs.edit().putFloat("repeatPenalty", clamped).apply()
        _state.update { it.copy(repeatPenalty = clamped) }
    }

    fun setSystemPrompt(value: String) {
        val trimmed = value.take(2_000)
        prefs.edit().putString("systemPrompt", trimmed).apply()
        _state.update { it.copy(systemPrompt = trimmed) }
    }

    fun resetSystemPrompt() = setSystemPrompt(LlmConversation.SYSTEM_PROMPT)

    fun setMnnUseOpenCl(value: Boolean) {
        prefs.edit().putBoolean("mnnUseOpenCl", value).apply()
        _state.update { it.copy(mnnUseOpenCl = value) }
    }

    fun setGgufKvPrecision(precision: KvCachePrecision) {
        prefs.edit().putString("ggufKvPrecision", precision.name).apply()
        _state.update { it.copy(ggufKvPrecision = precision) }
    }

    fun setGgufBackend(backend: GgufComputeBackend) {
        val caps = _state.value.ggufCapabilities
        val effective = if (caps.isSelectable(backend)) backend else GgufComputeBackend.Auto
        prefs.edit()
            .putString("ggufBackend", effective.prefKey)
            .remove("ggufUseVulkanGpu")
            .apply()
        _state.update { it.copy(ggufBackend = effective) }
    }

    fun selectConversation(id: String) {
        if (_state.value.conversations.none { it.id == id }) return
        _state.update { it.copy(selectedId = id, showConversations = false, usage = null, error = null) }
    }

    fun createConversation() {
        if (!_state.value.ready) return
        if (_state.value.conversations.size >= 100) {
            _state.update { it.copy(alert = "最多保存 100 个会话，请先删除不用的会话") }
            return
        }
        val currentModel = _state.value.conversation?.modelId
            ?.takeIf { id -> _state.value.chatModels.any { it.id == id } }
            ?: _state.value.chatModels.firstOrNull { it.ready }?.id
            ?: DEFAULT_MODEL
        val conversation = newConversation(currentModel)
        _state.update {
            it.copy(
                conversations = listOf(conversation) + it.conversations,
                selectedId = conversation.id,
                showConversations = false,
                showModels = false,
                usage = null,
                error = null
            )
        }
    }

    fun deleteConversation(id: String) {
        if (activeConversationId == id && _state.value.busy) {
            _state.update { it.copy(alert = "请先停止当前生成，再删除会话") }
            return
        }
        _state.update { state ->
            val remaining = state.conversations.filterNot { it.id == id }
            val fallbackModel = state.chatModels.firstOrNull { it.ready }?.id ?: DEFAULT_MODEL
            val conversations = remaining.ifEmpty { listOf(newConversation(fallbackModel)) }
            state.copy(
                conversations = conversations,
                selectedId = if (state.selectedId == id) conversations.first().id else state.selectedId,
                drafts = state.drafts - id,
                usage = null,
                error = null
            )
        }
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return
        updateConversation(id) { it.copy(title = clean) }
    }

    fun selectModel(id: String) {
        if (_state.value.busy) return
        val known = _state.value.chatModels.any { it.id == id } || hubRepository.find(id) != null
        if (!known) return
        val convId = _state.value.selectedId ?: return
        updateConversation(convId) { it.copy(modelId = id) }
        _state.update { it.copy(error = null) }
        if (_state.value.chatModels.none { it.id == id }) refreshModels()
    }

    fun setThinking(value: Boolean) {
        val conversation = _state.value.conversation ?: return
        if (activeConversationId == conversation.id && _state.value.busy) return
        updateConversation(conversation.id) { it.copy(thinking = value) }
        if (value && conversation.modelId == "qwen35-0.8b") {
            _state.update { it.copy(alert = "0.8B 可能反复思考；达到上限会停止，可换用更大的模型") }
        }
    }

    fun showModels(show: Boolean) {
        _state.update { it.copy(showModels = show, showConversations = false) }
        if (show) refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val (models, choices) = buildModelLists()
            applyModelLists(models, choices)
        }
    }

    private fun applyModelLists(models: List<LlmModelStatus>, choices: List<ChatModelChoice>) {
        val firstReady = choices.firstOrNull { it.ready }?.id
        _state.update { current ->
            val conv = current.conversation
            val currentReady = choices.firstOrNull { it.id == conv?.modelId }?.ready == true
            val nextConvs = if (!current.busy && conv != null && conv.messages.isEmpty() && !currentReady && firstReady != null) {
                current.conversations.map { if (it.id == conv.id) it.copy(modelId = firstReady) else it }
            } else {
                current.conversations
            }
            current.copy(models = models, chatModels = choices, conversations = nextConvs)
        }
    }

    fun clearAlert() = _state.update { it.copy(alert = null) }

    fun attachDocument(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(documentLoading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentTextExtractor.extractFromUri(getApplication(), uri) }
            }
            result.onSuccess { doc ->
                _state.update {
                    it.copy(
                        attachedDocument = doc,
                        documentLoading = false,
                        error = null
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        documentLoading = false,
                        error = err.message ?: err.toString()
                    )
                }
            }
        }
    }

    fun clearAttachedDocument() {
        _state.update { it.copy(attachedDocument = null, documentProgress = null) }
    }

    fun selectKnowledgeBase(kbId: String?, kbName: String?) {
        _state.update {
            it.copy(
                selectedKnowledgeBaseId = kbId,
                selectedKnowledgeBaseName = kbName
            )
        }
    }

    fun runDocumentAction(action: DocumentQuickAction, displayLabel: String) {
        val snapshot = _state.value
        if (!snapshot.ready || snapshot.busy) return
        val doc = snapshot.attachedDocument ?: return
        if (doc.text.isBlank()) {
            _state.update { it.copy(alert = "该文档未提取到可阅读文本（若为扫描版 PDF，请先转为可复制文本）") }
            return
        }
        val conversation = snapshot.conversation ?: return
        val choice = snapshot.chatModels.firstOrNull { it.id == conversation.modelId }
        if (choice?.ready != true) {
            _state.update { it.copy(alert = "请先下载或选择已就绪的对话模型") }
            return
        }
        val question = if (action == DocumentQuickAction.Ask) snapshot.draft.trim() else ""
        val userDisplay = if (question.isNotBlank()) {
            "[${doc.fileName}]\n$question"
        } else {
            "[${doc.fileName}] $displayLabel"
        }
        cancelRequested = false
        val user = LlmMessage(UUID.randomUUID().toString(), "user", userDisplay)
        val assistant = LlmMessage(UUID.randomUUID().toString(), "assistant", "", status = LlmMessageStatus.Streaming)
        updateConversation(conversation.id) { current ->
            current.copy(
                title = if (current.messages.isEmpty()) conversationTitle(userDisplay) else current.title,
                messages = current.messages + user + assistant,
                updatedAt = System.currentTimeMillis()
            )
        }
        _state.update {
            it.copy(
                drafts = if (action == DocumentQuickAction.Ask) it.drafts + (conversation.id to "") else it.drafts,
                phase = LlmPhase.Loading,
                workingModelId = conversation.modelId,
                documentProgress = null,
                usage = null,
                error = null
            )
        }
        activeConversationId = conversation.id
        activeMessageId = assistant.id
        operation = viewModelScope.launch {
            generateForDocument(conversation.id, assistant.id, doc, action, question)
        }
    }

    fun send() {
        val snapshot = _state.value
        if (!snapshot.ready || snapshot.busy) return
        val attached = snapshot.attachedDocument
        if (attached != null && attached.text.isNotBlank()) {
            val question = snapshot.draft.trim()
            if (question.isEmpty()) return
            runDocumentAction(DocumentQuickAction.Ask, question)
            return
        }
        val conversation = snapshot.conversation ?: return
        val text = snapshot.draft.trim()
        if (text.isEmpty()) return
        val choice = snapshot.chatModels.firstOrNull { it.id == conversation.modelId }
        if (choice?.ready != true) {
            _state.update { it.copy(alert = "请先下载或选择已就绪的对话模型") }
            return
        }
        cancelRequested = false
        val user = LlmMessage(UUID.randomUUID().toString(), "user", text)
        val assistant = LlmMessage(UUID.randomUUID().toString(), "assistant", "", status = LlmMessageStatus.Streaming)
        updateConversation(conversation.id) { current ->
            current.copy(
                title = if (current.messages.isEmpty()) conversationTitle(text) else current.title,
                messages = current.messages + user + assistant,
                updatedAt = System.currentTimeMillis()
            )
        }
        _state.update {
            it.copy(
                drafts = it.drafts + (conversation.id to ""),
                phase = LlmPhase.Loading,
                workingModelId = conversation.modelId,
                usage = null,
                error = null
            )
        }
        activeConversationId = conversation.id
        activeMessageId = assistant.id
        operation = viewModelScope.launch { generate(conversation.id, assistant.id) }
    }

    fun regenerateLast() {
        val snapshot = _state.value
        if (!snapshot.ready || snapshot.busy) return
        val conversation = snapshot.conversation ?: return
        val messages = conversation.messages
        if (messages.size < 2 || messages.last().role != "assistant") return
        val choice = snapshot.chatModels.firstOrNull { it.id == conversation.modelId }
        if (choice?.ready != true) {
            _state.update { it.copy(alert = "请先下载或选择已就绪的对话模型") }
            return
        }
        cancelRequested = false
        val replacement = LlmMessage(UUID.randomUUID().toString(), "assistant", "", status = LlmMessageStatus.Streaming)
        updateConversation(conversation.id) { current ->
            current.copy(
                messages = current.messages.dropLast(1) + replacement,
                updatedAt = System.currentTimeMillis()
            )
        }
        _state.update {
            it.copy(
                phase = LlmPhase.Loading,
                workingModelId = conversation.modelId,
                usage = null,
                error = null
            )
        }
        activeConversationId = conversation.id
        activeMessageId = replacement.id
        operation = viewModelScope.launch { generate(conversation.id, replacement.id) }
    }

    private suspend fun generate(conversationId: String, messageId: String) {
        try {
            val owner = "llm:$conversationId"
            val result = coordinator.withInference(owner) {
                val snapshot = _state.value
                val conversation = requireNotNull(snapshot.conversations.firstOrNull { it.id == conversationId })
                val modelId = conversation.modelId
                val threads = snapshot.threads
                val contextTokens = snapshot.contextTokens
                val sampling = ChatSamplingParams(
                    temperature = snapshot.temperature,
                    topP = snapshot.topP,
                    topK = 40,
                    repeatPenalty = snapshot.repeatPenalty
                )
                val baseMessages = conversation.modelMessages(snapshot.systemPrompt)
                val kbId = snapshot.selectedKnowledgeBaseId
                var ragCitations: List<LlmCitation> = emptyList()
                val messages = if (!kbId.isNullOrBlank() && baseMessages.lastOrNull()?.first == "user") {
                    val userQuery = baseMessages.last().second
                    val hits = retrieveKnowledgeHits(kbId, userQuery)
                    if (hits.isNotEmpty()) {
                        ragCitations = hits.map {
                            LlmCitation(
                                docName = it.docName,
                                chunkIndex = it.chunkIndex,
                                snippet = it.text.trim().take(280),
                                score = it.score
                            )
                        }
                        val augmented = KnowledgeBaseSearch.buildRagPrompt(hits, userQuery)
                        baseMessages.dropLast(1) + ("user" to augmented)
                    } else {
                        baseMessages
                    }
                } else {
                    baseMessages
                }
                if (ragCitations.isNotEmpty()) {
                    updateMessageCitations(conversationId, messageId, ragCitations)
                }

                val hubModel = withContext(Dispatchers.IO) { hubRepository.find(modelId) }
                if (hubModel != null) {
                    check(hubRepository.isPresent(hubModel)) { "模型文件缺失，请重新下载" }
                    val entry = hubRepository.entryFile(hubModel)
                    when (hubModel.type) {
                        HubModelType.GgufChat -> {
                            mnnEngine.unload()
                            activeBackend = ChatEngineBackend.LlamaGguf
                            if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                            val requestedBackend = snapshot.ggufBackend
                            var activeBackendPref = requestedBackend
                            var fellBackToCpu = false
                            var error = llamaEngine.load(
                                modelFile = entry,
                                modelKey = "${hubModel.id}:${hubModel.installedAt}",
                                threads = threads,
                                contextTokens = contextTokens,
                                kvPrecision = snapshot.ggufKvPrecision,
                                backend = activeBackendPref
                            )
                            if (error != null && activeBackendPref != GgufComputeBackend.Cpu && activeBackendPref != GgufComputeBackend.Auto && !cancelRequested) {
                                llamaEngine.unload()
                                activeBackendPref = GgufComputeBackend.Cpu
                                fellBackToCpu = true
                                error = llamaEngine.load(
                                    modelFile = entry,
                                    modelKey = "${hubModel.id}:${hubModel.installedAt}",
                                    threads = threads,
                                    contextTokens = contextTokens,
                                    kvPrecision = snapshot.ggufKvPrecision,
                                    backend = GgufComputeBackend.Cpu
                                )
                            }
                            if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                            check(error == null) { error.orEmpty() }
                            val effectiveCtx = llamaEngine.effectiveContextTokens.takeIf { it > 0 } ?: contextTokens
                            val maxNewTokens = snapshot.maxNewTokens.coerceAtMost((effectiveCtx - 256).coerceAtLeast(128))
                            _state.update {
                                it.copy(
                                    loadedModelId = modelId,
                                    loadedBackendLabel = llamaEngine.activeBackendLabel,
                                    loadedEffectiveContext = effectiveCtx,
                                    loadedTrainContext = llamaEngine.modelTrainContextTokens,
                                    phase = LlmPhase.Generating,
                                    alert = if (fellBackToCpu) "${requestedBackend.name} 后端初始化失败，已自动回退到 CPU 运行" else it.alert
                                )
                            }
                            var raw = ""
                            var nativeResult = llamaEngine.generate(conversationId, messages, maxNewTokens, conversation.thinking, sampling) { chunk ->
                                raw += chunk
                                if (activeConversationId == conversationId && activeMessageId == messageId) {
                                    // GGUF emits explicit <think> tags when thinking is active; never implicitly treat plain replies as thinking.
                                    updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, implicitThinking = false)
                                    true
                                } else false
                            }
                            // If GPU/NPU compute fails during first decode before emitting any text (e.g. Adreno Vulkan shader link error),
                            // reload cleanly on CPU and retry once so driver quirks never break chat.
                            if (nativeResult.finish == 3 && raw.isEmpty() &&
                                activeBackendPref != GgufComputeBackend.Cpu && activeBackendPref != GgufComputeBackend.Auto && !cancelRequested) {
                                llamaEngine.unload()
                                val cpuErr = llamaEngine.load(
                                    modelFile = entry,
                                    modelKey = "${hubModel.id}:${hubModel.installedAt}",
                                    threads = threads,
                                    contextTokens = contextTokens,
                                    kvPrecision = snapshot.ggufKvPrecision,
                                    backend = GgufComputeBackend.Cpu
                                )
                                if (cpuErr == null && !cancelRequested) {
                                    val cpuEffectiveCtx = llamaEngine.effectiveContextTokens.takeIf { it > 0 } ?: contextTokens
                                    val cpuMaxNewTokens = snapshot.maxNewTokens.coerceAtMost((cpuEffectiveCtx - 256).coerceAtLeast(128))
                                    _state.update {
                                        it.copy(
                                            loadedBackendLabel = llamaEngine.activeBackendLabel,
                                            loadedEffectiveContext = cpuEffectiveCtx,
                                            loadedTrainContext = llamaEngine.modelTrainContextTokens,
                                            alert = "${requestedBackend.name} 后端推理失败，已自动回退到 CPU 运行"
                                        )
                                    }
                                    nativeResult = llamaEngine.generate(conversationId, messages, cpuMaxNewTokens, conversation.thinking, sampling) { chunk ->
                                        raw += chunk
                                        if (activeConversationId == conversationId && activeMessageId == messageId) {
                                            updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, implicitThinking = false)
                                            true
                                        } else false
                                    }
                                }
                            }
                            nativeResult
                        }
                        HubModelType.MnnChat -> {
                            llamaEngine.unload()
                            activeBackend = ChatEngineBackend.Mnn
                            if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                            val mnnCtx = contextTokens.coerceAtMost(65536)
                            val maxNewTokens = snapshot.maxNewTokens.coerceAtMost((mnnCtx - 256).coerceAtLeast(128))
                            val error = mnnEngine.load(
                                config = entry,
                                modelKey = "${hubModel.id}:${hubModel.installedAt}",
                                threads = threads,
                                contextTokens = mnnCtx,
                                useOpenCl = snapshot.mnnUseOpenCl
                            )
                            if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                            check(error == null) { error.orEmpty() }
                            _state.update {
                                it.copy(
                                    loadedModelId = modelId,
                                    loadedEffectiveContext = mnnCtx,
                                    loadedTrainContext = 0,
                                    phase = LlmPhase.Generating
                                )
                            }
                            var raw = ""
                            mnnEngine.generate(conversationId, messages, maxNewTokens, conversation.thinking, sampling) { chunk ->
                                raw += chunk
                                if (activeConversationId == conversationId && activeMessageId == messageId) {
                                    updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, conversation.thinking)
                                    true
                                } else false
                            }
                        }
                        HubModelType.DecisionGguf -> error("Selected model is a decision model and cannot be used for chat")
                        HubModelType.SdImage -> error("Selected model is an image model and cannot be used for chat")
                        HubModelType.VideoWan -> error("Selected model is a video model and cannot be used for chat")
                        HubModelType.MusicAudio -> error("Selected model is a music model and cannot be used for chat")
                        HubModelType.Embedding -> error("Selected model is an embedding model and cannot be used for chat")
                    }
                } else {
                    llamaEngine.unload()
                    activeBackend = ChatEngineBackend.Mnn
                    val status = withContext(Dispatchers.IO) {
                        var current = repository.status(modelId)
                        if (!current.ready && current.present == current.spec.artifacts.size) {
                            runCatching { repository.verify(modelId) }
                            current = repository.status(modelId)
                        }
                        current
                    }
                    check(status.ready) { "模型未通过校验：${status.message}" }
                    if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                    val mnnCtx = contextTokens.coerceAtMost(65536)
                    val maxNewTokens = snapshot.maxNewTokens.coerceAtMost((mnnCtx - 256).coerceAtLeast(128))
                    val error = mnnEngine.load(
                        config = File(status.directory, "config.json"),
                        modelKey = status.spec.revision,
                        threads = threads,
                        contextTokens = mnnCtx,
                        useOpenCl = snapshot.mnnUseOpenCl
                    )
                    if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                    check(error == null) { error.orEmpty() }
                    _state.update {
                        it.copy(
                            loadedModelId = modelId,
                            loadedEffectiveContext = mnnCtx,
                            loadedTrainContext = 0,
                            phase = LlmPhase.Generating
                        )
                    }
                    var raw = ""
                    mnnEngine.generate(conversationId, messages, maxNewTokens, conversation.thinking, sampling) { chunk ->
                        raw += chunk
                        if (activeConversationId == conversationId && activeMessageId == messageId) {
                            updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, conversation.thinking)
                            true
                        } else false
                    }
                }
            }
            checkNotNull(result) { "另一个任务正在运行，请稍后重试" }
            applyResult(conversationId, messageId, result)
        } catch (error: CancellationException) {
            if (_state.value.ready) {
                updateActiveMessage(LlmMessageStatus.Interrupted)
                _state.update { it.copy(phase = LlmPhase.Idle, workingModelId = null) }
            }
            throw error
        } catch (error: Throwable) {
            updateActiveMessage(LlmMessageStatus.Failed)
            _state.update {
                it.copy(
                    phase = LlmPhase.Idle,
                    workingModelId = null,
                    error = if (it.selectedId == conversationId) error.message ?: error.toString() else it.error
                )
            }
        } finally {
            activeConversationId = null
            activeMessageId = null
            activeBackend = null
            operation = null
        }
    }

    private suspend fun generateForDocument(
        conversationId: String,
        messageId: String,
        doc: ExtractedDocument,
        action: DocumentQuickAction,
        userQuestion: String
    ) {
        try {
            val owner = "llm:$conversationId"
            val result = coordinator.withInference(owner) {
                val snapshot = _state.value
                val conversation = requireNotNull(snapshot.conversations.firstOrNull { it.id == conversationId })
                val modelId = conversation.modelId
                val threads = snapshot.threads
                val contextTokens = snapshot.contextTokens
                val sampling = ChatSamplingParams(
                    temperature = snapshot.temperature.coerceAtMost(0.5f),
                    topP = snapshot.topP,
                    topK = 40,
                    repeatPenalty = snapshot.repeatPenalty
                )
                val systemPrompt = snapshot.systemPrompt.trim().ifBlank { LlmConversation.SYSTEM_PROMPT }
                val hubModel = withContext(Dispatchers.IO) { hubRepository.find(modelId) }

                val effectiveCtx: Int
                val runner: suspend (String, Int, Boolean) -> NativeLlmResult

                if (hubModel != null && hubModel.type == HubModelType.GgufChat) {
                    check(hubRepository.isPresent(hubModel)) { "模型文件缺失，请重新下载" }
                    val entry = hubRepository.entryFile(hubModel)
                    mnnEngine.unload()
                    activeBackend = ChatEngineBackend.LlamaGguf
                    if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                    var activeBackendPref = snapshot.ggufBackend
                    var error = llamaEngine.load(
                        modelFile = entry,
                        modelKey = "${hubModel.id}:${hubModel.installedAt}",
                        threads = threads,
                        contextTokens = contextTokens,
                        kvPrecision = snapshot.ggufKvPrecision,
                        backend = activeBackendPref
                    )
                    if (error != null && activeBackendPref != GgufComputeBackend.Cpu && activeBackendPref != GgufComputeBackend.Auto && !cancelRequested) {
                        llamaEngine.unload()
                        activeBackendPref = GgufComputeBackend.Cpu
                        error = llamaEngine.load(
                            modelFile = entry,
                            modelKey = "${hubModel.id}:${hubModel.installedAt}",
                            threads = threads,
                            contextTokens = contextTokens,
                            kvPrecision = snapshot.ggufKvPrecision,
                            backend = GgufComputeBackend.Cpu
                        )
                    }
                    if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                    check(error == null) { error.orEmpty() }
                    effectiveCtx = llamaEngine.effectiveContextTokens.takeIf { it > 0 } ?: contextTokens
                    _state.update {
                        it.copy(
                            loadedModelId = modelId,
                            loadedBackendLabel = llamaEngine.activeBackendLabel,
                            loadedEffectiveContext = effectiveCtx,
                            loadedTrainContext = llamaEngine.modelTrainContextTokens,
                            phase = LlmPhase.Generating
                        )
                    }
                    var stepCounter = 0
                    runner = { prompt, stepMaxTokens, streamToUser ->
                        stepCounter++
                        val stepConvId = "doc-$conversationId-$messageId-$stepCounter"
                        val msgs = listOf("system" to systemPrompt, "user" to prompt)
                        var raw = ""
                        llamaEngine.generate(stepConvId, msgs, stepMaxTokens, false, sampling) { chunk ->
                            raw += chunk
                            if (streamToUser && activeConversationId == conversationId && activeMessageId == messageId) {
                                updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, implicitThinking = false)
                            }
                            !cancelRequested && activeConversationId == conversationId && activeMessageId == messageId
                        }
                    }
                } else {
                    llamaEngine.unload()
                    activeBackend = ChatEngineBackend.Mnn
                    val configFile: File
                    val modelKey: String
                    if (hubModel != null && hubModel.type == HubModelType.MnnChat) {
                        check(hubRepository.isPresent(hubModel)) { "模型文件缺失，请重新下载" }
                        configFile = hubRepository.entryFile(hubModel)
                        modelKey = "${hubModel.id}:${hubModel.installedAt}"
                    } else {
                        val status = withContext(Dispatchers.IO) {
                            var current = repository.status(modelId)
                            if (!current.ready && current.present == current.spec.artifacts.size) {
                                runCatching { repository.verify(modelId) }
                                current = repository.status(modelId)
                            }
                            current
                        }
                        check(status.ready) { "模型未通过校验：${status.message}" }
                        configFile = File(status.directory, "config.json")
                        modelKey = status.spec.revision
                    }
                    if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                    effectiveCtx = contextTokens.coerceAtMost(65536)
                    val error = mnnEngine.load(
                        config = configFile,
                        modelKey = modelKey,
                        threads = threads,
                        contextTokens = effectiveCtx,
                        useOpenCl = snapshot.mnnUseOpenCl
                    )
                    if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                    check(error == null) { error.orEmpty() }
                    _state.update {
                        it.copy(
                            loadedModelId = modelId,
                            loadedEffectiveContext = effectiveCtx,
                            loadedTrainContext = 0,
                            phase = LlmPhase.Generating
                        )
                    }
                    var stepCounter = 0
                    runner = { prompt, stepMaxTokens, streamToUser ->
                        stepCounter++
                        val stepConvId = "doc-$conversationId-$messageId-$stepCounter"
                        val msgs = listOf("system" to systemPrompt, "user" to prompt)
                        var raw = ""
                        mnnEngine.generate(stepConvId, msgs, stepMaxTokens, false, sampling) { chunk ->
                            raw += chunk
                            if (streamToUser && activeConversationId == conversationId && activeMessageId == messageId) {
                                updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, implicitThinking = false)
                            }
                            !cancelRequested && activeConversationId == conversationId && activeMessageId == messageId
                        }
                    }
                }

                val maxNew = snapshot.maxNewTokens.coerceAtMost((effectiveCtx - 256).coerceAtLeast(128))
                DocumentSummaryPipeline.execute(
                    doc = doc,
                    action = action,
                    userQuestion = userQuestion,
                    contextTokens = effectiveCtx,
                    maxNewTokens = maxNew,
                    isCancelled = { cancelRequested },
                    onProgress = { progress ->
                        _state.update { it.copy(documentProgress = progress) }
                    },
                    runInference = runner
                )
            }
            checkNotNull(result) { "另一个任务正在运行，请稍后重试" }
            applyResult(conversationId, messageId, result)
        } catch (error: CancellationException) {
            if (_state.value.ready) {
                updateActiveMessage(LlmMessageStatus.Interrupted)
                _state.update { it.copy(phase = LlmPhase.Idle, workingModelId = null, documentProgress = null) }
            }
            throw error
        } catch (error: Throwable) {
            updateActiveMessage(LlmMessageStatus.Failed)
            _state.update {
                it.copy(
                    phase = LlmPhase.Idle,
                    workingModelId = null,
                    documentProgress = null,
                    error = if (it.selectedId == conversationId) error.message ?: error.toString() else it.error
                )
            }
        } finally {
            _state.update { it.copy(documentProgress = null) }
            activeConversationId = null
            activeMessageId = null
            activeBackend = null
            operation = null
        }
    }

    private fun applyResult(conversationId: String, messageId: String, result: NativeLlmResult) {
        val status = when (result.finish) {
            0 -> LlmMessageStatus.Complete
            1 -> LlmMessageStatus.Cancelled
            2 -> LlmMessageStatus.MaxTokens
            else -> LlmMessageStatus.Failed
        }
        val thinking = _state.value.conversations.firstOrNull { it.id == conversationId }?.thinking == true
        val implicitThinking = activeBackend == ChatEngineBackend.Mnn && thinking
        updateMessage(conversationId, messageId, result.text, status, implicitThinking)
        _state.update {
            it.copy(
                phase = LlmPhase.Idle,
                workingModelId = null,
                error = if (it.selectedId == conversationId) result.error.ifBlank { null } else it.error,
                usage = if (it.selectedId == conversationId && (result.promptTokens > 0 || result.generatedTokens > 0)) {
                    LlmUsage(
                        result.promptTokens,
                        result.generatedTokens,
                        result.droppedMessages,
                        result.prefillUs,
                        result.decodeUs
                    )
                } else if (it.selectedId == conversationId) null else it.usage
            )
        }
    }

    fun cancel() {
        if (_state.value.phase == LlmPhase.Loading || _state.value.phase == LlmPhase.Generating) {
            cancelRequested = true
        }
        when (_state.value.phase) {
            LlmPhase.Downloading -> operation?.cancel()
            LlmPhase.Loading, LlmPhase.Generating -> {
                when (activeBackend) {
                    ChatEngineBackend.LlamaGguf -> llamaEngine.cancel()
                    ChatEngineBackend.Mnn -> mnnEngine.cancel()
                    null -> {
                        mnnEngine.cancel()
                        llamaEngine.cancel()
                    }
                }
            }
            LlmPhase.Idle -> Unit
        }
    }

    fun downloadModel(id: String) {
        val spec = repository.catalog.firstOrNull { it.id == id } ?: return
        ModelDownloadManager.enqueue(
            context = getApplication(),
            id = "builtin-llm:$id",
            title = spec.name,
            onSuccess = { refreshModels() }
        ) { onProgress ->
            repository.download(id) { progress ->
                onProgress(progress.file, progress.completed, progress.total)
            }
        }
    }

    fun verifyModel(id: String) = runModelTask(id) { modelId ->
        _state.update { it.copy(download = null) }
        withContext(Dispatchers.IO) { repository.verify(modelId) }
    }

    private fun runModelTask(id: String, action: suspend (String) -> Unit) {
        if (_state.value.busy || repository.catalog.none { it.id == id }) return
        _state.update { it.copy(phase = LlmPhase.Downloading, workingModelId = id, error = null, alert = null) }
        operation = viewModelScope.launch {
            try {
                val completed = coordinator.withInference("llm-models:$id") {
                    withContext(Dispatchers.IO) { action(id) }
                }
                checkNotNull(completed) { "另一个任务正在运行，请稍后重试" }
                val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update {
                    it.copy(
                        models = models,
                        chatModels = choices,
                        phase = LlmPhase.Idle,
                        workingModelId = null,
                        download = null
                    )
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(phase = LlmPhase.Idle, workingModelId = null, download = null) }
                throw error
            } catch (error: Throwable) {
                val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update {
                    it.copy(
                        phase = LlmPhase.Idle,
                        workingModelId = null,
                        alert = error.message ?: error.toString(),
                        models = models,
                        chatModels = choices
                    )
                }
            } finally {
                operation = null
            }
        }
    }

    fun deleteModel(id: String) {
        if (_state.value.busy) return
        operation = viewModelScope.launch {
            _state.update { it.copy(phase = LlmPhase.Downloading, workingModelId = id) }
            try {
                val deleted = coordinator.withInference("llm-delete:$id") {
                    if (_state.value.loadedModelId == id) {
                        mnnEngine.unload()
                        llamaEngine.unload()
                        _state.update { it.copy(loadedModelId = null) }
                    }
                    withContext(Dispatchers.IO) { repository.delete(id) }
                }
                checkNotNull(deleted) { "另一个任务正在运行，请稍后重试" }
                val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update {
                    it.copy(models = models, chatModels = choices, phase = LlmPhase.Idle, workingModelId = null)
                }
            } catch (error: Throwable) {
                val (models, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update {
                    it.copy(
                        phase = LlmPhase.Idle,
                        workingModelId = null,
                        alert = error.message ?: error.toString(),
                        models = models,
                        chatModels = choices
                    )
                }
            } finally {
                operation = null
            }
        }
    }

    suspend fun unloadIfLoadedSync(modelId: String) {
        if (_state.value.loadedModelId == modelId) {
            mnnEngine.unload()
            llamaEngine.unload()
            _state.update { it.copy(loadedModelId = null, loadedEffectiveContext = 0, loadedTrainContext = 0) }
        }
    }

    fun unloadIfLoaded(modelId: String) {
        if (_state.value.loadedModelId == modelId) {
            viewModelScope.launch { unloadIfLoadedSync(modelId) }
        }
    }

    private fun buildModelLists(): Pair<List<LlmModelStatus>, List<ChatModelChoice>> {
        val builtIn = repository.catalog.filter { it.kind == LlmModelKind.Chat }.map { spec ->
            runCatching { repository.status(spec.id) }.getOrElse {
                LlmModelStatus(spec, repository.directory(spec.id), 0, false, "无法读取模型目录")
            }
        }
        val hubInstalled = hubRepository.refresh().filter {
            it.type == HubModelType.GgufChat || it.type == HubModelType.MnnChat
        }
        val choices = buildList {
            builtIn.forEach { status ->
                add(
                    ChatModelChoice(
                        id = status.spec.id,
                        name = status.spec.name,
                        backend = ChatEngineBackend.Mnn,
                        totalBytes = status.spec.totalBytes,
                        ready = status.ready,
                        detail = if (status.ready) "已就绪 · MNN · ${formatBytesCompact(status.spec.totalBytes)}"
                        else "${status.message} · ${formatBytesCompact(status.spec.totalBytes)}",
                        builtIn = true
                    )
                )
            }
            hubInstalled.forEach { model ->
                val backend = if (model.type == HubModelType.GgufChat) ChatEngineBackend.LlamaGguf else ChatEngineBackend.Mnn
                val present = hubRepository.isPresent(model)
                add(
                    ChatModelChoice(
                        id = model.id,
                        name = model.name,
                        backend = backend,
                        totalBytes = model.totalBytes,
                        ready = present,
                        detail = if (present) "已就绪 · ${backend.label} · ${formatBytesCompact(model.totalBytes)}"
                        else "文件缺失",
                        builtIn = false
                    )
                )
            }
        }
        return builtIn to choices
    }

    private suspend fun retrieveKnowledgeHits(
        kbId: String,
        query: String
    ): List<com.tiggy.qwenimage.data.KnowledgeHit> = withContext(Dispatchers.IO) {
        val kbStore = KnowledgeBaseStore.get(getApplication())
        val chunks = kbStore.loadChunksForBase(kbId)
        if (chunks.isEmpty()) return@withContext emptyList()
        val embModelId = chunks.firstOrNull { !it.embeddingModelId.isNullOrBlank() }?.embeddingModelId
        val embModel = embModelId?.let { hubRepository.find(it) }
            ?.takeIf { it.type == HubModelType.Embedding && hubRepository.isPresent(it) }
        var queryVec: FloatArray? = null
        if (embModel != null && chunks.any { it.embedding != null }) {
            val engine = LocalLlamaEmbeddingEngine()
            try {
                val err = engine.load(
                    modelFile = hubRepository.entryFile(embModel),
                    modelKey = "${embModel.id}:${embModel.installedAt}",
                    threads = 4,
                    contextTokens = 1024
                )
                if (err == null) {
                    queryVec = engine.embed(query)
                }
            } finally {
                engine.close()
            }
        }
        KnowledgeBaseSearch.rankHybridHits(query, queryVec, chunks, topK = 4)
    }

    private fun updateMessageCitations(conversationId: String, messageId: String, citations: List<LlmCitation>) {
        updateConversation(conversationId) { conversation ->
            conversation.copy(messages = conversation.messages.map { message ->
                if (message.id == messageId) message.copy(citations = citations) else message
            })
        }
    }

    private fun updateActiveMessage(status: LlmMessageStatus) {
        val conversationId = activeConversationId ?: return
        val messageId = activeMessageId ?: return
        updateConversation(conversationId) { conversation ->
            conversation.copy(messages = conversation.messages.map {
                if (it.id == messageId) it.copy(status = status) else it
            })
        }
    }

    private fun updateMessage(
        conversationId: String,
        messageId: String,
        raw: String,
        status: LlmMessageStatus,
        implicitThinking: Boolean = false
    ) {
        val (content, reasoning) = splitReasoning(raw, implicitThinking)
        updateConversation(conversationId) { conversation ->
            conversation.copy(messages = conversation.messages.map { message ->
                if (message.id == messageId) message.copy(content = content, reasoning = reasoning, status = status)
                else message
            })
        }
    }

    private fun updateConversation(id: String, transform: (LlmConversation) -> LlmConversation) {
        _state.update { state ->
            state.copy(conversations = state.conversations.map { if (it.id == id) transform(it) else it })
        }
    }

    private suspend fun persist(snapshot: LlmChatSnapshot) {
        if (!persistenceEnabled) return
        val saved = withContext(Dispatchers.IO) { runCatching { store.save(snapshot) } }
        if (saved.isSuccess) {
            storageErrorShown = false
        } else if (!storageErrorShown) {
            storageErrorShown = true
            _state.update { it.copy(alert = "会话保存失败：${saved.exceptionOrNull()?.message ?: "存储不可用"}") }
        }
    }

    override fun onCleared() {
        mnnEngine.cancel()
        llamaEngine.cancel()
        val snapshotToSave = if (_state.value.ready && persistenceEnabled) {
            LlmChatSnapshot(_state.value.conversations, _state.value.selectedId, _state.value.drafts)
        } else null
        Thread({
            if (snapshotToSave != null) {
                runCatching { store.save(snapshotToSave) }
            }
            mnnEngine.close()
            llamaEngine.close()
            coordinator.unregisterEvictor(evictionKey)
        }, "llm-engine-close").start()
        super.onCleared()
    }

    companion object {
        const val MIN_CONTEXT_TOKENS = 512
        const val MAX_CONTEXT_TOKENS = 131_072
        const val MIN_MAX_NEW_TOKENS = 128
        const val MAX_MAX_NEW_TOKENS = 32_768
        private const val DEFAULT_MODEL = "qwen35-0.8b"
        private const val DEFAULT_CONTEXT_TOKENS = 4096
        private const val DEFAULT_MAX_NEW_TOKENS = 1024
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_P = 0.9f
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val MAX_INPUT_CHARACTERS = 32_000

        fun newConversation(modelId: String = DEFAULT_MODEL) = LlmConversation(
            id = UUID.randomUUID().toString(), title = "新对话", modelId = modelId
        )

        fun conversationTitle(text: String) = text.replace(Regex("\\s+"), " ").trim().take(42)
    }
}
