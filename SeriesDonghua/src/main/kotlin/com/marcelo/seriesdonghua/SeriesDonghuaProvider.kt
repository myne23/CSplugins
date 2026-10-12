package com.marcelo.seriesdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import java.util.Collections
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
        MD_NEW_URL to "Nuevos Episodios Mundodonghua",
        "$mainUrl/todos-los-donghuas/" to "Catalogo Completo Seriesdonghua",
        "$MD_URL/lista-donghuas" to "Catalogo Completo Mundodonghua"
    )

    private companion object {
        // Poné DEBUG = true solo cuando quieras ver logs en logcat.
        const val DEBUG = false

        const val MD_URL = "https://www.mundodonghua.com"

        // Home de MundoDonghua: de ahí sale la sección "Nuevos Episodios" (primera página).
        const val MD_NEW_URL = "$MD_URL/"

        // Tiempos máximos (segundos) para MundoDonghua: si la web no responde, se la deja de lado.
        const val MD_FAST_TIMEOUT_S = 4L
        const val MD_PAGE_TIMEOUT_S = 6L

        // Etiquetas que se agregan al nombre de cada fuente para saber de qué web viene.
        const val SD_TAG = "SeriesDonghua"
        const val MD_TAG = "MundoDonghua"

        // Los capítulos "Limitados" de la home de MundoDonghua se guardan unos minutos
        // para no volver a pedir la home cada vez que se abre una serie.
        const val MD_LIMITED_TTL_MS = 3 * 60 * 1000L

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
        val APOSTROPHES = Regex("""[’'`´]""")
        val EPISODE_SLUG = Regex("/([^/]+)-episodio-(\\d+)/?$")
        val DM_PATH_ID = Regex("/video/([A-Za-z0-9]+)")

        // MundoDonghua: /ver/serie/N (capítulo) y /donghua/serie (ficha). Los capítulos
        // "Limitados" de Patreon traen un token como tercer tramo y no coinciden a propósito.
        val MD_EPISODE_PATH = Regex("""/ver/([^/?#]+)/([^/?#]+)/?$""")
        val MD_SERIES_PATH = Regex("""/donghua/([^/?#]+)/?$""")
        // Capítulo "Limitado": /ver/serie/N/TOKEN (se puede reproducir igual que los normales).
        val MD_LIMITED_PATH = Regex("""/ver/([^/?#]+)/(\d+(?:\.\d+)?)/([^?#]+)$""")
        val MD_EPISODE_SUFFIX = Regex("""\s+Episodio\s+\d.*$""", RegexOption.IGNORE_CASE)

        // Cada servidor de MundoDonghua es un script empaquetado (eval(function(p,a,c,k,e,d)...)
        // que al hacer click agrega un <iframe src='...'>.
        val PACKED_SCRIPT = Regex(
            """eval\(function\(p,a,c,k,e,.*?\.split\('\|'\),0,\{\}\)\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val EMBED_SRC = Regex("""src=\\?['"](https?://[^'"\\\s]+)""")

        val ODYSEE_CONTENT_URL = Regex(
            """"contentUrl"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )
        // OK.ru: el reproductor trae el JSON escapado (&quot;, \", \/, \u0026...) y a veces
        // anidado; se limpia todo antes de buscar. Los videos normales (VOD) traen una lista de
        // mp4 por calidad y, según el caso, un manifest HLS (hlsManifestUrl / ondemandHls).
        val OK_BACKSLASH_QUOTE = Regex("""\\+\"""")
        val OK_BACKSLASH_AMP = Regex("""\\+u0026""", RegexOption.IGNORE_CASE)
        val OK_BACKSLASH_EQ = Regex("""\\+u003d""", RegexOption.IGNORE_CASE)
        val OK_BACKSLASH_SLASH = Regex("""\\+(?:/|u002f)""", RegexOption.IGNORE_CASE)
        val OK_VIDEO_ENTRY = Regex(""""name":"([a-z]+)","url":"(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
        val OK_HLS_URL = Regex(
            """"(?:hlsManifestUrl|ondemandHls|hlsMasterPlaylistUrl)":"(https?://[^"]+)"""",
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

    // Los apóstrofes se quitan (no se cambian por espacio) para que "Mortal’s" y "mortals"
    // normalicen igual: MundoDonghua usa el apóstrofe curvo en varios títulos.
    private fun normalize(text: String): String =
        text.lowercase().replace(APOSTROPHES, "").replace(NON_ALNUM, " ").trim()

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

    /** "VOE" + "MundoDonghua" -> "VOE · MundoDonghua" (sin repetir la etiqueta si ya la trae). */
    private fun tagged(base: String, tag: String): String =
        if (base.contains(tag, ignoreCase = true)) base else "$base · $tag"

    /**
     * Un extractor puede devolver el mismo video varias veces (una por calidad). Para que la
     * fuente salga una sola vez se elige: el master m3u8, si no el m3u8 sin calidad fija
     * (también es un master) y, si no hay, el de mayor calidad.
     */
    private fun pickBestLink(links: List<ExtractorLink>): ExtractorLink? {
        if (links.isEmpty()) return null
        links.firstOrNull { it.type == ExtractorLinkType.M3U8 && it.url.contains("master", true) }
            ?.let { return it }
        links.firstOrNull { it.type == ExtractorLinkType.M3U8 && it.quality <= 0 }
            ?.let { return it }
        return links.maxByOrNull { it.quality }
    }

    /** Texto del elemento respetando los <br> como saltos de línea. */
    private fun Element.textWithBreaks(): String = buildString {
        for (node in childNodes()) {
            when {
                node is TextNode -> append(node.text())
                node is Element && node.tagName() == "br" -> append('\n')
                node is Element -> append(node.text())
            }
        }
    }.lines().joinToString("\n") { it.trim() }.trim()

    /** Recolecta links sin repetir URLs ni trabajo ya hecho; seguro para uso paralelo. */
    private class LinkSink(private val callback: (ExtractorLink) -> Unit) {
        private val seenUrls = ConcurrentHashMap.newKeySet<String>()
        private val claimed = ConcurrentHashMap.newKeySet<String>()
        private val counter = AtomicInteger(0)
        private val nameCounts = ConcurrentHashMap<String, AtomicInteger>()

        val total: Int get() = counter.get()

        /** Si dos fuentes se llaman igual (p. ej. dos Filemoon) la segunda sale como "... 2". */
        fun uniqueName(base: String): String {
            val n = nameCounts.computeIfAbsent(base) { AtomicInteger(0) }.incrementAndGet()
            return if (n == 1) base else "$base $n"
        }

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
        val isMundoDonghua = request.data.startsWith(MD_URL)
        val isMdLatest = request.data == MD_NEW_URL

        val url = when {
            page == 1 -> request.data
            // El home de MundoDonghua no pagina: los episodios siguientes están en /lista-episodios/N.
            isMdLatest -> "$MD_URL/lista-episodios/$page"
            // El catálogo de MundoDonghua pagina como /lista-donghuas/2, no con ?page=2.
            isMundoDonghua -> "${request.data.trimEnd('/')}/$page"
            else -> {
                val separator = if (request.data.contains("?")) "&" else "?"
                "${request.data}${separator}page=$page"
            }
        }

        // Cada sección falla por separado: si una página se cae, la sección queda vacía
        // y el resto del catálogo sigue funcionando.
        val home: List<SearchResponse> = attempt {
            if (isMundoDonghua) {
                val document = app.get(url, timeout = MD_FAST_TIMEOUT_S).document
                if (isMdLatest) parseMundoDonghuaLatest(document, page) else parseMundoDonghuaCards(document)
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
            { searchMundoDonghua(encodedQuery, normalizedQuery) }
        )

        return sources.amap { it() }.flatten()
    }

    private suspend fun searchMundoDonghua(
        encodedQuery: String,
        normalizedQuery: String
    ): List<SearchResponse> {
        val document = attempt {
            app.get("$MD_URL/busquedas/?donghua=$encodedQuery", timeout = MD_FAST_TIMEOUT_S).document
        } ?: return emptyList()

        // Se vuelve a filtrar por título aunque la web ya filtre: así el resultado es el mismo
        // si la web devolviera la lista completa.
        return parseMundoDonghuaCards(document)
            .mapNotNull { result ->
                val score = titleScore(normalize(result.name), normalizedQuery, 80)
                if (score <= 0) return@mapNotNull null

                val response = newAnimeSearchResponse(
                    "${result.name} · MundoDonghua",
                    result.url,
                    TvType.Anime
                ) {
                    this.posterUrl = result.posterUrl
                }

                score to response
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    // ───────────────────────── Detalle de serie ─────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("mundodonghua.com/")) {
            return loadMundoDonghuaSeries(url)
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

    // ───────────────────────── MundoDonghua ─────────────────────────

    /** Une rutas relativas de MundoDonghua (/thumbs/..., /ver/...) con su dominio. */
    private fun mdUrl(path: String?): String? {
        val value = path?.trim().orEmpty()
        return when {
            value.isBlank() -> null
            value.startsWith("http", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$MD_URL$value"
            else -> "$MD_URL/$value"
        }
    }

    /** Slug de la serie a partir de /donghua/slug o de /ver/slug/N. */
    private fun mdSeriesSlug(href: String): String? =
        MD_SERIES_PATH.find(href)?.groupValues?.getOrNull(1)
            ?: MD_EPISODE_PATH.find(href)?.groupValues?.getOrNull(1)
            ?: MD_LIMITED_PATH.find(href)?.groupValues?.getOrNull(1)

    /** Capítulo "Limitado" de la home de MundoDonghua (se puede ver con su link con token). */
    private class MdLimited(
        val slug: String,
        val number: String,
        val url: String,
        val title: String,
        val poster: String?
    )

    @Volatile
    private var mdLimitedCache: List<MdLimited> = emptyList()

    @Volatile
    private var mdLimitedAt = 0L

    /** Busca los capítulos limitados en cualquier parte de la home, sin depender del contenedor. */
    private fun parseMdLimited(document: Document): List<MdLimited> =
        document.select("a[href*='/ver/']").mapNotNull { anchor ->
            val url = mdUrl(anchor.attr("href")) ?: return@mapNotNull null
            val match = MD_LIMITED_PATH.find(url) ?: return@mapNotNull null
            val slug = match.groupValues[1]
            val number = match.groupValues[2]

            val card = anchor.parents().firstOrNull { it.hasClass("md-card") }
            val image = anchor.selectFirst("img") ?: card?.selectFirst("img")

            val title = image?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() }
                ?: card?.selectFirst(".md-card-title")?.text()?.replace(MD_EPISODE_SUFFIX, "")?.trim()
                    .takeUnless { it.isNullOrBlank() }
                ?: slug.replace('-', ' ')

            MdLimited(slug, number, url, title, mdUrl(image.imageUrl()))
        }.distinctBy { it.url }

    /** Limitados de la home; usa la copia guardada si es reciente. */
    private suspend fun mdLimitedList(): List<MdLimited> {
        val now = System.currentTimeMillis()
        if (mdLimitedAt != 0L && now - mdLimitedAt < MD_LIMITED_TTL_MS) return mdLimitedCache

        val document = attempt { app.get(MD_NEW_URL, timeout = MD_FAST_TIMEOUT_S).document }
            ?: return mdLimitedCache

        val list = parseMdLimited(document)
        mdLimitedCache = list
        mdLimitedAt = System.currentTimeMillis()
        return list
    }

    private fun mdLatestCard(card: Element): SearchResponse? {
        val href = card.selectFirst("a[href*='/ver/']")?.attr("href")?.trim()
            ?: return null

        // Acepta capítulos normales (/ver/serie/N) y limitados (/ver/serie/N/TOKEN).
        if (!MD_EPISODE_PATH.containsMatchIn(href) && !MD_LIMITED_PATH.containsMatchIn(href)) return null

        val slug = mdSeriesSlug(href) ?: return null
        val image = card.selectFirst("img")

        val title = image?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() }
            ?: card.selectFirst(".md-card-title")?.text()?.replace(MD_EPISODE_SUFFIX, "")?.trim()
            ?: return null

        return newAnimeSearchResponse(title, "$MD_URL/donghua/$slug", TvType.Anime) {
            this.posterUrl = mdUrl(image.imageUrl())
        }
    }

    /**
     * "Nuevos Episodios" de MundoDonghua: cada ítem es un capítulo. Se muestra con el nombre
     * de la serie y apunta a la ficha de la serie (una sola tarjeta por serie).
     * Los capítulos "Limitados" también se incluyen (van primero, como en la web).
     */
    private fun parseMundoDonghuaLatest(document: Document, page: Int): List<SearchResponse> {
        // En el home los episodios están en #nuevos-episodios-grid; en /lista-episodios, en toda la página.
        val scope = document.selectFirst("#nuevos-episodios-grid") ?: document

        val limited: List<SearchResponse> = if (page == 1) {
            val items = parseMdLimited(document)
            mdLimitedCache = items
            mdLimitedAt = System.currentTimeMillis()

            items.map { item ->
                newAnimeSearchResponse(item.title, "$MD_URL/donghua/${item.slug}", TvType.Anime) {
                    this.posterUrl = item.poster
                }
            }
        } else {
            emptyList()
        }

        val regular = scope.select("div.md-card").mapNotNull { mdLatestCard(it) }

        return (limited + regular).distinctBy { it.url }
    }

    /** Tarjetas de series (catálogo y búsqueda de MundoDonghua). */
    private fun parseMundoDonghuaCards(document: Document): List<SearchResponse> =
        document.select("div.md-card").mapNotNull { card ->
            val href = card.selectFirst("a[href*='/donghua/']")?.attr("href")?.trim()
                ?: return@mapNotNull null

            val slug = MD_SERIES_PATH.find(href)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val image = card.selectFirst("img")

            val title = card.selectFirst(".md-card-title")?.text()?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: image?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() }
                ?: return@mapNotNull null

            newAnimeSearchResponse(title, "$MD_URL/donghua/$slug", TvType.Anime) {
                this.posterUrl = mdUrl(image.imageUrl())
            }
        }.distinctBy { it.url }

    private suspend fun loadMundoDonghuaSeries(url: String): LoadResponse? {
        // Si llega un capítulo (/ver/serie/N) se abre la serie: el slug ya dice cuál es.
        val slug = mdSeriesSlug(url) ?: return null
        val seriesUrl = "$MD_URL/donghua/$slug"

        // Los limitados no están en la ficha de la serie: se piden en paralelo desde la home.
        var fetchedDocument: Document? = null
        var limitedItems: List<MdLimited> = emptyList()

        listOf<suspend () -> Unit>(
            {
                fetchedDocument = attempt {
                    app.get(seriesUrl, timeout = MD_PAGE_TIMEOUT_S).document
                }
            },
            { limitedItems = attempt { mdLimitedList() } ?: emptyList() }
        ).amap { it() }

        val document = fetchedDocument ?: return null

        val title = document.selectFirst("h1.md-detail-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst(".md-detail-poster img")?.attr("src")

        val plot = document.selectFirst("p.md-detail-synopsis")?.textWithBreaks()

        val tags = document
            .select(".md-genres-block a.md-genre-tag")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val numberedEpisodes = document
            .select("li.md-episode-item")
            .mapNotNull { item ->
                val link = mdUrl(item.selectFirst("a[href]")?.attr("href"))
                    ?: return@mapNotNull null

                // Hay episodios con decimales (p. ej. 17.5): se usan para ordenar, pero
                // CloudStream solo admite números enteros en `episode`.
                val rawNumber = item.attr("data-ep").trim()
                    .ifBlank { MD_EPISODE_PATH.find(link)?.groupValues?.getOrNull(2).orEmpty() }
                val sortKey = rawNumber.toFloatOrNull() ?: return@mapNotNull null

                val episodeName = item.selectFirst("h5")?.text()?.trim()
                val episodePoster = item.selectFirst("img")?.attr("src")

                sortKey to newEpisode(link) {
                    this.name = episodeName
                    this.episode = rawNumber.toIntOrNull()
                    this.posterUrl = mdUrl(episodePoster)
                }
            }

        // Capítulos "Limitados" de esta serie que todavía no están en la lista normal.
        val knownNumbers = numberedEpisodes.map { it.first }.toSet()
        val limitedEpisodes = limitedItems
            .filter { it.slug == slug }
            .mapNotNull { item ->
                val sortKey = item.number.toFloatOrNull() ?: return@mapNotNull null
                if (sortKey in knownNumbers) return@mapNotNull null

                sortKey to newEpisode(item.url) {
                    this.name = "$title - ${item.number} (Limitado)"
                    this.episode = item.number.toIntOrNull()
                    this.posterUrl = item.poster
                }
            }

        val episodes = (numberedEpisodes + limitedEpisodes)
            .sortedBy { it.first }
            .map { it.second }

        return newAnimeLoadResponse("$title · MundoDonghua", seriesUrl, TvType.Anime) {
            this.posterUrl = mdUrl(poster)
            this.plot = plot
            this.tags = tags
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun fetchMundoDonghuaPage(url: String): Document? {
        val response = attempt { app.get(url, timeout = MD_PAGE_TIMEOUT_S) } ?: return null
        return if (response.isSuccessful) response.document else null
    }

    /** Capítulo que viene directo de MundoDonghua (/ver/serie/N). */
    private suspend fun loadMundoDonghuaEpisodeLinks(data: String, sink: LinkSink) {
        val document = fetchMundoDonghuaPage(data) ?: return
        resolveMundoDonghuaEpisode(document, data, sink)
    }

    /**
     * Fuente adicional para un capítulo de SeriesDonghua: se arma el link directo
     * /ver/{slug}/{N} de MundoDonghua (los slugs coinciden en casi todas las series).
     * Si esa serie o ese capítulo no existen allá, simplemente no se suman links.
     */
    private suspend fun loadMundoDonghuaLinks(data: String, sink: LinkSink) {
        val match = EPISODE_SLUG.find(data) ?: return

        val seriesSlug = match.groupValues[1]
        val episodeNumber = match.groupValues[2]
        val episodeUrl = "$MD_URL/ver/$seriesSlug/$episodeNumber"

        debug { "MundoDonghua episodio -> $episodeUrl" }

        val document = fetchMundoDonghuaPage(episodeUrl) ?: return
        resolveMundoDonghuaEpisode(document, episodeUrl, sink)
    }

    /** Desempaqueta los scripts de los servidores (Filemoon, VOE, VidHide, StreamWish) y los resuelve en paralelo. */
    private suspend fun resolveMundoDonghuaEpisode(
        document: Document,
        episodeUrl: String,
        sink: LinkSink
    ) {
        val embeds = LinkedHashSet<String>()

        for (script in document.select("script")) {
            val code = script.data()
            if (!code.contains("eval(function(p,a,c,k,e,")) continue

            // Un mismo <script> puede traer varios eval: se desempaqueta cada uno por separado.
            for (packed in PACKED_SCRIPT.findAll(code)) {
                val unpacked = attempt { JsUnpacker(packed.value).unpack() } ?: continue
                EMBED_SRC.find(unpacked)?.groupValues?.getOrNull(1)?.let { embeds.add(it) }
            }
        }

        debug { "MundoDonghua embeds=$embeds" }

        embeds.toList().amap { embedUrl ->
            guarded("MundoDonghua $embedUrl") {
                resolveMundoDonghuaEmbed(embedUrl, episodeUrl, sink)
            }
        }
    }

    private suspend fun resolveMundoDonghuaEmbed(
        embedUrl: String,
        episodeUrl: String,
        sink: LinkSink
    ) {
        if (!sink.claim("embed:$embedUrl")) return

        if (embedUrl.lowercase().contains("voe.")) {
            val voeUrl = extractVoeLink(embedUrl) ?: return
            val label = sink.uniqueName(tagged("VOE", MD_TAG))
            sink.emit(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = voeUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = embedUrl
                    quality = 0
                }
            )
            return
        }

        // Filemoon, VidHide y StreamWish los resuelven los extractores de CloudStream.
        // Los subtítulos se descartan a propósito (MundoDonghua ya trae los subs en el video).
        // Cada extractor puede devolver el mismo video una vez por calidad: se juntan todos
        // y se emite uno solo por servidor, para que no aparezca repetido.
        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())

        loadExtractor(
            url = embedUrl,
            referer = episodeUrl,
            subtitleCallback = { },
            callback = { link -> collected.add(link) }
        )

        val best = pickBestLink(synchronized(collected) { collected.toList() }) ?: return
        val label = sink.uniqueName(tagged(best.source.ifBlank { best.name }, MD_TAG))

        sink.emit(
            ExtractorLink(
                source = label,
                name = label,
                url = best.url,
                referer = best.referer,
                quality = best.quality,
                type = best.type
            )
        )
    }

    // ───────────────────────── Links ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sink = LinkSink(callback)

        // Episodio que viene directo de MundoDonghua.
        if (data.contains("mundodonghua.com/ver/")) {
            guarded("MundoDonghua episodio") {
                loadMundoDonghuaEpisodeLinks(data, sink)
            }
            return sink.total > 0
        }

        // Todas las fuentes (los 5 servidores + MundoDonghua) corren en paralelo.
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

        // Fuente adicional: MundoDonghua. Corre aparte y con timeouts cortos en cada request:
        // si esa web está caída o lenta, no rompe los links de SeriesDonghua.
        tasks.add {
            guarded("MundoDonghua") {
                loadMundoDonghuaLinks(data, sink)
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
            embedLower.contains("odysee.com") -> {
                loadOdyseeLink(embedUrl)?.let { sink.emit(it) }
            }

            embedLower.contains("ok.ru") || embedLower.contains("odnoklassniki") -> {
                loadOkRuLinks(embedUrl, pageUrl).forEach { sink.emit(it) }
            }

            embedLower.contains("dailymotion.com") -> {
                val id = dailymotionVideoId(embedUrl)
                if (id != null && sink.claim("dm:$id")) {
                    resolveDailymotion(id, tagged("Dailymotion", SD_TAG))?.let { sink.emit(it) }
                }
            }

            embedLower.contains("voe") -> {
                val voeUrl = extractVoeLink(embedUrl)
                if (voeUrl != null) {
                    val voeLabel = tagged("VOE", SD_TAG)
                    val voeLink = newExtractorLink(
                        source = voeLabel,
                        name = voeLabel,
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
                // Los subtítulos de estos servidores se descartan a propósito.
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
                            val label = tagged(link.name.ifBlank { serverName }, SD_TAG)
                            sink.emit(
                                ExtractorLink(
                                    source = label,
                                    name = label,
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

    // ───────────────────────── Dailymotion ─────────────────────────

    private fun dailymotionVideoId(embedUrl: String): String? =
        embedUrl
            .substringAfter("video=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }
            ?: DM_PATH_ID.find(embedUrl)?.groupValues?.getOrNull(1)

    private suspend fun resolveDailymotion(
        videoId: String,
        label: String
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
            name = label,
            source = label,
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            referer = "https://geo.dailymotion.com/"
            quality = 0
        }
    }

    // ───────────────────────── OK.ru / Odysee ─────────────────────────

    /** Quita los escapes del reproductor de OK.ru (entidades HTML, barras invertidas, \\u0026...) para poder buscar con regex. */
    private fun unescapePlayerText(raw: String): String =
        raw.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace(OK_BACKSLASH_QUOTE, "\"")
            .replace(OK_BACKSLASH_AMP, "&")
            .replace(OK_BACKSLASH_EQ, "=")
            .replace(OK_BACKSLASH_SLASH, "/")

    private fun okQuality(name: String): Int? = when (name.lowercase()) {
        "mobile" -> 144
        "lowest" -> 240
        "low" -> 360
        "sd" -> 480
        "hd" -> 720
        "full" -> 1080
        "quad" -> 1440
        "ultra" -> 2160
        else -> null
    }

    /**
     * OK.ru igual que en ExampleProvider: se pide /videoembed/ (no /video/) y se leen los mp4
     * por calidad ("name"/"url") además del manifest HLS si existe. Antes solo se buscaba
     * hlsManifestUrl, que los videos normales de OK.ru no suelen traer.
     */
    private suspend fun loadOkRuLinks(embedUrl: String, pageUrl: String): List<ExtractorLink> {
        val label = tagged("OK.ru", SD_TAG)

        val okEmbedUrl = embedUrl.trim()
            .let { if (it.startsWith("//")) "https:$it" else it }
            .replace("://m.ok.ru", "://ok.ru")
            .replace("/video/", "/videoembed/")

        // Algunos videos solo se dejan incrustar desde la web de origen: se prueba con ese
        // Referer y, si no trae datos, con el del propio OK.ru.
        val referers = listOf("$mainUrl/", pageUrl, "https://ok.ru/").distinct()

        var text: String? = null
        for (ref in referers) {
            val response = attempt {
                app.get(
                    okEmbedUrl,
                    headers = mapOf(
                        "User-Agent" to BROWSER_UA,
                        "Referer" to ref
                    )
                )
            } ?: continue

            if (response.code !in 200..299) continue

            val normalized = unescapePlayerText(response.text)
            if (OK_VIDEO_ENTRY.containsMatchIn(normalized) || OK_HLS_URL.containsMatchIn(normalized)) {
                text = normalized
                break
            }
        }

        val normalized = text ?: return emptyList()
        val links = mutableListOf<ExtractorLink>()

        for (match in OK_VIDEO_ENTRY.findAll(normalized)) {
            val quality = okQuality(match.groupValues[1]) ?: continue
            val videoUrl = match.groupValues[2].trim()

            links.add(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = okEmbedUrl
                    this.quality = quality
                }
            )
        }

        OK_HLS_URL.find(normalized)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { hlsUrl ->
                links.add(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = okEmbedUrl
                        quality = Qualities.Unknown.value
                    }
                )
            }

        debug { "OK.ru links=${links.size} embed=$okEmbedUrl" }
        return links
    }

    private suspend fun loadOdyseeLink(embedUrl: String): ExtractorLink? {
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

        val label = tagged("Odysee", SD_TAG)

        return newExtractorLink(
            source = label,
            name = label,
            url = contentUrl,
            type = linkType
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
