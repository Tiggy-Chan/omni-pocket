package com.tiggy.qwenimage.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class MossModelStatus(
    val directory: File,
    val present: Int,
    val total: Int,
    val ready: Boolean,
    val message: String
)

class MossModelRepository(private val context: Context) {
    val modelDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/moss_image")

    fun status(): MossModelStatus {
        recoverBackup()
        val manifest = readManifest()
        if (manifest == null) {
            return MossModelStatus(modelDir, 0, MossModelManifest.FILES.size, false, "请下载或导入经过校验的 MOSS 本地模型")
        }
        val count = manifest.artifacts.count { artifact ->
            safeChild(modelDir, artifact.path).let { it.isFile && it.length() == artifact.size }
        }
        val verification = runCatching { JSONObject(File(modelDir, ".verified.json").readText()) }.getOrNull()
        val manifestHash = runCatching { hash(File(modelDir, "manifest.json")) }.getOrNull()
        val verified = verification != null && verification.optString("manifestHash") == manifestHash &&
            manifest.artifacts.all { artifact ->
                val file = safeChild(modelDir, artifact.path)
                val record = verification.optJSONObject(artifact.path)
                record != null && record.optLong("size") == file.length() &&
                    record.optLong("modified") == file.lastModified() && record.optString("sha256") == artifact.sha256
            }
        val ready = count == manifest.artifacts.size && verified
        val message = if (ready) "MOSS 模型已校验，可以本地生成" else "$count/${manifest.artifacts.size} 个文件；需要完整校验"
        return MossModelStatus(modelDir, count, manifest.artifacts.size, ready, message)
    }

    fun artifactPath(path: String): File {
        require(MossModelManifest.validPath(path) && MossModelManifest.contains(path)) { "路径不在 MOSS 模型清单中：$path" }
        return safeChild(modelDir, path)
    }

