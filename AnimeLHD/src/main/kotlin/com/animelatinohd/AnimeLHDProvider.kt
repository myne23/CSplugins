package com.animelatinohd

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URI
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/* ====================================================================
 * Config
 * ==================================================================== */

private const val DEBUG = true

private const val WEB = "https://www.animelatinohd.com"
private const val API = "https://website.animelatinohd.com"
private const val TMDB = "https://image.tmdb.org/t/p"

private const val HOME_CACHE_MS = 180_000L
private const val BRIDGE_TIMEOUT_MS = 10_000L
private const val EXTRACTOR_TIMEOUT_MS = 9_000L
private const val VOE_TIMEOUT_NS = 25_000_000_000L
private const val VOE_BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
private val VOE_REDIRECT = Regex("""window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]""")
private val VOE_CONFIG = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']""")
private val VOE_CONFIG_STRICT = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>""")
private val VOE_TOKEN = Regex("""name=["']_token["'][^>]+value=["']([^"']+)["']""")
private val VOE_CHALLENGE = Regex("""<altcha-widget[^>]+challenge=["']([^"']+)["']""")
private val VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")
private val VOE_HEX_CHARS = "0123456789abcdef".toCharArray()

private val LULU_HOSTS = listOf("luluvdoo.com", "luluvdo.com", "lulustream.com", "lulu.st")
private const val BYSE_FALLBACK_HOST = "https://byse.sx"
private val BYSE_EMBED = Regex("""^https?://(?:www\.)?byse[a-z0-9-]*\.[a-z]+/(.*)$""", RegexOption.IGNORE_CASE)
private val IFRAME_SRC = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val M3U8_FILE = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
private val M3U8_ANY = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")

private fun log(msg: String) {
    if (DEBUG) Log.d("AnimeLHD", msg)
}

private fun safeUrl(url: String?): String {
    if (url.isNullOrBlank()) return "<vacío>"
    return url.substringBefore("#").substringBefore("?")
}

/* ====================================================================
 * Cloudflare
 * ==================================================================== */

private class CloudflareInterceptor(private val killer: CloudflareKiller) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.peekBody(512 * 1024).string()
        val title = try {
            Jsoup.parse(body).select("title").text()
        } catch (e: Exception) {
            ""
        }
        if (title.contains("Just a moment", ignoreCase = true)) {
            response.close()
            return killer.intercept(chain)
        }
        return response
    }
}

/* ====================================================================
 * Helpers JSON / RSC (Next.js mete los datos en self.__next_f.push)
 * ==================================================================== */

/** Une y des-escapa todos los self.__next_f.push([1,"..."]) del HTML. */
private fun flattenRsc(html: String): String {
    val marker = "self.__next_f.push([1,\""
    val sb = StringBuilder()
    var idx = html.indexOf(marker)
    while (idx >= 0) {
        val start = idx + marker.length
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (c == '\\') i += 2
            else if (c == '"') break
            else i++
        }
        if (i >= html.length) break
        try {
            sb.append(JSONArray("[1,\"" + html.substring(start, i) + "\"]").getString(1))
        } catch (e: Exception) {
            log("flattenRsc chunk inválido: ${e.message}")
        }
        idx = html.indexOf(marker, i)
    }
    return sb.toString()
}

