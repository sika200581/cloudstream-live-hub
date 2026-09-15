package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TwitchProvider())
        registerExtractorAPI(TwitchProvider.TwitchExtractor())

        registerMainAPI(KickProvider())
        registerExtractorAPI(KickProvider.KickExtractor())

        registerMainAPI(YouTubeLiveProvider())
        registerMainAPI(RumbleProvider())

        // v1: no combined multi-provider settings UI; each provider's defaults apply.
        // (Standalone plugins still expose their own openSettings gears.)
    }
}
