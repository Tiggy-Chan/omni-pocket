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
    DecisionGguf("decision_gguf", "决策模型"),
    SdImage("sd_image", "图像模型"),
    VideoWan("video_wan", "视频模型"),
    MusicAudio("music_audio", "音乐模型");

    companion object {
        fun fromWire(value: String): HubModelType? = entries.firstOrNull { it.wire == value }
    }
}

data class InstalledHubFile(
    val path: String,
    val size: Long,
    val sha256: String?,
    /** When non-null, file lives under models/hub/_companions/<sharedKey> and is shared across models. */
    val sharedKey: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("path", path)
        put("size", size)
        if (!sha256.isNullOrBlank()) put("sha256", sha256)
        if (!sharedKey.isNullOrBlank()) put("sharedKey", sharedKey)
    }

    companion object {
        fun fromJson(o: JSONObject) = InstalledHubFile(
            path = o.getString("path"),
            size = o.getLong("size"),
            sha256 = o.optString("sha256").takeIf { it.isNotBlank() },
            sharedKey = o.optString("sharedKey").takeIf { it.isNotBlank() }
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
    val installedAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
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
        if (metadata.isNotEmpty()) {
            put("metadata", JSONObject().apply { metadata.forEach { (k, v) -> put(k, v) } })
        }
        put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): InstalledHubModel? {
            val type = HubModelType.fromWire(o.optString("type")) ?: return null
            val filesArr = o.optJSONArray("files") ?: return null
            val files = (0 until filesArr.length()).map { InstalledHubFile.fromJson(filesArr.getJSONObject(it)) }
            val metaObj = o.optJSONObject("metadata")
            val metadata = buildMap {
                if (metaObj != null) {
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = metaObj.optString(k)
                        if (v.isNotBlank()) put(k, v)
                    }
                }
            }
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
                installedAt = o.optLong("installedAt"),
                metadata = metadata
            )
        }
    }
}

/** Manages custom models downloaded from Hugging Face / hf-mirror under models/hub/. */
class InstalledHubModelsRepository(private val context: Context) {
    private val modelsBase = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val root = File(modelsBase, "hub")
    val companionsDir = File(root, "_companions")
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

    /** Resolves a companion file stored either in _companions/ or already present in a built-in modality folder. */
    fun companionFile(sharedKey: String): File {
        require(ModelManifest.validPath(sharedKey)) { "非法共享组件路径：$sharedKey" }
        val inCompanions = safeChild(companionsDir, sharedKey)
        if (inCompanions.isFile) return inCompanions
        val builtInFallback = builtInCompanionFallback(sharedKey)
        if (builtInFallback != null && builtInFallback.isFile) return builtInFallback
        return inCompanions
    }

    fun resolveFile(model: InstalledHubModel, file: InstalledHubFile): File =
        if (!file.sharedKey.isNullOrBlank()) companionFile(file.sharedKey)
        else safeChild(modelDir(model.id), file.path)

    /** Resolves an optional metadata companion key (e.g. "llmCompanion", "vaeCompanion", "t5Companion", "taeCompanion"). */
    fun resolveCompanionByMeta(model: InstalledHubModel, metaKey: String): File? {
        val sharedKey = model.metadata[metaKey]?.takeIf { it.isNotBlank() } ?: return null
        return companionFile(sharedKey).takeIf { it.isFile }
    }

    fun isPresent(model: InstalledHubModel): Boolean {
        val dir = runCatching { modelDir(model.id) }.getOrNull() ?: return false
        return model.files.all { file ->
            val target = runCatching {
                if (!file.sharedKey.isNullOrBlank()) companionFile(file.sharedKey)
                else safeChild(dir, file.path)
            }.getOrNull() ?: return@all false
            target.isFile && (file.size <= 0L || target.length() == file.size)
        }
    }

    private fun builtInCompanionFallback(sharedKey: String): File? = when (sharedKey) {
        "umt5-xxl-encoder-Q3_K_S.gguf" -> File(modelsBase, "wan22_turbo/umt5-xxl-encoder-Q3_K_S.gguf")
        "taew2_2.safetensors" -> File(modelsBase, "wan22_turbo/taew2_2.safetensors")
        else -> null
    }

