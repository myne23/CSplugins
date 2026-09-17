package com.example

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {

    private var activity: AppCompatActivity? = null

    private val prefs by lazy {
        activity?.getSharedPreferences(
            "W0Fl1X_settings",
            Context.MODE_PRIVATE
        )
    }

    fun getWyzieApiKey(): String {
        return prefs?.getString("wyzie_api_key", "")?.trim().orEmpty()
    }

    fun setWyzieApiKey(key: String) {
        prefs?.edit()
            ?.putString("wyzie_api_key", key.trim())
            ?.apply()
    }

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        registerMainAPI(ExampleProvider(this))

        openSettings = {
            val frag = BlankFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "Frag")
            }
        }
    }
}
