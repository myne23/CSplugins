package com.marcelo.seriesdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

class SeriesDonghuaProvider : MainAPI() {
    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Orden de las secciones del catálogo.
    override val mainPage = mainPageOf(
        "$mainUrl/episodios/" to "Nuevos Episodios Seriesdonghua",
        DW_NEW_URL to "Nuevos Episodios Donghuaworld",
        "$mainUrl/todos-los-donghuas/" to "Catalogo Completo Seriesdonghua",
        "$DW_URL/anime/" to "Catalogo Completo Donghuaworld"
    )

    private companion object {
        // Poné DEBUG = true solo cuando quieras ver logs en logcat.
        const val DEBUG = false

        const val DW_URL = "https://donghuaworld.com"

        // Home de DonghuaWorld: de ahí sale la sección "Latest Release" (últimos capítulos).
        const val DW_NEW_URL = "$DW_URL/"

        // Tiempos máximos (segundos) para DonghuaWorld: si la web no responde, se la deja de lado.
        const val DW_FAST_TIMEOUT_S = 4L
        const val DW_PAGE_TIMEOUT_S = 6L

        const val BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
        const val ALTCHA_TIMEOUT_NS = 25_000_000_000L

        val SERVERS = listOf(
            "Dailymotion" to 0,
            "OK.ru" to 1,
            "Rumble" to 2,
            "Filemoon" to 3,
            "VOE" to 4
        )

        val HEX_CHARS = "0123456789abcdef".toCharArray()
        val POSTER_ATTRS = listOf("src", "data-src", "data-lazy-src", "data-original", "data-url")

        val NON_ALNUM = Regex("[^a-z0-9]+")
        val EPISODE_IN_TITLE = Regex("""\s+Episode\s+\d""", RegexOption.IGNORE_CASE)
        val DW_SERIES_URL = Regex("""^https?://[^/?#]+/anime/[^/?#]+/?$""")
        val EPISODE_SLUG = Regex("/([^/]+)-episodio-(\\d+)/?$")
        val IFRAME_SRC = Regex("""src=["']([^"']+)["']""")
        val TRACK = Regex("""\{"file":"([^"]+)","label":"([^"]+)"\}""")
        val DM_PATH_ID = Regex("/video/([A-Za-z0-9]+)")

        val ODYSEE_CONTENT_URL = Regex(
            """"contentUrl"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )
        val OK_HLS_URL = Regex(
            """"hlsManifestUrl":"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )

        val VOE_REDIRECT = Regex(
            """window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]"""
        )
        val VOE_CONFIG = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']"""
        )
        val VOE_CONFIG_STRICT = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>"""
        )
        val VOE_TOKEN = Regex("""name=["']_token["'][^>]+value=["']([^"']+)["']""")
        val VOE_CHALLENGE = Regex("""<altcha-widget[^>]+challenge=["']([^"']+)["']""")
        val VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private inline fun debug(message: () -> String) {
        if (DEBUG) println("SeriesDonghua: ${message()}")
    }

    /** Ejecuta el bloque y devuelve null si falla (sin tragarse la cancelación). */
    private suspend inline fun <T> attempt(block: () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debug { "ERROR ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    private suspend fun guarded(tag: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debug { "ERROR $tag -> ${e.javaClass.simpleName}: ${e.message}" }
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(NON_ALNUM, " ").trim()

    // Antes " movie " nunca coincidía con títulos terminados en "Movie" porque
    // el texto normalizado queda sin espacio final.
    private fun isMovieTitle(normalized: String): Boolean =
        " $normalized ".contains(" movie ")

    private fun Element?.imageUrl(): String? {
        if (this == null) return null
        for (key in POSTER_ATTRS) {
            val value = attr(key).trim()
            if (
                value.isNotBlank() &&
                !value.startsWith("data:image/") &&
                !value.contains("lazy_placeholder")
            ) {
                return value
            }
        }
        return null
    }

    private fun titleScore(title: String, query: String, titleInQueryScore: Int): Int = when {
        // Un título vacío (p. ej. solo caracteres no latinos) coincidía con todo.
        title.isBlank() || query.isBlank() -> 0
        title == query -> 100
        title.startsWith("$query ") -> 95
        title.contains(query) -> 90
        query.contains(title) -> titleInQueryScore
        isOneTypoAway(title, query) -> 70
        else -> 0
    }

    private fun isOneTypoAway(first: String, second: String): Boolean {
        val firstTokens = first.split(" ").filter { it.isNotBlank() }
        val secondTokens = second.split(" ").filter { it.isNotBlank() }

        if (firstTokens.size != secondTokens.size) return false

        var differences = 0

        for (index in firstTokens.indices) {
            val a = firstTokens[index]
            val b = secondTokens[index]

            if (a == b) continue
            if (kotlin.math.abs(a.length - b.length) > 1) return false

            var i = 0
            var j = 0
            var edits = 0

            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) {
                    i++
                    j++
                } else {
                    edits++
                    if (edits > 1) return false

                    when {
                        a.length > b.length -> i++
                        b.length > a.length -> j++
                        else -> {
                            i++
                            j++
                        }
                    }
                }
            }

            edits += (a.length - i) + (b.length - j)
            if (edits != 1) return false

            differences++
            if (differences > 1) return false
        }

        return differences == 1
    }

    /** Recolecta links sin repetir URLs ni trabajo ya hecho; seguro para uso paralelo. */
    private class LinkSink(private val callback: (ExtractorLink) -> Unit) {
        private val seenUrls = ConcurrentHashMap.newKeySet<String>()
        private val claimed = ConcurrentHashMap.newKeySet<String>()
        private val counter = AtomicInteger(0)

        val total: Int get() = counter.get()

        /** true solo la primera vez que se pide esa clave. */
        fun claim(key: String): Boolean = claimed.add(key)

        fun emit(link: ExtractorLink): Boolean {
            if (!seenUrls.add(link.url)) return false
            counter.incrementAndGet()
            callback(link)
            return true
        }
    }

    // ───────────────────────── Página principal ─────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isDwLatest = request.data == DW_NEW_URL

        val url = when {
            page == 1 -> request.data
            // La home de DonghuaWorld pagina como /page/2/, no con ?page=2.
            isDwLatest -> "$DW_URL/page/$page/"
            else -> {
                val separator = if (request.data.contains("?")) "&" else "?"
                "${request.data}${separator}page=$page"
            }
        }

        val isDonghuaWorld = request.data.startsWith(DW_URL)

        // Cada sección falla por separado: si una página se cae, la sección queda vacía
        // y el resto del catálogo sigue funcionando.
        val home: List<SearchResponse> = attempt {
            if (isDonghuaWorld) {
                val document = app.get(url, timeout = DW_FAST_TIMEOUT_S).document
                if (isDwLatest) parseDonghuaWorldLatest(document) else parseDonghuaWorldHome(document)
            } else {
                val document = app.get(url).document
                if (request.data.contains("/episodios/")) {
                    parseNewEpisodes(document)
                } else {
                    document.select("article.donghua-card").mapNotNull { it.toSearchResult() }
                }
            }
        } ?: run {
            debug { "sección '${request.name}' no disponible, se omite" }
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, home)),
            hasNext = home.isNotEmpty()
        )
    }

