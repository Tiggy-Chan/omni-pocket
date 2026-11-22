package com.tiggy.qwenimage.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class PackagedModelArtifact(
    val path: String,
    val size: Long,
    val sha256: String,
    val url: String
)

data class PackagedModelManifest(
    val modelId: String,
    val artifacts: List<PackagedModelArtifact>
) {
    val totalBytes: Long get() = artifacts.sumOf { it.size }

    companion object {
        fun fromJson(json: JSONObject): PackagedModelManifest {
            require(json.getInt("schemaVersion") == 1) { "不支持的模型清单版本" }
            val modelId = json.getString("modelId")
            require(modelId.isNotBlank()) { "缺少 modelId" }
            val files = json.getJSONArray("files")
            require(files.length() in 1..64) { "清单文件数量异常" }
            val artifacts = (0 until files.length()).map { index ->
                val item = files.getJSONObject(index)
                val path = item.getString("path")
                val size = item.getLong("size")
                val sha256 = item.getString("sha256").lowercase()
                val url = item.getString("url")
                require(ModelManifest.validPath(path)) { "非法路径：$path" }
                require(size > 0L && sha256.matches(Regex("[0-9a-f]{64}"))) { "缺少有效大小或 SHA-256" }
                require(url.startsWith("https://")) { "来源必须为 HTTPS" }
                PackagedModelArtifact(path, size, sha256, url)
            }
            require(artifacts.map { it.path }.distinct().size == artifacts.size) { "清单出现重复文件" }
            return PackagedModelManifest(modelId, artifacts)
        }
    }
}

data class PackagedModelStatus(
    val modelId: String,
    val directory: File,
    val present: Int,
    val total: Int,
    val totalBytes: Long,
    val ready: Boolean,
    val verified: Boolean,
    val missing: List<String>,
    val message: String
)

/**
 * Generic pinned-manifest repository for bundled modalities (Wan2.2 video, YuE2 music).
 * Supports mirror-aware resumable download, SHA-256 verification, and deletion.
 */
