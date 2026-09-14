package recloudstream

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TwitchPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TwitchProvider())
        registerExtractorAPI(TwitchProvider.TwitchExtractor())

        openSettings = { ctx ->
            val langs = TwitchProvider.ALL_HOME_LANGUAGES
            val labels = langs.map { TwitchProvider.languageLabel(it) }.toTypedArray()
            val enabled = TwitchProvider.getEnabledHomeLanguages().toMutableSet()
            val checked = BooleanArray(langs.size) { langs[it] in enabled }

            AlertDialog.Builder(ctx)
                .setTitle("Homepage language rows")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val code = langs[which]
                    if (isChecked) enabled.add(code) else enabled.remove(code)
                }
                .setPositiveButton("Save") { _, _ ->
                    val toSave = if (enabled.isEmpty()) {
                        setOf("EN") // never allow empty
                    } else {
                        // keep stable order from ALL_HOME_LANGUAGES
                        langs.filter { it in enabled }.toSet()
                    }
                    TwitchProvider.setEnabledHomeLanguages(toSave)
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset") { _, _ ->
                    TwitchProvider.setEnabledHomeLanguages(TwitchProvider.DEFAULT_HOME_LANGUAGES)
                }
                .show()
        }
    }
}