    private fun parseDonghuaWorldHome(document: Document): List<SearchResponse> =
        document.select("article.bs").mapNotNull { article ->
            val link = article.selectFirst("a[href*='/anime/'][itemprop='url'][title]")
                ?: return@mapNotNull null

            val href = link.attr("href").trim()
            val title = link.attr("title").trim()

            if (href.isBlank() || title.isBlank() || href.contains("/anime/?")) {
                return@mapNotNull null
            }

            if (isMovieTitle(normalize(title))) return@mapNotNull null

            val poster = article.selectFirst("img[itemprop='image']").imageUrl()

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }

    /**
     * Solo "Latest Release" de la home de DonghuaWorld (no el carrusel "Series Update"):
     * cada ítem es un capítulo. Se muestra con el
     * nombre de la serie y, al abrirlo, load() resuelve la serie para que elijas el capítulo.
     */
    private fun parseDonghuaWorldLatest(document: Document): List<SearchResponse> {
        // Solo la sección "Latest Release". Arriba está el carrusel "Series Update", que trae
        // otras series y se repite en todas las páginas: no se debe mezclar.
        val heading = document
            .select("h1, h2, h3, h4")
            .firstOrNull { it.text().trim().startsWith("Latest Release", ignoreCase = true) }
            ?: run {
                debug { "no se encontró el título 'Latest Release'" }
                return emptyList()
            }

        // El primer ancestro del título que contiene ítems es la sección completa.
        val section = heading.parents().firstOrNull { it.selectFirst("article, .bs") != null }
            ?: return emptyList()

        val articles = section.select("article, .bs")

        return articles.mapNotNull { article ->
            val link = article.selectFirst("a[href][title]")
                ?: article.selectFirst("a[href]")
                ?: return@mapNotNull null

            val href = link.attr("href").trim()
            if (href.isBlank() || href.contains("/anime/") || !href.contains("-episode-")) {
                return@mapNotNull null
            }

            val rawTitle = link.attr("title").trim()
                .ifBlank { article.selectFirst(".tt")?.ownText()?.trim().orEmpty() }

            // "Battle Through the Heavens Season 5 Episode 214 (4K) ..." -> nombre de la serie.
            val title = EPISODE_IN_TITLE.find(rawTitle)
                ?.let { rawTitle.substring(0, it.range.first) }
                ?.trim()
                ?: rawTitle

            if (title.isBlank() || isMovieTitle(normalize(title))) return@mapNotNull null

            val poster = article.selectFirst("img").imageUrl()

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }
    }

