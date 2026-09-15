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
 *
 * Children are *not* registered as MainAPIs. Their [MainAPI.name] must equal
 * this provider's name so SearchResponse/LoadResponse apiName resolves to
 * "Live Hub" (CloudStream looks up providers by apiName; wrong names cause
 * "This provider does not exist"). Playback stays on LiveHubProvider which
 * routes load/loadLinks to the matching child; Twitch/Kick extractors are
 * registered by [LiveHubPlugin].
 */
class LiveHubProvider : MainAPI() {
    override var name = "Live Hub"
    override var mainUrl = "https://www.twitch.tv"
    override var lang = "uni"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries, TvType.Movie)
    override val hasMainPage = true

    // Stamp hub name so cards/load responses never claim an unregistered provider.
    private val twitch = TwitchProvider().apply { name = HUB_NAME }
    private val kick = KickProvider().apply { name = HUB_NAME }
    private val youtube = YouTubeLiveProvider().apply { name = HUB_NAME }
    private val rumble = RumbleProvider().apply { name = HUB_NAME }

    override val mainPage = mainPageOf(
        "twitch" to "Twitch · Top worldwide",
        "kick" to "Kick · Top worldwide",
        "youtube" to "YouTube · Top live",
        "rumble" to "Rumble · Top live",
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

    override suspend fun load(url: String): LoadResponse? {
        val resp = route(url).load(url) ?: return null
        // Belt-and-suspenders: LoadResponse.apiName is mutable.
        resp.apiName = HUB_NAME
        return resp
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = route(data).loadLinks(data, isCasting, subtitleCallback, callback)

    companion object {
        const val HUB_NAME = "Live Hub"
    }
}
