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
 * Homepage prefers live-marked cards on `/browse/live`, resolves via embedJS, and keeps
 * streams that are effectively live (`live == 2` or HLS contains `live-hls`). Playback
 * uses HLS from embedJS (`ua.hls` / `u.hls`). Card URLs use `/__discover_video__/{id}`
 * so CloudStream does not hit `/embed/{id}` HTML (403). Always keep embed identity as
 * the requested `v=` id — never replace with JSON `vid`.
 *
 * Cloudflare may challenge datacenter IPs; browser-like headers + warm-up + retries help.
 */
class RumbleProvider : MainAPI() {
    override var mainUrl = "https://rumble.com"
    override var name = "Rumble Discover"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxLive = 24
    private val maxResolve = 40
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

    @Volatile
    private var warmed = false

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
        val items = when {
            request.data == "live" -> fetchLiveFromBrowse(maxLive)
            request.data.startsWith("cat:") -> {
                val cat = request.data.removePrefix("cat:")
                // Category query on browse/live is not reliably filtered; use search + live filter.
                fetchLiveFromSearch(cat, maxLive)
            }
            else -> emptyList()
        }.map { it.toLiveCard() }

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

        // Channel / user shortcuts
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

        // Prefer live when detectable via a light embedJS pass on numeric ids
        val resolved = LinkedHashMap<String, SearchResponse>()
        for (item in listing) {
            val id = item.numericId
            if (id != null) {
                val meta = fetchEmbed(id) ?: continue
                if (meta.live == 2) {
                    resolved[id] = meta.toLiveCard()
                } else {
                    resolved[id] = meta.toVodCard()
                }
            } else {
                // Page-slug listing without trusted numeric id — show from HTML scrape
                val url = item.pageUrl ?: continue
                val label = buildString {
                    append(item.author?.ifBlank { null } ?: "Rumble")
                    if (item.isLiveHint) append(" · LIVE")
                    item.title?.let {
                        append('\n')
                        append(it.truncate(70))
                    }
                }
                resolved[url] = newLiveSearchResponse(
                    label,
                    url,
                    if (item.isLiveHint) TvType.Live else TvType.Movie,
                    fix = false
                ) {
                    posterUrl = item.thumbnail.orEmpty()
                }
            }
            if (resolved.size >= maxSearch) break
        }

