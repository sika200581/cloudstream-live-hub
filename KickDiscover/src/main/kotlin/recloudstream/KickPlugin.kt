package recloudstream

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KickPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KickProvider())
        registerExtractorAPI(KickProvider.KickExtractor())

        openSettings = { ctx ->
            val langs = KickProvider.ALL_HOME_LANGUAGES
            val labels = langs.map { KickProvider.languageLabel(it) }.toTypedArray()
            val enabled = KickProvider.getEnabledHomeLanguages().toMutableSet()
            val checked = BooleanArray(langs.size) { langs[it] in enabled }

            AlertDialog.Builder(ctx)
                .setTitle("Homepage language rows")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val code = langs[which]
                    if (isChecked) enabled.add(code) else enabled.remove(code)
                }
                .setPositiveButton("Save") { _, _ ->
                    val toSave = if (enabled.isEmpty()) {
                        setOf("en") // never allow empty
                    } else {
                        langs.filter { it in enabled }.toSet()
                    }
                    KickProvider.setEnabledHomeLanguages(toSave)
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset") { _, _ ->
                    KickProvider.setEnabledHomeLanguages(KickProvider.DEFAULT_HOME_LANGUAGES)
                }
                .show()
        }
    }
}
