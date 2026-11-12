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
 * streamhj.top que lista servidores externos; esos servidores se resuelven
 * con los extractores de CloudStream y, si fallan, con un respaldo manual
 * que busca un .m3u8 en el JS de la página.
 * ==================================================================== */

private const val DEBUG = true
private const val TAG = "AnimeJara"

private const val WEB = "https://animejara.com"
private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** Máximo de enlaces que se emiten por idioma (para no tardar demasiado). */
private const val MAX_LINKS_PER_LANGUAGE = 2

/** Tiempo máximo aproximado resolviendo enlaces (ms). */
private const val LINKS_BUDGET_MS = 28_000L

private val HEADERS = mapOf(
    "User-Agent" to UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
)

/** Servidores que se intentan primero (los más estables en la app). */
private val PRIORITY_HOSTS = listOf(
    "filelions", "mxdrop", "uqload", "voe.sx", "susanexpectcare",
    "hgcloud", "streamtape", "mp4upload"
)

/** Hosts que la app no sabe resolver o que requieren captcha/PoW. */
private val IGNORED_HOSTS = listOf("bysekoze", "savefiles")

/** Hosts donde vale la pena buscar un .m3u8 a mano. */
private val MANUAL_HOSTS = listOf(
    "uqload", "filelions", "vidhide", "streamwish", "hgcloud", "voe"
)

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
    val poster: String?,
    val date: String?,
    val languages: List<String>
)

private data class LanguageGroup(
    val name: String,
    val embeds: List<String>
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

        val title = el.selectFirst("h3.card-title")?.text()?.trim().orEmpty()
            .ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null

        val poster = imageUrl(el.selectFirst("img"))
        val rating = el.selectFirst(".badge-rating")?.text()
            ?.replace("★", "")?.trim()?.toDoubleOrNull()
        val badge = el.selectFirst(".badge-season")?.text()?.trim().orEmpty()
        val isMovie = href.contains("/movie/") || badge.contains("PELICULA", true)
        val scoreValue = rating?.let { Score.from10(it * 2.0) }

        if (isMovie) {
            return newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                posterUrl = poster
                this.score = scoreValue
            }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            this.score = scoreValue
            when (languageHint?.lowercase()) {
                "latino", "castellano" -> addDubStatus(DubStatus.Dubbed)
                "japones" -> addDubStatus(DubStatus.Subbed)
            }
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
                ep.poster?.let { this.posterUrl = absolute(it) }
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
                        poster = ep.optString("poster_episodio").trim().takeIf { it.isNotEmpty() },
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

        val deadline = System.currentTimeMillis() + LINKS_BUDGET_MS

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

            val emitted = LinkedHashSet<String>()
            var total = 0
            for (group in groups) {
                if (System.currentTimeMillis() > deadline) {
                    log { "LINKS | tiempo agotado antes de ${prettyLang(group.name)}" }
                    break
                }
                total += resolveGroup(group, emitted, subtitleCallback, callback)
            }

            log { "LINKS FIN | emitidos=$total | ${short(url)}" }
            total > 0
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

    private suspend fun resolveGroup(
        group: LanguageGroup,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val label = prettyLang(group.name)
        var count = 0

        for (embed in group.embeds) {
            if (count >= MAX_LINKS_PER_LANGUAGE) break

            val servers = fetchServers(embed)
            if (servers.isEmpty()) {
                log { "LINKS | embed sin servidores | $label | ${short(embed)}" }
                continue
            }

            val ordered = orderServers(servers)
            log {
                "LINKS SERVIDORES | $label | " +
                    ordered.joinToString { it.first.ifBlank { hostOf(it.second) } }
            }

            for ((serverName, serverUrl) in ordered) {
                if (count >= MAX_LINKS_PER_LANGUAGE) break

                val host = hostOf(serverUrl)
                val collected = ArrayList<ExtractorLink>()
                try {
                    log {
                        "LINKS EXTRACTOR | $label | ${serverName.ifBlank { host }} | " +
                            short(serverUrl)
                    }
                    loadExtractor(serverUrl, embed, subtitleCallback) { link ->
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

                if (collected.isNotEmpty()) {
                    for (link in collected) {
                        if (count >= MAX_LINKS_PER_LANGUAGE) break
                        if (!emitted.add(link.url)) continue
                        val tagged = newExtractorLink(
                            source = "AnimeJara",
                            name = "AnimeJara · $label · ${link.name}",
                            url = link.url,
                            type = link.type
                        ) {
                            referer = link.referer
                            headers = link.headers
                            quality = link.quality
                            extractorData = link.extractorData
                        }
                        callback(tagged)
                        count++
                        log { "LINKS OK | $label | ${link.name} | ${short(link.url)}" }
                    }
                    continue
                }

                // Respaldo manual: descarga la página del host y busca un .m3u8
                // dentro del JS empaquetado (Dean Edwards). Sirve sobre todo
                // cuando el extractor de la app no conoce el mirror actual.
                if (MANUAL_HOSTS.none { host.contains(it, true) }) {
                    log { "LINKS SIN RESULTADO | $label | $host" }
                    continue
                }

                val manual = tryManual(serverUrl, embed, host)
                if (manual == null) {
                    log { "LINKS SIN RESULTADO | $label | $host" }
                    continue
                }
                if (!emitted.add(manual.url)) continue

                callback(newExtractorLink(
                    source = "AnimeJara",
                    name = "AnimeJara · $label · $host",
                    url = manual.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = manual.referer
                    quality = Qualities.Unknown.value
                    headers = HEADERS
                })
                count++
                log { "LINKS MANUAL OK | $label | $host | ${short(manual.url)}" }
            }
        }

        return count
    }

    private suspend fun fetchServers(embedUrl: String): List<Pair<String, String>> {
        val response = try {
            app.get(
                embedUrl,
                headers = HEADERS + mapOf("Referer" to "$WEB/"),
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

    private fun orderServers(servers: List<Pair<String, String>>): List<Pair<String, String>> {
        val filtered = ArrayList<Pair<String, String>>()
        for (server in servers) {
            val host = hostOf(server.second)
            if (IGNORED_HOSTS.any { host.contains(it, true) }) {
                log { "LINKS | servidor omitido | $host" }
                continue
            }
            filtered.add(server)
        }
        return filtered.sortedBy { (_, url) ->
            val index = PRIORITY_HOSTS.indexOfFirst { url.contains(it, true) }
            if (index < 0) PRIORITY_HOSTS.size else index
        }
    }

    /**
     * Respaldo manual: pide la página del servidor, la desempaqueta si está
     * ofuscada y devuelve el primer .m3u8 que aparezca.
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
                timeout = 15L
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
            val regex = Regex("""https?://[^"'\\\s]+?\.m3u8[^"'\\\s]*""", RegexOption.IGNORE_CASE)
            val stream = regex.findAll(searchIn)
                .map { it.value.replace("\\/", "/") }
                .firstOrNull { it.startsWith("http") }
                ?: return null

            val finalHost = hostOf(response.url.ifBlank { serverUrl })
            log { "LINKS MANUAL | $host | ${short(stream)}" }
            ManualLink(stream, "https://$finalHost/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "LINKS MANUAL ERROR | $host | ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }
}
