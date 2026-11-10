package com.animelatinohd

import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeLHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeLHDProvider())
        openSettings = { ctx -> showCloudflareSettings(ctx) }
    }

    private fun showCloudflareSettings(context: Context) {
        val modes = arrayOf(
            "Automático (recomendado)",
            "Automático + manual si falla",
            "Solo manual (VPN)"
        )
        val values = arrayOf("auto", "auto_manual", "manual")
        val current = CloudStreamApp.getKey("AnimeLHD", "CF_MODE", "auto_manual") as? String
            ?: "auto_manual"
        val selected = intArrayOf(values.indexOf(current).takeIf { it >= 0 } ?: 1)

        val clearance = CookieManager.getInstance()
            .getCookie("https://www.animelatinohd.com/")
            .orEmpty()
            .split(";")
            .any { part ->
                val cookie = part.trim()
                cookie.startsWith("cf_clearance=") &&
                    cookie.substringAfter("=").isNotBlank()
            }
        val ua = CloudStreamApp.getKey("AnimeLHD_CF_UA", "") as? String
        val status = "Cookie cf_clearance: ${if (clearance) "presente" else "ausente"}\n" +
            "User-Agent persistido: ${ua?.takeIf { it.isNotBlank() } ?: "no disponible"}"

        val builder = AlertDialog.Builder(context)
            .setTitle("AnimeLHD — Cloudflare")
            .setMessage(status)
            .setSingleChoiceItems(modes, selected[0]) { _, which ->
                selected[0] = which
            }
            .setPositiveButton("Guardar") { _, _ ->
                CloudStreamApp.setKey("AnimeLHD", "CF_MODE", values[selected[0]])
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Borrar cookie del sitio") { _, _ ->
                CookieManager.getInstance().setCookie(
                    "https://www.animelatinohd.com/",
                    "cf_clearance=; Max-Age=0"
                )
                CookieManager.getInstance().flush()
            }

        if (context is android.app.Activity) {
            context.runOnUiThread {
                if (!context.isFinishing) builder.show()
            }
        } else {
            builder.show()
        }
    }
}
