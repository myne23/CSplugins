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
private val BYSE_EMBED = Regex("""^https?://(?:www\\.)?byse[a-z0-9-]*\\.[a-z]+/(.*)$""", RegexOption.IGNORE_CASE)
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

    /** Byse y otros extractores de CloudStream. Byse necesita probar sus mirrors
     * y varios Referer, igual que el resolver ya probado en SeriesDonghua. */
    private suspend fun loadGeneric(
        embed: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        val isByse = BYSE_EMBED.containsMatchIn(embed)
        val path = BYSE_EMBED.find(embed)?.groupValues?.getOrNull(1).orEmpty()
        val candidates = if (isByse) {
            listOfNotNull(
                embed,
                "$BYSE_FALLBACK_HOST/$path".takeIf {
                    path.isNotBlank() && !embed.startsWith(BYSE_FALLBACK_HOST, ignoreCase = true)
                }
            )
        } else {
            listOf(embed)
        }

        val origin = "https://" + embed.substringAfter("://").substringBefore("/")
        val referers = listOf("$API/", "$WEB/", "$origin/").distinct()
        val collected = ConcurrentLinkedQueue<ExtractorLink>()

        log("LOAD EXTRACTOR START | " + prefix + " | byse=" + isByse + " | embed=" + safeUrl(embed))

        for (candidate in candidates) {
            for (ref in referers) {
                val before = collected.size
                try {
                    log("EXTRACTOR ATTEMPT | " + prefix + " | candidate=" + safeUrl(candidate) + " | referer=" + safeUrl(ref))
                    loadExtractor(candidate, ref, subtitleCallback) { link ->
                        log("EXTRACTOR LINK RAW | " + prefix + " | source=" + link.source + " | name=" + link.name + " | type=" + link.type + " | quality=" + link.quality + " | url=" + safeUrl(link.url) + " | referer=" + safeUrl(link.referer))
                        collected.add(link)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("EXTRACTOR ATTEMPT ERROR | " + prefix + " | " + e.javaClass.simpleName + ": " + e.message)
                }
                if (collected.size > before) break
            }
            if (collected.isNotEmpty()) break
        }

        log("LOAD EXTRACTOR END | " + prefix + " | links=" + collected.size)
        for (link in collected.distinctBy { it.url }) {
            val tagged = retag(link, prefix)
            log("EXTRACTOR LINK EMIT | " + prefix + " | " + tagged.name + " | type=" + tagged.type + " | quality=" + tagged.quality + " | url=" + safeUrl(tagged.url))
            emit(tagged)
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
