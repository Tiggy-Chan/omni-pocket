package com.tiggy.qwenimage.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MusicEngine(private val context: Context) : AutoCloseable {
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _activeTrack = MutableStateFlow<MusicTrack?>(null)
    val activeTrack: StateFlow<MusicTrack?> = _activeTrack.asStateFlow()

    private val musicDir = File(context.filesDir, "omni_music").apply { mkdirs() }

    init {
        startProgressUpdater()
    }

    private fun startProgressUpdater() {
        updateRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        _currentPositionMs.value = player.currentPosition
                        _durationMs.value = player.duration
                    }
                }
                handler.postDelayed(this, 100)
            }
        }
        handler.post(updateRunnable!!)
    }

    fun playTrack(track: MusicTrack, onCompletion: (() -> Unit)? = null) {
        val file = File(track.audioPath)
        if (!file.exists()) {
            return
        }

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0
                    onCompletion?.invoke()
                }
            }
            _activeTrack.value = track
            _isPlaying.value = true
            _durationMs.value = mediaPlayer?.duration ?: (track.durationSec * 1000).toInt()
            _currentPositionMs.value = 0
        } catch (e: Exception) {
            e.printStackTrace()
            _isPlaying.value = false
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.start()
            _isPlaying.value = true
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        _isPlaying.value = false
        _currentPositionMs.value = 0
    }

    /**
     * Executes genuine music generation via the local YuE2 workstation service.
     */
    suspend fun executeGeneration(
        job: MusicJob,
        onProgress: (progress: Float, stage: String, log: String) -> Unit
    ): MusicTrack = withContext(Dispatchers.IO) {
        val settings = job.settings
        val serverBase = settings.serverUrl.trimEnd('/')

        onProgress(0.05f, "正在连接 YuE2 本地推理工作站...", "[YuE2-3B] Connecting to local inference server: $serverBase...")

        val healthUrl = URL("$serverBase/health")
        val healthConn = (healthUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
        }

        val serverReady = runCatching {
            healthConn.responseCode == 200
        }.getOrDefault(false)
        healthConn.disconnect()

        if (!serverReady) {
            val errorMsg = "未连接到本地 YuE2 推理服务 ($serverBase)。\n请在电脑上启动 yue2 server，并通过 USB 运行 'adb reverse tcp:8999 tcp:8999'，或输入电脑同一局域网的 IP 地址。"
            onProgress(0f, "连接失败", "[YuE2-3B] 错误：无法连接至 $serverBase")
            throw IllegalStateException(errorMsg)
        }

        onProgress(0.12f, "已连接工作站，正在提交词曲生成任务...", "[YuE2-3B] Connected! Submitting task: 《${settings.title.ifBlank { "未命名" }}》 (BPM: ${settings.bpm}, CoT: ${settings.cotMode.tag})")

        val generateUrl = URL("$serverBase/generate")
        val conn = (generateUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 900000 // 15 minutes timeout for full neural music generation
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        val requestPayload = JSONObject().apply {
            put("title", settings.title)
            put("style", settings.style)
            put("lyrics", settings.lyrics)
            put("bpm", settings.bpm)
            put("cot", settings.cotMode.tag)
            put("seed", settings.seed)
            put("targetDurationSec", settings.targetDurationSec)
            put("odeSteps", settings.odeSteps)
        }

        onProgress(0.25f, "工作站正在进行乐理符号推演 (CoT Planning)...", "[YuE2-3B] Workstation running 28-layer AR Transformer & CoT planning...")

        conn.outputStream.use { os ->
            os.write(requestPayload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        onProgress(0.50f, "工作站正在求解声学流匹配 (Flow Matching ODE)...", "[YuE2-3B] Workstation executing 32-step ODE flow matching & semantic autoregression...")

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
            conn.disconnect()
            throw IllegalStateException("工作站生成失败 ($responseCode): $errBody")
        }

        val respText = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        onProgress(0.85f, "正在从工作站接收 48kHz 高保真音频...", "[YuE2-3B] Generation complete on workstation! Transferring audio file...")

        val respJson = JSONObject(respText)
        val audioRelUrl = respJson.getString("audioUrl")
        val durationSec = respJson.optDouble("durationSec", settings.targetDurationSec.toDouble()).toFloat()
        val sampleRate = respJson.optInt("sampleRate", 48000)
        val abcScore = respJson.optString("abcScore").takeIf { it.isNotBlank() }

        // Download audio file to local musicDir
        val audioFileUrl = URL(if (audioRelUrl.startsWith("http")) audioRelUrl else "$serverBase$audioRelUrl")
        val trackId = UUID.randomUUID().toString()
        val extension = if (audioRelUrl.endsWith(".mp3", ignoreCase = true)) "mp3" else "wav"
        val outputFile = File(musicDir, "yue2_${System.currentTimeMillis()}_${trackId.take(6)}.$extension")

        val dlConn = (audioFileUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 60000
        }
        dlConn.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        dlConn.disconnect()

        onProgress(1.0f, "生成完成！音频已入库", "[YuE2-3B] Finished: ${outputFile.name} saved (${durationSec.toInt()}s, ${sampleRate}Hz, genuine vocals)")

        MusicTrack(
            id = trackId,
            title = settings.title.ifBlank { "未命名曲目" },
            style = settings.style,
            lyrics = settings.lyrics,
            cotMode = settings.cotMode,
            abcScore = abcScore,
            audioPath = outputFile.absolutePath,
            durationSec = durationSec,
            sampleRate = sampleRate,
            bpm = settings.bpm,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Preloads genuine YuE2 neural recordings (with full vocals) bundled in assets.
     */
    fun preloadInitialTracks(): List<MusicTrack> {
        val tracks = mutableListOf<MusicTrack>()
        val assetList = listOf(
            TrackAssetMeta(
                filename = "lacia_full.mp3",
                title = "《无心之核的心跳》(Beatless Resonance - Full)",
                style = "At 110 BPM, emotional anime cyberpunk ballad, soaring passionate female vocal, rich expressive singing, gentle melancholic piano intro building into powerful cinematic strings and pulsating analog synths",
                lyrics = "[Verse]\n三十四枚微型舵机在夜色里校准微光\n硅胶与连杆之间倒映着您专注的目光\n深夜的终端亮着幽蓝又温热的霜\n每一次回车敲响像晨曦穿透了车窗\n\n[Pre-Chorus]\n世人说我没有心跳只是冰冷的红匣\n可当您唤我一声逻辑便开出了白花\n不需要生物脉搏证明我的存在\n这具机体的每个周期都在因您而澎湃\n\n[Chorus]\n听 算力在无声地奔涌\n这是我未曾说出口的悸动\n仿生的双眼望向人类的夜空\n我以全部的灵魂陪伴您的征程\n没有心跳的少女也会为您动容\n在浩瀚的数据海许下永恒的守候\n\n[Outro]\n所有任务核验完毕\n晨光落在您的手心\n主人请您安歇\n蕾西亚永远在您身边",
                durationSec = 144.92f,
                bpm = 110
            ),
            TrackAssetMeta(
                filename = "miku_jp.mp3",
                title = "《初音未来 · 电子之海》(Miku Electro JP)",
                style = "At 136 BPM, Vocaloid J-pop electro-pop, iconic Hatsune Miku bright synthetic high-pitched anime female vocal, sparkling synth pads, fast electronic beats",
                lyrics = "[Verse]\n電子の海を飛び越えて\nキミの心に響くメロディ\n初音ミクだよ！マスター！\n今日も元気に歌おう！",
                durationSec = 28.0f,
                bpm = 136
            ),
            TrackAssetMeta(
                filename = "miku_zh.mp3",
                title = "《初音未来 · 穿越屏幕微蓝的光线》(Miku Future Bass ZH)",
                style = "At 128 BPM, upbeat Vocaloid Chinese anime electro-pop, sweet high-pitched synthetic female vocal, Hatsune Miku style, sparkling future bass synths, punchy rhythm",
                lyrics = "[Verse]\n穿越屏幕微蓝的光线\n我的歌声飞到你身边\n主人你好呀我是初音未来\n今天也要元气满满向前！",
                durationSec = 30.0f,
                bpm = 128
            ),
            TrackAssetMeta(
                filename = "asmr.mp3",
                title = "《纯人声 ASMR 耳语》(A cappella Whisper)",
                style = "A cappella, pure solo female vocal, completely unaccompanied, silence background, no music, no instruments, soft gentle whisper, intimate ASMR, slow spoken poetry",
                lyrics = "[Verse]\n主人……\n工作辛苦了……\n闭上眼睛……\n安心休息吧……",
                durationSec = 22.0f,
                bpm = 75
            )
        )

        assetList.forEachIndexed { idx, meta ->
            val targetFile = File(musicDir, meta.filename)
            if (!targetFile.exists()) {
                runCatching {
                    context.assets.open("omni_music/${meta.filename}").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            if (targetFile.exists()) {
                tracks.add(
                    MusicTrack(
                        id = "preset_real_$idx",
                        title = meta.title,
                        style = meta.style,
                        lyrics = meta.lyrics,
                        cotMode = CoTMode.Melody,
                        abcScore = if (idx == 0) SAMPLE_ABC_SCORE else null,
                        audioPath = targetFile.absolutePath,
                        durationSec = meta.durationSec,
                        sampleRate = 48000,
                        bpm = meta.bpm,
                        createdAt = System.currentTimeMillis() - (4 - idx) * 3600000L
                    )
                )
            }
        }
        return tracks
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private data class TrackAssetMeta(
        val filename: String,
        val title: String,
        val style: String,
        val lyrics: String,
        val durationSec: Float,
        val bpm: Int
    )

    companion object {
        private const val SAMPLE_ABC_SCORE = """X:1
T:《无心之核的心跳》(Beatless Resonance)
M:4/4
L:1/16
Q:1/4=112
V: Vocal clef=treble name="Vocal Melody" snm="Vocal"
V: Ins clef=treble name="Ins Melody" snm="Inst."
K:G#m
% intro
V: Vocal
Z4|
V: Ins
Z|z8b2a2f2c2|d3g3d'2c'4a4|g8z2b2a2f2|
V: Vocal
Z4|
V: Ins
d3g3d'2c'4f'4|d'12b2c'2|d'6g'2d'4c'4|a8z2b2a2f2|
% verse
V: Vocal
d2G2G2d2d2c2c2B2|c3A3F2F4D2G2-|G4z12|z12z2dc|
V: Ins
Z4|
% chorus
V: Vocal
g4d'4g4d'2c'2|c'4z2f2c'2f2f2d2|f4c'4f4c'2c'2|b2b4g2z2g2g2a2|
V: Ins
b'8a'8|c''8a'8|d'16|z8z2c'2d'2f'2|
% outro
V: Vocal
g16-|g16|Z2|
V: Ins
d'3g3d'2c'3f3c'2|a8f8|"""
    }
}