    suspend fun download(onProgress: (String, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        recoverBackup()
        val manifestText = context.assets.open("moss_image.json").use { readManifestText(it) }
        val manifest = MossModelManifest.fromJson(JSONObject(manifestText))
        val staging = prepareStaging("downloading")
        val operationContext = coroutineContext
        val validTargets = mutableSetOf<String>()
        val total = manifest.artifacts.sumOf { it.size }
        val remaining = manifest.artifacts.sumOf { artifact ->
            val target = safeChild(staging, artifact.path)
            if (matches(target, artifact, operationContext)) {
                validTargets += artifact.path
                0L
            } else {
                val part = File(target.path + ".part")
                val identity = File(target.path + ".resume")
                val resumeBytes = if (part.isFile && part.length() <= artifact.size &&
                    runCatching { identity.readText() }.getOrNull() == artifact.sha256
                ) part.length() else 0L
                artifact.size - resumeBytes
            }
        }
        ensureSpace(staging, remaining)
        var completed = 0L
        for (artifact in manifest.artifacts) {
            operationContext.ensureActive()
            val target = safeChild(staging, artifact.path)
            if (artifact.path !in validTargets) {
                downloadOne(artifact, target) { done ->
                    onProgress(artifact.path, completed + done, total)
                }
            }
            completed += artifact.size
            onProgress(artifact.path, completed, total)
        }
        File(staging, "manifest.json").writeText(manifestText)
        verifyDirectory(staging, manifest) { onProgress("校验 $it", completed, total) }
        activate(staging)
    }

    suspend fun importTree(uri: Uri, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        recoverBackup()
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("无法打开所选目录")
        val manifestDocument = root.findFile("manifest.json") ?: error("目录缺少 manifest.json")
        val manifestText = openDocument(manifestDocument).use { readManifestText(it) }
        val manifest = MossModelManifest.fromJson(JSONObject(manifestText))
        val staging = prepareStaging("importing")
        val operationContext = coroutineContext
        val validTargets = mutableSetOf<String>()
        val remaining = manifest.artifacts.sumOf { artifact ->
            val target = safeChild(staging, artifact.path)
            if (matches(target, artifact, operationContext)) {
                validTargets += artifact.path
                0L
            } else {
                (artifact.size - File(target.path + ".part").length()).coerceAtLeast(0L)
            }
        }
        ensureSpace(staging, remaining)
        manifest.artifacts.forEachIndexed { index, artifact ->
            operationContext.ensureActive()
            val source = findDocument(root, artifact.path) ?: error("本地目录缺少 ${artifact.path}")
            val target = safeChild(staging, artifact.path)
            if (artifact.path !in validTargets) {
                val part = File(target.path + ".part")
                part.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建导入目录" } }
                openDocument(source).use { input ->
                    FileOutputStream(part, false).use { output ->
                        copy(input, output, artifact.size, operationContext)
                    }
                }
                if (!matches(part, artifact, operationContext)) {
                    part.delete()
                    error("${artifact.path} 大小或 SHA-256 不匹配")
                }
                replaceWithPart(part, target)
            }
            onProgress("导入 ${index + 1}/${manifest.artifacts.size}：${artifact.path}")
        }
        File(staging, "manifest.json").writeText(manifestText)
        recordVerification(staging, manifest)
        activate(staging)
    }

    suspend fun verify(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        recoverBackup()
        val manifest = readManifest() ?: error("没有有效的 MOSS 模型清单")
        verifyDirectory(modelDir, manifest, onProgress)
    }

    private suspend fun verifyDirectory(
        root: File,
        manifest: MossModelManifest,
        onProgress: (String) -> Unit
    ) {
        val operationContext = coroutineContext
        for (artifact in manifest.artifacts) {
            operationContext.ensureActive()
            onProgress(artifact.path)
            check(matches(safeChild(root, artifact.path), artifact, operationContext)) { "${artifact.path} 校验失败" }
        }
        recordVerification(root, manifest)
    }

    private fun recordVerification(root: File, manifest: MossModelManifest) {
        val json = JSONObject().put("manifestHash", hash(File(root, "manifest.json")))
        manifest.artifacts.forEach { artifact ->
            val file = safeChild(root, artifact.path)
            json.put(artifact.path, JSONObject()
                .put("size", file.length())
                .put("modified", file.lastModified())
                .put("sha256", artifact.sha256))
        }
        val target = File(root, ".verified.json")
        val temporary = File(root, ".verified.json.tmp")
        temporary.writeText(json.toString())
        if (target.exists()) check(target.delete()) { "无法更新校验记录" }
        check(temporary.renameTo(target)) { "无法保存校验记录" }
    }

    private fun activate(staging: File) {
        val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
        if (backup.exists()) check(backup.deleteRecursively()) { "无法清理旧备份" }
        if (modelDir.exists()) check(modelDir.renameTo(backup)) { "无法备份现有 MOSS 模型" }
        if (!staging.renameTo(modelDir)) {
            if (backup.exists()) backup.renameTo(modelDir)
            error("无法激活 MOSS 模型，已恢复之前的模型")
        }
        backup.deleteRecursively()
    }

    private fun recoverBackup() {
        val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
        if (!modelDir.exists() && backup.exists()) backup.renameTo(modelDir)
    }

    private fun readManifest(): MossModelManifest? = runCatching {
        MossModelManifest.fromJson(JSONObject(File(modelDir, "manifest.json").readText()))
    }.getOrNull()

    private fun prepareStaging(suffix: String): File {
        val staging = File(modelDir.parentFile, "${modelDir.name}.$suffix")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建 MOSS 暂存目录" }
        return staging
    }

    private fun ensureSpace(directory: File, remaining: Long) {
        val available = StatFs(directory.path).availableBytes
        check(available >= remaining + RESERVE_BYTES) {
            "空间不足，还需下载 ${remaining / 1_000_000_000.0} GB，并保留 512MB 余量"
        }
    }

    private suspend fun downloadOne(artifact: MossModelArtifact, target: File, progress: (Long) -> Unit) {
        val operationContext = coroutineContext
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建模型目录" } }
        val part = File(target.path + ".part")
        val identity = File(target.path + ".resume")
        val resumeIdentity = runCatching { identity.readText() }.getOrNull()
        if (part.length() > artifact.size || (part.isFile && resumeIdentity != artifact.sha256)) {
            check(!part.exists() || part.delete()) { "无法清理无效的续传文件" }
        }
        identity.writeText(artifact.sha256)
        if (part.isFile && part.length() == artifact.size) {
            if (matches(part, artifact, operationContext)) {
                replaceWithPart(part, target, identity)
                return
            }
            check(part.delete()) { "无法清理校验失败的模型文件" }
        }
        var have = part.length()
        val connection = open(artifact.url)
        try {
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val response = connection.responseCode
            require(response == HttpURLConnection.HTTP_OK || (have > 0 && response == HttpURLConnection.HTTP_PARTIAL)) {
                "${artifact.path} HTTP $response"
            }
            if (response == HttpURLConnection.HTTP_PARTIAL) validateContentRange(connection, have, artifact.size)
            val append = response == HttpURLConnection.HTTP_PARTIAL && have > 0
            if (!append) have = 0
            var done = have
            progress(done)
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        operationContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        check(done + count <= artifact.size) { "文件超过清单大小" }
                        output.write(buffer, 0, count)
                        done += count
                        progress(done)
                    }
                }
            }
            check(done == artifact.size) { "${artifact.path} 下载不完整，可继续重试" }
            if (!matches(part, artifact, operationContext)) {
                part.delete()
                identity.delete()
                error("${artifact.path} SHA-256 校验失败，请重新下载")
            }
            replaceWithPart(part, target, identity)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateContentRange(connection: HttpURLConnection, start: Long, size: Long) {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
            .matchEntire(connection.getHeaderField("Content-Range").orEmpty())
        require(match != null) { "服务器缺少有效的 Content-Range" }
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3]
        require(match.groupValues[1].toLong() == start && end >= start && end < size &&
            (total == "*" || total.toLong() == size)
        ) { "服务器返回错误的续传范围" }
    }

    private fun replaceWithPart(part: File, target: File, identity: File? = null) {
        if (target.exists()) check(target.delete()) { "无法替换旧模型文件" }
        check(part.renameTo(target)) { "无法保存模型文件 ${target.name}" }
        identity?.delete()
    }

    private fun copy(input: InputStream, output: FileOutputStream, maximum: Long, operationContext: CoroutineContext) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            operationContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            copied += count
            check(copied <= maximum) { "导入文件超过清单大小" }
            output.write(buffer, 0, count)
        }
    }

    private fun matches(file: File, artifact: MossModelArtifact, operationContext: CoroutineContext? = null): Boolean =
        file.isFile && file.length() == artifact.size && hash(file) { operationContext?.ensureActive() } == artifact.sha256

    private fun hash(file: File, onRead: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                onRead()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("Accept-Encoding", "identity")
        instanceFollowRedirects = true
    }

    private fun openDocument(document: DocumentFile): InputStream =
        context.contentResolver.openInputStream(document.uri) ?: error("无法打开 ${document.name ?: "模型文件"}")

    private fun readManifestText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var length = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            length += count
            require(length <= MAX_MANIFEST_BYTES) { "模型清单过大" }
            if (count > 0) output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun safeChild(root: File, path: String): File {
        require(MossModelManifest.validPath(path))
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator))
        return target
    }

    private fun findDocument(root: DocumentFile, path: String): DocumentFile? {
        require(MossModelManifest.validPath(path))
        var current = root
        for (name in path.split('/')) current = current.findFile(name) ?: return null
        return current.takeIf { it.isFile }
    }

    companion object {
        const val SFT_FILE = "MOSS-Image-SFT-Q4_0.gguf"
        const val TEXT_ENCODER_FILE = "Qwen3.5-9B-Q4_K_M.gguf"
        const val VAE_FILE = "flux2-vae_small.safetensors"
        const val TOKENIZER_FILE = "tokenizer.json"

        private const val BUFFER_SIZE = 1 shl 20
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val RESERVE_BYTES = 512L * 1024 * 1024
    }
}
