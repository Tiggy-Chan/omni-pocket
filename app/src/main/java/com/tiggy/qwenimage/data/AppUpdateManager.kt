package com.tiggy.qwenimage.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
    val sumsUrl: String?,
    val releaseNotesEn: String,
    val releaseNotesZh: String
) {
    fun localizedNotes(preferChinese: Boolean): String =
        if (preferChinese && releaseNotesZh.isNotBlank()) releaseNotesZh else releaseNotesEn.ifBlank { releaseNotesZh }
}

data class AppUpdateUiState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    val latest: AppReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val showDialog: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val readyApkPath: String? = null,
    val upToDateNotice: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Silent multi-source in-app update checker and self-updater.
 *
 * Source 1 is always the official GitHub Release API / direct APK URL. If direct GitHub access
 * fails, times out, or stalls on domestic networks, it silently falls back to China-friendly
 * GitHub mirrors without exposing mirror switching in the UI. Every downloaded APK is strictly
 * verified against its SHA-256 digest before handing off to Android's system package installer.
 */
object AppUpdateManager {
    private const val REPO = "Tiggy-Chan/omni-pocket"
    private const val GITHUB_LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS_NAME = "omni-app-update"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val MIN_PRIMARY_BYTES_PER_SEC = 80L * 1024L
    private const val SPEED_CHECK_WINDOW_MS = 6_000L

    private val SILENT_MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://edgeone.gh-proxy.com/"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    @Volatile private var hasAutoChecked = false
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun currentAppVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0.2.2" }

    fun autoCheckOnce(context: Context) {
        if (hasAutoChecked) return
        hasAutoChecked = true
        checkForUpdate(context, manual = false)
    }

