package com.tiggy.qwenimage.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Only verified manifests become active. Failed imports/downloads retain the previous model. */
class ModelRepository(private val context: Context) {
    val modelDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/qwen_image21_uc_q4km")
    private val preferences = context.getSharedPreferences("model-source", Context.MODE_PRIVATE)
    var sourceRepo: String
        get() = preferences.getString("repo", "").orEmpty()
        set(value) { preferences.edit().putString("repo", value.trim()).apply() }
    var revision: String
        get() = preferences.getString("revision", "main").orEmpty()
        set(value) { preferences.edit().putString("revision", value.trim().ifEmpty { "main" }).apply() }

    fun readManifest(): ModelManifest? = runCatching {
        ModelManifest.fromJson(JSONObject(File(modelDir, "manifest.json").readText()))
    }.getOrNull()

    fun status(): ModelStatus {
        recoverBackup()
        val manifest = readManifest()
        if (manifest == null) return ModelStatus(modelDir, ModelManifest.empty(), 0, 0, false, "请下载或导入经过校验的 MNN 转换包")
        val count = manifest.artifacts.count { File(modelDir, it.path).let { f -> f.isFile && f.length() == it.size } }
        val verification = runCatching { JSONObject(File(modelDir, ".verified.json").readText()) }.getOrNull()
        val verified = verification?.optString("manifestHash") == hash(File(modelDir, "manifest.json")) &&
            manifest.artifacts.all { a ->
                val f = File(modelDir, a.path)
                verification?.optJSONObject(a.path)?.let { v ->
                    v.optLong("size") == f.length() && v.optLong("modified") == f.lastModified() && v.optString("sha256") == a.sha256
                } == true
            }
        val ready = count == manifest.artifacts.size && verified
        return ModelStatus(modelDir, manifest, count, manifest.artifacts.size, ready, if (ready) "模型已校验，可以生成" else "${count}/${manifest.artifacts.size} 个文件；需要完整校验")
    }

    suspend fun download(onProgress: (String, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val repo = sourceRepo.trim()
        val downloadRevision = revision
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "仓库格式为 owner/repo，必须是包含 manifest.json 的 MNN 仓库" }
        val json = fetchJson(endpoint(repo, downloadRevision, "manifest.json"))
        val manifest = ModelManifest.fromJson(json)
        val staging = File(modelDir.parentFile, "${modelDir.name}.downloading")
        staging.mkdirs()
        val total = manifest.artifacts.sumOf { it.size }
        val remaining = manifest.artifacts.sumOf { (it.size - File(staging, it.path + ".part").length()).coerceAtLeast(0) }
        check(StatFs(staging.path).availableBytes > remaining + 512L * 1024 * 1024) { "空间不足，需至少 ${remaining / 1_000_000_000.0} GB 及 512MB 余量" }
        var completed = 0L
        for (artifact in manifest.artifacts) {
            coroutineContext.ensureActive()
            val target = safeChild(staging, artifact.path)
            if (!(target.isFile && matches(target, artifact))) {
                downloadOne(endpoint(repo, downloadRevision, artifact.path), artifact, target) { done ->
                    onProgress(artifact.path, completed + done, total)
                }
            }
            completed += artifact.size
            onProgress(artifact.path, completed, total)
        }
        File(staging, "manifest.json").writeText(json.toString(2))
        verifyDirectory(staging, manifest) { onProgress("校验 $it", completed, total) }
        activate(staging)
    }

