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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Twitch Discover — GQL-backed browse with stream titles, live previews,
 * category directory, and channel pages (Live + VODs + clips).
 */
class TwitchProvider : MainAPI() {
    override var mainUrl = "https://www.twitch.tv"
    override var name = "Twitch Discover"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxStreams = 24
    private val maxCategories = 30
    private val maxChannelVods = 12
    private val maxChannelClips = 12

    /** Path-based so CloudStream won't mangle a custom scheme into mainUrl/…. */
    private val categoryPath = "/__discover_category__/"
    /** Channel detail pages (TvSeries). Distinct from real /{login} used for HLS. */
    private val channelPath = "/__discover_channel__/"

    private val categoriesSection = "Category directory"

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
                val games = fetchTopGames(maxCategories).map { it.toCategoryCard() }
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            categoriesSection,
                            games,
                            isHorizontalImages = false // box art is portrait
                        )
                    ),
                    hasNext = false
                )
            }
            else -> {
                val lang = request.data.substringAfter("streams:", missingDelimiterValue = "")
                    .ifBlank { null }
                val streams = fetchTopStreams(maxStreams, language = lang)
                    .map { it.toStreamCard() }
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
        val categoryId = parseCategoryId(cleaned)
        if (categoryId != null) return loadCategory(categoryId)

        val channelLogin = parseChannelLogin(cleaned)
        if (channelLogin != null) return loadChannel(channelLogin)

        // Fallback: last path segment as login (legacy / bare logins)
        val login = cleaned.substringAfterLast("/").substringBefore("?").lowercase(Locale.ROOT)
        if (login.isNotBlank() && !login.startsWith("__discover_")) {
            return loadChannel(login)
        }
        throw RuntimeException("Unrecognized Twitch URL")
    }

    private fun parseCategoryId(url: String): String? {
        val markers = listOf(categoryPath, "twitch-category:", "/__discover_category__/")
        for (marker in markers) {
            if (url.contains(marker)) {
                return url.substringAfter(marker).substringBefore("/").substringBefore("?").ifBlank { null }
            }
        }
        return null
    }

    private fun parseChannelLogin(url: String): String? {
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

    private fun channelPageUrl(login: String): String = "$mainUrl$channelPath$login"

    private fun liveChannelUrl(login: String): String = "https://www.twitch.tv/$login"

    private suspend fun loadCategory(gameId: String): LoadResponse {
        val game = fetchGameWithStreams(gameId, maxStreams)
            ?: throw RuntimeException("Could not load category")

        val boxArt = boxArtUrl(game.boxArtURL)

        // TvType.Live hid the episode list in CloudStream. TvSeries shows episodes
        // as a proper list; each episode poster is the live_user stream thumbnail.
        // Episode data is the real channel URL so loadLinks plays HLS directly.
        val episodes = game.streams.mapIndexed { index, stream ->
            val login = stream.broadcaster?.login.orEmpty()
            val display = stream.broadcaster?.displayName?.ifBlank { login } ?: login
            val title = stream.title?.trim().orEmpty()
            val viewers = stream.viewersCount
            val epName = buildString {
                append(display)
                if (viewers != null) append(" · ").append(formatViewers(viewers))
                if (title.isNotBlank()) {
                    append('\n')
                    append(title.truncate(70))
                }
            }
            newEpisode(liveChannelUrl(login)) {
                this.name = epName
                this.posterUrl = previewUrl(login)
                this.episode = index + 1
                this.season = 1
                this.description = title.ifBlank { null }
            }
        }

        val streamCards = game.streams.map { it.toStreamCard() }

        return newTvSeriesLoadResponse(
            game.name,
            "$mainUrl$categoryPath$gameId",
            TvType.TvSeries,
            episodes
        ) {
            plot = buildString {
                append("Live streams in this Twitch category")
                game.viewersCount?.let { append(" · ${formatViewers(it)} watching") }
            }
            posterUrl = boxArt
            backgroundPosterUrl = boxArt
            tags = listOfNotNull(
                "Category",
                "Live",
                game.viewersCount?.let { formatViewers(it) + " viewers" },
                "${episodes.size} live"
            )
            recommendations = streamCards
        }
    }

    private suspend fun loadChannel(login: String): LoadResponse {
        val user = fetchChannelDetail(login, maxChannelVods, maxChannelClips)
            ?: throw RuntimeException("Could not load channel")

        val display = user.displayName?.ifBlank { login } ?: login
        val stream = user.stream
        val profile = user.profileImageURL?.ifBlank { null }
        val livePreview = if (stream != null) previewUrl(login) else null
        val vods = user.videos
        val clips = user.clips

        val seasonLive = if (stream != null) 1 else 0
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
            if (stream != null) {
                val title = stream.title?.trim().orEmpty()
                val viewers = stream.viewersCount
                val epName = buildString {
                    append("LIVE")
                    if (viewers != null) append(" · ").append(formatViewers(viewers))
                    if (title.isNotBlank()) {
                        append('\n')
                        append(title.truncate(70))
                    }
                }
                add(
                    newEpisode(liveChannelUrl(login)) {
                        this.name = epName
                        this.posterUrl = livePreview
                        this.episode = 1
                        this.season = seasonLive
                        this.description = buildString {
                            append(title)
                            stream.game?.name?.let {
                                if (isNotEmpty()) append('\n')
                                append("Playing: $it")
                            }
                        }.ifBlank { null }
                    }
                )
            }

            vods.forEachIndexed { index, vod ->
                val id = vod.id ?: return@forEachIndexed
                val title = vod.title?.trim().orEmpty().ifBlank { "VOD" }
                val views = vod.viewCount
                val dur = vod.lengthSeconds?.let { formatDuration(it) }
                val epName = buildString {
                    append("VOD")
                    if (views != null) append(" · ").append(formatViewers(views))
                    if (dur != null) append(" · ").append(dur)
                    append('\n')
                    append(title.truncate(70))
                }
                add(
                    newEpisode("https://www.twitch.tv/videos/$id") {
                        this.name = epName
                        this.posterUrl = vodThumbUrl(vod.previewThumbnailURL)
                        this.episode = index + 1
                        this.season = seasonVod
                        this.description = title.ifBlank { null }
                    }
                )
            }

            clips.forEachIndexed { index, clip ->
                val slug = clip.slug?.ifBlank { null } ?: return@forEachIndexed
                val title = clip.title?.trim().orEmpty().ifBlank { "Clip" }
                val views = clip.viewCount
                val dur = clip.durationSeconds?.let { formatDuration(it.toLong()) }
                val epName = buildString {
                    append("Clip")
                    if (views != null) append(" · ").append(formatViewers(views))
                    if (dur != null) append(" · ").append(dur)
                    append('\n')
                    append(title.truncate(70))
                }
                add(
                    newEpisode("https://www.twitch.tv/$login/clip/$slug") {
                        this.name = epName
                        this.posterUrl = clip.thumbnailURL
                        this.episode = index + 1
                        this.season = seasonClip
                        this.description = title.ifBlank { null }
                    }
                )
            }

            if (isEmpty()) {
                add(
                    newEpisode(liveChannelUrl(login)) {
                        this.name = "Offline"
                        this.posterUrl = profile
                        this.episode = 1
                        this.season = 1
                        this.description = "Channel is offline"
                    }
                )
            }
        }

        val tags = listOfNotNull(
            if (stream != null) "Live" else "Offline",
            stream?.game?.name,
            stream?.language,
            stream?.viewersCount?.let { formatViewers(it) + " watching" },
            if (vods.isNotEmpty()) "${vods.size} VODs" else null,
            if (clips.isNotEmpty()) "${clips.size} clips" else null,
        )
        val plot = buildString {
            user.description?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it) }
            stream?.title?.let {
                if (isNotEmpty()) append("\n\n")
                append("Now: $it")
            }
            stream?.game?.name?.let {
                if (isNotEmpty()) append("\n")
                append("Playing: $it")
            }
            if (stream == null && isEmpty()) append("Channel is offline")
        }

        return newTvSeriesLoadResponse(
            display,
            channelPageUrl(login),
            TvType.TvSeries,
            episodes
        ) {
            this.plot = plot
            posterUrl = livePreview ?: profile ?: ""
            backgroundPosterUrl = livePreview ?: profile
            this@newTvSeriesLoadResponse.tags = tags
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val games = fetchTopGames(100)
            .filter { it.name?.contains(q, ignoreCase = true) == true }
            .take(12)
            .map { it.toCategoryCard() }

        // Channel search via tracker, then enrich titles from GQL
        val trackerLogins = runCatching {
            app.get(
                "https://twitchtracker.com/search",
                params = mapOf("q" to q),
                referer = "https://twitchtracker.com"
            ).document.select("table.tops tr a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("/games") || href.startsWith("/languages")) null
                else href.substringAfterLast('/').ifBlank { null }
            }.distinct().take(20)
        }.getOrDefault(emptyList())

        val channels = if (trackerLogins.isEmpty()) {
            emptyList()
        } else {
            fetchUsers(trackerLogins).mapNotNull { user ->
                val login = user.login?.ifBlank { null } ?: return@mapNotNull null
                if (user.stream != null) {
                    user.toStreamCard()
                } else {
                    newLiveSearchResponse(
                        "${user.displayName ?: login} · Offline",
                        channelPageUrl(login),
                        TvType.Live,
                        fix = false
                    ) { posterUrl = user.profileImageURL.orEmpty() }
                }
            }
        }

        return (games + channels).distinctBy { it.url.lowercase(Locale.ROOT) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Discover marker URLs are detail pages, not playable streams
        if (data.contains("__discover_category__") || data.contains("__discover_channel__")) {
            return false
        }
        if (!data.contains("twitch.tv")) return false
        return loadExtractor(data, subtitleCallback, callback)
    }

    // --- GQL ---

    private val gqlClientId = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private val gqlUrl = "https://gql.twitch.tv/gql"

    private suspend fun gql(query: String, variables: Map<String, Any?> = emptyMap()): GqlResponse {
        val body = mapOf(
            "query" to query,
            "variables" to variables
        )
        return app.post(
            gqlUrl,
            headers = mapOf(
                "Client-ID" to gqlClientId,
                "Content-Type" to "application/json",
            ),
            json = body
        ).parsed()
    }

    private suspend fun fetchTopStreams(first: Int, language: String? = null): List<StreamNode> {
        // Language must be a Twitch GraphQL Language enum (EN, AR, ES, …), inlined — not a variable.
        val options = if (language.isNullOrBlank()) {
            "{ sort: VIEWER_COUNT }"
        } else {
            "{ sort: VIEWER_COUNT, languages: [${language.uppercase(Locale.ROOT)}] }"
        }
        val res = gql(
            """
            query(${'$'}first: Int!) {
              streams(first: ${'$'}first, options: $options) {
                edges {
                  node {
                    title
                    language
                    viewersCount
                    previewImageURL
                    broadcaster { login displayName }
                    game { id name boxArtURL }
                  }
                }
              }
            }
            """.trimIndent(),
            mapOf("first" to first)
        )
        return res.data?.streams?.edges.orEmpty().mapNotNull { it.node }
    }

    private suspend fun fetchTopGames(first: Int): List<GameNode> {
        val res = gql(
            """
            query(${'$'}first: Int!) {
              games(first: ${'$'}first) {
                edges {
                  node {
                    id
                    name
                    viewersCount
                    boxArtURL
                  }
                }
              }
            }
            """.trimIndent(),
            mapOf("first" to first)
        )
        return res.data?.games?.edges.orEmpty().mapNotNull { it.node }
    }

    private suspend fun fetchGameWithStreams(id: String, first: Int): GameWithStreams? {
        val res = gql(
            """
            query(${'$'}id: ID!, ${'$'}first: Int!) {
              game(id: ${'$'}id) {
                id
                name
                viewersCount
                boxArtURL
                streams(first: ${'$'}first, options: { sort: VIEWER_COUNT }) {
                  edges {
                    node {
                      title
                      language
                      viewersCount
                      previewImageURL
                      broadcaster { login displayName }
                      game { id name boxArtURL }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
            mapOf("id" to id, "first" to first)
        )
        val g = res.data?.game ?: return null
        return GameWithStreams(
            id = g.id.orEmpty(),
            name = g.name.orEmpty(),
            viewersCount = g.viewersCount,
            boxArtURL = g.boxArtURL,
            streams = g.streams?.edges.orEmpty().mapNotNull { it.node }
        )
    }

    private suspend fun fetchUsers(logins: List<String>): List<UserNode> {
        if (logins.isEmpty()) return emptyList()
        val res = gql(
            """
            query(${'$'}logins: [String!]!) {
              users(logins: ${'$'}logins) {
                login
                displayName
                profileImageURL(width: 300)
                stream {
                  title
                  language
                  viewersCount
                  previewImageURL
                  game { id name boxArtURL }
                }
              }
            }
            """.trimIndent(),
            mapOf("logins" to logins)
        )
        return res.data?.users.orEmpty().filterNotNull()
    }

    private suspend fun fetchChannelDetail(
        login: String,
        vodFirst: Int,
        clipFirst: Int
    ): ChannelDetail? {
        val res = gql(
            """
            query(${'$'}login: String!, ${'$'}vodFirst: Int!, ${'$'}clipFirst: Int!) {
              user(login: ${'$'}login) {
                login
                displayName
                profileImageURL(width: 300)
                description
                stream {
                  title
                  language
                  viewersCount
                  previewImageURL
                  game { id name boxArtURL }
                }
                videos(first: ${'$'}vodFirst, type: ARCHIVE, sort: TIME) {
                  edges {
                    node {
                      id
                      title
                      previewThumbnailURL
                      lengthSeconds
                      createdAt
                      viewCount
                    }
                  }
                }
                clips(first: ${'$'}clipFirst) {
                  edges {
                    node {
                      id
                      slug
                      title
                      thumbnailURL
                      durationSeconds
                      viewCount
                      createdAt
                    }
                  }
                }
              }
            }
            """.trimIndent(),
            mapOf("login" to login, "vodFirst" to vodFirst, "clipFirst" to clipFirst)
        )
        val u = res.data?.user ?: return null
        return ChannelDetail(
            login = u.login ?: login,
            displayName = u.displayName,
            profileImageURL = u.profileImageURL,
            description = u.description,
            stream = u.stream,
            videos = u.videos?.edges.orEmpty().mapNotNull { it.node },
            clips = u.clips?.edges.orEmpty().mapNotNull { it.node },
        )
    }

    // --- mapping ---

    private fun StreamNode.toStreamCard(): LiveSearchResponse {
        val login = broadcaster?.login.orEmpty()
        val display = broadcaster?.displayName?.ifBlank { login } ?: login
        val title = this.title?.trim().orEmpty()
        val viewers = viewersCount
        val line1 = buildString {
            append(display)
            if (viewers != null) append(" · ").append(formatViewers(viewers))
        }
        val name = if (title.isNotBlank()) {
            "$line1\n${title.truncate(70)}"
        } else line1

        return newLiveSearchResponse(
            name,
            channelPageUrl(login),
            TvType.Live,
            fix = false
        ) {
            posterUrl = previewUrl(login)
        }
    }

    private fun UserNode.toStreamCard(): LiveSearchResponse {
        val s = stream!!
        return StreamNode(
            title = s.title,
            language = s.language,
            viewersCount = s.viewersCount,
            previewImageURL = s.previewImageURL,
            broadcaster = Broadcaster(login = login, displayName = displayName),
            game = s.game
        ).toStreamCard()
    }

    private fun GameNode.toCategoryCard(): LiveSearchResponse {
        val viewers = viewersCount
        val label = buildString {
            append(name.orEmpty())
            if (viewers != null) append(" · ").append(formatViewers(viewers))
        }
        return newLiveSearchResponse(
            label,
            "$mainUrl$categoryPath${id.orEmpty()}",
            TvType.Live,
            fix = false
        ) {
            posterUrl = boxArtUrl(boxArtURL)
        }
    }

    private fun previewUrl(login: String): String {
        val user = login.lowercase(Locale.ROOT)
        return "https://static-cdn.jtvnw.net/previews-ttv/live_user_${user}-440x248.jpg"
    }

    private fun boxArtUrl(template: String?): String {
        val t = template?.ifBlank { null }
            ?: return "https://static-cdn.jtvnw.net/ttv-static/404_boxart-285x380.jpg"
        return t.replace("{width}", "285").replace("{height}", "380")
    }

    private fun vodThumbUrl(template: String?): String? {
        val t = template?.ifBlank { null } ?: return null
        return t.replace("{width}", "440").replace("{height}", "248")
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

    // --- GQL models ---

    data class GqlResponse(
        @JsonProperty("data") val data: GqlData? = null,
    )

    data class GqlData(
        @JsonProperty("streams") val streams: EdgeList<StreamNode>? = null,
        @JsonProperty("games") val games: EdgeList<GameNode>? = null,
        @JsonProperty("game") val game: GameDetail? = null,
        @JsonProperty("users") val users: List<UserNode?>? = null,
        @JsonProperty("user") val user: UserDetailNode? = null,
    )

    data class EdgeList<T>(
        @JsonProperty("edges") val edges: List<Edge<T>>? = null,
    )

    data class Edge<T>(
        @JsonProperty("node") val node: T? = null,
    )

    data class StreamNode(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("viewersCount") val viewersCount: Long? = null,
        @JsonProperty("previewImageURL") val previewImageURL: String? = null,
        @JsonProperty("broadcaster") val broadcaster: Broadcaster? = null,
        @JsonProperty("game") val game: GameRef? = null,
    )

    data class Broadcaster(
        @JsonProperty("login") val login: String? = null,
        @JsonProperty("displayName") val displayName: String? = null,
    )

    data class GameRef(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("boxArtURL") val boxArtURL: String? = null,
    )

    data class GameNode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("viewersCount") val viewersCount: Long? = null,
        @JsonProperty("boxArtURL") val boxArtURL: String? = null,
    )

    data class GameDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("viewersCount") val viewersCount: Long? = null,
        @JsonProperty("boxArtURL") val boxArtURL: String? = null,
        @JsonProperty("streams") val streams: EdgeList<StreamNode>? = null,
    )

    data class GameWithStreams(
        val id: String,
        val name: String,
        val viewersCount: Long?,
        val boxArtURL: String?,
        val streams: List<StreamNode>,
    )

    data class UserNode(
        @JsonProperty("login") val login: String? = null,
        @JsonProperty("displayName") val displayName: String? = null,
        @JsonProperty("profileImageURL") val profileImageURL: String? = null,
        @JsonProperty("stream") val stream: UserStream? = null,
    )

    data class UserStream(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("viewersCount") val viewersCount: Long? = null,
        @JsonProperty("previewImageURL") val previewImageURL: String? = null,
        @JsonProperty("game") val game: GameRef? = null,
    )

    data class UserDetailNode(
        @JsonProperty("login") val login: String? = null,
        @JsonProperty("displayName") val displayName: String? = null,
        @JsonProperty("profileImageURL") val profileImageURL: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("stream") val stream: UserStream? = null,
        @JsonProperty("videos") val videos: EdgeList<VideoNode>? = null,
        @JsonProperty("clips") val clips: EdgeList<ClipNode>? = null,
    )

    data class ChannelDetail(
        val login: String,
        val displayName: String?,
        val profileImageURL: String?,
        val description: String?,
        val stream: UserStream?,
        val videos: List<VideoNode>,
        val clips: List<ClipNode>,
    )

    data class VideoNode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("previewThumbnailURL") val previewThumbnailURL: String? = null,
        @JsonProperty("lengthSeconds") val lengthSeconds: Long? = null,
        @JsonProperty("createdAt") val createdAt: String? = null,
        @JsonProperty("viewCount") val viewCount: Long? = null,
    )

    data class ClipNode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumbnailURL") val thumbnailURL: String? = null,
        @JsonProperty("durationSeconds") val durationSeconds: Double? = null,
        @JsonProperty("viewCount") val viewCount: Long? = null,
        @JsonProperty("createdAt") val createdAt: String? = null,
    )

    companion object {
        private const val LANG_PREF_KEY = "TwitchDiscover_home_languages"

        val ALL_HOME_LANGUAGES = listOf(
            "EN", "AR", "ES", "PT", "FR", "DE", "JA", "KO", "RU", "IT", "TR", "PL"
        )
        val DEFAULT_HOME_LANGUAGES = setOf("EN", "AR", "ES", "PT", "FR", "DE")

        fun languageLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
            "EN" -> "English"
            "AR" -> "Arabic"
            "ES" -> "Spanish"
            "PT" -> "Portuguese"
            "FR" -> "French"
            "DE" -> "German"
            "JA" -> "Japanese"
            "KO" -> "Korean"
            "RU" -> "Russian"
            "IT" -> "Italian"
            "TR" -> "Turkish"
            "PL" -> "Polish"
            else -> code
        }

        private fun prefs(): SharedPreferences? {
            return try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
                app?.getSharedPreferences("TwitchDiscover", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }

        fun getEnabledHomeLanguages(): Set<String> {
            val raw = prefs()?.getString(LANG_PREF_KEY, null)
            if (raw.isNullOrBlank()) return DEFAULT_HOME_LANGUAGES
            val parsed = raw.split(',').map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
            return parsed.ifEmpty { DEFAULT_HOME_LANGUAGES }
        }

        fun setEnabledHomeLanguages(langs: Set<String>) {
            val value = langs.map { it.uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("EN") }
                .joinToString(",")
            prefs()?.edit()?.putString(LANG_PREF_KEY, value)?.apply()
        }
    }

    class TwitchExtractor : ExtractorApi() {
        override val mainUrl = "https://twitch.tv/"
        override val name = "Twitch"
        override val requiresReferer = false

        data class ApiResponse(
            val success: Boolean,
            val urls: Map<String, String>?
        )

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val response =
                app.get("https://pwn.sh/tools/streamapi.py?url=$url").parsed<ApiResponse>()
            response.urls?.forEach { (name, mediaUrl) ->
                val quality = getQualityFromName(name.substringBefore("p"))
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "${this.name} ${name.replace("${quality}p", "")}",
                        mediaUrl
                    ) {
                        this.type = ExtractorLinkType.M3U8
                        this.quality = quality
                        this.referer = ""
                    }
                )
            }
        }
    }
}
