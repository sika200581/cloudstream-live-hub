package recloudstream

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
import com.lagradost.cloudstream3.fixUrl
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
import org.jsoup.nodes.Element
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Discover-focused Twitch provider: richer homepage sections than the stock
 * "one row of top streams" experience (languages, more games, viewer counts).
 */
class TwitchProvider : MainAPI() {
    override var mainUrl = "https://twitchtracker.com"
    override var name = "Twitch Discover"
    override val supportedTypes = setOf(TvType.Live)

    override var lang = "uni"

    override val hasMainPage = true

    private val browseGamesName = "Browse top games"
    private val isHorizontal = true
    private val maxGamesOnHome = 10
    private val maxStreamsPerSection = 24

    override val mainPage = mainPageOf(
        "$mainUrl/channels/live" to "Top live worldwide",
        "$mainUrl/languages/English" to "Top English",
        "$mainUrl/languages/Arabic" to "Top Arabic",
        "$mainUrl/languages/Spanish" to "Top Spanish",
        "$mainUrl/languages/Portuguese" to "Top Portuguese",
        "$mainUrl/languages/French" to "Top French",
        "$mainUrl/languages/German" to "Top German",
        "$mainUrl/games" to browseGamesName,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            browseGamesName -> newHomePageResponse(parseTopGames(), hasNext = false)
            else -> {
                val streams = when {
                    request.data.contains("/languages/") ->
                        parseLanguageLiveTable(request.data)
                    else ->
                        parseChannelsLiveTable(
                            request.data,
                            params = mapOf("page" to page.toString())
                        )
                }.take(maxStreamsPerSection)

                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name,
                            streams,
                            isHorizontalImages = isHorizontal
                        )
                    ),
                    hasNext = !request.data.contains("/languages/") && streams.isNotEmpty()
                )
            }
        }
    }

    private suspend fun parseChannelsLiveTable(
        url: String,
        params: Map<String, String> = emptyMap(),
    ): List<LiveSearchResponse> {
        val doc = app.get(url, params = params, referer = mainUrl).document
        // Stock worldwide table
        val rows = doc.select("table#channels tr").ifEmpty {
            doc.select("table#channels-live tr")
        }
        return rows.mapNotNull { it.toStreamCardOrNull() }
    }

    private suspend fun parseLanguageLiveTable(url: String): List<LiveSearchResponse> {
        val doc = app.get(url, referer = mainUrl).document
        return doc.select("table#channels-live tr").mapNotNull { it.toStreamCardOrNull() }
    }

    private fun Element.toStreamCardOrNull(): LiveSearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank() || href == "#" || href.startsWith("/games") || href.startsWith("/languages")) {
            return null
        }
        val login = href.substringAfterLast('/').ifBlank { return null }
        val displayName = anchor.text().ifBlank { login }
        val image = this.selectFirst("img")?.attr("src").orEmpty()
        val viewers = this.selectFirst("span.to-number, .viewers-value, td:nth-child(2)")
            ?.text()
            ?.replace(",", "")
            ?.trim()
            ?.toLongOrNull()

        val title = if (viewers != null) {
            "$displayName · ${formatViewers(viewers)}"
        } else {
            displayName
        }

        return newLiveSearchResponse(
            title,
            login,
            TvType.Live,
            fix = false
        ) {
            posterUrl = image
        }
    }

    private suspend fun parseTopGames(): List<HomePageList> {
        val doc = app.get("$mainUrl/games", referer = mainUrl).document
        return doc.select("div.ranked-item")
            .take(maxGamesOnHome)
            .mapNotNull { element ->
                val game = element.selectFirst("div.ri-name > a") ?: return@mapNotNull null
                val url = fixUrl(game.attr("href"))
                val name = game.text().ifBlank { return@mapNotNull null }
                val streams = parseGameStreams(url).ifEmpty { return@mapNotNull null }
                HomePageList(
                    "🎮 $name",
                    streams.take(maxStreamsPerSection),
                    isHorizontalImages = isHorizontal
                )
            }
    }

    private suspend fun parseGameStreams(url: String): List<LiveSearchResponse> {
        val doc = app.get(url, referer = mainUrl).document
        val fromSlots = doc.select("td.cell-slot.sm").mapNotNull { it.toStreamCardOrNull() }
        if (fromSlots.isNotEmpty()) return fromSlots
        return doc.select("table tr").mapNotNull { it.toStreamCardOrNull() }
    }

    override suspend fun load(url: String): LoadResponse {
        val realUrl = url.substringAfterLast("/").substringBefore("?")
        val doc = app.get("$mainUrl/$realUrl", referer = mainUrl).document
        val name = doc.selectFirst("div#app-title")?.text().orEmpty()
        if (name.isBlank()) {
            throw RuntimeException("Could not load channel, please try again.\n")
        }
        val rank = doc.select("div.rank-badge > span").lastOrNull()?.text()?.toIntOrNull()
        val image = doc.selectFirst("div#app-logo > img")?.attr("src").orEmpty()
        val poster = doc.selectFirst("div.embed-responsive > img")?.attr("src").orEmpty().ifEmpty { image }
        val description = doc.selectFirst("div[style='word-wrap:break-word;font-size:12px;']")
            ?.text()
            .orEmpty()
        val language = doc.selectFirst("a.label.label-soft")?.text()?.ifEmpty { null }
        val isLive = doc.select("div.live-indicator-container").isNotEmpty()
        val gameName = doc.selectFirst("a[href^='/games/']")?.text()?.ifBlank { null }
        val viewersText = doc.selectFirst(".to-number, .viewers-value")?.text()
            ?.replace(",", "")
            ?.trim()
        val viewers = viewersText?.toLongOrNull()

        val tags = listOfNotNull(
            if (isLive) "Live" else "Offline",
            gameName,
            language,
            viewers?.let { formatViewers(it) + " watching" },
            rank?.let { "Rank #$it" },
        )

        val plotParts = listOfNotNull(
            description.ifBlank { null },
            gameName?.let { "Playing: $it" },
            viewers?.let { "Viewers: ${formatViewers(it)}" },
        )

        val twitchUrl = "https://www.twitch.tv/$realUrl"

        return newLiveStreamLoadResponse(
            name, twitchUrl, twitchUrl
        ) {
            plot = plotParts.joinToString("\n")
            posterUrl = image
            backgroundPosterUrl = poster
            this@newLiveStreamLoadResponse.tags = tags
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val channelHits = app.get(
            "$mainUrl/search",
            params = mapOf("q" to q),
            referer = mainUrl
        ).document.select("table.tops tr").mapNotNull { it.toStreamCardOrNull() }

        // Also surface matching live categories when the query looks like a game name
        val gameHits = runCatching {
            val gamesDoc = app.get("$mainUrl/games", referer = mainUrl).document
            gamesDoc.select("div.ranked-item").mapNotNull { element ->
                val game = element.selectFirst("div.ri-name > a") ?: return@mapNotNull null
                val name = game.text()
                if (!name.contains(q, ignoreCase = true)) return@mapNotNull null
                val url = fixUrl(game.attr("href"))
                parseGameStreams(url).firstOrNull()?.let { top ->
                    // Re-title so search results show the category context
                    newLiveSearchResponse(
                        "🎮 $name · ${top.name.substringBefore(" · ")}",
                        top.url.substringAfterLast('/'),
                        TvType.Live,
                        fix = false
                    ) {
                        posterUrl = top.posterUrl
                    }
                }
            }
        }.getOrDefault(emptyList())

        return (channelHits + gameHits).distinctBy {
            it.url.substringAfterLast('/').lowercase(Locale.ROOT)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, subtitleCallback, callback)
    }

    private fun formatViewers(count: Long): String {
        if (count < 1000) return count.toString()
        val exp = (ln(count.toDouble()) / ln(1000.0)).toInt()
        val units = arrayOf("K", "M", "B")
        val value = count / 1000.0.pow(exp.toDouble())
        val formatted = if (value >= 100 || exp == 0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
        }
        return formatted + units[exp - 1]
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
