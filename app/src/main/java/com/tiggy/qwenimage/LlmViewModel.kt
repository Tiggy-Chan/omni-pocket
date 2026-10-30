package com.tiggy.qwenimage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.LlmChatSnapshot
import com.tiggy.qwenimage.data.LlmChatStore
import com.tiggy.qwenimage.data.LlmConversation
import com.tiggy.qwenimage.data.LlmDownloadProgress
import com.tiggy.qwenimage.data.LlmMessage
import com.tiggy.qwenimage.data.LlmMessageStatus
import com.tiggy.qwenimage.data.LlmModelKind
import com.tiggy.qwenimage.data.LlmModelRepository
import com.tiggy.qwenimage.data.LlmModelStatus
import com.tiggy.qwenimage.data.splitReasoning
import com.tiggy.qwenimage.engine.LocalInferenceCoordinator
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
    val phase: LlmPhase = LlmPhase.Idle,
    val workingModelId: String? = null,
    val download: LlmDownloadProgress? = null,
    val usage: LlmUsage? = null,
    val loadedModelId: String? = null,
    val showModels: Boolean = false,
    val showConversations: Boolean = false,
    val alert: String? = null,
    val error: String? = null
) {
    val conversation: LlmConversation? get() = conversations.firstOrNull { it.id == selectedId }
    val draft: String get() = drafts[selectedId].orEmpty()
    val busy: Boolean get() = phase != LlmPhase.Idle
    val modelReady: Boolean get() = models.firstOrNull { it.spec.id == conversation?.modelId }?.ready == true
}