/** Devuelve el objeto/array JSON balanceado que empieza en `start`. */
private fun extractBalanced(text: String, start: Int): String? {
    if (start < 0 || start >= text.length) return null
    val open = text[start]
    if (open != '{' && open != '[') return null
    val close = if (open == '{') '}' else ']'
    var depth = 0
    var inStr = false
    var esc = false
    for (i in start until text.length) {
        val c = text[i]
        if (inStr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') inStr = false
        } else {
            if (c == '"') inStr = true
            else if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

/** Busca `marker` (que debe terminar justo antes de { o [) y devuelve el JSON que sigue. */
private fun jsonAfter(text: String, marker: String, from: Int = 0): String? {
    val i = text.indexOf(marker, from)
    if (i < 0) return null
    return extractBalanced(text, i + marker.length)
}

private fun JSONArray.objs(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun tmdb(path: String?, size: String = "w300"): String? {
    val p = path?.trim().orEmpty()
    if (p.isBlank() || p == "null") return null
    return if (p.startsWith("http")) p else "$TMDB/$size$p"
}

private fun langLabel(raw: String): String = when (raw.uppercase()) {
    "SUB" -> "SUB"
    "LAT" -> "LAT"
    "ESP" -> "CAST"
    else -> raw.uppercase()
}

private fun MainAPI.toSearch(o: JSONObject): SearchResponse? {
    val slug = o.optString("slug")
    val title = o.optString("name")
    if (slug.isBlank() || title.isBlank()) return null

    val type = when (o.optString("type")) {
        "Movie" -> TvType.AnimeMovie
        "OVA", "Special" -> TvType.OVA
        else -> TvType.Anime
    }

    return newAnimeSearchResponse(title, "$WEB/anime/$slug", type) {
        posterUrl = tmdb(o.optString("poster"))
    }
}

/* ====================================================================
 * Provider
 * ==================================================================== */

class AnimeLHDProvider : MainAPI() {

    override var mainUrl = WEB
    override var name = "AnimeLHD"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override var lang = "es"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "recent" to "Episodios recientes",
        "latinos" to "Latinos",
        "castellanos" to "Castellanos",
        "added" to "Animes agregados"
    )

    private val cfInterceptor = CloudflareInterceptor(CloudflareKiller())

    @Volatile
    private var homeCache: Pair<Long, String>? = null

    /* ------------------------------------------------------------
     * HTTP
     * ------------------------------------------------------------ */

    // OJO: no seteamos User-Agent a mano; CloudflareKiller necesita que
    // coincida con el del WebView que resolvió el challenge.
    private suspend fun fetch(url: String, json: Boolean = false): String? {
        return try {
            val headers =
                if (json) mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "$WEB/",
                    "Origin" to WEB
                ) else mapOf("Referer" to "$WEB/")

            log("HTTP GET | " + safeUrl(url))
            val res = app.get(url, headers = headers, interceptor = cfInterceptor)
            val body = res.text
            log("HTTP RESULT | code=" + res.code + " | ok=" + res.isSuccessful + " | chars=" + body.length + " | url=" + safeUrl(url))
            if (res.isSuccessful) body
            else {
                log("HTTP ERROR BODY | " + body.take(180).replace("\n", " "))
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("FETCH EXCEPTION " + safeUrl(url) + " -> " + e.javaClass.simpleName + ": " + e.message)
            null
        }
    }

    /** Intenta la API JSON; si falla, parsea el HTML de la página equivalente. */
    private suspend fun fetchList(
        apiUrl: String,
        htmlUrl: String,
        htmlKey: String
    ): Pair<List<JSONObject>, Int> {

        fetch(apiUrl, json = true)?.let { txt ->
            try {
                val j = JSONObject(txt)
                return (j.optJSONArray("data")?.objs() ?: emptyList()) to j.optInt("last_page", 1)
            } catch (e: Exception) {
                log("API no devolvió JSON: ${e.message}")
            }
        }

        val html = fetch(htmlUrl) ?: return emptyList<JSONObject>() to 1
        val flat = flattenRsc(html)
        val obj = jsonAfter(flat, htmlKey)?.let {
            try {
                JSONObject(it)
            } catch (e: Exception) {
                null
            }
        } ?: return emptyList<JSONObject>() to 1

        return (obj.optJSONArray("data")?.objs() ?: emptyList()) to obj.optInt("last_page", 1)
    }

    private suspend fun homeFlat(): String? {
        val cached = homeCache
        if (cached != null && System.currentTimeMillis() - cached.first < HOME_CACHE_MS) {
            return cached.second
        }
        val html = fetch("$WEB/") ?: return null
        val flat = flattenRsc(html)
        homeCache = System.currentTimeMillis() to flat
        return flat
    }

    /* ------------------------------------------------------------
     * Main page / search
     * ------------------------------------------------------------ */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val (items, hasNext) = when (request.data) {

            "recent" -> {
                if (page > 1) emptyList<SearchResponse>() to false
                else {
                    val arr = homeFlat()?.let { jsonAfter(it, "\"episodes\":") }
                    val list = (arr?.let { JSONArray(it).objs() } ?: emptyList())
                        .mapNotNull { ep ->
                            val title = ep.optString("anime_title")
                            val slug = ep.optString("anime_slug")
                            val number = ep.optInt("number", 0)
                            if (title.isBlank() || slug.isBlank()) return@mapNotNull null

                            newAnimeSearchResponse(
                                if (number > 0) "$title - Episodio $number" else title,
                                "$WEB/anime/$slug",
                                TvType.Anime
                            ) {
                                posterUrl = tmdb(ep.optString("anime_image"))
                            }
                        }
                        .distinctBy { it.url }
                    list to false
                }
            }

            "added" -> {
                if (page > 1) emptyList<SearchResponse>() to false
                else {
                    val arr = homeFlat()?.let { jsonAfter(it, "\"animes\":") }
                    val list = (arr?.let { JSONArray(it).objs() } ?: emptyList())
                        .mapNotNull { toSearch(it) }
                    list to false
                }
            }

            else -> {
                val key = request.data // latinos | castellanos
                val (raw, last) = fetchList(
                    "$API/api/$key?page=$page",
                    "$WEB/$key?page=$page",
                    "\"initialData\":"
                )
                raw.mapNotNull { toSearch(it) } to (page < last)
            }
        }

        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = URLEncoder.encode(query, "UTF-8")
        val (raw, _) = fetchList(
            "$API/api/animes?search=$q&page=1",
            "$WEB/directorio?search=$q",
            "\"initialAnimes\":"
        )
        return raw.mapNotNull { toSearch(it) }
    }

    /* ------------------------------------------------------------
     * Load
     * ------------------------------------------------------------ */

    override suspend fun load(url: String): LoadResponse? {

        val slug = url
            .substringAfter("/anime/", "")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        if (slug.isBlank()) return null

        val html = fetch("$WEB/anime/$slug") ?: return null
        val flat = flattenRsc(html)

        val animeStart = flat.indexOf("\"anime\":{\"id\":")
        if (animeStart < 0) return null

        val anime = try {
            JSONObject(jsonAfter(flat, "\"anime\":", animeStart) ?: return null)
        } catch (e: Exception) {
            return null
        }

        val title = anime.optString("name").ifBlank { return null }
        val type = anime.optString("type")

        val episodeNumbers = (jsonAfter(flat, "\"episodes\":", animeStart)
            ?.let { JSONArray(it).objs() } ?: emptyList())
            .map { it.optInt("number", 0) }
            .filter { it > 0 }
            .distinct()
            .sorted()

        val poster = tmdb(anime.optString("poster"), "w500")
        val banner = tmdb(anime.optString("banner"), "w1280")
        val plot = anime.optString("overview").ifBlank { null }
        val yearValue = anime.optString("aired").take(4).toIntOrNull()
        val tagList = anime.optString("genres")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { g -> g.replaceFirstChar { it.uppercase() } }
        val statusValue = when (anime.optInt("status", -1)) {
            0 -> ShowStatus.Completed
            1 -> ShowStatus.Ongoing
            else -> null
        }

        val recs = jsonAfter(flat, "\"relations\":", animeStart)
            ?.let {
                try {
                    JSONObject(it)
                } catch (e: Exception) {
                    null
                }
            }
            ?.let { rel ->
                listOf("prequel", "sequel", "related")
                    .flatMap { k -> rel.optJSONArray(k)?.objs() ?: emptyList() }
                    .mapNotNull { toSearch(it) }
                    .distinctBy { it.url }
            }

        // Película: un solo "episodio" -> mejor como Movie
        if (type == "Movie" && episodeNumbers.size <= 1) {
            val n = episodeNumbers.firstOrNull() ?: 1
            return newMovieLoadResponse(title, "$WEB/anime/$slug", TvType.AnimeMovie, "$slug|$n") {
                posterUrl = poster
                backgroundPosterUrl = banner
                this.plot = plot
                year = yearValue
                tags = tagList
                recommendations = recs
            }
        }

        val episodes = episodeNumbers.map { n ->
            newEpisode("$slug|$n") {
                this.name = "Episodio $n"
                this.episode = n
                this.season = 1
            }
        }

        return newTvSeriesLoadResponse(title, "$WEB/anime/$slug", TvType.Anime, episodes) {
            posterUrl = poster
            backgroundPosterUrl = banner
            this.plot = plot
            year = yearValue
            tags = tagList
            showStatus = statusValue
            recommendations = recs
        }
    }

    /* ------------------------------------------------------------
     * loadLinks
     * ------------------------------------------------------------ */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split("|")
        if (parts.size < 2) return false
        val rawSlug = parts[0].trim()
        // Algunas versiones de CloudStream pueden entregar el data como URL completa.
        // El endpoint /ver/ espera únicamente el slug (por ejemplo: link-click).
        val slug = rawSlug
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')
            .substringAfterLast('/')
            .ifBlank { rawSlug }
        val episode = parts[1].trim().toIntOrNull() ?: return false

        log("LOADLINKS START | rawSlug=" + rawSlug + " | slug=" + slug + " | episodio=" + episode + " | casting=" + isCasting)
        val html = fetch("$WEB/ver/$slug/$episode") ?: run {
            log("LOADLINKS ABORT | no se pudo descargar la página")
            return false
        }
        log("WATCH HTML | chars=" + html.length + " | next_f_push=" + html.contains("self.__next_f.push"))
        val flat = flattenRsc(html)
        log("WATCH RSC | chars=" + flat.length + " | players_marker=" + flat.contains("\"players\":"))

        val playersJson = jsonAfter(flat, "\"players\":")
        if (playersJson == null) {
            log("LOADLINKS ABORT | no se encontró el bloque players")
            return false
        }
        val players = try {
            JSONArray(playersJson).objs()
        } catch (e: Exception) {
            log("LOADLINKS ABORT | JSON players inválido: " + e.javaClass.simpleName + ": " + e.message)
            return false
        }

        log("PLAYERS FOUND | count=" + players.size)
        players.forEachIndexed { index, p -> log("PLAYER[" + index + "] | lang=" + p.optString("language") + " | server=" + p.optString("server_name") + " | bridge=" + safeUrl(p.optString("bridge_url"))) }

        val count = AtomicInteger(0)
        val lock = Any()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val emit: suspend (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.url)) {
                count.incrementAndGet()
                synchronized(lock) { callback(link) }
            }
        }

        coroutineScope {
            players.map { p ->
                async {
                    try {
                        val lang = langLabel(p.optString("language"))
                        val server = p.optString("server_name")
                        val bridge = p.optString("bridge_url")
                        if (bridge.isBlank()) return@async

                        log("PLAYER START | [" + lang + "] " + server + " | bridge=" + safeUrl(bridge))
                        val embed = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) { resolveBridge(bridge) }
                        if (embed == null) {
                            log("bridge sin iframe: $lang $server")
                            return@async
                        }
                        log("EMBED RESOLVED | [" + lang + "] " + server + " | " + safeUrl(embed))

                        log("EXTRACTOR ROUTE START | [" + lang + "] " + server + " | " + safeUrl(embed))
                        withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                            val prefix = "[$lang] $server"
                            when {
                                server.equals("Gamma", ignoreCase = true) ||
                                    embed.contains("ashleyhardat.com", ignoreCase = true) -> {
                                    log("VOE ROUTE | $prefix | embed=" + safeUrl(embed))
                                    loadVoe(embed, prefix, emit)
                                }
                                BYSE_EMBED.containsMatchIn(embed) ||
                                    embed.contains("byseraguci.com", ignoreCase = true) -> {
                                    log("BYSE ROUTE | $prefix | embed=" + safeUrl(embed))
                                    loadByseManual(embed, prefix, "$WEB/ver/$slug/$episode", emit)
                                }
                                LULU_HOSTS.any { embed.contains(it, ignoreCase = true) } ->
                                    loadLulu(embed, prefix, emit)
                                else -> loadGeneric(embed, prefix, subtitleCallback, emit)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("player ERROR -> ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        log("LOADLINKS END | enlaces_emitidos=" + count.get() + " | players=" + players.size)
        return count.get() > 0
    }

    /** bridge_url -> URL del embed real (el iframe que devuelve el bridge). */
    private suspend fun resolveBridge(bridgeUrl: String): String? {
        log("BRIDGE REQUEST | " + safeUrl(bridgeUrl))
        val body = fetch(bridgeUrl) ?: run {
            log("BRIDGE FAIL | fetch devolvió null")
            return null
        }
        val src = IFRAME_SRC.find(body)?.groupValues?.get(1) ?: run {
            log("BRIDGE FAIL | no se encontró iframe src | chars=" + body.length + " | preview=" + body.take(180).replace("\n", " "))
            return null
        }
        return src.replace("&amp;", "&").let {
            if (it.startsWith("//")) "https:$it" else it
        }
    }

    private suspend fun retag(link: ExtractorLink, prefix: String): ExtractorLink =
        newExtractorLink(
            source = link.source,
            name = "$prefix · ${link.name}",
            url = link.url,
            type = link.type
        ) {
            this.referer = link.referer
            this.quality = link.quality
            this.headers = link.headers
            this.extractorData = link.extractorData
        }

    private val byseUa = "Mozilla/5.0 (Linux; Android 6.0.1; SM-G5510 Build/MMB29M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.125 Mobile Safari/537.36 SamsungBrowser/7.2"

    private fun byseB64Decode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        return java.util.Base64.getDecoder().decode(normalized + "=".repeat((4 - normalized.length % 4) % 4))
    }

    private fun byseB64Encode(value: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun byseSha256(value: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun byseRotate(value: Int, count: Int): Int = Integer.rotateLeft(value, count)

    private fun byseMix(state: IntArray) {
        state[0] += state[1]
        state[3] = byseRotate(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = byseRotate(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = byseRotate(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = byseRotate(state[1] xor state[2], 7)
    }

    private fun byseWorkHash(input: String): IntArray {
        val state = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())
        val memory = IntArray(512)
        for (ch in input) {
            state[0] = byseRotate(state[0] + ch.code, 7)
            byseMix(state)
        }
        repeat(8) { byseMix(state) }
        for (i in 0 until 512) {
            byseMix(state)
            memory[i] = state[0] xor state[2]
        }
        repeat(2) {
            for (i in 0 until 512) {
                val a = memory[i] and 511
                var c = memory[i] + memory[a]
                c = byseRotate(c, 13)
                c = c xor (memory[(i + 1) and 511] * 2654435761L.toInt())
                memory[i] = c
                state[0] = state[0] xor c
                byseMix(state)
            }
        }
        return IntArray(8) { i ->
            byseMix(state)
            var v = state[0]
            for (j in i * 64 until (i + 1) * 64) {
                val d = memory[j]
                v += d
                v = byseRotate(v, 5)
                v = v xor (d * 2246822519L.toInt())
            }
            v xor state[2]
        }
    }

    private fun byseLeadingZeroBits(words: IntArray): Int {
        var count = 0
        for (word in words) {
            if (word == 0) count += 32
            else return count + Integer.numberOfLeadingZeros(word)
        }
        return count
    }

    private suspend fun solveBysePow(nonce: String, difficulty: Int): String? {
        if (difficulty < 0 || difficulty > 32) return null
        if (difficulty <= 0) return "0"
        val started = System.nanoTime()
        var counter = 0
        while (counter < 200000 && (System.nanoTime() - started) / 1000000 < 14000) {
            if (byseLeadingZeroBits(byseWorkHash(nonce + ":" + counter)) >= difficulty) {
                log("Byse PoW resuelto bits=" + difficulty + " intentos=" + (counter + 1))
                return counter.toString()
            }
            counter++
            if (counter % 256 == 0) delay(1)
        }
        log("Byse PoW agotado bits=" + difficulty + " intentos=" + counter)
        return null
    }

    private fun byseFixedUnsigned(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray().let {
            if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        val out = ByteArray(32)
        val copy = raw.takeLast(32).toByteArray()
        System.arraycopy(copy, 0, out, 32 - copy.size, copy.size)
        return out
    }

    private fun byseDerSignatureToP1363(der: ByteArray): ByteArray {
        var offset = 0
        if (der.isEmpty() || der[offset++].toInt() != 0x30) error("firma ECDSA DER inválida")
        var sequenceLength = der[offset++].toInt() and 0xff
        if (sequenceLength and 0x80 != 0) {
            val count = sequenceLength and 0x7f
            sequenceLength = 0
            repeat(count) { sequenceLength = (sequenceLength shl 8) or (der[offset++].toInt() and 0xff) }
        }
        if (der[offset++].toInt() != 0x02) error("firma ECDSA r inválida")
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        if (der[offset++].toInt() != 0x02) error("firma ECDSA s inválida")
        val sLength = der[offset++].toInt() and 0xff
        val ss = der.copyOfRange(offset, offset + sLength)
        fun fit(bytes: ByteArray): ByteArray {
            val unsigned = if (bytes.size > 32 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
            return ByteArray(32).also { out ->
                val v = unsigned.takeLast(32).toByteArray()
                System.arraycopy(v, 0, out, 32 - v.size, v.size)
            }
        }
        return fit(r) + fit(ss)
    }

    private suspend fun bysePostJson(url: String, headers: Map<String, String>, payload: JSONObject, timeoutSeconds: Long = 15L): JSONObject {
        val response = app.post(
            url,
            headers = headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            json = JsonAsString(payload.toString()),
            timeout = timeoutSeconds
        )
        if (response.code !in 200..299) error("HTTP " + response.code + " " + URI(url).path)
        return JSONObject(response.text)
    }

    /** Resolución manual de Bysekoze siguiendo el flujo de AnimeYT/Kino. */
    private suspend fun loadByseManual(embed: String, prefix: String, sourcePage: String, emit: suspend (ExtractorLink) -> Unit) {
        val input = runCatching { URI(embed) }.getOrNull() ?: return
        val code = Regex("""/(?:e|embed|sovm9|boh)/([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(input.path.orEmpty())?.groupValues?.getOrNull(1) ?: return
        val initialHeaders = mapOf("User-Agent" to byseUa, "Referer" to sourcePage)
        try {
            val page = app.get(embed, headers = initialHeaders, timeout = 9)
            if (page.code !in 200..399) error("embed HTTP " + page.code)
            val pageUrl = page.url.ifBlank { embed }
            val origin = runCatching { URI(pageUrl).let { it.scheme + "://" + it.authority } }
                .getOrDefault(input.scheme + "://" + input.authority)
            val pagePath = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault(input.path.orEmpty())
            val embedMode = Regex("""/(?:e|embed|sovm9|boh)/""", RegexOption.IGNORE_CASE).containsMatchIn(pagePath)
            val apiPrefix = origin + "/api/videos/" + code + if (embedMode) "/embed" else ""
            val headers = initialHeaders + mapOf(
                "Origin" to origin,
                "Referer" to pageUrl,
                "X-Embed-Origin" to "https://mytsumi.com",
                "X-Embed-Referer" to "https://mytsumi.com/",
                "X-Embed-Parent" to embed
            )
            val settingsResponse = app.get(apiPrefix + "/settings", headers = headers, timeout = 12)
            if (settingsResponse.code !in 200..299) error("settings HTTP " + settingsResponse.code)
            val settings = JSONObject(settingsResponse.text)
            var fingerprint: JSONObject? = null
            var captchaToken = ""
            if (settings.optBoolean("captcha_required", false)) {
                val challenge = bysePostJson(origin + "/api/videos/access/challenge", headers, JSONObject(), 15)
                val nonce = challenge.optString("nonce")
                val challengeId = challenge.optString("challenge_id")
                if (nonce.isBlank() || challengeId.isBlank()) error("challenge incompleto")
                val generator = java.security.KeyPairGenerator.getInstance("EC")
                generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                val pair = generator.generateKeyPair()
                val publicKey = pair.public as java.security.interfaces.ECPublicKey
                val signer = java.security.Signature.getInstance("SHA256withECDSA")
                signer.initSign(pair.private)
                signer.update(nonce.toByteArray(Charsets.UTF_8))
                val signature = byseB64Encode(byseDerSignatureToP1363(signer.sign()))
                val random = java.security.SecureRandom().nextInt().toUInt().toLong() / 4294967295.0
                fun fpHash(n: Double): String = byseB64Encode(byseSha256(n.toString()))
                val client = JSONObject()
                    .put("user_agent", byseUa).put("architecture", "arm").put("bitness", "32")
                    .put("platform", "Android").put("platform_version", "6.0.1").put("model", "SM-G5510")
                    .put("ua_full_version", "59.0.3071.125")
                    .put("brand_full_versions", JSONArray().put(JSONObject().put("brand", "SamsungBrowser").put("version", "7.2")))
                    .put("pixel_ratio", 1).put("screen_width", 1280).put("screen_height", 720).put("color_depth", 24)
                    .put("languages", JSONArray().put("en-US")).put("timezone", "America/New_York")
                    .put("hardware_concurrency", 4).put("device_memory", 2).put("touch_points", 1)
                    .put("canvas_hash", fpHash(random)).put("audio_hash", fpHash(random + 1))
                    .put("fonts_hash", fpHash(random + 3)).put("codecs_hash", fpHash(random + 4))
                    .put("media_devices", "ai1ao1vi3").put("pointer_type", "coarse")
                    .put("extra", JSONObject().put("vendor", "Samsung").put("appVersion", "7.2"))
                val jwk = JSONObject().put("kty", "EC").put("crv", "P-256")
                    .put("x", byseB64Encode(byseFixedUnsigned(publicKey.w.affineX)))
                    .put("y", byseB64Encode(byseFixedUnsigned(publicKey.w.affineY)))
                    .put("ext", true).put("key_ops", JSONArray().put("verify"))
                val attest = bysePostJson(origin + "/api/videos/access/attest", headers,
                    JSONObject().put("viewer_id", "").put("device_id", "").put("challenge_id", challengeId)
                        .put("nonce", nonce).put("signature", signature).put("public_key", jwk)
                        .put("client", client).put("storage", JSONObject())
                        .put("attributes", JSONObject().put("entropy", "high")), 20)
                if (attest.optString("token").isBlank() || attest.optString("viewer_id").isBlank() || attest.optString("device_id").isBlank()) {
                    error("atestación incompleta")
                }
                fingerprint = JSONObject().put("token", attest.optString("token"))
                    .put("viewer_id", attest.optString("viewer_id")).put("device_id", attest.optString("device_id"))
                if (attest.has("confidence")) fingerprint.put("confidence", attest.opt("confidence"))
                val captcha = bysePostJson(apiPrefix + "/captcha", headers, JSONObject().put("fingerprint", fingerprint), 15)
                val powNonce = captcha.optString("pow_nonce")
                val powToken = captcha.optString("pow_token")
                if (powNonce.isBlank() || powToken.isBlank()) error("desafío PoW incompleto")
                val solution = solveBysePow(powNonce, captcha.optInt("pow_difficulty", -1)) ?: error("PoW agotó el tiempo")
                val verified = bysePostJson(apiPrefix + "/captcha/verify", headers,
                    JSONObject().put("pow_token", powToken).put("solution", solution).put("fingerprint", fingerprint), 15)
                captchaToken = verified.optString("token")
                if (captchaToken.isBlank()) error("verificación CAPTCHA sin token")
            }
            val playbackHeaders = headers + if (captchaToken.isBlank()) emptyMap() else mapOf("X-Captcha-Token" to captchaToken)
            val playback = bysePostJson(apiPrefix + "/playback", playbackHeaders,
                if (settings.optBoolean("captcha_required", false)) JSONObject().put("fingerprint", fingerprint) else JSONObject(), 20)
            var sources = playback.optJSONArray("sources")
            if (sources == null) {
                val encrypted = playback.optJSONObject("playback")
                val parts = encrypted?.optJSONArray("key_parts")
                if (encrypted != null && parts != null && parts.length() > 0) {
                    var keys = (0 until parts.length()).mapNotNull { parts.optString(it).takeIf(String::isNotBlank) }
                    val version = encrypted.optInt("version", 0)
                    if (version in 1..keys.size) keys = listOf(keys[version - 1], keys[keys.size - version])
                    val key = keys.flatMap { byseB64Decode(it).toList() }.toByteArray()
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, byseB64Decode(encrypted.optString("iv"))))
                    sources = JSONObject(String(cipher.doFinal(byseB64Decode(encrypted.optString("payload"))), Charsets.UTF_8)).optJSONArray("sources")
                }
            }
            if (sources == null || sources.length() == 0) error("playback sin fuentes")
            val choices = (0 until sources.length()).mapNotNull { i ->
                val item = sources.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                if (url.isBlank()) null else Pair(item.optString("label", item.optString("name", "Bysekoze")), url)
            }.sortedByDescending { Regex("""(\d{3,4})\s*[pP]?""").find(it.first)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 }
            val chosen = choices.firstOrNull { it.second.startsWith("http", true) } ?: choices.firstOrNull()
                ?: error("fuentes sin URL")
            val streamUrl = URI(origin).resolve(chosen.second).toString()
            val streamHeaders = mapOf(
                "User-Agent" to byseUa, "Referer" to pageUrl, "Origin" to origin,
                "X-Embed-Origin" to "https://mytsumi.com", "X-Embed-Referer" to "https://mytsumi.com/",
                "X-Embed-Parent" to embed
            )
            val isHls = Regex("""\.m3u8(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(streamUrl)
            val isDash = Regex("""\.mpd(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(streamUrl)
            var actualQuality = Regex("""(\\d{3,4})\\s*[pP]?""").find(chosen.first)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
            if (isHls) {
                try {
                    val manifest = app.get(streamUrl, headers = streamHeaders, timeout = 8)
                    val heights = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .findAll(manifest.text).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
                    if (heights.isNotEmpty()) actualQuality = heights.maxOrNull() ?: actualQuality
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (actualQuality > 720) actualQuality = Qualities.Unknown.value
                }
            }
            val qualityLabel = if (actualQuality >= 2160) "2160p" else if (actualQuality >= 1440) "1440p"
                else if (actualQuality >= 1080) "1080p" else if (actualQuality >= 720) "720p"
                else if (actualQuality >= 480) "480p" else if (actualQuality >= 360) "360p" else "Auto"
            val link = newExtractorLink(
                source = name,
                name = "$prefix · Bysekoze · " + qualityLabel,
                url = streamUrl,
                type = if (isHls || isDash) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = pageUrl
                quality = actualQuality
                this.headers = streamHeaders
            }
            emit(link)
            log("Byse resuelto host=" + URI(streamUrl).host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Byse resolver ERROR " + input.host + ": " + e.javaClass.simpleName + ": " + e.message)
        }
    }


    /** Resuelve Gamma/VOE manualmente, sin depender de loadExtractor. */
    private suspend fun loadVoe(
        embedUrl: String,
        prefix: String,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        try {
            log("VOE START | $prefix | embed=" + safeUrl(embedUrl))
            val sourceUrl = withTimeoutOrNull(VOE_TIMEOUT_NS / 1_000_000L) {
                resolveVoe(embedUrl)
            }
            if (sourceUrl.isNullOrBlank()) {
                log("VOE FAIL | $prefix | no se pudo extraer source")
                return
            }
            log("VOE SOURCE | $prefix | " + safeUrl(sourceUrl))
            emit(newExtractorLink(
                source = "VOE",
                name = "$prefix · VOE",
                url = sourceUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = Qualities.Unknown.value
            })
            log("VOE EMIT | $prefix")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("VOE ERROR | $prefix | ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun resolveVoe(embedUrl: String): String? {
        val headers = mapOf("User-Agent" to VOE_BROWSER_UA)

        val firstResponse = app.get(embedUrl, headers = headers)
        if (!firstResponse.isSuccessful) return null

        val redirectUrl = VOE_REDIRECT.find(firstResponse.text)?.groupValues?.getOrNull(1)
        val realUrl = redirectUrl ?: embedUrl

        val pageResponse = if (realUrl != embedUrl) {
            app.get(realUrl, headers = headers)
        } else {
            firstResponse
        }

        var html = pageResponse.text
        var encodedConfig = VOE_CONFIG.find(html)?.groupValues?.getOrNull(1)

        if (encodedConfig.isNullOrBlank()) {
            html = solveVoeAltcha(realUrl, html, pageResponse.cookies) ?: return null
            encodedConfig = VOE_CONFIG_STRICT.find(html)?.groupValues?.getOrNull(1)
        }

        if (encodedConfig.isNullOrBlank()) return null

        val decodedConfig = decodeVoeConfig(encodedConfig)
        if (decodedConfig.isNullOrBlank()) return null

        val source = VOE_SOURCE.find(decodedConfig)?.groupValues?.getOrNull(1)
        if (source.isNullOrBlank()) return null

        return source
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
    }

    /** Resuelve el desafío ALTCHA de VOE y devuelve el HTML de la página resultante. */
    private suspend fun solveVoeAltcha(
        realUrl: String,
        html: String,
        pageCookies: Map<String, String>
    ): String? {
        val csrf = VOE_TOKEN.find(html)?.groupValues?.getOrNull(1)
        val challengeUrl = VOE_CHALLENGE.find(html)?.groupValues?.getOrNull(1)

        if (csrf.isNullOrBlank() || challengeUrl.isNullOrBlank()) return null

        val challengeResponse = app.get(
            challengeUrl,
            headers = mapOf(
                "User-Agent" to VOE_BROWSER_UA,
                "Referer" to realUrl
            )
        )
        if (!challengeResponse.isSuccessful) return null

        val challengeJson = challengeResponse.text

        val algorithm = jsonString(challengeJson, "algorithm")
        val cost = jsonInt(challengeJson, "cost")
        val keyLength = jsonInt(challengeJson, "keyLength")
        val keyPrefix = jsonString(challengeJson, "keyPrefix")
        val nonce = jsonString(challengeJson, "nonce")
        val salt = jsonString(challengeJson, "salt")

        if (
            algorithm.isNullOrBlank() ||
            cost == null ||
            keyLength == null ||
            keyPrefix.isNullOrBlank() ||
            nonce.isNullOrBlank() ||
            salt.isNullOrBlank()
        ) {
            return null
        }

        val saltBytes = hexToBytes(salt)
        val nonceBytes = hexToBytes(nonce)
        val mac = Mac.getInstance("HmacSHA256")

        var solvedCounter = -1
        var solvedKey = ""
        val powStart = System.nanoTime()

        for (counter in 0 until 1_000_000) {
            // Corte de seguridad para no colgar la carga de links.
            if ((counter and 0xff) == 0 && System.nanoTime() - powStart > VOE_TIMEOUT_NS) break

            val counterBytes = byteArrayOf(
                ((counter ushr 24) and 0xff).toByte(),
                ((counter ushr 16) and 0xff).toByte(),
                ((counter ushr 8) and 0xff).toByte(),
                (counter and 0xff).toByte()
            )

            val derived = pbkdf2Sha256(mac, nonceBytes + counterBytes, saltBytes, cost, keyLength)
            val hex = bytesToHex(derived)

            if (hex.startsWith(keyPrefix)) {
                solvedCounter = counter
                solvedKey = hex
                break
            }
        }

        if (solvedCounter < 0) return null

        val powElapsedMs = (System.nanoTime() - powStart) / 1_000_000.0

        val altchaPayload =
            """{"challenge":$challengeJson,"solution":{"counter":$solvedCounter,"derivedKey":"$solvedKey","time":$powElapsedMs}}"""

        val payloadB64 = Base64.getEncoder()
            .encodeToString(altchaPayload.toByteArray(Charsets.UTF_8))

        val voeCookies = pageCookies.entries
            .joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }

        // Antes el Origin estaba fijo a un dominio; ahora sale de la URL real.
        val origin = "https://" + realUrl.substringAfter("://").substringBefore("/")

        val postResponse = app.post(
            realUrl,
            headers = mapOf(
                "User-Agent" to VOE_BROWSER_UA,
                "Referer" to realUrl,
                "Origin" to origin,
                "Cookie" to voeCookies,
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            data = mapOf(
                "_token" to csrf,
                "access" to "0",
                "altcha" to payloadB64
            )
        )

        if (!postResponse.isSuccessful) return null
        return postResponse.text
    }

    private fun jsonString(json: String, key: String): String? =
        Regex("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"")
            .find(json)?.groupValues?.getOrNull(1)

    private fun jsonInt(json: String, key: String): Int? =
        Regex("\"" + key + "\"\\s*:\\s*(\\d+)")
            .find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun decodeVoeConfig(encoded: String): String? {
        return try {
            var value = buildString {
                for (char in encoded) {
                    append(
                        when (char) {
                            in 'A'..'Z' ->
                                ((char.code - 'A'.code + 13) % 26 + 'A'.code).toChar()

                            in 'a'..'z' ->
                                ((char.code - 'a'.code + 13) % 26 + 'a'.code).toChar()

                            else -> char
                        }
                    )
                }
            }

            val separators = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

            for (separator in separators) {
                value = value.replace(separator, "_")
            }

            value = value.replace("_", "")

            var bytes = Base64.getDecoder().decode(value)

            bytes = bytes.map { (it.toInt() - 3).toByte() }.toByteArray()
            bytes.reverse()

            val decoded = Base64.getDecoder().decode(String(bytes, Charsets.UTF_8))

            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            log("VOE decode ERROR -> ${e.message}")
            null
        }
    }

    /**
     * PBKDF2-HMAC-SHA256. El Mac se inicializa una sola vez por llamada (doFinal
     * lo deja listo con la misma clave), en vez de re-inicializarlo en cada
     * iteración: es lo que más pesaba al resolver el ALTCHA.
     */
    private fun pbkdf2Sha256(
        mac: Mac,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        mac.init(SecretKeySpec(password, "HmacSHA256"))

        val hashLength = mac.macLength
        val blockCount = (keyLength + hashLength - 1) / hashLength
        val output = ByteArray(blockCount * hashLength)

        for (block in 1..blockCount) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    ((block ushr 24) and 0xff).toByte(),
                    ((block ushr 16) and 0xff).toByte(),
                    ((block ushr 8) and 0xff).toByte(),
                    (block and 0xff).toByte()
                )
            )

            var u = mac.doFinal()
            val t = u.copyOf()

            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            System.arraycopy(t, 0, output, (block - 1) * hashLength, hashLength)
        }

        return output.copyOf(keyLength)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = VOE_HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = VOE_HEX_CHARS[v and 0x0f]
        }
        return String(chars)
    }

    private fun hexToBytes(value: String): ByteArray {
        val clean = value.trim()

        if (clean.length % 2 != 0) {
            throw IllegalArgumentException("Hex inválido: longitud impar")
        }

        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
    /**
     * LuluStream: la página /e/<código> trae un JS "packed" (eval(function(p,a,c,k,e,d)...)
     * que al desempaquetarse deja jwplayer.setup({sources:[{file:"...master.m3u8?t=..."}]}).
     */
    private suspend fun loadLulu(
        embed: String,
        prefix: String,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        val res = app.get(embed, headers = mapOf("Referer" to "$API/"))
        if (!res.isSuccessful) {
            log("Lulu HTTP ${res.code}")
            return
        }
        val text = res.text

        val unpacked = try {
            getAndUnpack(text)
        } catch (e: Exception) {
            ""
        }

        val m3u8 = M3U8_FILE.find(unpacked)?.groupValues?.get(1)
            ?: M3U8_ANY.find(unpacked)?.value
            ?: M3U8_ANY.find(text)?.value
            ?: run {
                log("Lulu: no se encontró m3u8")
                return
            }

        val origin = "https://" + embed.substringAfter("://").substringBefore("/")

        emit(
            newExtractorLink(
                source = "LuluStream",
                name = "$prefix · LuluStream",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$origin/"
                quality = Qualities.Unknown.value
            }
        )
    }
}