    private fun parseNewEpisodes(document: Document): List<SearchResponse> =
        document.select("article.donghua-card").mapNotNull { card ->
            val episodeUrl = card.selectFirst("a")?.attr("href")?.trim()
                ?: return@mapNotNull null

            val seriesPath = episodeUrl.substringBeforeLast("-episodio-").trimEnd('/')
            if (seriesPath.isBlank()) return@mapNotNull null

            val title = card.selectFirst(".card-title")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = card.selectFirst("img").imageUrl()

            newAnimeSearchResponse(title, fixUrl("$seriesPath/"), TvType.Anime) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = selectFirst(".card-title")?.text()?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: anchor.attr("title").trim()

        if (title.isBlank()) return null

        val poster = selectFirst("img").imageUrl()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // ───────────────────────── Búsqueda ─────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        // Ambas páginas se consultan a la vez; el orden del resultado se mantiene.
        // Si una falla, devuelve lista vacía y se muestran los resultados de la otra.
        val sources = listOf<suspend () -> List<SearchResponse>>(
            {
                attempt {
                    app.get("$mainUrl/buscar.php?s=$encodedQuery")
                        .document
                        .select("article.donghua-card")
                        .mapNotNull { it.toSearchResult() }
                } ?: emptyList()
            },
            { searchDonghuaWorld(encodedQuery, normalizedQuery) }
        )

        return sources.amap { it() }.flatten()
    }

