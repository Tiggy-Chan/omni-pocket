package com.tiggy.qwenimage.data

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

enum class TaskDomain(val displayName: String) {
    Image("图像"),
    Music("音乐"),
    Video("视频"),
    Model("模型")
}

enum class GenerationBackend { LocalQwen, LocalMoss, LocalWan, LocalYue2 }

data class TaskUiState(
    val running: Boolean = false,
    val progress: Int? = null,
    val status: String = "准备就绪",
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val resultPath: String? = null
)

data class ActiveTask(
    val domain: TaskDomain,
    val id: String,
    val backend: GenerationBackend? = null
)

data class TaskStates(
    val imageTask: TaskUiState = TaskUiState(),
    val musicTask: TaskUiState = TaskUiState(),
    val videoTask: TaskUiState = TaskUiState(),
    val modelTask: TaskUiState = TaskUiState(),
    val activeTask: ActiveTask? = null
) {
    fun task(domain: TaskDomain): TaskUiState = when (domain) {
        TaskDomain.Image -> imageTask
        TaskDomain.Music -> musicTask
        TaskDomain.Video -> videoTask
        TaskDomain.Model -> modelTask
    }

    fun updateTask(domain: TaskDomain, transform: (TaskUiState) -> TaskUiState): TaskStates = when (domain) {
        TaskDomain.Image -> copy(imageTask = transform(imageTask))
        TaskDomain.Music -> copy(musicTask = transform(musicTask))
        TaskDomain.Video -> copy(videoTask = transform(videoTask))
        TaskDomain.Model -> copy(modelTask = transform(modelTask))
    }

    fun appendLog(domain: TaskDomain, line: String): TaskStates = updateTask(domain) { task ->
        task.copy(logs = (task.logs + line).takeLast(MAX_LOGS))
    }

    fun activate(domain: TaskDomain, id: String, backend: GenerationBackend? = null): TaskStates? =
        if (activeTask == null) copy(activeTask = ActiveTask(domain, id, backend)) else null

    fun release(domain: TaskDomain, id: String): TaskStates =
        if (isActive(domain, id)) copy(activeTask = null) else this

    fun isActive(domain: TaskDomain, id: String): Boolean =
        activeTask?.domain == domain && activeTask.id == id

    private companion object {
        const val MAX_LOGS = 80
    }
}
