package com.tiggy.qwenimage

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.DecisionKind
import com.tiggy.qwenimage.data.DecisionOption
import com.tiggy.qwenimage.data.DecisionQuestion
import com.tiggy.qwenimage.data.DecisionResult
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.LlmModelKind
import com.tiggy.qwenimage.data.LlmModelRepository
import com.tiggy.qwenimage.data.LlmModelSpec
import com.tiggy.qwenimage.data.LlmModelStatus
import com.tiggy.qwenimage.data.ModelDownloadManager
import com.tiggy.qwenimage.data.parseDecisionResult
import com.tiggy.qwenimage.data.planDecisionRequest
import com.tiggy.qwenimage.data.renderDecisionState
import com.tiggy.qwenimage.engine.LocalDecisionEngine
import com.tiggy.qwenimage.engine.LocalInferenceCoordinator
import com.tiggy.qwenimage.engine.NativeDecisionResult
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

enum class DecisionPhase { Idle, Verifying, Loading, Computing }

data class DecisionModelChoice(
    val id: String,
    val name: String,
    val totalBytes: Long,
    val ready: Boolean,
    val builtIn: Boolean,
    val attentionMode: String = "block_bidirectional"
)

data class DecisionDraft(
    val id: String,
    val kind: DecisionKind,
    val instructions: String = "",
    val options: List<DecisionOption> = emptyList()
) {
    fun validated() = DecisionQuestion(id, kind, instructions, options)
}

data class DecisionUiState(
    val ready: Boolean = false,
    val stateText: String = "",
    val jsonState: Boolean = false,
    val questions: List<DecisionDraft> = listOf(DecisionDraft("q1", DecisionKind.Noul)),
    val phase: DecisionPhase = DecisionPhase.Idle,
    val model: LlmModelStatus? = null,
    val decisionModels: List<DecisionModelChoice> = emptyList(),
    val selectedModelId: String = "elephant-1",
    val loadedModelId: String? = null,
    val threads: Int = 4,
    val result: DecisionResult? = null,
    val progress: Int = 0,
    val total: Int = 0,
    val loaded: Boolean = false,
    val error: String? = null
) {
    val busy get() = phase != DecisionPhase.Idle
    val selectedModelChoice: DecisionModelChoice?
        get() = decisionModels.firstOrNull { it.id == selectedModelId } ?: decisionModels.firstOrNull()
    val modelReady get() = selectedModelChoice?.ready == true || (selectedModelId == "elephant-1" && model?.ready == true)
}