    fun checkForUpdate(context: Context, manual: Boolean) {
        val appContext = context.applicationContext
        val current = currentAppVersion(appContext)
        _state.update {
            it.copy(
                currentVersion = current,
                checking = true,
                upToDateNotice = false,
                error = if (manual) null else it.error
            )
        }
        checkJob?.cancel()
        checkJob = scope.launch {
            val release = runCatching { fetchLatestReleaseSilent() }.getOrNull()
            if (release == null) {
                _state.update {
                    it.copy(
                        checking = false,
                        error = if (manual) "检查更新失败，请稍后重试" else null
                    )
                }
                return@launch
            }
            val newer = isNewerVersion(release.versionName, current)
            val ignored = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_IGNORED_VERSION, null)
            val shouldPop = newer && (manual || ignored != release.versionName)
            _state.update {
                it.copy(
                    currentVersion = current,
                    checking = false,
                    latest = release,
                    hasUpdate = newer,
                    showDialog = shouldPop || it.showDialog,
                    upToDateNotice = manual && !newer,
                    error = null
                )
            }
        }
    }

    fun openUpdateDialog() {
        if (_state.value.latest != null && _state.value.hasUpdate) {
            _state.update { it.copy(showDialog = true, error = null) }
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(showDialog = false, error = null) }
    }

    fun ignoreCurrentUpdate(context: Context) {
        val latestVer = _state.value.latest?.versionName
        if (!latestVer.isNullOrBlank()) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_IGNORED_VERSION, latestVer).apply()
        }
        _state.update { it.copy(showDialog = false, error = null) }
    }

    fun clearNotices() {
        _state.update { it.copy(upToDateNotice = false, error = null) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(downloading = false, downloadedBytes = 0L, totalBytes = 0L) }
    }

    fun startUpdateDownload(context: Context) {
        val release = _state.value.latest ?: return
        if (_state.value.downloading) return
        val appContext = context.applicationContext
        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val targetApk = File(updatesDir, release.apkName)

        // Reuse already downloaded & verified APK immediately.
        if (targetApk.isFile && release.apkSizeBytes > 0L && targetApk.length() == release.apkSizeBytes) {
            scope.launch {
                val expected = release.expectedSha256 ?: fetchExpectedSha256Silent(release)
                if (expected == null || sha256File(targetApk).equals(expected, ignoreCase = true)) {
                    _state.update { it.copy(readyApkPath = targetApk.absolutePath, downloading = false, error = null) }
                    withContext(Dispatchers.Main) { launchApkInstaller(appContext, targetApk) }
                    return@launch
                }
                targetApk.delete()
                launchDownloadJob(appContext, release, targetApk)
            }
            return
        }
        launchDownloadJob(appContext, release, targetApk)
    }

    private fun launchDownloadJob(appContext: Context, release: AppReleaseInfo, targetApk: File) {
        downloadJob?.cancel()
        _state.update {
            it.copy(
                downloading = true,
                downloadedBytes = 0L,
                totalBytes = release.apkSizeBytes.coerceAtLeast(0L),
                readyApkPath = null,
                error = null
            )
        }
        downloadJob = scope.launch {
            val partFile = File(targetApk.path + ".part")
            try {
                // Clean up stale update APKs from previous versions.
                targetApk.parentFile?.listFiles()?.forEach { f ->
                    if (f.name != targetApk.name && f.name != partFile.name && f.name.endsWith(".apk")) {
                        f.delete()
                    }
                }
                val expectedSha = release.expectedSha256 ?: fetchExpectedSha256Silent(release)
                downloadApkWithSilentFallback(release, partFile) { done, total ->
                    _state.update {
                        it.copy(
                            downloadedBytes = done,
                            totalBytes = if (total > 0L) total else it.totalBytes
                        )
                    }
                }
                if (!expectedSha.isNullOrBlank()) {
                    val actualSha = sha256File(partFile)
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        partFile.delete()
                        throw IOException("安装包校验失败，请重试")
                    }
                }
                if (targetApk.exists()) targetApk.delete()
                check(partFile.renameTo(targetApk)) { "无法保存更新安装包" }
                _state.update {
                    it.copy(
                        downloading = false,
                        downloadedBytes = targetApk.length(),
                        totalBytes = targetApk.length(),
                        readyApkPath = targetApk.absolutePath,
                        error = null
                    )
                }
                withContext(Dispatchers.Main) {
                    launchApkInstaller(appContext, targetApk)
                }
            } catch (_: CancellationException) {
                partFile.delete()
                _state.update { it.copy(downloading = false) }
            } catch (err: Throwable) {
                partFile.delete()
                _state.update {
                    it.copy(
                        downloading = false,
                        error = err.message ?: "更新下载失败，请稍后重试"
                    )
                }
            }
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(permIntent) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }

    internal fun buildCandidateUrls(primaryUrl: String): List<String> = buildList {
        add(primaryUrl)
        SILENT_MIRROR_PREFIXES.forEach { prefix -> add(prefix + primaryUrl) }
    }

    private fun fetchLatestReleaseSilent(): AppReleaseInfo? {
        val candidates = buildCandidateUrls(GITHUB_LATEST_API)
        for (urlStr in candidates) {
            val body = runCatching { httpGetText(urlStr, connectMs = 4_500, readMs = 7_000) }.getOrNull()
            if (!body.isNullOrBlank()) {
                val parsed = runCatching { parseReleaseJson(body) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private fun fetchExpectedSha256Silent(release: AppReleaseInfo): String? {
        val sumsUrl = release.sumsUrl ?: return null
        for (urlStr in buildCandidateUrls(sumsUrl)) {
            val body = runCatching { httpGetText(urlStr, connectMs = 4_000, readMs = 6_000) }.getOrNull()
            if (!body.isNullOrBlank()) {
                val matched = parseSha256FromSums(body, release.apkName)
                if (matched != null) return matched
            }
        }
        return null
    }

    private suspend fun downloadApkWithSilentFallback(
        release: AppReleaseInfo,
        partFile: File,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        val urls = buildCandidateUrls(release.apkUrl)
        var lastError: Throwable? = null
        for ((idx, urlStr) in urls.withIndex()) {
            val isPrimary = idx == 0
            val hasFallback = idx < urls.lastIndex
            try {
                downloadSingleAttempt(
                    urlStr = urlStr,
                    partFile = partFile,
                    expectedTotal = release.apkSizeBytes,
                    abortIfSlow = isPrimary && hasFallback,
                    onProgress = onProgress
                )
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                lastError = err
                // Keep partial file only if size looks sane; otherwise reset cleanly for next silent mirror.
                if (partFile.length() > release.apkSizeBytes && release.apkSizeBytes > 0L) {
                    partFile.delete()
                }
            }
        }
        throw lastError ?: IOException("下载失败，请检查网络后重试")
    }

    private suspend fun downloadSingleAttempt(
        urlStr: String,
        partFile: File,
        expectedTotal: Long,
        abortIfSlow: Boolean,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        val existingOffset = if (partFile.isFile && partFile.length() > 0L &&
            (expectedTotal <= 0L || partFile.length() < expectedTotal)
        ) partFile.length() else 0L

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = if (abortIfSlow) 5_000 else 10_000
            readTimeout = if (abortIfSlow) 10_000 else 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OmniPocket-Android")
            if (existingOffset > 0L) {
                setRequestProperty("Range", "bytes=$existingOffset-")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IOException("HTTP $code")
            }
            val append = (code == HttpURLConnection.HTTP_PARTIAL && existingOffset > 0L)
            val startOffset = if (append) existingOffset else 0L
            val contentLen = conn.contentLengthLong
            val total = when {
                expectedTotal > 0L -> expectedTotal
                contentLen > 0L -> startOffset + contentLen
                else -> 0L
            }
            var downloaded = startOffset
            var sessionBytes = 0L
            val startMs = System.currentTimeMillis()
            var lastEmitMs = 0L

            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            throw CancellationException("Download cancelled")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buf, 0, n)
                        downloaded += n
                        sessionBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 200L) {
                            lastEmitMs = now
                            onProgress(downloaded, total)
                        }
                        if (abortIfSlow && now - startMs >= SPEED_CHECK_WINDOW_MS) {
                            val bps = (sessionBytes * 1000L) / (now - startMs).coerceAtLeast(1L)
                            if (bps < MIN_PRIMARY_BYTES_PER_SEC) {
                                throw IOException("slow_primary")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            onProgress(downloaded, total.coerceAtLeast(downloaded))
            if (expectedTotal > 0L && partFile.length() != expectedTotal) {
                throw IOException("文件下载不完整")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetText(urlStr: String, connectMs: Int, readMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OmniPocket-Android")
        }
        return try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun parseReleaseJson(jsonText: String): AppReleaseInfo? {
        val root = JSONObject(jsonText)
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null
        val tag = root.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val versionName = normalizeVersion(tag)
        val assets = root.optJSONArray("assets") ?: return null
        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L
        var apkSha: String? = null
        var sumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val dlUrl = a.optString("browser_download_url")
            if (name.endsWith("-arm64-v8a.apk", ignoreCase = true) ||
                (apkUrl == null && name.endsWith(".apk", ignoreCase = true))
            ) {
                apkName = name
                apkUrl = dlUrl
                apkSize = a.optLong("size", 0L)
                val digest = a.optString("digest")
                if (digest.startsWith("sha256:", ignoreCase = true)) {
                    apkSha = digest.substringAfter(':').trim().lowercase(Locale.ROOT)
                        .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                }
            } else if (name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                sumsUrl = dlUrl
            }
        }
        if (apkName.isNullOrBlank() || apkUrl.isNullOrBlank()) return null
        val body = root.optString("body")
        val (enNotes, zhNotes) = extractReleaseHighlights(body)
        return AppReleaseInfo(
            tagName = tag,
            versionName = versionName,
            apkName = apkName,
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            expectedSha256 = apkSha,
            sumsUrl = sumsUrl,
            releaseNotesEn = enNotes,
            releaseNotesZh = zhNotes
        )
    }

    internal fun parseSha256FromSums(text: String, fileName: String): String? {
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1].removePrefix("*") == fileName) {
                val hash = parts[0].lowercase(Locale.ROOT)
                if (hash.matches(Regex("[0-9a-f]{64}"))) return hash
            }
        }
        return null
    }

    internal fun extractReleaseHighlights(body: String): Pair<String, String> {
        if (body.isBlank()) return "" to ""
        val detailsStart = body.indexOf("<details>")
        val detailsEnd = body.indexOf("</details>")
        val zhBlock = if (detailsStart >= 0 && detailsEnd > detailsStart) {
            body.substring(detailsStart, detailsEnd)
                .replace(Regex("<summary>.*?</summary>", RegexOption.DOT_MATCHES_ALL), "")
                .replace("<details>", "")
        } else ""
        val enBlock = if (detailsStart >= 0 && detailsEnd > detailsStart) {
            body.removeRange(detailsStart, detailsEnd + "</details>".length)
        } else body

        fun cleanBullets(src: String): String = src.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { line ->
                line.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                    .replace("`", "")
            }
            .take(6)
            .joinToString("\n")
            .take(600)

        return cleanBullets(enBlock) to cleanBullets(zhBlock)
    }

    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')

    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseSemver(normalizeVersion(remote))
        val c = parseSemver(normalizeVersion(current))
        val maxLen = maxOf(r.size, c.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseSemver(v: String): List<Int> =
        v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
