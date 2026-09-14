package recloudstream

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Kick Discover — REST-backed browse with stream titles, live previews,
 * category directory, and channel pages (Live + VODs + clips).
 *
 * Inspired by TwitchDiscover patterns in this repo.
 *
 * Note: Kick's /stream/livestreams/{lang} path does not reliably filter by
 * language; homepage language rows are filled by client-side filtering.
 * Cloudflare may intermittently 403 — browser-like headers help; optional
 * session cookie/token settings can be added later if needed.
 */
class KickProvider : MainAPI() {
    override var mainUrl = "https://kick.com"
    override var name = "Kick Discover"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxStreams = 24
    private val maxCategories = 30
    private val maxChannelVods = 12
    private val maxChannelClips = 12
    private val langFetchLimit = 100

    /** Path-based so CloudStream won't mangle a custom scheme into mainUrl/…. */
    private val categoryPath = "/__discover_category__/"
    /** Channel detail pages (TvSeries). Distinct from real /{slug} used for HLS. */
    private val channelPath = "/__discover_channel__/"

    private val categoriesSection = "Category directory"

    private val kickHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://kick.com/",
        "Origin" to "https://kick.com",
    )

    override val mainPage
        get() = mainPageOf(
            *buildList {
                add("categories" to categoriesSection)
                add("streams" to "Top live worldwide")
                for (code in getEnabledHomeLanguages()) {
                    add("streams:$code" to "Top ${languageLabel(code)}")
                }
            }.toTypedArray()
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.data) {
            "categories" -> {
                val cats = fetchTopSubcategories(maxCategories).map { it.toCategoryCard() }
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            categoriesSection,
                            cats,
                            isHorizontalImages = false
                        )
                    ),
                    hasNext = false
                )
            }
            else -> {
                val langCode = request.data.substringAfter("streams:", missingDelimiterValue = "")
                    .ifBlank { null }
                val streams = if (langCode == null) {
                    fetchLivestreams(maxStreams)
                } else {
                    fetchLivestreamsFiltered(langFetchLimit, langCode).take(maxStreams)
                }.map { it.toStreamCard() }
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name,
                            streams,
                            isHorizontalImages = isHorizontal
                        )
                    ),
                    hasNext = false
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleaned = url.trim()
        val categorySlug = parseCategorySlug(cleaned)
        if (categorySlug != null) return loadCategory(categorySlug)

        val channelSlug = parseChannelSlug(cleaned)
        if (channelSlug != null) return loadChannel(channelSlug)

        val login = cleaned.substringAfterLast("/").substringBefore("?").lowercase(Locale.ROOT)
        if (login.isNotBlank() && !login.startsWith("__discover_")) {
            return loadChannel(login)
        }
        throw RuntimeException("Unrecognized Kick URL")
    }

    private fun parseCategorySlug(url: String): String? {
        val markers = listOf(categoryPath, "/__discover_category__/")
        for (marker in markers) {
            if (url.contains(marker)) {
                return url.substringAfter(marker).substringBefore("/").substringBefore("?").ifBlank { null }
            }
        }
        return null
    }

    private fun parseChannelSlug(url: String): String? {
        val markers = listOf(channelPath, "/__discover_channel__/")
        for (marker in markers) {
            if (url.contains(marker)) {
                return url.substringAfter(marker)
                    .substringBefore("/")
                    .substringBefore("?")
                    .lowercase(Locale.ROOT)
                    .ifBlank { null }
            }
        }
        return null
    }

    private fun channelPageUrl(slug: String): String = "$mainUrl$channelPath$slug"

    private fun liveChannelUrl(slug: String): String = "https://kick.com/$slug"

    private fun videoUrl(uuid: String): String = "https://kick.com/video/$uuid"

    private fun clipUrl(clipId: String): String = "https://kick.com/clip/$clipId"

    private suspend fun loadCategory(slug: String): LoadResponse {
        val meta = fetchSubcategory(slug)
        val streams = fetchLivestreams(maxStreams, subcategory = slug)
        val banner = meta?.bannerUrl()

        val episodes = streams.mapIndexed { index, stream ->
            val channelSlug = stream.channel?.slug.orEmpty()
            val display = stream.channel?.user?.username?.ifBlank { channelSlug } ?: channelSlug
            val title = stream.sessionTitle?.trim().orEmpty()
            val viewers = stream.viewerCount
            val epName = buildString {
                append(display)
                if (viewers != null) append(" · ").append(formatViewers(viewers))
                if (title.isNotBlank()) {
                    append('\n')
                    append(title.truncate(70))
                }
            }
            newEpisode(liveChannelUrl(channelSlug)) {
                this.name = epName
                this.posterUrl = stream.thumbnail?.src
                this.episode = index + 1
                this.season = 1
                this.description = title.ifBlank { null }
            }
        }

        val name = meta?.name ?: slug
        val viewers = meta?.viewers
        return newTvSeriesLoadResponse(
            name,
            "$mainUrl$categoryPath$slug",
            TvType.TvSeries,
            episodes
        ) {
            plot = buildString {
                append("Live streams in this Kick category")
                viewers?.let { append(" · ${formatViewers(it)} watching") }
            }
            posterUrl = banner
            backgroundPosterUrl = banner
            tags = listOfNotNull(
                "Category",
                "Live",
                viewers?.let { formatViewers(it) + " viewers" },
                "${episodes.size} live"
            )
            recommendations = streams.map { it.toStreamCard() }
        }
    }

    private suspend fun loadChannel(slug: String): LoadResponse {
        val channel = fetchChannel(slug)
            ?: throw RuntimeException("Could not load channel")

        val display = channel.user?.username?.ifBlank { slug } ?: slug
        val livestream = channel.livestream
        val profile = channel.user?.profilePic ?: channel.user?.profilepic
        val liveThumb = livestream?.thumbnail?.src
            ?: runCatching {
                fetchChannelLivestream(slug)?.thumbnail?.src
            }.getOrNull()
        val vods = fetchChannelVideos(slug).filter { it.isLive != true }.take(maxChannelVods)
        val clips = fetchChannelClips(slug).take(maxChannelClips)

        val seasonLive = if (livestream != null && livestream.isLive != false) 1 else 0
        val seasonVod = when {
            vods.isEmpty() -> 0
            seasonLive > 0 -> 2
            else -> 1
        }
        val seasonClip = when {
            clips.isEmpty() -> 0
            seasonVod > 0 -> seasonVod + 1
            seasonLive > 0 -> 2
            else -> 1
        }

        val episodes = buildList {
            if (livestream != null && livestream.isLive != false) {
                val title = livestream.sessionTitle?.trim().orEmpty()
                val viewers = livestream.viewerCount
                val epName = buildString {
                    append("LIVE")
                    if (viewers != null) append(" · ").append(formatViewers(viewers))
                    if (title.isNotBlank()) {
                        append('\n')
                        append(title.truncate(70))
                    }
                }
                val catName = livestream.categories?.firstOrNull()?.name
                add(
                    newEpisode(liveChannelUrl(slug)) {
                        this.name = epName
                        this.posterUrl = liveThumb ?: profile
                        this.episode = 1
                        this.season = seasonLive
                        this.description = buildString {
                            append(title)
                            catName?.let {
                                if (isNotEmpty()) append('\n')
                                append("Playing: $it")
                            }
                        }.ifBlank { null }
                    }
                )
            }

            vods.forEachIndexed { index, vod ->
                val uuid = vod.video?.uuid?.ifBlank { null } ?: return@forEachIndexed
                val title = vod.sessionTitle?.trim().orEmpty().ifBlank { "VOD" }
                val views = vod.views ?: vod.video?.views
                val durMs = vod.duration
                val dur = durMs?.let { formatDuration(it / 1000) }
                val epName = buildString {
                    append("VOD")
                    if (views != null) append(" · ").append(formatViewers(views))
                    if (dur != null) append(" · ").append(dur)
                    append('\n')
                    append(title.truncate(70))
                }
                add(
                    newEpisode(videoUrl(uuid)) {
                        this.name = epName
                        this.posterUrl = vod.thumbnail?.src
                        this.episode = index + 1
                        this.season = seasonVod
                        this.description = title.ifBlank { null }
                    }
                )
            }

            clips.forEachIndexed { index, clip ->
                val id = clip.id?.ifBlank { null } ?: return@forEachIndexed
                val title = clip.title?.trim().orEmpty().ifBlank { "Clip" }
                val views = clip.views ?: clip.viewCount
                val dur = clip.duration?.let { formatDuration(it.toLong()) }
                val epName = buildString {
                    append("Clip")
                    if (views != null) append(" · ").append(formatViewers(views))
                    if (dur != null) append(" · ").append(dur)
                    append('\n')
                    append(title.truncate(70))
                }
                val playData = clip.clipUrl?.takeIf { it.contains(".m3u8") }
                    ?: clip.videoUrl?.takeIf { it.contains(".m3u8") }
                    ?: clipUrl(id)
                add(
                    newEpisode(playData) {
                        this.name = epName
                        this.posterUrl = clip.thumbnailUrl
                        this.episode = index + 1
                        this.season = seasonClip
                        this.description = title.ifBlank { null }
                    }
                )
            }

            if (isEmpty()) {
                add(
                    newEpisode(liveChannelUrl(slug)) {
                        this.name = "Offline"
                        this.posterUrl = profile
                        this.episode = 1
                        this.season = 1
                        this.description = "Channel is offline"
                    }
                )
            }
        }

        val isLive = livestream != null && livestream.isLive != false
        val tags = listOfNotNull(
            if (isLive) "Live" else "Offline",
            livestream?.categories?.firstOrNull()?.name,
            livestream?.language,
            livestream?.viewerCount?.let { formatViewers(it) + " watching" },
            if (vods.isNotEmpty()) "${vods.size} VODs" else null,
            if (clips.isNotEmpty()) "${clips.size} clips" else null,
        )
        val plot = buildString {
            channel.user?.bio?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it) }
            livestream?.sessionTitle?.let {
                if (isNotEmpty()) append("\n\n")
                append("Now: $it")
            }
            livestream?.categories?.firstOrNull()?.name?.let {
                if (isNotEmpty()) append("\n")
                append("Playing: $it")
            }
            if (!isLive && isEmpty()) append("Channel is offline")
        }

        return newTvSeriesLoadResponse(
            display,
            channelPageUrl(slug),
            TvType.TvSeries,
            episodes
        ) {
            this.plot = plot
            posterUrl = liveThumb ?: profile ?: ""
            backgroundPosterUrl = liveThumb ?: profile
            this@newTvSeriesLoadResponse.tags = tags
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (q.length < 3) {
            // Kick search requires ≥3 chars; try direct channel slug lookup
            val channel = fetchChannel(q.lowercase(Locale.ROOT)) ?: return emptyList()
            val slug = channel.slug ?: q
            val live = channel.livestream
            return listOf(
                if (live != null && live.isLive != false) {
                    live.toStreamCard(slug, channel.user?.username)
                } else {
                    newLiveSearchResponse(
                        "${channel.user?.username ?: slug} · Offline",
                        channelPageUrl(slug),
                        TvType.Live,
                        fix = false
                    ) {
                        posterUrl = channel.user?.profilePic ?: channel.user?.profilepic.orEmpty()
                    }
                }
            )
        }

        val res = runCatching {
            kickGet("https://kick.com/api/search?searched_word=${java.net.URLEncoder.encode(q, "UTF-8")}")
                .parsed<SearchResult>()
        }.getOrNull() ?: return emptyList()

        val cats = res.categories.orEmpty().take(12).map { it.toCategoryCard() }

        val channels = res.channels.orEmpty().take(20).mapNotNull { ch ->
            val slug = ch.slug?.ifBlank { null } ?: return@mapNotNull null
            val display = ch.user?.username?.ifBlank { slug } ?: slug
            if (ch.isLive == true) {
                newLiveSearchResponse(
                    "$display · Live",
                    channelPageUrl(slug),
                    TvType.Live,
                    fix = false
                ) {
                    posterUrl = ch.user?.profilePic ?: ch.user?.profilepic.orEmpty()
                }
            } else {
                newLiveSearchResponse(
                    "$display · Offline",
                    channelPageUrl(slug),
                    TvType.Live,
                    fix = false
                ) {
                    posterUrl = ch.user?.profilePic ?: ch.user?.profilepic.orEmpty()
                }
            }
        }

        return (cats + channels).distinctBy { it.url.lowercase(Locale.ROOT) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("__discover_category__") || data.contains("__discover_channel__")) {
            return false
        }
        if (!data.contains("kick.com") && !data.contains(".m3u8")) return false
        var ok = false
        KickExtractor().getUrl(data, null, subtitleCallback) { link ->
            ok = true
            callback(link)
        }
        return ok
    }

    // --- Kick HTTP ---

    private suspend fun kickGet(url: String) = app.get(url, headers = kickHeaders)

    private suspend fun fetchLivestreams(
        limit: Int,
        subcategory: String? = null,
        languagePath: String = "en"
    ): List<LivestreamNode> {
        val params = mutableMapOf(
            "page" to "1",
            "limit" to limit.toString(),
            "sort" to "desc",
        )
        if (!subcategory.isNullOrBlank()) params["subcategory"] = subcategory
        return runCatching {
            app.get(
                "https://kick.com/stream/livestreams/$languagePath",
                headers = kickHeaders,
                params = params
            ).parsed<LivestreamPage>().data.orEmpty()
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchLivestreamsFiltered(limit: Int, langCode: String): List<LivestreamNode> {
        val wanted = languageNames(langCode)
        return fetchLivestreams(limit).filter { node ->
            val lang = node.language?.trim().orEmpty()
            wanted.any { w -> lang.equals(w, ignoreCase = true) || lang.startsWith(w, ignoreCase = true) }
        }
    }

    private suspend fun fetchTopSubcategories(limit: Int): List<SubcategoryNode> {
        return runCatching {
            app.get(
                "https://kick.com/api/v1/subcategories",
                headers = kickHeaders,
                params = mapOf("limit" to limit.toString(), "page" to "1")
            ).parsed<SubcategoryPage>().data.orEmpty()
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchSubcategory(slug: String): SubcategoryNode? {
        return runCatching {
            kickGet("https://kick.com/api/v1/subcategories/$slug").parsed<SubcategoryNode>()
        }.getOrNull()
    }

    private suspend fun fetchChannel(slug: String): ChannelDetail? {
        return runCatching {
            kickGet("https://kick.com/api/v2/channels/$slug").parsed<ChannelDetail>()
        }.getOrNull()
    }

    private suspend fun fetchChannelLivestream(slug: String): ChannelLivestreamData? {
        return runCatching {
            kickGet("https://kick.com/api/v2/channels/$slug/livestream")
                .parsed<ChannelLivestreamWrap>().data
        }.getOrNull()
    }

    private suspend fun fetchChannelVideos(slug: String): List<VideoNode> {
        return runCatching {
            kickGet("https://kick.com/api/v2/channels/$slug/videos")
                .parsed<Array<VideoNode>>().toList()
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchChannelClips(slug: String): List<ClipNode> {
        return runCatching {
            kickGet("https://kick.com/api/v2/channels/$slug/clips")
                .parsed<ClipsWrap>().clips.orEmpty()
        }.getOrDefault(emptyList())
    }

    // --- mapping ---

    private fun LivestreamNode.toStreamCard(): LiveSearchResponse {
        val slug = channel?.slug.orEmpty()
        val display = channel?.user?.username?.ifBlank { slug } ?: slug
        return toStreamCard(slug, display)
    }

    private fun LivestreamNode.toStreamCard(slug: String, displayName: String?): LiveSearchResponse {
        val display = displayName?.ifBlank { slug } ?: slug
        val title = sessionTitle?.trim().orEmpty()
        val viewers = viewerCount
        val line1 = buildString {
            append(display)
            if (viewers != null) append(" · ").append(formatViewers(viewers))
        }
        val name = if (title.isNotBlank()) {
            "$line1\n${title.truncate(70)}"
        } else line1

        return newLiveSearchResponse(
            name,
            channelPageUrl(slug),
            TvType.Live,
            fix = false
        ) {
            posterUrl = thumbnail?.src.orEmpty()
        }
    }

    private fun ChannelLivestream.toStreamCard(slug: String, displayName: String?): LiveSearchResponse {
        return LivestreamNode(
            sessionTitle = sessionTitle,
            language = language,
            viewerCount = viewerCount,
            isLive = isLive,
            thumbnail = thumbnail,
            channel = ChannelRef(slug = slug, user = UserRef(username = displayName)),
            categories = categories,
        ).toStreamCard(slug, displayName)
    }

    private fun SubcategoryNode.toCategoryCard(): LiveSearchResponse {
        val viewers = this.viewers
        val label = buildString {
            append(name.orEmpty())
            if (viewers != null) append(" · ").append(formatViewers(viewers))
        }
        return newLiveSearchResponse(
            label,
            "$mainUrl$categoryPath${slug.orEmpty()}",
            TvType.Live,
            fix = false
        ) {
            posterUrl = bannerUrl().orEmpty()
        }
    }

    private fun SubcategoryNode.bannerUrl(): String? {
        return banner?.url
            ?: banner?.src
            ?: banner?.srcset?.substringBefore(" ")?.ifBlank { null }
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

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    // --- models ---

    data class LivestreamPage(
        @JsonProperty("data") val data: List<LivestreamNode>? = null,
    )

    data class LivestreamNode(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("viewer_count") val viewerCount: Long? = null,
        @JsonProperty("is_live") val isLive: Boolean? = null,
        @JsonProperty("thumbnail") val thumbnail: Thumb? = null,
        @JsonProperty("channel") val channel: ChannelRef? = null,
        @JsonProperty("categories") val categories: List<CategoryRef>? = null,
    )

    data class Thumb(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("srcset") val srcset: String? = null,
    )

    data class ChannelRef(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("playback_url") val playbackUrl: String? = null,
        @JsonProperty("user") val user: UserRef? = null,
    )

    data class UserRef(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("bio") val bio: String? = null,
        @JsonProperty("profilepic") val profilepic: String? = null,
        @JsonProperty("profilePic") val profilePic: String? = null,
    )

    data class CategoryRef(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
    )

    data class SubcategoryPage(
        @JsonProperty("data") val data: List<SubcategoryNode>? = null,
    )

    data class SubcategoryNode(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("viewers") val viewers: Long? = null,
        @JsonProperty("banner") val banner: Banner? = null,
    )

    data class Banner(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("srcset") val srcset: String? = null,
        @JsonProperty("responsive") val responsive: String? = null,
    )

    data class ChannelDetail(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("playback_url") val playbackUrl: String? = null,
        @JsonProperty("livestream") val livestream: ChannelLivestream? = null,
        @JsonProperty("user") val user: UserRef? = null,
    )

    data class ChannelLivestream(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("is_live") val isLive: Boolean? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("viewer_count") val viewerCount: Long? = null,
        @JsonProperty("thumbnail") val thumbnail: Thumb? = null,
        @JsonProperty("categories") val categories: List<CategoryRef>? = null,
    )

    data class ChannelLivestreamWrap(
        @JsonProperty("data") val data: ChannelLivestreamData? = null,
    )

    data class ChannelLivestreamData(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("playback_url") val playbackUrl: String? = null,
        @JsonProperty("viewers") val viewers: Long? = null,
        @JsonProperty("thumbnail") val thumbnail: Thumb? = null,
        @JsonProperty("language") val language: String? = null,
    )

    data class VideoNode(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("is_live") val isLive: Boolean? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("viewer_count") val viewerCount: Long? = null,
        @JsonProperty("views") val views: Long? = null,
        @JsonProperty("thumbnail") val thumbnail: Thumb? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("video") val video: VideoMeta? = null,
    )

    data class VideoMeta(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("uuid") val uuid: String? = null,
        @JsonProperty("views") val views: Long? = null,
        @JsonProperty("source") val source: String? = null,
    )

    data class ClipsWrap(
        @JsonProperty("clips") val clips: List<ClipNode>? = null,
    )

    data class ClipNode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("clip_url") val clipUrl: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
        @JsonProperty("duration") val duration: Double? = null,
        @JsonProperty("views") val views: Long? = null,
        @JsonProperty("view_count") val viewCount: Long? = null,
        @JsonProperty("video_url") val videoUrl: String? = null,
    )

    data class SearchResult(
        @JsonProperty("channels") val channels: List<SearchChannel>? = null,
        @JsonProperty("categories") val categories: List<SubcategoryNode>? = null,
    )

    data class SearchChannel(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("isLive") val isLive: Boolean? = null,
        @JsonProperty("user") val user: UserRef? = null,
    )

    companion object {
        private const val LANG_PREF_KEY = "KickDiscover_home_languages"

        /** ISO-ish codes used in settings; mapped to Kick language display names. */
        val ALL_HOME_LANGUAGES = listOf(
            "en", "ar", "es", "pt", "fr", "de", "ja", "ko", "ru", "it", "tr", "pl"
        )
        val DEFAULT_HOME_LANGUAGES = setOf("en", "ar", "es", "pt", "fr", "de")

        fun languageLabel(code: String): String = when (code.lowercase(Locale.ROOT)) {
            "en" -> "English"
            "ar" -> "Arabic"
            "es" -> "Spanish"
            "pt" -> "Portuguese"
            "fr" -> "French"
            "de" -> "German"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            "it" -> "Italian"
            "tr" -> "Turkish"
            "pl" -> "Polish"
            else -> code
        }

        fun languageNames(code: String): List<String> = when (code.lowercase(Locale.ROOT)) {
            "en" -> listOf("English")
            "ar" -> listOf("Arabic")
            "es" -> listOf("Spanish")
            "pt" -> listOf("Portuguese")
            "fr" -> listOf("French")
            "de" -> listOf("German")
            "ja" -> listOf("Japanese")
            "ko" -> listOf("Korean")
            "ru" -> listOf("Russian")
            "it" -> listOf("Italian")
            "tr" -> listOf("Turkish")
            "pl" -> listOf("Polish")
            else -> listOf(languageLabel(code))
        }

        private fun prefs(): SharedPreferences? {
            return try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
                app?.getSharedPreferences("KickDiscover", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }

        fun getEnabledHomeLanguages(): Set<String> {
            val raw = prefs()?.getString(LANG_PREF_KEY, null)
            if (raw.isNullOrBlank()) return DEFAULT_HOME_LANGUAGES
            val parsed = raw.split(',')
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toSet()
            return parsed.ifEmpty { DEFAULT_HOME_LANGUAGES }
        }

        fun setEnabledHomeLanguages(langs: Set<String>) {
            val value = langs.map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("en") }
                .joinToString(",")
            prefs()?.edit()?.putString(LANG_PREF_KEY, value)?.apply()
        }
    }

    class KickExtractor : ExtractorApi() {
        override val mainUrl = "https://kick.com/"
        override val name = "Kick"
        override val requiresReferer = false

        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json",
            "Referer" to "https://kick.com/",
            "Origin" to "https://kick.com",
        )

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url.contains("__discover_")) return

            // Direct HLS (clips sometimes stored this way)
            if (url.contains(".m3u8")) {
                emitHls(url, callback)
                return
            }

            val videoUuid = Regex("""kick\.com/video/([0-9a-fA-F-]{36})""")
                .find(url)?.groupValues?.getOrNull(1)
            if (videoUuid != null) {
                val video = runCatching {
                    app.get(
                        "https://kick.com/api/v1/video/$videoUuid",
                        headers = headers
                    ).parsed<VideoApiResponse>()
                }.getOrNull()
                val source = video?.source ?: video?.livestream?.source
                if (!source.isNullOrBlank()) {
                    emitHls(source, callback)
                }
                return
            }

            val clipId = Regex("""kick\.com/clip/([^/?#]+)""")
                .find(url)?.groupValues?.getOrNull(1)
            if (clipId != null) {
                // Clips playlist is predictable when we only have the id from episode data;
                // prefer re-resolving via a known clip_url pattern is unreliable, so try
                // channel-less: clips are hosted at clips.kick.com — we need the clip_url.
                // Episode data should be kick.com/clip/{id}; attempt common playlist paths
                // is not reliable. Fall back: search is unused — store resolved at load time
                // by also accepting video_url query. For v1, try clip page JSON if any.
                // Practical approach: fetch won't work without shard; require m3u8 in data
                // OR use video_url from a lightweight cache. Simplest fix: episode data for
                // clips is the clip_url m3u8 (handled above). If we reach here with clip id,
                // try Kick's undocumented clip endpoint.
                val tried = listOf(
                    "https://kick.com/api/v2/clips/$clipId",
                    "https://clips.kick.com/$clipId",
                )
                for (u in tried) {
                    val body = runCatching { app.get(u, headers = headers).text }.getOrNull()
                    val m3u8 = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
                        .find(body.orEmpty())?.value
                    if (!m3u8.isNullOrBlank()) {
                        emitHls(m3u8, callback)
                        return
                    }
                }
                return
            }

            // Channel live: https://kick.com/{slug}
            val slug = url.trimEnd('/')
                .substringAfter("kick.com/")
                .substringBefore("/")
                .substringBefore("?")
                .lowercase(Locale.ROOT)
            if (slug.isBlank() || slug.startsWith("api") || slug.startsWith("video") || slug.startsWith("clip")) {
                return
            }

            val live = runCatching {
                app.get(
                    "https://kick.com/api/v2/channels/$slug/livestream",
                    headers = headers
                ).parsed<ChannelLivestreamWrap>().data
            }.getOrNull()

            val playback = live?.playbackUrl
                ?: runCatching {
                    app.get(
                        "https://kick.com/api/v2/channels/$slug",
                        headers = headers
                    ).parsed<ChannelDetail>().playbackUrl
                }.getOrNull()

            if (!playback.isNullOrBlank()) {
                emitHls(playback, callback)
            }
        }

        private suspend fun emitHls(mediaUrl: String, callback: (ExtractorLink) -> Unit) {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    mediaUrl
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = "https://kick.com/"
                }
            )
        }

        data class VideoApiResponse(
            @JsonProperty("uuid") val uuid: String? = null,
            @JsonProperty("source") val source: String? = null,
            @JsonProperty("livestream") val livestream: VideoLivestream? = null,
        )

        data class VideoLivestream(
            @JsonProperty("source") val source: String? = null,
        )
    }
}
