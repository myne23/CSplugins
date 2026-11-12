package com.animejara

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/* ====================================================================
 * AnimeJara (animejara.com) - catálogo + reproducción
 *
 * El sitio es WordPress y no expone los anime por la API REST, así que
 * el catálogo se lee del HTML y el buscador usa admin-ajax.php.
 *
 * La ficha de cada serie trae TEMPORADAS_DATA (JSON) con los episodios y
 * sus idiomas. Cada episodio/película embebe un reproductor de
 * streamhj.top que lista servidores externos. Los servidores se resuelven
 * en paralelo: FileLions/VidHide y Uqload a mano (para elegir el hls4 que
 * funciona), Byse con su flujo de challenge/attest/PoW/AES-GCM, y el resto
 * con los extractores de CloudStream y un respaldo manual de .m3u8.
 * ==================================================================== */

private const val DEBUG = true
private const val TAG = "AnimeJara"

private const val WEB = "https://animejara.com"
private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** Máximo de enlaces que se emiten por idioma (para no tardar demasiado). */
private const val MAX_LINKS_PER_LANGUAGE = 2

/** Tiempo máximo aproximado resolviendo enlaces (ms). */
private const val LINKS_BUDGET_MS = 22_000L

/** Tiempo máximo para resolver un servidor normal y uno de Byse (ms). */
private const val SERVER_TIMEOUT_MS = 11_000L
private const val BYSE_TIMEOUT_MS = 18_000L

private val HEADERS = mapOf(
    "User-Agent" to UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
)

/** Servidores que se intentan primero (los más estables en la app). */
private val PRIORITY_HOSTS = listOf(
    "filelions", "uqload", "mxdrop", "voe.sx", "hgcloud",
    "streamtape", "mp4upload", "byse"
)

/** Hosts que la app no sabe resolver o que no aportan nada. */
private val IGNORED_HOSTS = listOf("savefiles")

/** Hosts donde vale la pena buscar un .m3u8 a mano. */
private val MANUAL_HOSTS = listOf(
    "uqload", "filelions", "vidhide", "streamwish", "hgcloud", "voe"
)

/** Familia Byse (bysekoze y mirrors). */
private val BYSE_EMBED = Regex(
    """^https?://(?:www\.)?byse[a-z0-9-]*\.[a-z]+/(.*)$""",
    RegexOption.IGNORE_CASE
)

private val BYSE_UA =
    "Mozilla/5.0 (Linux; Android 6.0.1; SM-G5510 Build/MMB29M) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/59.0.3071.125 Mobile Safari/537.36 SamsungBrowser/7.2"

/** Límites globales para no saturar el teléfono al abrir muchas fuentes. */
private val SERVER_GATE = Semaphore(6)
private val BYSE_GATE = Semaphore(1)

private fun log(msg: () -> String) {
    if (DEBUG) Log.d(TAG, msg())
}

private fun short(value: String, max: Int = 160): String =
    if (value.length <= max) value else value.take(max) + "…"

private fun hostOf(url: String): String = try {
    java.net.URI(url.trim()).host ?: url.substringAfter("://").substringBefore('/')
} catch (e: Exception) {
    url.substringAfter("://").substringBefore('/')
}

private fun absolute(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> WEB + value
        else -> value
    }
}

private fun absHref(raw: String): String {
    val href = raw.trim().substringBefore('#')
    return when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> WEB + href
        href.startsWith("?") -> "$WEB/catalogo/$href"
        else -> href
    }
}

/** Lee la URL de una imagen teniendo en cuenta la carga diferida (lazy). */
private fun imageUrl(el: Element?): String? {
    if (el == null) return null
    for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
        val raw = el.attr(attr).trim()
        if (raw.isNotEmpty() && !raw.startsWith("data:")) return absolute(raw)
    }
    return null
}

private fun prettyLang(raw: String): String {
    val value = raw.trim()
    return when {
        value.contains("LATINO", true) -> "Latino"
        value.contains("CASTELLANO", true) -> "Castellano"
        value.contains("JAPON", true) -> "Japonés (sub)"
        value.contains("DUB", true) -> "Doblaje"
        value.contains("SUB", true) -> "Subtitulado"
        value.isEmpty() -> "Audio"
        else -> value.lowercase().replaceFirstChar { it.uppercase() }
    }
}

/**
 * Extrae el primer array JSON que aparece después de [key]. Se recorre el
 * texto contando corchetes y respetando las cadenas, para no cortar el JSON
 * en un "]" que esté dentro de una sinopsis.
 */
private fun extractJsonArray(html: String, vararg keys: String): String? {
    for (key in keys) {
        val keyIndex = html.indexOf(key)
        if (keyIndex < 0) continue
        val start = html.indexOf('[', keyIndex + key.length)
        if (start < 0) continue
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
        }
    }
    return null
}

// --------------------------------------------------------------------
// Modelos internos
// --------------------------------------------------------------------

private data class JaraEpisode(
    val season: Int,
    val number: Int,
    val rawNumber: String,
    val title: String?,
    val date: String?,
    val languages: List<String>
)

private data class LanguageGroup(
    val name: String,
    val embeds: List<String>
)

private data class ServerCandidate(
    val name: String,
    val url: String,
    val embed: String
)

private data class ServerResult(
    val service: String,
    val link: ExtractorLink
)

private data class ManualLink(
    val url: String,
    val referer: String
)

class AnimeJaraProvider : MainAPI() {