class PackagedModelRepository(
    private val context: Context,
    private val assetName: String,
    dirRelativePath: String,
    private val displayName: String,
    private val hubPreferences: HubPreferences = HubPreferences.get(context)
) {
    val modelDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, dirRelativePath)
    val manifest: PackagedModelManifest by lazy {
        val text = context.assets.open(assetName).use { ResumableHttpDownloader.readLimited(it, 256 * 1024) }
        PackagedModelManifest.fromJson(JSONObject(text))
    }

    fun status(): PackagedModelStatus {
        recoverBackup()
        val missing = mutableListOf<String>()
        var present = 0
        for (artifact in manifest.artifacts) {
            val file = safeChild(modelDir, artifact.path)
            if (file.isFile && file.length() == artifact.size) {
                present++
            } else {
                missing += artifact.path
            }
        }
        val verified = present == manifest.artifacts.size && verificationValid()
        val ready = present == manifest.artifacts.size
        val message = when {
            verified -> "已校验"
            ready -> "文件完整（可运行，建议校验）"
            present > 0 -> "$present/${manifest.artifacts.size} 个文件"
            else -> "未下载"
        }
        return PackagedModelStatus(
            modelId = manifest.modelId,
            directory = modelDir,
            present = present,
            total = manifest.artifacts.size,
            totalBytes = manifest.totalBytes,
            ready = ready,
            verified = verified,
            missing = missing,
            message = message
        )
    }

    suspend fun download(onProgress: (file: String, done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        recoverBackup()
        val staging = File(modelDir.parentFile, "${modelDir.name}.downloading")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建 $displayName 暂存目录" }
        val op = coroutineContext
        val hubSettings = hubPreferences.current()

        // Reuse already-complete files from existing modelDir into staging if present and valid.
        onProgress("检查本地已有文件…", 0L, manifest.totalBytes)
        val validPaths = mutableSetOf<String>()
        val reusableFromExisting = mutableListOf<PackagedModelArtifact>()
        val remaining = manifest.artifacts.sumOf { artifact ->
            val staged = safeChild(staging, artifact.path)
            val existing = safeChild(modelDir, artifact.path)
            when {
                matches(staged, artifact, op) -> {
                    validPaths += artifact.path
                    0L
                }
                matches(existing, artifact, op) -> {
                    reusableFromExisting += artifact
                    artifact.size
                }
                else -> {
                    val part = File(staged.path + ".part")
                    val identity = File(staged.path + ".resume")
                    val resumeBytes = if (part.isFile && part.length() <= artifact.size &&
                        runCatching { identity.readText() }.getOrNull() == artifact.sha256
                    ) part.length() else 0L
                    artifact.size - resumeBytes
                }
            }
        }
        val available = StatFs(staging.path).availableBytes
        check(available >= remaining + RESERVE_BYTES) {
            "空间不足，还需 ${String.format("%.2f", (remaining + RESERVE_BYTES) / 1_000_000_000.0)} GB 可用空间"
        }
        for (artifact in reusableFromExisting) {
            op.ensureActive()
            val staged = safeChild(staging, artifact.path)
            staged.parentFile?.mkdirs()
            safeChild(modelDir, artifact.path).copyTo(staged, overwrite = true)
            validPaths += artifact.path
        }

        val total = manifest.totalBytes
        var completed = 0L
        for (artifact in manifest.artifacts) {
            op.ensureActive()
            val target = safeChild(staging, artifact.path)
            if (artifact.path !in validPaths) {
                downloadArtifact(artifact, target, hubSettings, op) { done ->
                    onProgress(artifact.path.substringAfterLast('/'), completed + done, total)
                }
            }
            completed += artifact.size
            onProgress(artifact.path.substringAfterLast('/'), completed, total)
        }
        onProgress("校验完整性", total, total)
        verifyDirectory(staging, op)
        activate(staging)
    }

    suspend fun verify(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        recoverBackup()
        val op = coroutineContext
        for (artifact in manifest.artifacts) {
            op.ensureActive()
            onProgress(artifact.path.substringAfterLast('/'))
            check(matches(safeChild(modelDir, artifact.path), artifact, op)) {
                "${artifact.path} 大小或 SHA-256 校验失败"
            }
        }
        recordVerification(modelDir)
    }

    fun delete() {
        if (modelDir.exists()) check(modelDir.deleteRecursively()) { "无法删除 $displayName 模型" }
        File(modelDir.parentFile, "${modelDir.name}.downloading").deleteRecursively()
        File(modelDir.parentFile, "${modelDir.name}.backup").deleteRecursively()
    }

    private fun downloadArtifact(
        artifact: PackagedModelArtifact,
        target: File,
        hubSettings: HubSettings,
        op: CoroutineContext,
        onDone: (Long) -> Unit
    ) {
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建子目录" } }
        val part = File(target.path + ".part")
        val identity = File(target.path + ".resume")
        if (part.length() > artifact.size || (part.isFile && runCatching { identity.readText() }.getOrNull() != artifact.sha256)) {
            if (part.exists()) check(part.delete()) { "无法清理失效续传文件" }
        }
        identity.writeText(artifact.sha256)
        ResumableHttpDownloader.download(
            url = artifact.url,
            part = part,
            expectedSize = artifact.size,
            operation = op,
            hubSettings = hubSettings
        ) { done, _ -> onDone(done) }
        if (!matches(part, artifact, op)) {
            part.delete()
            identity.delete()
            error("${artifact.path} SHA-256 校验失败，请重新下载")
        }
        if (target.exists()) check(target.delete()) { "无法覆盖旧文件" }
        check(part.renameTo(target)) { "无法保存 ${artifact.path}" }
        identity.delete()
    }

    private fun verifyDirectory(root: File, op: CoroutineContext) {
        for (artifact in manifest.artifacts) {
            op.ensureActive()
            check(matches(safeChild(root, artifact.path), artifact, op)) { "${artifact.path} 校验失败" }
        }
        recordVerification(root)
    }

    private fun recordVerification(root: File) {
        val json = JSONObject().put("modelId", manifest.modelId)
        for (artifact in manifest.artifacts) {
            val file = safeChild(root, artifact.path)
            json.put(
                artifact.path,
                JSONObject()
                    .put("size", file.length())
                    .put("modified", file.lastModified())
                    .put("sha256", artifact.sha256)
            )
        }
        val target = File(root, ".verified.json")
        val tmp = File(root, ".verified.json.tmp")
        tmp.writeText(json.toString())
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "无法保存校验记录" }
    }

    private fun verificationValid(): Boolean = runCatching {
        val verification = JSONObject(File(modelDir, ".verified.json").readText())
        verification.optString("modelId") == manifest.modelId &&
            manifest.artifacts.all { artifact ->
                val file = safeChild(modelDir, artifact.path)
                val rec = verification.optJSONObject(artifact.path) ?: return@all false
                file.isFile && rec.optLong("size") == file.length() &&
                    rec.optLong("modified") == file.lastModified() &&
                    rec.optString("sha256") == artifact.sha256
            }
    }.getOrDefault(false)

    private fun activate(staging: File) {
        val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
        if (backup.exists()) check(backup.deleteRecursively()) { "无法清理旧备份" }
        if (modelDir.exists()) check(modelDir.renameTo(backup)) { "无法备份现有模型" }
        if (!staging.renameTo(modelDir)) {
            if (backup.exists()) backup.renameTo(modelDir)
            error("无法启用 $displayName 模型")
        }
        backup.deleteRecursively()
    }

    private fun recoverBackup() {
        val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
        if (!modelDir.exists() && backup.exists()) backup.renameTo(modelDir)
    }

    private fun matches(file: File, artifact: PackagedModelArtifact, op: CoroutineContext): Boolean =
        file.isFile && file.length() == artifact.size && ResumableHttpDownloader.sha256(file, op) == artifact.sha256

    private fun safeChild(base: File, path: String): File {
        require(ModelManifest.validPath(path))
        val target = File(base, path).canonicalFile
        require(target.path.startsWith(base.canonicalPath + File.separator))
        return target
    }

    companion object {
        private const val RESERVE_BYTES = 512L * 1024 * 1024

        fun wanVideo(context: Context) = PackagedModelRepository(
            context = context,
            assetName = "wan_video.json",
            dirRelativePath = "models/wan22_turbo",
            displayName = "Wan2.2 视频"
        )

        fun yue2Music(context: Context) = PackagedModelRepository(
            context = context,
            assetName = "yue2_music.json",
            dirRelativePath = "models/yue2",
            displayName = "YuE2 音乐"
        )
    }
}
