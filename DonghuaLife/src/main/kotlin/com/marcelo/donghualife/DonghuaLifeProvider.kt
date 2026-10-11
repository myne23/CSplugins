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
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

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
        "$mainUrl/series" to "Series populares",
        "$mainUrl/series?status=finalizadas" to "Series finalizadas",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series?sort=az" to "Series A-Z"
    )

    private companion object {
        // DEBUG = true: logs en logcat con el tag "DonghuaLife" (ponelo en false para la versión final).
        const val DEBUG = true

        const val BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"

        const val INDEX_CACHE_MS = 30L * 60_000L
        const val HOME_CACHE_MS = 60_000L
        const val MAX_PAGES = 40

        // Tiempos máximos (segundos) para que una fuente lenta no cuelgue la carga.
        const val SOURCE_TIMEOUT_S = 20L
        const val HLS_TIMEOUT_S = 10L

        const val ALTCHA_TIMEOUT_NS = 25_000_000_000L
        val HEX_CHARS = "0123456789abcdef".toCharArray()

        val CARD_HREF = Regex("""/(?:series|peliculas)/[^/?#]+/?$""")
        val WATCH_HREF = Regex("""/watch/[^/?#]+/?$""")
        val MOVIE_HREF = Regex("""/peliculas/[^/?#]+/?$""")
        val WATCH_SLUG = Regex("""/watch/([A-Za-z0-9][A-Za-z0-9_-]*)""")

        // Texto de un link de capítulo: en la ficha "Capítulo 12 ...", en el reproductor "12 Capitulo ...".
        val EP_TEXT_START = Regex("""^\s*(?:\d+|(?:Cap[ií]tulo|Episodio|Ep\.?)\s*\d+)""", RegexOption.IGNORE_CASE)
        val EP_LEADING_NUMBER = Regex("""^\s*(\d+)""")
        val EP_TEXT = Regex("""(?:Cap[ií]tulo|Episodio)\s*(\d+)""", RegexOption.IGNORE_CASE)
        val SLUG_TAIL_NUMBER = Regex("""-(\d+)$""")
        val EP_IN_CARD = Regex("""^(.*?)EP\s*\d""")
        val PAGE_PARAM = Regex("""[?&]page=(\d+)""")
        val SEASON_PARAM = Regex("""[?&]s=(\d+)""")
        val SEASON_LABEL = Regex("""^Temporada\s+(\d+)$""", RegexOption.IGNORE_CASE)
        val SEASON_HEADING = Regex("""^(.+?)\s*[—–-]\s*\d+\s+Episodios?""", RegexOption.IGNORE_CASE)
        val SEASON_COUNT = Regex("""(\d+)\s+Temporadas?""", RegexOption.IGNORE_CASE)
        val NEXT_IMAGE_URL = Regex("""[?&]url=([^&]+)""")
        val DIACRITICS = Regex("""\p{M}+""")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val DM_PATH_ID = Regex("/video/([A-Za-z0-9]+)")
        val IFRAME_SRC = Regex("""src=["']([^"']+)["']""")
        val IMAGE_EXT = Regex("""\.(?:jpe?g|png|webp|gif|svg|avif)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        val SUB_EXT = Regex("""\.(?:vtt|srt|ass|ssa)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        val SUB_NOISE = Regex("thumb|sprite|storyboard|preview")

        // Cada fuente del episodio trae un campo "token" (ver parseSources).
        val TOKEN_FIELD = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
        val SOURCE_OBJECT = Regex("""\{[^{}]*"token"\s*:\s*"[^"]+"[^{}]*\}""")

        // Subtítulos: arrays/objetos bajo estas claves, o cualquier .vtt/.srt suelto como último recurso.
        val SUB_ARRAY_KEY = Regex(
            "\"(?:subtitles?|tracks|captions|subs|textTracks|subtitleTracks)\"\\s*:\\s*[\\[{]",
            RegexOption.IGNORE_CASE
        )
        val SUB_TEXT_URL = Regex(
            "(?:https?:)?//[^\"'\\s\\\\<>]+?\\.(?:vtt|srt|ass|ssa)(?:\\?[^\"'\\s\\\\<>]*)?" +
                "|/[^\"'\\s\\\\<>]+?\\.(?:vtt|srt|ass|ssa)(?:\\?[^\"'\\s\\\\<>]*)?",
            RegexOption.IGNORE_CASE
        )
        val FILE_LANG = Regex("(?:^|[._-])(es|en|pt|fr|id|ar|ru|it|de|tr|vi|th)(?:[._-]|$)")
        val SUB_URL_KEYS = listOf("url", "file", "src", "link", "path", "href", "uri", "vtt", "srt")
        val SUB_LABEL_KEYS = listOf("label", "lang", "language", "name", "srclang", "title", "code")
        val HLS_URI = Regex("URI=\"([^\"]+)\"")
        val HLS_NAME = Regex("NAME=\"([^\"]+)\"")
        val HLS_LANG = Regex("LANGUAGE=\"([^\"]+)\"")

        val ODYSEE_CONTENT_URL = Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        const val ODYSEE_API = "https://api.na-backend.odysee.com/api/v1/proxy"
        const val ODYSEE_ORIGIN = "https://odysee.com"
        const val FOREGROUND_CONCURRENCY = 5
        const val BACKGROUND_CONCURRENCY = 3
        const val INDEX_PARTIAL_TTL_MS = 2L * 60_000L
        const val INDEX_FILE_NAME = "donghualife_index_v1.json"
        val SIG_PARAM = Regex("[?&]signature=([^&]+)")
        val SIG_TS_PARAM = Regex("[?&]signature_ts=([^&]+)")
        val UUID_FIND = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val SUB_WORD = Regex("subtitle|subs|subtitulo", RegexOption.IGNORE_CASE)
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val ODYSEE_CLAIM_ID = Regex("^[0-9a-fA-F]{6,40}$")
        val OK_HLS_URL = Regex("\"hlsManifestUrl\":\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        val VOE_REDIRECT = Regex("""window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]""")
        val VOE_CONFIG = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']""")
        val VOE_CONFIG_STRICT = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>"""
        )
        val VOE_TOKEN = Regex("""name=["']_token["'][^>]+value=["']([^"']+)["']""")
        val VOE_CHALLENGE = Regex("""<altcha-widget[^>]+challenge=["']([^"']+)["']""")
        val VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")

        val STOPWORDS = setOf("el", "la", "los", "las", "de", "del", "the", "of", "a", "an", "and", "y", "en")

        val LANG_NAMES = mapOf(
            "es" to "Español",
            "es-419" to "Español (Latino)",
            "es-la" to "Español (Latino)",
            "es-mx" to "Español (Latino)",
            "es-es" to "Español (España)",
            "en" to "Inglés",
            "pt" to "Portugués",
            "pt-br" to "Portugués (Brasil)",
            "fr" to "Francés",
            "it" to "Italiano",
            "de" to "Alemán",
            "id" to "Indonesio",
            "ar" to "Árabe",
            "ru" to "Ruso",
            "tr" to "Turco",
            "vi" to "Vietnamita",
            "th" to "Tailandés"
        )

        val IGNORE_KEY_PARTS = listOf(
            "poster", "thumb", "image", "cover", "banner", "logo", "preview", "sprite", "storyboard", "icon"
        )
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private inline fun debug(message: () -> String) {
        if (DEBUG) android.util.Log.d("DonghuaLife", message())
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

    // Versión no suspendida de attempt() para usar dentro de secuencias y funciones comunes.
    private inline fun <T> attempt0(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
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

    private fun anchorPath(anchor: Element): String =
        fixUrl(anchor.attr("href").trim().substringBefore("#")).substringBefore("?")

    /** El payload de Next.js trae el JSON con las comillas escapadas; se normaliza antes de buscar. */
    private fun unescapePayload(text: String): String =
        text.replace("\\\"", "\"").replace("\\u0026", "&").replace("\\/", "/")

    /** Recolecta links sin repetir URLs ni trabajo ya hecho; seguro para uso paralelo. */
    private class LinkSink(private val callback: (ExtractorLink) -> Unit) {
        private val seenUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val claimed = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
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
            val path = anchorPath(anchor)
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
            val path = anchorPath(anchor)
            if (!WATCH_HREF.containsMatchIn(path) || !seen.add(path)) return@mapNotNull null

            val title = cardTitle(anchor)
                ?: EP_IN_CARD.find(anchor.text())?.groupValues?.getOrNull(1)?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val episode = SLUG_TAIL_NUMBER.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val poster = anchor.firstPoster()

            newAnimeSearchResponse(title, path, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, episode)
            }
        }
    }

    /**
     * Banner de la portada. El HTML solo trae la primera diapositiva (el resto rota por JS),
     * así que se completa con "Te recomendamos", que es de donde salen las demás.
     */
    private fun parseFeatured(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()

        val slides = doc.select("a[href*='/series/']")
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

        val recommended = sectionRoot(doc, "Te recomendamos", "/series/")
            ?.let { parseSeriesCards(it) }
            .orEmpty()

        return (slides + recommended).distinctBy { it.url }
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

        prefetchIndex()

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

    private val prefetchStarted = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)

    // Un solo recorrido del catálogo a la vez; los demás esperan su resultado (solo stdlib de Kotlin).
    private val indexLock = Any()
    private var indexBuilding = false
    private val indexWaiters = ArrayList<Continuation<List<SearchResponse>>>()

    /** Lanza una corrutina "fire and forget" sin depender de kotlinx.coroutines. */
    private fun launchBackground(block: suspend () -> Unit) {
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {}
        })
    }

    // En Android java.io.tmpdir apunta al cache de la app: el índice sobrevive entre sesiones.
    private val indexFile: File by lazy {
        File(System.getProperty("java.io.tmpdir") ?: ".", INDEX_FILE_NAME)
    }

    private fun saveIndexToDisk(index: List<SearchResponse>) {
        try {
            val array = JSONArray()
            for (item in index) {
                array.put(
                    JSONArray()
                        .put(if (item.type == TvType.AnimeMovie) 1 else 0)
                        .put(item.name)
                        .put(item.url)
                        .put(item.posterUrl ?: "")
                )
            }
            indexFile.writeText(array.toString())
        } catch (e: Exception) {
            debug { "no se pudo guardar el índice: ${e.message}" }
        }
    }

    private fun loadIndexFromDisk(): Pair<Long, List<SearchResponse>>? {
        return try {
            if (!indexFile.exists()) return null
            val array = JSONArray(indexFile.readText())
            val items = ArrayList<SearchResponse>(array.length())
            for (i in 0 until array.length()) {
                val row = array.getJSONArray(i)
                val isMovie = row.optInt(0) == 1
                val title = row.optString(1)
                val itemUrl = row.optString(2)
                val poster = row.optString(3).takeIf { it.isNotBlank() }
                if (title.isBlank() || itemUrl.isBlank()) continue
                items += if (isMovie) {
                    newMovieSearchResponse(title, itemUrl, TvType.AnimeMovie) { this.posterUrl = poster }
                } else {
                    newAnimeSearchResponse(title, itemUrl, TvType.Anime) { this.posterUrl = poster }
                }
            }
            if (items.isEmpty()) null else indexFile.lastModified() to items
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Índice completo de series y películas. Recorrer el catálogo cuesta varios segundos (OkHttp
     * solo deja 5 conexiones por host), así que:
     *  - se precarga en segundo plano apenas se abre la portada,
     *  - se guarda en disco y se reutiliza al instante en la próxima sesión,
     *  - si está vencido se devuelve igual y se refresca en segundo plano.
     */
    private suspend fun fullIndex(concurrency: Int = FOREGROUND_CONCURRENCY): List<SearchResponse> {
        val now = System.currentTimeMillis()

        indexCache?.let { (time, items) ->
            if (now - time >= INDEX_CACHE_MS) refreshIndexInBackground()
            return items
        }

        loadIndexFromDisk()?.let { cached ->
            indexCache = cached
            if (now - cached.first >= INDEX_CACHE_MS) refreshIndexInBackground()
            return cached.second
        }

        return buildIndex(force = false, concurrency = concurrency)
    }

    private fun refreshIndexInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        launchBackground {
            try {
                attempt { buildIndex(force = true, concurrency = BACKGROUND_CONCURRENCY) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun prefetchIndex() {
        if (!prefetchStarted.compareAndSet(false, true)) return
        launchBackground { attempt { fullIndex(BACKGROUND_CONCURRENCY) } }
    }

    private suspend fun buildIndex(force: Boolean, concurrency: Int): List<SearchResponse> {
        val cached = indexCache
        if (!force && cached != null && System.currentTimeMillis() - cached.first < INDEX_CACHE_MS) {
            return cached.second
        }

        val mustWait = synchronized(indexLock) {
            if (indexBuilding) true else { indexBuilding = true; false }
        }

        if (mustWait) {
            return suspendCoroutine { continuation ->
                val finished = synchronized(indexLock) {
                    if (indexBuilding) { indexWaiters.add(continuation); false } else true
                }
                if (finished) continuation.resume(indexCache?.second.orEmpty())
            }
        }

        var result: List<SearchResponse> = cached?.second.orEmpty()
        try {
            result = crawlIndex(cached?.second.orEmpty(), concurrency)
        } finally {
            val waiting = synchronized(indexLock) {
                indexBuilding = false
                ArrayList(indexWaiters).also { indexWaiters.clear() }
            }
            for (waiter in waiting) waiter.resume(result)
        }
        return result
    }

    private suspend fun crawlIndex(previous: List<SearchResponse>, concurrency: Int): List<SearchResponse> {
        val failures = AtomicInteger(0)

        suspend fun page(url: String): Document? {
            val doc = attempt { app.get(url).document } ?: attempt { app.get(url).document }
            if (doc == null) failures.incrementAndGet()
            return doc
        }

        // Primero la página 1 de cada catálogo (para saber cuántas hay) y luego el resto en tandas.
        // Las tandas limitan las conexiones simultáneas: en segundo plano se dejan libres para que
        // la portada no quede trabada esperando.
        val bases = listOf("$mainUrl/series", "$mainUrl/peliculas")
        val firsts = bases.amap { base -> base to page(base) }

        val crawled = ArrayList<SearchResponse>()
        val rest = ArrayList<String>()
        for ((base, doc) in firsts) {
            if (doc == null) continue
            crawled += parseSeriesCards(doc)
            for (number in 2..maxPage(doc).coerceAtMost(MAX_PAGES)) rest += "$base?page=$number"
        }

        for (batch in rest.chunked(concurrency.coerceAtLeast(1))) {
            val parsed = batch.amap { pageUrl -> page(pageUrl)?.let { parseSeriesCards(it) } ?: emptyList() }
            for (items in parsed) crawled += items
        }

        val index = crawled.distinctBy { it.url }

        if (index.isEmpty()) return previous

        if (failures.get() == 0) {
            indexCache = System.currentTimeMillis() to index
            saveIndexToDisk(index)
        } else {
            // Índice incompleto: se usa, pero vence pronto para reintentar y no se guarda en disco.
            indexCache = (System.currentTimeMillis() - INDEX_CACHE_MS + INDEX_PARTIAL_TTL_MS) to index
        }
        return index
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /** 1.0 igual, 0.9 prefijo, 0.85 con una o dos letras de diferencia (renegade/renegado), 0 si no se parecen. */
    private fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val shortLen = minOf(a.length, b.length)
        if (shortLen >= 3 && (a.startsWith(b) || b.startsWith(a))) return 0.9
        val maxLen = maxOf(a.length, b.length)
        val allowed = when {
            maxLen >= 9 -> 2
            maxLen >= 5 -> 1
            else -> 0
        }
        if (allowed == 0) return 0.0
        return if (levenshtein(a, b) <= allowed) 0.85 else 0.0
    }

    /**
     * La web titula en español ("El inmortal renegado") y se busca en inglés ("renegade immortal"),
     * por eso se compara palabra por palabra, sin importar el orden y tolerando letras de diferencia.
     * También se mira el slug del link, que a veces conserva el nombre en inglés.
     */
    private fun searchScore(item: SearchResponse, query: String, queryTokens: List<String>): Int {
        val title = normalize(item.name)
        if (title.isBlank()) return 0

        val slug = normalize(
            item.url.substringBefore("?").trimEnd('/').substringAfterLast('/').replace('-', ' ')
        )

        when {
            title == query || slug == query -> return 100
            title.startsWith(query) -> return 95
            title.contains(query) || slug.contains(query) -> return 90
        }

        if (queryTokens.isEmpty()) return 0
        val candidateTokens = (title.split(" ") + slug.split(" ")).filter { it.isNotBlank() }.distinct()
        if (candidateTokens.isEmpty()) return 0

        var matched = 0
        var total = 0.0
        for (token in queryTokens) {
            val best = candidateTokens.maxOf { tokenSimilarity(token, it) }
            if (best >= 0.75) {
                matched++
                total += best
            }
        }

        val needed = if (queryTokens.size <= 2) queryTokens.size else (queryTokens.size * 2 + 2) / 3
        if (matched < needed || matched == 0) return 0

        val ratio = total / queryTokens.size
        return if (matched == queryTokens.size) 60 + (ratio * 25).toInt() else 40 + (ratio * 20).toInt()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val allTokens = normalizedQuery.split(" ").filter { it.isNotBlank() }
        val tokens = allTokens.filter { it !in STOPWORDS }.ifEmpty { allTokens }

        return fullIndex()
            .mapNotNull { response ->
                val score = searchScore(response, normalizedQuery, tokens)
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
            .map { anchorPath(it) }
            .firstOrNull { CARD_HREF.containsMatchIn(it) }
            ?.trimEnd('/')
    }

    private class EpisodeRaw(val url: String, val number: Int, val vip: Boolean)

    private class SeasonBucket(val index: Int, val label: String, val episodes: List<EpisodeRaw>)

    private fun isNavigationText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("ver último") ||
            lower.contains("ver ultimo") ||
            lower.contains("siguiente") ||
            lower.contains("anterior") ||
            lower.contains("volver")
    }

    private fun episodeNumber(text: String, path: String): Int? =
        EP_LEADING_NUMBER.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: EP_TEXT.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: SLUG_TAIL_NUMBER.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Capítulos de la lista visible. El número sale del texto del link, NO del slug: los slugs de
     * películas/especiales terminan en números que no son temporada ("...-peli-ep-151--152-1").
     * Se descartan "Ver Último", "Siguiente", los links de pestañas (?s=N), etc.
     */
    private fun harvestEpisodes(root: Element): List<EpisodeRaw> {
        val anchors = root.select("a[href*='/watch/']").filter { anchor ->
            WATCH_HREF.containsMatchIn(anchorPath(anchor)) &&
                !anchor.attr("href").contains("?s=") &&
                !isNavigationText(anchor.text())
        }

        val chosen = anchors.filter { EP_TEXT_START.containsMatchIn(it.text()) }.ifEmpty { anchors }

        val result = LinkedHashMap<String, EpisodeRaw>()
        var position = 0
        for (anchor in chosen) {
            val path = anchorPath(anchor)
            if (result.containsKey(path)) continue
            position++

            val text = anchor.text().trim()
            val number = episodeNumber(text, path) ?: position
            result[path] = EpisodeRaw(path, number, text.contains("VIP"))
        }

        return result.values.toList()
    }

    /** Etiquetas de las pestañas de temporada que muestra la ficha ("Temporada 1", "Especial", "Peli Ep 151- 152"). */
    private fun seasonLabelsFromPage(document: Document, title: String): List<String> {
        val heading = document.getElementsContainingOwnText("Temporadas")
            .firstOrNull { it.ownText().trim().equals("Temporadas", ignoreCase = true) }
            ?: return emptyList()

        for (parent in heading.parents()) {
            val labels = parent.select("img[alt]")
                .map { it.attr("alt").trim() }
                .filter {
                    it.isNotBlank() &&
                        !it.equals(title, ignoreCase = true) &&
                        !it.equals("DonghuaLife", ignoreCase = true)
                }
            if (labels.isNotEmpty()) return labels.distinct()
        }
        return emptyList()
    }

    /**
     * URL del capítulo reproducible de una película. Orden: links visibles → cualquier /watch/ del
     * HTML o del payload de Next.js que empiece con el slug de la película → probar slugs típicos.
     */
    private suspend fun findMovieWatchUrl(document: Document, movieUrl: String): String? {
        harvestEpisodes(document).firstOrNull()?.let { return it.url }

        document.select("a[href*='/watch/']")
            .map { anchorPath(it) }
            .firstOrNull { WATCH_HREF.containsMatchIn(it) }
            ?.let { return it.trimEnd('/') }

        val slug = movieUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null

        val fromPayload = WATCH_SLUG.findAll(unescapePayload(document.html()))
            .map { it.groupValues[1] }
            .distinct()
            .firstOrNull { it.equals(slug, ignoreCase = true) || it.startsWith("$slug-", ignoreCase = true) }
        if (fromPayload != null) return "$mainUrl/watch/$fromPayload"

        for (candidate in listOf("$mainUrl/watch/$slug-1", "$mainUrl/watch/$slug")) {
            val response = attempt {
                app.get(
                    candidate,
                    headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "$mainUrl/"),
                    timeout = SOURCE_TIMEOUT_S
                )
            } ?: continue
            if (response.code in 200..299 && TOKEN_FIELD.containsMatchIn(unescapePayload(response.text))) {
                return candidate
            }
        }
        return null
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

        // El estado se busca junto al título, no en toda la página.
        val h1: Element? = document.selectFirst("h1")
        val h1Parent: Element? = h1?.parent()
        val h1Grand: Element? = h1Parent?.parent()
        val statusScope: String = (h1Grand ?: h1Parent)?.text() ?: ""
        val status = when {
            statusScope.contains("Finalizad", ignoreCase = true) -> ShowStatus.Completed
            statusScope.contains("Emisi", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        // ── Películas ──
        // Se resuelven antes y sin pedir temporadas. Si la ficha no trae un <a href="/watch/...">
        // (el botón puede ir en el payload de Next.js), se busca por otras vías; si aun así no se
        // encuentra, se entrega la propia URL de la película y loadLinks vuelve a intentarlo.
        if (MOVIE_HREF.containsMatchIn(url.substringBefore("?"))) {
            // La ficha de la película ya trae el reproductor y sus fuentes (con token) embebidos:
            // loadLinks lee la propia URL. Solo si no hay fuentes se busca un /watch/ aparte.
            val hasSources = TOKEN_FIELD.containsMatchIn(unescapePayload(document.html()))
            val watch = if (hasSources) url else (findMovieWatchUrl(document, url) ?: url)
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, watch) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        // ── Temporadas ──
        // La ficha solo trae la lista de la pestaña activa. Las demás se piden en paralelo a través
        // de la página de un capítulo (?s=N), donde N es la posición de la pestaña (no el número de
        // temporada: "Especial" y "Peli" ocupan lugar).
        val heading = document.select("h2, h3, h4")
            .firstOrNull { SEASON_HEADING.containsMatchIn(it.text().trim()) }

        val listRoot: Element = heading?.parents()?.firstOrNull { parent ->
            parent.select("a[href*='/watch/']").any { EP_TEXT_START.containsMatchIn(it.text()) }
        } ?: document

        val activeLabel = heading
            ?.let { SEASON_HEADING.find(it.text().trim())?.groupValues?.getOrNull(1)?.trim() }

        val pageLabels = seasonLabelsFromPage(document, title)
        val declaredCount = document.getElementsContainingOwnText("Temporada")
            .mapNotNull { SEASON_COUNT.matchEntire(it.ownText().trim())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull() ?: 0

        val seasonCount = maxOf(pageLabels.size, declaredCount, 1)
        val activeIndex = pageLabels.indexOfFirst { it.equals(activeLabel, ignoreCase = true) }
            .let { if (it >= 0) it + 1 else 1 }

        val activeEpisodes = harvestEpisodes(listRoot)
        val anchorEpisode = activeEpisodes.firstOrNull()?.url

        val tabLabels = ConcurrentHashMap<Int, String>()

        val fetched: Map<Int, List<EpisodeRaw>> =
            if (seasonCount > 1 && anchorEpisode != null) {
                (1..seasonCount).filter { it != activeIndex }.toList()
                    .amap { index ->
                        val seasonDocument = attempt { app.get("$anchorEpisode?s=$index").document }
                        if (seasonDocument == null) {
                            index to emptyList<EpisodeRaw>()
                        } else {
                            for (tab in seasonDocument.select("a[href*='?s=']")) {
                                val number = SEASON_PARAM.find(tab.attr("href"))
                                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                                val label = tab.text().trim()
                                if (label.isNotBlank()) tabLabels[number] = label
                            }
                            index to harvestEpisodes(seasonDocument)
                        }
                    }
                    .toMap()
            } else {
                emptyMap()
            }

        // Si la web ignora ?s=N y devuelve siempre la misma lista, esa pestaña se descarta
        // en vez de duplicar capítulos.
        val claimed = HashSet<String>()
        val buckets = ArrayList<SeasonBucket>()
        for (index in 1..seasonCount) {
            val raw = if (index == activeIndex) activeEpisodes else fetched[index].orEmpty()
            val fresh = raw.filter { claimed.add(it.url) }
            if (fresh.isEmpty()) continue

            val label = tabLabels[index]
                ?: pageLabels.getOrNull(index - 1)
                ?: activeLabel.takeIf { index == activeIndex }
                ?: "Temporada $index"

            buckets += SeasonBucket(index, label, fresh)
        }

        val episodes = buckets.flatMap { bucket ->
            val numbered = SEASON_LABEL.matches(bucket.label.trim())
            val single = bucket.episodes.size == 1

            bucket.episodes
                .sortedBy { it.number }
                .map { item ->
                    newEpisode(item.url) {
                        this.name = if (!numbered && single) {
                            bucket.label
                        } else {
                            "Capítulo ${item.number}" + if (item.vip) " · VIP" else ""
                        }
                        this.season = bucket.index
                        this.episode = item.number
                    }
                }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            if (status != null) this.showStatus = status
            if (buckets.size > 1) {
                this.seasonNames = buckets.map { SeasonData(season = it.index, name = it.label) }
            }
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ───────────────────────── Links ─────────────────────────

    private class PlayerSource(
        val id: String,
        val label: String,
        val token: String,
        val provider: String,
        val priority: Int,
        val subtitles: List<Pair<String, String>>,
        // UUIDs que trae la fuente (id, etc.): candidatos para /api/subtitles/{uuid}.
        val uuids: List<String>
    )

    /** Devuelve el objeto JSON { ... } que contiene la posición dada (aguanta objetos anidados). */
    private fun enclosingObject(text: String, position: Int): String? {
        var depth = 0
        var start = -1
        var i = position
        while (i >= 0 && position - i < 20_000) {
            val c = text[i]
            if (c == '}') {
                depth++
            } else if (c == '{') {
                if (depth == 0) {
                    start = i
                    break
                }
                depth--
            }
            i--
        }
        if (start < 0) return null
        return balanced(text, start, 60_000)
    }

    /** Texto balanceado desde `start` (que debe ser '{' o '[') hasta su cierre. */
    private fun balanced(text: String, start: Int, limit: Int): String? {
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var i = start
        val end = minOf(text.length, start + limit)
        while (i < end) {
            val c = text[i]
            if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    /**
     * La página del capítulo trae las fuentes dentro del payload de Next.js, como objetos con
     * "label", "token", "provider", "isWorking", "priority" (y a veces subtítulos anidados).
     * La URL real no está en la página: se pide aparte a /api/player/source con el token.
     */
    private fun parseSources(html: String): List<PlayerSource> {
        val text = unescapePayload(html)
        val result = ArrayList<PlayerSource>()
        val seen = HashSet<String>()

        fun add(json: JSONObject) {
            val token = json.optString("token").trim()
            if (token.isBlank() || !seen.add(token)) return
            if (!json.optBoolean("isWorking", true)) return

            val label = json.optString("label").ifBlank { json.optString("name") }.trim()
            val provider = json.optString("provider").trim()
            if (label.isBlank() && provider.isBlank()) return

            val subtitles = ArrayList<Pair<String, String>>()
            collectSubtitleKeys(json, subtitles)

            // UUIDs a cualquier profundidad (los subtítulos pueden venir anidados en la fuente).
            val uuids = LinkedHashSet<String>()
            collectUuids(json, uuids)

            result += PlayerSource(
                uuids = uuids.toList(),
                id = json.optString("id"),
                label = label.ifBlank { provider.ifBlank { "Servidor" } },
                token = token,
                provider = provider,
                priority = json.optInt("priority", 99),
                subtitles = subtitles
            )
        }

        for (match in TOKEN_FIELD.findAll(text)) {
            val objectText = enclosingObject(text, match.range.first) ?: continue
            val json = attempt0 { JSONObject(objectText) } ?: continue
            add(json)
        }

        // Respaldo: objetos planos sin anidar.
        if (result.isEmpty()) {
            for (match in SOURCE_OBJECT.findAll(text)) {
                val json = attempt0 { JSONObject(match.value) } ?: continue
                add(json)
            }
        }

        return result.sortedBy { it.priority }
    }

    private fun collectUuids(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "token") continue
                    collectUuids(node.opt(key), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectUuids(node.opt(i), out)
            is String -> if (UUID_RE.matches(node)) out.add(node)
        }
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
            app.get(
                data,
                headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "$mainUrl/"),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return false

        val pageText = page.text
        val sources = parseSources(pageText)
        debug { "loadLinks data=$data fuentes=${sources.map { "${it.label}/${it.provider}/ids=${it.uuids}" }}" }
        debug { "fuentes encontradas: ${sources.map { "${it.label}/${it.provider}" }}" }

        // Subtítulos que vengan en el payload de la página (valen para todas las fuentes).
        guarded("subtítulos de la página") {
            emitSubtitles(harvestSubtitles(pageText), subtitleCallback, sink)
        }

        val cookies = page.cookies.entries
            .joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }

        // Todas las fuentes se resuelven en paralelo; si una falla, las demás siguen.
        val extraUuids = if (sources.any { it.provider.equals("r2", true) || it.label.equals("r2", true) }) {
            fetchR2SubtitleIds(pageText, data, cookies)
        } else {
            emptyList()
        }

        sources.amap { source ->
            guarded(source.label) {
                resolveSource(source, data, cookies, subtitleCallback, sink, extraUuids)
            }
        }

        debug { "loadLinks final linkCount=${sink.total}" }
        return sink.total > 0
    }

    private suspend fun emitSubtitles(
        subtitles: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        for ((label, subtitleUrl) in subtitles) {
            if (sink.claim("sub:$subtitleUrl")) {
                debug { "subtítulo $label -> $subtitleUrl" }
                subtitleCallback(newSubtitleFile(label, subtitleUrl))
            }
        }
    }

    /**
     * Consulta la lista oficial de pistas del episodio. Los UUID se obtienen de la API,
     * no se adivinan buscando UUID aleatorios dentro del HTML.
     */
    private suspend fun fetchR2SubtitleIds(
        pageText: String,
        pageUrl: String,
        cookies: String
    ): List<String> {
        val text = unescapePayload(pageText)
        val episodeIdPattern = Regex(
            "\"episodeId\"\\s*:\\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\""
        )
        val episodeId = episodeIdPattern.find(text)?.groupValues?.getOrNull(1)
        if (episodeId.isNullOrBlank()) {
            debug { "R2 subtítulos: no se encontró episodeId en el HTML" }
            return emptyList()
        }

        val headers = mutableMapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to pageUrl,
            "Accept" to "application/json"
        )
        if (cookies.isNotBlank()) headers["Cookie"] = cookies

        val response = attempt {
            app.get(
                "$mainUrl/api/subtitles?episodeId=$episodeId",
                headers = headers,
                timeout = HLS_TIMEOUT_S
            )
        } ?: return emptyList()

        if (response.code !in 200..299) {
            debug { "R2 subtítulos episodio=$episodeId -> HTTP ${response.code}" }
            return emptyList()
        }

        val json = attempt0 { JSONObject(response.text) } ?: return emptyList()
        val tracks = json.optJSONArray("subtitles") ?: return emptyList()
        val ids = LinkedHashSet<String>()

        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val id = track.optString("id").trim()
            if (UUID_RE.matches(id)) ids.add(id)
        }

        debug { "R2 subtítulos episodio=$episodeId: pistas=${ids.size} ids=$ids" }
        return ids.toList()
    }

    /** Servidor local para entregar subtítulos al reproductor. */
    private object LocalTextServer {
        private val files = ConcurrentHashMap<String, ByteArray>()

        @Volatile
        private var socket: java.net.ServerSocket? = null

        @Synchronized
        private fun ensureStarted(): java.net.ServerSocket? {
            socket?.let { if (!it.isClosed) return it }
            return try {
                val created = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                socket = created
                val acceptor = Thread {
                    while (!created.isClosed) {
                        try {
                            val client = created.accept()
                            Thread { serve(client) }.apply { isDaemon = true }.start()
                        } catch (e: Exception) {
                            if (created.isClosed) break
                        }
                    }
                }
                acceptor.isDaemon = true
                acceptor.start()
                created
            } catch (e: Exception) {
                null
            }
        }

        private fun serve(client: java.net.Socket) {
            try {
                client.soTimeout = 10_000
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val name = (parts.getOrNull(1) ?: "/").substringBefore("?").trimStart('/')
                val body = files[name]
                val out = client.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.ISO_8859_1))
                    if (method != "HEAD") out.write(body)
                }
                out.flush()
            } catch (e: Exception) {
                // conexión cortada por el reproductor: nada que hacer
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {
                }
            }
        }

        /** Publica el texto con el nombre dado (la extensión indica el formato) y devuelve su URL. */
        fun publish(name: String, content: String): String? {
            val server = ensureStarted() ?: return null
            files[name] = content.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)
            return "http://127.0.0.1:${server.localPort}/$name"
        }
    }

    private fun looksLikeSubtitleText(body: String): Boolean {
        val text = body.trimStart()
        return text.startsWith("WEBVTT") || text.contains("-->") || text.startsWith("[Script Info]")
    }

    // La URL del endpoint no tiene extensión: se añade como fragmento (no viaja en la petición)
    // para que el reproductor sepa en qué formato interpretarla.
    private fun subtitleFormatSuffix(body: String): String {
        val head = body.trimStart().take(200)
        return when {
            head.startsWith("WEBVTT") -> ".vtt"
            head.contains("[Script Info]") -> ".ass"
            else -> ".srt"
        }
    }

    /**
     * GET /api/subtitles/{uuid}. Acepta que la respuesta sea el propio archivo de subtítulos o un
     * JSON (lista de pistas con url, o con ids que a su vez se piden a este mismo endpoint).
     */
    private suspend fun fetchSubtitlesById(
        id: String,
        pageUrl: String,
        cookies: String,
        label: String,
        depth: Int = 0
    ): List<Pair<String, String>> {
        val apiUrl = "$mainUrl/api/subtitles/$id"
        val headers = mutableMapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to pageUrl,
            "Accept" to "*/*"
        )
        if (cookies.isNotBlank()) headers["Cookie"] = cookies

        val response = attempt { app.get(apiUrl, headers = headers, timeout = HLS_TIMEOUT_S) }
            ?: return emptyList()
        debug { "subtítulos $id -> HTTP ${response.code}: ${response.text.take(800)}" }
        if (response.code !in 200..299) return emptyList()

        val body = response.text
        val trimmed = body.trim()

        // Formato real de la API: {"success":true,"label":"Español (Estilos)","language":"es","content":"[Script Info]..."}
        if (trimmed.startsWith("{")) {
            val json = attempt0 { JSONObject(trimmed) }
            val content = json?.optString("content").orEmpty()
            if (json != null && content.isNotBlank()) {
                val trackLabel = json.optString("label").takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("language").takeIf { it.isNotBlank() && it != "null" }?.let { langLabel(it) }
                    ?: label
                val local = LocalTextServer.publish(id + subtitleFormatSuffix(content), content)
                debug { "subtítulo '$trackLabel' (${content.length} chars) servido en $local" }
                return if (local != null) listOf(trackLabel to local) else emptyList()
            }
        }

        if (looksLikeSubtitleText(body)) {
            return listOf(label to apiUrl + "#" + subtitleFormatSuffix(body))
        }

        val node: Any? = attempt0 {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> null
            }
        } ?: return emptyList()

        val found = ArrayList<Pair<String, String>>()
        collectSubtitles(node, found, null, apiUrl)
        found.removeAll { it.second.startsWith("token:") }
        if (found.isNotEmpty() || depth >= 1) return found.distinctBy { it.second }

        // Pistas que solo traen un id: se piden una por una.
        val pending = ArrayList<Pair<String, String>>()
        fun walk(n: Any?) {
            when (n) {
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
                is JSONObject -> {
                    val trackId = n.optString("id")
                    if (UUID_RE.matches(trackId)) {
                        val trackLabel = SUB_LABEL_KEYS
                            .map { n.optString(it).trim() }
                            .firstOrNull { it.isNotBlank() && it != "null" }
                            ?.let { langLabel(it) } ?: label
                        pending += trackLabel to trackId
                    } else {
                        val keys = n.keys()
                        while (keys.hasNext()) walk(n.opt(keys.next()))
                    }
                }
            }
        }
        walk(node)

        val result = ArrayList<Pair<String, String>>()
        for ((trackLabel, trackId) in pending.take(12)) {
            result += fetchSubtitlesById(trackId, pageUrl, cookies, trackLabel, depth + 1)
        }
        return result.distinctBy { it.second }
    }

    /** POST a /api/player/source con el token de una fuente (o de un subtítulo). Devuelve el cuerpo si fue 2xx. */
    private suspend fun postSource(token: String, pageUrl: String, cookies: String, tag: String): String? {
        val headers = mutableMapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to pageUrl,
            "Origin" to mainUrl,
            "Content-Type" to "application/json",
            "Accept" to "*/*"
        )
        if (cookies.isNotBlank()) headers["Cookie"] = cookies

        val response = attempt {
            app.post(
                "$mainUrl/api/player/source",
                headers = headers,
                json = JsonAsString(JSONObject().put("token", token).toString()),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return null

        debug { "$tag -> HTTP ${response.code}: ${response.text.take(1500)}" }
        if (response.code !in 200..299) return null
        return response.text.trim()
    }

    private suspend fun resolveSource(
        source: PlayerSource,
        pageUrl: String,
        cookies: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink,
        extraUuids: List<String> = emptyList()
    ) {
        val body = postSource(source.token, pageUrl, cookies, source.label) ?: return

        val urls = ArrayList<String>()
        val subtitles = ArrayList<Pair<String, String>>(source.subtitles)
        val parsed: Any? = attempt0 {
            when {
                body.startsWith("{") -> JSONObject(body)
                body.startsWith("[") -> JSONArray(body)
                else -> null
            }
        }

        var mediaBase: String? = null

        if (parsed != null) {
            // 1.ª pasada: ver cuál es la URL del video. 2.ª: resolver subtítulos relativos contra ella.
            val jsonSubtitles = ArrayList<Pair<String, String>>()
            collectFromJson(parsed, urls, jsonSubtitles)
            mediaBase = urls.map { normalizeUrl(it) }.firstOrNull { it.startsWith("http") }
            if (mediaBase != null) {
                jsonSubtitles.clear()
                collectFromJson(parsed, ArrayList(), jsonSubtitles, mediaBase)
            }
            subtitles += jsonSubtitles
        } else if (body.startsWith("http") || body.startsWith("//")) {
            urls.add(body)
        } else {
            IFRAME_SRC.find(body)?.groupValues?.getOrNull(1)?.let { urls.add(it) }
        }

        subtitles += harvestSubtitles(body, mediaBase ?: mainUrl)

        // Subtítulos que la API entrega como objetos con su propio token: se piden aparte.
        val resolvedSubtitles = subtitles.distinctBy { it.second }.mapNotNull { (label, subtitleUrl) ->
            if (!subtitleUrl.startsWith("token:")) return@mapNotNull label to subtitleUrl
            val subBody = postSource(subtitleUrl.removePrefix("token:"), pageUrl, cookies, "sub $label")
                ?: return@mapNotNull null
            val direct = harvestSubtitles(subBody, mediaBase ?: mainUrl).firstOrNull()?.second
                ?: subBody.takeIf { it.startsWith("http") }
                ?: attempt0 { JSONObject(subBody) }?.let { json ->
                    val found = ArrayList<String>()
                    collectFromJson(json, found, ArrayList(), mediaBase ?: mainUrl)
                    found.firstOrNull()
                }
            direct?.let { label to it }
        }
        val isR2 = source.provider.equals("r2", ignoreCase = true) ||
            source.label.equals("r2", ignoreCase = true)

        var finalSubtitles = resolvedSubtitles
        if (isR2 && finalSubtitles.isEmpty()) {
            // R2 (video directo) pide sus subtítulos a /api/subtitles/{uuid}.
            val candidates = (source.uuids + extraUuids).distinct()
            debug { "R2 ${source.label}: probando ${candidates.size} uuids de subtítulos" }
            var index = 0
            while (index < candidates.size && finalSubtitles.isEmpty()) {
                val batch = candidates.subList(index, minOf(candidates.size, index + 5))
                val results = batch.amap { candidate ->
                    candidate to fetchSubtitlesById(candidate, pageUrl, cookies, "Español")
                }
                val hits = results.filter { it.second.isNotEmpty() }
                if (hits.isNotEmpty()) {
                    debug { "SUBTÍTULOS ENCONTRADOS uuids=${hits.map { it.first }}" }
                    finalSubtitles = hits.flatMap { it.second }.distinctBy { it.second }
                }
                index += 5
            }
            if (finalSubtitles.isEmpty()) debug { "R2: ningún uuid de la página devolvió subtítulos" }
        }
        emitSubtitles(finalSubtitles, subtitleCallback, sink)

        for (candidate in urls.map { normalizeUrl(it) }.distinct()) {
            emitUrl(candidate, source, pageUrl, sink, subtitleCallback)
        }
    }

    // ───────────────────────── JSON y subtítulos ─────────────────────────

    private fun isSubtitleKey(lowerKey: String): Boolean =
        lowerKey == "subs" ||
            lowerKey == "sub" ||
            lowerKey == "cc" ||
            lowerKey.startsWith("subt") ||
            lowerKey == "vtt" ||
            lowerKey == "srt" ||
            lowerKey.contains("subtitle") ||
            lowerKey.contains("caption") ||
            lowerKey.endsWith("tracks") ||
            lowerKey == "track"

    private fun isIgnoredKey(lowerKey: String): Boolean =
        IGNORE_KEY_PARTS.any { lowerKey.contains(it) }

    private fun langLabel(code: String): String {
        val value = code.trim()
        return LANG_NAMES[value.lowercase()] ?: value.replaceFirstChar { it.uppercase() }
    }

    private fun guessSubtitleLabel(subtitleUrl: String): String {
        val file = subtitleUrl.substringBefore("?").substringAfterLast('/').lowercase()
        val code = FILE_LANG.find(file)?.groupValues?.getOrNull(1)
        return if (code != null) langLabel(code) else "Español"
    }

    /** `base` es la URL del video: las rutas relativas de los subtítulos se resuelven contra ella. */
    private fun subtitleUrlOf(raw: String, base: String = mainUrl): String? {
        val value = raw.trim().replace("\\/", "/")
        if (value.isBlank() || value.startsWith("data:")) return null
        if (SUB_NOISE.containsMatchIn(value.lowercase())) return null

        return when {
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> base.substringBefore("://", "https") + "://" + hostOf(base) + value
            SUB_EXT.containsMatchIn(value) -> resolveRelative(base, value)
            else -> null
        }
    }

    /** Subtítulos en cualquiera de las formas habituales: lista de objetos, mapa idioma→url o texto suelto. */
    private fun collectSubtitles(
        node: Any?,
        out: MutableList<Pair<String, String>>,
        hint: String? = null,
        base: String = mainUrl
    ) {
        when (node) {
            is JSONArray -> {
                for (index in 0 until node.length()) collectSubtitles(node.opt(index), out, hint, base)
            }

            is JSONObject -> {
                val subtitleUrl = SUB_URL_KEYS
                    .map { node.optString(it).trim() }
                    .firstOrNull { it.isNotBlank() && it != "null" }

                if (subtitleUrl != null) {
                    val full = subtitleUrlOf(subtitleUrl, base) ?: return
                    val label = SUB_LABEL_KEYS
                        .map { node.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() && it != "null" }
                        ?.let { langLabel(it) }
                        ?: hint
                        ?: guessSubtitleLabel(full)
                    out.add(label to full)
                } else if (node.optString("token").isNotBlank()) {
                    val label = SUB_LABEL_KEYS
                        .map { node.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() && it != "null" }
                        ?.let { langLabel(it) }
                        ?: hint
                        ?: "Español"
                    out.add(label to "token:" + node.optString("token").trim())
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = node.opt(key)
                        if (value is String) {
                            subtitleUrlOf(value, base)?.let { out.add(langLabel(key) to it) }
                        } else if (value is JSONArray || value is JSONObject) {
                            collectSubtitles(value, out, langLabel(key), base)
                        }
                    }
                }
            }

            is String -> {
                val full = subtitleUrlOf(node, base) ?: return
                out.add((hint ?: guessSubtitleLabel(full)) to full)
            }
        }
    }

    /** Recorre un JSON buscando claves de subtítulos (subtitles, tracks, captions...). */
    private fun collectSubtitleKeys(
        node: Any?,
        out: MutableList<Pair<String, String>>,
        base: String = mainUrl
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (isSubtitleKey(key.lowercase())) {
                        collectSubtitles(value, out, null, base)
                    } else {
                        collectSubtitleKeys(value, out, base)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) collectSubtitleKeys(node.opt(index), out, base)
            }
        }
    }

    /**
     * Busca subtítulos en un texto (respuesta de la API o payload de la página): primero las
     * claves habituales; si no hay ninguna, cualquier .vtt/.srt/.ass que aparezca.
     */
    private fun harvestSubtitles(rawText: String, base: String = mainUrl): List<Pair<String, String>> {
        val text = unescapePayload(rawText)
        val out = ArrayList<Pair<String, String>>()

        for (match in SUB_ARRAY_KEY.findAll(text)) {
            val chunk = balanced(text, match.range.last, 60_000) ?: continue
            val node: Any? = attempt0 {
                if (chunk.startsWith("[")) JSONArray(chunk) else JSONObject(chunk)
            }
            if (node != null) collectSubtitles(node, out, null, base)
        }

        if (out.isEmpty()) {
            for (match in SUB_TEXT_URL.findAll(text)) {
                val full = subtitleUrlOf(match.value, base) ?: continue
                out.add(guessSubtitleLabel(full) to full)
            }
        }

        return out.distinctBy { it.second }
    }

    private fun collectFromJson(
        node: Any?,
        urls: MutableList<String>,
        subtitles: MutableList<Pair<String, String>>,
        base: String = mainUrl
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    val lowerKey = key.lowercase()

                    when {
                        isSubtitleKey(lowerKey) -> collectSubtitles(value, subtitles, null, base)
                        isIgnoredKey(lowerKey) -> Unit
                        else -> collectFromJson(value, urls, subtitles, base)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectFromJson(node.opt(index), urls, subtitles, base)
                }
            }

            is String -> {
                val value = node.trim()
                when {
                    value.startsWith("http") || value.startsWith("//") -> {
                        val full = normalizeUrl(value)
                        when {
                            SUB_EXT.containsMatchIn(full) ->
                                subtitleUrlOf(full, base)?.let { subtitles.add(guessSubtitleLabel(it) to it) }

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

    // ───────────────────────── Emisión de links ─────────────────────────

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore("/").substringBefore("?").lowercase()

    private suspend fun emitUrl(
        url: String,
        source: PlayerSource,
        pageUrl: String,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val lower = url.lowercase()
        val host = hostOf(url)
        val provider = source.provider.lowercase()
        val display = displayName(source.label)
        val isOwnVideo = provider == "r2" || host.endsWith("donghualife.com")
        debug { "emitUrl [${source.label}/${source.provider}] host=$host url=$url" }

        when {
            lower.contains("dailymotion.com") || lower.contains("dai.ly") || provider == "dailymotion" -> {
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

            host.contains("odysee.com") || host.contains("lbry.tv") || provider == "odysee" -> {
                val links = resolveOdyseeLinks(url, display)
                if (links.isNotEmpty()) links.forEach { sink.emit(it) } else extractWithExtractors(url, pageUrl, sink)
            }

            host.contains("ok.ru") || host.contains("odnoklassniki") || provider == "okru" || provider == "ok.ru" -> {
                val link = resolveOkRu(url, display)
                if (link != null) sink.emit(link) else extractWithExtractors(url, pageUrl, sink)
            }

            host.contains("voe") || provider == "voe" -> {
                val voeUrl = attempt { resolveVoe(url) }
                if (voeUrl != null) {
                    sink.emit(
                        newExtractorLink(
                            source = name,
                            name = "$name · $display",
                            url = voeUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    extractWithExtractors(url, pageUrl, sink)
                }
            }

            lower.contains(".m3u8") -> {
                emitDirect(url, display, ExtractorLinkType.M3U8, sink)
                if (isOwnVideo) emitHlsSubtitles(url, subtitleCallback, sink)
            }

            lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ->
                emitDirect(url, display, ExtractorLinkType.VIDEO, sink)

            // R2 es el video propio de la web: si no trae extensión se trata como HLS.
            isOwnVideo -> {
                emitDirect(url, display, ExtractorLinkType.M3U8, sink)
                emitHlsSubtitles(url, subtitleCallback, sink)
            }

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

    /** Rumble, Filemoon, Streamwish y cualquier otro embed: se resuelve con los extractores de CloudStream. */
    private suspend fun extractWithExtractors(url: String, pageUrl: String, sink: LinkSink) {
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())

        loadExtractor(
            url = url,
            referer = "$mainUrl/",
            subtitleCallback = { },
            callback = { found.add(it) }
        )

        val lower = url.lowercase()
        val selected = when {
            // De Rumble solo sirven las playlists HLS; si no hay ninguna se deja lo que haya.
            lower.contains("rumble.com") ->
                found.filter { it.url.contains("rumble.com/hls-vod/") }.ifEmpty { found.toList() }

            lower.contains("filemoon") ->
                found.filter { it.type == ExtractorLinkType.M3U8 && it.url.contains("master.m3u8") }
                    .ifEmpty { found.toList() }

            else -> found.toList()
        }

        debug { "extractor $url -> ${selected.size} links (referer $pageUrl)" }
        selected.forEach { sink.emit(it) }
    }

    // ───────────────────────── Subtítulos dentro del HLS de R2 ─────────────────────────

    private fun resolveRelative(base: String, ref: String): String? {
        attempt0 { URI(base).resolve(ref).toString() }?.let { return it }
        // Respaldo manual para URLs con caracteres que URI rechaza (espacios, '|', etc.).
        return when {
            ref.startsWith("http") -> ref
            ref.startsWith("//") -> "https:$ref"
            ref.startsWith("/") -> base.substringBefore("://", "https") + "://" + hostOf(base) + ref
            else -> base.substringBefore("?").substringBeforeLast('/') + "/" + ref
        }
    }

    /** Si el master .m3u8 declara pistas #EXT-X-MEDIA:TYPE=SUBTITLES, se ofrecen como subtítulos. */
    private suspend fun emitHlsSubtitles(
        masterUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        guarded("subtítulos HLS") {
            val headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )

            val master = app.get(masterUrl, headers = headers, timeout = HLS_TIMEOUT_S).text
            debug { "master HLS (${master.length} chars): ${master.take(700)}" }
            if (!master.contains("#EXTM3U")) return@guarded

            val found = ArrayList<Pair<String, String>>()

            for (rawLine in master.lineSequence()) {
                val line = rawLine.trim()
                if (!line.startsWith("#EXT-X-MEDIA")) continue
                if (!line.contains("TYPE=SUBTITLES") && !line.contains("TYPE=CLOSED-CAPTIONS")) continue

                val uri = HLS_URI.find(line)?.groupValues?.getOrNull(1) ?: continue
                val absolute = resolveRelative(masterUrl, uri) ?: continue

                val label = HLS_NAME.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: HLS_LANG.find(line)?.groupValues?.getOrNull(1)?.let { langLabel(it) }
                    ?: "Español"

                val direct = when {
                    SUB_EXT.containsMatchIn(absolute) -> absolute
                    absolute.contains(".m3u8", ignoreCase = true) -> {
                        // Playlist de subtítulos con un único .vtt: se usa ese archivo directamente.
                        val playlist = attempt {
                            app.get(absolute, headers = headers, timeout = HLS_TIMEOUT_S).text
                        }
                        val files = playlist?.lineSequence()
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() && !it.startsWith("#") }
                            ?.toList()
                            .orEmpty()
                        debug { "playlist de subs $label: ${files.size} segmentos" }
                        if (files.size == 1) resolveRelative(absolute, files.first()) else null
                    }
                    else -> null
                }

                if (direct != null) found.add(label to direct)
            }

            debug { "subtítulos HLS de $masterUrl -> ${found.size}" }
            emitSubtitles(found.distinctBy { it.second }, subtitleCallback, sink)
        }
    }

    // ───────────────────────── Dailymotion ─────────────────────────

    private fun dailymotionVideoId(embedUrl: String): String? =
        embedUrl
            .substringAfter("video=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }
            ?: DM_PATH_ID.find(embedUrl)?.groupValues?.getOrNull(1)

    private suspend fun resolveDailymotion(videoId: String, display: String): ExtractorLink? {
        val dmReferer = "https://geo.dailymotion.com/"
        val dmHeaders = mapOf(
            "Accept" to "*/*",
            "Origin" to "https://geo.dailymotion.com",
            "Referer" to dmReferer,
            "X-Request-Origin" to "https://geo.dailymotion.com",
            "User-Agent" to BROWSER_UA
        )

        val response = attempt {
            app.get(
                "https://geo.dailymotion.com/videos/$videoId",
                headers = dmHeaders + ("Accept" to "application/json"),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return null

        val streamUrl = attempt {
            JSONObject(response.text).getJSONObject("stream").optString("url")
        }?.takeIf { it.isNotBlank() } ?: return null

        debug { "Dailymotion: manifiesto encontrado para $videoId" }

        return newExtractorLink(
            source = name,
            name = "$name · $display",
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = dmReferer
            this.quality = Qualities.Unknown.value
            this.headers = dmHeaders
        }
    }

    // ───────────────────────── Odysee / OK.ru ─────────────────────────

    private fun cleanEscapes(text: String): String =
        text.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

    private suspend fun fetchEmbed(embedUrl: String): String? {
        val response = attempt {
            app.get(
                embedUrl,
                headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to embedUrl),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return null

        if (response.code !in 200..299) return null
        return cleanEscapes(response.text)
    }

    /** Convierte la URL de Odysee (embed, /@canal/video, /$/download/...) en un lbry:// URI. */
    private fun odyseeUri(url: String): String? {
        val path = attempt0 {
            URLDecoder.decode(
                url.substringAfter("://", url).substringAfter("/", "")
                    .substringBefore("?").substringBefore("#"),
                "UTF-8"
            )
        } ?: return null

        val segments = path.split('/').filter { it.isNotBlank() }.toMutableList()
        if (segments.isEmpty()) return null

        if (segments.first() == "$") {
            segments.removeAt(0)
            val kind = segments.firstOrNull()
            if (kind == "download" && segments.size >= 3) {
                // /$/download/{nombre}/{claimId}
                return "lbry://${segments[1]}#${segments[2]}"
            }
            if (kind == "embed" || kind == "stream") segments.removeAt(0)
        }
        if (segments.isEmpty()) return null

        // Embed estándar: /$/embed/{nombre}/{claimId} (sin ':' ni '#').
        if (segments.size == 2 && !segments[0].startsWith("@") && ODYSEE_CLAIM_ID.matches(segments[1])) {
            return "lbry://${segments[0]}#${segments[1]}"
        }

        return "lbry://" + segments.joinToString("/") { it.replace(':', '#') }
    }

    /**
     * URL directa del video. La API de Odysee entrega nombre, claim y sd_hash, con los que se arma
     * el stream libre (player.odycdn.com). Es mucho más fiable que el contentUrl de la página,
     * que suele ser un redirect /$/download/ que el reproductor no sigue bien.
     */
    private suspend fun odyseeStreamUrl(url: String): String? {
        val uri = odyseeUri(url) ?: return null

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "resolve")
            .put("params", JSONObject().put("urls", JSONArray().put(uri)))
            .put("id", 1)

        debug { "odysee uri=$uri" }

        val response = attempt {
            app.post(
                "$ODYSEE_API?m=resolve",
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Content-Type" to "application/json",
                    "Origin" to ODYSEE_ORIGIN,
                    "Referer" to "$ODYSEE_ORIGIN/"
                ),
                json = JsonAsString(payload.toString()),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return null
        debug { "odysee resolve -> HTTP ${response.code}: ${response.text.take(300)}" }
        if (response.code !in 200..299) return null

        val result = attempt0 { JSONObject(response.text).optJSONObject("result") } ?: return null
        val keys = result.keys()
        while (keys.hasNext()) {
            val claim = result.optJSONObject(keys.next()) ?: continue
            val claimId = claim.optString("claim_id").takeIf { it.isNotBlank() } ?: continue
            val claimName = claim.optString("name").takeIf { it.isNotBlank() } ?: continue
            val sdHash = claim.optJSONObject("value")?.optJSONObject("source")
                ?.optString("sd_hash")?.takeIf { it.length >= 6 } ?: continue
            return "https://player.odycdn.com/api/v3/streams/free/$claimName/$claimId/${sdHash.take(6)}.mp4"
        }
        return null
    }

    /**
     * Mismo método base que SeriesDonghua: contentUrl de la página del embed con referer = embed.
     * La diferencia: acá el embed viene firmado (signature + signature_ts), y el stream de contenido
     * restringido necesita esos mismos parámetros, así que se ofrece la URL firmada y la lisa.
     */
    private suspend fun resolveOdyseeLinks(embedUrl: String, display: String): List<ExtractorLink> {
        val signature = SIG_PARAM.find(embedUrl)?.groupValues?.getOrNull(1)
        val signatureTs = SIG_TS_PARAM.find(embedUrl)?.groupValues?.getOrNull(1)

        if (signature.isNullOrBlank() || signatureTs.isNullOrBlank()) {
            debug { "Odysee: falta signature o signature_ts en el embed" }
            return emptyList()
        }

        val page = fetchEmbed(embedUrl) ?: run {
            debug { "Odysee: no se pudo obtener el embed" }
            return emptyList()
        }

        val normalized = page
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

        val contentUrl = ODYSEE_CONTENT_URL.find(normalized)
            ?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

        if (contentUrl == null) {
            debug { "Odysee: no se encontró contentUrl (${page.length} caracteres)" }
            return emptyList()
        }

        val streamParts = Regex(
            """/api/v[0-9]+/streams/free/([^/]+)/([0-9a-fA-F]{40})/""",
            RegexOption.IGNORE_CASE
        ).find(contentUrl)

        val claimName = streamParts?.groupValues?.getOrNull(1)
        val claimId = streamParts?.groupValues?.getOrNull(2)

        if (claimName.isNullOrBlank() || claimId.isNullOrBlank()) {
            debug { "Odysee: no se pudo extraer name/claim_id del contentUrl" }
            return emptyList()
        }

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "get")
            .put(
                "params",
                JSONObject()
                    .put("uri", "lbry://$claimName#$claimId")
                    .put("signature", signature)
                    .put("signature_ts", signatureTs)
            )
            .put("id", 1)

        val response = attempt {
            app.post(
                "$ODYSEE_API?m=get",
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Content-Type" to "application/json",
                    "Origin" to ODYSEE_ORIGIN,
                    "Referer" to "$ODYSEE_ORIGIN/"
                ),
                json = JsonAsString(payload.toString()),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: run {
            debug { "Odysee: error de red al solicitar streaming_url" }
            return emptyList()
        }

        debug { "Odysee get -> HTTP ${response.code}: ${response.text.take(400)}" }
        if (response.code !in 200..299) return emptyList()

        val streamingUrl = attempt0 {
            JSONObject(response.text)
                .optJSONObject("result")
                ?.optString("streaming_url")
                ?.takeIf { it.startsWith("https://secure.odycdn.com/") }
        }

        if (streamingUrl.isNullOrBlank()) {
            debug { "Odysee: la API no devolvió una streaming_url válida" }
            return emptyList()
        }

        debug { "Odysee: streaming_url firmada obtenida correctamente" }

        return listOf(
            newExtractorLink(
                source = name,
                name = "$name · $display",
                url = streamingUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                referer = "$ODYSEE_ORIGIN/"
              this.headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Origin" to ODYSEE_ORIGIN,
                    "Referer" to "$ODYSEE_ORIGIN/"
                )
                quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun resolveOkRu(embedUrl: String, display: String): ExtractorLink? {
        val page = fetchEmbed(embedUrl) ?: return null

        val hlsUrl = OK_HLS_URL.find(page)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        return newExtractorLink(
            source = name,
            name = "$name · $display",
            url = hlsUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = embedUrl
            this.quality = Qualities.Unknown.value
        }
    }

    // ───────────────────────── VOE ─────────────────────────

    private suspend fun resolveVoe(embedUrl: String): String? {
        val headers = mapOf("User-Agent" to BROWSER_UA)

        val firstResponse = app.get(embedUrl, headers = headers, timeout = SOURCE_TIMEOUT_S)
        if (!firstResponse.isSuccessful) return null

        val redirectUrl = VOE_REDIRECT.find(firstResponse.text)?.groupValues?.getOrNull(1)
        val realUrl = redirectUrl ?: embedUrl

        val pageResponse = if (realUrl != embedUrl) {
            app.get(realUrl, headers = headers, timeout = SOURCE_TIMEOUT_S)
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

        val payloadB64 = android.util.Base64.encodeToString(
            altchaPayload.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val voeCookies = pageCookies.entries
            .joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }

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

            var bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)

            bytes = bytes.map { (it.toInt() - 3).toByte() }.toByteArray()
            bytes.reverse()

            val decoded = android.util.Base64.decode(String(bytes, Charsets.UTF_8), android.util.Base64.DEFAULT)

            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            debug { "VOE decode ERROR -> ${e.message}" }
            null
        }
    }

    /** PBKDF2-HMAC-SHA256 con el Mac inicializado una sola vez por llamada. */
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
