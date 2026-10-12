package com.marcelo.donghuaworld

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class DonghuaWorldProvider : MainAPI() {
    override var mainUrl = "https://donghuaworld.com"
    override var name = "DonghuaWorld"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Orden de las secciones del catálogo.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Nuevos Episodios",
        "$mainUrl/anime/" to "Catalogo Completo"
    )

    private companion object {
        // Poné DEBUG = true solo cuando quieras ver logs en logcat.
        const val DEBUG = false

        // Tiempos máximos (segundos): si la web no responde, la sección queda vacía.
        const val FAST_TIMEOUT_S = 4L
        const val PAGE_TIMEOUT_S = 6L

        // La búsqueda de WordPress es lenta: con 4 s a veces vencía y, como el error se
        // descartaba en silencio, la búsqueda parecía "vacía".
        const val SEARCH_TIMEOUT_S = 10L

        // Caché en memoria (home y búsquedas): la home pesa ~1 MB y la búsqueda de WordPress
        // no se cachea en el servidor, así que repetir lo mismo no tiene por qué volver a la web.
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val CACHE_MAX_ENTRIES = 40

        // Puntajes de la búsqueda.
        // Resultado que la web devolvió pero cuyo título no coincide: la web también busca en
        // la sinopsis y en otros nombres, así que se muestra igual, al final.
        const val SITE_ONLY_SCORE = 20
        // Desde acá ya hay una coincidencia real de título y no hace falta rescatar tipeos.
        const val GOOD_SCORE = 80
        // Máximo de búsquedas extra (en paralelo) para rescatar errores de tipeo.
        const val MAX_PROBES = 4

        const val BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"

        val POSTER_ATTRS = listOf("src", "data-src", "data-lazy-src", "data-original", "data-url")

        val NON_ALNUM = Regex("[^a-z0-9]+")
        val EPISODE_IN_TITLE = Regex("""\s+Episode\s+\d""", RegexOption.IGNORE_CASE)
        val SERIES_URL = Regex("""^https?://[^/?#]+/anime/[^/?#]+/?$""")
        val IFRAME_SRC = Regex("""src=["']([^"']+)["']""")
        val TRACK = Regex("""\{"file":"([^"]+)","label":"([^"]+)"\}""")
        val DM_PATH_ID = Regex("/video/([A-Za-z0-9]+)")
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private inline fun debug(message: () -> String) {
        if (DEBUG) println("DonghuaWorld: ${message()}")
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

    private fun titleScore(title: String, query: String, titleInQueryScore: Int): Int {
        if (title.isBlank() || query.isBlank()) return 0
        if (title == query) return 120
        if (title.startsWith("$query ")) return 110
        if (title.contains(query)) return 100
        if (query.contains(title)) return titleInQueryScore

        val queryTokens = query.split(" ").filter { it.isNotBlank() }
        val titleTokens = title.split(" ").filter { it.isNotBlank() }
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0

        var exact = 0 // palabras que están tal cual o como comienzo de una palabra del título
        var fuzzy = 0 // palabras que coinciden con algún error de tipeo
        for (q in queryTokens) {
            when {
                titleTokens.any { it == q || it.startsWith(q) } -> exact++
                titleTokens.any { fuzzyWord(q, it) } -> fuzzy++
            }
        }

        val matched = exact + fuzzy
        if (matched == queryTokens.size) return if (fuzzy == 0) 90 else 85
        if (queryTokens.size >= 2 && matched.toDouble() / queryTokens.size >= 0.5) return 65
        return 0
    }

    /**
     * ¿La palabra escrita `q` es la palabra del título `t` con un error de tipeo?
     * Solo para palabras de 5+ letras (las cortas deben coincidir exacto o por comienzo).
     * Admite letras de más, de menos, cambiadas o intercambiadas ("renegde", "imortal", "swalowed").
     */
    private fun fuzzyWord(q: String, t: String): Boolean {
        if (q.length < 5) return false
        val maxEdits = if (q.length >= 9) 2 else 1
        if (kotlin.math.abs(q.length - t.length) <= maxEdits && editDistance(q, t) <= maxEdits) {
            return true
        }
        // Escribió solo el comienzo de la palabra y con un error: se compara con el mismo largo.
        return t.length > q.length && editDistance(q, t.substring(0, q.length)) <= maxEdits
    }

    /** Distancia de edición (insertar, borrar, cambiar o intercambiar dos letras seguidas). */
    private fun editDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        var beforePrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)

        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, beforePrev[j - 2] + 1)
                }
                cur[j] = value
            }
            val reuse = beforePrev
            beforePrev = prev
            prev = cur
            cur = reuse
        }
        return prev[m]
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

    // ───────────────────────── Caché ─────────────────────────

    private class CacheEntry(val at: Long, val value: List<SearchResponse>)

    private val homeCache = ConcurrentHashMap<String, CacheEntry>()
    private val searchCache = ConcurrentHashMap<String, CacheEntry>()

    private fun cacheGet(cache: ConcurrentHashMap<String, CacheEntry>, key: String): List<SearchResponse>? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at < CACHE_TTL_MS) return entry.value
        cache.remove(key)
        return null
    }

    /** Solo se guardan resultados con contenido: un fallo o una lista vacía no se queda pegado. */
    private fun cachePut(cache: ConcurrentHashMap<String, CacheEntry>, key: String, value: List<SearchResponse>) {
        if (value.isEmpty()) return
        if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
        cache[key] = CacheEntry(System.currentTimeMillis(), value)
    }

    // ───────────────────────── Página principal ─────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isLatest = request.data == "$mainUrl/"

        val url = when {
            page == 1 -> request.data
            // La home de DonghuaWorld pagina como /page/2/, no con ?page=2.
            isLatest -> "$mainUrl/page/$page/"
            else -> {
                val separator = if (request.data.contains("?")) "&" else "?"
                "${request.data}${separator}page=$page"
            }
        }

        // Si la página se cae, la sección queda vacía en vez de romper todo el catálogo.
        // Las páginas vistas hace poco salen de la caché (la home pesa ~1 MB).
        val home: List<SearchResponse> = cacheGet(homeCache, url)
            ?: attempt {
                val document = app.get(url, timeout = FAST_TIMEOUT_S).document
                if (isLatest) parseLatest(document) else parseCatalog(document)
            }?.also { cachePut(homeCache, url, it) }
            ?: run {
            debug { "sección '${request.name}' no disponible, se omite" }
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, home)),
            hasNext = home.isNotEmpty()
        )
    }

    private fun parseCatalog(document: Document): List<SearchResponse> =
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
     * Solo "Latest Release" de la home (no el carrusel "Series Update"): cada ítem es un
     * capítulo. Se muestra con el nombre de la serie y, al abrirlo, load() resuelve la
     * serie para que elijas el capítulo.
     */
    private fun parseLatest(document: Document): List<SearchResponse> {
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

    // ───────────────────────── Búsqueda ─────────────────────────

    private class Hit(
        val score: Int,
        val isEpisode: Boolean,
        val key: String,
        val response: SearchResponse
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        cacheGet(searchCache, normalizedQuery)?.let { return it }

        val primary = query.trim()
        val words = normalizedQuery.split(" ").filter { it.length >= 4 }

        // Fase 1 (todo en paralelo): la consulta tal cual la escribió la persona y, si tiene
        // varias palabras, cada palabra suelta (la web exige que aparezcan todas juntas).
        val firstTerms = linkedSetOf(primary)
        if (words.size >= 2) firstTerms.addAll(words)

        var hits = searchTerms(firstTerms.toList(), primary, normalizedQuery)

        // Fase 2: solo si no apareció ninguna coincidencia real de título (p. ej. un error de
        // tipeo). La web busca texto exacto, así que se piden pedazos de cada palabra y
        // después se filtra por parecido.
        if (hits.none { it.score >= GOOD_SCORE }) {
            val probes = typoProbes(normalizedQuery).filter { it !in firstTerms }
            if (probes.isNotEmpty()) hits = hits + searchTerms(probes, primary, normalizedQuery)
        }

        debug { "búsqueda '$query': candidatos=${hits.size}" }

        val results = hits
            .sortedBy { it.isEpisode }
            .groupBy { it.key }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.score } }
            .sortedByDescending { it.score }
            .map { it.response }

        cachePut(searchCache, normalizedQuery, results)
        return results
    }

    /** Lanza todas las búsquedas a la vez (antes iban una detrás de otra, hasta 10 s cada una). */
    private suspend fun searchTerms(
        terms: List<String>,
        primary: String,
        normalizedQuery: String
    ): List<Hit> =
        terms.amap { term -> searchOnce(term, normalizedQuery, trustSite = term == primary) }
            .flatten()

    /**
     * Pedazos de las palabras más largas (comienzo y final) para encontrar series cuando
     * la palabra está mal escrita: un error de tipeo no puede estar en los dos pedazos a la vez.
     */
    private fun typoProbes(normalizedQuery: String): List<String> =
        normalizedQuery.split(" ")
            .filter { it.length >= 5 }
            .sortedByDescending { it.length }
            .take(2)
            .flatMap { word ->
                val half = maxOf(3, (word.length + 1) / 2)
                listOf(word.take(half), word.takeLast(half))
            }
            .distinct()
            .take(MAX_PROBES)

    /**
     * Una búsqueda en la web (una página de resultados) convertida en candidatos.
     *
     * `trustSite`: la consulta tal cual la escribió la persona. Todo lo que la web devuelve se
     * muestra aunque el título no coincida (como en donghuaworld.com, que también busca en la
     * sinopsis). Las búsquedas auxiliares (palabras sueltas, pedazos) solo aportan candidatos
     * que además parezcan el título buscado.
     */
    private suspend fun searchOnce(
        term: String,
        normalizedQuery: String,
        trustSite: Boolean
    ): List<Hit> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(term, "UTF-8")}"
        val document = attempt {
            app.get(searchUrl, timeout = SEARCH_TIMEOUT_S).document
        } ?: return emptyList()

        // Solo las tarjetas de resultados. Antes, si no había ninguna, se tomaban todos los
        // links /anime/ de la página y entraba el sidebar ("Popular Series").
        val units = document.select("div.postbody article.bs")
            .ifEmpty { document.select("article.bs") }

        return units.mapNotNull { unit ->
            val link = unit.selectFirst("a[href*='/anime/']")
                ?: unit.selectFirst("a[href*='-episode-']")
                ?: return@mapNotNull null

            val href = link.attr("href").trim()
            if (href.isBlank() || href.contains("/anime/?")) return@mapNotNull null

            val isSeries = href.contains("/anime/")
            val isEpisode = !isSeries && href.contains("-episode-")
            if (!isSeries && !isEpisode) return@mapNotNull null

            val rawTitle = link.attr("title").trim()
                .ifBlank { unit.selectFirst(".tt")?.ownText()?.trim().orEmpty() }
                .ifBlank { unit.selectFirst("h2")?.text()?.trim().orEmpty() }
                .ifBlank { unit.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { link.text().trim() }

            val title = if (isEpisode) {
                EPISODE_IN_TITLE.find(rawTitle)
                    ?.let { rawTitle.substring(0, it.range.first).trim() }
                    ?: rawTitle
            } else rawTitle

            if (title.isBlank()) return@mapNotNull null

            val normalizedTitle = normalize(title)
            val titleMatch = titleScore(normalizedTitle, normalizedQuery, 80)
            val score = when {
                titleMatch > 0 -> titleMatch
                trustSite -> SITE_ONLY_SCORE
                else -> return@mapNotNull null
            }

            val slug = href.substringAfter("/anime/", "").lowercase()
            val isMovie = isMovieTitle(normalizedTitle) || slug.contains("movie")
            val poster = (unit.selectFirst("img[itemprop='image']")
                ?: unit.selectFirst("img")).imageUrl()

            val response = newAnimeSearchResponse(
                title,
                fixUrl(href),
                if (isMovie) TvType.AnimeMovie else TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(poster)
            }

            val identity = fixUrl(href).trimEnd('/')
            Hit(score, isEpisode, "$identity|${if (isMovie) "movie" else "series"}", response)
        }
    }

    private suspend fun findSeries(query: String): List<Pair<Int, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery", timeout = PAGE_TIMEOUT_S).document
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

    // ───────────────────────── Detalle de serie ─────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("donghuaworld.com/anime/")) {
            return loadSeries(url)
        }

        // Capítulo suelto de "Nuevos Episodios": se abre la serie completa.
        val seriesUrl = resolveSeriesUrl(url) ?: return null
        return loadSeries(seriesUrl)
    }

    /**
     * Dado el link de un capítulo, devuelve el link de su serie (/anime/...).
     * La página del capítulo enlaza a la serie desde varios lugares (ruta de navegación,
     * "All Episodes", ficha de la serie), pero el sidebar también trae otras series populares.
     * Por eso se elige el link /anime/ cuyo slug comparte más palabras con el del capítulo.
     */
    private suspend fun resolveSeriesUrl(episodeUrl: String): String? {
        val episodeSlug = episodeUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        val episodeTokens = episodeSlug.split('-').filter { it.isNotBlank() }.toSet()

        val fromPage = attempt {
            val document = app.get(episodeUrl, timeout = PAGE_TIMEOUT_S).document
            val seen = HashSet<String>()
            var best: String? = null
            var bestScore = 0

            for (anchor in document.select("a[href*='/anime/']")) {
                val href = fixUrl(anchor.attr("href").trim().substringBefore("#"))
                if (!SERIES_URL.matches(href)) continue

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

        return attempt { findSeries(seriesName).firstOrNull()?.second }
    }

    private suspend fun loadSeries(url: String): LoadResponse? {
        val document = attempt {
            app.get(url, timeout = PAGE_TIMEOUT_S).document
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

        val isMovie = isMovieTitle(normalize(title)) ||
            url.substringAfter("/anime/").lowercase().contains("movie")
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ───────────────────────── Links ─────────────────────────

    /** `data` es la URL del capítulo (…-episode-N-…). Dailymotion y Dark Server, en paralelo. */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sink = LinkSink(callback)

        val document = attempt {
            app.get(data, timeout = PAGE_TIMEOUT_S).document
        } ?: return false

        val tasks = listOf<suspend () -> Unit>(
            {
                guarded("Dailymotion") {
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
                guarded("Dark Server") {
                    loadDarkServerLink(document, data, subtitleCallback)
                        ?.let { sink.emit(it) }
                }
            }
        )

        tasks.amap { it() }

        debug { "loadLinks final linkCount=${sink.total}" }
        return sink.total > 0
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
                timeout = PAGE_TIMEOUT_S
            ).text
        } ?: return null

        // Subtítulos
        val tracksStart = html.indexOf("const tracks = [")
        if (tracksStart >= 0) {
            val tracksEnd = html.indexOf("]", tracksStart)
            if (tracksEnd > tracksStart) {
                val tracks = TRACK.findAll(html.substring(tracksStart, tracksEnd + 1))
                    .map { match -> match.groupValues[2] to match.groupValues[1].replace("\\", "") }
                    .distinctBy { (_, trackUrl) -> trackUrl }
                    .toList()

                // Si un mismo idioma trae varias pistas y una es "Genérico", se deja solo esa.
                val selected = tracks
                    .groupBy { (label, _) -> label.substringBefore("(").trim().lowercase() }
                    .values
                    .flatMap { group ->
                        val generic = group.filter { (label, _) ->
                            label.lowercase().replace("é", "e").contains("generic")
                        }
                        if (group.size > 1 && generic.isNotEmpty()) generic else group
                    }

                for ((label, trackUrl) in selected) {
                    // El sufijo permite ver en el reproductor cuáles subs manda el plugin.
                    subtitleCallback(SubtitleFile(lang = "$label · Dark Server", url = trackUrl))
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
}
