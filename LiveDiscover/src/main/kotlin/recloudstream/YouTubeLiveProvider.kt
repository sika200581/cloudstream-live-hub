package recloudstream

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.Locale

/**
 * YouTube Live Discover — Innertube-backed browse/search for LIVE streams only.
 *
 * Discovery uses youtubei/v1/browse (live trending) and youtubei/v1/search
 * with the live filter. Playback delegates to CloudStream's built-in YouTube
 * extractor via loadExtractor(watchUrl).
 *
 * No VODs, uploads, playlists, or scheduled upcoming lives.
 */
class YouTubeLiveProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Live Discover"
    override val supportedTypes = setOf(TvType.Live)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxItems = 24

    /** Innertube WEB API key (public, used by youtube.com). */
    private val innertubeKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val clientVersion = "2.20250901.00.00"

    /** Live trending / “Live” tab browseId (Invidious trending live). */
    private val liveTrendingBrowseId = "UC4R8DWoMoI7CAwX8_LjQHig"
    /** Params for live shelf on that browseId. */
    private val liveBrowseParams = "EgdsaXZldGFikgEDCKEK"

    /**
     * Search filter: features=live (base64 protobuf).
     * Both URL-encoded and raw forms work; we send raw base64.
     */
    private val liveSearchParams = "EgJAAQ=="

    private val ytHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "*/*",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
    )

    override val mainPage
        get() = mainPageOf(
            "live_trending" to "Live trending",
            "search:gaming" to "Gaming live",
            "search:music" to "Music live",
            "search:news" to "News live",
            "search:sports" to "Sports live",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when {
            request.data == "live_trending" -> fetchLiveTrending(maxItems)
            request.data.startsWith("search:") -> {
                val q = request.data.removePrefix("search:")
                searchLive(q, maxItems)
            }
            else -> emptyList()
        }.map { it.toCard() }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items,
                    isHorizontalImages = isHorizontal
                )
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchLive(q, maxItems).map { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = extractVideoId(url)
            ?: throw RuntimeException("Not a YouTube watch URL")

        val meta = fetchVideoMeta(videoId)
            ?: throw RuntimeException("Could not load video metadata")

        if (!meta.isLive) {
            throw RuntimeException("Not a live stream (VODs and uploads are excluded)")
        }

        val watchUrl = watchUrl(videoId)
        val title = meta.title?.ifBlank { null } ?: "YouTube Live"
        val viewers = meta.viewerCount
        val author = meta.author

        return newLiveStreamLoadResponse(
            title,
            watchUrl,
            videoId
        ) {
            plot = buildString {
                author?.let { append(it) }
                if (viewers != null) {
                    if (isNotEmpty()) append(" · ")
                    append(formatViewers(viewers)).append(" watching")
                }
                meta.description?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append(it.truncate(400))
                }
            }
            posterUrl = meta.thumbnail ?: thumbUrl(videoId)
            backgroundPosterUrl = posterUrl
            tags = listOfNotNull(
                "Live",
                author,
                viewers?.let { formatViewers(it) + " watching" },
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractVideoId(data) ?: data.trim().takeIf {
            it.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
        } ?: return false

        // Mirror official recloudstream YoutubeProvider: loadExtractor on watch URL
        return loadExtractor(
            watchUrl(videoId),
            subtitleCallback,
            callback
        )
    }

    // --- Innertube ---

    private fun clientContext(): Map<String, Any> = mapOf(
        "client" to mapOf(
            "clientName" to "WEB",
            "clientVersion" to clientVersion,
            "hl" to "en",
            "gl" to getRegion(),
        )
    )

    private suspend fun innertubePost(endpoint: String, body: Map<String, Any>): JsonNode? {
        return runCatching {
            app.post(
                "https://www.youtube.com/youtubei/v1/$endpoint?key=$innertubeKey",
                headers = ytHeaders,
                json = body
            ).parsed<JsonNode>()
        }.getOrNull()
    }

    private suspend fun fetchLiveTrending(limit: Int): List<LiveItem> {
        val root = innertubePost(
            "browse",
            mapOf(
                "context" to clientContext(),
                "browseId" to liveTrendingBrowseId,
                "params" to liveBrowseParams,
            )
        ) ?: return emptyList()
        return parseLiveVideos(root).take(limit)
    }

    private suspend fun searchLive(query: String, limit: Int): List<LiveItem> {
        val root = innertubePost(
            "search",
            mapOf(
                "context" to clientContext(),
                "query" to query,
                "params" to liveSearchParams,
            )
        ) ?: return emptyList()
        return parseLiveVideos(root).take(limit)
    }

    /** youtubei/v1/next — works without login; exposes isLive on viewCount renderer. */
    private suspend fun fetchVideoMeta(videoId: String): VideoMeta? {
        val root = innertubePost(
            "next",
            mapOf(
                "context" to clientContext(),
                "videoId" to videoId,
            )
        ) ?: return null

        var title: String? = null
        var author: String? = null
        var isLive = false
        var viewerCount: Long? = null
        var description: String? = null
        var thumbnail: String? = null

        walk(root) { node ->
            if (node.has("videoPrimaryInfoRenderer")) {
                val primary = node.get("videoPrimaryInfoRenderer")
                title = title ?: textFrom(primary.get("title"))
                val vc = primary.get("viewCount")?.get("videoViewCountRenderer")
                if (vc != null) {
                    if (vc.path("isLive").asBoolean(false)) isLive = true
                    viewerCount = viewerCount ?: parseCount(
                        vc.path("originalViewCount").asText(null)
                            ?: textFrom(vc.get("viewCount"))
                    )
                }
            }
            if (node.has("videoOwnerRenderer")) {
                val owner = node.get("videoOwnerRenderer")
                author = author ?: textFrom(owner.get("title"))
                if (thumbnail == null) {
                    thumbnail = bestThumb(owner.get("thumbnail")?.get("thumbnails"))
                }
            }
            if (node.has("videoSecondaryInfoRenderer")) {
                val sec = node.get("videoSecondaryInfoRenderer")
                val at = sec.get("attributedDescription")?.path("content")?.asText(null)
                description = description ?: at ?: textFrom(sec.get("description"))
            }
            // Some layouts expose isLiveNow on badges / overlays elsewhere
            if (node.path("style").asText("").contains("LIVE") &&
                node.path("text").asText("").equals("LIVE", ignoreCase = true)
            ) {
                // weak signal; primary isLive above is authoritative
            }
        }

        // Fallback: if next didn't mark live, reject unless we still have title from a live card path
        if (!isLive) {
            // Double-check overlays / badges anywhere under this response
            val raw = root.toString()
            if (raw.contains("\"isLive\":true") || raw.contains("BADGE_STYLE_TYPE_LIVE_NOW")) {
                isLive = true
            }
        }

        return VideoMeta(
            videoId = videoId,
            title = title,
            author = author,
            isLive = isLive,
            viewerCount = viewerCount,
            description = description,
            thumbnail = thumbnail ?: thumbUrl(videoId),
        )
    }

    private fun parseLiveVideos(root: JsonNode): List<LiveItem> {
        val out = LinkedHashMap<String, LiveItem>()
        walk(root) { node ->
            val vr = when {
                node.has("videoRenderer") -> node.get("videoRenderer")
                node.has("gridVideoRenderer") -> node.get("gridVideoRenderer")
                node.has("compactVideoRenderer") -> node.get("compactVideoRenderer")
                else -> null
            } ?: return@walk

            val id = vr.path("videoId").asText(null)?.ifBlank { null } ?: return@walk
            if (out.containsKey(id)) return@walk

            // Skip upcoming scheduled streams
            if (vr.has("upcomingEventData")) return@walk

            if (!isLiveRenderer(vr)) return@walk

            val title = textFrom(vr.get("title")) ?: return@walk
            val owner = textFrom(vr.get("ownerText"))
                ?: textFrom(vr.get("shortBylineText"))
                ?: textFrom(vr.get("longBylineText"))
            val viewers = parseCount(
                textFrom(vr.get("viewCountText"))
                    ?: textFrom(vr.get("shortViewCountText"))
            )
            val thumb = bestThumb(vr.get("thumbnail")?.get("thumbnails"))
                ?: thumbUrl(id)

            out[id] = LiveItem(
                videoId = id,
                title = title,
                author = owner,
                viewerCount = viewers,
                thumbnail = thumb,
            )
        }
        return out.values.toList()
    }

    private fun isLiveRenderer(vr: JsonNode): Boolean {
        // Prefer positive LIVE signals; drop anything with a fixed length (VOD)
        if (vr.has("lengthText") && !vr.path("lengthText").isNull) {
            // Live items typically omit lengthText
            val len = textFrom(vr.get("lengthText"))
            if (!len.isNullOrBlank() && !len.equals("LIVE", ignoreCase = true)) {
                return false
            }
        }
        if (vr.has("upcomingEventData")) return false

        var live = false
        walk(vr) { n ->
            val style = n.path("style").asText("")
            if (style == "LIVE" || style == "BADGE_STYLE_TYPE_LIVE_NOW") live = true
            if (n.path("label").asText("").equals("LIVE", ignoreCase = true)) live = true
            if (n.path("text").asText("").equals("LIVE", ignoreCase = true) &&
                (style.contains("LIVE") || n.path("icon").path("iconType").asText("") == "LIVE")
            ) live = true
            if (n.path("isLive").asBoolean(false)) live = true
            if (n.path("isLiveNow").asBoolean(false)) live = true
        }
        // Also accept viewCountText containing "watching"
        val views = textFrom(vr.get("viewCountText")) ?: textFrom(vr.get("shortViewCountText"))
        if (views != null && views.contains("watching", ignoreCase = true)) live = true

        return live
    }

    private fun walk(node: JsonNode?, block: (JsonNode) -> Unit) {
        if (node == null || node.isNull) return
        if (node.isObject) {
            block(node)
            val fields = node.fields()
            while (fields.hasNext()) {
                walk(fields.next().value, block)
            }
        } else if (node.isArray) {
            for (child in node) walk(child, block)
        }
    }

    private fun textFrom(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        if (node.has("simpleText")) return node.path("simpleText").asText(null)
        if (node.has("runs") && node.get("runs").isArray) {
            val sb = StringBuilder()
            for (run in node.get("runs")) {
                sb.append(run.path("text").asText(""))
            }
            return sb.toString().ifBlank { null }
        }
        if (node.isTextual) return node.asText().ifBlank { null }
        return null
    }

    private fun bestThumb(thumbs: JsonNode?): String? {
        if (thumbs == null || !thumbs.isArray || thumbs.isEmpty) return null
        return thumbs.last().path("url").asText(null)?.ifBlank { null }
    }

    private fun parseCount(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.lowercase(Locale.US)
            .replace(",", "")
            .replace(" watching now", "")
            .replace(" watching", "")
            .replace(" viewers", "")
            .replace(" views", "")
            .trim()
        val m = Regex("""([\d.]+)\s*([kmb])?""").find(cleaned) ?: return cleaned.toLongOrNull()
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues.getOrNull(2)) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    private fun formatViewers(count: Long): String {
        if (count < 1000) return count.toString()
        val exp = (kotlin.math.ln(count.toDouble()) / kotlin.math.ln(1000.0)).toInt().coerceAtLeast(1)
        val units = arrayOf("K", "M", "B")
        val value = count / Math.pow(1000.0, exp.toDouble())
        val formatted = if (value >= 100) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
        }
        return formatted + units[(exp - 1).coerceIn(0, units.lastIndex)]
    }

    private fun String.truncate(max: Int): String {
        val t = trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun thumbUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private fun extractVideoId(url: String): String? {
        val u = url.trim()
        Regex("""(?:youtube\.com/watch\?(?:[^#]*&)?v=|youtu\.be/|youtube\.com/live/|youtube\.com/embed/)([a-zA-Z0-9_-]{11})""")
            .find(u)?.groupValues?.getOrNull(1)?.let { return it }
        if (u.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return u
        return null
    }

    private fun LiveItem.toCard(): LiveSearchResponse {
        val line1 = buildString {
            append(author?.ifBlank { null } ?: "YouTube")
            viewerCount?.let { append(" · ").append(formatViewers(it)) }
        }
        val name = if (title.isNotBlank()) {
            "$line1\n${title.truncate(70)}"
        } else line1

        return newLiveSearchResponse(
            name,
            watchUrl(videoId),
            TvType.Live,
            fix = false
        ) {
            posterUrl = thumbnail
        }
    }

    data class LiveItem(
        val videoId: String,
        val title: String,
        val author: String?,
        val viewerCount: Long?,
        val thumbnail: String,
    )

    data class VideoMeta(
        val videoId: String,
        val title: String?,
        val author: String?,
        val isLive: Boolean,
        val viewerCount: Long?,
        val description: String?,
        val thumbnail: String?,
    )

    companion object {
        private const val REGION_PREF_KEY = "YouTubeLiveDiscover_region"
        const val DEFAULT_REGION = "US"

        val ALL_REGIONS = listOf(
            "US", "GB", "CA", "AU", "DE", "FR", "JP", "KR", "BR", "IN", "SA", "EG", "MX", "ES", "IT"
        )

        fun regionLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
            "US" -> "United States"
            "GB" -> "United Kingdom"
            "CA" -> "Canada"
            "AU" -> "Australia"
            "DE" -> "Germany"
            "FR" -> "France"
            "JP" -> "Japan"
            "KR" -> "South Korea"
            "BR" -> "Brazil"
            "IN" -> "India"
            "SA" -> "Saudi Arabia"
            "EG" -> "Egypt"
            "MX" -> "Mexico"
            "ES" -> "Spain"
            "IT" -> "Italy"
            else -> code
        }

        private fun prefs(): SharedPreferences? {
            return try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
                app?.getSharedPreferences("YouTubeLiveDiscover", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }

        fun getRegion(): String {
            val raw = prefs()?.getString(REGION_PREF_KEY, null)?.trim()?.uppercase(Locale.ROOT)
            return if (!raw.isNullOrBlank() && raw.length == 2) raw else DEFAULT_REGION
        }

        fun setRegion(code: String) {
            val v = code.trim().uppercase(Locale.ROOT).take(2).ifBlank { DEFAULT_REGION }
            prefs()?.edit()?.putString(REGION_PREF_KEY, v)?.apply()
        }
    }
}
