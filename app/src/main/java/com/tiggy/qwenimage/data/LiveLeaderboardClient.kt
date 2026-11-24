package com.tiggy.qwenimage.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class LiveBoardSource(val displayName: String, val homeUrl: String) {
    ArtificialAnalysis("Artificial Analysis", "https://artificialanalysis.ai"),
    JevDecisionIndex("Decision Index (Jev)", "https://huggingface.co/spaces/multimodalart/jev-decision-index"),
    HfOpenLlm("HF Open LLM (≤10B)", "https://huggingface.co/spaces/open-llm-leaderboard/open_llm_leaderboard")
}

enum class LiveChatTier(val pathSuffix: String) {
    Tiny("/models/open-source/tiny"),
    Small("/models/open-source/small"),
    AllOpen("/models/open-source")
}

enum class LiveMusicTrack(val pathSuffix: String) {
    Vocals("/music/leaderboard/vocals"),
    Instrumental("/music/leaderboard/instrumental")
}

data class LiveLeaderboardEntry(
    val rank: Int,
    val name: String,
    val creator: String,
    val primaryMetricLabel: String,
    val secondaryMetricLabel: String = "",
    val openWeights: Boolean = false,
    val paramsBillions: Double? = null,
    val estimatedBytes: Long? = null,
    val huggingFaceRepo: String? = null,
    val searchQuery: String? = null,
    val searchCategory: HubSearchCategory? = null,
    val upstreamUrl: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rank", rank)
        put("name", name)
        put("creator", creator)
        put("primary", primaryMetricLabel)
        put("secondary", secondaryMetricLabel)
        put("openWeights", openWeights)
        if (paramsBillions != null) put("paramsB", paramsBillions)
        if (estimatedBytes != null) put("estBytes", estimatedBytes)
        if (!huggingFaceRepo.isNullOrBlank()) put("hfRepo", huggingFaceRepo)
        if (!searchQuery.isNullOrBlank()) put("searchQuery", searchQuery)
        if (searchCategory != null) put("searchCat", searchCategory.name)
        if (!upstreamUrl.isNullOrBlank()) put("url", upstreamUrl)
    }

    companion object {
        fun fromJson(o: JSONObject): LiveLeaderboardEntry = LiveLeaderboardEntry(
            rank = o.optInt("rank", 0),
            name = o.optString("name"),
            creator = o.optString("creator"),
            primaryMetricLabel = o.optString("primary"),
            secondaryMetricLabel = o.optString("secondary"),
            openWeights = o.optBoolean("openWeights", false),
            paramsBillions = if (o.has("paramsB")) o.optDouble("paramsB").takeIf { it.isFinite() } else null,
            estimatedBytes = if (o.has("estBytes")) o.optLong("estBytes").takeIf { it > 0L } else null,
            huggingFaceRepo = o.optString("hfRepo").takeIf { it.isNotBlank() },
            searchQuery = o.optString("searchQuery").takeIf { it.isNotBlank() },
            searchCategory = o.optString("searchCat").takeIf { it.isNotBlank() }?.let {
                runCatching { HubSearchCategory.valueOf(it) }.getOrNull()
            },
            upstreamUrl = o.optString("url").takeIf { it.isNotBlank() }
        )
    }
}

data class LiveLeaderboardSnapshot(
    val boardId: String,
    val source: LiveBoardSource,
    val modality: BenchmarkModality,
    val sourceUrl: String,
    val fetchedAtMs: Long,
    val entries: List<LiveLeaderboardEntry>,
    val fromCache: Boolean = false,
    val stale: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("boardId", boardId)
        put("source", source.name)
        put("modality", modality.name)
        put("sourceUrl", sourceUrl)
        put("fetchedAtMs", fetchedAtMs)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): LiveLeaderboardSnapshot? {
            if (o.optInt("schemaVersion") != 1) return null
            val source = runCatching { LiveBoardSource.valueOf(o.getString("source")) }.getOrNull() ?: return null
            val modality = runCatching { BenchmarkModality.valueOf(o.getString("modality")) }.getOrNull() ?: return null
            val arr = o.optJSONArray("entries") ?: return null
            val entries = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(LiveLeaderboardEntry::fromJson)
            }
            if (entries.isEmpty()) return null
            return LiveLeaderboardSnapshot(
                boardId = o.getString("boardId"),
                source = source,
                modality = modality,
                sourceUrl = o.optString("sourceUrl", source.homeUrl),
                fetchedAtMs = o.optLong("fetchedAtMs", 0L),
                entries = entries,
                fromCache = true
            )
        }
    }
}