        // Stable order: lives first
        return resolved.values.sortedByDescending {
            it.type == TvType.Live
        }
    }

    override suspend fun load(url: String): LoadResponse {
        warmUp()
        val cleaned = url.trim()

        parseCategorySlug(cleaned)?.let { return loadCategory(it) }
        parseChannelSlug(cleaned)?.let { (kind, name) -> return loadChannel(kind, name) }

        // Plain /c/Name or /user/Name
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
        val data = videoId // loadLinks uses numeric / embed id

        return if (meta.live == 2) {
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

        // Prefer live HLS
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

        // Optional mp4 qualities from ua.mp4
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

    private suspend fun fetchLiveFromBrowse(limit: Int): List<EmbedMeta> {
        val html = getHtml("$mainUrl/browse/live") ?: return emptyList()
        val liveMarked = extractLiveMarkedVideoIds(html)
        val ids = (if (liveMarked.isNotEmpty()) liveMarked else extractDataVideoIds(html))
            .distinct()
            .take(maxResolve)
        return resolveLiveMetas(ids, limit)
    }

    private suspend fun fetchLiveFromSearch(query: String, limit: Int): List<EmbedMeta> {
        val listing = scrapeSearchVideos(query)
        val ids = listing.mapNotNull { it.numericId }.distinct().take(maxResolve)
        if (ids.isNotEmpty()) {
            val lives = resolveLiveMetas(ids, limit)
            if (lives.isNotEmpty()) return lives
        }
        // Fall back: resolve page URLs via watch-page scrape (best-effort)
        val out = ArrayList<EmbedMeta>()
        for (item in listing) {
            if (out.size >= limit) break
            val page = item.pageUrl ?: continue
            val id = resolveVideoId(page) ?: continue
            val meta = fetchEmbed(id) ?: continue
            if (meta.live == 2) out.add(meta)
        }
        return out
    }

    private suspend fun resolveLiveMetas(ids: List<String>, limit: Int): List<EmbedMeta> {
        val out = ArrayList<EmbedMeta>()
        val seenIds = HashSet<String>()
        val seenStreams = HashSet<String>()
        for (id in ids) {
            if (out.size >= limit) break
            if (!seenIds.add(id)) continue
            val meta = fetchEmbed(id) ?: continue
            if (meta.live != 2) continue
            val streamKey = meta.hlsUrls().firstOrNull()?.second
                ?: meta.pageUrl
                ?: meta.id
            if (!seenStreams.add(streamKey)) continue
            out.add(meta)
        }
        return out
    }

    private suspend fun loadCategory(slug: String): LoadResponse {
        warmUp()
        // Prefer search-based live list for the category keyword
        val lives = fetchLiveFromSearch(slug, maxLive)
        val episodes = lives.mapIndexed { index, meta ->
            val title = decodeHtml(meta.title)?.ifBlank { null } ?: "Live"
            val author = meta.authorName
            newEpisode(discoverVideoUrl(meta.id)) {
                this.name = buildString {
                    append(author ?: "Rumble")
                    append('\n')
                    append(title.truncate(70))
                }
                this.posterUrl = meta.bestThumb()
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
            recommendations = lives.map { it.toLiveCard() }
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
            // Featured / grid cards with data-video-id only
            extractDataVideoIds(html).map { ListingItem(numericId = it, pageUrl = discoverVideoUrl(it)) }
        }.take(maxChannelItems)

        var liveCount = 0
        val episodes = buildList {
            items.forEachIndexed { index, item ->
                val id = item.numericId
                    ?: item.pageUrl?.let { resolveVideoId(it) }
                    ?: return@forEachIndexed
                val meta = fetchEmbed(id) ?: return@forEachIndexed
                val isLive = meta.live == 2
                if (isLive) liveCount++
                val title = decodeHtml(meta.title)?.ifBlank { null } ?: item.title ?: "Video"
                val season = if (isLive) 1 else 2
                val epIndex = if (isLive) liveCount else (index + 1)
                add(
                    newEpisode(discoverVideoUrl(meta.id)) {
                        this.name = buildString {
                            if (isLive) append("LIVE · ")
                            append(title.truncate(70))
                        }
                        this.posterUrl = meta.bestThumb() ?: item.thumbnail
                        this.episode = epIndex
                        this.season = season
                        this.description = decodeHtml(meta.title)
                    }
                )
            }
            if (isEmpty()) {
                add(
                    newEpisode("$mainUrl$path") {
                        this.name = "No videos found"
                        this.episode = 1
                        this.season = 1
                    }
                )
            }
        }

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
        runCatching { app.get(mainUrl, headers = htmlHeaders) }
        warmed = true
    }

    private suspend fun getHtml(url: String): String? {
        return runCatching {
            val res = app.get(url, headers = htmlHeaders)
            val text = res.text
            if (text.contains("Just a moment", ignoreCase = true) && text.length < 20_000) {
                // Cloudflare challenge — retry once after another warm-up
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
        // Always keep the requested v= id. Rumble's JSON `vid` is often a different
        // number and must not replace identity (breaks later fetches / playback).
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
        return EmbedMeta(
            id = requestedId,
            live = effectiveLive,
            title = title,
            authorName = authorName,
            authorUrl = authorUrl,
            thumbs = thumbs,
            pageUrl = pageUrl,
            root = root,
        )
    }

    /** Collect HLS URL strings from ua.hls / u.hls without building labeled pairs. */
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
     * Resolve a numeric / embed id from a Rumble URL or raw id.
     * Page slugs like `v7fh4sc` often fail on embedJS — scrape the watch page when needed.
     */
    private suspend fun resolveVideoId(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        // Raw numeric
        if (s.matches(Regex("""^\d{6,}$"""))) return s

        // Internal discover card URL: /__discover_video__/{numericId}
        Regex("""__discover_video__/(\d+)""").find(s)?.groupValues?.getOrNull(1)?.let { return it }

        // embed/{id} or embed/{prefix}.{id}
        Regex("""rumble\.com/embed/(?:[0-9a-z]+\.)?([0-9a-z]+)""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.getOrNull(1)?.let { return it }

        // Already looks like embed id (v + alnum) — try embedJS first; if false, scrape
        val slug = Regex("""rumble\.com/(v[0-9a-z]+)""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.getOrNull(1)
            ?: s.takeIf { it.matches(Regex("""^v[0-9a-z]+$""", RegexOption.IGNORE_CASE)) }

        if (slug != null) {
            fetchEmbed(slug)?.let { return it.id }
        }

        // Watch / page URL — scrape for data-video-id or Rumble("play") embed id
        if (s.contains("rumble.com/")) {
            val html = getHtml(s.substringBefore("?").let {
                if (it.startsWith("http")) it else mainUrl + it
            }) ?: return slug
            extractDataVideoIds(html).firstOrNull()?.let { return it }
            Regex(
                """Rumble\(\s*"play"\s*,\s*\{[^}]*["']?video["']?\s*:\s*["']([0-9a-z]+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.let { return it }
            Regex("""rumble\.com/embed/(?:[0-9a-z]+\.)?([0-9a-z]+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }

        return slug
    }

    // --- HTML scrape helpers ---

    private fun extractDataVideoIds(html: String): List<String> {
        return Regex("""data-video-id="(\d+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /**
     * Prefer ids from cards explicitly marked live on browse HTML.
     * Fallback regex looks for data-video-id near videostream__status--live.
     */
    private fun extractLiveMarkedVideoIds(html: String): List<String> {
        val doc = Jsoup.parse(html, mainUrl)
        val fromSel = LinkedHashSet<String>()
        val selectors = listOf(
            "div.videostream:has(.videostream__status--live)[data-video-id]",
            "div.videostream:has(.thumbnail__thumb--live)[data-video-id]",
            "div.videostream.videostream--live[data-video-id]",
            "div.videostream:has(.videostream__badge--live)[data-video-id]",
        )
        for (sel in selectors) {
            for (el in doc.select(sel)) {
                el.attr("data-video-id").takeIf { it.isNotBlank() }?.let { fromSel.add(it) }
            }
        }
        if (fromSel.isNotEmpty()) return fromSel.toList()

        val regexHits = LinkedHashSet<String>()
        Regex(
            """data-video-id="(\d+)"[^>]{0,200}>[\s\S]{0,1200}?videostream__status--live|videostream__status--live[\s\S]{0,1200}?data-video-id="(\d+)"""",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(html).forEach { m ->
            val id = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (id.isNotBlank()) regexHits.add(id)
        }
        // Also: thumbnail__thumb--live near data-video-id
        Regex(
            """data-video-id="(\d+)"[^>]{0,200}>[\s\S]{0,800}?thumbnail__thumb--live|thumbnail__thumb--live[\s\S]{0,800}?data-video-id="(\d+)"""",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(html).forEach { m ->
            val id = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (id.isNotBlank()) regexHits.add(id)
        }
        return regexHits.toList()
    }

    private suspend fun scrapeSearchVideos(query: String): List<ListingItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = getHtml("$mainUrl/search/video?q=$encoded") ?: return emptyList()
        return scrapeListingItems(html)
    }

    private fun scrapeListingItems(html: String): List<ListingItem> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<ListingItem>()
        val seen = HashSet<String>()

        // Newer grid cards
        for (el in doc.select("div.videostream[data-video-id], div.videostream.thumbnail__grid-item, div.videostream.thumbnail__grid--item")) {
            val id = el.attr("data-video-id").ifBlank { null }
            val href = el.selectFirst("a[href*=/v]")?.attr("abs:href")
            val title = el.selectFirst("img.thumbnail__image")?.attr("alt")
                ?: el.selectFirst(".thumbnail__title, .videostream__title, h3")?.text()
            val thumb = el.selectFirst("img.thumbnail__image")?.attr("abs:src")
            val key = id ?: href ?: continue
            if (!seen.add(key)) continue
            out.add(
                ListingItem(
                    numericId = id,
                    pageUrl = href?.substringBefore("?"),
                    title = title,
                    thumbnail = thumb,
                    isLiveHint = el.selectFirst(".thumbnail__thumb--live, .videostream__status--live") != null
                        || el.className().contains("live"),
                    author = el.selectFirst("a[href*=/c/], a[href*=/user/]")?.text()
                )
            )
        }

        // Legacy search / listing articles
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
            // data-id on rumbles-vote is unreliable for pairing — do not trust as video id
            val key = href
            if (!seen.add(key)) continue
            out.add(
                ListingItem(
                    numericId = null,
                    pageUrl = href,
                    title = title,
                    thumbnail = thumb,
                    isLiveHint = liveHint,
                    author = author
                )
            )
        }

        return out
    }

    // --- mapping ---

    private fun EmbedMeta.toLiveCard() = newLiveSearchResponse(
        buildString {
            append(authorName?.ifBlank { null } ?: "Rumble")
            append(" · LIVE")
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
            // Prefer auto / live keys first
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

        // Prefer live-hls URLs first in list order
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

    private fun embedUrl(id: String) = "$mainUrl/embed/$id"

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
        // Heuristic unused for most paths; keep for @user shortcuts
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
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
