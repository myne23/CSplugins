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

    private fun titleScore(title: String, query: String, titleInQueryScore: Int): Int = when {
        // Un título vacío (p. ej. solo caracteres no latinos) coincidía con todo.
        title.isBlank() || query.isBlank() -> 0
        title == query -> 100
        title.startsWith("$query ") -> 95
        title.contains(query) -> 90
        query.contains(title) -> titleInQueryScore
        isOneTypoAway(title, query) -> 70
        // Búsqueda flexible: todas las palabras de la búsqueda aparecen en el título,
        // sin importar el orden (ej. "immortal renegade" -> "Renegade Immortal").
        matchesAllTokens(title, query) -> 75
        else -> 0
    }

    private fun matchesAllTokens(title: String, query: String): Boolean {
        val queryTokens = query.split(" ").filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return false
        val titleTokens = title.split(" ").filter { it.isNotBlank() }
        return queryTokens.all { q -> titleTokens.any { it.startsWith(q) } }
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
        val home: List<SearchResponse> = attempt {
            val document = app.get(url, timeout = FAST_TIMEOUT_S).document
            if (isLatest) parseLatest(document) else parseCatalog(document)
        } ?: run {
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

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        val document = attempt {
            app.get("$mainUrl/?s=$encodedQuery", timeout = FAST_TIMEOUT_S).document
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
                val isMovie = isMovieTitle(normalizedTitle) ||
                    href.substringAfter("/anime/").lowercase().contains("movie")

                val score = titleScore(normalizedTitle, normalizedQuery, 80)
                if (score <= 0) return@mapNotNull null

                val card = element.parents().firstOrNull { it.tagName() == "article" }
                val image = card?.selectFirst("img[itemprop='image']")
                    ?: card?.selectFirst("img")
                val poster = image.imageUrl()

                val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
                val response = newAnimeSearchResponse(title, fixUrl(href), type) {
                    this.posterUrl = fixUrlNull(poster)
                }

                score to response
            }
            .distinctBy { it.second.url }
            .sortedByDescending { it.first }
            .map { it.second }
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
