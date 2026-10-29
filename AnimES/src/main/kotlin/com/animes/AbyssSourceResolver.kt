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

internal data class AbyssSource(
    val url: String,
    val filename: String,
    val referer: String,
    val expectedSize: Long = 0L,
    val partSize: Long = 0L,
    val segmented: Boolean = false,
    val md5Id: String = "",
    val resId: Int = 0,
    val hlsUrl: String = ""
)
internal class ResolveFailure(val code: String, message: String) : Exception(message)

internal object AbyssSourceResolver {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    private val latin1: Charset = Charsets.ISO_8859_1

    fun resolve(ref: String, cancelled: java.util.concurrent.atomic.AtomicBoolean, diagnostic: (String, String) -> Unit = { _, _ -> }, progress: (Int, String) -> Unit): AbyssSource {
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
        if (embedUrl.protocol != "https" || (!embedUrl.host.equals("player.abyssplayer.com", true) && !embedUrl.host.equals("abyssplayer.com", true))) throw ResolveFailure("unsupported", "El enlace no pertenece a AbyssPlayer / Link is not from AbyssPlayer")

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
        // Abyss variants may wrap provider metadata inside a "media" object, while
        // others keep mp4/hls at the payload root and use "media" for unrelated metadata.
        // Prefer whichever object actually contains the MP4 source manifest; otherwise
        // keep the previous nested-object fallback for useful HLS/unavailable diagnostics.
        val nestedMedia = payload.optJSONObject("media")
        val media = when {
            payload.optJSONObject("mp4") != null -> payload
            nestedMedia?.optJSONObject("mp4") != null -> nestedMedia
            else -> nestedMedia ?: payload
        }
        val mp4 = media.optJSONObject("mp4")
        val hls = media.optJSONObject("hls")
        if (mp4 == null) {
            val keys = media.keys().asSequence().take(12).toList().joinToString(",")
            if (hls != null) {
                val hlsKeys = hls.keys().asSequence().take(12).toList().joinToString(",")
                val hlsSources = hls.optJSONArray("sources")
                val metadata = hls.optJSONObject("metadata")
                val streamsCount = metadata?.optJSONArray("streams")?.length() ?: 0
                val mediaCount = metadata?.optJSONArray("media")?.length() ?: 0
                throw ResolveFailure(
                    "hls_only",
                    "Abyss parece ofrecer HLS pero no MP4 / HLS detected without MP4; mediaKeys=$keys hlsKeys=$hlsKeys hlsSources=${hlsSources?.length() ?: 0} streams=$streamsCount media=$mediaCount"
                )
            }
            throw ResolveFailure("unavailable", "Abyss no ofrece fuentes MP4 reconocibles / No MP4 sources object; payloadKeys=$keys")
        }
        val sources = mp4.optJSONArray("sources") ?: JSONArray()
        val rawSources = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
        val firstDatas = mp4.optJSONArray("fristDatas") ?: JSONArray()
        val domains = mp4.optJSONArray("domains") ?: JSONArray()
        val firstDataObjects = (0 until firstDatas.length()).mapNotNull { index ->
            firstDatas.optJSONObject(index)?.let { index to it }
        }
        val options = rawSources.map { json ->
            val resId = json.optInt("res_id", 0)
            val size = json.optLong("size", 0L)
            val matched = firstDataObjects.firstOrNull { (_, item) ->
                item.optInt("res_id", 0) == resId &&
                    (item.optLong("size", -1L) == size || item.optLong("size", -1L) < 0L)
            } ?: firstDataObjects.firstOrNull { (_, item) -> item.optInt("res_id", 0) == resId }
            val dataIndex = matched?.first ?: -1
            val data = matched?.second
            var sourceUrl = json.optString("url").takeUnless { it == "null" }.orEmpty()
            var sourcePath = json.optString("path").takeUnless { it == "null" }.orEmpty()
            var directUrl = ""
            if (sourceUrl.isBlank() || sourcePath.isBlank()) {
                val metadataUrl = data?.optString("url").takeUnless { it == "null" }.orEmpty()
                val candidate = metadataUrl.ifBlank { sourceUrl }
                if (candidate.startsWith("https://", true) || candidate.startsWith("//")) {
                    val absolute = if (candidate.startsWith("//")) "https:$candidate" else candidate
                    runCatching {
                        val parsed = URL(absolute)
                        if (parsed.protocol == "https" && parsed.host.isNotBlank()) {
                            if (parsed.path.isNullOrBlank() || parsed.path == "/") {
                                directUrl = absolute
                            } else {
                                sourceUrl = "https://${parsed.authority}"
                                sourcePath = parsed.file.removePrefix("/")
                            }
                        }
                    }
                } else if (candidate.isNotBlank() && !candidate.startsWith("http://", true)) {
                    val domainValue = if (dataIndex >= 0) domains.optString(dataIndex).orEmpty() else ""
                    val domain = domainValue.removePrefix("https://").removePrefix("http://").trim().trimEnd('/')
                    if (domain.isNotBlank() && !domain.contains('/') && !domain.contains(' ')) {
                        directUrl = "https://$domain/${candidate.trimStart('/')}"
                    }
                }
            }
            if (sourcePath.isNotBlank() && sourceUrl.startsWith("https://")) directUrl = ""
            val allKeys = (json.keys().asSequence().toList() +
                (data?.keys()?.asSequence()?.map { "fristDatas.$it" }?.toList() ?: emptyList())).distinct()
            AbyssSourceOption(
                label = json.optString("label").takeUnless { it == "null" }.orEmpty(),
                resId = resId,
                size = size,
                partSize = data?.optLong("partSize", json.optLong("partSize", 0L)) ?: json.optLong("partSize", 0L),
                status = json.optBoolean("status", false),
                path = sourcePath,
                url = sourceUrl,
                keys = allKeys,
                directUrl = directUrl,
                sub = json.optString("sub").takeUnless { it == "null" }.orEmpty()
            )
        }
        // First preserve the exact v0.2.9 / Actions #40 source selection and URL
        // construction. Only opt into Sora when that exact legacy candidate is
        // positively identified as a split first-part URL.
        val legacyWanted = when (quality.lowercase()) {
            "3", "480", "480p" -> 480
            "4", "720", "720p" -> 720
            "5", "1080", "1080p" -> 1080
            else -> Int.MAX_VALUE
        }
        val legacyCandidates = rawSources.filter {
            it.optString("url").startsWith("https://") && it.optString("path").isNotBlank()
        }
        val legacyJson = legacyCandidates.sortedByDescending { qualityHeight(it) }
            .firstOrNull { qualityHeight(it) <= legacyWanted }
            ?: legacyCandidates.maxByOrNull { qualityHeight(it) }
        if (legacyJson != null) {
            val legacyBase = legacyJson.getString("url").trimEnd('/')
            val legacyPath = legacyJson.getString("path").trimStart('/')
            val legacyUrl = "$legacyBase/$legacyPath"
            val legacyFilename = URL(legacyUrl).path.substringAfterLast('/').ifBlank { legacyPath.substringAfterLast('/') }
            val legacyResId = legacyJson.optInt("res_id", 0)
            val legacySize = legacyJson.optLong("size", 0L)
            val legacyOption = options.firstOrNull {
                it.resId == legacyResId && it.path.trimStart('/') == legacyPath && it.url.trimEnd('/') == legacyBase
            }
            val legacyPartSize = legacyOption?.partSize ?: 0L
            val rawDomainLegacy = domains.optString(0).takeUnless { it == "null" }.orEmpty()
                .removePrefix("https://").removePrefix("http://").trim().trimEnd('/')
            val suffixLegacy = rawDomainLegacy.substringAfter(".", "")
            val legacySub = legacyOption?.sub.orEmpty()
            val legacySoraBase = if (legacySub.isNotBlank() && suffixLegacy.isNotBlank()) "https://$legacySub.$suffixLegacy" else ""
            val legacyHasSoraMetadata = legacySoraBase.isNotBlank() && legacyPartSize > 0L &&
                legacySize > legacyPartSize && md5Id.isNotBlank() && legacyResId > 0
            val legacyProbe = if (legacyHasSoraMetadata) probeDirectCandidate(legacyUrl, referer, legacySize, legacyPartSize, cancelled) else "legacy-direct"
            val useSoraForLegacySource = legacyHasSoraMetadata &&
                (legacyProbe == "first-part-only" || legacyProbe == "partial-total-$legacyPartSize")
            if (!useSoraForLegacySource) {
                diagnostic("source_transport", "transport=legacy-direct-v029 url=$legacyUrl probe=$legacyProbe")
                progress(88, "Preparando transmisión segura… / Preparing stream…")
                if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
                progress(100, "Fuente encontrada / Source found")
                return AbyssSource(legacyUrl, legacyFilename, referer)
            }
            diagnostic("source_transport", "transport=abyss-sora reason=confirmed-first-part expected=$legacySize partSize=$legacyPartSize resId=$legacyResId")
            progress(88, "Preparando transmisión segura… / Preparing stream…")
            if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
            progress(100, "Fuente encontrada / Source found")
            val hlsPlaylist = findHlsPlaylist(hls)
            if (hlsPlaylist.isNotBlank()) {
                diagnostic("source_transport", "transport=abyss-hls reason=confirmed-split playlist=" + hlsPlaylist.substringBefore("?").take(180))
            }
            return AbyssSource(legacySoraBase, "$md5Id-$legacyResId.mp4", referer, legacySize, legacyPartSize, true, md5Id, legacyResId, hlsPlaylist)
        }
        val selection = AbyssSourceSelection.select(options, quality)
        val selected = selection.selected
        val diagnosticSummary = AbyssSourceSelection.diagnosticSummary(options, selection)
        diagnostic("source_selection", diagnosticSummary)
        if (selected == null) {
            if (selection.failureCode == "unsupported") {
                throw ResolveFailure("unsupported", selection.failureMessage ?: "Formato fragmentado no compatible aún / Split format not supported yet")
            }
            val mp4Keys = mp4.keys().asSequence().toList().joinToString(",")
            val domains = describeMetadata(mp4.opt("domains"))
            val firstDatas = describeMetadata(mp4.opt("fristDatas"))
            if (hls != null) {
                val hlsKeys = hls.keys().asSequence().take(12).toList().joinToString(",")
                val hlsSources = hls.optJSONArray("sources")
                val metadata = hls.optJSONObject("metadata")
                val streamsCount = metadata?.optJSONArray("streams")?.length() ?: 0
                val mediaCount = metadata?.optJSONArray("media")?.length() ?: 0
                throw ResolveFailure(
                    "hls_only",
                    "Abyss no tiene MP4 utilizable; HLS disponible / No usable MP4; HLS metadata found; mp4Keys=$mp4Keys sourceCountBefore=${options.size} sourceCountAfter=0 hlsKeys=$hlsKeys hlsSources=${hlsSources?.length() ?: 0} streams=$streamsCount media=$mediaCount"
                )
            }
            throw ResolveFailure(
                "unavailable",
                "No hay fuentes MP4 válidas / No valid MP4 sources; sourceCountBefore=${options.size} sourceCountAfter=0 domains=$domains fristDatas=$firstDatas"
            )
        }
        // Match the reference client exactly: buildSegmentUrl(domains.firstOrNull(), source.sub)
        // returns https://<sub>.<domain suffix>, where suffix is everything after the first dot.
        val path = selected.path.trimStart('/')
        val directCandidate = selected.directUrl.ifBlank { "${selected.url.trimEnd('/')}/$path" }
        val rawDomain = domains.optString(0).takeUnless { it == "null" }.orEmpty()
            .removePrefix("https://").removePrefix("http://").trim().trimEnd('/')
        val domainSuffix = rawDomain.substringAfter(".", "")
        val soraBase = if (selected.sub.isNotBlank() && domainSuffix.isNotBlank()) {
            "https://${selected.sub}.$domainSuffix"
        } else ""
        // Preserve direct transport only when a tiny Range probe confirms the full MP4.
        // Some apparently valid file paths expose only the first partSize bytes.
        val metadataSaysSplit = soraBase.isNotBlank() &&
            selected.partSize > 0L && selected.size > selected.partSize &&
            md5Id.isNotBlank() && selected.resId > 0
        val directProbe = if (metadataSaysSplit) {
            probeDirectCandidate(directCandidate, referer, selected.size, selected.partSize, cancelled)
        } else "not-needed"
        // Keep the commit-40 direct transport as the safe default. Switch to Sora
        // only when the probe positively identifies a first-part-only URL; transient
        // probe failures must not silently change a previously working series to Sora.
        val directIsFirstPartOnly = directProbe == "first-part-only" ||
            directProbe == "partial-total-${selected.partSize}"
        val segmented = metadataSaysSplit && directIsFirstPartOnly
        diagnostic("direct_transport_probe", "result=$directProbe expectedSize=${selected.size} partSize=${selected.partSize} useSora=$segmented decision=${if (segmented) "confirmed-split" else "legacy-direct"}")
        val segmentBase = if (segmented) soraBase else ""
        val finalUrl = if (segmented) segmentBase else directCandidate
        val filename = if (segmented) "$md5Id-${selected.resId}.mp4" else URL(finalUrl).path.substringAfterLast('/').ifBlank { path.substringAfterLast('/') }
        if (!finalUrl.startsWith("https://")) throw ResolveFailure("unsupported", "La fuente no usa HTTPS / Source is not HTTPS")
        progress(88, "Preparando transmisión segura… / Preparing stream…")
        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        progress(100, "Fuente encontrada / Source found")
        diagnostic("source_transport", if (segmented) "transport=abyss-sora size=${selected.size} resId=${selected.resId} md5Id=$md5Id domain=${rawDomain.take(100)} sub=${selected.sub} domainSuffix=${domainSuffix.take(100)} base=$segmentBase" else "transport=direct url=$finalUrl")
        // Only use HLS when MP4 metadata positively identified this source as split Sora.
        // Fully working direct MP4 sources above remain on their original path.
        val hlsPlaylist = if (segmented) findHlsPlaylist(hls) else ""
        if (hlsPlaylist.isNotBlank()) {
            diagnostic("source_transport", "transport=abyss-hls reason=confirmed-split playlist=" + hlsPlaylist.substringBefore("?").take(180))
        }
        return AbyssSource(finalUrl, filename, referer, selected.size, selected.partSize, segmented, md5Id, selected.resId, hlsPlaylist)
    }

