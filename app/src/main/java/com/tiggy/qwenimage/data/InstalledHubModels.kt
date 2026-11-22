package com.tiggy.qwenimage.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

enum class HubModelType(val wire: String, val label: String) {
    GgufChat("gguf_chat", "GGUF 对话"),
    MnnChat("mnn_chat", "MNN 对话"),
    SdImage("sd_image", "图像模型");

    companion object {
        fun fromWire(value: String): HubModelType? = entries.firstOrNull { it.wire == value }
    }
}

data class InstalledHubFile(
    val path: String,
    val size: Long,
    val sha256: String?
) {
    fun toJson() = JSONObject().apply {
        put("path", path)
        put("size", size)
        if (!sha256.isNullOrBlank()) put("sha256", sha256)
    }

    companion object {
        fun fromJson(o: JSONObject) = InstalledHubFile(
            path = o.getString("path"),
            size = o.getLong("size"),
            sha256 = o.optString("sha256").takeIf { it.isNotBlank() }
        )
    }
}

data class InstalledHubModel(
    val id: String,
    val name: String,
    val repo: String,
    val revision: String,
    val type: HubModelType,
    val entryFile: String,
    val files: List<InstalledHubFile>,
    val totalBytes: Long,
    val quantTag: String? = null,
    val installedAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("repo", repo)
        put("revision", revision)
        put("type", type.wire)
        put("entryFile", entryFile)
        put("totalBytes", totalBytes)
        if (!quantTag.isNullOrBlank()) put("quantTag", quantTag)
        put("installedAt", installedAt)
        put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): InstalledHubModel? {
            val type = HubModelType.fromWire(o.optString("type")) ?: return null
            val filesArr = o.optJSONArray("files") ?: return null
            val files = (0 until filesArr.length()).map { InstalledHubFile.fromJson(filesArr.getJSONObject(it)) }
            return InstalledHubModel(
                id = o.getString("id"),
                name = o.getString("name"),
                repo = o.getString("repo"),
                revision = o.optString("revision", "main"),
                type = type,
                entryFile = o.getString("entryFile"),
                files = files,
                totalBytes = o.optLong("totalBytes", files.sumOf { it.size }),
                quantTag = o.optString("quantTag").takeIf { it.isNotBlank() },
                installedAt = o.optLong("installedAt")
            )
        }
    }
}

/** Manages custom models downloaded from Hugging Face / hf-mirror under models/hub/. */
class InstalledHubModelsRepository(private val context: Context) {
    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/hub")
    private val registryFile = File(root, "registry.json")
    private val lock = Any()
    private val _models = MutableStateFlow<List<InstalledHubModel>>(emptyList())
    val models: StateFlow<List<InstalledHubModel>> = _models.asStateFlow()

    init {
        refresh()
    }

    fun refresh(): List<InstalledHubModel> = synchronized(lock) {
        val loaded = readRegistry().filter { isPresent(it) }
        _models.value = loaded
        loaded
    }

    fun listByType(type: HubModelType): List<InstalledHubModel> = _models.value.filter { it.type == type }

    fun find(id: String): InstalledHubModel? = _models.value.firstOrNull { it.id == id }

