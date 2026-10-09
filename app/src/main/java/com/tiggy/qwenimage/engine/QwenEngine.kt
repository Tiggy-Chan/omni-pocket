package com.tiggy.qwenimage.engine

import android.graphics.Bitmap
import com.scsonic.qwenimage21.QwenImage21
import java.io.File

class QwenEngine(private val modelDir: File, private val settings: EngineSettings) : AutoCloseable {
    private val model: QwenImage21

    init {
        val options = QwenImage21.Options().apply {
            useGpu = true
            textEncoderOnCpu = true
            vaeOnCpu = !settings.tinyVae
            keepModelsLoaded = settings.keepModelsLoaded
            threads = settings.cpuThreads
            turbo = settings.turbo
            officialTurbo = settings.officialTurbo
            dit2Bit = false
            tinyVae = settings.tinyVae
            refSize = if (settings.halfReference) QwenImage21.RefSize.HALF else QwenImage21.RefSize.FULL
            crashMarkerFile = settings.crashMarker
        }
        model = QwenImage21(modelDir, options)
    }

    fun generate(
        prompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        output: File,
        onProgress: (Int) -> Unit
    ): Bitmap = model.generate(prompt, width, height, steps, seed, output, onProgress)

    fun edit(
        prompt: String,
        input: File,
        input2: File?,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        output: File,
        onProgress: (Int) -> Unit
    ): Bitmap {
        val tier = if (width * height <= 384 * 384) QwenImage21.Size.Tier.FAST
        else QwenImage21.Size.Tier.STANDARD
        return model.edit(prompt, input, input2, tier, steps, seed, output, onProgress)
    }

    override fun close() = model.close()

    companion object {
        fun availableMemoryMb(): Int = QwenImage21.availableMemoryMB()
    }
}

data class EngineSettings(
    val turbo: Boolean,
    val officialTurbo: Boolean,
    val tinyVae: Boolean,
    val keepModelsLoaded: Boolean,
    val halfReference: Boolean,
    val cpuThreads: Int,
    val crashMarker: File
) {
    val key: String
        get() = "$turbo:$officialTurbo:$tinyVae:$keepModelsLoaded:$halfReference:$cpuThreads"
}
