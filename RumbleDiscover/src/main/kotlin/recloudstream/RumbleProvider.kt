package recloudstream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Rumble Discover — **Live now only**.
 *
 * Homepage loads a small `rumble-live.json` feed (short embed ids + HLS URLs) so devices
 * never scrape 800KB watch pages. Playback uses embedJS by short id, with direct HLS
 * fallback from the feed.
 */
class RumbleProvider : MainAPI() {
    override var mainUrl = "https://rumble.com"
    override var name = "Rumble Discover"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxLive = 40
    private val videoPath = "/__discover_video__/"

    private val liveFeedUrl =
        "https://raw.githubusercontent.com/sika200581/cloudstream-live-hub/main/rumble-live.json"

    private val htmlHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://rumble.com/",
    )

    private val jsonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://rumble.com",
        "Referer" to "https://rumble.com/",
    )

    private val mapper = ObjectMapper()

    /** shortId → HLS from rumble-live.json */
    private val feedHls = HashMap<String, String>()
    private val feedMeta = HashMap<String, FeedItem>()

    @Volatile
    private var warmed = false

    override val mainPage
        get() = mainPageOf(
            "live" to "Live now",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        warmUp()
        val items = loadFeedCards()
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
        warmUp()
        val cards = loadFeedCards()
        val needle = q.lowercase(Locale.ROOT)
        return cards.filter {
            it.name.lowercase(Locale.ROOT).contains(needle)
        }.ifEmpty { cards.take(12) }
    }

    override suspend fun load(url: String): LoadResponse {
        warmUp()
        val cleaned = url.trim()
        ensureFeedLoaded()

        val videoId = parseDiscoverVideoId(cleaned)
            ?: cleaned.trim().takeIf { it.matches(Regex("""^v[0-9a-z]+$""", RegexOption.IGNORE_CASE)) }
            ?: findFeedIdByPage(cleaned)
            ?: resolveShortIdFromWatch(cleaned)
            ?: throw RuntimeException("Could not resolve Rumble video id")

        val feed = feedMeta[videoId]
        val meta = fetchEmbed(videoId)
        val title = decodeHtml(meta?.title) ?: feed?.title ?: "Rumble Live"
        val author = meta?.authorName ?: feed?.author
        val thumb = meta?.bestThumb() ?: feed?.thumb
        val watchUrl = meta?.pageUrl ?: feed?.page ?: discoverVideoUrl(videoId)
        val isLive = (meta != null && isEffectiveLive(meta)) || feedHls.containsKey(videoId)

        return if (isLive) {
            newLiveStreamLoadResponse(title, watchUrl, videoId) {
                plot = buildString {
                    author?.let { append(it) }
                    append(" · Live on Rumble")
                }
                posterUrl = thumb
                backgroundPosterUrl = thumb
                tags = listOfNotNull("Live", author)
            }
        } else {
            newMovieLoadResponse(title, watchUrl, TvType.Movie, videoId) {
                plot = author?.let { "$it · Video" } ?: "Rumble video"
                posterUrl = thumb
                backgroundPosterUrl = thumb
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("__discover_") && !data.contains(videoPath)) return false
        warmUp()
        ensureFeedLoaded()

        val videoId = parseDiscoverVideoId(data)
            ?: data.trim().takeIf { it.matches(Regex("""^v[0-9a-z]+$""", RegexOption.IGNORE_CASE)) }
            ?: resolveShortIdFromWatch(data)
            ?: data.trim().takeIf { it.isNotBlank() }
            ?: return false

        var emitted = false
        val meta = fetchEmbed(videoId)
        val hlsUrls = ArrayList<Pair<String, String>>()
        if (meta != null) hlsUrls.addAll(meta.hlsUrls())
        if (hlsUrls.isEmpty()) {
            feedHls[videoId]?.let { hlsUrls.add("feed" to it) }
        }
        if (hlsUrls.isEmpty()) return false

        for ((label, hls) in hlsUrls) {
            callback(
                newExtractorLink(
                    name,
                    "$name $label",
                    hls
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = "https://rumble.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }

        for ((height, mp4) in meta?.mp4Urls().orEmpty()) {
            callback(
                newExtractorLink(
                    name,
                    "$name ${height}p",
                    mp4
                ) {
                    this.type = ExtractorLinkType.VIDEO
                    this.referer = "https://rumble.com/"
                    this.quality = height
                }
            )
            emitted = true
        }

        return emitted
    }

    // --- feed ---

    private suspend fun loadFeedCards(): List<SearchResponse> {
        ensureFeedLoaded()
        val items = feedMeta.values
            .sortedByDescending { it.viewers ?: -1L }
            .take(maxLive)
        return items.map { it.toCard() }
    }

    private suspend fun ensureFeedLoaded() {
        if (feedMeta.isNotEmpty()) return
        val root = runCatching {
            val res = app.get(liveFeedUrl, headers = jsonHeaders)
            val body = res.text.trim()
            if (body.isEmpty() || body.startsWith("<")) return@runCatching null
            mapper.readTree(body)
        }.getOrNull() ?: return

        val arr = root.path("lives")
        if (!arr.isArray) return
        feedHls.clear()
        feedMeta.clear()
        for (n in arr) {
            val id = n.path("id").asText(null)?.trim().orEmpty()
            if (id.isEmpty()) continue
            val hls = n.path("hls").asText(null)
            if (!hls.isNullOrBlank()) feedHls[id] = hls
            feedMeta[id] = FeedItem(
                id = id,
                title = n.path("title").asText(null),
                author = n.path("author").asText(null),
                thumb = n.path("thumb").asText(null),
                page = n.path("page").asText(null),
                viewers = n.path("viewers").asLong(-1).takeIf { it >= 0 },
            )
        }
    }

    private fun findFeedIdByPage(pageUrl: String): String? {
        val needle = pageUrl.substringBefore("?").trimEnd('/')
        for (item in feedMeta.values) {
            val page = item.page?.substringBefore("?")?.trimEnd('/') ?: continue
            if (page == needle || needle.endsWith(page.removePrefix("https://rumble.com"))) {
                return item.id
            }
            // slug match /v7fho4c-
            val slug = Regex("""/(v[0-9a-z]+)-""", RegexOption.IGNORE_CASE).find(needle)?.groupValues?.getOrNull(1)
            val pageSlug = Regex("""/(v[0-9a-z]+)-""", RegexOption.IGNORE_CASE).find(page)?.groupValues?.getOrNull(1)
            if (slug != null && slug.equals(pageSlug, true)) return item.id
        }
        return null
    }

    // --- HTTP / embedJS ---

    private suspend fun warmUp() {
        if (warmed) return
        runCatching { app.get(mainUrl, headers = htmlHeaders) }
        warmed = true
    }

    private suspend fun getHtml(url: String): String? {
        return runCatching {
            val text = app.get(url, headers = htmlHeaders).text
            if (text.contains("Just a moment", ignoreCase = true) && text.length < 20_000) null
            else text
        }.getOrNull()
    }

    private suspend fun fetchEmbed(videoId: String): EmbedMeta? {
        val id = videoId.trim()
        if (id.isEmpty()) return null
        val url = "$mainUrl/embedJS/u3/?request=video&ver=2&v=${URLEncoder.encode(id, "UTF-8")}"
        repeat(2) {
            val meta = runCatching {
                val text = app.get(url, headers = jsonHeaders).text.trim()
                if (text.isEmpty() || text == "false" || text == "null") return@runCatching null
                if (text.startsWith("<")) return@runCatching null
                parseEmbed(id, mapper.readTree(text))
            }.getOrNull()
            if (meta != null) return meta
        }
        return null
    }

    private fun parseEmbed(requestedId: String, root: JsonNode): EmbedMeta? {
        if (root.isBoolean && !root.asBoolean()) return null
        if (!root.isObject) return null
        val rawLive = root.path("live").asInt(-1)
        val hasLiveHls = collectHls(root).any { it.contains("live-hls") }
        val live = if (rawLive == 2 || hasLiveHls) 2 else rawLive
        val author = root.path("author")
        val thumbs = ArrayList<String>()
        root.path("i").asText(null)?.let { thumbs.add(it) }
        val t = root.get("t")
        if (t != null && t.isArray) {
            for (n in t) n.path("i").asText(null)?.let { thumbs.add(it) }
        }
        val pagePath = root.path("l").asText(null)
        val pageUrl = when {
            pagePath.isNullOrBlank() -> null
            pagePath.startsWith("http") -> pagePath
            else -> mainUrl + pagePath
        }
        return EmbedMeta(
            id = requestedId,
            live = live,
            title = root.path("title").asText(null),
            authorName = author.path("name").asText(null),
            thumbs = thumbs,
            pageUrl = pageUrl,
            root = root,
        )
    }

    private suspend fun resolveShortIdFromWatch(raw: String): String? {
        if (!raw.contains("rumble.com/v")) return null
        val pageUrl = raw.substringBefore("?").let {
            if (it.startsWith("http")) it else mainUrl + it
        }
        val html = getHtml(pageUrl) ?: return null
        return Regex(
            """["']video["']\s*:\s*["'](v[0-9a-z]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
    }

    private fun parseDiscoverVideoId(url: String): String? {
        if (!url.contains(videoPath) && !url.contains("/__discover_video__/")) return null
        val id = url.substringAfter(videoPath, missingDelimiterValue = "")
            .ifBlank { url.substringAfter("/__discover_video__/") }
            .substringBefore("/")
            .substringBefore("?")
            .trim()
        return id.takeIf { it.matches(Regex("""^v[0-9a-z]+$""", RegexOption.IGNORE_CASE)) }
    }

    private fun isEffectiveLive(meta: EmbedMeta): Boolean {
        if (meta.live == 2) return true
        return meta.hlsUrls().any { it.second.contains("live-hls") }
    }

    private fun collectHls(root: JsonNode): List<String> {
        val out = ArrayList<String>()
        fun absorb(node: JsonNode?) {
            if (node == null) return
            if (node.isObject) {
                val url = node.path("url").asText(null)
                if (!url.isNullOrBlank()) out.add(url)
                val fields = node.fields()
                while (fields.hasNext()) absorb(fields.next().value)
            } else if (node.isArray) {
                for (n in node) absorb(n)
            }
        }
        absorb(root.path("ua").get("hls"))
        absorb(root.path("u").get("hls"))
        return out
    }

    private fun EmbedMeta.hlsUrls(): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        fun absorb(node: JsonNode?, label: String) {
            if (node == null || !node.isObject) return
            val url = node.path("url").asText(null)
            if (!url.isNullOrBlank()) out.putIfAbsent(label, url)
        }
        val uaHls = root.path("ua").get("hls")
        if (uaHls != null && uaHls.isObject) {
            for (k in uaHls.fieldNames().asSequence().toList()) absorb(uaHls.get(k), k)
        }
        absorb(root.path("u").get("hls"), "hls")
        return out.entries
            .sortedByDescending { it.value.contains("live-hls") }
            .map { it.key to it.value }
    }

    private fun EmbedMeta.mp4Urls(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val mp4 = root.path("ua").get("mp4") ?: return out
        if (!mp4.isObject) return out
        val fields = mp4.fields()
        while (fields.hasNext()) {
            val e = fields.next()
            val height = e.key.toIntOrNull()
                ?: e.value.path("meta").path("h").asInt(0).takeIf { it > 0 }
                ?: continue
            val url = e.value.path("url").asText(null) ?: continue
            if (url.isNotBlank()) out.add(height to url)
        }
        return out.sortedByDescending { it.first }
    }

    private fun EmbedMeta.bestThumb(): String? = thumbs.firstOrNull { it.isNotBlank() }

    private fun FeedItem.toCard() = newLiveSearchResponse(
        buildString {
            append(author?.ifBlank { null } ?: "Rumble")
            append(" · LIVE")
            viewers?.let {
                append(" · ")
                append(formatViewers(it))
            }
            title?.let {
                append('\n')
                append(it.truncate(70))
            }
        },
        discoverVideoUrl(id),
        TvType.Live,
        fix = false
    ) {
        posterUrl = thumb.orEmpty()
    }

    private fun discoverVideoUrl(id: String) = "$mainUrl$videoPath$id"

    private fun decodeHtml(s: String?): String? {
        if (s.isNullOrBlank()) return s
        return Jsoup.parse(s).text().ifBlank { s }
    }

    private fun String.truncate(max: Int): String {
        val t = trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    private fun formatViewers(count: Long): String {
        if (count < 1000) return count.toString()
        val exp = (ln(count.toDouble()) / ln(1000.0)).toInt().coerceAtLeast(1)
        val units = arrayOf("K", "M", "B")
        val value = count / 1000.0.pow(exp.toDouble())
        val formatted = if (value >= 100) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
        }
        return formatted + units[(exp - 1).coerceIn(0, units.lastIndex)]
    }

    data class FeedItem(
        val id: String,
        val title: String?,
        val author: String?,
        val thumb: String?,
        val page: String?,
        val viewers: Long?,
    )

    data class EmbedMeta(
        val id: String,
        val live: Int,
        val title: String?,
        val authorName: String?,
        val thumbs: List<String>,
        val pageUrl: String?,
        val root: JsonNode,
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