/**
 * Fetches and caches live public model leaderboards from:
 * - Artificial Analysis (https://artificialanalysis.ai) across all 5 modalities
 * - Jev Decision Index (https://huggingface.co/spaces/multimodalart/jev-decision-index, mirror-aware)
 * - Hugging Face Open LLM Leaderboard <=10B edge tier (datasets-server.huggingface.co)
 *
 * Uses only public unauthenticated endpoints and preserves explicit upstream source links.
 */
object LiveLeaderboardClient {
    private const val AA_BASE = "https://artificialanalysis.ai"
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131.0"

    fun boardId(
        source: LiveBoardSource,
        modality: BenchmarkModality,
        chatTier: LiveChatTier = LiveChatTier.Tiny,
        musicTrack: LiveMusicTrack = LiveMusicTrack.Vocals
    ): String = when (source) {
        LiveBoardSource.ArtificialAnalysis -> when (modality) {
            BenchmarkModality.Chat -> "aa_chat_${chatTier.name.lowercase(Locale.ROOT)}"
            BenchmarkModality.Decision -> "aa_decision"
            BenchmarkModality.Image -> "aa_image_t2i"
            BenchmarkModality.Video -> "aa_video_t2v"
            BenchmarkModality.Music -> "aa_music_${musicTrack.name.lowercase(Locale.ROOT)}"
        }
        LiveBoardSource.JevDecisionIndex -> "jev_decision_v04"
        LiveBoardSource.HfOpenLlm -> "hf_open_llm_10b"
    }

    fun sourcePageUrl(
        source: LiveBoardSource,
        modality: BenchmarkModality,
        chatTier: LiveChatTier = LiveChatTier.Tiny,
        musicTrack: LiveMusicTrack = LiveMusicTrack.Vocals
    ): String = when (source) {
        LiveBoardSource.ArtificialAnalysis -> when (modality) {
            BenchmarkModality.Chat -> "$AA_BASE${chatTier.pathSuffix}"
            BenchmarkModality.Decision -> "$AA_BASE/evaluations/artificial-analysis-decision-index"
            BenchmarkModality.Image -> "$AA_BASE/image/leaderboard/text-to-image"
            BenchmarkModality.Video -> "$AA_BASE/video/leaderboard/text-to-video"
            BenchmarkModality.Music -> "$AA_BASE${musicTrack.pathSuffix}"
        }
        LiveBoardSource.JevDecisionIndex -> "https://huggingface.co/spaces/multimodalart/jev-decision-index"
        LiveBoardSource.HfOpenLlm -> "https://huggingface.co/spaces/open-llm-leaderboard/open_llm_leaderboard"
    }

    fun readCached(context: Context, boardId: String): LiveLeaderboardSnapshot? {
        val file = cacheFile(context, boardId)
        if (!file.isFile) return null
        return runCatching {
            LiveLeaderboardSnapshot.fromJson(JSONObject(file.readText()))
        }.getOrNull()
    }

