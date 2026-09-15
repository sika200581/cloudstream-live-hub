package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Live Hub — single provider homepage that aggregates top live rows from
 * Twitch, Kick, YouTube Live, and Rumble via internal child providers.
 */
class LiveHubProvider : MainAPI() {
    override var name = "Live Hub"
    override var mainUrl = "https://www.twitch.tv"
    override var lang = "uni"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries, TvType.Movie)
    override val hasMainPage = true

    private val twitch = TwitchProvider()
    private val kick = KickProvider()
    private val youtube = YouTubeLiveProvider()
    private val rumble = RumbleProvider()

    override val mainPage = mainPageOf(
        "twitch" to "Twitch",
        "kick" to "Kick",
        "youtube" to "YouTube Live",
        "rumble" to "Rumble",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (child, childData) = when (request.data) {
            "twitch" -> twitch to "streams"          // Top live worldwide
            "kick" -> kick to "streams"
            "youtube" -> youtube to "live_trending"
            "rumble" -> rumble to "live"
            else -> return newHomePageResponse(
                listOf(HomePageList(request.name, emptyList(), true)),
                false
            )
        }
        val childReq = MainPageRequest(request.name, childData, true)
        return child.getMainPage(page, childReq)
            ?: newHomePageResponse(listOf(HomePageList(request.name, emptyList(), true)), false)
    }

    private fun route(url: String): MainAPI {
        val u = url.lowercase()
        return when {
            "kick.com" in u -> kick
            "youtube.com" in u || "youtu.be" in u -> youtube
            "rumble.com" in u -> rumble
            else -> twitch // twitch.tv + default
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? = buildList {
        listOf(twitch, kick, youtube, rumble).forEach { p ->
            runCatching { p.search(query) }.getOrNull()?.let { addAll(it) }
        }
    }.distinctBy { it.url }.take(40)

    override suspend fun load(url: String): LoadResponse? = route(url).load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = route(data).loadLinks(data, isCasting, subtitleCallback, callback)
}
