package recloudstream

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
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
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
 * and a category directory (game box art → live streams).
 */
class TwitchProvider : MainAPI() {
    override var mainUrl = "https://www.twitch.tv"
    override var name = "Twitch Discover"
    override val supportedTypes = setOf(TvType.Live)

    override var lang = "uni"
    override val hasMainPage = true

    private val isHorizontal = true
    private val maxStreams = 24
    private val maxCategories = 30
    private val categoryPrefix = "twitch-category:"

    private val categoriesSection = "Category directory"

    override val mainPage = mainPageOf(
        "streams" to "Top live worldwide",
        "streams:EN" to "Top English",
        "streams:AR" to "Top Arabic",
        "streams:ES" to "Top Spanish",
        "streams:PT" to "Top Portuguese",
        "streams:FR" to "Top French",
        "streams:DE" to "Top German",
        "categories" to categoriesSection,
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
                val fetchCount = if (lang == null) maxStreams else 80
                val streams = fetchTopStreams(fetchCount)
                    .filter { lang == null || it.language.equals(lang, ignoreCase = true) }
                    .take(maxStreams)
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
        return if (url.startsWith(categoryPrefix) || url.contains("/category/")) {
            loadCategory(url)
        } else {
            loadChannel(url)
        }
    }

    private suspend fun loadCategory(url: String): LoadResponse {
        val gameId = url.substringAfter(categoryPrefix)
            .substringAfterLast("/category/")
            .substringBefore("?")
            .ifBlank { throw RuntimeException("Missing category id") }

        val game = fetchGameWithStreams(gameId, maxStreams)
            ?: throw RuntimeException("Could not load category")

        val streamCards = game.streams.map { it.toStreamCard() }
        val boxArt = boxArtUrl(game.boxArtURL)

        return newLiveStreamLoadResponse(
            game.name,
            "$mainUrl/directory/category/${game.name}",
            // No single playable link for a category — first live stream if any
            game.streams.firstOrNull()?.broadcaster?.login?.let { "https://www.twitch.tv/$it" } ?: mainUrl
        ) {
            plot = buildString {
                append("Live category on Twitch")
                game.viewersCount?.let { append(" · ${formatViewers(it)} watching") }
                append("\n\nOpen a stream below (recommendations).")
            }
            posterUrl = boxArt
            backgroundPosterUrl = boxArt
            tags = listOfNotNull(
                "Category",
                game.viewersCount?.let { formatViewers(it) + " viewers" },
                "${streamCards.size} live"
            )
            recommendations = streamCards
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val login = url.substringAfterLast("/").substringBefore("?").ifBlank {
            throw RuntimeException("Missing channel")
        }
        val user = fetchUsers(listOf(login)).firstOrNull()
            ?: throw RuntimeException("Could not load channel")

        val stream = user.stream
        val tags = listOfNotNull(
            if (stream != null) "Live" else "Offline",
            stream?.game?.name,
            stream?.language,
            stream?.viewersCount?.let { formatViewers(it) + " watching" },
        )
        val plot = buildString {
            stream?.title?.let { append(it) }
            stream?.game?.name?.let {
                if (isNotEmpty()) append("\n")
                append("Playing: $it")
            }
            if (stream == null) append("Channel is offline")
        }
        val twitchUrl = "https://www.twitch.tv/$login"
        val preview = stream?.let { previewUrl(login) }

        return newLiveStreamLoadResponse(
            user.displayName ?: login,
            twitchUrl,
            twitchUrl
        ) {
            this.plot = plot
            posterUrl = preview ?: ""
            backgroundPosterUrl = preview
            this@newLiveStreamLoadResponse.tags = tags
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
                        login,
                        TvType.Live,
                        fix = false
                    ) { posterUrl = "" }
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
        // Category pages pass a twitch channel URL of the top stream (or mainUrl)
        if (!data.contains("twitch.tv/")) return false
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

    private suspend fun fetchTopStreams(first: Int): List<StreamNode> {
        val res = gql(
            """
            query(${'$'}first: Int!) {
              streams(first: ${'$'}first) {
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
            login,
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
            "$categoryPrefix${id.orEmpty()}",
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

    // --- GQL models ---

    data class GqlResponse(
        @JsonProperty("data") val data: GqlData? = null,
    )

    data class GqlData(
        @JsonProperty("streams") val streams: EdgeList<StreamNode>? = null,
        @JsonProperty("games") val games: EdgeList<GameNode>? = null,
        @JsonProperty("game") val game: GameDetail? = null,
        @JsonProperty("users") val users: List<UserNode?>? = null,
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
        @JsonProperty("stream") val stream: UserStream? = null,
    )

    data class UserStream(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("viewersCount") val viewersCount: Long? = null,
        @JsonProperty("previewImageURL") val previewImageURL: String? = null,
        @JsonProperty("game") val game: GameRef? = null,
    )

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
