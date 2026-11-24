package com.tiggy.qwenimage.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

enum class LocalImportKind { SingleFile, MnnFolder, Companion }

data class LocalImportPreview(
    val uri: Uri,
    val kind: LocalImportKind,
    val fileName: String,
    val totalBytes: Long,
    val fileCount: Int,
    val suggestedType: HubModelType,
    val displayName: String,
    val quantTag: String?,
    val archHint: String?,
    val metadata: Map<String, String> = emptyMap(),
    val companionSharedKey: String? = null,
    val cachedCompanions: List<String> = emptyList(),
    val missingCompanions: List<String> = emptyList(),
    val mnnEntryFile: String? = null
)

/**
 * Zero-permission Storage Access Framework (SAF) importer for local GGUF/safetensors weights,
 * MNN chat model folders, and shared companion artifacts.
 */
object LocalModelImporter {
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val RESERVE_BYTES = 512L * 1024 * 1024
    private val ALLOWED_SINGLE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt")

    suspend fun inspectSingleFile(
        context: Context,
        uri: Uri,
        repo: InstalledHubModelsRepository = InstalledHubModelsRepository.get(context)
    ): LocalImportPreview = withContext(Dispatchers.IO) {
        val (rawName, size) = queryDocumentMeta(context, uri)
        val fileName = sanitizeFileName(rawName)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(ext in ALLOWED_SINGLE_EXTENSIONS) {
            "仅支持导入 .gguf、.safetensors 或 .ckpt 模型文件（当前：$fileName）"
        }

        val header = if (ext == "gguf") {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                GgufHeaderParser.parse(stream)
            }
        } else null
        if (ext == "gguf" && header == null) {
            throw IOException("文件不是有效的 GGUF v2/v3 模型：$fileName")
        }

        val classified = GgufHeaderParser.classifyFile(fileName, header)
        val cached = classified.expectedCompanionKeys.filter { key ->
            runCatching { repo.companionFile(key).isFile }.getOrDefault(false)
        }
        val missing = classified.expectedCompanionKeys.filterNot { it in cached }
        val kind = if (classified.companionSharedKey != null) LocalImportKind.Companion else LocalImportKind.SingleFile