    /**
     * Determine whether an apparently direct Abyss URL represents the complete file.
     * Only request one byte and disconnect without consuming the response body.
     */
    private fun probeDirectCandidate(
        candidate: String,
        referer: String,
        expectedSize: Long,
        partSize: Long,
        cancelled: java.util.concurrent.atomic.AtomicBoolean
    ): String {
        if (cancelled.get()) return "cancelled"
        val conn = runCatching { URL(candidate).openConnection() as HttpURLConnection }.getOrElse {
            return "probe-error-${it.javaClass.simpleName}"
        }
        return try {
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Origin", "https://player.abyssplayer.com")
            val code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range").orEmpty()
            val rangeTotal = Regex("/(\\d+)\\s*$").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
            val bodyLength = conn.contentLengthLong
            when {
                code == HttpURLConnection.HTTP_PARTIAL && rangeTotal == expectedSize -> "full-range"
                code in 200..299 && bodyLength == expectedSize -> "full-body"
                code == HttpURLConnection.HTTP_PARTIAL && rangeTotal == partSize -> "first-part-only"
                code in 200..299 && bodyLength == partSize -> "first-part-only"
                code == HttpURLConnection.HTTP_PARTIAL -> "partial-total-${rangeTotal ?: "unknown"}"
                else -> "http-$code"
            }
        } catch (e: Exception) {
            "probe-error-${e.javaClass.simpleName}"
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Use only an explicit HTTPS HLS playlist exposed in Abyss's decrypted metadata.
     * Do not synthesize playlist URLs: if the schema does not expose one, keep Sora MP4.
     */
    private fun findHlsPlaylist(value: Any?): String {
        fun visit(node: Any?): String {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val found = visit(node.opt(key))
                        if (found.isNotBlank()) return found
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        val found = visit(node.opt(i))
                        if (found.isNotBlank()) return found
                    }
                }
                is String -> {
                    val candidate = node.trim()
                    if (candidate.startsWith("https://", true)) {
                        val parsed = runCatching { URL(candidate) }.getOrNull()
                        if (parsed != null && parsed.protocol.equals("https", true) &&
                            parsed.path.endsWith(".m3u8", true) && parsed.host.isNotBlank()) {
                            return candidate
                        }
                    }
                }
            }
            return ""
        }
        return visit(value)
    }

    private fun describeMetadata(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> "object(keys=" + value.keys().asSequence().take(12).toList().joinToString(",") + ")"
            is JSONArray -> {
                val first = value.opt(0)
                val firstSummary = when (first) {
                    is JSONObject -> "object(keys=" + first.keys().asSequence().take(12).toList().joinToString(",") + ")"
                    is JSONArray -> "array(count=" + first.length() + ")"
                    is String -> "string(" + first.take(80).replace(" ", "_") + ")"
                    null, JSONObject.NULL -> "null"
                    else -> first.javaClass.simpleName
                }
                "array(count=${value.length()},first=$firstSummary)"
            }
            is String -> "string(" + value.take(80).replace(" ", "_") + ")"
            else -> value.javaClass.simpleName
        }
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
