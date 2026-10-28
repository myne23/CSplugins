package com.animes

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimESPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(AnimESProvider())
    }
}
