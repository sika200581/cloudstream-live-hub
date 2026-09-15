package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveHubPlugin : Plugin() {
    override fun load(context: Context) {
        // One aggregator MainAPI only — platform providers stay internal for delegation.
        registerMainAPI(LiveHubProvider())
        registerExtractorAPI(TwitchProvider.TwitchExtractor())
        registerExtractorAPI(KickProvider.KickExtractor())
    }
}