    private suspend fun searchDonghuaWorld(
        encodedQuery: String,
        normalizedQuery: String
    ): List<SearchResponse> {
        val document = attempt {
            app.get("$DW_URL/?s=$encodedQuery", timeout = DW_FAST_TIMEOUT_S).document
        } ?: return emptyList()

        return document
            .select("a[href*='/anime/'][itemprop='url'][title]")
            .mapNotNull { element ->
                val href = element.attr("href").trim()
                val title = element.attr("title").trim()

                if (href.isBlank() || title.isBlank() || href.contains("/anime/?")) {
                    return@mapNotNull null
                }

                val normalizedTitle = normalize(title)
                if (isMovieTitle(normalizedTitle)) return@mapNotNull null

                val score = titleScore(normalizedTitle, normalizedQuery, 80)
                if (score <= 0) return@mapNotNull null

                val card = element.parents().firstOrNull { it.tagName() == "article" }
                val image = card?.selectFirst("img[itemprop='image']")
                    ?: card?.selectFirst("img")
                val poster = image.imageUrl()

                val response = newAnimeSearchResponse(
                    "$title · DonghuaWorld",
                    fixUrl(href),
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(poster)
                }

                score to response
            }
            .distinctBy { it.second.url }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    // ───────────────────────── Detalle de serie ─────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("donghuaworld.com/anime/")) {
            return loadDonghuaWorldSeries(url)
        }

        // Capítulo suelto de "Nuevos Episodios Donghuaworld": se abre la serie completa.
        if (url.contains("donghuaworld.com/")) {
            val seriesUrl = resolveDonghuaWorldSeriesUrl(url) ?: return null
            return loadDonghuaWorldSeries(seriesUrl)
        }

        val document = attempt { app.get(url).document } ?: return null

        val title = document.selectFirst("h1.hero-title")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("img.hero-poster")?.attr("src")

        val plot = document
            .select(".glass-panel p")
            .joinToString("\n") { it.text() }

        val tags = document
            .select(".genre-pill-list a.genre-pill")
            .map { it.text() }