@OptIn(FlowPreview::class)
class LlmViewModel(
    application: Application,
    private val coordinator: LocalInferenceCoordinator
) : AndroidViewModel(application) {
    private val repository = LlmModelRepository(application)
    private val store = LlmChatStore(application)
    private val engine = LocalLlmEngine(File(application.cacheDir, "llm-runtime"))
    private val _state = MutableStateFlow(LlmUiState())
    val state: StateFlow<LlmUiState> = _state.asStateFlow()
    private var operation: Job? = null
    private var activeConversationId: String? = null
    private var activeMessageId: String? = null
    @Volatile private var cancelRequested = false
    private val evictionKey = "llm:${System.identityHashCode(this)}"
    private var persistenceEnabled = false
    private var storageErrorShown = false

    init {
        coordinator.registerEvictor(evictionKey) {
            engine.unload()
            _state.update { it.copy(loadedModelId = null) }
        }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { runCatching { store.load() } }
            val restoreFailed = restored.isFailure
            val recovered = if (restoreFailed) {
                withContext(Dispatchers.IO) { runCatching { store.preserveUnreadable() } }
            } else Result.success(Unit)
            persistenceEnabled = !restoreFailed || recovered.isSuccess
            val snapshot = restored.getOrElse { LlmChatSnapshot(emptyList(), null) }
            val conversations = snapshot.conversations.ifEmpty { listOf(newConversation()) }
            val models = withContext(Dispatchers.IO) { refreshStatuses() }
            _state.update {
                it.copy(ready = true, conversations = conversations,
                    selectedId = snapshot.selectedId ?: conversations.first().id,
                    drafts = snapshot.drafts,
                    models = models,
                    alert = if (restoreFailed) {
                        if (persistenceEnabled) "旧会话无法读取，原文件已备份" else "会话存档无法读取，原文件未覆盖"
                    } else null)
            }
            viewModelScope.launch {
                state.map { LlmChatSnapshot(it.conversations, it.selectedId, it.drafts) }
                    .distinctUntilChanged().debounce(400).collect(::persist)
            }
        }
    }

    fun updateDraft(value: String) {
        if (value.length > MAX_INPUT_CHARACTERS) return
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(drafts = it.drafts + (id to value), error = null) }
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
        val conversation = newConversation(_state.value.conversation?.modelId ?: DEFAULT_MODEL)
        _state.update {
            it.copy(conversations = listOf(conversation) + it.conversations, selectedId = conversation.id,
                showConversations = false, showModels = false, usage = null, error = null)
        }
    }

    fun deleteConversation(id: String) {
        if (activeConversationId == id && _state.value.busy) {
            _state.update { it.copy(alert = "请先停止当前生成，再删除会话") }
            return
        }
        _state.update { state ->
            val remaining = state.conversations.filterNot { it.id == id }
            val conversations = remaining.ifEmpty { listOf(newConversation()) }
            state.copy(conversations = conversations,
                selectedId = if (state.selectedId == id) conversations.first().id else state.selectedId,
                drafts = state.drafts - id, usage = null, error = null)
        }
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return
        updateConversation(id) { it.copy(title = clean) }
    }

    fun selectModel(id: String) {
        if (_state.value.busy || _state.value.models.none { it.spec.id == id }) return
        updateConversation(_state.value.selectedId ?: return) { it.copy(modelId = id) }
        _state.update { it.copy(error = null) }
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
        if (show) viewModelScope.launch(Dispatchers.IO) {
            val models = refreshStatuses()
            _state.update { it.copy(models = models) }
        }
    }
    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val models = refreshStatuses()
            _state.update { it.copy(models = models) }
        }
    }

    fun clearAlert() = _state.update { it.copy(alert = null) }

    fun send() {
        val snapshot = _state.value
        if (!snapshot.ready || snapshot.busy) return
        val conversation = snapshot.conversation ?: return
        val text = snapshot.draft.trim()
        if (text.isEmpty()) return
        val status = snapshot.models.firstOrNull { it.spec.id == conversation.modelId }
        if (status?.ready != true) {
            _state.update { it.copy(alert = "请先下载并校验 ${status?.spec?.name ?: "语言模型"}") }
            showModels(true)
            return
        }
        cancelRequested = false
        val user = LlmMessage(UUID.randomUUID().toString(), "user", text)
        val assistant = LlmMessage(UUID.randomUUID().toString(), "assistant", "", status = LlmMessageStatus.Streaming)
        updateConversation(conversation.id) { current ->
            current.copy(title = if (current.messages.isEmpty()) conversationTitle(text) else current.title,
                messages = current.messages + user + assistant,
                updatedAt = System.currentTimeMillis())
        }
        _state.update { it.copy(drafts = it.drafts + (conversation.id to ""), phase = LlmPhase.Loading,
            workingModelId = conversation.modelId, usage = null, error = null) }
        activeConversationId = conversation.id
        activeMessageId = assistant.id
        operation = viewModelScope.launch { generate(conversation.id, assistant.id) }
    }

    private suspend fun generate(conversationId: String, messageId: String) {
        try {
            val owner = "llm:$conversationId"
            val result = coordinator.withInference(owner) {
                val conversation = requireNotNull(_state.value.conversations.firstOrNull { it.id == conversationId })
                val status = withContext(Dispatchers.IO) {
                    var current = repository.status(conversation.modelId)
                    if (!current.ready && current.present == current.spec.artifacts.size) {
                        runCatching { repository.verify(conversation.modelId) }
                        current = repository.status(conversation.modelId)
                    }
                    current
                }
                check(status.ready) { "模型未通过校验：${status.message}" }
                if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                val error = engine.load(File(status.directory, "config.json"), status.spec.revision,
                    threads = 4, contextTokens = CONTEXT_TOKENS)
                if (cancelRequested) return@withInference NativeLlmResult(1, "", "", 0, 0, 0, 0, 0)
                check(error == null) { error.orEmpty() }
                _state.update { it.copy(loadedModelId = conversation.modelId, phase = LlmPhase.Generating) }
                var raw = ""
                engine.generate(conversationId, conversation.modelMessages(), MAX_NEW_TOKENS, conversation.thinking) { chunk ->
                    raw += chunk
                    if (activeConversationId == conversationId && activeMessageId == messageId) {
                        updateMessage(conversationId, messageId, raw, LlmMessageStatus.Streaming, conversation.thinking)
                        true
                    } else false
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
                it.copy(phase = LlmPhase.Idle, workingModelId = null,
                    error = if (it.selectedId == conversationId) error.message ?: error.toString() else it.error)
            }
        } finally {
            activeConversationId = null
            activeMessageId = null
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
        updateMessage(conversationId, messageId, result.text, status, thinking)
        _state.update {
            it.copy(phase = LlmPhase.Idle, workingModelId = null,
                error = if (it.selectedId == conversationId) result.error.ifBlank { null } else it.error,
                usage = if (it.selectedId == conversationId && (result.promptTokens > 0 || result.generatedTokens > 0)) {
                    LlmUsage(result.promptTokens, result.generatedTokens, result.droppedMessages,
                        result.prefillUs, result.decodeUs)
                } else if (it.selectedId == conversationId) null else it.usage)
        }
    }

    fun cancel() {
        if (_state.value.phase == LlmPhase.Loading || _state.value.phase == LlmPhase.Generating) {
            cancelRequested = true
        }
        when (_state.value.phase) {
            LlmPhase.Downloading -> operation?.cancel()
            LlmPhase.Loading, LlmPhase.Generating -> engine.cancel()
            LlmPhase.Idle -> Unit
        }
    }

    fun downloadModel(id: String) = runModelTask(id) { modelId ->
        repository.download(modelId) { progress ->
            _state.update { it.copy(download = progress) }
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
                _state.update { it.copy(models = refreshStatuses(), phase = LlmPhase.Idle,
                    workingModelId = null, download = null) }
            } catch (error: CancellationException) {
                _state.update { it.copy(phase = LlmPhase.Idle, workingModelId = null, download = null) }
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(phase = LlmPhase.Idle, workingModelId = null,
                    alert = error.message ?: error.toString(), models = refreshStatuses()) }
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
                        engine.unload()
                        _state.update { it.copy(loadedModelId = null) }
                    }
                    withContext(Dispatchers.IO) { repository.delete(id) }
                }
                checkNotNull(deleted) { "另一个任务正在运行，请稍后重试" }
                _state.update { it.copy(models = refreshStatuses(), phase = LlmPhase.Idle, workingModelId = null) }
            } catch (error: Throwable) {
                _state.update { it.copy(phase = LlmPhase.Idle, workingModelId = null,
                    alert = error.message ?: error.toString(), models = refreshStatuses()) }
            } finally {
                operation = null
            }
        }
    }

    private fun refreshStatuses(): List<LlmModelStatus> = repository.catalog.filter {
        it.kind == LlmModelKind.Chat
    }.map { spec ->
        runCatching { repository.status(spec.id) }.getOrElse {
            LlmModelStatus(spec, repository.directory(spec.id), 0, false, "无法读取模型目录")
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
        thinking: Boolean = false
    ) {
        val (content, reasoning) = splitReasoning(raw, thinking)
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
        engine.cancel()
        if (_state.value.ready) {
            kotlinx.coroutines.runBlocking {
                persist(LlmChatSnapshot(_state.value.conversations, _state.value.selectedId, _state.value.drafts))
            }
        }
        Thread({
            engine.close()
            coordinator.unregisterEvictor(evictionKey)
        }, "llm-engine-close").start()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_MODEL = "qwen35-0.8b"
        const val CONTEXT_TOKENS = 4096
        const val MAX_NEW_TOKENS = 512
        const val MAX_INPUT_CHARACTERS = 8_000

        fun newConversation(modelId: String = DEFAULT_MODEL) = LlmConversation(
            id = UUID.randomUUID().toString(), title = "新对话", modelId = modelId
        )

        fun conversationTitle(text: String) = text.replace(Regex("\\s+"), " ").trim().take(42)
    }
}