    suspend fun loadBoard(
        context: Context,
        source: LiveBoardSource,
        modality: BenchmarkModality,
        hubSettings: HubSettings,
        chatTier: LiveChatTier = LiveChatTier.Tiny,
        musicTrack: LiveMusicTrack = LiveMusicTrack.Vocals,
        forceRefresh: Boolean = false
    ): Result<LiveLeaderboardSnapshot> = withContext(Dispatchers.IO) {
        val id = boardId(source, modality, chatTier, musicTrack)
        val cached = readCached(context, id)
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.fetchedAtMs in 0..CACHE_TTL_MS) {
            return@withContext Result.success(cached.copy(fromCache = true, stale = false))
        }
        val fetched = runCatching {
            val pageUrl = sourcePageUrl(source, modality, chatTier, musicTrack)
            val entries = when (source) {
                LiveBoardSource.ArtificialAnalysis -> when (modality) {
                    BenchmarkModality.Chat -> parseAaOpenSourceModels(httpGet(pageUrl), chatTier)
                    BenchmarkModality.Decision -> parseAaDecisionIndex(httpGet(pageUrl))
                    BenchmarkModality.Image -> parseAaArenaLeaderboard(httpGet(pageUrl), BenchmarkModality.Image)
                    BenchmarkModality.Video -> parseAaArenaLeaderboard(httpGet(pageUrl), BenchmarkModality.Video)
                    BenchmarkModality.Music -> parseAaArenaLeaderboard(httpGet(pageUrl), BenchmarkModality.Music)
                }
                LiveBoardSource.JevDecisionIndex -> {
                    val base = hubSettings.effectiveBaseUrl
                    val jsonUrl = "$base/spaces/multimodalart/jev-decision-index/resolve/main/data/v03.json"
                    parseJevDecisionIndex(httpGet(jsonUrl))
                }
                LiveBoardSource.HfOpenLlm -> {
                    val apiUrl = "https://datasets-server.huggingface.co/filter?" +
                        "dataset=open-llm-leaderboard/contents&config=default&split=train" +
                        "&where=%22%23Params+(B)%22+%3C%3D+10+AND+%22%23Params+(B)%22+%3E+0.3" +
                        "&orderby=%22Average+%E2%AC%86%EF%B8%8F%22+DESC&offset=0&length=40"
                    parseHfOpenLlmLeaderboard(httpGet(apiUrl))
                }
            }
            if (entries.isEmpty()) throw IOException("No leaderboard entries parsed")
            val snap = LiveLeaderboardSnapshot(
                boardId = id,
                source = source,
                modality = modality,
                sourceUrl = pageUrl,
                fetchedAtMs = System.currentTimeMillis(),
                entries = entries,
                fromCache = false,
                stale = false
            )
            writeCache(context, snap)
            snap
        }
        if (fetched.isSuccess) {
            fetched
        } else if (cached != null) {
            Result.success(cached.copy(fromCache = true, stale = true))
        } else {
            fetched
        }
    }

    private fun cacheFile(context: Context, boardId: String): File {
        val safe = boardId.replace(Regex("[^a-z0-9_-]"), "_")
        val dir = File(context.cacheDir, "live_leaderboards")
        return File(dir, "$safe.json")
    }

    private fun writeCache(context: Context, snapshot: LiveLeaderboardSnapshot) {
        runCatching {
            val target = cacheFile(context, snapshot.boardId)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(snapshot.toJson().toString())
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private fun httpGet(urlString: String): String {
        var lastError: IOException? = null
        for (attempt in 1..3) {
            try {
                return httpGetOnce(urlString)
            } catch (e: IOException) {
                lastError = e
                if (attempt < 3) {
                    runCatching { Thread.sleep(450L * attempt) }
                }
            }
        }
        throw lastError ?: IOException("Network request failed")
    }

    private fun httpGetOnce(urlString: String): String {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 25_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Connection", "close")
            }
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect missing location")
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_RESPONSE_BYTES) throw IOException("Response too large")
                        out.write(buf, 0, n)
                    }
                }
                return out.toString("UTF-8")
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /** Unescapes Next.js App Router RSC payload strings (`self.__next_f.push([1,"..."])`). */
    internal fun extractNextRscText(html: String): String {
        val sb = StringBuilder(html.length / 2)
        val needle = "self.__next_f.push([1,\""
        var pos = 0
        while (true) {
            val start = html.indexOf(needle, pos)
            if (start < 0) break
            var i = start + needle.length
            val rawStart = i
            var escaped = false
            while (i < html.length) {
                val c = html[i]
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    break
                }
                i++
            }
            if (i >= html.length) break
            val rawLiteral = html.substring(rawStart, i)
            runCatching {
                val decoded = JSONArray("[\"$rawLiteral\"]").getString(0)
                sb.append(decoded)
            }
            pos = i + 1
        }
        return sb.toString()
    }

    /** Extracts a balanced JSON array `[...]` starting at [openBracketIndex]. */
    internal fun extractBalancedJsonArray(text: String, openBracketIndex: Int): String? {
        if (openBracketIndex !in text.indices || text[openBracketIndex] != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openBracketIndex until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(openBracketIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** Extracts a balanced JSON object `{...}` starting at [openBraceIndex]. */
    internal fun extractBalancedJsonObject(text: String, openBraceIndex: Int): String? {
        if (openBraceIndex !in text.indices || text[openBraceIndex] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openBraceIndex until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(openBraceIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** Parses Artificial Analysis Open-Source LLM pages (`/models/open-source[/tiny|/small]`). */
    internal fun parseAaOpenSourceModels(html: String, tier: LiveChatTier): List<LiveLeaderboardEntry> {
        val rsc = extractNextRscText(html).ifBlank { html }
        val marker = "\"initialModels\":["
        val idx = rsc.indexOf(marker)
        if (idx < 0) return emptyList()
        val arrayStr = extractBalancedJsonArray(rsc, idx + marker.length - 1) ?: return emptyList()
        val array = runCatching { JSONArray(arrayStr) }.getOrNull() ?: return emptyList()

        data class RawLlm(
            val slug: String,
            val name: String,
            val creator: String,
            val paramsB: Double?,
            val activeB: Double?,
            val ctxTokens: Long,
            val index: Double?,
            val score: Double?,
            val reasoning: Boolean,
            val releaseDate: String,
            val weightsUrl: String?
        )

        val seen = HashSet<String>()
        val items = mutableListOf<RawLlm>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val slug = obj.optString("slug").ifBlank { obj.optString("id") }
            val name = obj.optString("shortName").ifBlank { obj.optString("name") }
            if (name.isBlank() || !seen.add(slug.ifBlank { name })) continue
            val params = if (obj.has("parameters") && !obj.isNull("parameters")) obj.optDouble("parameters").takeIf { it.isFinite() && it > 0.0 } else null
            val active = if (obj.has("inferenceParametersActiveBillions") && !obj.isNull("inferenceParametersActiveBillions")) {
                obj.optDouble("inferenceParametersActiveBillions").takeIf { it.isFinite() && it > 0.0 }
            } else null
            val intel = if (obj.has("intelligenceIndex") && !obj.isNull("intelligenceIndex")) {
                obj.optDouble("intelligenceIndex").takeIf { it.isFinite() }
            } else null
            val rawScore = if (obj.has("score") && !obj.isNull("score")) {
                obj.optDouble("score").takeIf { it.isFinite() }
            } else null
            val creator = obj.optJSONObject("modelCreator")?.optString("name").orEmpty()
                .ifBlank { obj.optJSONObject("creator")?.optString("name").orEmpty() }
            val ctx = obj.optLong("contextWindowTokens", 0L)
            val weightsUrl = if (obj.isNull("modelWeightsSourceUrl")) null
            else obj.optString("modelWeightsSourceUrl").takeIf { it.isNotBlank() }
            items += RawLlm(
                slug = slug,
                name = name,
                creator = creator,
                paramsB = params,
                activeB = active,
                ctxTokens = ctx,
                index = intel,
                score = rawScore,
                reasoning = obj.optBoolean("isReasoning", false),
                releaseDate = obj.optString("releaseDate"),
                weightsUrl = weightsUrl
            )
        }

        // Artificial Analysis ranks open-source models primarily by composite `score`,
        // falling back to `intelligenceIndex` when `score` is absent.
        val sorted = items.sortedWith(
            compareByDescending<RawLlm> { it.score != null }
                .thenByDescending { it.score ?: -1.0 }
                .thenByDescending { it.index != null }
                .thenByDescending { it.index ?: -1.0 }
        ).take(40)

        return sorted.mapIndexed { idxPos, item ->
            val primary = when {
                item.score != null && item.index != null ->
                    String.format(Locale.US, "AA Score %.0f · Intel %.1f", item.score, item.index)
                item.score != null -> String.format(Locale.US, "AA Score %.0f", item.score)
                item.index != null -> String.format(Locale.US, "Intelligence %.1f", item.index)
                else -> "Open Weights"
            }
            val secondary = buildList {
                item.paramsB?.let { p ->
                    val pFmt = if (p >= 10.0) String.format(Locale.US, "%.0fB", p) else String.format(Locale.US, "%.1fB", p)
                    val aFmt = item.activeB?.let { a ->
                        val af = if (a >= 10.0) String.format(Locale.US, "%.0fB", a) else String.format(Locale.US, "%.1fB", a)
                        " ($af active)"
                    }.orEmpty()
                    add(pFmt + aFmt)
                }
                if (item.ctxTokens >= 1024L) {
                    val k = item.ctxTokens / 1024L
                    add(if (k >= 1000L) "${k / 1000}M ctx" else "${k}K ctx")
                }
                if (item.reasoning) add("Reasoning")
            }.joinToString(" · ")
            val matched = ModelBenchmarkCatalog.matchRepoOrName(item.name)
                ?: ModelBenchmarkCatalog.matchRepoOrName(item.slug)
                ?: item.weightsUrl?.let(ModelBenchmarkCatalog::matchRepoOrName)
            val estBytes = item.paramsB?.let { (it * 650_000_000.0).toLong() }
            LiveLeaderboardEntry(
                rank = idxPos + 1,
                name = item.name,
                creator = item.creator,
                primaryMetricLabel = primary,
                secondaryMetricLabel = secondary,
                openWeights = true,
                paramsBillions = item.paramsB,
                estimatedBytes = estBytes,
                huggingFaceRepo = matched?.recommendedRepo,
                searchQuery = cleanSearchQuery(item.name),
                searchCategory = matched?.searchCategory ?: HubSearchCategory.GgufChat,
                upstreamUrl = if (item.slug.isNotBlank()) "$AA_BASE/models/${item.slug}" else "$AA_BASE${tier.pathSuffix}"
            )
        }
    }

    /** Parses Artificial Analysis Decision Index v2.1 (`/evaluations/artificial-analysis-decision-index`). */
    internal fun parseAaDecisionIndex(html: String): List<LiveLeaderboardEntry> {
        val rsc = extractNextRscText(html).ifBlank { html }
        val kindNeedle = "\"kind\":\"decisionModel\""
        val seen = HashSet<String>()

        data class RawDecision(
            val slug: String,
            val name: String,
            val creator: String,
            val indexScore: Double,
            val accuracyScore: Double?,
            val latencyMs: Double?
        )

        val rows = mutableListOf<RawDecision>()
        var searchFrom = 0
        while (true) {
            val hit = rsc.indexOf(kindNeedle, searchFrom)
            if (hit < 0) break
            // Walk back to the enclosing '{' of this model object.
            val objStart = rsc.lastIndexOf('{', hit)
            if (objStart >= 0) {
                val objText = extractBalancedJsonObject(rsc, objStart)
                if (objText != null) {
                    runCatching {
                        val obj = JSONObject(objText)
                        val slug = obj.optString("slug")
                        val name = obj.optString("name")
                        val indexScore = obj.optDouble("indexScore", Double.NaN)
                        if (name.isNotBlank() && indexScore.isFinite() && seen.add(slug.ifBlank { name })) {
                            val acc = obj.optDouble("accuracyIndexScore", Double.NaN).takeIf { it.isFinite() }
                            val ms = obj.optDouble("timePerTaskMs", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                            rows += RawDecision(
                                slug = slug,
                                name = name,
                                creator = obj.optString("creatorName").ifBlank { obj.optString("hostName") },
                                indexScore = indexScore,
                                accuracyScore = acc,
                                latencyMs = ms
                            )
                        }
                    }
                    searchFrom = objStart + objText.length
                    continue
                }
            }
            searchFrom = hit + kindNeedle.length
        }

        return rows.sortedByDescending { it.indexScore }.mapIndexed { idx, row ->
            val lower = (row.slug + " " + row.name).lowercase(Locale.ROOT)
            val isOpen = lower.contains("elephant") || lower.contains("mammoth") || lower.contains("bison") ||
                lower.contains("glide") || lower.contains("onejev") || lower.contains("groundhog")
            val paramsB = parseParamsFromName(row.name)
            val hfRepo = when {
                lower.contains("elephant") -> "mradermacher/elephant-1-GGUF"
                lower.contains("onejev") && lower.contains("9b") -> "mradermacher/OneJev1.6-9B-GGUF"
                lower.contains("onejev") && lower.contains("4b") -> "mradermacher/OneJev1.6-4B-GGUF"
                lower.contains("onejev") && lower.contains("0.8b") -> "mradermacher/OneJev1.6-0.8B-GGUF"
                else -> null
            }
            val secondary = buildList {
                row.accuracyScore?.let { add(String.format(Locale.US, "Accuracy %.1f", it)) }
                row.latencyMs?.let { add(String.format(Locale.US, "%.0f ms", it)) }
            }.joinToString(" · ")
            LiveLeaderboardEntry(
                rank = idx + 1,
                name = row.name,
                creator = row.creator,
                primaryMetricLabel = String.format(Locale.US, "Decision Index %.1f", row.indexScore),
                secondaryMetricLabel = secondary,
                openWeights = isOpen,
                paramsBillions = paramsB,
                estimatedBytes = paramsB?.let { (it * 650_000_000.0).toLong() },
                huggingFaceRepo = hfRepo,
                searchQuery = if (isOpen) cleanSearchQuery(row.name) else null,
                searchCategory = if (isOpen) HubSearchCategory.DecisionGguf else null,
                upstreamUrl = "$AA_BASE/evaluations/artificial-analysis-decision-index"
            )
        }
    }

    /**
     * Parses Artificial Analysis Arena leaderboards (Image T2I, Video T2V, Music Vocals/Instrumental).
     * Stops at the first rank reset so only the primary overall table is parsed (excluding sub-category tables).
     */
    internal fun parseAaArenaLeaderboard(html: String, modality: BenchmarkModality): List<LiveLeaderboardEntry> {
        val rsc = extractNextRscText(html).ifBlank { html }
        val needle = "\"values\":{\"id\":\""
        val seen = HashSet<String>()

        data class RawArena(
            val id: String,
            val name: String,
            val creator: String,
            val rank: Int,
            val elo: Double,
            val appearances: Int,
            val openWeightsUrl: String?,
            val modelUrl: String?
        )

        val rows = mutableListOf<RawArena>()
        var pos = 0
        var prevRank = -1
        while (true) {
            val hit = rsc.indexOf(needle, pos)
            if (hit < 0) break
            val braceStart = hit + "\"values\":".length
            val objText = extractBalancedJsonObject(rsc, braceStart)
            if (objText == null) {
                pos = hit + needle.length
                continue
            }
            val parsed = runCatching { JSONObject(objText) }.getOrNull()
            pos = braceStart + objText.length
            if (parsed == null) continue

            val rank = parsed.optInt("rank", -1)
            val elo = parsed.optDouble("elo", Double.NaN)
            val name = parsed.optString("name")
            if (rank < 0 || !elo.isFinite() || name.isBlank()) continue

            // Stop when the second table (category breakdown) restarts from rank 0 / lower rank.
            if (rows.size >= 5 && rank < prevRank) break
            prevRank = rank

            val id = parsed.optString("id").ifBlank { name }
            if (!seen.add(id)) continue
            val creator = parsed.optJSONObject("creator")?.optString("name").orEmpty()
            val ow = if (parsed.isNull("openWeightsUrl")) null else parsed.optString("openWeightsUrl").takeIf { it.isNotBlank() }
            val url = if (parsed.isNull("url")) null else parsed.optString("url").takeIf { it.isNotBlank() }
            rows += RawArena(
                id = id,
                name = name,
                creator = creator,
                rank = rank,
                elo = elo,
                appearances = parsed.optInt("appearances", 0),
                openWeightsUrl = ow,
                modelUrl = url
            )
        }

        val sorted = rows.sortedWith(compareBy<RawArena> { it.rank }.thenByDescending { it.elo })
        val defaultCategory = when (modality) {
            BenchmarkModality.Image -> HubSearchCategory.SdImage
            BenchmarkModality.Video -> HubSearchCategory.VideoWan
            BenchmarkModality.Music -> HubSearchCategory.MusicAudio
            else -> HubSearchCategory.GgufChat
        }

        return sorted.mapIndexed { idx, row ->
            val isOpen = row.openWeightsUrl != null
            val matched = if (isOpen) {
                ModelBenchmarkCatalog.matchRepoOrName(row.name)
                    ?: row.openWeightsUrl?.let(ModelBenchmarkCatalog::matchRepoOrName)
            } else null
            val paramsB = parseParamsFromName(row.name)
            // Only link directly to a repo sheet when we know a compatible mobile GGUF/SD repo;
            // otherwise let "Search HF" search community quantizations on Hugging Face / HF-Mirror.
            val hfRepo = matched?.recommendedRepo
            val secondary = buildList {
                if (row.appearances > 0) add(String.format(Locale.US, "%,d votes", row.appearances))
            }.joinToString(" · ")
            LiveLeaderboardEntry(
                rank = idx + 1,
                name = row.name,
                creator = row.creator,
                primaryMetricLabel = String.format(Locale.US, "ELO %.0f", row.elo),
                secondaryMetricLabel = secondary,
                openWeights = isOpen,
                paramsBillions = paramsB,
                estimatedBytes = paramsB?.let { (it * 650_000_000.0).toLong() },
                huggingFaceRepo = hfRepo,
                searchQuery = if (isOpen) cleanSearchQuery(row.name) else null,
                searchCategory = if (isOpen) (matched?.searchCategory ?: defaultCategory) else null,
                upstreamUrl = row.modelUrl?.let { if (it.startsWith("http")) it else "$AA_BASE$it" }
                    ?: sourcePageUrl(LiveBoardSource.ArtificialAnalysis, modality)
            )
        }
    }

    /** Parses Jev Decision Index v0.4.1 `data/v03.json` (accessible via Hugging Face or HF-Mirror). */
    internal fun parseJevDecisionIndex(jsonText: String): List<LiveLeaderboardEntry> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("models") ?: return emptyList()

        data class RawJev(
            val engine: String,
            val name: String,
            val score: Double,
            val pubScore: Double?,
            val rankTie: Int
        )

        val rows = mutableListOf<RawJev>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val score = m.optDouble("v", Double.NaN)
            val name = m.optString("name")
            if (!score.isFinite() || name.isBlank()) continue
            rows += RawJev(
                engine = m.optString("engine"),
                name = name,
                score = score,
                pubScore = m.optDouble("pub", Double.NaN).takeIf { it.isFinite() },
                rankTie = m.optInt("rank_tie", 0)
            )
        }

        val sorted = rows.sortedByDescending { it.score }
        // Take top 45 plus any notable phone-sized OneJev/Elephant models further down the 346-model list.
        val topIds = sorted.take(45).map { it.engine }.toMutableSet()
        val extras = sorted.drop(45).filter { r ->
            val low = (r.name + " " + r.engine).lowercase(Locale.ROOT)
            (low.contains("onejev1.6-4b") || low.contains("onejev1.6-0.8b") || low.contains("onejev1.6-9b") || low.contains("elephant")) &&
                topIds.add(r.engine)
        }
        val selected = sorted.take(45) + extras

        return selected.map { row ->
            val fullRank = sorted.indexOf(row) + 1
            val low = (row.name + " " + row.engine).lowercase(Locale.ROOT)
            val hfRepo = when {
                low.contains("onejev1.6-0.8b") -> "mradermacher/OneJev1.6-0.8B-GGUF"
                low.contains("onejev1.6-4b") -> "mradermacher/OneJev1.6-4B-GGUF"
                low.contains("onejev1.6-9b") -> "mradermacher/OneJev1.6-9B-GGUF"
                low.contains("elephant") -> "mradermacher/elephant-1-GGUF"
                else -> null
            }
            val isOpen = hfRepo != null || low.contains("onejev") || low.contains("glide") ||
                low.contains("qwen") || low.contains("gemma") || low.contains("llama") ||
                low.contains("mammoth") || low.contains("bison")
            val paramsB = parseParamsFromName(row.name) ?: parseParamsFromName(row.engine)
            val secondary = buildList {
                row.pubScore?.let { add(String.format(Locale.US, "Public %.1f", it)) }
                paramsB?.let {
                    add(if (it >= 10.0) String.format(Locale.US, "%.0fB", it) else String.format(Locale.US, "%.1fB", it))
                }
            }.joinToString(" · ")
            LiveLeaderboardEntry(
                rank = fullRank,
                name = row.name,
                creator = if (row.rankTie > 0) "Rank #${row.rankTie} (tied)" else "",
                primaryMetricLabel = String.format(Locale.US, "DI v0.4 %.1f", row.score),
                secondaryMetricLabel = secondary,
                openWeights = isOpen,
                paramsBillions = paramsB,
                estimatedBytes = paramsB?.let { (it * 650_000_000.0).toLong() },
                huggingFaceRepo = hfRepo,
                searchQuery = if (isOpen) cleanSearchQuery(row.name) else null,
                searchCategory = if (isOpen) HubSearchCategory.DecisionGguf else null,
                upstreamUrl = "https://huggingface.co/spaces/multimodalart/jev-decision-index"
            )
        }
    }

    /** Parses Hugging Face Open LLM Leaderboard datasets-server JSON (`<=10B` edge models). */
    internal fun parseHfOpenLlmLeaderboard(jsonText: String): List<LiveLeaderboardEntry> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val rows = root.optJSONArray("rows") ?: return emptyList()
        val seen = HashSet<String>()
        val results = mutableListOf<LiveLeaderboardEntry>()
        for (i in 0 until rows.length()) {
            val rowObj = rows.optJSONObject(i)?.optJSONObject("row") ?: continue
            if (!rowObj.optBoolean("Available on the hub", true)) continue
            val fullName = rowObj.optString("fullname").trim()
            val avg = rowObj.optDouble("Average ⬆️", Double.NaN)
            val paramsB = rowObj.optDouble("#Params (B)", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            if (fullName.isBlank() || !avg.isFinite() || !seen.add(fullName.lowercase(Locale.ROOT))) continue
            val author = fullName.substringBefore('/', "")
            val shortName = fullName.substringAfter('/', fullName)
            val license = rowObj.optString("Hub License").takeIf { it.isNotBlank() && it != "?" }
            val secondary = buildList {
                paramsB?.let { add(String.format(Locale.US, "%.1fB", it)) }
                license?.let { add(it) }
            }.joinToString(" · ")
            results += LiveLeaderboardEntry(
                rank = results.size + 1,
                name = shortName,
                creator = author,
                primaryMetricLabel = String.format(Locale.US, "Open LLM Avg %.1f", avg),
                secondaryMetricLabel = secondary,
                openWeights = true,
                paramsBillions = paramsB,
                estimatedBytes = paramsB?.let { (it * 650_000_000.0).toLong() },
                huggingFaceRepo = null,
                searchQuery = cleanSearchQuery(shortName),
                searchCategory = HubSearchCategory.GgufChat,
                upstreamUrl = "https://huggingface.co/$fullName"
            )
            if (results.size >= 30) break
        }
        return results
    }

    private fun parseParamsFromName(name: String): Double? {
        val match = PARAMS_REGEX.find(name) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.1..5000.0 }
    }

    private fun cleanSearchQuery(name: String): String =
        name.replace(Regex("""\s*\([^)]*\)"""), "").replace("[", "").replace("]", "").trim()

    private val PARAMS_REGEX = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*B\b""")
}