        LocalImportPreview(
            uri = uri,
            kind = kind,
            fileName = fileName,
            totalBytes = size.coerceAtLeast(0L),
            fileCount = 1,
            suggestedType = classified.suggestedType,
            displayName = classified.displayName,
            quantTag = classified.quantTag,
            archHint = classified.archHint,
            metadata = classified.metadata,
            companionSharedKey = classified.companionSharedKey,
            cachedCompanions = cached,
            missingCompanions = missing
        )
    }

    suspend fun inspectFolder(
        context: Context,
        treeUri: Uri
    ): LocalImportPreview = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法读取所选文件夹")
        val folderName = sanitizeFileName(rootDoc.name ?: "local-mnn-model")
        val entries = collectFolderFiles(rootDoc)
        require(entries.isNotEmpty()) { "所选文件夹为空" }

        val entryNames = entries.map { it.first }
        val mnnConfig = entryNames.firstOrNull { it.equals("llm_config.json", ignoreCase = true) }
            ?: entryNames.firstOrNull { it.equals("config.json", ignoreCase = true) }
        val hasMnnWeights = entryNames.any { it.endsWith(".mnn", ignoreCase = true) }
        require(mnnConfig != null && hasMnnWeights) {
            "未在文件夹中检测到 MNN 对话模型（需包含 llm_config.json/config.json 与 .mnn 权重文件）"
        }

        val totalBytes = entries.sumOf { it.second.length().coerceAtLeast(0L) }
        LocalImportPreview(
            uri = treeUri,
            kind = LocalImportKind.MnnFolder,
            fileName = folderName,
            totalBytes = totalBytes,
            fileCount = entries.size,
            suggestedType = HubModelType.MnnChat,
            displayName = folderName.take(80),
            quantTag = GgufHeaderParser.extractQuantTag(folderName),
            archHint = "mnn",
            mnnEntryFile = mnnConfig
        )
    }

    suspend fun executeImport(
        context: Context,
        preview: LocalImportPreview,
        selectedType: HubModelType,
        importAsCompanion: Boolean,
        repo: InstalledHubModelsRepository = InstalledHubModelsRepository.get(context),
        onProgress: (file: String, copied: Long, total: Long) -> Unit
    ): InstalledHubModel? = withContext(Dispatchers.IO) {
        val opContext = coroutineContext
        check(repo.root.isDirectory || repo.root.mkdirs()) { "无法创建模型目录" }
        check(repo.companionsDir.isDirectory || repo.companionsDir.mkdirs()) { "无法创建共享组件目录" }

        val expectedTotal = preview.totalBytes.coerceAtLeast(1L)
        val available = StatFs(repo.root.path).availableBytes
        check(available >= preview.totalBytes + RESERVE_BYTES) {
            "存储空间不足，请至少预留 ${String.format(Locale.US, "%.2f", (preview.totalBytes + RESERVE_BYTES) / 1_000_000_000.0)} GB"
        }

        if (importAsCompanion && !preview.companionSharedKey.isNullOrBlank()) {
            val sharedKey = preview.companionSharedKey
            require(ModelManifest.validPath(sharedKey)) { "非法共享组件名：$sharedKey" }
            val target = File(repo.companionsDir, sharedKey)
            target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建组件目录" } }
            val part = File(target.path + ".importing")
            part.delete()
            try {
                copyUriToFile(context, preview.uri, part, opContext) { done ->
                    onProgress(sharedKey, done, expectedTotal.coerceAtLeast(done))
                }
                if (target.exists()) check(target.delete()) { "无法覆盖已有共享组件" }
                check(part.renameTo(target)) { "无法保存共享组件 $sharedKey" }
                repo.refresh()
                return@withContext null
            } catch (error: Throwable) {
                part.delete()
                throw error
            }
        }

        if (preview.kind == LocalImportKind.MnnFolder) {
            val rootDoc = DocumentFile.fromTreeUri(context, preview.uri)
                ?: throw IOException("无法访问所选文件夹")
            val entries = collectFolderFiles(rootDoc)
            val id = makeLocalModelId("mnn", preview.fileName)
            val staging = File(repo.root, ".$id.importing")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "无法创建临时导入目录" }
            val installedFiles = mutableListOf<InstalledHubFile>()
            var copiedSoFar = 0L
            try {
                for ((relPath, doc) in entries) {
                    opContext.ensureActive()
                    require(ModelManifest.validPath(relPath)) { "跳过非法路径：$relPath" }
                    val dest = File(staging, relPath)
                    dest.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建子目录" } }
                    val written = copyUriToFile(context, doc.uri, dest, opContext) { done ->
                        onProgress(relPath, copiedSoFar + done, expectedTotal.coerceAtLeast(copiedSoFar + done))
                    }
                    copiedSoFar += written
                    installedFiles += InstalledHubFile(path = relPath, size = written, sha256 = null)
                }
                val record = InstalledHubModel(
                    id = id,
                    name = preview.displayName.take(80),
                    repo = LOCAL_REPO_TAG,
                    revision = "local",
                    type = HubModelType.MnnChat,
                    entryFile = preview.mnnEntryFile ?: "llm_config.json",
                    files = installedFiles,
                    totalBytes = installedFiles.sumOf { it.size },
                    quantTag = preview.quantTag,
                    installedAt = System.currentTimeMillis()
                )
                return@withContext repo.commitStagedModel(staging, record)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

        // Single model file (.gguf / .safetensors / .ckpt)
        val id = makeLocalModelId(selectedType.wire, preview.fileName)
        val staging = File(repo.root, ".$id.importing")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "无法创建临时导入目录" }
        try {
            val dest = File(staging, preview.fileName)
            val written = copyUriToFile(context, preview.uri, dest, opContext) { done ->
                onProgress(preview.fileName, done, expectedTotal.coerceAtLeast(done))
            }
            val effectiveMeta = if (selectedType == preview.suggestedType) preview.metadata else emptyMap()
            val files = buildList {
                add(InstalledHubFile(path = preview.fileName, size = written, sha256 = null))
                // Link already-cached shared companions when metadata matches; missing ones stay in metadata
                // so the engine reports them cleanly without hiding the imported model from the list.
                if (effectiveMeta.isNotEmpty()) {
                    preview.cachedCompanions.forEach { key ->
                        val f = runCatching { repo.companionFile(key) }.getOrNull()
                        if (f != null && f.isFile) {
                            add(InstalledHubFile(path = key, size = f.length(), sha256 = null, sharedKey = key))
                        }
                    }
                }
            }
            val record = InstalledHubModel(
                id = id,
                name = preview.displayName.take(80),
                repo = LOCAL_REPO_TAG,
                revision = "local",
                type = selectedType,
                entryFile = preview.fileName,
                files = files,
                totalBytes = written,
                quantTag = preview.quantTag,
                installedAt = System.currentTimeMillis(),
                metadata = effectiveMeta
            )
            return@withContext repo.commitStagedModel(staging, record)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    internal fun makeLocalModelId(prefix: String, fileName: String): String {
        val slug = fileName
            .substringBeforeLast('.')
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-', '.')
            .ifBlank { "model" }
            .take(80)
        return "local-$prefix-$slug".take(116)
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.replace(Regex("[^A-Za-z0-9_.+()-]"), "_").ifBlank { "imported_model.gguf" }
    }

    private fun queryDocumentMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        if (name.isNullOrBlank()) {
            name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "model.gguf"
        }
        if (size <= 0L) {
            size = DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
        }
        return name!! to size
    }

    private fun collectFolderFiles(
        dir: DocumentFile,
        prefix: String = "",
        depth: Int = 0
    ): List<Pair<String, DocumentFile>> {
        if (depth > 3) return emptyList()
        val out = mutableListOf<Pair<String, DocumentFile>>()
        for (child in dir.listFiles()) {
            val childName = child.name?.takeIf { it.isNotBlank() && !it.startsWith(".") } ?: continue
            val rel = if (prefix.isEmpty()) childName else "$prefix/$childName"
            if (child.isDirectory) {
                out += collectFolderFiles(child, rel, depth + 1)
            } else if (child.isFile) {
                out += rel to child
            }
        }
        return out
    }

    private suspend fun copyUriToFile(
        context: Context,
        uri: Uri,
        dest: File,
        opContext: kotlin.coroutines.CoroutineContext,
        onBytes: (Long) -> Unit
    ): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开所选文件，请重试")
        var total = 0L
        var lastEmit = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        input.use { src ->
            FileOutputStream(dest).use { dst ->
                while (true) {
                    opContext.ensureActive()
                    val n = src.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    dst.write(buffer, 0, n)
                    total += n
                    if (total - lastEmit >= 2L * 1024 * 1024) {
                        lastEmit = total
                        onBytes(total)
                    }
                }
                dst.fd.sync()
            }
        }
        onBytes(total)
        return total
    }

    const val LOCAL_REPO_TAG = "local"
}
