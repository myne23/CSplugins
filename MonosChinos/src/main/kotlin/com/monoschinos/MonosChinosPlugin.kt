package com.monoschinos

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MonosChinosPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MonosChinosProvider())
    }
}
