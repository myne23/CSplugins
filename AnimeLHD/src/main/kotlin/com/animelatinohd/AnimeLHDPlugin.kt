package com.animelatinohd

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val WEB = "https://www.animelatinohd.com/"

@CloudstreamPlugin
class AnimeLHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeLHDProvider())
        openSettings = { ctx -> showCloudflareSettings(ctx) }
    }

    private fun showCloudflareSettings(context: Context) {
        val values = arrayOf("auto_manual", "manual", "auto")
        val labels = arrayOf(
            "Automático y, si falla, WebView manual (recomendado)",
            "Solo manual (WebView visible, para VPN)",
            "Solo automático (sin diálogo)"
        )
        val current = CloudStreamApp.getKey("AnimeLHD", "CF_MODE", "auto_manual") as? String
            ?: "auto_manual"
        val selected = values.indexOf(current).takeIf { it >= 0 } ?: 0

        val cm = CookieManager.getInstance()
        val hasClearance = cm.getCookie(WEB).orEmpty()
            .split(";")
            .any { part ->
                val cookie = part.trim()
                cookie.startsWith("cf_clearance=") &&
                    cookie.substringAfter("=").isNotBlank()
            }

        val persistedUa = CloudStreamApp.getKey("AnimeLHD_CF_UA", "") as? String
        val effectiveUa = persistedUa?.takeIf { it.isNotBlank() }
            ?: WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }
            ?: WebSettings.getDefaultUserAgent(context)
        val status = "cf_clearance: ${if (hasClearance) "presente" else "ausente"}\n" +
            "User-Agent: ${effectiveUa.take(60)}${if (effectiveUa.length > 60) "…" else ""}"

        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(context).apply {
            text = status
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        labels.forEachIndexed { index, label ->
            radioGroup.addView(RadioButton(context).apply {
                id = index + 1
                text = label
                isChecked = index == selected
            }, RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        layout.addView(radioGroup, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val builder = AlertDialog.Builder(context)
            .setTitle("AnimeLHD — Cloudflare")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val index = (radioGroup.checkedRadioButtonId - 1)
                    .takeIf { it in values.indices } ?: 0
                CloudStreamApp.setKey("AnimeLHD", "CF_MODE", values[index])
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Borrar cookies") { _, _ ->
                cm.getCookie(WEB).orEmpty().split(";")
                    .map { it.substringBefore("=").trim() }
                    .filter { it.isNotBlank() }
                    .forEach { cm.setCookie(WEB, "$it=; Max-Age=0; Path=/") }
                cm.flush()
                Toast.makeText(context, "Cookies borradas", Toast.LENGTH_SHORT).show()
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