    suspend fun importTree(uri: Uri, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("无法打开所选目录")
        val manifestDocument = root.findFile("manifest.json") ?: error("目录缺少 manifest.json；请先在电脑完成转换")
        val manifestText = context.contentResolver.openInputStream(manifestDocument.uri).use { it!!.bufferedReader().readText() }
        val manifest = ModelManifest.fromJson(JSONObject(manifestText))
        val staging = File(modelDir.parentFile, "${modelDir.name}.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        check(StatFs(staging.path).availableBytes > manifest.artifacts.sumOf { it.size } + 512L * 1024 * 1024) { "空间不足以导入模型" }
        manifest.artifacts.forEachIndexed { index, artifact ->
            coroutineContext.ensureActive()
            val source = findDocument(root, artifact.path) ?: error("本地目录缺少 ${artifact.path}")
            val target = safeChild(staging, artifact.path)
            target.parentFile!!.mkdirs()
            context.contentResolver.openInputStream(source.uri).use { input ->
                requireNotNull(input)
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            onProgress("导入 ${index + 1}/${manifest.artifacts.size}：${artifact.path}")
            check(matches(target, artifact)) { "${artifact.path} 大小或 SHA-256 不匹配" }
        }
        File(staging, "manifest.json").writeText(manifestText)
        recordVerification(staging, manifest)
        activate(staging)
    }

    suspend fun verify(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val manifest = readManifest() ?: error("没有有效的模型清单")
        verifyDirectory(modelDir, manifest, onProgress)
    }

    private suspend fun verifyDirectory(root: File, manifest: ModelManifest, onProgress: (String) -> Unit) {
        for (a in manifest.artifacts) {
            coroutineContext.ensureActive()
            onProgress(a.path)
            check(matches(safeChild(root, a.path), a)) { "${a.path} 校验失败" }
        }
        recordVerification(root, manifest)
    }

    private fun recordVerification(root: File, manifest: ModelManifest) {
        val json = JSONObject().put("manifestHash", hash(File(root, "manifest.json")))
        manifest.artifacts.forEach { a ->
            val f = File(root, a.path)
            json.put(a.path, JSONObject().put("size", f.length()).put("modified", f.lastModified()).put("sha256", a.sha256))
        }
        File(root, ".verified.json").writeText(json.toString())
    }

    private fun activate(staging: File) {
        val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
        backup.deleteRecursively()
        if (modelDir.exists()) check(modelDir.renameTo(backup)) { "无法备份现有模型" }
        if (!staging.renameTo(modelDir)) {
            backup.renameTo(modelDir)
            error("无法激活模型，已恢复之前的模型")
        }
        backup.deleteRecursively()
    }

    private fun recoverBackup() {
        val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
        if (!modelDir.exists() && backup.exists()) backup.renameTo(modelDir)
    }

    private fun fetchJson(url: String): JSONObject {
        val c = open(url)
        try {
            check(c.responseCode == 200) { "模型清单 HTTP ${c.responseCode}" }
            val text = c.inputStream.use { it.bufferedReader().readText() }
            require(text.length <= 256 * 1024) { "模型清单过大" }
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    private suspend fun downloadOne(url: String, artifact: ModelArtifact, target: File, progress: (Long) -> Unit) {
        target.parentFile!!.mkdirs()
        val part = File(target.path + ".part")
        val identity = File(target.path + ".resume")
        if (identity.takeIf { it.isFile }?.readText() != artifact.sha256 || part.length() > artifact.size) part.delete()
        identity.writeText(artifact.sha256)
        if (part.length() == artifact.size && matches(part, artifact)) {
            target.delete(); check(part.renameTo(target)); identity.delete(); return
        }
        var have = part.length()
        val c = open(url)
        try {
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val response = c.responseCode
            require(response == 200 || response == 206) { "${artifact.path} HTTP $response" }
            if (response == 206) require(c.getHeaderField("Content-Range")?.startsWith("bytes $have-") == true) { "服务器返回错误的续传范围" }
            val append = response == 206 && have > 0
            if (!append) have = 0
            var done = have
            c.inputStream.use { input ->
                part.outputStream(append).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        check(done + n <= artifact.size) { "文件超过清单大小" }
                        output.write(buffer, 0, n); done += n
                        progress(done)
                    }
                }
            }
            if (!matches(part, artifact)) { part.delete(); error("${artifact.path} 校验失败，请重新下载") }
            target.delete(); check(part.renameTo(target)); identity.delete()
        } finally { c.disconnect() }
    }

    private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append)
    private fun matches(file: File, artifact: ModelArtifact) = file.isFile && file.length() == artifact.size && hash(file) == artifact.sha256
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer); if (n <= 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000; readTimeout = 60_000
        setRequestProperty("Accept-Encoding", "identity"); instanceFollowRedirects = true
    }
    private fun endpoint(repo: String, revision: String, path: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "https://huggingface.co/$repo/resolve/${enc(revision)}/${path.split('/').joinToString("/") { enc(it) }}"
    }
    private fun safeChild(root: File, path: String): File {
        require(ModelManifest.validPath(path))
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator))
        return target
    }
    private fun findDocument(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (name in path.split('/')) current = current.findFile(name) ?: return null
        return current
    }
}
