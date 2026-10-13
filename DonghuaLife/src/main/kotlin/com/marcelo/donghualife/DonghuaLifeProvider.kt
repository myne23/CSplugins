package com.marcelo.donghualife

import android.util.Base64
import android.util.Log
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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        const val TAG = "DonghuaLife"

        // true = logs en logcat con el tag "DonghuaLife".
        const val DEBUG = true

        const val BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"

        // Cachés y límites
        const val INDEX_CACHE_MS = 30L * 60_000L
        const val INDEX_PARTIAL_TTL_MS = 2L * 60_000L
        const val HOME_CACHE_MS = 60_000L
        const val MAX_PAGES = 40
        const val FOREGROUND_CONCURRENCY = 5
        const val BACKGROUND_CONCURRENCY = 3
        const val INDEX_FILE_NAME = "donghualife_index_v1.json"

        // Tiempos máximos (segundos) para que una fuente lenta no cuelgue la carga.
        const val SOURCE_TIMEOUT_S = 20L
        const val HLS_TIMEOUT_S = 10L
        const val ALTCHA_TIMEOUT_NS = 25_000_000_000L

        // Los capítulos VIP no se pueden reproducir: se ocultan de la lista.
        const val VIP_MARK = "VIP"

        // Portada / tarjetas / capítulos
        val CARD_HREF = Regex("""/(?:series|peliculas)/[^/?#]+/?$""")
        val WATCH_HREF = Regex("""/watch/[^/?#]+/?$""")
        val MOVIE_HREF = Regex("""/peliculas/[^/?#]+/?$""")
        val WATCH_SLUG = Regex("""/watch/([A-Za-z0-9][A-Za-z0-9_-]*)""")
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

        // Búsqueda
        val DIACRITICS = Regex("""\p{M}+""")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val STOPWORDS = setOf("el", "la", "los", "las", "de", "del", "the", "of", "a", "an", "and", "y", "en")

        // Fuentes del episodio (cada una trae un campo "token")
        val TOKEN_FIELD = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
        val SOURCE_OBJECT = Regex("""\{[^{}]*"token"\s*:\s*"[^"]+"[^{}]*\}""")
        val EPISODE_ID_JSON = Regex(
            "\"episodeId\"\\s*:\\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\""
        )
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val IFRAME_SRC = Regex("""src=["']([^"']+)["']""")
        val IMAGE_EXT = Regex("""\.(?:jpe?g|png|webp|gif|svg|avif)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

        // Subtítulos: arrays/objetos bajo estas claves, o cualquier .vtt/.srt suelto como último recurso.
        val SUB_EXT = Regex("""\.(?:vtt|srt|ass|ssa)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        val SUB_NOISE = Regex("thumb|sprite|storyboard|preview")
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
        val IGNORE_KEY_PARTS = listOf(
            "poster", "thumb", "image", "cover", "banner", "logo", "preview", "sprite", "storyboard", "icon"
        )
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

        // Dailymotion / OK.ru
        val DM_PATH_ID = Regex("/video/([A-Za-z0-9]+)")
        val OK_HLS_URL = Regex("\"hlsManifestUrl\":\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        // VOE
        val HEX_CHARS = "0123456789abcdef".toCharArray()
        val VOE_REDIRECT = Regex("""window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]""")
        val VOE_CONFIG = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']""")
        val VOE_CONFIG_STRICT = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>"""
        )
        val VOE_TOKEN = Regex("""name=["']_token["'][^>]+value=["']([^"']+)["']""")
        val VOE_CHALLENGE = Regex("""<altcha-widget[^>]+challenge=["']([^"']+)["']""")
        val VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")
        val VOE_SEPARATORS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private inline fun debug(message: () -> String) {
        if (DEBUG) Log.d(TAG, message())
    }

    /** Ejecuta el bloque y devuelve null si falla (la cancelación sí se propaga). */
    private inline fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debug { "${e.javaClass.simpleName}: ${e.message}" }
            null
        }

    private fun JSONObject.firstString(keys: List<String>): String? =
        keys.asSequence().map { optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }

    private fun JSONObject.entries(): List<Pair<String, Any?>> =
        keys().asSequence().map { it to opt(it) }.toList()

    private fun JSONArray.items(): List<Any?> = (0 until length()).map { opt(it) }

    private fun siteHeaders() = mapOf("User-Agent" to BROWSER_UA, "Referer" to "$mainUrl/")

    private fun apiHeaders(
        pageUrl: String,
        cookies: String,
        accept: String = "*/*",
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> = buildMap {
        put("User-Agent", BROWSER_UA)
        put("Referer", pageUrl)
        put("Accept", accept)
        if (cookies.isNotBlank()) put("Cookie", cookies)
        putAll(extra)
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

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore("/").substringBefore("?").lowercase()

    private fun slugOf(url: String): String =
        url.substringBefore("?").trimEnd('/').substringAfterLast('/')

    private fun slugTitle(url: String): String =
        slugOf(url).split('-')
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
        select("img").firstOrNull { !it.attr("src").contains("logo", ignoreCase = true) }.imageUrl()

    private fun cardTitle(anchor: Element): String? =
        anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("DonghuaLife", ignoreCase = true) }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("h1, h2, h3, h4, h5")?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun maxPage(root: Element): Int =
        root.select("a[href*='page=']")
            .mapNotNull { PAGE_PARAM.find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0

    private fun anchorPath(anchor: Element): String =
        fixUrl(anchor.attr("href").trim().substringBefore("#")).substringBefore("?")

    /** El payload de Next.js trae el JSON con las comillas escapadas; se normaliza antes de buscar. */
    private fun unescapePayload(text: String): String =
        text.replace("\\\"", "\"").replace("\\u0026", "&").replace("\\/", "/")

    private fun langLabel(code: String): String {
        val value = code.trim()
        return LANG_NAMES[value.lowercase()] ?: value.replaceFirstChar { it.uppercase() }
    }

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

    /** Candado de corrutinas hecho solo con la stdlib de Kotlin (el proyecto no incluye kotlinx.coroutines). */
    private class SuspendLock {
        private val monitor = Any()
        private var held = false
        private val waiters = java.util.ArrayDeque<Continuation<Unit>>()

        suspend fun acquire() {
            val mustWait = synchronized(monitor) {
                if (!held) { held = true; false } else true
            }
            if (!mustWait) return
            suspendCoroutine<Unit> { continuation ->
                val granted = synchronized(monitor) {
                    if (!held) { held = true; true } else { waiters.add(continuation); false }
                }
                if (granted) continuation.resume(Unit)
            }
        }

        /** Si hay alguien esperando, el candado pasa directo a él (sigue "held"). */
        fun release() {
            val next = synchronized(monitor) {
                val waiting = waiters.poll()
                if (waiting == null) held = false
                waiting
            }
            next?.resume(Unit)
        }

        suspend inline fun <T> withLock(block: () -> T): T {
            acquire()
            try {
                return block()
            } finally {
                release()
            }
        }
    }

    private val pauseScheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }
    }

    private suspend fun pause(ms: Long) {
        if (ms <= 0) return
        suspendCoroutine<Unit> { continuation ->
            pauseScheduler.schedule(
                { continuation.resume(Unit) },
                ms,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /** Lanza una corrutina "fire and forget" sin depender de kotlinx.coroutines. */
    private fun launchBackground(block: suspend () -> Unit) {
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {}
        })
    }

    // ───────────────────────── Parseo de tarjetas ─────────────────────────

    private fun searchResponse(
        title: String,
        url: String,
        poster: String?,
        movie: Boolean = url.contains("/peliculas/")
    ): SearchResponse =
        if (movie) {
            newMovieSearchResponse(title, url, TvType.AnimeMovie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
        }

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
            if (title.isBlank()) null else searchResponse(title, info.url, info.poster)
        }
    }

    /** Tarjetas de capítulos (/watch/...), sin los VIP: al abrirlas, load() resuelve la serie. */
    private fun parseEpisodeCards(root: Element): List<SearchResponse> {
        val seen = HashSet<String>()

        return root.select("a[href*='/watch/']").mapNotNull { anchor ->
            val path = anchorPath(anchor)
            if (!WATCH_HREF.containsMatchIn(path) || !seen.add(path)) return@mapNotNull null
            if (anchor.text().contains(VIP_MARK)) return@mapNotNull null

            val title = cardTitle(anchor)
                ?: EP_IN_CARD.find(anchor.text())?.groupValues?.getOrNull(1)?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null

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
        homeCache?.let { (time, document) ->
            if (System.currentTimeMillis() - time < HOME_CACHE_MS) return document
        }
        val document = attempt { app.get(mainUrl).document } ?: return null
        homeCache = System.currentTimeMillis() to document
        return document
    }

    private suspend fun emptyHome(): HomePageResponse = newHomePageResponse(emptyList(), hasNext = false)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        prefetchIndex()
        return if (request.data.startsWith("home:")) homeSection(page, request) else catalogPage(page, request)
    }

    private suspend fun homeSection(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return emptyHome()
        val document = homeDocument() ?: return emptyHome()

        val items: List<SearchResponse> = when (request.data) {
            "home:featured" -> parseFeatured(document)
            "home:latest" ->
                sectionRoot(document, "Últimos episodios", "/watch/")?.let { parseEpisodeCards(it) }.orEmpty()
            "home:trending" ->
                sectionRoot(document, "Tendencias", "/series/")?.let { parseSeriesCards(it) }.orEmpty()
            else -> emptyList()
        }
        if (items.isEmpty()) return emptyHome()

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = request.data == "home:featured")),
            hasNext = false
        )
    }

    private suspend fun catalogPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = if (page == 1) data else data + (if (data.contains("?")) "&" else "?") + "page=$page"

        // Si una sección falla, queda vacía y el resto del catálogo sigue funcionando.
        val document = attempt { app.get(url).document } ?: return emptyHome()

        val items = parseSeriesCards(document)
        val last = maxPage(document)

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty() && (last == 0 || page < last)
        )
    }

    // ───────────────────────── Índice y búsqueda ─────────────────────────

    @Volatile
    private var indexCache: Pair<Long, List<SearchResponse>>? = null

    private val prefetchStarted = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val indexLock = SuspendLock()

    // En Android java.io.tmpdir apunta al cache de la app: el índice sobrevive entre sesiones.
    private val indexFile: File by lazy {
        File(System.getProperty("java.io.tmpdir") ?: ".", INDEX_FILE_NAME)
    }

    private fun saveIndexToDisk(index: List<SearchResponse>) {
        attempt {
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
        }
    }

    private fun loadIndexFromDisk(): Pair<Long, List<SearchResponse>>? = attempt {
        if (!indexFile.exists()) return@attempt null

        val array = JSONArray(indexFile.readText())
        val items = ArrayList<SearchResponse>(array.length())
        for (i in 0 until array.length()) {
            val row = array.getJSONArray(i)
            val title = row.optString(1)
            val itemUrl = row.optString(2)
            if (title.isBlank() || itemUrl.isBlank()) continue
            items += searchResponse(title, itemUrl, row.optString(3).takeIf { it.isNotBlank() }, row.optInt(0) == 1)
        }
        if (items.isEmpty()) null else indexFile.lastModified() to items
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

    /** Un solo recorrido del catálogo a la vez: quien espera el candado reutiliza lo que armó el anterior. */
    private suspend fun buildIndex(force: Boolean, concurrency: Int): List<SearchResponse> {
        val requestedAt = System.currentTimeMillis()
        return indexLock.withLock {
            val cached = indexCache
            // Con force solo vale lo que se armó mientras esperábamos; sin force, lo que no esté vencido.
            val usable = cached != null &&
                (if (force) cached.first >= requestedAt else System.currentTimeMillis() - cached.first < INDEX_CACHE_MS)
            if (cached != null && usable) cached.second else crawlIndex(cached?.second.orEmpty(), concurrency)
        }
    }

    private suspend fun crawlIndex(previous: List<SearchResponse>, concurrency: Int): List<SearchResponse> {
        val failures = AtomicInteger(0)

        suspend fun page(url: String): Document? {
            val doc = attempt { app.get(url).document } ?: attempt { app.get(url).document }
            if (doc == null) failures.incrementAndGet()
            return doc
        }

        // Primero la página 1 de cada catálogo (para saber cuántas hay) y luego el resto en tandas.
        // Las tandas limitan las conexiones simultáneas: en segundo plano quedan libres para que
        // la portada no se trabe esperando.
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
            batch.amap { pageUrl -> page(pageUrl)?.let { parseSeriesCards(it) }.orEmpty() }
                .forEach { crawled += it }
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
        if (minOf(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a))) return 0.9
        val allowed = when {
            maxOf(a.length, b.length) >= 9 -> 2
            maxOf(a.length, b.length) >= 5 -> 1
            else -> 0
        }
        return if (allowed > 0 && levenshtein(a, b) <= allowed) 0.85 else 0.0
    }

    /** Título, slug y palabras ya normalizados: se calculan una vez por índice, no en cada búsqueda. */
    private class SearchEntry(
        val item: SearchResponse,
        val title: String,
        val slug: String,
        val tokens: List<String>
    )

    @Volatile
    private var entryCache: Pair<List<SearchResponse>, List<SearchEntry>>? = null

    private fun entriesFor(index: List<SearchResponse>): List<SearchEntry> {
        entryCache?.let { (source, entries) -> if (source === index) return entries }
        val entries = index.map { item ->
            val title = normalize(item.name)
            val slug = normalize(slugOf(item.url).replace('-', ' '))
            SearchEntry(item, title, slug, (title.split(" ") + slug.split(" ")).filter { it.isNotBlank() }.distinct())
        }
        entryCache = index to entries
        return entries
    }

    /**
     * La web titula en español ("El inmortal renegado") y se busca en inglés ("renegade immortal"),
     * por eso se compara palabra por palabra, sin importar el orden y tolerando letras de diferencia.
     * También se mira el slug del link, que a veces conserva el nombre en inglés.
     */
    private fun searchScore(entry: SearchEntry, query: String, queryTokens: List<String>): Int {
        if (entry.title.isBlank()) return 0

        when {
            entry.title == query || entry.slug == query -> return 100
            entry.title.startsWith(query) -> return 95
            entry.title.contains(query) || entry.slug.contains(query) -> return 90
        }

        if (queryTokens.isEmpty() || entry.tokens.isEmpty()) return 0

        var matched = 0
        var total = 0.0
        for (token in queryTokens) {
            val best = entry.tokens.maxOf { tokenSimilarity(token, it) }
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

        return entriesFor(fullIndex())
            .mapNotNull { entry ->
                val score = searchScore(entry, normalizedQuery, tokens)
                if (score > 0) score to entry.item else null
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    // ───────────────────────── Detalle ─────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val clean = url.substringBefore("#")

        // Un capítulo suelto (p. ej. de "Últimos episodios"): se abre la serie completa.
        val seriesUrl = if (WATCH_HREF.containsMatchIn(clean.substringBefore("?"))) {
            resolveSeriesFromWatch(clean) ?: return null
        } else {
            clean
        }
        return loadSeries(seriesUrl)
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

    private class ShowMeta(
        val title: String,
        val poster: String?,
        val plot: String?,
        val tags: List<String>,
        val status: ShowStatus?
    )

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
     * Capítulos de la lista visible (VIP incluidos: se filtran después, porque el primero de la
     * lista sirve de ancla para pedir las otras temporadas). El número sale del texto del link,
     * NO del slug: los slugs de películas/especiales terminan en números que no son temporada
     * ("...-peli-ep-151--152-1"). Se descartan "Ver Último", "Siguiente", los links de pestañas (?s=N), etc.
     */
    private fun harvestEpisodes(root: Element): List<EpisodeRaw> {
        val anchors = root.select("a[href*='/watch/']").filter { anchor ->
            WATCH_HREF.containsMatchIn(anchorPath(anchor)) &&
                !anchor.attr("href").contains("?s=") &&
                !isNavigationText(anchor.text())
        }

        val chosen = anchors.filter { EP_TEXT_START.containsMatchIn(it.text()) }.ifEmpty { anchors }

        val result = LinkedHashMap<String, EpisodeRaw>()
        for (anchor in chosen) {
            val path = anchorPath(anchor)
            if (result.containsKey(path)) continue

            val text = anchor.text().trim()
            val number = episodeNumber(text, path) ?: (result.size + 1)
            result[path] = EpisodeRaw(path, number, text.contains(VIP_MARK))
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
        harvestEpisodes(document).firstOrNull { !it.vip }?.let { return it.url }

        document.select("a[href*='/watch/']")
            .map { anchorPath(it) }
            .firstOrNull { WATCH_HREF.containsMatchIn(it) }
            ?.let { return it.trimEnd('/') }

        val slug = slugOf(movieUrl)
        if (slug.isBlank()) return null

        val fromPayload = WATCH_SLUG.findAll(unescapePayload(document.html()))
            .map { it.groupValues[1] }
            .distinct()
            .firstOrNull { it.equals(slug, ignoreCase = true) || it.startsWith("$slug-", ignoreCase = true) }
        if (fromPayload != null) return "$mainUrl/watch/$fromPayload"

        for (candidate in listOf("$mainUrl/watch/$slug-1", "$mainUrl/watch/$slug")) {
            val response = attempt {
                app.get(candidate, headers = siteHeaders(), timeout = SOURCE_TIMEOUT_S)
            } ?: continue
            if (response.code in 200..299 && TOKEN_FIELD.containsMatchIn(unescapePayload(response.text))) {
                return candidate
            }
        }
        return null
    }

    private suspend fun loadSeries(url: String): LoadResponse? {
        val document = attempt { app.get(url).document } ?: return null
        val h1 = document.selectFirst("h1")

        val title = h1?.text()?.trim()?.takeIf { it.isNotBlank() }
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
        val statusScope = (h1?.parent()?.parent() ?: h1?.parent())?.text().orEmpty()
        val status = when {
            statusScope.contains("Finalizad", ignoreCase = true) -> ShowStatus.Completed
            statusScope.contains("Emisi", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val meta = ShowMeta(title, poster, plot, tags, status)
        return if (MOVIE_HREF.containsMatchIn(url.substringBefore("?"))) {
            loadMovie(url, document, meta)
        } else {
            loadShow(url, document, meta)
        }
    }

    /**
     * Las películas se resuelven antes y sin pedir temporadas. La ficha ya trae el reproductor y sus
     * fuentes (con token) embebidos: loadLinks lee la propia URL. Solo si no hay fuentes se busca un
     * /watch/ aparte; si tampoco aparece, se entrega la URL de la película y loadLinks reintenta.
     */
    private suspend fun loadMovie(url: String, document: Document, meta: ShowMeta): LoadResponse {
        val hasSources = TOKEN_FIELD.containsMatchIn(unescapePayload(document.html()))
        val watch = if (hasSources) url else (findMovieWatchUrl(document, url) ?: url)

        return newMovieLoadResponse(meta.title, url, TvType.AnimeMovie, watch) {
            this.posterUrl = meta.poster
            this.plot = meta.plot
            this.tags = meta.tags
        }
    }

    /**
     * La ficha solo trae la lista de la pestaña activa. Las demás se piden en paralelo a través de
     * la página de un capítulo (?s=N), donde N es la posición de la pestaña (no el número de
     * temporada: "Especial" y "Peli" ocupan lugar).
     */
    private suspend fun collectSeasons(document: Document, title: String): List<SeasonBucket> {
        val heading = document.select("h2, h3, h4")
            .firstOrNull { SEASON_HEADING.containsMatchIn(it.text().trim()) }

        val listRoot: Element = heading?.parents()?.firstOrNull { parent ->
            parent.select("a[href*='/watch/']").any { EP_TEXT_START.containsMatchIn(it.text()) }
        } ?: document

        val activeLabel = heading
            ?.let { SEASON_HEADING.find(it.text().trim())?.groupValues?.getOrNull(1)?.trim() }

        val pageLabels = seasonLabelsFromPage(document, title)
        val declaredCount = document.getElementsContainingOwnText("Temporada")
            .firstNotNullOfOrNull { SEASON_COUNT.matchEntire(it.ownText().trim())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: 0

        val seasonCount = maxOf(pageLabels.size, declaredCount, 1)
        val activeIndex = pageLabels.indexOfFirst { it.equals(activeLabel, ignoreCase = true) }
            .let { if (it >= 0) it + 1 else 1 }

        val activeEpisodes = harvestEpisodes(listRoot)
        val anchorEpisode = activeEpisodes.firstOrNull()?.url

        val tabLabels = ConcurrentHashMap<Int, String>()

        val fetched: Map<Int, List<EpisodeRaw>> =
            if (seasonCount > 1 && anchorEpisode != null) {
                (1..seasonCount).filter { it != activeIndex }
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

        // Si la web ignora ?s=N y devuelve siempre la misma lista, esa pestaña se descarta en vez de
        // duplicar capítulos. Los VIP se reclaman igual (para detectar duplicados) pero no se muestran.
        val claimed = HashSet<String>()
        val buckets = ArrayList<SeasonBucket>()
        for (index in 1..seasonCount) {
            val raw = if (index == activeIndex) activeEpisodes else fetched[index].orEmpty()
            val playable = raw.filter { claimed.add(it.url) && !it.vip }
            if (playable.isEmpty()) continue

            val label = tabLabels[index]
                ?: pageLabels.getOrNull(index - 1)
                ?: activeLabel.takeIf { index == activeIndex }
                ?: "Temporada $index"

            buckets += SeasonBucket(index, label, playable)
        }
        return buckets
    }

    private suspend fun loadShow(url: String, document: Document, meta: ShowMeta): LoadResponse {
        val buckets = collectSeasons(document, meta.title)

        val episodes = buckets.flatMap { bucket ->
            // Una pestaña con un solo capítulo que no es "Temporada N" (Especial, Peli...) usa su etiqueta.
            val useLabel = !SEASON_LABEL.matches(bucket.label.trim()) && bucket.episodes.size == 1

            bucket.episodes
                .sortedBy { it.number }
                .map { item ->
                    newEpisode(item.url) {
                        this.name = if (useLabel) bucket.label else "Capítulo ${item.number}"
                        this.season = bucket.index
                        this.episode = item.number
                    }
                }
        }

        return newAnimeLoadResponse(meta.title, url, TvType.Anime) {
            this.posterUrl = meta.poster
            this.plot = meta.plot
            this.tags = meta.tags
            if (meta.status != null) this.showStatus = meta.status
            if (buckets.size > 1) {
                this.seasonNames = buckets.map { SeasonData(season = it.index, name = it.label) }
            }
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ───────────────────────── Fuentes del episodio ─────────────────────────

    private class PlayerSource(
        val label: String,
        val token: String,
        val provider: String,
        val priority: Int,
        val subtitles: List<Pair<String, String>>,
        // UUIDs que trae la fuente: candidatos para /api/subtitles/{uuid}.
        val uuids: List<String>
    ) {
        val isR2: Boolean
            get() = provider.equals("r2", ignoreCase = true) || label.equals("r2", ignoreCase = true)
    }

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
        return if (start < 0) null else balanced(text, start, 60_000)
    }

    /** Texto balanceado desde `start` (que debe ser '{' o '[') hasta su cierre. */
    private fun balanced(text: String, start: Int, limit: Int): String? {
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        val end = minOf(text.length, start + limit)
        for (i in start until end) {
            val c = text[i]
            if (c == open) {
                depth++
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1)
            }
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
                label = label.ifBlank { provider.ifBlank { "Servidor" } },
                token = token,
                provider = provider,
                priority = json.optInt("priority", 99),
                subtitles = subtitles,
                uuids = uuids.toList()
            )
        }

        for (match in TOKEN_FIELD.findAll(text)) {
            val json = enclosingObject(text, match.range.first)?.let { attempt { JSONObject(it) } } ?: continue
            add(json)
        }

        // Respaldo: objetos planos sin anidar.
        if (result.isEmpty()) {
            for (match in SOURCE_OBJECT.findAll(text)) {
                add(attempt { JSONObject(match.value) } ?: continue)
            }
        }

        return result.sortedBy { it.priority }
    }

    private fun collectUuids(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> for ((key, value) in node.entries()) {
                if (key != "token") collectUuids(value, out)
            }
            is JSONArray -> node.items().forEach { collectUuids(it, out) }
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

    // ───────────────────────── Links ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sink = LinkSink(callback)

        val page = attempt {
            app.get(data, headers = siteHeaders(), timeout = SOURCE_TIMEOUT_S)
        } ?: return false

        val pageText = page.text
        val sources = parseSources(pageText)
        debug { "fuentes: ${sources.map { "${it.label}/${it.provider}" }}" }

        // Subtítulos que vengan en el payload de la página (valen para todas las fuentes).
        attempt { emitSubtitles(harvestSubtitles(pageText), subtitleCallback, sink) }

        val cookies = page.cookies.entries.joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }
        val extraUuids = if (sources.any { it.isR2 }) fetchR2SubtitleIds(pageText, data, cookies) else emptyList()

        // Todas las fuentes se resuelven en paralelo; si una falla, las demás siguen.
        sources.amap { source ->
            attempt { resolveSource(source, data, cookies, subtitleCallback, sink, extraUuids) }
        }

        return sink.total > 0
    }

    private suspend fun emitSubtitles(
        subtitles: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink
    ) {
        for ((label, subtitleUrl) in subtitles) {
            if (sink.claim("sub:$subtitleUrl")) subtitleCallback(newSubtitleFile(label, subtitleUrl))
        }
    }

    /** POST a /api/player/source con el token de una fuente (o de un subtítulo). Devuelve el cuerpo si fue 2xx. */
    private suspend fun postSource(token: String, pageUrl: String, cookies: String): String? {
        val response = attempt {
            app.post(
                "$mainUrl/api/player/source",
                headers = apiHeaders(
                    pageUrl,
                    cookies,
                    extra = mapOf("Origin" to mainUrl, "Content-Type" to "application/json")
                ),
                json = JsonAsString(JSONObject().put("token", token).toString()),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return null

        if (response.code !in 200..299) return null
        return response.text.trim()
    }

    private class SourcePayload(
        val urls: List<String>,
        val subtitles: List<Pair<String, String>>,
        // URL del video: las rutas relativas de los subtítulos se resuelven contra ella.
        val mediaBase: String?
    )

    /** Saca de la respuesta de /api/player/source las URLs de video y los subtítulos. */
    private fun parseSourceBody(body: String): SourcePayload {
        val urls = ArrayList<String>()
        val subtitles = ArrayList<Pair<String, String>>()
        var mediaBase: String? = null

        val parsed: Any? = attempt {
            when {
                body.startsWith("{") -> JSONObject(body)
                body.startsWith("[") -> JSONArray(body)
                else -> null
            }
        }

        if (parsed != null) {
            // 1.ª pasada: ver cuál es la URL del video. 2.ª: resolver subtítulos relativos contra ella.
            collectFromJson(parsed, urls, subtitles)
            mediaBase = urls.map { normalizeUrl(it) }.firstOrNull { it.startsWith("http") }
            if (mediaBase != null) {
                subtitles.clear()
                collectFromJson(parsed, ArrayList(), subtitles, mediaBase)
            }
        } else if (body.startsWith("http") || body.startsWith("//")) {
            urls.add(body)
        } else {
            IFRAME_SRC.find(body)?.groupValues?.getOrNull(1)?.let { urls.add(it) }
        }

        subtitles += harvestSubtitles(body, mediaBase ?: mainUrl)
        return SourcePayload(urls, subtitles, mediaBase)
    }

    private suspend fun resolveSource(
        source: PlayerSource,
        pageUrl: String,
        cookies: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: LinkSink,
        extraUuids: List<String>
    ) {
        val body = postSource(source.token, pageUrl, cookies) ?: return
        val payload = parseSourceBody(body)
        val base = payload.mediaBase ?: mainUrl

        // Subtítulos que la API entrega como objetos con su propio token: se piden aparte.
        var subtitles = (source.subtitles + payload.subtitles)
            .distinctBy { it.second }
            .mapNotNull { (label, subtitleUrl) ->
                if (!subtitleUrl.startsWith("token:")) return@mapNotNull label to subtitleUrl
                resolveTokenSubtitle(subtitleUrl.removePrefix("token:"), pageUrl, cookies, base)?.let { label to it }
            }

        // R2 (video directo) pide sus subtítulos a /api/subtitles/{uuid}.
        if (subtitles.isEmpty() && source.isR2) {
            subtitles = findR2Subtitles((source.uuids + extraUuids).distinct(), pageUrl, cookies)
        }
        emitSubtitles(subtitles, subtitleCallback, sink)

        for (candidate in payload.urls.map { normalizeUrl(it) }.distinct()) {
            emitUrl(candidate, source, pageUrl, sink, subtitleCallback)
        }
    }

    private suspend fun resolveTokenSubtitle(token: String, pageUrl: String, cookies: String, base: String): String? {
        val body = postSource(token, pageUrl, cookies) ?: return null
        return harvestSubtitles(body, base).firstOrNull()?.second
            ?: body.takeIf { it.startsWith("http") }
            ?: attempt { JSONObject(body) }?.let { json ->
                val found = ArrayList<String>()
                collectFromJson(json, found, ArrayList(), base)
                found.firstOrNull()
            }
    }

    // ───────────────────────── Subtítulos de R2 ─────────────────────────

    /**
     * Consulta la lista oficial de pistas del episodio. Los UUID se obtienen de la API,
     * no se adivinan buscando UUID aleatorios dentro del HTML.
     */
    private suspend fun fetchR2SubtitleIds(pageText: String, pageUrl: String, cookies: String): List<String> {
        val episodeId = EPISODE_ID_JSON.find(unescapePayload(pageText))?.groupValues?.getOrNull(1)
        if (episodeId.isNullOrBlank()) return emptyList()

        val response = attempt {
            app.get(
                "$mainUrl/api/subtitles?episodeId=$episodeId",
                headers = apiHeaders(pageUrl, cookies, accept = "application/json"),
                timeout = HLS_TIMEOUT_S
            )
        } ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val tracks = attempt { JSONObject(response.text) }?.optJSONArray("subtitles") ?: return emptyList()
        return tracks.items()
            .mapNotNull { (it as? JSONObject)?.optString("id")?.trim() }
            .filter { UUID_RE.matches(it) }
            .distinct()
    }

    /** Prueba los UUID de a 5 en paralelo y se queda con la primera tanda que devuelva subtítulos. */
    private suspend fun findR2Subtitles(
        candidates: List<String>,
        pageUrl: String,
        cookies: String
    ): List<Pair<String, String>> {
        for (batch in candidates.chunked(5)) {
            val hits = batch.amap { fetchSubtitlesById(it, pageUrl, cookies) }.flatten()
            if (hits.isNotEmpty()) return hits.distinctBy { it.second }
        }
        return emptyList()
    }

    /** Servidor local para entregar subtítulos al reproductor. */
    private object LocalTextServer {
        private val files = ConcurrentHashMap<String, ByteArray>()
        private val workers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }

        @Volatile
        private var socket: ServerSocket? = null

        @Synchronized
        private fun ensureStarted(): ServerSocket? {
            socket?.let { if (!it.isClosed) return it }
            return try {
                val created = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                socket = created
                Thread {
                    while (!created.isClosed) {
                        try {
                            val client = created.accept()
                            workers.execute { serve(client) }
                        } catch (e: Exception) {
                            if (created.isClosed) break
                        }
                    }
                }.apply { isDaemon = true }.start()
                created
            } catch (e: Exception) {
                null
            }
        }

        private fun serve(client: Socket) {
            try {
                client.soTimeout = 10_000
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotEmpty() == true) {
                    // se descartan los headers de la petición
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val fileName = (parts.getOrNull(1) ?: "/").substringBefore("?").trimStart('/')
                val body = files[fileName]
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
                    // ya estaba cerrada
                }
            }
        }

        /** Publica el texto con el nombre dado (la extensión indica el formato) y devuelve su URL. */
        fun publish(fileName: String, content: String): String? {
            val server = ensureStarted() ?: return null
            files[fileName] = content.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)
            return "http://127.0.0.1:${server.localPort}/$fileName"
        }
    }

    private fun looksLikeSubtitleText(body: String): Boolean {
        val text = body.trimStart()
        return text.startsWith("WEBVTT") || text.contains("-->") || text.startsWith("[Script Info]")
    }

    // La URL del endpoint no tiene extensión: se añade para que el reproductor sepa en qué formato interpretarla.
    private fun subtitleFormatSuffix(body: String): String {
        val head = body.trimStart().take(200)
        return when {
            head.startsWith("WEBVTT") -> ".vtt"
            head.contains("[Script Info]") -> ".ass"
            else -> ".srt"
        }
    }

    /**
     * GET /api/subtitles/{uuid}. Formato real: {"success":true,"label":"Español (Estilos)","language":"es",
     * "content":"[Script Info]..."}, que se sirve desde el servidor local. También se acepta que la
     * respuesta sea el propio archivo de subtítulos o un JSON con URLs.
     */
    private suspend fun fetchSubtitlesById(
        id: String,
        pageUrl: String,
        cookies: String,
        label: String = "Español"
    ): List<Pair<String, String>> {
        val apiUrl = "$mainUrl/api/subtitles/$id"
        val response = attempt {
            app.get(apiUrl, headers = apiHeaders(pageUrl, cookies), timeout = HLS_TIMEOUT_S)
        } ?: return emptyList()
        if (response.code !in 200..299) return emptyList()

        val body = response.text
        val trimmed = body.trim()

        if (trimmed.startsWith("{")) {
            val json = attempt { JSONObject(trimmed) }
            val content = json?.optString("content").orEmpty()
            if (json != null && content.isNotBlank()) {
                val trackLabel = json.firstString(listOf("label"))
                    ?: json.firstString(listOf("language"))?.let { langLabel(it) }
                    ?: label
                val local = LocalTextServer.publish(id + subtitleFormatSuffix(content), content)
                return if (local != null) listOf(trackLabel to local) else emptyList()
            }
        }

        if (looksLikeSubtitleText(body)) return listOf(label to "$apiUrl#${subtitleFormatSuffix(body)}")

        val node: Any? = attempt {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> null
            }
        } ?: return emptyList()

        val found = ArrayList<Pair<String, String>>()
        collectSubtitles(node, found, null, apiUrl)
        return found.filterNot { it.second.startsWith("token:") }.distinctBy { it.second }
    }

    // ───────────────────────── JSON y subtítulos ─────────────────────────

    private fun isSubtitleKey(key: String): Boolean =
        key == "subs" || key == "sub" || key == "cc" || key == "vtt" || key == "srt" || key == "track" ||
            key.startsWith("subt") || key.contains("subtitle") || key.contains("caption") || key.endsWith("tracks")

    private fun guessSubtitleLabel(subtitleUrl: String): String {
        val file = subtitleUrl.substringBefore("?").substringAfterLast('/').lowercase()
        return FILE_LANG.find(file)?.groupValues?.getOrNull(1)?.let { langLabel(it) } ?: "Español"
    }

    private fun resolveRelative(base: String, ref: String): String? {
        attempt { URI(base).resolve(ref).toString() }?.let { return it }
        // Respaldo manual para URLs con caracteres que URI rechaza (espacios, '|', etc.).
        return when {
            ref.startsWith("http") -> ref
            ref.startsWith("//") -> "https:$ref"
            ref.startsWith("/") -> base.substringBefore("://", "https") + "://" + hostOf(base) + ref
            else -> base.substringBefore("?").substringBeforeLast('/') + "/" + ref
        }
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
            is JSONArray -> node.items().forEach { collectSubtitles(it, out, hint, base) }

            is JSONObject -> {
                val directUrl = node.firstString(SUB_URL_KEYS)
                val token = node.optString("token").trim()
                val label = node.firstString(SUB_LABEL_KEYS)?.let { langLabel(it) }

                if (directUrl != null) {
                    val full = subtitleUrlOf(directUrl, base) ?: return
                    out.add((label ?: hint ?: guessSubtitleLabel(full)) to full)
                } else if (token.isNotBlank()) {
                    out.add((label ?: hint ?: "Español") to "token:$token")
                } else {
                    for ((key, value) in node.entries()) {
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
            is JSONObject -> for ((key, value) in node.entries()) {
                if (isSubtitleKey(key.lowercase())) {
                    collectSubtitles(value, out, null, base)
                } else {
                    collectSubtitleKeys(value, out, base)
                }
            }
            is JSONArray -> node.items().forEach { collectSubtitleKeys(it, out, base) }
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
            val node: Any? = attempt { if (chunk.startsWith("[")) JSONArray(chunk) else JSONObject(chunk) }
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

    /** URLs de video (y subtítulos) dentro de la respuesta JSON de la API de fuentes. */
    private fun collectFromJson(
        node: Any?,
        urls: MutableList<String>,
        subtitles: MutableList<Pair<String, String>>,
        base: String = mainUrl
    ) {
        when (node) {
            is JSONObject -> for ((key, value) in node.entries()) {
                val lowerKey = key.lowercase()
                if (isSubtitleKey(lowerKey)) {
                    collectSubtitles(value, subtitles, null, base)
                } else if (IGNORE_KEY_PARTS.none { lowerKey.contains(it) }) {
                    collectFromJson(value, urls, subtitles, base)
                }
            }

            is JSONArray -> node.items().forEach { collectFromJson(it, urls, subtitles, base) }

            is String -> {
                val value = node.trim()
                if (value.startsWith("http") || value.startsWith("//")) {
                    val full = normalizeUrl(value)
                    if (SUB_EXT.containsMatchIn(full)) {
                        subtitleUrlOf(full, base)?.let { subtitles.add(guessSubtitleLabel(it) to it) }
                    } else if (!IMAGE_EXT.containsMatchIn(full)) {
                        urls.add(full)
                    }
                } else if (value.contains("<iframe", ignoreCase = true)) {
                    IFRAME_SRC.find(value)?.groupValues?.getOrNull(1)?.let { urls.add(it) }
                }
            }
        }
    }

    // ───────────────────────── Emisión de links ─────────────────────────

    private suspend fun makeLink(
        display: String,
        url: String,
        type: ExtractorLinkType,
        linkReferer: String,
        linkHeaders: Map<String, String> = emptyMap()
    ): ExtractorLink =
        newExtractorLink(
            source = name,
            name = "$name · $display",
            url = url,
            type = type
        ) {
            this.referer = linkReferer
            this.quality = Qualities.Unknown.value
            this.headers = linkHeaders
        }

    private suspend fun emitDirect(url: String, display: String, type: ExtractorLinkType, sink: LinkSink) {
        sink.emit(
            makeLink(
                display, url, type, "$mainUrl/",
                mapOf("Origin" to mainUrl, "User-Agent" to BROWSER_UA)
            )
        )
    }

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
        val isOwnVideo = source.isR2 || host.endsWith("donghualife.com")
        debug { "emitUrl: provider='${source.provider}' label='${source.label}' url=$url" }

        when {
            lower.contains("dailymotion.com") || lower.contains("dai.ly") || provider == "dailymotion" -> {
                val id = dailymotionVideoId(url)
                if (id != null && !sink.claim("dm:$id")) return
                val link = id?.let { resolveDailymotion(it, display) }
                if (link != null) sink.emit(link) else extractWithExtractors(url, pageUrl, sink)
            }

            host.contains("odysee.com") || host.contains("lbry.tv") || provider == "odysee" -> {
                // Se ofrecen las dos vías: el método nuevo (resolve directo + CDN) y el extractor
                // de CloudStream. Cada una va aislada: si una falla o no saca nada, la otra sigue.
                val direct = attempt { emitOdysee(url, display, sink) } ?: false
                debug { "odysee: método directo ${if (direct) "OK" else "sin links"}, probando extractor" }
                attempt { extractWithExtractors(url, pageUrl, sink) }
            }

            host.contains("ok.ru") || host.contains("odnoklassniki") || provider == "okru" || provider == "ok.ru" -> {
                val link = resolveOkRu(url, display)
                if (link != null) sink.emit(link) else extractWithExtractors(url, pageUrl, sink)
            }

            host.contains("voe") || provider == "voe" -> {
                val voeUrl = attempt { resolveVoe(url) }
                if (voeUrl != null) {
                    sink.emit(makeLink(display, voeUrl, ExtractorLinkType.M3U8, url))
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

    // ───────────────────────── Odysee: resolve directo, sin pasar por el extractor ─────────────────────────

    private val odyseeProxy = "https://api.na-backend.odysee.com/api/v1/proxy"
    private val odyseePlayer = "https://player.odycdn.com"
    private val odyseeHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Origin" to "https://odysee.com",
        "Referer" to "https://odysee.com/",
        "Accept" to "application/json",
        "Content-Type" to "application/json"
    )

    // uri lbry:// -> claim ya resuelto. Concurrente porque las fuentes se resuelven en paralelo (amap).
    private val odyseeClaims = ConcurrentHashMap<String, JSONObject>()

    /**
     * POST JSON-RPC al proxy de Odysee, sin auth. Reintenta con backoff exponencial si hay
     * 429/502/503 (o error de red), respetando Retry-After. Devuelve null si se agotan los intentos.
     */
    private suspend fun odyseeRpc(method: String, params: JSONObject, tries: Int = 4): JSONObject? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()

        var waitMs = 500L
        for (round in 1..tries) {
            val response = attempt {
                app.post(
                    "$odyseeProxy?m=$method",
                    headers = odyseeHeaders,
                    json = JsonAsString(payload),
                    timeout = SOURCE_TIMEOUT_S
                )
            }

            if (response != null) {
                val code = response.code
                if (code in 200..299) return attempt { JSONObject(response.text) }
                if (code != 429 && code != 502 && code != 503) {
                    debug { "odysee $method -> HTTP $code (sin reintento)" }
                    return null
                }
                debug { "odysee $method -> HTTP $code (intento $round/$tries)" }
            }

            if (round == tries) break
            val retryAfterMs = response?.headers?.get("Retry-After")?.toLongOrNull()?.times(1000)
            pause((retryAfterMs ?: waitMs).coerceAtMost(8_000L))
            waitMs = (waitMs * 2).coerceAtMost(8_000L)
        }
        return null
    }

    /** Resuelve una uri lbry:// a su claim. Cacheado: el mismo capítulo no vuelve a pegarle a la API. */
    private suspend fun odyseeResolve(uri: String): JSONObject? {
        odyseeClaims[uri]?.let {
            debug { "odysee resolve $uri -> cache" }
            return it
        }

        val response = odyseeRpc("resolve", JSONObject().put("urls", JSONArray().put(uri))) ?: run {
            debug { "odysee resolve $uri -> sin respuesta (error de red o reintentos agotados)" }
            return null
        }
        val result = response.optJSONObject("result") ?: run {
            debug { "odysee resolve $uri -> sin 'result': ${response.toString().take(300)}" }
            return null
        }

        // La clave suele ser la misma uri que se pidió; si Odysee la normaliza se toma la primera.
        val entry = result.optJSONObject(uri)
            ?: result.keys().asSequence().firstOrNull()?.let { result.optJSONObject(it) }
            ?: return null

        if (entry.has("error")) {
            debug { "odysee resolve $uri -> ${entry.opt("error")}" }
            return null
        }
        debug { "odysee resolve $uri -> ok claim_id=${entry.optString("claim_id")}" }
        odyseeClaims[uri] = entry
        return entry
    }

    /**
     * URLs directas del CDN armadas con claim_id + sd_hash (evita la llamada extra a "get").
     * El .mp4 de /v6/streams/{claim}/{sd_hash} figura en la config pública de odysee-api; el
     * master.m3u8 no se pudo confirmar, por eso se ofrecen las dos y se ve cuál anda.
     */
    private fun odyseeCdnUrls(claim: JSONObject): List<Pair<String, ExtractorLinkType>> {
        val claimId = claim.optString("claim_id").takeIf { it.isNotBlank() && it != "null" }
            ?: return emptyList()
        val sdHash = claim.optJSONObject("value")?.optJSONObject("source")?.optString("sd_hash")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return emptyList()
        return listOf(
            "$odyseePlayer/v6/streams/$claimId/$sdHash.mp4" to ExtractorLinkType.VIDEO,
            "$odyseePlayer/v6/streams/$claimId/$sdHash/master.m3u8" to ExtractorLinkType.M3U8
        )
    }

    /** Último recurso: pedirle la streaming_url a la API "get" (la que más rate-limit come). */
    private suspend fun odyseeGetStreamingUrl(uri: String): String? {
        val result = odyseeRpc("get", JSONObject().put("uri", uri).put("environment", "live"))
            ?.optJSONObject("result") ?: return null
        return result.optString("streaming_url").takeIf { it.isNotBlank() && it != "null" }
    }

    /** https://odysee.com/$/embed/@canal:c/video:a  (o /@canal:c/video:a, o lbry://...)  ->  lbry://@canal#c/video#a */
    private fun odyseeUriOf(url: String): String? {
        if (url.startsWith("lbry://")) return url.substringBefore('?').takeIf { it.length > "lbry://".length }

        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
            .let { if (it.firstOrNull() == "\$") it.drop(2) else it } // $/embed/... , $/download/...
        if (segments.isEmpty()) return null

        return "lbry://" + segments.joinToString("/") { segment ->
            (attempt { URLDecoder.decode(segment, "UTF-8") } ?: segment).replaceFirst(":", "#")
        }
    }

    /**
     * true  = lo manejó (hay links, o esta uri ya se estaba procesando) -> no hace falta el extractor.
     * false = no pudo resolverla -> el llamador cae al extractor genérico.
     */
    private suspend fun emitOdysee(url: String, display: String, sink: LinkSink): Boolean {
        val uri = odyseeUriOf(url)
        debug { "odysee: url=$url -> uri=$uri" }
        if (uri == null) return false
        if (!sink.claim("odysee:$uri")) return true

        var links = odyseeResolve(uri)?.let { odyseeCdnUrls(it) }.orEmpty()
        if (links.isEmpty()) {
            debug { "odysee $uri -> sin sd_hash/claim_id, probando API get" }
            val streaming = odyseeGetStreamingUrl(uri)
            if (streaming == null) {
                debug { "odysee $uri -> get tampoco devolvió streaming_url" }
                return false
            }
            val type = if (streaming.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            links = listOf(streaming to type)
        }
        debug { "odysee $uri -> ${links.size} links: ${links.map { it.first }}" }

        val linkHeaders = mapOf("Origin" to "https://odysee.com", "User-Agent" to BROWSER_UA)
        for ((streamUrl, type) in links) {
            val label = if (type == ExtractorLinkType.M3U8) "$display HLS" else "$display MP4"
            sink.emit(makeLink(label, streamUrl, type, "https://odysee.com/", linkHeaders))
        }
        return true
    }

    // ───────────────────────── Subtítulos dentro del HLS de R2 ─────────────────────────

    /** Si el master .m3u8 declara pistas #EXT-X-MEDIA:TYPE=SUBTITLES, se ofrecen como subtítulos. */
    private suspend fun emitHlsSubtitles(masterUrl: String, subtitleCallback: (SubtitleFile) -> Unit, sink: LinkSink) {
        val found = attempt { readHlsSubtitles(masterUrl) }.orEmpty()
        emitSubtitles(found, subtitleCallback, sink)
    }

    private suspend fun readHlsSubtitles(masterUrl: String): List<Pair<String, String>> {
        val headers = siteHeaders() + ("Origin" to mainUrl)

        val master = app.get(masterUrl, headers = headers, timeout = HLS_TIMEOUT_S).text
        if (!master.contains("#EXTM3U")) return emptyList()

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

                // Playlist de subtítulos con un único .vtt: se usa ese archivo directamente.
                absolute.contains(".m3u8", ignoreCase = true) -> {
                    val playlist = attempt { app.get(absolute, headers = headers, timeout = HLS_TIMEOUT_S).text }
                    val files = playlist?.lineSequence()
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() && !it.startsWith("#") }
                        ?.toList()
                        .orEmpty()
                    if (files.size == 1) resolveRelative(absolute, files.first()) else null
                }

                else -> null
            }

            if (direct != null) found.add(label to direct)
        }

        return found.distinctBy { it.second }
    }

    // ───────────────────────── Dailymotion ─────────────────────────

    private fun dailymotionVideoId(embedUrl: String): String? =
        embedUrl.substringAfter("video=", "").substringBefore("&").takeIf { it.isNotBlank() }
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

        return makeLink(display, streamUrl, ExtractorLinkType.M3U8, dmReferer, dmHeaders)
    }

    // ───────────────────────── OK.ru ─────────────────────────

    private fun cleanEscapes(text: String): String =
        text.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\\\/", "/")
            .replace("\\\\\\\\", "\\\\")

    private suspend fun fetchEmbed(embedUrl: String): String? {
        val response = attempt {
            app.get(
                embedUrl,
                headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to embedUrl),
                timeout = SOURCE_TIMEOUT_S
            )
        } ?: return null

        return if (response.code in 200..299) cleanEscapes(response.text) else null
    }

    private suspend fun resolveOkRu(embedUrl: String, display: String): ExtractorLink? {
        val page = fetchEmbed(embedUrl) ?: return null
        val hlsUrl = OK_HLS_URL.find(page)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return makeLink(display, hlsUrl, ExtractorLinkType.M3U8, embedUrl)
    }

    // ───────────────────────── VOE ─────────────────────────

    private suspend fun resolveVoe(embedUrl: String): String? {
        val headers = mapOf("User-Agent" to BROWSER_UA)

        val first = app.get(embedUrl, headers = headers, timeout = SOURCE_TIMEOUT_S)
        if (!first.isSuccessful) return null

        val realUrl = VOE_REDIRECT.find(first.text)?.groupValues?.getOrNull(1) ?: embedUrl
        val page = if (realUrl != embedUrl) app.get(realUrl, headers = headers, timeout = SOURCE_TIMEOUT_S) else first

        var encoded = VOE_CONFIG.find(page.text)?.groupValues?.getOrNull(1)
        if (encoded.isNullOrBlank()) {
            val solved = solveVoeAltcha(realUrl, page.text, page.cookies) ?: return null
            encoded = VOE_CONFIG_STRICT.find(solved)?.groupValues?.getOrNull(1)
        }
        if (encoded.isNullOrBlank()) return null

        val decoded = decodeVoeConfig(encoded)?.takeIf { it.isNotBlank() } ?: return null
        return VOE_SOURCE.find(decoded)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.replace("\\\\/", "/")
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
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
            headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to realUrl)
        )
        if (!challengeResponse.isSuccessful) return null

        val challengeJson = challengeResponse.text
        val challenge = attempt { JSONObject(challengeJson) } ?: return null

        val cost = challenge.optInt("cost", -1)
        val keyLength = challenge.optInt("keyLength", -1)
        val keyPrefix = challenge.optString("keyPrefix")
        val nonce = challenge.optString("nonce")
        val salt = challenge.optString("salt")
        if (
            challenge.optString("algorithm").isBlank() ||
            cost < 0 ||
            keyLength <= 0 ||
            keyPrefix.isBlank() ||
            nonce.isBlank() ||
            salt.isBlank()
        ) {
            return null
        }

        val saltBytes = hexToBytes(salt)
        val nonceBytes = hexToBytes(nonce)
        val mac = Mac.getInstance("HmacSHA256")

        // Contraseña = nonce + contador de 4 bytes (big endian). El arreglo se reutiliza: solo cambia el contador.
        val password = nonceBytes.copyOf(nonceBytes.size + 4)
        val counterAt = nonceBytes.size

        var solvedCounter = -1
        var solvedKey = ""
        val powStart = System.nanoTime()

        for (counter in 0 until 1_000_000) {
            // Corte de seguridad para no colgar la carga de links.
            if ((counter and 0xff) == 0 && System.nanoTime() - powStart > ALTCHA_TIMEOUT_NS) break

            password[counterAt] = (counter ushr 24).toByte()
            password[counterAt + 1] = (counter ushr 16).toByte()
            password[counterAt + 2] = (counter ushr 8).toByte()
            password[counterAt + 3] = counter.toByte()

            val derived = pbkdf2Sha256(mac, password, saltBytes, cost, keyLength)
            if (startsWithHex(derived, keyPrefix)) {
                solvedCounter = counter
                solvedKey = bytesToHex(derived)
                break
            }
        }
        if (solvedCounter < 0) return null

        val powElapsedMs = (System.nanoTime() - powStart) / 1_000_000.0
        val altchaPayload =
            """{"challenge":$challengeJson,"solution":{"counter":$solvedCounter,"derivedKey":"$solvedKey","time":$powElapsedMs}}"""
        val payloadB64 = Base64.encodeToString(altchaPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val voeCookies = pageCookies.entries.joinToString("; ") { (cookieName, cookieValue) -> "$cookieName=$cookieValue" }
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

        return if (postResponse.isSuccessful) postResponse.text else null
    }

    private fun rot13(text: String): String = buildString(text.length) {
        for (char in text) {
            append(
                when (char) {
                    in 'A'..'Z' -> 'A' + (char - 'A' + 13) % 26
                    in 'a'..'z' -> 'a' + (char - 'a' + 13) % 26
                    else -> char
                }
            )
        }
    }

    private fun decodeVoeConfig(encoded: String): String? =
        attempt {
            var value = rot13(encoded)
            for (separator in VOE_SEPARATORS) value = value.replace(separator, "_")
            value = value.replace("_", "")

            val bytes = Base64.decode(value, Base64.DEFAULT)
            for (i in bytes.indices) bytes[i] = (bytes[i] - 3).toByte()
            bytes.reverse()

            String(Base64.decode(String(bytes, Charsets.UTF_8), Base64.DEFAULT), Charsets.UTF_8)
        }

    /** PBKDF2-HMAC-SHA256 con el Mac reutilizado entre llamadas. */
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
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte()
                )
            )

            var u = mac.doFinal()
            val t = u.copyOf()

            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }

            System.arraycopy(t, 0, output, (block - 1) * hashLength, hashLength)
        }

        return output.copyOf(keyLength)
    }

    /** Compara el inicio del hash con un prefijo en hex sin armar el string completo en cada intento. */
    private fun startsWithHex(bytes: ByteArray, prefix: String): Boolean {
        if (prefix.length > bytes.size * 2) return false
        for (i in prefix.indices) {
            val value = bytes[i ushr 1].toInt() and 0xff
            val nibble = if ((i and 1) == 0) value ushr 4 else (value and 0x0f)
            if (HEX_CHARS[nibble] != prefix[i].lowercaseChar()) return false
        }
        return true
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
        require(clean.length % 2 == 0) { "Hex inválido: longitud impar" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
