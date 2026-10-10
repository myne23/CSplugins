package com.marcelo.donghualife

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class DonghuaLifeProvider : MainAPI() {
    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Las entradas "home:*" salen de la portada; las demás son catálogos paginados (?page=N).
    override val mainPage = mainPageOf(
        "home:featured" to "Destacado",
        "home:latest" to "Últimos episodios",
        "home:trending" to "Tendencias",
        "home:recommended" to "Recomendados",
        "$mainUrl/series" to "Series populares",
        "$mainUrl/series?sort=latest" to "Series recientes",
        "$mainUrl/series?status=finalizadas" to "Series finalizadas",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series?sort=az" to "Series A-Z"
    )

    private companion object {
        // Poné DEBUG = true solo cuando quieras ver logs en logcat.
        const val DEBUG = false

        const val BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"

        const val INDEX_CACHE_MS = 30L * 60_000L
        const val HOME_CACHE_MS = 60_000L
        const val MAX_PAGES = 40

        val CARD_HREF = Regex("""/(?:series|peliculas)/[^/?#]+/?$""")
        val WATCH_HREF = Regex("""/watch/[^/?#]+/?$""")

        // El slug de un episodio termina en "-{temporada}-{capítulo}" (p. ej. ...-temporada-1-6, ...-t3-9).
        val SEASON_EP = Regex("""(\d+)-(\d+)$""")
        val EP_TEXT = Regex("""(?:Cap[ií]tulo|Episodio)\s*(\d+)""", RegexOption.IGNORE_CASE)
        val EP_IN_CARD = Regex("""^(.*?)EP\s*\d""")
        val PAGE_PARAM = Regex("""[?&]page=(\d+)""")
        val SEASON_LABEL = Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE)
        val NEXT_IMAGE_URL = Regex("""[?&]url=([^&]+)""")
        val DIACRITICS = Regex("""\p{M}+""")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val DM_PATH_ID = Regex("/video/([A-Za-z0-9]+)")
        val IFRAME_SRC = Regex("""src=["']([^"']+)["']""")
        val IMAGE_EXT = Regex("""\.(?:jpe?g|png|webp|gif|svg|avif)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        val SUB_EXT = Regex("""\.(?:vtt|srt|ass|ssa)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

        // Cada fuente del episodio viene como un objeto JSON con "token" (ver parseSources).
        val SOURCE_OBJECT = Regex("""\{[^{}]*"token"\s*:\s*"[^"]+"[^{}]*\}""")

        val SUB_KEYS = setOf("subtitles", "subtitle", "tracks", "captions", "subs")
        val IGNORE_KEYS = setOf(
            "poster", "thumbnail", "thumb", "image", "cover", "banner", "logo", "preview", "sprite"
        )
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private inline fun debug(message: () -> String) {
        if (DEBUG) println("DonghuaLife: ${message()}")
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
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(NON_ALNUM, " ")
            .trim()

    private fun normalizeUrl(url: String): String {
        val value = url.trim()
        return if (value.startsWith("//")) "https:$value" else value
    }

    private fun slugTitle(url: String): String =
        url.substringBefore("?").trimEnd('/').substringAfterLast('/')
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private fun parseSeasonEp(path: String): Pair<Int, Int>? {
        val match = SEASON_EP.find(path.substringBefore("?").trimEnd('/')) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    /** Las imágenes pasan por /_next/image?url=...; se usa la ruta real (/uploads/...). */
    private fun cleanImage(src: String): String? {
        val real = if (src.contains("/_next/image")) {
            NEXT_IMAGE_URL.find(src)?.groupValues?.getOrNull(1)
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?: src
        } else {
            src
        }
        return fixUrlNull(real)
    }

    private fun Element?.imageUrl(): String? {
        if (this == null) return null
        for (key in listOf("src", "data-src", "data-lazy-src", "data-original")) {
            val value = attr(key).trim()
            if (value.isBlank() || value.startsWith("data:")) continue
            return cleanImage(value)
        }
        return null
    }

    private fun Element.firstPoster(): String? =
        select("img")
            .firstOrNull { !it.attr("src").contains("logo", ignoreCase = true) }
            .imageUrl()

    private fun cardTitle(anchor: Element): String? {
        anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("DonghuaLife", ignoreCase = true) }
            ?.let { return it }
        anchor.attr("title").trim().takeIf { it.isNotBlank() }?.let { return it }
        anchor.selectFirst("h1, h2, h3, h4, h5")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return null
    }

    private fun maxPage(root: Element): Int =
        root.select("a[href*='page=']")
            .mapNotNull { PAGE_PARAM.find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0

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

    // ───────────────────────── Parseo de tarjetas ─────────────────────────

    private class CardInfo(val url: String, var title: String?, var poster: String?)

    /** Tarjetas de series/películas: se agrupan por link, así no depende de las clases CSS. */
    private fun parseSeriesCards(root: Element): List<SearchResponse> {
        val cards = LinkedHashMap<String, CardInfo>()

        for (anchor in root.select("a[href]")) {
            val full = fixUrl(anchor.attr("href").trim().substringBefore("#"))
            val path = full.substringBefore("?")
            if (!CARD_HREF.containsMatchIn(path)) continue

            val key = path.trimEnd('/')
            val info = cards.getOrPut(key) { CardInfo(key, null, null) }
            if (info.title == null) info.title = cardTitle(anchor)
            if (info.poster == null) info.poster = anchor.firstPoster()
        }

        return cards.values.mapNotNull { info ->
            val title = info.title ?: slugTitle(info.url)
            if (title.isBlank()) return@mapNotNull null

            if (info.url.contains("/peliculas/")) {
                newMovieSearchResponse(title, info.url, TvType.AnimeMovie) {
                    this.posterUrl = info.poster
                }
            } else {
                newAnimeSearchResponse(title, info.url, TvType.Anime) {
                    this.posterUrl = info.poster
                }
            }
        }
    }

    /** Tarjetas de capítulos (/watch/...): al abrirlas, load() resuelve la serie. */
    private fun parseEpisodeCards(root: Element): List<SearchResponse> {
        val seen = HashSet<String>()

        return root.select("a[href*='/watch/']").mapNotNull { anchor ->
            val full = fixUrl(anchor.attr("href").trim().substringBefore("#"))
            val path = full.substringBefore("?")
            if (!WATCH_HREF.containsMatchIn(path) || !seen.add(path)) return@mapNotNull null

            val title = cardTitle(anchor)
                ?: EP_IN_CARD.find(anchor.text())?.groupValues?.getOrNull(1)?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val episode = parseSeasonEp(path)?.second
            val poster = anchor.firstPoster()

            newAnimeSearchResponse(title, path, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, episode)
            }
        }
    }

    /** Banner de la portada: cada slide tiene un botón "DETALLES" que apunta a la serie. */
    private fun parseFeatured(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()

        return doc.select("a[href*='/series/']")
            .filter { it.text().trim().equals("Detalles", ignoreCase = true) }
            .mapNotNull { anchor ->
                val url = fixUrl(anchor.attr("href").trim()).substringBefore("?").trimEnd('/')
                if (!CARD_HREF.containsMatchIn(url) || !seen.add(url)) return@mapNotNull null

                val container = anchor.parents().firstOrNull {
                    it.selectFirst("h1, h2") != null && it.selectFirst("img") != null
                }

                val title = container?.selectFirst("h1, h2")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: slugTitle(url)

                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = container?.firstPoster()
                }
            }
    }

    /** Busca el título de la sección y devuelve el contenedor más chico que tiene sus tarjetas. */
    private fun sectionRoot(doc: Document, heading: String, hrefPart: String): Element? {
        val title = doc.getElementsContainingOwnText(heading)
            .firstOrNull { it.ownText().trim().startsWith(heading, ignoreCase = true) }
            ?: return null

        return title.parents().firstOrNull { it.select("a[href*='$hrefPart']").size >= 2 }
    }

    // ───────────────────────── Página principal ─────────────────────────

    @Volatile
    private var homeCache: Pair<Long, Document>? = null

    private suspend fun homeDocument(): Document? {
        val cached = homeCache
        if (cached != null && System.currentTimeMillis() - cached.first < HOME_CACHE_MS) {
            return cached.second
        }
        val document = attempt { app.get(mainUrl).document } ?: return null
        homeCache = System.currentTimeMillis() to document
        return document
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = request.data

        if (data.startsWith("home:")) {
            if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

            val document = homeDocument()
                ?: return newHomePageResponse(emptyList(), hasNext = false)

            val items: List<SearchResponse> = when (data) {
                "home:featured" -> parseFeatured(document)
                "home:latest" ->
                    sectionRoot(document, "Últimos episodios", "/watch/")
                        ?.let { parseEpisodeCards(it) }.orEmpty()
                "home:trending" ->
                    sectionRoot(document, "Tendencias", "/series/")
                        ?.let { parseSeriesCards(it) }.orEmpty()
                "home:recommended" ->
                    sectionRoot(document, "Te recomendamos", "/series/")
                        ?.let { parseSeriesCards(it) }.orEmpty()
                else -> emptyList()
            }

            if (items.isEmpty()) return newHomePageResponse(emptyList(), hasNext = false)

            return newHomePageResponse(
                listOf(
                    HomePageList(
                        request.name,
                        items,
                        isHorizontalImages = data == "home:featured"
                    )
                ),
                hasNext = false
            )
        }

        val url = if (page == 1) {
            data
        } else {
            data + (if (data.contains("?")) "&" else "?") + "page=$page"
        }

        // Si una sección falla, queda vacía y el resto del catálogo sigue funcionando.
        val document = attempt { app.get(url).document }
            ?: return newHomePageResponse(emptyList(), hasNext = false)

        val items = parseSeriesCards(document)
        val last = maxPage(document)

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty() && (last == 0 || page < last)
        )
    }

    // ───────────────────────── Búsqueda ─────────────────────────

    @Volatile
    private var indexCache: Pair<Long, List<SearchResponse>>? = null

    /** Índice completo de series y películas (se recorre el catálogo y se guarda 30 minutos). */
    private suspend fun fullIndex(): List<SearchResponse> {
        val cached = indexCache
        if (cached != null && System.currentTimeMillis() - cached.first < INDEX_CACHE_MS) {
            return cached.second
        }

        val result = ArrayList<SearchResponse>()

        for (base in listOf("$mainUrl/series", "$mainUrl/peliculas")) {
            val first = attempt { app.get(base).document } ?: continue
            result += parseSeriesCards(first)

            val pages = maxPage(first).coerceAtMost(MAX_PAGES)
            if (pages >= 2) {
                val rest = (2..pages).toList().amap { number ->
                    attempt { parseSeriesCards(app.get("$base?page=$number").document) }
                        ?: emptyList()
                }
                result += rest.flatten()
            }
        }

        val index = result.distinctBy { it.url }
        if (index.isNotEmpty()) indexCache = System.currentTimeMillis() to index
        return index
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val tokens = normalizedQuery.split(" ").filter { it.isNotBlank() }

        return fullIndex()
            .mapNotNull { response ->
                val title = normalize(response.name)
                val score = when {
                    title == normalizedQuery -> 100
                    title.startsWith(normalizedQuery) -> 95
                    title.contains(normalizedQuery) -> 90
                    tokens.all { title.contains(it) } -> 80
                    else -> 0
                }
                if (score > 0) score to response else null
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    // ───────────────────────── Detalle ─────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val clean = url.substringBefore("#")

        // Un capítulo suelto (p. ej. de "Últimos episodios"): se abre la serie completa.
        if (WATCH_HREF.containsMatchIn(clean.substringBefore("?"))) {
            val seriesUrl = resolveSeriesFromWatch(clean) ?: return null
            return loadSeries(seriesUrl)
        }

        return loadSeries(clean)
    }

    private suspend fun resolveSeriesFromWatch(watchUrl: String): String? {
        val document = attempt { app.get(watchUrl).document } ?: return null

        // El primer link a /series/{slug} es la ruta de navegación de la serie del capítulo.
        return document.select("a[href]")
            .asSequence()
            .map { fixUrl(it.attr("href").trim().substringBefore("#")).substringBefore("?") }
            .firstOrNull { CARD_HREF.containsMatchIn(it) }
            ?.trimEnd('/')
    }

    private class EpisodeRaw(val url: String, val season: Int, val episode: Int, val vip: Boolean)

    private fun harvestEpisodes(root: Element): List<EpisodeRaw> {
        val result = LinkedHashMap<String, EpisodeRaw>()

        for (anchor in root.select("a[href*='/watch/']")) {
            val full = fixUrl(anchor.attr("href").trim().substringBefore("#"))
            val path = full.substringBefore("?")
            if (!WATCH_HREF.containsMatchIn(path)) continue

            val text = anchor.text()
            val seasonEpisode = parseSeasonEp(path)
            val episode = seasonEpisode?.second
                ?: EP_TEXT.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val season = seasonEpisode?.first ?: 1

            result.getOrPut(path) {
                EpisodeRaw(path, season, episode, text.contains("VIP"))
            }
        }

        return result.values.toList()
    }

    private suspend fun loadSeries(url: String): LoadResponse? {
        val document = attempt { app.get(url).document } ?: return null

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !it.contains("logo-brand") }
            ?.let { fixUrlNull(it) }
            ?: document.firstPoster()

        val plot = document.getElementsContainingOwnText("Sinopsis")
            .firstOrNull { it.ownText().trim().equals("Sinopsis", ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val bodyText = document.body().text()
        val status = when {
            Regex("Finalizad", RegexOption.IGNORE_CASE).containsMatchIn(bodyText) ->
                ShowStatus.Completed
            Regex("Emisi[oó]n", RegexOption.IGNORE_CASE).containsMatchIn(bodyText) ->
                ShowStatus.Ongoing
            else -> null
        }

        val raw = LinkedHashMap<String, EpisodeRaw>()
        harvestEpisodes(document).forEach { raw[it.url] = it }

        // Si la serie tiene varias temporadas y la página solo trae una, el resto se pide
        // a través de la página de un capítulo (?s=N), que es como las cambia la web.
        val labeled = SEASON_LABEL.findAll(bodyText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
        val found = raw.values.map { it.season }.toSet()
        val anchorEpisode = raw.values.firstOrNull()?.url

        if (anchorEpisode != null) {
            for (season in (labeled - found).sorted()) {
                val seasonDocument = attempt {
                    app.get("$anchorEpisode?s=$season").document
                } ?: continue

                harvestEpisodes(seasonDocument)
                    .filter { it.season == season }
                    .forEach { raw.putIfAbsent(it.url, it) }
            }
        }

        val episodes = raw.values
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .map { item ->
                newEpisode(item.url) {
                    this.name = "Capítulo ${item.episode}" + if (item.vip) " · VIP" else ""
                    this.season = item.season
                    this.episode = item.episode
                }
            }

        if (url.contains("/peliculas/") && episodes.isNotEmpty()) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            if (status != null) this.showStatus = status
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ───────────────────────── Links ─────────────────────────

    private class PlayerSource(
        val id: String,
        val label: String,
        val token: String,
        val provider: String,
        val priority: Int
    )

    /**
     * La página del capítulo trae las fuentes dentro del payload de Next.js, como objetos con
     * "label", "token", "provider", "isWorking", "priority". La URL real no está en la página:
     * se pide aparte a /api/player/source con el token.
     */
    private fun parseSources(html: String): List<PlayerSource> {
        // En el payload las comillas vienen escapadas (\"); se normalizan antes de buscar.
        val text = html.replace("\\\"", "\"")

        return SOURCE_OBJECT.findAll(text)
            .mapNotNull { match ->
                val json = attempt0 { JSONObject(match.value) } ?: return@mapNotNull null
                val token = json.optString("token").trim()
                if (token.isBlank()) return@mapNotNull null
                if (!json.optBoolean("isWorking", true)) return@mapNotNull null

                val label = json.optString("label").ifBlank { json.optString("name") }.trim()
                val provider = json.optString("provider").trim()

                PlayerSource(
                    id = json.optString("id"),
                    label = label.ifBlank { provider.ifBlank { "Servidor" } },
                    token = token,
                    provider = provider,
                    priority = json.optInt("priority", 99)
                )
            }
            .distinctBy { it.token }
            .sortedBy { it.priority }
            .toList()
    }

    // Versión no suspendida de attempt() para usar dentro de secuencias.
    private inline fun <T> attempt0(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        }

    private fun displayName(label: String): String {
        val value = label.trim()
        return when {
            value.isBlank() -> "Servidor"
            value.length <= 3 -> value.uppercase()
            else -> value.replaceFirstChar { it.uppercase() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sink = LinkSink(callback)

        val page = attempt {
            app.get(data, headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "$mainUrl/"))
        } ?: return false

        val sources = parseSources(page.text)
        debug { "fuentes encontradas: ${sources.map { it.label }}" }

        val cookies = page.cookies.entries
            .joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }

        // Todas las fuentes se resuelven en paralelo; si una falla, las demás siguen.
        sources.amap { source ->
            guarded(source.label) {
                resolveSource(source, data, cookies, subtitleCallback, sink)
            }
        }

        debug { "loadLinks final linkCount=${sink.total}" }
        return sink.total > 0
    }

    private suspend fun resolveSource(
        source: PlayerSource,
        pageUrl: String,
        cookies: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        val headers = mutableMapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to pageUrl,
            "Origin" to mainUrl,
            "Content-Type" to "application/json",
            "Accept" to "*/*"
        )
        if (cookies.isNotBlank()) headers["Cookie"] = cookies

        val response = app.post(
            "$mainUrl/api/player/source",
            headers = headers,
            json = JsonAsString(JSONObject().put("token", source.token).toString())
        )

        debug { "${source.label} -> HTTP ${response.code}: ${response.text.take(400)}" }
        if (response.code !in 200..299) return

        val urls = ArrayList<String>()
        val subtitles = ArrayList<Pair<String, String>>()
        val body = response.text.trim()

        val parsed: Any? = attempt0 {
            when {
                body.startsWith("{") -> JSONObject(body)
                body.startsWith("[") -> JSONArray(body)
                else -> null
            }
        }

        if (parsed != null) {
            collectFromJson(parsed, urls, subtitles)
        } else if (body.startsWith("http") || body.startsWith("//")) {
            urls.add(body)
        } else {
            IFRAME_SRC.find(body)?.groupValues?.getOrNull(1)?.let { urls.add(it) }
        }

        for ((label, subtitleUrl) in subtitles.distinctBy { it.second }) {
            subtitleCallback(SubtitleFile(lang = label, url = subtitleUrl))
        }

        for (candidate in urls.map { normalizeUrl(it) }.distinct()) {
            emitUrl(candidate, source, pageUrl, sink)
        }
    }

    private fun collectFromJson(
        node: Any?,
        urls: MutableList<String>,
        subtitles: MutableList<Pair<String, String>>
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    val lowerKey = key.lowercase()

                    when {
                        lowerKey in SUB_KEYS -> collectSubtitles(value, subtitles)
                        lowerKey in IGNORE_KEYS -> Unit
                        else -> collectFromJson(value, urls, subtitles)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectFromJson(node.opt(index), urls, subtitles)
                }
            }

            is String -> {
                val value = node.trim()
                when {
                    value.startsWith("http") || value.startsWith("//") -> {
                        val full = normalizeUrl(value)
                        when {
                            SUB_EXT.containsMatchIn(full) -> subtitles.add("Español" to full)
                            IMAGE_EXT.containsMatchIn(full) -> Unit
                            else -> urls.add(full)
                        }
                    }

                    value.contains("<iframe", ignoreCase = true) ->
                        IFRAME_SRC.find(value)?.groupValues?.getOrNull(1)?.let { urls.add(it) }
                }
            }
        }
    }

    private fun collectSubtitles(node: Any?, subtitles: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONArray -> {
                for (index in 0 until node.length()) collectSubtitles(node.opt(index), subtitles)
            }

            is JSONObject -> {
                val subtitleUrl = listOf("url", "file", "src", "link")
                    .map { node.optString(it).trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: return

                val label = listOf("label", "lang", "language", "name", "srclang")
                    .map { node.optString(it).trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: "Español"

                subtitles.add(label to normalizeUrl(subtitleUrl))
            }

            is String -> {
                val value = node.trim()
                if (value.startsWith("http") || value.startsWith("//")) {
                    subtitles.add("Español" to normalizeUrl(value))
                }
            }
        }
    }

    private suspend fun emitUrl(
        url: String,
        source: PlayerSource,
        pageUrl: String,
        sink: LinkSink
    ) {
        val lower = url.lowercase()
        val display = displayName(source.label)

        when {
            lower.contains("dailymotion.com") || lower.contains("dai.ly") -> {
                val id = dailymotionVideoId(url)
                if (id != null) {
                    if (!sink.claim("dm:$id")) return
                    val link = resolveDailymotion(id, display)
                    if (link != null) {
                        sink.emit(link)
                        return
                    }
                }
                extractWithExtractors(url, pageUrl, sink)
            }

            lower.contains(".m3u8") ->
                emitDirect(url, display, ExtractorLinkType.M3U8, sink)

            lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ->
                emitDirect(url, display, ExtractorLinkType.VIDEO, sink)

            // R2 es el video propio de la web: si no trae extensión se trata como HLS.
            source.provider.equals("r2", ignoreCase = true) ||
                lower.contains("videos.donghualife.com") ->
                emitDirect(url, display, ExtractorLinkType.M3U8, sink)

            else -> extractWithExtractors(url, pageUrl, sink)
        }
    }

    private suspend fun emitDirect(
        url: String,
        display: String,
        type: ExtractorLinkType,
        sink: LinkSink
    ) {
        val link = newExtractorLink(
            source = name,
            name = "$name · $display",
            url = url,
            type = type
        ) {
            this.referer = "$mainUrl/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Origin" to mainUrl,
                "User-Agent" to BROWSER_UA
            )
        }
        sink.emit(link)
    }

    /** Rumble y cualquier otro embed: se resuelve con los extractores de CloudStream. */
    private suspend fun extractWithExtractors(url: String, pageUrl: String, sink: LinkSink) {
        val found = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())

        loadExtractor(
            url = url,
            referer = "$mainUrl/",
            subtitleCallback = { },
            callback = { found.add(it) }
        )

        val selected = if (url.contains("rumble.com", ignoreCase = true)) {
            // De Rumble solo sirven las playlists HLS; si no hay ninguna se deja lo que haya.
            found.filter { it.url.contains("rumble.com/hls-vod/") }.ifEmpty { found.toList() }
        } else {
            found.toList()
        }

        debug { "extractor $url -> ${selected.size} links (referer $pageUrl)" }
        selected.forEach { sink.emit(it) }
    }

    // ───────────────────────── Dailymotion ─────────────────────────

    private fun dailymotionVideoId(embedUrl: String): String? =
        embedUrl
            .substringAfter("video=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }
            ?: DM_PATH_ID.find(embedUrl)?.groupValues?.getOrNull(1)

    private suspend fun resolveDailymotion(videoId: String, display: String): ExtractorLink? {
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
            source = name,
            name = "$name · $display",
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://geo.dailymotion.com/"
            this.quality = Qualities.Unknown.value
        }
    }
}
