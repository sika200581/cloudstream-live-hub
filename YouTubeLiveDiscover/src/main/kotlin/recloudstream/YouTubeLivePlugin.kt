package recloudstream

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YouTubeLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YouTubeLiveProvider())

        openSettings = { ctx ->
            val regions = YouTubeLiveProvider.ALL_REGIONS
            val labels = regions.map { YouTubeLiveProvider.regionLabel(it) }.toTypedArray()
            val current = YouTubeLiveProvider.getRegion()
            val selected = regions.indexOf(current).coerceAtLeast(0)

            AlertDialog.Builder(ctx)
                .setTitle("Default region (gl)")
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    YouTubeLiveProvider.setRegion(regions[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset") { _, _ ->
                    YouTubeLiveProvider.setRegion(YouTubeLiveProvider.DEFAULT_REGION)
                }
                .show()
        }
    }
}
