package com.tiggy.qwenimage.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MusicEngine(private val context: Context) : AutoCloseable {
    private var mediaPlayer: MediaPlayer? = null
    private var preparing = false
    private val handler = Handler(Looper.getMainLooper())
    private val activeClients = ConcurrentHashMap.newKeySet<WorkstationClient>()

    @Volatile
    private var cancelRequested = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            if (player.isPlaying) {
                _currentPositionMs.value = player.currentPosition
                _durationMs.value = player.duration
                handler.postDelayed(this, 250)
            }
        }
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _activeTrack = MutableStateFlow<MusicTrack?>(null)
    val activeTrack: StateFlow<MusicTrack?> = _activeTrack.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val musicDir = File(context.filesDir, "omni_music").apply { mkdirs() }

    fun playTrack(track: MusicTrack, onCompletion: (() -> Unit)? = null) {
        val file = File(track.audioPath)
        if (!file.isFile) {
            _playbackError.value = "音频文件不存在"
            return
        }

        releasePlayer()
        _activeTrack.value = track
        _currentPositionMs.value = 0
        _durationMs.value = (track.durationSec * 1000).toInt()
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer !== prepared) return@setOnPreparedListener
                preparing = false
                prepared.start()
                _isPlaying.value = true
                _durationMs.value = prepared.duration
                handler.post(progressUpdater)
                onCompletion?.let { completion ->
                    prepared.setOnCompletionListener {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0
                        handler.removeCallbacks(progressUpdater)
                        completion()
                    }
                }
            }
            player.setOnCompletionListener {
                _isPlaying.value = false
                _currentPositionMs.value = 0
                handler.removeCallbacks(progressUpdater)
            }
            player.setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) playbackFailed("无法播放此音频")
                true
            }
            preparing = true
            player.prepareAsync()
        } catch (error: Exception) {
            playbackFailed(error.message ?: "无法初始化播放器")
        }
    }

    fun togglePlayPause(track: MusicTrack) {
        val player = mediaPlayer
        if (player == null || _activeTrack.value?.id != track.id) {
            playTrack(track)
            return
        }
        if (preparing) return
        try {
            if (player.isPlaying) {
                pausePlayback()
            } else {
                if (_durationMs.value > 0 && _currentPositionMs.value >= _durationMs.value) player.seekTo(0)
                player.start()
                _isPlaying.value = true
                handler.post(progressUpdater)
            }
        } catch (error: Exception) {
            playbackFailed(error.message ?: "播放器状态异常")
        }
    }

    fun pausePlayback() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) player.pause()
        } catch (error: Exception) {
            playbackFailed(error.message ?: "无法暂停播放")
            return
        }
        _isPlaying.value = false
        handler.removeCallbacks(progressUpdater)
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
            _currentPositionMs.value = positionMs
        } catch (error: Exception) {
            playbackFailed(error.message ?: "无法调整播放位置")
        }
    }

    fun stopPlayback() {
        releasePlayer()
        _currentPositionMs.value = 0
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    private fun playbackFailed(message: String) {
        _playbackError.value = message
        releasePlayer()
    }

    private fun releasePlayer() {
        preparing = false
        handler.removeCallbacks(progressUpdater)
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
    }

    fun cancelGeneration() {
        cancelRequested = true
        activeClients.forEach { it.close() }
    }

    suspend fun executeGeneration(
        job: MusicJob,
        onUpdate: (EngineUpdate) -> Unit
    ): MusicTrack = withContext(Dispatchers.IO) {
        cancelRequested = false
        job.settings.validate()
        val settings = job.settings
        val client = WorkstationClient(settings.serverUrl)
        activeClients += client
        try {
            onUpdate(EngineUpdate(null, "正在连接 YuE2 工作站"))
            if (!client.isHealthy()) {
                throw IOException("无法连接 YuE2 工作站。请启动服务；USB 连接可运行 adb reverse tcp:8999 tcp:8999")
            }
            val payload = JSONObject().apply {
                put("title", settings.title)
                put("style", settings.style)
                put("lyrics", settings.lyrics)
                put("bpm", settings.bpm)
                put("cot", settings.cotMode.tag)
                put("seed", settings.seed)
                put("targetDurationSec", settings.targetDurationSec)
                put("odeSteps", settings.odeSteps)
                put("guidanceScale", settings.guidanceScale.toDouble())
            }
            onUpdate(EngineUpdate(null, "工作站正在生成", "任务已提交，等待服务器返回"))
            val response = client.postJson("/generate", payload, 900_000)
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消音乐生成")

            val reference = response.getString("audioUrl")
            val extension = client.resolve(reference).path.substringAfterLast('.', "wav").lowercase()
                .takeIf { it in AUDIO_EXTENSIONS } ?: "wav"
            val trackId = "yue2_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val outputFile = File(musicDir, "$trackId.$extension")
            onUpdate(EngineUpdate(null, "正在接收音频"))
            client.download(reference, outputFile, 512L * 1024 * 1024, "audio")

            val info = readAudioInfo(outputFile)
            require(info.valid) { "下载内容不是可识别的音频" }
            val track = MusicTrack(
                id = trackId,
                title = settings.title.ifBlank { "未命名曲目" },
                style = settings.style,
                lyrics = settings.lyrics,
                cotMode = settings.cotMode,
                abcScore = response.optString("abcScore").takeIf { it.isNotBlank() },
                audioPath = outputFile.absolutePath,
                durationSec = info.durationSec.takeIf { it > 0 }
                    ?: response.optDouble("durationSec", settings.targetDurationSec.toDouble()).toFloat(),
                sampleRate = info.sampleRate.takeIf { it > 0 } ?: response.optInt("sampleRate", 0),
                bpm = settings.bpm,
                createdAt = System.currentTimeMillis()
            )
            saveMetadata(track)
            onUpdate(EngineUpdate(1f, "音频已保存", outputFile.name))
            track
        } catch (error: Throwable) {
            if (cancelRequested) throw CancellationException("已取消音乐生成") else throw error
        } finally {
            activeClients -= client
            client.close()
        }
    }

    private fun readAudioInfo(file: File): AudioInfo = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            require(mimeType.startsWith("audio/") || hasAudio) { "不是音频" }
            AudioInfo(
                valid = true,
                durationSec = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull() ?: 0f) / 1000,
                sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            )
        } finally {
            retriever.release()
        }
    }.getOrDefault(AudioInfo())

    private fun saveMetadata(track: MusicTrack) {
        val audio = File(track.audioPath)
        val target = metadataFile(audio)
        val partial = File(audio.parentFile, "${audio.nameWithoutExtension}.json.part")
        partial.writeText(track.toJson().toString())
        if (target.exists() && !target.delete()) throw IOException("无法更新音频元数据")
        if (!partial.renameTo(target)) throw IOException("无法保存音频元数据")
    }

    private fun metadataFile(audio: File) = File(audio.parentFile, "${audio.nameWithoutExtension}.json")

    private data class AudioInfo(val valid: Boolean = false, val durationSec: Float = 0f, val sampleRate: Int = 0)

    /**
     * Preloads genuine YuE2 neural recordings (with full vocals) bundled in assets.
     */
    fun preloadInitialTracks(): List<MusicTrack> {
        val tracks = mutableListOf<MusicTrack>()
        val assetList = listOf(
            TrackAssetMeta(
                filename = "sample_ballad.mp3",
                title = "《夏夜晚风》(Summer Breeze)",
                style = "At 110 BPM, emotional anime cyberpunk ballad, soaring passionate female vocal, rich expressive singing, gentle melancholic piano intro building into powerful cinematic strings and pulsating analog synths",
                lyrics = "[Verse]\n晚风吹过街角的灯\n城市慢慢安静下来\n我把今天写进旋律\n留给明天的自己\n\n[Chorus]\n唱吧 让心跳跟着节拍\n唱吧 把烦恼留在窗外",
                durationSec = 144.92f,
                bpm = 110
            ),
            TrackAssetMeta(
                filename = "miku_jp.mp3",
                title = "《初音未来 · 电子之海》(Miku Electro JP)",
                style = "At 136 BPM, Vocaloid J-pop electro-pop, iconic Hatsune Miku bright synthetic high-pitched anime female vocal, sparkling synth pads, fast electronic beats",
                lyrics = "[Verse]\n電子の海を飛び越えて\nキミの心に響くメロディ\n初音ミクだよ！\n今日も元気に歌おう！",
                durationSec = 28.0f,
                bpm = 136
            ),
            TrackAssetMeta(
                filename = "miku_zh.mp3",
                title = "《初音未来 · 穿越屏幕微蓝的光线》(Miku Future Bass ZH)",
                style = "At 128 BPM, upbeat Vocaloid Chinese anime electro-pop, sweet high-pitched synthetic female vocal, Hatsune Miku style, sparkling future bass synths, punchy rhythm",
                lyrics = "[Verse]\n穿越屏幕微蓝的光线\n我的歌声飞到你身边\n你好呀我是初音未来\n今天也要元气满满向前！",
                durationSec = 30.0f,
                bpm = 128
            ),
            TrackAssetMeta(
                filename = "a_cappella.mp3",
                title = "《纯人声清唱》(A cappella)",
                style = "A cappella, pure solo female vocal, completely unaccompanied, silence background, no music, no instruments, clear gentle singing",
                lyrics = "[Verse]\n晚风吹过街角的灯\n城市慢慢安静下来\n我把今天写进旋律\n留给明天的自己\n\n[Chorus]\n唱吧 让心跳跟着节拍\n唱吧 把烦恼留在窗外",
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
                val info = readAudioInfo(targetFile)
                tracks.add(
                    MusicTrack(
                        id = "preset_real_$idx",
                        title = meta.title,
                        style = meta.style,
                        lyrics = meta.lyrics,
                        cotMode = CoTMode.Melody,
                        abcScore = if (idx == 0) SAMPLE_ABC_SCORE else null,
                        audioPath = targetFile.absolutePath,
                        durationSec = info.durationSec.takeIf { it > 0 } ?: meta.durationSec,
                        sampleRate = info.sampleRate,
                        bpm = meta.bpm,
                        createdAt = targetFile.lastModified()
                    )
                )
            }
        }

        val bundledPaths = tracks.map { it.audioPath }.toSet()
        val generated = musicDir.listFiles { file ->
            file.isFile && file.extension.lowercase() in AUDIO_EXTENSIONS && file.absolutePath !in bundledPaths
        }.orEmpty().mapNotNull { file ->
            val restored = runCatching { MusicTrack.fromJson(JSONObject(metadataFile(file).readText())) }.getOrNull()
            val info = readAudioInfo(file)
            if (!info.valid) return@mapNotNull null
            restored?.copy(
                audioPath = file.absolutePath,
                durationSec = info.durationSec.takeIf { it > 0 } ?: restored.durationSec,
                sampleRate = info.sampleRate.takeIf { it > 0 } ?: restored.sampleRate
            ) ?: MusicTrack(
                id = file.nameWithoutExtension,
                title = file.nameWithoutExtension,
                style = "",
                lyrics = "",
                cotMode = CoTMode.Off,
                audioPath = file.absolutePath,
                durationSec = info.durationSec,
                sampleRate = info.sampleRate,
                createdAt = file.lastModified()
            )
        }
        return (generated + tracks).sortedByDescending { it.createdAt }
    }

    override fun close() {
        releasePlayer()
        handler.removeCallbacksAndMessages(null)
        cancelGeneration()
        _activeTrack.value = null
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
        private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac", "ogg", "m4a")
        private const val SAMPLE_ABC_SCORE = """X:1
T:《夏夜晚风》(Summer Breeze)
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