    suspend fun downloadOption(
        option: HfRepoOption,
        hubSettings: HubSettings,
        onProgress: (file: String, downloaded: Long, total: Long) -> Unit
    ): InstalledHubModel = withContext(Dispatchers.IO) {
        require(option.splitWarning == null) { option.splitWarning ?: "暂不支持该文件" }
        require(option.files.isNotEmpty()) { "没有可下载的文件" }
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录" }
        check(companionsDir.isDirectory || companionsDir.mkdirs()) { "无法创建共享组件目录" }
        val staging = File(root, ".${option.id}.downloading")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建暂存目录" }
        val opContext = coroutineContext

        onProgress("检查本地已有文件…", 0L, option.totalBytes.coerceAtLeast(1L))
        val remaining = option.files.sumOf { file ->
            val existingCompanion = file.sharedCompanionKey?.let { key ->
                companionFile(key).takeIf { it.isFile && (file.size <= 0L || it.length() == file.size) }
            }
            if (existingCompanion != null) return@sumOf 0L
            val target = if (file.sharedCompanionKey != null) safeChild(companionsDir, file.sharedCompanionKey)
            else safeChild(staging, file.path)
            val part = File(target.path + ".part")
            when {
                target.isFile && (file.size <= 0L || target.length() == file.size) -> 0L
                part.isFile && (file.size <= 0L || part.length() <= file.size) -> (file.size - part.length()).coerceAtLeast(0L)
                else -> file.size.coerceAtLeast(0L)
            }
        }
        val available = StatFs(staging.path).availableBytes
        check(available >= remaining + RESERVE_BYTES) {
            "空间不足，请至少预留 ${String.format(java.util.Locale.US, "%.2f", (remaining + RESERVE_BYTES) / 1_000_000_000.0)} GB 可用空间"
        }

        val total = option.totalBytes.coerceAtLeast(1L)
        var completed = 0L
        val installedFiles = mutableListOf<InstalledHubFile>()

        for (file in option.files) {
            opContext.ensureActive()
            val sharedKey = file.sharedCompanionKey
            val expectedSize = file.size.takeIf { it > 0L }

            if (sharedKey != null) {
                val reused = builtInCompanionFallback(sharedKey)?.takeIf {
                    it.isFile && (expectedSize == null || it.length() == expectedSize)
                }
                val companionTarget = safeChild(companionsDir, sharedKey)
                val readyFile = when {
                    reused != null -> reused
                    companionTarget.isFile && (expectedSize == null || companionTarget.length() == expectedSize) -> companionTarget
                    else -> null
                }
                if (readyFile != null) {
                    val actualSize = readyFile.length()
                    installedFiles += InstalledHubFile(file.path, actualSize, file.sha256, sharedKey)
                    completed += actualSize
                    onProgress("复用组件 ${sharedKey.substringAfterLast('/')}", completed, total.coerceAtLeast(completed))
                    continue
                }
                companionTarget.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建组件子目录" } }
                val part = File(companionTarget.path + ".part")
                val srcRepo = file.sourceRepo ?: option.repo
                val srcRev = file.sourceRevision ?: option.revision
                val url = HubUrlRewriter.resolveFileUrl(srcRepo, srcRev, file.path, hubSettings)
                val finalBytes = ResumableHttpDownloader.download(
                    url = url,
                    part = part,
                    expectedSize = expectedSize,
                    operation = opContext,
                    hubSettings = hubSettings
                ) { done, _ ->
                    onProgress(sharedKey.substringAfterLast('/'), completed + done, total)
                }
                if (file.sha256 != null) {
                    onProgress("校验 ${sharedKey.substringAfterLast('/')}", completed + finalBytes, total)
                    val actualHash = ResumableHttpDownloader.sha256(part, opContext)
                    if (actualHash != file.sha256) {
                        part.delete()
                        error("${sharedKey} SHA-256 校验失败，请重试")
                    }
                }
                if (companionTarget.exists()) check(companionTarget.delete()) { "无法覆盖旧组件" }
                check(part.renameTo(companionTarget)) { "无法保存 $sharedKey" }
                val actualSize = companionTarget.length()
                installedFiles += InstalledHubFile(file.path, actualSize, file.sha256, sharedKey)
                completed += actualSize
                onProgress(sharedKey.substringAfterLast('/'), completed, total.coerceAtLeast(completed))
                continue
            }

            val target = safeChild(staging, file.path)
            target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建子目录" } }
            val alreadyGood = target.isFile && (expectedSize == null || target.length() == expectedSize) &&
                (file.sha256 == null || ResumableHttpDownloader.sha256(target, opContext) == file.sha256)
            if (!alreadyGood) {
                val part = File(target.path + ".part")
                val srcRepo = file.sourceRepo ?: option.repo
                val srcRev = file.sourceRevision ?: option.revision
                val url = HubUrlRewriter.resolveFileUrl(srcRepo, srcRev, file.path, hubSettings)
                val finalBytes = ResumableHttpDownloader.download(
                    url = url,
                    part = part,
                    expectedSize = expectedSize,
                    operation = opContext,
                    hubSettings = hubSettings
                ) { done, _ ->
                    onProgress(file.path.substringAfterLast('/'), completed + done, total)
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
            onProgress(file.path.substringAfterLast('/'), completed, total.coerceAtLeast(completed))
        }

        val displayName = when (option.type) {
            HubModelType.MnnChat -> option.repo.substringAfter('/')
            HubModelType.MusicAudio -> option.label.take(80)
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
            installedAt = System.currentTimeMillis(),
            metadata = option.metadata
        )
        commitStagedModel(staging, record)
    }

    internal fun commitStagedModel(staging: File, record: InstalledHubModel): InstalledHubModel {
        val targetDir = modelDir(record.id)
        val backupDir = File(root, ".${record.id}.backup")
        if (backupDir.exists()) backupDir.deleteRecursively()
        if (targetDir.exists()) check(targetDir.renameTo(backupDir)) { "无法备份旧模型" }
        if (!staging.renameTo(targetDir)) {
            if (backupDir.exists()) backupDir.renameTo(targetDir)
            error("无法启用已导入/下载的模型")
        }
        backupDir.deleteRecursively()
        synchronized(lock) {
            val next = readRegistry().filterNot { it.id == record.id } + record
            writeRegistry(next)
            _models.value = next.filter { isPresent(it) }
        }
        return record
    }

    fun delete(id: String) {
        synchronized(lock) {
            val existing = readRegistry()
            val removed = existing.firstOrNull { it.id == id }
            val dir = modelDir(id)
            if (dir.exists()) check(dir.deleteRecursively()) { "无法删除模型文件" }
            File(root, ".$id.downloading").deleteRecursively()
            val next = existing.filterNot { it.id == id }
            writeRegistry(next)
            // Clean up any shared companion files no longer referenced by remaining hub models.
            if (removed != null) {
                val stillUsed = next.flatMap { m -> m.files.mapNotNull { it.sharedKey } }.toSet()
                removed.files.mapNotNull { it.sharedKey }.filterNot { it in stillUsed }.forEach { orphan ->
                    runCatching { safeChild(companionsDir, orphan).delete() }
                }
            }
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
