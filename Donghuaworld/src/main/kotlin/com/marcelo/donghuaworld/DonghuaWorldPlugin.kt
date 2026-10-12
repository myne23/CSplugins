package com.marcelo.donghuaworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaWorldProvider())
    }
}
