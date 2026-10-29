package com.animelatinohd

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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/* ====================================================================
 * Config
 * ==================================================================== */

private const val DEBUG = false

private const val WEB = "https://www.animelatinohd.com"
private const val API = "https://website.animelatinohd.com"
private const val TMDB = "https://image.tmdb.org/t/p"

private const val HOME_CACHE_MS = 180_000L
private const val BRIDGE_TIMEOUT_MS = 10_000L
private const val EXTRACTOR_TIMEOUT_MS = 9_000L

private val LULU_HOSTS = listOf("luluvdoo.com", "luluvdo.com", "lulustream.com", "lulu.st")
private val IFRAME_SRC = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val M3U8_FILE = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
private val M3U8_ANY = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")

private fun log(msg: String) {
    if (DEBUG) println("AnimeLHD: $msg")
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

            val res = app.get(url, headers = headers, interceptor = cfInterceptor)
            if (res.isSuccessful) res.text
            else {
                log("HTTP ${res.code} en $url")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("fetch ERROR $url -> ${e.javaClass.simpleName}: ${e.message}")
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
        val slug = parts[0].trim()
        val episode = parts[1].trim().toIntOrNull() ?: return false

        val html = fetch("$WEB/ver/$slug/$episode") ?: return false
        val flat = flattenRsc(html)

        val players = jsonAfter(flat, "\"players\":")
            ?.let { JSONArray(it).objs() }
            ?: return false

        log("players=${players.size}")

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

                        val embed = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) { resolveBridge(bridge) }
                        if (embed == null) {
                            log("bridge sin iframe: $lang $server")
                            return@async
                        }
                        log("$lang $server -> $embed")

                        withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                            if (LULU_HOSTS.any { embed.contains(it, ignoreCase = true) }) {
                                loadLulu(embed, "[$lang] $server", emit)
                            } else {
                                loadGeneric(embed, "[$lang] $server", subtitleCallback, emit)
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

        return count.get() > 0
    }

    /** bridge_url -> URL del embed real (el iframe que devuelve el bridge). */
    private suspend fun resolveBridge(bridgeUrl: String): String? {
        val body = fetch(bridgeUrl) ?: return null
        val src = IFRAME_SRC.find(body)?.groupValues?.get(1) ?: return null
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

    /** Extractores que ya trae CloudStream (Byse, etc.). */
    private suspend fun loadGeneric(
        embed: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        val collected = ConcurrentLinkedQueue<ExtractorLink>()
        loadExtractor(embed, "$API/", subtitleCallback) { collected.add(it) }
        for (link in collected) emit(retag(link, prefix))
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