class DecisionViewModel(
    application: Application,
    private val coordinator: LocalInferenceCoordinator
) : AndroidViewModel(application) {
    private val repository = LlmModelRepository(application)
    private val hubRepository = InstalledHubModelsRepository.get(application)
    private val prefs = application.getSharedPreferences("decision-settings", Context.MODE_PRIVATE)
    private val engine = LocalDecisionEngine(File(application.cacheDir, "decision-runtime"))
    private val _state = MutableStateFlow(
        DecisionUiState(
            selectedModelId = prefs.getString("selectedModelId", MODEL_ID).orEmpty().ifBlank { MODEL_ID },
            threads = prefs.getInt("threads", DEFAULT_THREADS).coerceIn(2, 8)
        )
    )
    val state: StateFlow<DecisionUiState> = _state.asStateFlow()
    private var operation: Job? = null
    private var nextId = 2
    @Volatile private var cancelRequested = false
    private val evictionKey = "decision:${System.identityHashCode(this)}"

    init {
        coordinator.registerEvictor(evictionKey) {
            engine.unload()
            _state.update { it.copy(loaded = false, loadedModelId = null) }
        }
        viewModelScope.launch {
            val (model, choices) = withContext(Dispatchers.IO) { buildModelLists() }
            _state.update { cur ->
                val activeId = resolveSelectedModelId(cur.selectedModelId, choices)
                cur.copy(ready = true, model = model, decisionModels = choices, selectedModelId = activeId)
            }
        }
        viewModelScope.launch {
            hubRepository.models.collect {
                val (model, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update { cur ->
                    val activeId = resolveSelectedModelId(cur.selectedModelId, choices)
                    cur.copy(model = model, decisionModels = choices, selectedModelId = activeId)
                }
            }
        }
        viewModelScope.launch {
            var lastRev = ModelDownloadManager.state.value.revision
            ModelDownloadManager.state.collect { dl ->
                if (dl.revision != lastRev) {
                    lastRev = dl.revision
                    val (model, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                    _state.update { cur ->
                        val activeId = resolveSelectedModelId(cur.selectedModelId, choices)
                        cur.copy(model = model, decisionModels = choices, selectedModelId = activeId)
                    }
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        if (_state.value.busy) return
        prefs.edit().putString("selectedModelId", modelId).apply()
        _state.update { it.copy(selectedModelId = modelId, error = null, result = null) }
    }

    suspend fun unloadIfLoadedSync(modelId: String) {
        if (_state.value.loadedModelId == modelId) {
            engine.unload()
            _state.update { it.copy(loaded = false, loadedModelId = null) }
        }
    }

    fun updateState(value: String) {
        _state.update { it.copy(stateText = value, result = null, error = null) }
    }

    fun setJsonState(value: Boolean) = _state.update {
        it.copy(jsonState = value, result = null, error = null)
    }

    fun updateInstructions(id: String, value: String) = updateQuestion(id) {
        it.copy(instructions = value.take(8192))
    }

    fun changeKind(id: String, kind: DecisionKind) = updateQuestion(id) { question ->
        if (question.kind == kind) return@updateQuestion question
        val options = when (kind) {
            DecisionKind.Noul -> emptyList()
            DecisionKind.Choice -> question.options.mapIndexed { index, option ->
                option.copy(name = option.name.ifBlank { "option-${index + 1}" })
            }.ifEmpty {
                listOf(DecisionOption("yes", "the statement is true"),
                    DecisionOption("no", "the statement is false"))
            }
            DecisionKind.Score -> question.options.take(10).ifEmpty {
                listOf(DecisionOption(description = "Low"),
                    DecisionOption(description = "Medium"), DecisionOption(description = "High"))
            }
        }
        question.copy(kind = kind, options = options)
    }

    fun updateOptionName(id: String, index: Int, value: String) = updateOption(id, index) {
        it.copy(name = value.take(200))
    }

    fun updateOptionDescription(id: String, index: Int, value: String) = updateOption(id, index) {
        it.copy(description = value.take(8192))
    }

    fun addOption(id: String) = updateQuestion(id) { question ->
        val index = question.options.size
        when {
            question.kind == DecisionKind.Choice && index < 2048 ->
                question.copy(options = question.options + DecisionOption("option-${index + 1}", ""))
            question.kind == DecisionKind.Score && index < 10 ->
                question.copy(options = question.options + DecisionOption(description = ""))
            else -> question
        }
    }

    fun removeOption(id: String, index: Int) = updateQuestion(id) { question ->
        if (question.options.size > 1 && index in question.options.indices) {
            question.copy(options = question.options.filterIndexed { item, _ -> item != index })
        } else question
    }

    fun addQuestion() {
        if (_state.value.questions.size >= 32 || _state.value.busy) return
        val question = DecisionDraft("q${nextId++}", DecisionKind.Noul)
        _state.update { it.copy(questions = it.questions + question, result = null, error = null) }
    }

    fun removeQuestion(id: String) {
        if (_state.value.questions.size <= 1 || _state.value.busy) return
        _state.update {
            it.copy(questions = it.questions.filterNot { question -> question.id == id }, result = null, error = null)
        }
    }

    fun showExample() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                stateText = "I was charged twice this month, please refund one of them.",
                jsonState = false,
                questions = listOf(
                    DecisionDraft("refund", DecisionKind.Noul, "Is the customer asking for a refund?"),
                    DecisionDraft("team", DecisionKind.Choice, "Which team should handle this?", listOf(
                        DecisionOption("billing", "Charges, refunds, invoices"),
                        DecisionOption("technical", "App or site faults"),
                        DecisionOption("fraud", "Suspected unauthorised use")
                    )),
                    DecisionDraft("urgency", DecisionKind.Score, "How urgent is this?", listOf(
                        DecisionOption(description = "Can wait"),
                        DecisionOption(description = "Today"),
                        DecisionOption(description = "Blocking the customer now")
                    ))
                ),
                result = null, error = null
            )
        }
    }

    fun setThreads(value: Int) {
        val clamped = value.coerceIn(2, 8)
        prefs.edit().putInt("threads", clamped).apply()
        _state.update { it.copy(threads = clamped) }
    }

    fun refreshModel() {
        viewModelScope.launch {
            val (model, choices) = withContext(Dispatchers.IO) { buildModelLists() }
            _state.update { cur ->
                val activeId = resolveSelectedModelId(cur.selectedModelId, choices)
                cur.copy(model = model, decisionModels = choices, selectedModelId = activeId)
            }
        }
    }

    fun downloadModel() {
        ModelDownloadManager.enqueue(
            context = getApplication(),
            id = "builtin-decision:$MODEL_ID",
            title = "Elephant-1 9B",
            onSuccess = { refreshModel() }
        ) { onProgress ->
            repository.download(MODEL_ID) { progress ->
                onProgress(progress.file, progress.completed, progress.total)
            }
        }
    }

    fun verifyModel() = modelTask {
        withContext(Dispatchers.IO) { repository.verify(MODEL_ID) }
    }

    fun deleteModel() = modelTask {
        if (_state.value.loadedModelId == MODEL_ID || _state.value.loaded) {
            engine.unload()
            _state.update { it.copy(loaded = false, loadedModelId = null) }
        }
        withContext(Dispatchers.IO) { repository.delete(MODEL_ID) }
    }

    private fun modelTask(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(phase = DecisionPhase.Verifying, error = null) }
        operation = viewModelScope.launch {
            try {
                val completed = coordinator.withInference("decision-models:$MODEL_ID") { action() }
                checkNotNull(completed) { "Another inference task is currently running" }
                val (model, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update { cur ->
                    cur.copy(
                        model = model,
                        decisionModels = choices,
                        selectedModelId = resolveSelectedModelId(cur.selectedModelId, choices),
                        phase = DecisionPhase.Idle,
                        error = null
                    )
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(phase = DecisionPhase.Idle) }
                throw error
            } catch (error: Throwable) {
                val (model, choices) = withContext(Dispatchers.IO) { buildModelLists() }
                _state.update { cur ->
                    cur.copy(
                        model = model,
                        decisionModels = choices,
                        phase = DecisionPhase.Idle,
                        error = error.message ?: error.toString()
                    )
                }
            } finally {
                operation = null
            }
        }
    }

    fun run() {
        val snapshot = _state.value
        if (!snapshot.ready || snapshot.busy) return
        if (!snapshot.modelReady) {
            _state.update { it.copy(error = "Please download a Decision model in Models first") }
            return
        }
        val questions = try {
            snapshot.questions.map { it.validated() }
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
            return
        }
        val renderedState = try {
            renderDecisionState(snapshot.stateText, snapshot.jsonState)
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
            return
        }
        try {
            planDecisionRequest(renderedState, questions)
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
            return
        }
        val activeModelId = snapshot.selectedModelChoice?.id ?: MODEL_ID
        cancelRequested = false
        _state.update { it.copy(phase = DecisionPhase.Loading, result = null, progress = 0,
            total = questions.size, error = null) }
        operation = viewModelScope.launch {
            try {
                val outcome = coordinator.withInference("decision:request") {
                    val threads = _state.value.threads
                    val hubModel = if (activeModelId != MODEL_ID) {
                        withContext(Dispatchers.IO) {
                            hubRepository.refresh()
                            hubRepository.find(activeModelId)
                        }
                    } else null
                    val loadError = if (hubModel != null && hubModel.type == HubModelType.DecisionGguf) {
                        val ggufFile = hubRepository.entryFile(hubModel)
                        check(ggufFile.isFile) { "Model file missing: ${ggufFile.name}" }
                        val causal = hubModel.metadata["attentionMode"] != "block_bidirectional"
                        _state.update { it.copy(phase = DecisionPhase.Loading) }
                        if (cancelRequested) return@withInference null to CANCELLED
                        engine.loadGguf(
                            ggufFile = ggufFile,
                            modelKey = "hub:${hubModel.id}:${ggufFile.length()}:$threads:$causal",
                            threads = threads,
                            causalAttention = causal
                        )
                    } else {
                        val status = withContext(Dispatchers.IO) {
                            var current = repository.status(MODEL_ID)
                            if (!current.ready && current.present == current.spec.artifacts.size) {
                                _state.update { it.copy(phase = DecisionPhase.Verifying) }
                                repository.verify(MODEL_ID)
                                current = repository.status(MODEL_ID)
                            }
                            current
                        }
                        check(status.ready) { "Model verification required" }
                        _state.update { it.copy(model = status, phase = DecisionPhase.Loading) }
                        if (cancelRequested) return@withInference null to CANCELLED
                        val weights = status.spec.artifacts.first { it.path == LlmModelSpec.DECISION_WEIGHTS }.sha256
                        engine.load(
                            File(status.directory, "config.json"),
                            "${status.spec.revision}:$weights:$threads",
                            threads = threads
                        )
                    }
                    if (cancelRequested) return@withInference null to CANCELLED
                    check(loadError == null) { loadError.orEmpty() }
                    _state.update { it.copy(loaded = true, loadedModelId = activeModelId) }
                    val slots = engine.slotLabels()
                    check(slots.size in 26..256) { "Incomplete decision slot tokens" }
                    val plan = planDecisionRequest(renderedState, questions, slots.size)
                    _state.update { it.copy(phase = DecisionPhase.Computing, total = plan.branches.size) }
                    if (cancelRequested) return@withInference null to CANCELLED
                    plan to engine.decide(plan.state, plan.suffixes, plan.optionCounts) { completed, total ->
                        _state.update { it.copy(progress = completed, total = total) }
                        !cancelRequested
                    }
                }
                val (plan, result) = checkNotNull(outcome) { "Another inference task is currently running" }
                when {
                    result.cancelled -> _state.update { it.copy(phase = DecisionPhase.Idle, progress = 0) }
                    result.error.isNotBlank() -> _state.update {
                        it.copy(phase = DecisionPhase.Idle, error = result.error, progress = 0)
                    }
                    else -> {
                        val answers = parseDecisionResult(result.resultJson, requireNotNull(plan))
                        _state.update { it.copy(phase = DecisionPhase.Idle, result = answers, error = null) }
                    }
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(phase = DecisionPhase.Idle, progress = 0) }
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(phase = DecisionPhase.Idle, progress = 0, error = error.message ?: error.toString())
                }
            } finally {
                operation = null
            }
        }
    }

    fun cancel() {
        cancelRequested = true
        when (_state.value.phase) {
            DecisionPhase.Verifying -> operation?.cancel()
            DecisionPhase.Loading, DecisionPhase.Computing -> engine.cancel()
            DecisionPhase.Idle -> Unit
        }
    }

    private fun buildModelLists(): Pair<LlmModelStatus, List<DecisionModelChoice>> {
        val builtIn = refreshStatus()
        val hubInstalled = hubRepository.refresh().filter { it.type == HubModelType.DecisionGguf }
        val choices = buildList {
            add(
                DecisionModelChoice(
                    id = builtIn.spec.id,
                    name = builtIn.spec.name,
                    totalBytes = builtIn.spec.totalBytes,
                    ready = builtIn.ready,
                    builtIn = true,
                    attentionMode = "block_bidirectional"
                )
            )
            hubInstalled.forEach { model ->
                add(
                    DecisionModelChoice(
                        id = model.id,
                        name = model.name,
                        totalBytes = model.totalBytes,
                        ready = hubRepository.isPresent(model),
                        builtIn = false,
                        attentionMode = model.metadata["attentionMode"] ?: "causal"
                    )
                )
            }
        }
        return builtIn to choices
    }

    private fun resolveSelectedModelId(current: String, choices: List<DecisionModelChoice>): String {
        val exact = choices.firstOrNull { it.id == current }
        if (exact != null && exact.ready) return exact.id
        return choices.firstOrNull { it.ready }?.id ?: exact?.id ?: MODEL_ID
    }

    private fun refreshStatus(): LlmModelStatus {
        val spec = repository.catalog.single { it.kind == LlmModelKind.Decision }
        return runCatching { repository.status(spec.id) }.getOrElse {
            LlmModelStatus(spec, repository.directory(spec.id), 0, false, "Unable to read model directory")
        }
    }

    private fun updateQuestion(id: String, transform: (DecisionDraft) -> DecisionDraft) {
        if (_state.value.busy) return
        _state.update { state ->
            state.copy(questions = state.questions.map { if (it.id == id) transform(it) else it },
                result = null, error = null)
        }
    }

    private fun updateOption(id: String, index: Int, transform: (DecisionOption) -> DecisionOption) =
        updateQuestion(id) { question ->
            if (index !in question.options.indices) return@updateQuestion question
            question.copy(options = question.options.mapIndexed { item, option ->
                if (item == index) transform(option) else option
            })
        }

    override fun onCleared() {
        engine.cancel()
        Thread({ engine.close(); coordinator.unregisterEvictor(evictionKey) }, "decision-engine-close").start()
        super.onCleared()
    }

    private companion object {
        const val MODEL_ID = "elephant-1"
        const val DEFAULT_THREADS = 4
        val CANCELLED = NativeDecisionResult(true, "", "")
    }
}