        val episodes = document
            .select("article.episode-card-item")
            .mapNotNull { epElement ->
                val epLink = epElement.selectFirst("a")?.attr("href")
                    ?: return@mapNotNull null

                val epNum = epElement.attr("data-ep").toIntOrNull()
                val epTitle = epElement.selectFirst(".card-title")?.text()
                val epPoster = epElement.selectFirst("img")?.attr("src")

                newEpisode(fixUrl(epLink)) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(epPoster)
                }
            }
            .reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.tags = tags
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /**
     * Dado el link de un capítulo de DonghuaWorld, devuelve el link de su serie (/anime/...).
     * La página del capítulo enlaza a la serie desde varios lugares (ruta de navegación,
     * "All Episodes", ficha de la serie), pero el sidebar también trae otras series populares.
     * Por eso se elige el link /anime/ cuyo slug comparte más palabras con el del capítulo.
     */
    private suspend fun resolveDonghuaWorldSeriesUrl(episodeUrl: String): String? {
        val episodeSlug = episodeUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        val episodeTokens = episodeSlug.split('-').filter { it.isNotBlank() }.toSet()

        val fromPage = attempt {
            val document = app.get(episodeUrl, timeout = DW_PAGE_TIMEOUT_S).document
            val seen = HashSet<String>()
            var best: String? = null
            var bestScore = 0

            for (anchor in document.select("a[href*='/anime/']")) {
                val href = fixUrl(anchor.attr("href").trim().substringBefore("#"))
                if (!DW_SERIES_URL.matches(href)) continue

                val key = href.trimEnd('/') + "/"
                if (!seen.add(key)) continue

                val slugTokens = key.trimEnd('/').substringAfterLast('/')
                    .split('-')
                    .filter { it.isNotBlank() }
                if (slugTokens.isEmpty()) continue

                val score = slugTokens.count { it in episodeTokens }

                // Con empate gana el primero que aparece (la ruta de navegación).
                if (score > bestScore && score * 2 >= slugTokens.size) {
                    best = key
                    bestScore = score
                }
            }

            best
        }
        if (fromPage != null) return fromPage

        // Si la página del capítulo no respondió o no tenía el link, se busca la serie por nombre.
        val seriesName = episodeSlug
            .substringBefore("-episode-")
            .replace("-", " ")
            .trim()
        if (seriesName.isBlank()) return null

        return attempt { findDonghuaWorldSeries(seriesName).firstOrNull()?.second }
    }

    private suspend fun loadDonghuaWorldSeries(url: String): LoadResponse? {
        val document = attempt {
            app.get(url, timeout = DW_PAGE_TIMEOUT_S).document
        } ?: return null

        val title = document
            .selectFirst("h1.entry-title[itemprop='name']")
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst("img.ts-post-image[itemprop='image']")
            ?.attr("src")
            ?.trim()

        val episodes = document
            .select("a[href*='episode-']")
            .mapNotNull { episodeElement ->
                val episodeUrl = episodeElement.attr("href").trim()
                if (episodeUrl.isBlank()) return@mapNotNull null

                val container = episodeElement.parent() ?: return@mapNotNull null

                val episodeNumber = container
                    .selectFirst(".epl-num")
                    ?.text()
                    ?.trim()
                    ?.substringBefore("[")
                    ?.substringBefore("(")
                    ?.trim()
                    ?.toIntOrNull()
                    ?: return@mapNotNull null

                val episodeTitle = container.selectFirst(".epl-title")?.text()?.trim()

                Triple(episodeNumber, fixUrl(episodeUrl), episodeTitle)
            }
            .distinctBy { it.second }
            .sortedByDescending { it.first }
            .map { (episodeNumber, episodeUrl, episodeTitle) ->
                newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                    this.posterUrl = fixUrlNull(poster)
                }
            }

        return newAnimeLoadResponse("$title · DonghuaWorld", url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ───────────────────────── Links ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sink = LinkSink(callback)

        // Episodio que viene directo de DonghuaWorld.
        if (data.contains("donghuaworld.com/") && data.contains("-episode-")) {
            guarded("DonghuaWorld episodio") {
                loadDonghuaWorldEpisodeLinks(data, subtitleCallback, sink)
            }
            return sink.total > 0
        }

        // Todas las fuentes (los 5 servidores + DonghuaWorld) corren en paralelo.
        val tasks = mutableListOf<suspend () -> Unit>()

        val pageResponse = attempt { app.get(data) }

        if (pageResponse != null) {
            val document = pageResponse.document

            val videoId = document
                .selectFirst("[data-video-id]")
                ?.attr("data-video-id")
                ?.trim()
                ?.toIntOrNull()

            val csrf = document
                .selectFirst("meta[name=csrf-token]")
                ?.attr("content")
                ?.trim()

            if (videoId != null && !csrf.isNullOrBlank()) {
                val cookies = pageResponse.cookies.entries
                    .joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }

                for ((serverName, serverIndex) in SERVERS) {
                    tasks.add {
                        guarded(serverName) {
                            loadSeriesDonghuaServer(
                                serverName = serverName,
                                serverIndex = serverIndex,
                                videoId = videoId,
                                csrf = csrf,
                                cookies = cookies,
                                pageUrl = data,
                                subtitleCallback = subtitleCallback,
                                sink = sink
                            )
                        }
                    }
                }
            } else {
                debug { "no se encontró data-video-id o CSRF" }
            }
        }

        // Fuente adicional: DonghuaWorld. Corre aparte y con timeouts cortos en cada request:
        // si esa web está caída o lenta, no rompe los links de SeriesDonghua.
        tasks.add {
            guarded("DonghuaWorld") {
                loadDonghuaWorldLinks(data, subtitleCallback, sink)
            }
        }

        tasks.amap { it() }

        debug { "loadLinks final linkCount=${sink.total}" }
        return sink.total > 0
    }

    private suspend fun loadSeriesDonghuaServer(
        serverName: String,
        serverIndex: Int,
        videoId: Int,
        csrf: String,
        cookies: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        val requestBody = JSONObject()
            .put("video_id", videoId)
            .put("server_index", serverIndex)
            .toString()

        val postResponse = app.post(
            "$mainUrl/api/player/get-server",
            headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to pageUrl,
                "Origin" to mainUrl,
                "Cookie" to cookies,
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/plain, */*",
                "X-CSRF-TOKEN" to csrf,
                "X-Requested-With" to "XMLHttpRequest"
            ),
            json = JsonAsString(requestBody)
        )

        if (postResponse.code !in 200..299) return

        val json = attempt { JSONObject(postResponse.text) } ?: return
        if (!json.optBoolean("success", false)) return

        val embedUrl = json.optString("embed_url").trim()
        if (embedUrl.isBlank()) return

        // Si dos servidores devuelven el mismo embed, se resuelve una sola vez.
        if (!sink.claim("embed:$embedUrl")) return

        val embedLower = embedUrl.lowercase()
        debug { "$serverName index=$serverIndex embed real=$embedUrl" }

        /*
         * IMPORTANTE:
         * El server_index/nombre que devuelve la página no siempre coincide
         * con el host real del embed. Por eso se resuelve según embed_url.
         */
        when {
            embedLower.contains("odysee.com") || embedLower.contains("ok.ru") -> {
                loadOkRuLinks(embedUrl)?.let { sink.emit(it) }
            }

            embedLower.contains("dailymotion.com") -> {
                val id = dailymotionVideoId(embedUrl)
                if (id != null && sink.claim("dm:$id")) {
                    resolveDailymotion(id, "Dailymotion", "Dailymotion")?.let { sink.emit(it) }
                }
            }

            embedLower.contains("voe") -> {
                val voeUrl = extractVoeLink(embedUrl)
                if (voeUrl != null) {
                    val voeLink = newExtractorLink(
                        source = "VOE",
                        name = "VOE",
                        url = voeUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = embedUrl
                        quality = 0
                    }
                    sink.emit(voeLink)
                }
            }

            else -> {
                // Los subtítulos de estos servidores se descartan a propósito: los únicos que
                // se ofrecen son los del Dark Server de DonghuaWorld (ver loadDarkServerLink).
                loadExtractor(
                    url = embedUrl,
                    referer = pageUrl,
                    subtitleCallback = { },
                    callback = { link ->
                        val keep = when {
                            embedLower.contains("rumble.com") ->
                                link.url.contains("rumble.com/hls-vod/")

                            embedLower.contains("filemoon") ->
                                link.type == ExtractorLinkType.M3U8 &&
                                    link.url.contains("master.m3u8")

                            else -> true
                        }

                        if (keep) {
                            sink.emit(
                                ExtractorLink(
                                    source = link.source,
                                    name = "${link.name} · $serverName",
                                    url = link.url,
                                    referer = link.referer,
                                    quality = link.quality,
                                    type = link.type
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    // ───────────────────────── DonghuaWorld ─────────────────────────

    private suspend fun loadDonghuaWorldEpisodeLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        val document = attempt {
            app.get(data, timeout = DW_PAGE_TIMEOUT_S).document
        } ?: return
        resolveDonghuaWorldEpisode(document, data, subtitleCallback, sink)
    }

    private suspend fun loadDonghuaWorldLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        val match = EPISODE_SLUG.find(data) ?: return

        val seriesSlug = match.groupValues[1]
        val episodeNumber = match.groupValues[2]
        val searchQuery = seriesSlug.replace("-", " ").trim()

        var candidates = findDonghuaWorldSeries(searchQuery)

        if (candidates.isEmpty()) {
            val fallbackQuery = searchQuery.replace(Regex("(?i)shrouding"), "shrounding")
            if (fallbackQuery != searchQuery) {
                candidates = findDonghuaWorldSeries(fallbackQuery)
            }
        }

        val seriesUrl = candidates.firstOrNull()?.second ?: return
        debug { "DonghuaWorld serie -> $seriesUrl" }

        val seriesDocument = app.get(seriesUrl, timeout = DW_PAGE_TIMEOUT_S).document
        val episodeRegex = Regex("episode-$episodeNumber(?:-|/|\$)")

        val episodeUrl = seriesDocument
            .select("a[href*='episode-$episodeNumber']")
            .asSequence()
            .map { it.attr("href").trim() }
            .firstOrNull { href ->
                href.isNotBlank() &&
                    episodeRegex.containsMatchIn(href.substringBefore("?").substringBefore("#"))
            }
            ?.let { fixUrl(it) }
            ?: return

        debug { "DonghuaWorld episodio -> $episodeUrl" }

        val episodeDocument = app.get(episodeUrl, timeout = DW_PAGE_TIMEOUT_S).document
        resolveDonghuaWorldEpisode(episodeDocument, episodeUrl, subtitleCallback, sink)
    }

    private suspend fun findDonghuaWorldSeries(query: String): List<Pair<Int, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$DW_URL/?s=$encodedQuery", timeout = DW_PAGE_TIMEOUT_S).document
        val normalizedSearch = normalize(query)

        return document
            .select("a[href*='/anime/'][itemprop='url']")
            .mapNotNull { element ->
                val href = element.attr("href").trim()
                if (href.isBlank() || href.contains("/anime/?")) return@mapNotNull null

                val title = element.attr("title").trim().ifBlank { element.text().trim() }
                val normalizedTitle = normalize(title)
                val slug = href.substringAfter("/anime/").trim('/').lowercase()

                if (slug.contains("movie") || isMovieTitle(normalizedTitle)) {
                    return@mapNotNull null
                }

                val score = titleScore(normalizedTitle, normalizedSearch, 60)
                if (score > 0) (score to fixUrl(href)) else null
            }
            .distinctBy { it.second }
            .sortedByDescending { it.first }
    }

    /** Dailymotion y Dark Server de un episodio de DonghuaWorld, en paralelo. */
    private suspend fun resolveDonghuaWorldEpisode(
        document: Document,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        val tasks = listOf<suspend () -> Unit>(
            {
                guarded("DonghuaWorld Dailymotion") {
                    val embed = document
                        .selectFirst("iframe[src*='geo.dailymotion.com'][src*='video=']")
                        ?.attr("src")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                    val id = embed?.let { dailymotionVideoId(it) }

                    if (id != null && sink.claim("dm:$id")) {
                        resolveDailymotion(id, "DonghuaWorld", "DonghuaWorld · Dailymotion")
                            ?.let { sink.emit(it) }
                    }
                }
            },
            {
                guarded("DonghuaWorld Dark Server") {
                    loadDarkServerLink(document, episodeUrl, subtitleCallback)
                        ?.let { sink.emit(it) }
                }
            }
        )

        tasks.amap { it() }
    }

    private suspend fun loadDarkServerLink(
        document: Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): ExtractorLink? {
        val darkServer = document
            .select(".server-item a")
            .firstOrNull { it.text().trim().equals("Dark Server", ignoreCase = true) }
            ?: return null

        val encodedHash = darkServer.attr("data-hash").trim()
        if (encodedHash.isBlank()) return null

        val decodedEmbed = attempt {
            String(Base64.getDecoder().decode(encodedHash), Charsets.UTF_8)
        } ?: return null

        val playerUrl = IFRAME_SRC.find(decodedEmbed)?.groupValues?.getOrNull(1)?.trim()
        if (playerUrl.isNullOrBlank()) return null

        val html = attempt {
            app.get(
                playerUrl,
                headers = mapOf(
                    "Referer" to pageUrl,
                    "User-Agent" to BROWSER_UA
                ),
                timeout = DW_PAGE_TIMEOUT_S
            ).text
        } ?: return null

        // Subtítulos
        val tracksStart = html.indexOf("const tracks = [")
        if (tracksStart >= 0) {
            val tracksEnd = html.indexOf("]", tracksStart)
            if (tracksEnd > tracksStart) {
                TRACK.findAll(html.substring(tracksStart, tracksEnd + 1)).distinctBy { match ->
                    match.groupValues[1]
                }.forEach { match ->
                    subtitleCallback(
                        SubtitleFile(
                            lang = match.groupValues[2],
                            url = match.groupValues[1].replace("\\", "")
                        )
                    )
                }
            }
        }

        // Playlist de Rumble
        val rumbleStart = html.indexOf("rumble.com")
        if (rumbleStart < 0) return null

        val urlStart = html.lastIndexOf("https", rumbleStart)
        val endMarker = "playlist.m3u8"
        val endPos = html.indexOf(endMarker, rumbleStart)
        if (urlStart < 0 || endPos < 0) return null

        val playlist = html
            .substring(urlStart, endPos + endMarker.length)
            .replace("\\", "")

        return newExtractorLink(
            name = "DonghuaWorld · Dark Server",
            source = "DonghuaWorld",
            url = playlist,
            type = ExtractorLinkType.M3U8
        ) {
            referer = playerUrl
            quality = 0
        }
    }

    // ───────────────────────── Dailymotion ─────────────────────────

    private fun dailymotionVideoId(embedUrl: String): String? =
        embedUrl
            .substringAfter("video=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }
            ?: DM_PATH_ID.find(embedUrl)?.groupValues?.getOrNull(1)

    private suspend fun resolveDailymotion(
        videoId: String,
        source: String,
        displayName: String
    ): ExtractorLink? {
        val response = attempt {
            app.get(
                "https://geo.dailymotion.com/videos/$videoId",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Referer" to "https://geo.dailymotion.com/",
                    "User-Agent" to BROWSER_UA
                )
            )
        } ?: return null

        val streamUrl = attempt {
            JSONObject(response.text).getJSONObject("stream").optString("url")
        }?.takeIf { it.isNotBlank() } ?: return null

        return newExtractorLink(
            name = displayName,
            source = source,
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            referer = "https://geo.dailymotion.com/"
            quality = 0
        }
    }

    // ───────────────────────── OK.ru / Odysee ─────────────────────────

    private suspend fun loadOkRuLinks(embedUrl: String): ExtractorLink? {
        val response = attempt {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to embedUrl
                )
            )
        } ?: return null

        if (response.code !in 200..299) return null

        val normalized = response.text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

        if (embedUrl.contains("odysee.com", ignoreCase = true)) {
            val contentUrl = ODYSEE_CONTENT_URL
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\u0026", "&")
                ?.replace("\\u003D", "=")
                ?.replace("\\/", "/")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            // Odysee a veces entrega vídeo sin extensión; contentUrl ya es un recurso de vídeo.
            val linkType = if (contentUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            return newExtractorLink(
                source = "Odysee",
                name = "Odysee",
                url = contentUrl,
                type = linkType
            ) {
                referer = embedUrl
                quality = 0
            }
        }

        val hlsUrl = OK_HLS_URL
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\u0026", "&")
            ?.replace("\\u003D", "=")
            ?.replace("\\/", "/")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return newExtractorLink(
            source = "OK.ru",
            name = "OK.ru",
            url = hlsUrl,
            type = ExtractorLinkType.M3U8
        ) {
            referer = embedUrl
            quality = 0
        }
    }

    // ───────────────────────── VOE ─────────────────────────

    private suspend fun extractVoeLink(embedUrl: String): String? =
        attempt { resolveVoe(embedUrl) }

    private suspend fun resolveVoe(embedUrl: String): String? {
        val headers = mapOf("User-Agent" to BROWSER_UA)

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
                "User-Agent" to BROWSER_UA,
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
            if ((counter and 0xff) == 0 && System.nanoTime() - powStart > ALTCHA_TIMEOUT_NS) break

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
                "User-Agent" to BROWSER_UA,
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
            debug { "VOE decode ERROR -> ${e.message}" }
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
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0f]
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
}
