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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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
 * Rumble Discover — live-first browse via HTML listings + embedJS metadata/playback.
 *
 * Important: HTML `data-video-id` (numeric) is NOT the embedJS id. Watch pages contain
 * `Rumble("play", { "video":"v7db0j2" …})` — that short id is what embedJS expects.
 * Homepage scrapes `/browse/live` HTML only (fast). Watch→short id→embedJS runs on play.
 * Do not crawl watch pages during getMainPage — that times out CloudStream (120s).
 *
 * Card URLs use `/__discover_video__/{shortId}` so CloudStream does not hit `/embed/{id}`
 * HTML (403). Always keep embed identity as the requested `v=` id — never replace with JSON `vid`.
 */
class RumbleProvider : MainAPI() {
    override var mainUrl = "https://rumble.com"
    override var name = "Rumble Discover"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxLive = 30
    private val maxResolve = 8
    private val maxBrowsePages = 1
    private val maxSearch = 24
    private val maxChannelItems = 20

    private val categoryPath = "/__discover_category__/"
    private val channelPath = "/__discover_channel__/"
    private val videoPath = "/__discover_video__/"

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

    /** Category keyword hints for best-effort filtering of browse/live when search fails. */
    private val categoryKeywords = mapOf(
        "gaming" to listOf(
            "game", "gaming", "play", "stream", "fps", "rpg", "minecraft", "fortnite",
            "valorant", "warzone", "gta", "roblox", "twitch", "esport", "xbox", "playstation",
            "nintendo", "pc game", "speedrun"
        ),
        "news" to listOf(
            "news", "breaking", "live news", "headline", "politics", "election", "trump",
            "congress", "war", "ukraine", "israel", "rt ", "newsmax", "cnn", "fox", "msnbc",
            "report", "journalist", "press"
        ),
        "music" to listOf(
            "music", "dj", "radio", "song", "concert", "live set", "edm", "hip hop", "rap",
            "rock", "jazz", "playlist", "remix", "karaoke", "band"
        ),
        "viral" to listOf(
            "viral", "funny", "comedy", "reaction", "prank", "meme", "clip", "shorts",
            "trending", "drama", "rant"
        ),
        "finance" to listOf(
            "finance", "stock", "crypto", "bitcoin", "market", "trading", "invest", "forex",
            "economy", "fed", "gold", "money", "business", "wall street", "nasdaq"
        ),
        "sports" to listOf(
            "sport", "nfl", "nba", "mlb", "nhl", "soccer", "football", "basketball", "mma",
            "ufc", "boxing", "wrestling", "golf", "tennis", "racing", "f1", "baseball", "hockey"
        ),
    )

    @Volatile
    private var warmed = false

    /** Cached browse/live HTML cards for homepage + category rows (same session). */
    @Volatile
    private var cachedBrowseCards: List<ListingItem>? = null

    override val mainPage
        get() = mainPageOf(
            "live" to "Live now",
            "cat:gaming" to "Gaming",
            "cat:news" to "News",
            "cat:music" to "Music",
            "cat:viral" to "Viral",
            "cat:finance" to "Finance",
            "cat:sports" to "Sports",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        warmUp()
        val cards = ensureBrowseCards()
        val items: List<SearchResponse> = when {
            request.data == "live" -> cards
                .sortedByDescending { it.viewers ?: -1L }
                .take(maxLive)
                .map { it.toLiveCard() }
            request.data.startsWith("cat:") -> {
                val cat = request.data.removePrefix("cat:")
                filterCardsByCategory(cards, cat)
                    .sortedByDescending { it.viewers ?: -1L }
                    .take(maxLive.coerceAtMost(24))
                    .map { it.toLiveCard() }
            }
            else -> emptyList()
        }

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

        val channelHint = q.removePrefix("@").removePrefix("c/").removePrefix("user/")
        if (q.startsWith("c/") || q.startsWith("@") || q.startsWith("user/")) {
            val url = if (q.startsWith("user/") || looksLikeUser(channelHint)) {
                channelPageUrl("user", channelHint)
            } else {
                channelPageUrl("c", channelHint)
            }
            return listOf(
                newLiveSearchResponse(
                    channelHint,
                    url,
                    TvType.TvSeries,
                    fix = false
                )
            )
        }

        val listing = scrapeSearchVideos(q).take(maxSearch)
        if (listing.isEmpty()) return emptyList()

        // Fast path: show HTML cards. Resolve watch/embed only for a tiny live sample if needed.
        val out = ArrayList<SearchResponse>()
        for (item in listing) {
            if (item.isLiveHint) out.add(item.toLiveCard())
            else {
                val url = item.pageUrl ?: continue
                out.add(
                    newLiveSearchResponse(
                        buildString {
                            append(item.author?.ifBlank { null } ?: "Rumble")
                            item.title?.let {
                                append('\n')
                                append(it.truncate(70))
                            }
                        },
                        url,
                        TvType.Movie,
                        fix = false
                    ) {
                        posterUrl = item.thumbnail.orEmpty()
                    }
                )
            }
            if (out.size >= maxSearch) break
        }
        return out.sortedByDescending { it.type == TvType.Live }
    }

