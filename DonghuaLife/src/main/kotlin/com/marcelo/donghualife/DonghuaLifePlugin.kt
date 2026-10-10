package com.marcelo.donghualife

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaLifePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaLifeProvider())
    }
}
