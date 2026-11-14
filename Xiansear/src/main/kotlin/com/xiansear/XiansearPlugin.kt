package com.xiansear

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XiansearPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(XiansearProvider())
    }
}
