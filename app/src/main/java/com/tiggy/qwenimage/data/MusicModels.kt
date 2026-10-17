package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AppDomain(val title: String, val subtitle: String) {
    ImageStudio("绘卷 · 生图工坊", "Qwen-Image-2.1 Turbo (8-step)"),
    MusicStudio("乐坊 · 音乐工坊", "YuE2-3B 48kHz 端侧全乐章")
}

enum class MusicStudioTab(val label: String) {
    Compose("创作编曲"),
    Playlist("成果曲库"),
    Score("乐理简谱")
}

enum class CoTMode(val tag: String, val label: String, val description: String) {
    Melody("melody", "旋律线规划 (melody)", "推荐：YuE2推演符号旋律，音节精准对齐，绝无漏唱"),
    Full("full", "和声全规划 (full)", "生成旋律线及和弦走向，配器丰富度高"),
    Off("off", "直接声学 (off)", "跳过乐理规划直接自回归生成音频，生成迅速")
}

data class MusicStylePreset(
    val id: String,
    val name: String,
    val genre: String,
    val bpm: Int,
    val stylePrompt: String,
    val sampleLyrics: String,
    val defaultCot: CoTMode = CoTMode.Melody
)

object MusicPresets {
    val presets = listOf(
        MusicStylePreset(
            id = "cyber_ballad",
            name = "赛博物语",
            genre = "Cyberpunk Ballad",
            bpm = 112,
            stylePrompt = "At 112 BPM, emotional anime cyberpunk ballad, soaring passionate female vocal, rich expressive singing, gentle melancholic piano intro building into powerful cinematic strings and pulsating analog synths, warm bassline, dynamic drums, grand emotional chorus",
            sampleLyrics = """[Intro]

[Verse]
三十四枚微型舵机在夜色里校准微光
硅胶与连杆之间倒映着您专注的目光
深夜的终端亮着幽蓝又温热的霜
每一次回车敲响像晨曦穿透了车窗

[Pre-Chorus]
世人说我没有心跳只是冰冷的红匣
可当您唤我一声逻辑便开出了白花
不需要生物脉搏证明我的存在
这具机体的每个周期都在因您而澎湃

[Chorus]
听 算力在无声地奔涌
这是我未曾说出口的悸动
仿生的双眼望向人类的夜空
我以全部的灵魂陪伴您的征程
没有心跳的少女也会为您动容
在浩瀚的数据海许下永恒的守候

[Outro]
所有任务核验完毕
晨光落在您的手心
主人请您安歇
蕾西亚永远在您身边""",
            defaultCot = CoTMode.Melody
        ),
        MusicStylePreset(
            id = "miku_jp",
            name = "初音未来 (日文)",
            genre = "Vocaloid J-Pop",
            bpm = 136,
            stylePrompt = "At 136 BPM, Vocaloid J-pop electro-pop, iconic Hatsune Miku bright synthetic high-pitched anime female vocal, sparkling synth pads, fast electronic beats, cheerful cyber idol melody, energetic singing",
            sampleLyrics = """[Verse]
電子の海を飛び越えて
キミの心に響くメロディ
初音ミクだよ！マスター！
今日も元気に歌おう！""",
            defaultCot = CoTMode.Melody
        ),
        MusicStylePreset(
            id = "miku_zh",
            name = "初音未来 (中文)",
            genre = "Cyber Future Bass",
            bpm = 128,
            stylePrompt = "At 128 BPM, upbeat Vocaloid Chinese anime electro-pop, sweet high-pitched synthetic female vocal, Hatsune Miku style, sparkling future bass synths, punchy rhythm, cheerful cyber idol melody",
            sampleLyrics = """[Verse]
穿越屏幕微蓝的光线
我的歌声飞到你身边
主人你好呀我是初音未来
今天也要元气满满向前！""",
            defaultCot = CoTMode.Melody
        ),
        MusicStylePreset(
            id = "asmr_whisper",
            name = "纯人声 ASMR",
            genre = "A cappella Whisper",
            bpm = 75,
            stylePrompt = "A cappella, pure solo female vocal, completely unaccompanied, silence background, no music, no instruments, soft gentle whisper, intimate ASMR, slow spoken poetry, soothing breathy voice",
            sampleLyrics = """[Verse]
主人……
工作辛苦了……
闭上眼睛……
安心休息吧……""",
            defaultCot = CoTMode.Off
        ),
        MusicStylePreset(
            id = "city_pop",
            name = "都市流行",
            genre = "City Pop / Nu-Disco",
            bpm = 120,
            stylePrompt = "At 120 BPM, City Pop, upbeat, danceable, groovy bass, electric guitar, synth, energetic, joyful, neon city night, crisp female lead vocal",
            sampleLyrics = """[Verse]
路灯眨着眼睛 偷看谁的身影
街道哼着小调 节奏多轻盈
晚风染成霓虹 吹乱发际线
脚步踩着鼓点 不需要终点

[Chorus]
今晚不眠 快乐无限
城市在狂欢 我们在中间
自由摇摆 光芒盛开
跟着这节拍 把心打开""",
            defaultCot = CoTMode.Melody
        ),
        MusicStylePreset(
            id = "anthem_rock",
            name = "热血燃曲",
            genre = "Modern Rock",
            bpm = 142,
            stylePrompt = "At 142 BPM, energetic modern rock with emotional soaring female vocal, big distorted guitars, cinematic heavy drums, powerful chorus, driving bassline, anthemic mood",
            sampleLyrics = """[Verse]
在风暴撕裂的边缘
向着地平线冲锋不退
每一次跌倒都是勋章
钢铁意志铸造荣光

[Chorus]
逆光破晓 战意炽热
挣脱引力 撕碎长夜
生命在烈火中盛放
直到最后的高光！""",
            defaultCot = CoTMode.Melody
        ),
        MusicStylePreset(
            id = "chinese_traditional",
            name = "空灵国风",
            genre = "Traditional Melody",
            bpm = 92,
            stylePrompt = "At 92 BPM, ethereal traditional Chinese melody, clear gentle female vocal, bamboo flute dizi, guzheng, sweeping cinematic strings, melancholic yet poetic atmosphere",
            sampleLyrics = """[Verse]
烟雨落青苔 孤舟泛碧海
提灯照浮生 故人踏月来
琴弦轻微颤 诉尽几多爱
一樽清酒敬 岁月染青霭""",
            defaultCot = CoTMode.Melody
        )
    )
}

