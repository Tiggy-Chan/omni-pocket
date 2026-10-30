package com.tiggy.qwenimage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiggy.qwenimage.data.DecisionKind
import com.tiggy.qwenimage.data.DecisionOption
import com.tiggy.qwenimage.data.DecisionQuestion
import com.tiggy.qwenimage.data.DecisionResult
import com.tiggy.qwenimage.data.LlmModelKind
import com.tiggy.qwenimage.data.LlmModelRepository
import com.tiggy.qwenimage.data.LlmModelSpec
import com.tiggy.qwenimage.data.LlmModelStatus
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
    val showModels: Boolean = false,
    val result: DecisionResult? = null,
    val progress: Int = 0,
    val total: Int = 0,
    val loaded: Boolean = false,
    val error: String? = null
) {
    val busy get() = phase != DecisionPhase.Idle
    val modelReady get() = model?.ready == true
}

class DecisionViewModel(
    application: Application,
    private val coordinator: LocalInferenceCoordinator
) : AndroidViewModel(application) {
    private val repository = LlmModelRepository(application)
    private val engine = LocalDecisionEngine(File(application.cacheDir, "decision-runtime"))
    private val _state = MutableStateFlow(DecisionUiState())
    val state: StateFlow<DecisionUiState> = _state.asStateFlow()
    private var operation: Job? = null
    private var nextId = 2
    @Volatile private var cancelRequested = false
    private val evictionKey = "decision:${System.identityHashCode(this)}"

    init {
        coordinator.registerEvictor(evictionKey) {
            engine.unload()
            _state.update { it.copy(loaded = false) }
        }
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) { refreshStatus() }
            _state.update { it.copy(ready = true, model = model) }
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
                listOf(DecisionOption(description = "低"),
                    DecisionOption(description = "中"), DecisionOption(description = "高"))
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

    fun showModels(show: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(showModels = show) }
        if (show) viewModelScope.launch {
            val model = withContext(Dispatchers.IO) { refreshStatus() }
            _state.update { it.copy(model = model) }
        }
    }

    fun refreshModel() {
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) { refreshStatus() }
            _state.update { it.copy(model = model) }
        }
    }

    fun verifyModel() = modelTask {
        withContext(Dispatchers.IO) { repository.verify(MODEL_ID) }
    }

    fun deleteModel() = modelTask {
        if (_state.value.loaded) {
            engine.unload()
            _state.update { it.copy(loaded = false) }
        }
        withContext(Dispatchers.IO) { repository.delete(MODEL_ID) }
    }

    private fun modelTask(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(phase = DecisionPhase.Verifying, error = null) }
        operation = viewModelScope.launch {
            try {
                val completed = coordinator.withInference("decision-models:$MODEL_ID") { action() }
                checkNotNull(completed) { "另一个任务正在运行，请稍后重试" }
                _state.update { it.copy(model = refreshStatus(), phase = DecisionPhase.Idle, error = null) }
            } catch (error: CancellationException) {
                _state.update { it.copy(phase = DecisionPhase.Idle) }
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(model = refreshStatus(), phase = DecisionPhase.Idle,
                        error = error.message ?: error.toString())
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
            _state.update { it.copy(showModels = true, error = "请先从电脑导入并校验 Elephant-1") }
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
        cancelRequested = false
        _state.update { it.copy(phase = DecisionPhase.Loading, result = null, progress = 0,
            total = questions.size, error = null) }
        operation = viewModelScope.launch {
            try {
                val outcome = coordinator.withInference("decision:request") {
                    val status = withContext(Dispatchers.IO) {
                        var current = repository.status(MODEL_ID)
                        if (!current.ready && current.present == current.spec.artifacts.size) {
                            _state.update { it.copy(phase = DecisionPhase.Verifying) }
                            repository.verify(MODEL_ID)
                            current = repository.status(MODEL_ID)
                        }
                        current
                    }
                    check(status.ready) { "模型未通过校验：${status.message}" }
                    _state.update { it.copy(model = status, phase = DecisionPhase.Loading) }
                    if (cancelRequested) return@withInference null to CANCELLED
                    val weights = status.spec.artifacts.first { it.path == LlmModelSpec.DECISION_WEIGHTS }.sha256
                    val error = engine.load(File(status.directory, "config.json"),
                        "${status.spec.revision}:$weights", threads = 4)
                    if (cancelRequested) return@withInference null to CANCELLED
                    check(error == null) { error.orEmpty() }
                    _state.update { it.copy(loaded = true) }
                    val slots = engine.slotLabels()
                    check(slots.size in 26..256) { "模型选项 token 不完整" }
                    val plan = planDecisionRequest(renderedState, questions, slots.size)
                    _state.update { it.copy(phase = DecisionPhase.Computing, total = plan.branches.size) }
                    if (cancelRequested) return@withInference null to CANCELLED
                    plan to engine.decide(plan.state, plan.suffixes, plan.optionCounts) { completed, total ->
                        _state.update { it.copy(progress = completed, total = total) }
                        !cancelRequested
                    }
                }
                val (plan, result) = checkNotNull(outcome) { "另一个任务正在运行，请稍后重试" }
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

    private fun refreshStatus(): LlmModelStatus {
        val spec = repository.catalog.single { it.kind == LlmModelKind.Decision }
        return runCatching { repository.status(spec.id) }.getOrElse {
            LlmModelStatus(spec, repository.directory(spec.id), 0, false, "无法读取模型目录")
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
        val CANCELLED = NativeDecisionResult(true, "", "")
    }
}
