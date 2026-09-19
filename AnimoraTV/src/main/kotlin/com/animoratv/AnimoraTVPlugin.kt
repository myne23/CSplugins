package com.animoratv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimoraTVPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(AnimoraTVProvider())
    }
}
