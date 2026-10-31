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
import java.io.IOException
import java.util.Locale
import java.util.UUID

class MusicEngine(private val context: Context) : AutoCloseable {
    private var mediaPlayer: MediaPlayer? = null
    private var preparing = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeProcess: Process? = null

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
    private val modelDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/yue2")

    /** Missing or incomplete YuE2 files; empty when local generation can run. */
    fun missingComponents(): List<String> = buildList {
        REQUIRED_FILES.forEach { (name, size) ->
            val file = File(modelDir, name)
            if (!file.isFile || file.length() != size) add(name)
        }
        if (!File(context.applicationInfo.nativeLibraryDir, NATIVE_LIB).isFile) add(NATIVE_LIB)
    }

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
        activeProcess?.destroy()
    }

    suspend fun executeGeneration(
        job: MusicJob,
        onUpdate: (EngineUpdate) -> Unit
    ): MusicTrack = withContext(Dispatchers.IO) {
        cancelRequested = false
        job.settings.validate()
        val settings = job.settings
        val missing = missingComponents()
        if (missing.isNotEmpty()) throw IOException("本机 YuE2 缺少：${missing.joinToString("、")}")

        val trackId = "yue2_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val outputFile = File(musicDir, "$trackId.wav")
        val partialFile = File(musicDir, "$trackId.part.wav")
        val workDir = File(context.cacheDir, "yue2-$trackId")
        var process: Process? = null
        try {
            if (!workDir.mkdirs()) throw IOException("无法创建音乐临时目录")
            val command = buildYue2Command(
                Yue2Options(
                    nativeLib = File(context.applicationInfo.nativeLibraryDir, NATIVE_LIB),
                    modelDir = modelDir,
                    output = partialFile,
                    outputDir = workDir,
                    style = settings.style,
                    bpm = settings.bpm,
                    lyrics = settings.lyrics,
                    cot = settings.cotMode,
                    seed = settings.seed,
                    durationSec = settings.targetDurationSec,
                    odeSteps = settings.odeSteps,
                    guidanceScale = settings.guidanceScale.takeIf { it != DEFAULT_GUIDANCE }
                )
            )
            onUpdate(EngineUpdate(0f, "正在加载 YuE2", "YuE2-3B Q4_0 · CPU · seed=${settings.seed} · ${settings.targetDurationSec} 秒 · ${settings.odeSteps} 步"))
            val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
            builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            process = builder.start()
            activeProcess = process
            if (cancelRequested) throw CancellationException("已取消音乐生成")

            val parser = Yue2ProgressParser()
            val input = process.inputStream
            val buffer = ByteArray(4096)
            var outputTail = ""
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val chunk = String(buffer, 0, count, Charsets.UTF_8)
                outputTail = (outputTail + chunk).takeLast(4_000)
                parser.feed(chunk).forEach { dispatchYue2Event(it, settings.cotMode, onUpdate) }
            }
            val exitCode = process.waitFor()
            parser.finish().forEach { dispatchYue2Event(it, settings.cotMode, onUpdate) }
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消音乐生成")
            if (exitCode != 0 || partialFile.length() < 1024L) {
                val detail = outputTail.lines().filterNot { it.startsWith("[TRACE") || it.startsWith("[TIMING") }
                    .joinToString("\n").trim().takeLast(600)
                throw IOException("本机音乐生成失败（退出码 $exitCode）${if (detail.isEmpty()) "" else "\n$detail"}")
            }
            if (outputFile.exists() || !partialFile.renameTo(outputFile)) throw IOException("无法保存生成的音频")

            val info = readAudioInfo(outputFile)
            require(info.valid) { "生成结果不是可识别的音频" }
            val score = File(workDir, "score.abc").takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            val track = MusicTrack(
                id = trackId,
                title = settings.title.ifBlank { "未命名曲目" },
                style = settings.style,
                lyrics = settings.lyrics,
                cotMode = settings.cotMode,
                abcScore = score,
                audioPath = outputFile.absolutePath,
                durationSec = info.durationSec,
                sampleRate = info.sampleRate,
                bpm = settings.bpm,
                createdAt = System.currentTimeMillis()
            )
            saveMetadata(track)
            onUpdate(EngineUpdate(1f, "音频已保存", "${outputFile.name} · ${String.format(Locale.US, "%.1f", info.durationSec)} 秒"))
            track
        } catch (error: Throwable) {
            if (cancelRequested) throw CancellationException("已取消音乐生成") else throw error
        } finally {
            activeProcess = null
            process?.let { if (it.isAlive) it.destroy() }
            partialFile.delete()
            workDir.deleteRecursively()
        }
    }

    private fun dispatchYue2Event(event: Yue2Event, cot: CoTMode, onUpdate: (EngineUpdate) -> Unit) {
        val seconds = event.seconds?.let { " · ${String.format(Locale.US, "%.1f", it)} 秒" }.orEmpty()
        when (event.stage) {
            Yue2Stage.Loading -> Unit
            Yue2Stage.Planning -> onUpdate(
                EngineUpdate(0.05f, if (cot == CoTMode.Off) "正在生成语义" else "正在规划乐谱", "模型已加载$seconds")
            )
            Yue2Stage.Semantic -> onUpdate(EngineUpdate(0.1f, "正在生成语义"))
            Yue2Stage.Acoustic -> onUpdate(
                EngineUpdate(0.45f, "正在合成声学", "语义 ${event.tokens ?: 0} 帧 · 约 ${(event.tokens ?: 0) / YUE2_FRAMES_PER_SECOND} 秒")
            )
            Yue2Stage.Decoding -> onUpdate(EngineUpdate(0.85f, "正在解码音频", "声学合成完成$seconds"))
            Yue2Stage.Done -> onUpdate(EngineUpdate(0.97f, "正在保存音频", "解码完成$seconds"))
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

    /** Restores generated tracks saved in the app's music directory. */
    fun preloadInitialTracks(): List<MusicTrack> {
        val generated = musicDir.listFiles { file ->
            file.isFile && file.extension.lowercase() in AUDIO_EXTENSIONS
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
        return generated.sortedByDescending { it.createdAt }
    }

    override fun close() {
        releasePlayer()
        handler.removeCallbacksAndMessages(null)
        cancelGeneration()
        _activeTrack.value = null
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac", "ogg", "m4a")
        private const val NATIVE_LIB = "libyue2_cli.so"
        private const val DEFAULT_GUIDANCE = 1.0f
        // Exact sizes of the audio-cpp/Yue2-3B-GGUF files pushed by scripts/push_music_models.sh.
        private val REQUIRED_FILES = mapOf(
            YUE2_MODEL_FILE to 2_665_641_504L,
            YUE2_VAE_FILE to 265_218_656L,
            "sidecars/yue2-generation-config.json" to 466L,
            "sidecars/yue2-model-config.json" to 959L,
            "sidecars/yue2-qwen.tiktoken" to 2_561_218L,
            "sidecars/yue2-vae-config.json" to 1_378L
        )
    }
}