    override var mainUrl = WEB
    override var name = "AnimeJara"
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "inicio" to "Inicio",
        "latino" to "Anime latino",
        "castellano" to "Anime castellano",
        "japones" to "Anime japonés (sub)"
    )

    /* ------------------------------------------------------------
     * HTTP
     * ------------------------------------------------------------ */

    private suspend fun getDoc(url: String, referer: String? = null): Document? {
        return try {
            val headers = if (referer == null) HEADERS else HEADERS + mapOf("Referer" to referer)
            val response = app.get(url, headers = headers, timeout = 30L)
            if (response.code !in 200..299) {
                // El sitio devuelve 404 en páginas de episodio que sí tienen contenido.
                log { "GET | HTTP ${response.code} (se procesa igual) | ${short(url)}" }
            }
            response.document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "GET ERROR | ${e.javaClass.simpleName}: ${e.message} | ${short(url)}" }
            null
        }
    }

    /* ------------------------------------------------------------
     * Home
     * ------------------------------------------------------------ */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == "inicio") {
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
            return homePage()
        }

        val idioma = when (request.data) {
            "latino" -> "LATINO"
            "castellano" -> "CASTELLANO"
            "japones" -> "JAPONES"
            else -> null
        }
        return catalogPage(page, request, idioma)
    }

    private suspend fun homePage(): HomePageResponse {
        val doc = getDoc("$WEB/inicio")
        if (doc == null) {
            log { "HOME FAIL | no se pudo leer /inicio" }
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val sections = ArrayList<HomePageList>()

        val latest = doc.select("a.ep-card").mapNotNull { latestCard(it) }
        log { "HOME | ultimos episodios=${latest.size}" }
        if (latest.isNotEmpty()) {
            sections.add(HomePageList("Últimos episodios", latest))
        }

        val seasons = doc.select("a.anime-card").mapNotNull { animeCard(it, null) }
        log { "HOME | ultimas temporadas=${seasons.size}" }
        if (seasons.isNotEmpty()) {
            sections.add(HomePageList("Últimas temporadas", seasons))
        }

        val ranking = doc.select("#top-dia a.top-anime-item").mapNotNull { rankingCard(it) }
        log { "HOME | ranking=${ranking.size}" }
        if (ranking.isNotEmpty()) {
            sections.add(HomePageList("Ranking de hoy", ranking))
        }

        heroCard(doc)?.let { hero ->
            sections.add(HomePageList("Destacado", listOf(hero), isHorizontalImages = true))
        }

        return newHomePageResponse(sections, hasNext = false)
    }

    private suspend fun catalogPage(
        page: Int,
        request: MainPageRequest,
        idioma: String?
    ): HomePageResponse {
        val url = buildString {
            append("$WEB/catalogo/?")
            if (!idioma.isNullOrBlank()) append("idioma=$idioma&")
            if (page > 1) append("paged=$page")
        }.trimEnd('&', '?')

        val doc = getDoc(url)
        if (doc == null) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = doc.select("a.anime-card").mapNotNull { animeCard(it, idioma) }
        val hasNext = doc.select(".paginacion a[data-page]").any {
            it.attr("data-page").trim().toIntOrNull() == page + 1
        }
        log { "CATALOGO | ${request.name} | pagina=$page | items=${items.size} | next=$hasNext" }
        return newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
    }

    /** Tarjeta de "Últimos episodios". Enlaza a la serie y muestra el chip de idioma. */
    private fun latestCard(el: Element): SearchResponse? {
        val href = absHref(el.attr("href"))
        if (href.isBlank()) return null

        val title = el.selectFirst(".ep-name")?.text()?.trim().orEmpty()
            .ifBlank { el.selectFirst(".ep-poster-wrap img")?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null

        val poster = imageUrl(el.selectFirst(".ep-poster-wrap img"))
        val epTag = el.selectFirst(".ep-tag")?.text()?.trim().orEmpty()
        val langText = el.select(".lang-tag").joinToString(" ") { it.text() }.uppercase()
        val isMovie = href.contains("/movie/")
        val epNumber = Regex("""(?i)(\d+)x(\d+)""").find(epTag)?.groupValues?.get(2)?.toIntOrNull()
            ?: Regex("""-(\d+)x(\d+)/?$""").find(href)?.groupValues?.get(2)?.toIntOrNull()

        val seriesUrl = if (isMovie) {
            href
        } else {
            val m = Regex("""/episode/(.+?)-\d+x\d+/?$""").find(href)
            if (m != null) "$WEB/anime/${m.groupValues[1]}" else href
        }

        val display = if (epTag.isNotBlank() && !epTag.equals("PELICULA", true)) {
            "$title · $epTag"
        } else {
            title
        }

        if (isMovie) {
            return newMovieSearchResponse(display, seriesUrl, TvType.AnimeMovie) {
                posterUrl = poster
            }
        }

        return newAnimeSearchResponse(display, seriesUrl, TvType.Anime) {
            posterUrl = poster
            if (langText.contains("SUB") || langText.contains("JAPON")) {
                addDubStatus(DubStatus.Subbed, epNumber)
            }
            if (langText.contains("LATINO") || langText.contains("CASTELLANO") || langText.contains("DUB")) {
                addDubStatus(DubStatus.Dubbed, epNumber)
            }
        }
    }

    /** Tarjeta de serie/película (casa, catálogo y ranking). */
    private fun animeCard(el: Element, languageHint: String?): SearchResponse? {
        val href = absHref(el.attr("href"))
        if (href.isBlank()) return null

        // En el catálogo el primer <img> del bloque es la bandera de idioma
        // (.card-langs). La portada real está en .card-poster; en el home
        // viene dentro de .card-inner.
        val posterEl = el.selectFirst("img.card-poster")
            ?: el.selectFirst(".card-poster-wrapper img")
            ?: el.selectFirst(".card-inner img")
            ?: el.selectFirst("img")
        val title = el.selectFirst("h3.card-title")?.text()?.trim().orEmpty()
            .ifBlank { posterEl?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null

        val poster = imageUrl(posterEl)
        val rating = el.selectFirst(".badge-rating")?.text()
            ?.replace("★", "")?.trim()?.toDoubleOrNull()
        val badge = el.selectFirst(".badge-season")?.text()?.trim().orEmpty()
        val isMovie = href.contains("/movie/") || badge.contains("PELICULA", true)
        val scoreValue = rating?.let { Score.from10(it * 2.0) }

        // Las banderas del catálogo indican los idiomas disponibles aunque
        // la pestaña no los diga (por ejemplo, un anime con latino y japonés).
        val flagLangs = el.select(".card-langs img").mapNotNull {
            it.attr("alt").trim().uppercase().takeIf { value -> value.isNotEmpty() }
        }
        val langs = (flagLangs + listOfNotNull(languageHint?.uppercase())).distinct()
        val hasSub = langs.any { it.contains("JAPON") || it.contains("SUB") }
        val hasDub = langs.any {
            it.contains("LATINO") || it.contains("CASTELLANO") || it.contains("DUB")
        }

        if (isMovie) {
            return newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                posterUrl = poster
                this.score = scoreValue
            }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            this.score = scoreValue
            if (hasSub) addDubStatus(DubStatus.Subbed)
            if (hasDub) addDubStatus(DubStatus.Dubbed)
        }
    }

    private fun rankingCard(el: Element): SearchResponse? {
        val href = absHref(el.attr("href"))
        if (href.isBlank()) return null

        val title = el.selectFirst(".top-title")?.text()?.trim().orEmpty()
            .ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null

        val poster = imageUrl(el.selectFirst(".top-img"))
        val rating = el.selectFirst(".top-rating")?.text()
            ?.replace("★", "")?.replace("☆", "")?.trim()?.toDoubleOrNull()
        val scoreValue = rating?.let { Score.from10(it * 2.0) }
        val isMovie = href.contains("/movie/")

        if (isMovie) {
            return newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                posterUrl = poster
                this.score = scoreValue
            }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            this.score = scoreValue
        }
    }

    private fun heroCard(doc: Document): SearchResponse? {
        val href = absHref(doc.selectFirst("#heroLink")?.attr("href").orEmpty())
        if (href.isBlank()) return null
        val title = doc.selectFirst("#heroTitle")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = imageUrl(doc.selectFirst("#heroImg"))

        if (href.contains("/movie/")) {
            return newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                posterUrl = poster
            }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    /* ------------------------------------------------------------
     * Buscador (admin-ajax.php)
     * ------------------------------------------------------------ */

    override suspend fun search(query: String): List<SearchResponse>? {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()

        return try {
            val response = app.post(
                "$WEB/wp-admin/admin-ajax.php",
                headers = HEADERS + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$WEB/inicio",
                    "Origin" to WEB
                ),
                data = mapOf("action" to "live_search", "s" to clean),
                timeout = 30L
            )
            if (response.code !in 200..299) {
                log { "SEARCH | HTTP ${response.code} | q=$clean" }
                return emptyList()
            }

            val root = JSONObject(response.text)
            val animes = root.optJSONObject("data")?.optJSONArray("animes")
            val out = ArrayList<SearchResponse>()
            if (animes != null) {
                for (i in 0 until animes.length()) {
                    searchItem(animes.optJSONObject(i))?.let { out.add(it) }
                }
            }
            log { "SEARCH | q=$clean | resultados=${out.size}" }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "SEARCH ERROR | ${e.javaClass.simpleName}: ${e.message} | q=$clean" }
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private fun searchItem(item: JSONObject?): SearchResponse? {
        if (item == null) return null
        val title = item.optString("titulo").trim()
        val slug = item.optString("slug").trim()
        if (title.isEmpty() || slug.isEmpty()) return null

        val isMovie = item.optString("tipo").equals("pelicula", true)
        val href = if (isMovie) "$WEB/movie/$slug/" else "$WEB/anime/$slug"
        val poster = absolute(item.optString("poster"))
        val scoreValue = item.optString("rating").trim().toDoubleOrNull()
            ?.let { Score.from10(it * 2.0) }
        val yearValue = item.optString("anio").trim().toIntOrNull()

        if (isMovie) {
            return newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                posterUrl = poster
                this.year = yearValue
                this.score = scoreValue
            }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            this.year = yearValue
            this.score = scoreValue
        }
    }

    /* ------------------------------------------------------------
     * Ficha (serie / película)
     * ------------------------------------------------------------ */

    override suspend fun load(url: String): LoadResponse? {
        var target = url.trim()

        // Las tarjetas de "Últimos episodios" enlazan a la serie, pero si llega
        // una URL de episodio se resuelve la serie con el botón LISTA.
        if (target.contains("/episode/")) {
            log { "LOAD | url de episodio, buscando serie | ${short(target)}" }
            val episodeDoc = getDoc(target)
            val lista = episodeDoc?.select("a.btn-nav")
                ?.firstOrNull { it.text().trim().equals("LISTA", true) }
                ?.attr("href")
                ?.trim()
            target = when {
                !lista.isNullOrBlank() -> lista
                else -> {
                    val m = Regex("""/episode/(.+?)-\d+x\d+/?$""").find(target)
                    if (m != null) "$WEB/anime/${m.groupValues[1]}" else return null
                }
            }
        }

        val canonical = target.substringBefore('#').trim()
        if (canonical.isEmpty()) return null

        val doc = getDoc(canonical) ?: return null

        val title = doc.selectFirst("h1.anime-title-desktop")?.text()?.trim()
            ?: doc.selectFirst("h1.anime-title-mobile")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")?.trim()
        if (title.isNullOrBlank()) {
            log { "LOAD FAIL | sin título | ${short(canonical)}" }
            return null
        }

        val poster = imageUrl(doc.selectFirst("img.main-poster-img"))
            ?: imageUrl(doc.selectFirst("#mainPosterImg"))
        val plotText = doc.selectFirst(".anime-sinopsis-contenedor")?.text()?.trim()
        val tagList = doc.selectFirst(".anime-categorias")
            ?.select("span")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val yearValue = doc.select("i.fa-calendar-alt")
            .mapNotNull { it.parent()?.selectFirst("span")?.text()?.trim()?.toIntOrNull() }
            .firstOrNull { it in 1900..2100 }
        val scoreValue = doc.selectFirst("#rating-val")?.text()?.trim()
            ?.toDoubleOrNull()?.let { Score.from10(it * 2.0) }
        val statusText = doc.selectFirst(".label-poster")?.text()?.trim().orEmpty()
        val statusValue = when {
            statusText.contains("EMISION", true) || statusText.contains("EMISIÓN", true) -> ShowStatus.Ongoing
            statusText.contains("FINALIZ", true) || statusText.contains("COMPLET", true) -> ShowStatus.Completed
            else -> null
        }
        val isMovie = canonical.contains("/movie/")

        log {
            "LOAD | title=$title | tipo=${if (isMovie) "PELICULA" else "SERIE"} | " +
                "año=${yearValue ?: "-"} | nota=${scoreValue ?: "-"} | tags=${tagList.size}"
        }

        if (isMovie) {
            return newMovieLoadResponse(title, canonical, TvType.AnimeMovie, canonical) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plotText
                year = yearValue
                tags = tagList
                score = scoreValue
            }
        }

        val slug = Regex("""/anime/([^/?#]+)""").find(canonical)?.groupValues?.get(1)
            ?: canonical.trimEnd('/').substringAfterLast('/')
        val parsed = parseEpisodes(doc)
        log { "LOAD | slug=$slug | episodios=${parsed.size}" }

        if (slug.isBlank() || parsed.isEmpty()) {
            return newAnimeLoadResponse(title, canonical, TvType.Anime) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plotText
                year = yearValue
                tags = tagList
                score = scoreValue
                showStatus = statusValue
            }
        }

        val subEpisodes = ArrayList<Episode>(parsed.size)
        val dubEpisodes = ArrayList<Episode>()

        for (ep in parsed.sortedWith(compareBy({ it.season }, { it.number }))) {
            val epUrl = "$WEB/episode/$slug-${ep.season}x${ep.rawNumber}/"
            val episode = newEpisode(epUrl, initializer = {
                this.name = ep.title ?: "Episodio ${ep.number}"
                this.episode = ep.number
                this.season = ep.season
                // Sin miniatura por episodio: la ficha abre mucho más rápido
                // (One Piece tiene más de 1000 episodios).
                ep.date?.let { addDate(it, "yyyy-MM-dd HH:mm:ss") }
            })

            val langs = ep.languages.map { it.uppercase() }
            val hasSub = langs.any { it.contains("JAPON") || it.contains("SUB") }
            val hasDub = langs.any {
                it.contains("LATINO") || it.contains("CASTELLANO") || it.contains("DUB")
            }
            if (hasSub || !hasDub) subEpisodes.add(episode)
            if (hasDub) dubEpisodes.add(episode)
        }

        log { "LOAD IDIOMAS | title=$title | sub=${subEpisodes.size} | dub=${dubEpisodes.size}" }

        return newAnimeLoadResponse(title, canonical, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = plotText
            year = yearValue
            tags = tagList
            score = scoreValue
            showStatus = statusValue
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    /** Lee TEMPORADAS_DATA de la ficha de la serie. */
    private fun parseEpisodes(doc: Document): List<JaraEpisode> {
        val html = doc.html()
        val json = extractJsonArray(
            html,
            "TEMPORADAS_DATA =",
            "TEMPORADAS_DATA=",
            "TEMPORADAS_DATA"
        )
        if (json == null) {
            log { "LOAD | TEMPORADAS_DATA no encontrado" }
            return emptyList()
        }

        val seasons = try {
            JSONArray(json)
        } catch (e: Exception) {
            log { "LOAD | TEMPORADAS_DATA inválido: ${e.message}" }
            return emptyList()
        }

        val out = ArrayList<JaraEpisode>()
        for (s in 0 until seasons.length()) {
            val seasonObj = seasons.optJSONObject(s) ?: continue
            val seasonNum = seasonObj.optInt("numero_temporada", s + 1)
            val episodes = seasonObj.optJSONArray("episodios") ?: continue
            for (e in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(e) ?: continue
                val raw = ep.optString("numero_episodio").trim()
                if (raw.isEmpty()) continue
                val languages = ep.optJSONArray("idiomas")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).trim().takeIf { it.isNotEmpty() }
                    }
                }.orEmpty()
                out.add(
                    JaraEpisode(
                        season = seasonNum,
                        number = raw.toIntOrNull() ?: (e + 1),
                        rawNumber = raw,
                        title = ep.optString("nombre_episodio").trim().takeIf { it.isNotEmpty() },
                        date = ep.optString("fecha_actualizacion").trim().takeIf { it.isNotEmpty() },
                        languages = languages
                    )
                )
            }
        }
        return out
    }

    /* ------------------------------------------------------------
     * Reproducción
     * ------------------------------------------------------------ */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = data.trim()
        log { "LINKS | $url" }
        if (url.isEmpty() || !url.contains("animejara.com")) {
            log { "LINKS FAIL | url no reconocida" }
            return false
        }

        return try {
            val response = app.get(url, headers = HEADERS, timeout = 30L)
            if (response.code !in 200..299) {
                log { "LINKS | HTTP ${response.code} (se procesa igual) | ${short(url)}" }
            }

            val html = response.text
            val isMovie = url.contains("/movie/")
            val groups = parseLanguageGroups(response.document, html, isMovie)
            if (groups.isEmpty()) {
                log { "LINKS FAIL | sin idiomas/servidores | ${short(url)}" }
                return false
            }

            log { "LINKS IDIOMAS | ${groups.joinToString { "${it.name}(${it.embeds.size})" }}" }

            val emitted = ConcurrentHashMap.newKeySet<String>()
            val total = AtomicInteger(0)
            val emitLock = Any()
            val emit: (ExtractorLink) -> Unit = { link ->
                synchronized(emitLock) { callback(link) }
                total.incrementAndGet()
            }

            // Todos los idiomas y servidores a la vez: los enlaces van
            // apareciendo conforme responden, sin esperar a los lentos.
            withTimeoutOrNull(LINKS_BUDGET_MS) {
                coroutineScope {
                    groups.map { group ->
                        async { resolveGroup(group, url, emitted, subtitleCallback, emit) }
                    }.awaitAll()
                }
            }

            log { "LINKS FIN | emitidos=${total.get()} | ${short(url)}" }
            total.get() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "LINKS ERROR | ${e.javaClass.simpleName}: ${e.message} | ${short(url)}" }
            false
        }
    }

    /**
     * Empareja los botones de idioma con sus URLs de embed.
     * - Episodios: `const enlaces = [url, ...]` (un embed por idioma).
     * - Películas: `const dataIdiomas = [[url, ...], ...]` (varios por idioma).
     */
    private fun parseLanguageGroups(
        doc: Document,
        html: String,
        isMovie: Boolean
    ): List<LanguageGroup> {
        val buttons = ArrayList<Pair<Int, String>>()
        for (btn in doc.select(".botones-idioma .boton-idioma")) {
            val name = btn.selectFirst(".lang-name")?.text()?.trim().orEmpty()
            val index = Regex("""cambiarIdioma(?:Grupo)?\(\s*(\d+)""")
                .find(btn.attr("onclick"))
                ?.groupValues?.get(1)?.toIntOrNull()
            if (name.isNotEmpty() || index != null) {
                buttons.add((index ?: buttons.size) to name)
            }
        }

        val json = if (isMovie) {
            extractJsonArray(html, "dataIdiomas =", "dataIdiomas=", "dataIdiomas")
        } else {
            extractJsonArray(
                html,
                "const enlaces =",
                "const enlaces=",
                "var enlaces =",
                "let enlaces =",
                "enlaces ="
            )
        }
        if (json == null) {
            log { "LINKS | JSON de idiomas no encontrado (movie=$isMovie)" }
            return emptyList()
        }

        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            log { "LINKS | JSON de idiomas inválido: ${e.message}" }
            return emptyList()
        }

        val groups = ArrayList<LanguageGroup>()
        val used = HashSet<Int>()

        if (!isMovie) {
            for ((index, name) in buttons) {
                val embed = array.optString(index).trim()
                if (!embed.startsWith("http")) continue
                used.add(index)
                groups.add(LanguageGroup(name.ifBlank { "Audio ${index + 1}" }, listOf(embed)))
            }
            for (i in 0 until array.length()) {
                if (i in used) continue
                val embed = array.optString(i).trim()
                if (embed.startsWith("http")) {
                    groups.add(LanguageGroup("Audio ${i + 1}", listOf(embed)))
                }
            }
        } else {
            for ((index, name) in buttons) {
                val urls = urlsAt(array, index)
                if (urls.isEmpty()) continue
                used.add(index)
                groups.add(LanguageGroup(name.ifBlank { "Audio ${index + 1}" }, urls))
            }
            for (i in 0 until array.length()) {
                if (i in used) continue
                val urls = urlsAt(array, i)
                if (urls.isNotEmpty()) {
                    groups.add(LanguageGroup("Audio ${i + 1}", urls))
                }
            }
        }

        return groups
    }

    private fun urlsAt(array: JSONArray, index: Int): List<String> {
        val inner = array.optJSONArray(index)
        if (inner == null) {
            val single = array.optString(index).trim()
            return if (single.startsWith("http")) listOf(single) else emptyList()
        }
        val out = ArrayList<String>(inner.length())
        for (i in 0 until inner.length()) {
            val url = inner.optString(i).trim()
            if (url.startsWith("http")) out.add(url)
        }
        return out
    }

    /**
     * Resuelve un idioma. Lanza todos sus servidores a la vez y va emitiendo
     * el primero que responde de cada servicio hasta juntar dos. Byse se
     * arranca primero (es el más lento por el PoW), pero se cancela en cuanto
     * dos servidores rápidos ya dieron enlace.
     */
    private suspend fun resolveGroup(
        group: LanguageGroup,
        sourcePage: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int = coroutineScope {
        val label = prettyLang(group.name)
        val servers = group.embeds.map { embed ->
            async {
                fetchServers(embed, "$WEB/").map { (name, url) ->
                    ServerCandidate(name, url, embed)
                }
            }
        }.awaitAll().flatten()
        val ordered = orderServers(servers)
        if (ordered.isEmpty()) {
            log { "LINKS | embed sin servidores | $label" }
            return@coroutineScope 0
        }

        log {
            "LINKS SERVIDORES | $label | " +
                ordered.joinToString { it.name.ifBlank { hostOf(it.url) } }
        }

        val launchOrder = ordered.sortedBy { if (isByseUrl(it.url)) 0 else 1 }
        val channel = Channel<ServerResult?>(Channel.UNLIMITED)
        val jobs = launchOrder.map { candidate ->
            launch {
                val result = try {
                    if (isByseUrl(candidate.url)) {
                        // Un solo PoW a la vez: compite por CPU con el resto.
                        withTimeoutOrNull(BYSE_TIMEOUT_MS) {
                            BYSE_GATE.withPermit {
                                resolveServer(candidate, label, sourcePage, subtitleCallback)
                            }
                        }
                    } else {
                        withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                            SERVER_GATE.withPermit {
                                resolveServer(candidate, label, sourcePage, subtitleCallback)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Si fue un timeout interno, avisa con null para que el
                    // grupo no se quede esperando; si cancelaron todo, da igual.
                    channel.trySend(null)
                    return@launch
                } catch (e: Exception) {
                    log {
                        "LINKS ERROR | $label | ${hostOf(candidate.url)} | " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    }
                    null
                }
                channel.trySend(result)
            }
        }

        val usedServices = HashSet<String>()
        var count = 0
        var pending = jobs.size
        while (pending > 0 && count < MAX_LINKS_PER_LANGUAGE) {
            val result = channel.receive()
            pending--
            if (result == null) continue
            if (!usedServices.add(result.service)) continue
            if (!emitted.add(result.link.url)) continue
            callback(result.link)
            count++
            log { "LINKS OK | $label | ${result.service} | ${short(result.link.url)}" }
        }

        jobs.forEach { it.cancel() }
        channel.close()
        count
    }

    private suspend fun resolveServer(
        candidate: ServerCandidate,
        label: String,
        sourcePage: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): ServerResult? {
        val host = hostOf(candidate.url)

        if (isByseUrl(candidate.url)) {
            log { "LINKS BYSE | $label | ${short(candidate.url)}" }
            val link = resolveByse(candidate.url, label, sourcePage) ?: return null
            return ServerResult("byse", link)
        }

        var manualTried = false
        if (isFileLionsHost(host)) {
            // El extractor de la app devuelve hls3/hls2: uno está muerto y el
            // otro a veces no inicia. Se resuelve a mano y se prefiere hls4.
            log { "LINKS FILELIONS | $label | ${short(candidate.url)}" }
            val manual = tryManual(candidate.url, candidate.embed, host)
            manualTried = true
            if (manual != null) {
                return ServerResult(
                    "filelions",
                    jaraLink(label, "FileLions", manual.url, ExtractorLinkType.M3U8, manual.referer)
                )
            }
            log { "LINKS FILELIONS FAIL | $label | se prueba el extractor" }
        }

        val collected = ArrayList<ExtractorLink>()
        try {
            log {
                "LINKS EXTRACTOR | $label | ${candidate.name.ifBlank { host }} | " +
                    short(candidate.url)
            }
            loadExtractor(candidate.url, candidate.embed, subtitleCallback) { link ->
                collected.add(link)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log {
                "LINKS EXTRACTOR ERROR | $label | $host | " +
                    "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        val best = pickBestLink(collected)
        if (best != null) {
            val tagged = newExtractorLink(
                source = "AnimeJara",
                name = "AnimeJara · $label · ${best.name}",
                url = best.url,
                type = best.type
            ) {
                referer = best.referer
                headers = best.headers
                quality = best.quality
                extractorData = best.extractorData
            }
            return ServerResult(serviceKey(host), tagged)
        }

        if (!manualTried && MANUAL_HOSTS.any { host.contains(it, true) }) {
            val manual = tryManual(candidate.url, candidate.embed, host)
            if (manual != null) {
                return ServerResult(
                    serviceKey(host),
                    jaraLink(label, host, manual.url, ExtractorLinkType.M3U8, manual.referer)
                )
            }
        }

        log { "LINKS SIN RESULTADO | $label | $host" }
        return null
    }

    /** Normaliza el nombre del servicio para no repetir mirrors del mismo host. */
    private fun serviceKey(host: String): String {
        val clean = host.lowercase().removePrefix("www.")
        return when {
            clean.contains("filelions") || clean.contains("vidhide") ||
                clean.contains("streamwish") -> "filelions"
            clean.contains("uqload") -> "uqload"
            clean.contains("mxdrop") || clean.contains("mixdrop") -> "mxdrop"
            clean.contains("hgcloud") -> "hgcloud"
            clean.contains("voe") -> "voe"
            clean.contains("streamtape") -> "streamtape"
            clean.contains("mp4upload") -> "mp4upload"
            clean.contains("byse") -> "byse"
            else -> clean.substringBefore('.')
        }
    }

    private fun isFileLionsHost(host: String): Boolean {
        val clean = host.lowercase()
        return clean.contains("filelions") || clean.contains("vidhide") ||
            clean.contains("streamwish")
    }

    private fun isByseUrl(url: String): Boolean = BYSE_EMBED.containsMatchIn(url)

    private suspend fun jaraLink(
        label: String,
        sourceName: String,
        url: String,
        type: ExtractorLinkType,
        referer: String?
    ): ExtractorLink = newExtractorLink(
        source = "AnimeJara",
        name = "AnimeJara · $label · $sourceName",
        url = url,
        type = type
    ) {
        this.referer = referer.orEmpty()
        quality = Qualities.Unknown.value
        headers = mapOf(
            "User-Agent" to UA,
            "Referer" to referer.orEmpty()
        ).filterValues { it.isNotBlank() }
    }

    /** De varios enlaces del mismo extractor elige uno solo y de la mejor calidad. */
    private fun pickBestLink(links: List<ExtractorLink>): ExtractorLink? {
        val valid = links.filter { it.url.startsWith("http") }.distinctBy { it.url }
        if (valid.isEmpty()) return null
        return valid.maxWithOrNull(
            compareBy<ExtractorLink> {
                when (it.type) {
                    ExtractorLinkType.M3U8 -> 2
                    ExtractorLinkType.VIDEO -> 1
                    else -> 0
                }
            }.thenBy { it.quality }
        )
    }

    private suspend fun fetchServers(
        embedUrl: String,
        referer: String
    ): List<Pair<String, String>> {
        val response = try {
            app.get(
                embedUrl,
                headers = HEADERS + mapOf("Referer" to referer),
                timeout = 20L
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "LINKS | embed error | ${e.javaClass.simpleName}: ${e.message}" }
            return emptyList()
        }

        // Enlace -> nombre del servidor (sin duplicados, mantiene el orden).
        val found = LinkedHashMap<String, String>()

        for (li in response.document.select("li[onclick]")) {
            val onclick = li.attr("onclick")
            val serverUrl = Regex("""playVideo\(\s*["']([^"']+)["']\s*\)""")
                .find(onclick)?.groupValues?.get(1)?.trim().orEmpty()
            if (!serverUrl.startsWith("http")) continue
            val serverName = li.selectFirst(".nombre-server")?.text()?.trim().orEmpty()
            found.putIfAbsent(serverUrl, serverName)
        }

        if (found.isEmpty()) {
            val regex = Regex("""playVideo\(\s*(?:&quot;|")(.*?)(?:&quot;|")""")
            for (match in regex.findAll(response.text)) {
                val serverUrl = match.groupValues[1].trim()
                if (serverUrl.startsWith("http")) found.putIfAbsent(serverUrl, "")
            }
        }

        return found.entries.map { it.value to it.key }
    }

    private fun orderServers(servers: List<ServerCandidate>): List<ServerCandidate> {
        val filtered = ArrayList<ServerCandidate>()
        for (server in servers) {
            val host = hostOf(server.url)
            if (IGNORED_HOSTS.any { host.contains(it, true) }) {
                log { "LINKS | servidor omitido | $host" }
                continue
            }
            filtered.add(server)
        }
        return filtered.sortedBy { server ->
            val index = PRIORITY_HOSTS.indexOfFirst { server.url.contains(it, true) }
            if (index < 0) PRIORITY_HOSTS.size else index
        }
    }

    /**
     * Respaldo manual: pide la página del servidor, la desempaqueta si está
     * ofuscada y devuelve un único enlace de vídeo. Para FileLions/VidHide
     * prefiere hls4, que es el que usa su propio reproductor (hls3 suele ser
     * un host muerto y hls2 a veces no inicia).
     */
    private suspend fun tryManual(
        serverUrl: String,
        embedUrl: String,
        host: String
    ): ManualLink? {
        return try {
            val response = app.get(
                serverUrl,
                headers = HEADERS + mapOf("Referer" to embedUrl),
                timeout = 12L
            )
            val raw = response.text
            val unpacked = try {
                getAndUnpack(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                raw
            }

            val searchIn = if (unpacked.length > raw.length) "$raw\n$unpacked" else unpacked
            val finalPage = response.url.ifBlank { serverUrl }
            val stream = extractManualStream(searchIn, finalPage) ?: return null
            log { "LINKS MANUAL | $host | ${short(stream.url)}" }
            stream
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "LINKS MANUAL ERROR | $host | ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    private fun extractManualStream(script: String, base: String): ManualLink? {
        val linksBlock = Regex("""links\s*=\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.getOrNull(1)
        if (linksBlock != null) {
            val map = LinkedHashMap<String, String>()
            for (match in Regex(
                """["']?(hls\d+|hls|file|src)["']?\s*:\s*["']([^"']+)["']"""
            ).findAll(linksBlock)) {
                map[match.groupValues[1]] = match.groupValues[2]
            }
            for (key in listOf("hls4", "hls3", "hls2", "hls1", "hls", "file", "src")) {
                val value = map[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val url = resolveMediaUrl(base, value)
                log { "LINKS MANUAL PICK | $key | ${short(url)}" }
                return ManualLink(url, base)
            }
        }

        val candidates = ArrayList<String>()
        for (match in Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").findAll(script)) {
            candidates.add(match.groupValues[1])
        }
        for (match in Regex(
            """https?://[^"'\\\s]+?\.m3u8[^"'\\\s]*""",
            RegexOption.IGNORE_CASE
        ).findAll(script)) {
            candidates.add(match.value)
        }
        for (match in Regex(
            """["'](/[^"']+?\.(?:m3u8|txt)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(script)) {
            candidates.add(match.groupValues[1])
        }

        val chosen = candidates
            .map { it.replace("\\/", "/").trim() }
            .firstOrNull { value ->
                value.isNotEmpty() && !value.contains("links.", ignoreCase = true) &&
                    (value.startsWith("http") || value.startsWith("/")) &&
                    (value.contains(".m3u8", ignoreCase = true) ||
                        value.contains(".txt", ignoreCase = true) ||
                        value.contains("master", ignoreCase = true))
            } ?: return null
        return ManualLink(resolveMediaUrl(base, chosen), base)
    }

    private fun resolveMediaUrl(base: String, raw: String): String {
        val value = raw.trim().replace("\\/", "/")
        return try {
            URI(base).resolve(value).toString()
        } catch (e: Exception) {
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("/") -> {
                    val origin = runCatching {
                        URI(base).let { "${it.scheme}://${it.authority}" }
                    }.getOrNull() ?: WEB
                    origin + value
                }
                else -> value
            }
        }
    }

    /* ------------------------------------------------------------
     * Byse (bysekoze y mirrors): misma resolución manual que AnimeLHD
     * ------------------------------------------------------------ */

    private fun byseB64Decode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        return java.util.Base64.getDecoder()
            .decode(normalized + "=".repeat((4 - normalized.length % 4) % 4))
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
        val state = intArrayOf(
            1779033703,
            3144134277L.toInt(),
            1013904242,
            2773480762L.toInt()
        )
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

    /** PoW con varios hilos: en el teléfono tarda la mitad o menos. */
    private suspend fun solveBysePow(nonce: String, difficulty: Int): String? {
        if (difficulty < 0 || difficulty > 32) return null
        if (difficulty <= 0) return "0"
        val started = System.nanoTime()
        val counter = AtomicInteger(0)
        val found = AtomicBoolean(false)
        val solution = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 3)

        coroutineScope {
            (0 until workers).map {
                async(Dispatchers.Default) {
                    while (!found.get() && solution.get() == null) {
                        if ((System.nanoTime() - started) / 1_000_000 > 12_000) break
                        val current = counter.getAndIncrement()
                        if (current >= 300_000) break
                        if (byseLeadingZeroBits(byseWorkHash("$nonce:$current")) >= difficulty) {
                            if (found.compareAndSet(false, true)) {
                                solution.set(current.toString())
                            }
                            break
                        }
                        if (current % 256 == 0) yield()
                    }
                }
            }.awaitAll()
        }

        val value = solution.get()
        log {
            "BYSE PoW | bits=$difficulty | intentos=${counter.get()} | " +
                "ms=${(System.nanoTime() - started) / 1_000_000} | solucion=${value ?: "no"}"
        }
        return value
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
            repeat(count) {
                sequenceLength = (sequenceLength shl 8) or (der[offset++].toInt() and 0xff)
            }
        }
        if (der[offset++].toInt() != 0x02) error("firma ECDSA r inválida")
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        if (der[offset++].toInt() != 0x02) error("firma ECDSA s inválida")
        val sLength = der[offset++].toInt() and 0xff
        val s = der.copyOfRange(offset, offset + sLength)

        fun fit(bytes: ByteArray): ByteArray {
            val unsigned = if (bytes.size > 32 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
            return ByteArray(32).also { out ->
                val value = unsigned.takeLast(32).toByteArray()
                System.arraycopy(value, 0, out, 32 - value.size, value.size)
            }
        }
        return fit(r) + fit(s)
    }

    private suspend fun bysePostJson(
        url: String,
        headers: Map<String, String>,
        payload: JSONObject,
        timeoutSeconds: Long = 15L
    ): JSONObject {
        val response = app.post(
            url,
            headers = headers + mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            json = JsonAsString(payload.toString()),
            timeout = timeoutSeconds
        )
        if (response.code !in 200..299) error("HTTP ${response.code} ${URI(url).path}")
        return JSONObject(response.text)
    }

    private suspend fun resolveByse(
        embed: String,
        label: String,
        sourcePage: String
    ): ExtractorLink? {
        val input = runCatching { URI(embed) }.getOrNull() ?: return null
        val code = Regex("""/(?:e|embed|sovm9|boh)/([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(input.path.orEmpty())?.groupValues?.getOrNull(1) ?: return null
        val initialHeaders = mapOf("User-Agent" to BYSE_UA, "Referer" to sourcePage)

        try {
            val page = app.get(embed, headers = initialHeaders, timeout = 9)
            if (page.code !in 200..399) error("embed HTTP ${page.code}")
            val pageUrl = page.url.ifBlank { embed }
            val origin = runCatching {
                URI(pageUrl).let { "${it.scheme}://${it.authority}" }
            }.getOrDefault("${input.scheme}://${input.authority}")
            val pagePath = runCatching { URI(pageUrl).path.orEmpty() }
                .getOrDefault(input.path.orEmpty())
            val embedMode = Regex(
                """/(?:e|embed|sovm9|boh)/""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(pagePath)
            val apiPrefix = origin + "/api/videos/" + code + if (embedMode) "/embed" else ""
            val headers = initialHeaders + mapOf(
                "Origin" to origin,
                "Referer" to pageUrl,
                "X-Embed-Origin" to "https://mytsumi.com",
                "X-Embed-Referer" to "https://mytsumi.com/",
                "X-Embed-Parent" to embed
            )

            val settingsResponse = app.get(apiPrefix + "/settings", headers = headers, timeout = 10)
            if (settingsResponse.code !in 200..299) {
                error("settings HTTP ${settingsResponse.code}")
            }
            val settings = JSONObject(settingsResponse.text)
            val captchaRequired = settings.optBoolean("captcha_required", false)
            var fingerprint: JSONObject? = null
            var captchaToken = ""

            if (captchaRequired) {
                val challenge = bysePostJson(
                    origin + "/api/videos/access/challenge",
                    headers,
                    JSONObject(),
                    12
                )
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
                    .put("user_agent", BYSE_UA).put("architecture", "arm").put("bitness", "32")
                    .put("platform", "Android").put("platform_version", "6.0.1")
                    .put("model", "SM-G5510").put("ua_full_version", "59.0.3071.125")
                    .put(
                        "brand_full_versions",
                        JSONArray().put(JSONObject().put("brand", "SamsungBrowser").put("version", "7.2"))
                    )
                    .put("pixel_ratio", 1).put("screen_width", 1280).put("screen_height", 720)
                    .put("color_depth", 24).put("languages", JSONArray().put("en-US"))
                    .put("timezone", "America/New_York").put("hardware_concurrency", 4)
                    .put("device_memory", 2).put("touch_points", 1)
                    .put("canvas_hash", fpHash(random)).put("audio_hash", fpHash(random + 1))
                    .put("fonts_hash", fpHash(random + 3)).put("codecs_hash", fpHash(random + 4))
                    .put("media_devices", "ai1ao1vi3").put("pointer_type", "coarse")
                    .put("extra", JSONObject().put("vendor", "Samsung").put("appVersion", "7.2"))

                val jwk = JSONObject().put("kty", "EC").put("crv", "P-256")
                    .put("x", byseB64Encode(byseFixedUnsigned(publicKey.w.affineX)))
                    .put("y", byseB64Encode(byseFixedUnsigned(publicKey.w.affineY)))
                    .put("ext", true).put("key_ops", JSONArray().put("verify"))

                val attest = bysePostJson(
                    origin + "/api/videos/access/attest",
                    headers,
                    JSONObject().put("viewer_id", "").put("device_id", "")
                        .put("challenge_id", challengeId).put("nonce", nonce)
                        .put("signature", signature).put("public_key", jwk)
                        .put("client", client).put("storage", JSONObject())
                        .put("attributes", JSONObject().put("entropy", "high")),
                    18
                )
                val viewerId = attest.optString("viewer_id")
                val deviceId = attest.optString("device_id")
                if (attest.optString("token").isBlank() || viewerId.isBlank() || deviceId.isBlank()) {
                    error("atestación incompleta")
                }
                fingerprint = JSONObject().put("token", attest.optString("token"))
                    .put("viewer_id", viewerId).put("device_id", deviceId)
                if (attest.has("confidence")) {
                    fingerprint.put("confidence", attest.opt("confidence"))
                }

                val captcha = bysePostJson(
                    apiPrefix + "/captcha",
                    headers,
                    JSONObject().put("fingerprint", fingerprint),
                    12
                )
                val powNonce = captcha.optString("pow_nonce")
                val powToken = captcha.optString("pow_token")
                if (powNonce.isBlank() || powToken.isBlank()) error("desafío PoW incompleto")
                val solution = solveBysePow(powNonce, captcha.optInt("pow_difficulty", -1))
                    ?: error("PoW agotó el tiempo")
                val verified = bysePostJson(
                    apiPrefix + "/captcha/verify",
                    headers,
                    JSONObject().put("pow_token", powToken)
                        .put("solution", solution).put("fingerprint", fingerprint),
                    12
                )
                captchaToken = verified.optString("token")
                if (captchaToken.isBlank()) error("verificación CAPTCHA sin token")
            }

            val playbackHeaders = headers + if (captchaToken.isBlank()) {
                emptyMap()
            } else {
                mapOf("X-Captcha-Token" to captchaToken)
            }
            val playback = bysePostJson(
                apiPrefix + "/playback",
                playbackHeaders,
                if (captchaRequired) JSONObject().put("fingerprint", fingerprint) else JSONObject(),
                18
            )

            var sources = playback.optJSONArray("sources")
            if (sources == null) {
                val encrypted = playback.optJSONObject("playback")
                val parts = encrypted?.optJSONArray("key_parts")
                if (encrypted != null && parts != null && parts.length() > 0) {
                    var keys = (0 until parts.length())
                        .mapNotNull { parts.optString(it).takeIf(String::isNotBlank) }
                    val version = encrypted.optInt("version", 0)
                    if (version in 1..keys.size) {
                        keys = listOf(keys[version - 1], keys[keys.size - version])
                    }
                    val key = keys.flatMap { byseB64Decode(it).toList() }.toByteArray()
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.GCMParameterSpec(
                            128,
                            byseB64Decode(encrypted.optString("iv"))
                        )
                    )
                    sources = JSONObject(
                        String(
                            cipher.doFinal(byseB64Decode(encrypted.optString("payload"))),
                            Charsets.UTF_8
                        )
                    ).optJSONArray("sources")
                }
            }
            val sourceArray = sources ?: error("playback sin fuentes")
            if (sourceArray.length() == 0) error("playback sin fuentes")

            val choices = (0 until sourceArray.length()).mapNotNull { i ->
                val item = sourceArray.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                if (url.isBlank()) return@mapNotNull null
                val sourceLabel = item.optString("label", item.optString("name", "Auto"))
                val height = item.optInt("height", 0).takeIf { it in 144..4320 }
                    ?: Regex("""(\d{3,4})\s*[pP]?""")
                        .find(sourceLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
                Triple(sourceLabel, url, height)
            }.sortedByDescending { it.third }
            val chosen = choices.firstOrNull { it.second.startsWith("http", true) }
                ?: choices.firstOrNull()
                ?: error("fuentes sin URL")

            val streamUrl = URI(origin).resolve(chosen.second).toString()
            val streamHeaders = mapOf(
                "User-Agent" to BYSE_UA,
                "Referer" to pageUrl,
                "Origin" to origin,
                "X-Embed-Origin" to "https://mytsumi.com",
                "X-Embed-Referer" to "https://mytsumi.com/",
                "X-Embed-Parent" to embed
            )
            val isHls = Regex(
                """\.m3u8(?:$|[?#])""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(streamUrl)
            val isDash = Regex(
                """\.mpd(?:$|[?#])""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(streamUrl)

            log {
                "BYSE OK | $label | ${chosen.first} | calidad=${chosen.third} | " +
                    short(streamUrl)
            }
            return newExtractorLink(
                source = "AnimeJara",
                name = "AnimeJara · $label · Byse",
                url = streamUrl,
                type = when {
                    isHls -> ExtractorLinkType.M3U8
                    isDash -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = pageUrl
                quality = chosen.third
                this.headers = streamHeaders
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log {
                "BYSE ERROR | $label | ${input.host} | " +
                    "${e.javaClass.simpleName}: ${e.message}"
            }
            return null
        }
    }
}