    override suspend fun load(url: String): LoadResponse {
        warmUp()
        val cleaned = url.trim()

        parseCategorySlug(cleaned)?.let { return loadCategory(it) }
        parseChannelSlug(cleaned)?.let { (kind, name) -> return loadChannel(kind, name) }

        Regex("""rumble\.com/(c|user)/([^/?#]+)""").find(cleaned)?.let { m ->
            return loadChannel(m.groupValues[1], m.groupValues[2])
        }

        val videoId = resolveVideoId(cleaned)
            ?: throw RuntimeException("Could not resolve Rumble video id")

        val meta = fetchEmbed(videoId)
            ?: throw RuntimeException("embedJS returned no data for $videoId")

        val title = decodeHtml(meta.title)?.ifBlank { null } ?: "Rumble"
        val author = meta.authorName
        val thumb = meta.bestThumb()
        val watchUrl = meta.pageUrl ?: discoverVideoUrl(videoId)
        val data = videoId

        return if (isEffectiveLive(meta)) {
            newLiveStreamLoadResponse(title, watchUrl, data) {
                plot = buildString {
                    author?.let { append(it) }
                    append(" · Live on Rumble")
                }
                posterUrl = thumb
                backgroundPosterUrl = thumb
                tags = listOfNotNull("Live", author)
            }
        } else {
            newMovieLoadResponse(title, watchUrl, TvType.Movie, data) {
                plot = buildString {
                    author?.let { append(it) }
                    when (meta.live) {
                        1 -> append(" · Upcoming / was live")
                        else -> append(" · Video")
                    }
                }
                posterUrl = thumb
                backgroundPosterUrl = thumb
                tags = listOfNotNull(
                    when (meta.live) {
                        1 -> "Was live"
                        else -> "VOD"
                    },
                    author
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("__discover_")) return false
        warmUp()

        val videoId = resolveVideoId(data) ?: data.trim().takeIf { it.isNotBlank() }
            ?: return false

        val meta = fetchEmbed(videoId) ?: return false
        var emitted = false

        val hlsUrls = meta.hlsUrls()
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

        for ((height, mp4) in meta.mp4Urls()) {
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

    // --- discovery ---

    /** One (or two) browse/live HTML scrapes — no watch-page crawl. */
    private suspend fun ensureBrowseCards(): List<ListingItem> {
        cachedBrowseCards?.takeIf { it.isNotEmpty() }?.let { return it }
        val cards = ArrayList<ListingItem>()
        val seen = HashSet<String>()
        for (page in 1..maxBrowsePages) {
            val url = if (page == 1) "$mainUrl/browse/live" else "$mainUrl/browse/live?page=$page"
            val html = getHtml(url) ?: continue
            for (c in scrapeLiveCards(html)) {
                val key = c.pageUrl ?: continue
                if (!seen.add(key)) continue
                cards.add(c)
            }
            if (cards.size >= maxLive) break
        }
        cachedBrowseCards = cards
        return cards
    }

    private fun filterCardsByCategory(cards: List<ListingItem>, category: String): List<ListingItem> {
        val keywords = categoryKeywords[category.lowercase(Locale.ROOT)]
            ?: listOf(category.lowercase(Locale.ROOT))
        return cards.filter { item ->
            val hay = buildString {
                append(item.title.orEmpty())
                append(' ')
                append(item.author.orEmpty())
            }.lowercase(Locale.ROOT)
            keywords.any { kw -> hay.contains(kw) }
        }
    }

    private suspend fun fetchLiveFromBrowse(limit: Int): List<EmbedMeta> {
        // Used by category detail pages — cap resolves hard to avoid timeouts
        val cards = ensureBrowseCards().take(maxResolve.coerceAtLeast(limit.coerceAtMost(8)))
        return resolveLiveCards(cards, limit.coerceAtMost(8))
    }

    private suspend fun fetchLiveForCategory(category: String, limit: Int): List<EmbedMeta> {
        val cards = filterCardsByCategory(ensureBrowseCards(), category).take(maxResolve)
        if (cards.isEmpty()) return emptyList()
        return resolveLiveCards(cards, limit.coerceAtMost(8))
    }

    private suspend fun fetchLiveFromSearch(query: String, limit: Int): List<EmbedMeta> {
        val listing = scrapeSearchVideos(query)
        if (listing.isEmpty()) return emptyList()
        val preferred = listing.filter { it.isLiveHint }.ifEmpty { listing }
        return resolveLiveCards(preferred.take(maxResolve), limit.coerceAtMost(8))
    }

        private suspend fun resolveLiveCards(cards: List<ListingItem>, limit: Int): List<EmbedMeta> {
        val out = ArrayList<EmbedMeta>()
        val seenStreams = HashSet<String>()
        val seenIds = HashSet<String>()

        for (card in cards) {
            if (out.size >= limit) break
            val shortId = resolveListingToShortId(card) ?: continue
            if (!seenIds.add(shortId)) continue
            val meta = fetchEmbed(shortId) ?: continue
            if (!isEffectiveLive(meta)) continue
            val streamKey = meta.hlsUrls().firstOrNull()?.second
                ?: meta.pageUrl
                ?: meta.id
            if (!seenStreams.add(streamKey)) continue
            // Prefer listing viewers when embed has none
            val withViews = if (meta.viewers == null && card.viewers != null) {
                meta.copy(viewers = card.viewers)
            } else meta
            out.add(withViews)
        }

        return out.sortedByDescending { it.viewers ?: -1L }
    }

    /** Resolve listing card to short embed id via watch page (never use numeric data-video-id). */
    private suspend fun resolveListingToShortId(item: ListingItem): String? {
        item.pageUrl?.let { page ->
            extractShortIdFromWatch(page)?.let { return it }
        }
        // Optional last-resort: numeric id (often wrong VOD — only if no page URL)
        val num = item.numericId
        if (num != null && item.pageUrl == null) {
            val meta = fetchEmbed(num)
            if (meta != null && isEffectiveLive(meta)) return meta.id
        }
        return null
    }

    private suspend fun extractShortIdFromWatch(pageUrl: String): String? {
        val url = when {
            pageUrl.startsWith("http") -> pageUrl.substringBefore("?")
            pageUrl.startsWith("/") -> mainUrl + pageUrl.substringBefore("?")
            else -> "$mainUrl/$pageUrl".substringBefore("?")
        }
        val html = getHtml(url) ?: return null
        return extractShortEmbedId(html)
    }

    private fun extractShortEmbedId(html: String): String? {
        // Primary: Rumble("play", { … "video":"v7db0j2" …})
        Regex(
            """Rumble\(\s*"play"\s*,\s*\{[\s\S]{0,1200}?"video"\s*:\s*"([0-9a-z]+)"""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """["']video["']\s*:\s*["'](v[0-9a-z]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """embedJS[^"']*[?&]v=([0-9a-z]+)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """rumble\.com/embed/(?:[0-9a-z]+\.)?([0-9a-z]+)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        return null
    }

    private fun isEffectiveLive(meta: EmbedMeta): Boolean {
        if (meta.live == 2) return true
        return meta.hlsUrls().any { it.second.contains("live-hls") }
    }

    private suspend fun loadCategory(slug: String): LoadResponse {
        warmUp()
        val cards = filterCardsByCategory(ensureBrowseCards(), slug)
            .sortedByDescending { it.viewers ?: -1L }
            .take(maxLive)
        val episodes = cards.mapIndexed { index, item ->
            val title = item.title?.ifBlank { null } ?: "Live"
            val author = item.author
            newEpisode(item.pageUrl ?: mainUrl) {
                this.name = buildString {
                    append(author ?: "Rumble")
                    append('\n')
                    append(title.truncate(70))
                }
                this.posterUrl = item.thumbnail
                this.episode = index + 1
                this.season = 1
                this.description = title
            }
        }
        return newTvSeriesLoadResponse(
            slug.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
            "$mainUrl$categoryPath$slug",
            TvType.TvSeries,
            episodes
        ) {
            plot = "Live streams related to “$slug” on Rumble"
            posterUrl = episodes.firstOrNull()?.posterUrl
            tags = listOf("Category", "Live", "${episodes.size} live")
            recommendations = cards.map { it.toLiveCard() }
        }
    }

    private suspend fun loadChannel(kind: String, name: String): LoadResponse {
        warmUp()
        val path = if (kind == "user") "/user/$name" else "/c/$name"
        val html = getHtml("$mainUrl$path")
            ?: getHtml("$mainUrl$path?page=1")
            ?: throw RuntimeException("Could not load channel")

        val doc = Jsoup.parse(html, mainUrl)
        val display = doc.selectFirst("h1")?.text()?.ifBlank { null } ?: name
        val items = scrapeListingItems(html).ifEmpty {
            scrapeLiveCards(html)
        }.take(maxChannelItems)

        val episodes = items.mapIndexed { index, item ->
            val isLive = item.isLiveHint
            val title = item.title?.ifBlank { null } ?: "Video"
            newEpisode(item.pageUrl ?: "$mainUrl$path") {
                this.name = buildString {
                    if (isLive) append("LIVE · ")
                    append(title.truncate(70))
                }
                this.posterUrl = item.thumbnail
                this.episode = index + 1
                this.season = if (isLive) 1 else 2
                this.description = title
            }
        }.ifEmpty {
            listOf(
                newEpisode("$mainUrl$path") {
                    this.name = "No videos found"
                    this.episode = 1
                    this.season = 1
                }
            )
        }

        val liveCount = items.count { it.isLiveHint }
        return newTvSeriesLoadResponse(
            display,
            channelPageUrl(kind, name),
            TvType.TvSeries,
            episodes
        ) {
            plot = "Rumble channel · ${episodes.size} items"
            posterUrl = episodes.firstOrNull()?.posterUrl
            tags = listOfNotNull(
                if (liveCount > 0) "Live" else null,
                "$liveCount live",
                "${episodes.size} items"
            )
        }
    }

    // --- HTTP / embedJS ---

    private suspend fun warmUp() {
        if (warmed) return
        // One browse/live hit sets cookies for later watch-page resolve on play
        runCatching { app.get("$mainUrl/browse/live", headers = htmlHeaders) }
        warmed = true
    }

    private suspend fun getHtml(url: String): String? {
        return runCatching {
            val res = app.get(url, headers = htmlHeaders)
            val text = res.text
            if (text.contains("Just a moment", ignoreCase = true) && text.length < 20_000) {
                warmed = false
                warmUp()
                val retry = app.get(url, headers = htmlHeaders).text
                if (retry.contains("Just a moment", ignoreCase = true) && retry.length < 20_000) {
                    null
                } else retry
            } else text
        }.getOrNull()
    }

    private suspend fun fetchEmbed(videoId: String): EmbedMeta? {
        val id = videoId.trim()
        if (id.isEmpty()) return null
        val url = "$mainUrl/embedJS/u3/?request=video&ver=2&v=${URLEncoder.encode(id, "UTF-8")}"
        repeat(3) {
            val meta = runCatching {
                val res = app.get(url, headers = jsonHeaders)
                val text = res.text.trim()
                if (text.isEmpty() || text == "false" || text == "null") return@runCatching null
                if (text.startsWith("<") || text.contains("Just a moment", ignoreCase = true)) {
                    warmed = false
                    warmUp()
                    return@runCatching null
                }
                val root = ObjectMapper().readTree(text)
                parseEmbed(id, root)
            }.getOrNull()
            if (meta != null) return meta
        }
        return null
    }

    private fun parseEmbed(requestedId: String, root: JsonNode): EmbedMeta? {
        if (root.isBoolean && !root.asBoolean()) return null
        if (!root.isObject) return null
        val rawLive = root.path("live").asInt(-1)
        val hasLiveHls = collectHlsUrlStrings(root).any { it.contains("live-hls") }
        val effectiveLive = if (rawLive == 2 || hasLiveHls) 2 else rawLive
        val title = root.path("title").asText(null)
        val author = root.path("author")
        val authorName = author.path("name").asText(null)
        val authorUrl = author.path("url").asText(null)
        val thumb = root.path("i").asText(null)
        val thumbs = ArrayList<String>()
        if (!thumb.isNullOrBlank()) thumbs.add(thumb)
        val t = root.get("t")
        if (t != null && t.isArray) {
            for (n in t) {
                n.path("i").asText(null)?.let { thumbs.add(it) }
            }
        }
        val pagePath = root.path("l").asText(null)
        val pageUrl = when {
            !pagePath.isNullOrBlank() && pagePath.startsWith("http") -> pagePath
            !pagePath.isNullOrBlank() -> mainUrl + pagePath
            else -> null
        }
        val viewers = root.path("watching").asLong(-1).takeIf { it >= 0 }
            ?: root.path("viewer_count").asLong(-1).takeIf { it >= 0 }
            ?: root.path("views").asLong(-1).takeIf { it >= 0 }
        return EmbedMeta(
            id = requestedId,
            live = effectiveLive,
            title = title,
            authorName = authorName,
            authorUrl = authorUrl,
            thumbs = thumbs,
            pageUrl = pageUrl,
            root = root,
            viewers = viewers,
        )
    }

    private fun collectHlsUrlStrings(root: JsonNode): List<String> {
        val out = ArrayList<String>()
        fun absorb(node: JsonNode?) {
            if (node == null) return
            when {
                node.isTextual -> {
                    val s = node.asText()
                    if (s.contains(".m3u8") || s.contains("hls")) out.add(s)
                }
                node.isObject -> {
                    val url = node.path("url").asText(null)
                    if (!url.isNullOrBlank()) out.add(url)
                    val fields = node.fields()
                    while (fields.hasNext()) {
                        absorb(fields.next().value)
                    }
                }
                node.isArray -> {
                    for (n in node) absorb(n)
                }
            }
        }
        absorb(root.path("ua").get("hls"))
        absorb(root.path("u").get("hls"))
        return out
    }

    /**
     * Resolve short embed id (`v7db0j2`) or numeric id from a Rumble URL / discover marker.
     * Prefer short ids via embedJS; scrape watch page when only a page slug URL is known.
     * Do NOT prefer numeric data-video-id from watch HTML.
     */
    private suspend fun resolveVideoId(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        // Discover card: /__discover_video__/{id} — id may be short (v…) or numeric
        Regex("""__discover_video__/([0-9a-zA-Z]+)""").find(s)?.groupValues?.getOrNull(1)?.let {
            return it
        }

        // Raw short embed id
        if (s.matches(Regex("""^v[0-9a-z]+$""", RegexOption.IGNORE_CASE))) {
            fetchEmbed(s)?.let { return it.id }
            return s
        }

        // Raw numeric — last resort (often wrong for live cards)
        if (s.matches(Regex("""^\d{6,}$"""))) return s

        // embed/{id} or embed/{prefix}.{id}
        Regex("""rumble\.com/embed/(?:[0-9a-z]+\.)?([0-9a-z]+)""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.getOrNull(1)?.let { return it }

        // Watch page slug /v….html — scrape for Rumble("play") short id (not data-video-id)
        val pageSlug = Regex("""rumble\.com/(v[0-9a-z]+(?:-[^/?#]*)?\.html)""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.getOrNull(1)
            ?: Regex("""/(v[0-9a-z]+(?:-[^/?#]*)?\.html)""", RegexOption.IGNORE_CASE)
                .find(s)?.groupValues?.getOrNull(1)

        if (pageSlug != null || s.contains("rumble.com/v") || s.contains("/v")) {
            val pageUrl = when {
                s.startsWith("http") -> s.substringBefore("?")
                s.startsWith("/") -> mainUrl + s.substringBefore("?")
                pageSlug != null -> "$mainUrl/$pageSlug"
                else -> null
            }
            if (pageUrl != null) {
                extractShortIdFromWatch(pageUrl)?.let { return it }
            }
        }

        // Already looks like short id embedded in path without .html
        Regex("""rumble\.com/(v[0-9a-z]+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.getOrNull(1)?.let { slug ->
                fetchEmbed(slug)?.let { return it.id }
                extractShortIdFromWatch("$mainUrl/$slug")?.let { return it }
                return slug
            }

        return null
    }

    // --- HTML scrape helpers ---

    /** Live-marked cards on browse/live (and similar grids). */
    private fun scrapeLiveCards(html: String): List<ListingItem> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<ListingItem>()
        val seen = HashSet<String>()

        val selectors = listOf(
            "div.videostream.thumbnail__grid-item",
            "div.videostream[data-video-id]",
            "div.videostream.thumbnail__grid--item",
        )
        for (sel in selectors) {
            for (el in doc.select(sel)) {
                val isLive = el.selectFirst(
                    ".thumbnail__thumb--live, .videostream__status--live, .videostream__badge--live"
                ) != null || el.hasClass("videostream--live") ||
                    el.className().contains("live")
                if (!isLive) continue

                val href = el.selectFirst("a.videostream__link[href], a.title__link[href], a[href*=/v]")
                    ?.attr("abs:href")
                    ?.substringBefore("?")
                val title = el.selectFirst("img.thumbnail__image")?.attr("alt")
                    ?: el.selectFirst(".thumbnail__title, h3.thumbnail__title")?.attr("title")
                    ?: el.selectFirst(".thumbnail__title, h3")?.text()
                val thumb = el.selectFirst("img.thumbnail__image")?.attr("abs:src")
                val author = el.selectFirst("a.channel__link, a[href*=/c/], a[href*=/user/]")?.text()
                val viewsText = el.selectFirst(".videostream__number")?.text()?.trim()
                val viewers = parseViewerCount(viewsText)
                // Keep numeric only as optional metadata — never primary embed key
                val numeric = el.attr("data-video-id").ifBlank { null }
                val key = href ?: continue
                if (!seen.add(key)) continue
                out.add(
                    ListingItem(
                        numericId = numeric,
                        pageUrl = href,
                        title = title,
                        thumbnail = thumb,
                        isLiveHint = true,
                        author = author,
                        viewers = viewers,
                    )
                )
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private fun scrapeListingItems(html: String): List<ListingItem> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<ListingItem>()
        val seen = HashSet<String>()

        for (el in doc.select(
            "div.videostream[data-video-id], div.videostream.thumbnail__grid-item, div.videostream.thumbnail__grid--item"
        )) {
            val id = el.attr("data-video-id").ifBlank { null }
            val href = el.selectFirst("a[href*=/v]")?.attr("abs:href")?.substringBefore("?")
            val title = el.selectFirst("img.thumbnail__image")?.attr("alt")
                ?: el.selectFirst(".thumbnail__title, .videostream__title, h3")?.text()
            val thumb = el.selectFirst("img.thumbnail__image")?.attr("abs:src")
            val viewsText = el.selectFirst(".videostream__number")?.text()?.trim()
            val key = href ?: id ?: continue
            if (!seen.add(key)) continue
            out.add(
                ListingItem(
                    numericId = id,
                    pageUrl = href,
                    title = title,
                    thumbnail = thumb,
                    isLiveHint = el.selectFirst(".thumbnail__thumb--live, .videostream__status--live") != null
                        || el.className().contains("live"),
                    author = el.selectFirst("a[href*=/c/], a[href*=/user/]")?.text(),
                    viewers = parseViewerCount(viewsText),
                )
            )
        }

        for (el in doc.select("article.video-item")) {
            val a = el.selectFirst("a.video-item--a[href]") ?: continue
            val href = a.attr("abs:href").substringBefore("?")
            val title = el.selectFirst(".video-item--title")?.text()
                ?: a.selectFirst("img")?.attr("alt")
            val thumb = a.selectFirst("img")?.attr("abs:src")
            val author = el.selectFirst(".video-item--by, .channel-name, a[href*=/c/], a[href*=/user/]")?.text()
            val duration = el.selectFirst(".video-item--duration")?.attr("data-value")
            val liveHint = el.hasClass("video-item--live") ||
                el.className().contains("live") ||
                duration.isNullOrBlank()
            if (!seen.add(href)) continue
            out.add(
                ListingItem(
                    numericId = null,
                    pageUrl = href,
                    title = title,
                    thumbnail = thumb,
                    isLiveHint = liveHint,
                    author = author,
                )
            )
        }

        return out
    }

    private suspend fun scrapeSearchVideos(query: String): List<ListingItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = getHtml("$mainUrl/search/video?q=$encoded") ?: return emptyList()
        // Prefer live-badged subset when present
        val live = scrapeLiveCards(html)
        if (live.isNotEmpty()) return live
        return scrapeListingItems(html)
    }

    private fun parseViewerCount(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().uppercase(Locale.US).replace(",", "")
        val m = Regex("""^([\d.]+)\s*([KMB])?$""").find(s) ?: return s.filter { it.isDigit() }.toLongOrNull()
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    // --- mapping ---

    private fun ListingItem.toLiveCard() = newLiveSearchResponse(
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
        pageUrl ?: mainUrl,
        TvType.Live,
        fix = false
    ) {
        posterUrl = thumbnail.orEmpty()
    }

    private fun EmbedMeta.toLiveCard() = newLiveSearchResponse(
        buildString {
            append(authorName?.ifBlank { null } ?: "Rumble")
            append(" · LIVE")
            viewers?.let {
                append(" · ")
                append(formatViewers(it))
            }
            decodeHtml(title)?.let {
                append('\n')
                append(it.truncate(70))
            }
        },
        discoverVideoUrl(id),
        TvType.Live,
        fix = false
    ) {
        posterUrl = bestThumb().orEmpty()
    }

    private fun EmbedMeta.toVodCard() = newLiveSearchResponse(
        buildString {
            append(authorName?.ifBlank { null } ?: "Rumble")
            decodeHtml(title)?.let {
                append('\n')
                append(it.truncate(70))
            }
        },
        pageUrl ?: discoverVideoUrl(id),
        TvType.Movie,
        fix = false
    ) {
        posterUrl = bestThumb().orEmpty()
    }

    private fun EmbedMeta.bestThumb(): String? = thumbs.firstOrNull { it.isNotBlank() }

    private fun EmbedMeta.hlsUrls(): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        fun absorb(node: JsonNode?, label: String) {
            if (node == null || !node.isObject) return
            val url = node.path("url").asText(null)
            if (!url.isNullOrBlank()) out.putIfAbsent(label, url)
        }

        val ua = root.get("ua")
        val uaHls = ua?.get("hls")
        if (uaHls != null && uaHls.isObject) {
            val keys = uaHls.fieldNames().asSequence().toList()
            val ordered = keys.sortedBy { k ->
                when {
                    k.equals("auto", true) -> 0
                    k.contains("live", true) -> 1
                    else -> 2
                }
            }
            for (k in ordered) absorb(uaHls.get(k), k)
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

    private fun discoverVideoUrl(id: String) = "$mainUrl$videoPath$id"

    private fun channelPageUrl(kind: String, name: String) =
        "$mainUrl$channelPath$kind/$name"

    private fun parseCategorySlug(url: String): String? {
        if (!url.contains(categoryPath) && !url.contains("/__discover_category__/")) return null
        return url.substringAfter(categoryPath, missingDelimiterValue = "")
            .ifBlank { url.substringAfter("/__discover_category__/") }
            .substringBefore("/")
            .substringBefore("?")
            .ifBlank { null }
    }

    private fun parseChannelSlug(url: String): Pair<String, String>? {
        val markers = listOf(channelPath, "/__discover_channel__/")
        for (marker in markers) {
            if (!url.contains(marker)) continue
            val rest = url.substringAfter(marker).substringBefore("?").trim('/')
            val parts = rest.split('/')
            return when {
                parts.size >= 2 -> parts[0] to parts[1]
                parts.size == 1 && parts[0].isNotBlank() -> "c" to parts[0]
                else -> null
            }
        }
        return null
    }

    private fun looksLikeUser(name: String): Boolean {
        return name.contains('_') || name.any { it.isLowerCase() }
    }

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

    data class ListingItem(
        val numericId: String? = null,
        val pageUrl: String? = null,
        val title: String? = null,
        val thumbnail: String? = null,
        val isLiveHint: Boolean = false,
        val author: String? = null,
        val viewers: Long? = null,
    )

    data class EmbedMeta(
        val id: String,
        val live: Int,
        val title: String?,
        val authorName: String?,
        val authorUrl: String?,
        val thumbs: List<String>,
        val pageUrl: String?,
        val root: JsonNode,
        val viewers: Long? = null,
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