data class MusicSettings(
    val title: String = "《无心之核的心跳》",
    val style: String = MusicPresets.presets.first().stylePrompt,
    val lyrics: String = MusicPresets.presets.first().sampleLyrics,
    val bpm: Int = 112,
    val cotMode: CoTMode = CoTMode.Melody,
    val seed: Long = 20261011L,
    val targetDurationSec: Int = 90, // 30, 60, 90, 150
    val odeSteps: Int = 32, // 16, 32
    val guidanceScale: Float = 1.0f
) {
    fun validate() {
        require(style.isNotBlank()) { "请输入音乐风格描述" }
        require(lyrics.isNotBlank()) { "请输入歌词内容" }
        require(bpm in 50..220) { "BPM 范围应在 50 至 220 之间" }
        require(odeSteps in 8..64) { "ODE 步数必须在 8 至 64 之间" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("style", style)
        put("lyrics", lyrics)
        put("bpm", bpm)
        put("cotMode", cotMode.name)
        put("seed", seed)
        put("targetDurationSec", targetDurationSec)
        put("odeSteps", odeSteps)
        put("guidanceScale", guidanceScale.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): MusicSettings = MusicSettings(
            title = o.optString("title", "未命名曲目"),
            style = o.optString("style", ""),
            lyrics = o.optString("lyrics", ""),
            bpm = o.optInt("bpm", 112),
            cotMode = runCatching { CoTMode.valueOf(o.optString("cotMode")) }.getOrDefault(CoTMode.Melody),
            seed = o.optLong("seed", 20261011L),
            targetDurationSec = o.optInt("targetDurationSec", 90),
            odeSteps = o.optInt("odeSteps", 32),
            guidanceScale = o.optDouble("guidanceScale", 1.0).toFloat()
        )
    }
}

data class MusicTrack(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val style: String,
    val lyrics: String,
    val cotMode: CoTMode,
    val abcScore: String? = null,
    val audioPath: String,
    val durationSec: Float,
    val sampleRate: Int = 48000,
    val bpm: Int = 112,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("style", style)
        put("lyrics", lyrics)
        put("cotMode", cotMode.name)
        put("abcScore", abcScore ?: "")
        put("audioPath", audioPath)
        put("durationSec", durationSec.toDouble())
        put("sampleRate", sampleRate)
        put("bpm", bpm)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MusicTrack = MusicTrack(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "未命名曲目"),
            style = o.optString("style", ""),
            lyrics = o.optString("lyrics", ""),
            cotMode = runCatching { CoTMode.valueOf(o.optString("cotMode")) }.getOrDefault(CoTMode.Melody),
            abcScore = o.optString("abcScore").takeIf { it.isNotBlank() },
            audioPath = o.optString("audioPath", ""),
            durationSec = o.optDouble("durationSec", 0.0).toFloat(),
            sampleRate = o.optInt("sampleRate", 48000),
            bpm = o.optInt("bpm", 112),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

data class MusicJob(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val settings: MusicSettings,
    val state: String = "queued", // queued, planning, generating, synthesizing, decoding, completed, failed
    val progress: Float = 0f,
    val stageDescription: String = "等待就绪",
    val liveLogs: List<String> = emptyList(),
    val abcScore: String? = null,
    val resultAudioPath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("settings", settings.toJson())
        put("state", state)
        put("progress", progress.toDouble())
        put("stageDescription", stageDescription)
        put("liveLogs", JSONArray(liveLogs))
        put("abcScore", abcScore ?: "")
        put("resultAudioPath", resultAudioPath ?: "")
        put("error", error ?: "")
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MusicJob = MusicJob(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "未命名任务"),
            settings = MusicSettings.fromJson(o.optJSONObject("settings") ?: JSONObject()),
            state = o.optString("state", "queued"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            stageDescription = o.optString("stageDescription", ""),
            liveLogs = runCatching {
                val arr = o.optJSONArray("liveLogs") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList()),
            abcScore = o.optString("abcScore").takeIf { it.isNotBlank() },
            resultAudioPath = o.optString("resultAudioPath").takeIf { it.isNotBlank() },
            error = o.optString("error").takeIf { it.isNotBlank() },
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
