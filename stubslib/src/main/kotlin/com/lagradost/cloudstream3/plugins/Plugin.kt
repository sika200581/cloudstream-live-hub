package com.lagradost.cloudstream3.plugins

import android.content.Context

/** Compile-only stub — at runtime the host CloudStream app provides this class. */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    var openSettings: ((context: Context) -> Unit)? = null
}
