package com.animes

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class AbyssSource(val url: String, val filename: String, val referer: String)
internal class ResolveFailure(val code: String, message: String) : Exception(message)

internal object AbyssSourceResolver {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    private val latin1: Charset = Charsets.ISO_8859_1

    fun resolve(ref: String, cancelled: java.util.concurrent.atomic.AtomicBoolean, progress: (Int, String) -> Unit): AbyssSource {
        val parts = ref.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "abyss") throw ResolveFailure("unsupported", "Referencia Abyss inválida / Invalid Abyss reference")
        val rawSlug = parts[1]
        val quality = parts[2]
        val referer = decode(parts[3]).ifBlank { "https://mytsumi.com/" }
        val embed = if (rawSlug.startsWith("https%3A", true) || rawSlug.startsWith("https://")) decode(rawSlug) else
            "https://player.abyssplayer.com/$rawSlug"
        val embedUrl = try { URL(embed) } catch (_: Exception) {
            throw ResolveFailure("unsupported", "URL de Abyss inválida / Invalid Abyss URL")
        }
        if (embedUrl.protocol != "https" || embedUrl.host != "player.abyssplayer.com") throw ResolveFailure("unsupported", "El enlace no pertenece a AbyssPlayer / Link is not from AbyssPlayer")

        progress(8, "Abriendo reproductor Abyss… / Opening Abyss player…")
        val html = getText(embed, referer, cancelled)
        val marker = "const datas = \""
        val at = html.indexOf(marker)
        if (at < 0) throw ResolveFailure("bad_response", "No se encontró el paquete de datos de Abyss / Abyss data payload was not found")
        val start = at + marker.length
        val end = html.indexOf('"', start)
        if (end <= start) throw ResolveFailure("bad_response", "Paquete de datos incompleto / Incomplete data payload")
        val packed = try { Base64.decode(html.substring(start, end), Base64.DEFAULT) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "No se pudo decodificar el paquete de Abyss / Could not decode Abyss payload")
        }
        val jsonText = String(packed, latin1)
        val data = try { JSONObject(jsonText) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "El paquete de Abyss no es JSON válido / Abyss payload is not valid JSON")
        }
        val userId = data.optString("user_id")
        val slug = data.optString("slug")
        val md5Id = data.optString("md5_id")
        val encryptedMedia = data.optString("media")
        if (userId.isBlank() || slug.isBlank() || md5Id.isBlank() || encryptedMedia.isBlank()) {
            throw ResolveFailure("bad_response", "Faltan campos de identificación de Abyss / Abyss identification fields are missing")
        }

        progress(32, "Descifrando metadatos… / Decrypting metadata…")
        val mediaKey = md5("$userId:$slug:$md5Id").toByteArray(Charsets.US_ASCII)
        val mediaCipher = Cipher.getInstance("AES/CTR/NoPadding")
        mediaCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mediaKey, "AES"), IvParameterSpec(mediaKey.copyOfRange(0, 16)))
        val encryptedBytes = Base64.encode(encryptedMedia.toByteArray(latin1), Base64.NO_WRAP)
        val plaintext = String(mediaCipher.doFinal(Base64.decode(encryptedBytes, Base64.NO_WRAP)), Charsets.UTF_8)
        val payload = try { JSONObject(plaintext) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "No se pudieron descifrar los metadatos MP4 / Could not decrypt MP4 metadata")
        }
        val mp4 = payload.optJSONObject("mp4") ?: throw ResolveFailure("unavailable", "Abyss no ofrece fuentes MP4 / Abyss has no MP4 sources")
        val sources = mp4.optJSONArray("sources") ?: JSONArray()
        val candidates = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") && it.optString("path").isNotBlank() }
        if (candidates.isEmpty()) throw ResolveFailure("unavailable", "No se encontraron fuentes MP4 / No MP4 sources were found")
        val wanted = when (quality.lowercase()) { "3", "480", "480p" -> 480; "4", "720", "720p" -> 720; "5", "1080", "1080p" -> 1080; else -> Int.MAX_VALUE }
        val source = candidates.sortedByDescending { qualityHeight(it) }.firstOrNull { qualityHeight(it) <= wanted }
            ?: candidates.maxByOrNull { qualityHeight(it) }!!
        val base = source.getString("url").trimEnd('/')
        val path = source.getString("path").trimStart('/')
        val finalUrl = "$base/$path"
        val filename = URL(finalUrl).path.substringAfterLast('/').ifBlank { path.substringAfterLast('/') }
        if (!finalUrl.startsWith("https://")) throw ResolveFailure("unsupported", "La fuente no usa HTTPS / Source is not HTTPS")
        progress(88, "Preparando transmisión segura… / Preparing stream…")
        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        progress(100, "Fuente encontrada / Source found")
        return AbyssSource(finalUrl, filename, referer)
    }

    private fun qualityHeight(o: JSONObject): Int {
        val s = listOf(o.optString("quality"), o.optString("label"), o.optString("path")).joinToString(" ")
        return Regex("(\\d{3,4})\\s*p?", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun getText(url: String, referer: String, cancelled: java.util.concurrent.atomic.AtomicBoolean): String {
        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("Origin", "https://player.abyssplayer.com")
        try {
            if (c.responseCode !in 200..299) throw ResolveFailure("unavailable", "Abyss devolvió HTTP ${c.responseCode} / Abyss returned HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun md5(s: String) = AbyssCrypto.md5Hex(s)
}