    fun modelDir(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "模型 ID 无效" }
        return File(root, id)
    }

    fun entryFile(model: InstalledHubModel): File = safeChild(modelDir(model.id), model.entryFile)

    fun isPresent(model: InstalledHubModel): Boolean {
        val dir = runCatching { modelDir(model.id) }.getOrNull() ?: return false
        return model.files.all { file ->
            val target = runCatching { safeChild(dir, file.path) }.getOrNull() ?: return@all false
            target.isFile && (file.size <= 0L || target.length() == file.size)
        }
    }

    suspend fun downloadOption(
        option: HfRepoOption,
        hubSettings: HubSettings,
        onProgress: (file: String, downloaded: Long, total: Long) -> Unit
    ): InstalledHubModel = withContext(Dispatchers.IO) {
        require(option.splitWarning == null) { option.splitWarning ?: "暂不支持该文件" }
        require(option.files.isNotEmpty()) { "没有可下载的文件" }
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录" }
        val staging = File(root, ".${option.id}.downloading")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建暂存目录" }
        val opContext = coroutineContext

        val remaining = option.files.sumOf { file ->
            val target = safeChild(staging, file.path)
            val part = File(target.path + ".part")
            when {
                target.isFile && (file.size <= 0L || target.length() == file.size) -> 0L
                part.isFile && (file.size <= 0L || part.length() <= file.size) -> (file.size - part.length()).coerceAtLeast(0L)
                else -> file.size.coerceAtLeast(0L)
            }
        }
        val available = StatFs(staging.path).availableBytes
        check(available >= remaining + RESERVE_BYTES) {
            "空间不足，请至少预留 ${String.format("%.2f", (remaining + RESERVE_BYTES) / 1_000_000_000.0)} GB 可用空间"
        }

        val total = option.totalBytes.coerceAtLeast(1L)
        var completed = 0L
        val installedFiles = mutableListOf<InstalledHubFile>()

        for (file in option.files) {
            opContext.ensureActive()
            val target = safeChild(staging, file.path)
            target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建子目录" } }
            val expectedSize = file.size.takeIf { it > 0L }
            val alreadyGood = target.isFile && (expectedSize == null || target.length() == expectedSize) &&
                (file.sha256 == null || ResumableHttpDownloader.sha256(target, opContext) == file.sha256)
            if (!alreadyGood) {
                val part = File(target.path + ".part")
                val url = HubUrlRewriter.resolveFileUrl(option.repo, option.revision, file.path, hubSettings)
                val finalBytes = ResumableHttpDownloader.download(
                    url = url,
                    part = part,
                    expectedSize = expectedSize,
                    operation = opContext,
                    hubSettings = hubSettings
                ) { done, _ ->
                    onProgress(file.path, completed + done, total)
                }
                if (file.sha256 != null) {
                    onProgress("校验 ${file.path.substringAfterLast('/')}", completed + finalBytes, total)
                    val actualHash = ResumableHttpDownloader.sha256(part, opContext)
                    if (actualHash != file.sha256) {
                        part.delete()
                        error("${file.path} SHA-256 校验失败，请重试")
                    }
                }
                if (target.exists()) check(target.delete()) { "无法覆盖旧文件" }
                check(part.renameTo(target)) { "无法保存 ${file.path}" }
            }
            val actualSize = target.length()
            installedFiles += InstalledHubFile(file.path, actualSize, file.sha256)
            completed += actualSize
            onProgress(file.path, completed, total.coerceAtLeast(completed))
        }

        val targetDir = modelDir(option.id)
        val backupDir = File(root, ".${option.id}.backup")
        if (backupDir.exists()) backupDir.deleteRecursively()
        if (targetDir.exists()) check(targetDir.renameTo(backupDir)) { "无法备份旧模型" }
        if (!staging.renameTo(targetDir)) {
            if (backupDir.exists()) backupDir.renameTo(targetDir)
            error("无法启用已下载模型")
        }
        backupDir.deleteRecursively()

        val displayName = when (option.type) {
            HubModelType.MnnChat -> option.repo.substringAfter('/')
            else -> "${option.repo.substringAfter('/')} · ${option.quantTag ?: option.entryFile.substringAfterLast('/')}"
        }
        val record = InstalledHubModel(
            id = option.id,
            name = displayName.take(80),
            repo = option.repo,
            revision = option.revision,
            type = option.type,
            entryFile = option.entryFile,
            files = installedFiles,
            totalBytes = installedFiles.sumOf { it.size },
            quantTag = option.quantTag,
            installedAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            val next = readRegistry().filterNot { it.id == record.id } + record
            writeRegistry(next)
            _models.value = next.filter { isPresent(it) }
        }
        record
    }

    fun delete(id: String) {
        synchronized(lock) {
            val dir = modelDir(id)
            if (dir.exists()) check(dir.deleteRecursively()) { "无法删除模型文件" }
            File(root, ".$id.downloading").deleteRecursively()
            val next = readRegistry().filterNot { it.id == id }
            writeRegistry(next)
            _models.value = next.filter { isPresent(it) }
        }
    }

    private fun readRegistry(): List<InstalledHubModel> = runCatching {
        if (!registryFile.isFile) return emptyList()
        val json = JSONObject(registryFile.readText())
        val array = json.optJSONArray("models") ?: return emptyList()
        (0 until array.length()).mapNotNull { InstalledHubModel.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeRegistry(models: List<InstalledHubModel>) {
        if (!root.isDirectory) root.mkdirs()
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("models", JSONArray().apply { models.forEach { put(it.toJson()) } })
        val tmp = File(root, "registry.json.tmp")
        tmp.writeText(json.toString(2))
        if (registryFile.exists()) registryFile.delete()
        check(tmp.renameTo(registryFile)) { "无法保存自定义模型索引" }
    }

    private fun safeChild(base: File, relative: String): File {
        require(ModelManifest.validPath(relative)) { "非法路径：$relative" }
        val target = File(base, relative).canonicalFile
        require(target.path.startsWith(base.canonicalPath + File.separator)) { "越界路径：$relative" }
        return target
    }

    companion object {
        private const val RESERVE_BYTES = 512L * 1024 * 1024

        @Volatile private var instance: InstalledHubModelsRepository? = null

        fun get(context: Context): InstalledHubModelsRepository =
            instance ?: synchronized(this) {
                instance ?: InstalledHubModelsRepository(context.applicationContext).also { instance = it }
            }
    }
}
