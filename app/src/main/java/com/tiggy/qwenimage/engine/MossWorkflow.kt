package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.MOSS_TEXT_ENCODER
import com.tiggy.qwenimage.data.MOSS_VAE
import com.tiggy.qwenimage.data.MossSettings
import org.json.JSONArray
import org.json.JSONObject

object MossWorkflow {
    private const val UNET = "1"
    private const val CLIP = "2"
    private const val VAE = "3"
    private const val MODEL_SAMPLING = "4"
    private const val SLG = "5"
    private const val UPSAMPLER = "6"
    private const val POSITIVE = "7"
    private const val NEGATIVE = "8"
    private const val LATENT = "9"
    private const val SAMPLER = "10"
    private const val DECODE = "11"
    private const val SAVE = "12"

    private const val UPSAMPLER_SYSTEM_PROMPT = "将请求扩写为完整可见画面描述，保留所有原文文字，只输出画面描述"

    fun build(settings: MossSettings, outputPrefix: String = "omni_pocket_moss"): JSONObject {
        settings.validate()
        val positiveText: Any = if (settings.usesPromptUpsampler) link(UPSAMPLER) else settings.modelPrompt()
        val samplerModel = if (settings.slg.enabled) link(SLG) else link(MODEL_SAMPLING)

        return JSONObject().apply {
            put(UNET, node("UNETLoader", JSONObject().apply {
                put("unet_name", settings.checkpoint.fileName)
                put("weight_dtype", "default")
            }))
            put(CLIP, node("CLIPLoader", JSONObject().apply {
                put("clip_name", MOSS_TEXT_ENCODER)
                put("type", "moss_image")
                put("device", "default")
            }))
            put(VAE, node("VAELoader", JSONObject().put("vae_name", MOSS_VAE)))
            put(MODEL_SAMPLING, node("ModelSamplingAuraFlow", JSONObject().apply {
                put("model", link(UNET))
                put("shift", settings.shift)
            }))
            if (settings.slg.enabled) {
                put(SLG, node("SkipLayerGuidanceDiTSimple", JSONObject().apply {
                    put("model", link(MODEL_SAMPLING))
                    put("double_layers", "")
                    put("single_layers", settings.slg.singleLayers)
                    put("start_percent", settings.slg.startPercent)
                    put("end_percent", settings.slg.endPercent)
                }))
            }
            if (settings.usesPromptUpsampler) {
                put(UPSAMPLER, node("TextGenerate", JSONObject().apply {
                    put("clip", link(CLIP))
                    put("prompt", settings.prompt)
                    put("system_prompt", UPSAMPLER_SYSTEM_PROMPT)
                    put("max_length", 4096)
                    put("sampling_mode", "on")
                    put("temperature", 0.7)
                    put("top_k", 64)
                    put("top_p", 0.95)
                    put("min_p", 0.05)
                    put("repetition_penalty", 1.0)
                    put("seed", settings.seed)
                    put("presence_penalty", 0.0)
                    put("thinking", false)
                    put("use_default_template", true)
                    put("mtp", "auto")
                }))
            }
            put(POSITIVE, node("CLIPTextEncode", JSONObject().apply {
                put("clip", link(CLIP))
                put("text", positiveText)
            }))
            put(NEGATIVE, node("CLIPTextEncode", JSONObject().apply {
                put("clip", link(CLIP))
                put("text", settings.negativePrompt)
            }))
            put(LATENT, node("EmptyFlux2LatentImage", JSONObject().apply {
                put("width", settings.width)
                put("height", settings.height)
                put("batch_size", 1)
            }))
            put(SAMPLER, node("KSampler", JSONObject().apply {
                put("model", samplerModel)
                put("positive", link(POSITIVE))
                put("negative", link(NEGATIVE))
                put("latent_image", link(LATENT))
                put("seed", settings.seed)
                put("steps", settings.steps)
                put("cfg", settings.cfg)
                put("sampler_name", settings.sampler)
                put("scheduler", settings.scheduler)
                put("denoise", 1.0)
            }))
            put(DECODE, node("VAEDecode", JSONObject().apply {
                put("samples", link(SAMPLER))
                put("vae", link(VAE))
            }))
            put(SAVE, node("SaveImage", JSONObject().apply {
                put("images", link(DECODE))
                put("filename_prefix", outputPrefix)
            }))
        }
    }

    fun requiredNodeTypes(settings: MossSettings): Set<String> = buildSet {
        add("UNETLoader")
        add("CLIPLoader")
        add("VAELoader")
        add("CLIPTextEncode")
        add("EmptyFlux2LatentImage")
        add("ModelSamplingAuraFlow")
        if (settings.slg.enabled) add("SkipLayerGuidanceDiTSimple")
        if (settings.usesPromptUpsampler) add("TextGenerate")
        add("KSampler")
        add("VAEDecode")
        add("SaveImage")
    }

    private fun node(classType: String, inputs: JSONObject) = JSONObject().apply {
        put("class_type", classType)
        put("inputs", inputs)
    }

    private fun link(nodeId: String, output: Int = 0) = JSONArray().put(nodeId).put(output)
}
