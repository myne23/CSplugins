package com.animejara

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeJaraPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(AnimeJaraProvider())
    }
}
