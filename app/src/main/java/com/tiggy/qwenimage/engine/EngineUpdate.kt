package com.tiggy.qwenimage.engine

data class EngineUpdate(
    val progress: Float?,
    val stage: String,
    val log: String? = null
)
