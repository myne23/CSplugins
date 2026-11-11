package com.anipm

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/* ====================================================================
 * Ani.Pm (ani.pm) - catálogo + reproducción
 *
 * El catálogo se lee de la API JSON pública del sitio (/api/anime/...).
 * La reproducción usa dos fuentes:
 *   1) beta.cno.to: HLS master directo (multi-audio y multi-sub, sin CF).
 *   2) embed.settlar.io: respaldo con la sesión que firma ani.pm.
 * ==================================================================== */

private const val DEBUG = true
private const val TAG = "AniPm"

private const val WEB = "https://ani.pm"
private const val SETTLAR = "https://embed.settlar.io"
private const val BETA_ZONE = "https://beta.cno.to"

// ani.pm y beta.cno.to rechazan (403) las peticiones sin un User-Agent de navegador.
private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val ANILIST_OFFSET = 1_000_000L

private fun log(msg: () -> String) {
    if (DEBUG) Log.d(TAG, msg())
}

private fun short(value: String, max: Int = 150): String =
    if (value.length <= max) value else value.take(max) + "…"

/** Lee un string seguro de un JSONObject (null si falta, es JSON null o está vacío). */
private fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return value.takeIf { it.isNotEmpty() && it != "null" }
}

private fun JSONObject.int(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = optInt(key, 0)
    return value.takeIf { it > 0 }
}

private fun JSONObject.array(key: String): List<JSONObject> {
    val arr = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<JSONObject>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { out.add(it) }
    }
    return out
}

private fun JSONObject.stringList(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val value = arr.optString(i).trim()
        if (value.isNotEmpty()) out.add(value)
    }
    return out
}

/** Referencia interna del plugin: https://ani.pm/x/<fuente>/<id>[/<ep>/<anilist>] */
private data class Ref(
    val source: String,
    val id: Long,
    val episode: Int? = null,
    val anilistId: Long? = null
)

private fun seriesUrl(source: String, id: Long): String = "$WEB/x/$source/$id"

private fun episodeUrl(source: String, id: Long, episode: Int, anilistId: Long?): String =
    "$WEB/x/$source/$id/$episode/${anilistId ?: 0}"

private fun parseRef(url: String): Ref? {
    val clean = url.trim()

    // Compatibilidad con referencias antiguas: "settlar|123" y también
    // "https://ani.pm/settlar|123" (versiones que aplican fixUrl a todo).
    if (clean.contains("|")) {
        val parts = clean.split("|")
        val head = parts.getOrNull(0)?.substringAfterLast('/')?.lowercase()
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Ref(
            source = if (head == "anilist") "anilist" else "settlar",
            id = id,
            episode = parts.getOrNull(2)?.toIntOrNull(),
            anilistId = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0 }
        )
    }

    val marker = "/x/"
    val index = clean.indexOf(marker)
    if (index < 0) return null
    val parts = clean.substring(index + marker.length).trim('/').split('/')
    val source = parts.getOrNull(0)?.lowercase() ?: return null
    if (source != "settlar" && source != "anilist") return null
    val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
    return Ref(
        source = source,
        id = id,
        episode = parts.getOrNull(2)?.toIntOrNull(),
        anilistId = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0 }
    )
}

// Contenido adulto que no queremos listar en el catálogo.
private val blockedGenres = setOf("hentai")

private fun isBlocked(item: JSONObject): Boolean {
    val genres = item.stringList("genres").map { it.lowercase() }
    val tags = item.stringList("tags").map { it.lowercase() }
    return genres.any { it in blockedGenres } || tags.any { it in blockedGenres }
}

class AniPmProvider : MainAPI() {

    override var mainUrl = WEB
    override var name = "Ani.Pm"
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf("inicio" to "Inicio")

    private val jsonHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Referer" to "$WEB/"
    )

    /* ------------------------------------------------------------
     * HTTP
     * ------------------------------------------------------------ */

    private suspend fun getJson(
        pathOrUrl: String,
        referer: String? = null
    ): JSONObject? {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$WEB$pathOrUrl"
        val headers = if (referer == null) jsonHeaders else jsonHeaders + mapOf("Referer" to referer)
        return try {
            val response = app.get(url, headers = headers, timeout = 30L)
            if (response.code !in 200..299) {
                log { "API FAIL | code=${response.code} | url=${short(url)}" }
                return null
            }
            JSONObject(response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "API ERROR | ${e.javaClass.simpleName}: ${e.message} | url=${short(url)}" }
            null
        }
    }

    private fun cover(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            else -> WEB + path
        }
    }

    /* ------------------------------------------------------------
     * Mapeo catálogo -> SearchResponse
     * ------------------------------------------------------------ */

    private fun parseItems(
        array: JSONArray?,
        defaultSource: String? = null,
        preferBanner: Boolean = false
    ): List<SearchResponse> {
        if (array == null) return emptyList()
        val out = ArrayList<SearchResponse>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            toSearch(item, defaultSource, preferBanner)?.let { out.add(it) }
        }
        return out
    }

    private fun toSearch(
        item: JSONObject,
        defaultSource: String?,
        preferBanner: Boolean,
        nameSuffix: String? = null
    ): SearchResponse? {
        if (isBlocked(item)) return null
        val rawTitle = item.str("title") ?: return null
        val title = if (nameSuffix != null) "$rawTitle · $nameSuffix" else rawTitle
        val anilistId = item.str("anilistId")?.toLongOrNull()
        val rawId = item.optLong("id", 0L)

        // En el feed de novedades el "id" viene desplazado con +1.000.000;
        // el anilistId siempre es el valor real de AniList.
        val source = item.str("source")
            ?: defaultSource
            ?: if (anilistId != null) "anilist" else "settlar"

        val id = when (source) {
            "anilist" -> anilistId ?: if (rawId >= ANILIST_OFFSET) rawId - ANILIST_OFFSET else rawId
            else -> rawId
        }
        if (id <= 0L) return null

        val typeRaw = (item.str("type") ?: "").uppercase()
        val tvType = when (typeRaw) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA" -> TvType.OVA
            else -> TvType.Anime
        }

        val poster = cover(item.str("poster"))
        val banner = cover(item.str("banner")) ?: cover(item.str("heroBackdrop"))
        val image = if (preferBanner) banner ?: poster else poster ?: banner
        val year = item.int("year")
        val url = seriesUrl(source, id)
        // En el feed de últimos episodios no hay contadores: usamos el número
        // del episodio publicado para la etiqueta de sub/dub.
        val latestEpisode = item.int("epNumber")
        val dubEpisodes = item.int("dubCount") ?: if (item.optBoolean("dub")) latestEpisode else null
        val subEpisodes = item.int("subCount") ?: if (item.optBoolean("sub")) latestEpisode else null

        return if (tvType == TvType.AnimeMovie) {
            newMovieSearchResponse(name = title, url = url, type = tvType, fix = false) {
                posterUrl = image
                this.year = year
            }
        } else {
            newAnimeSearchResponse(name = title, url = url, type = tvType, fix = false) {
                posterUrl = image
                this.year = year
                if (dubEpisodes != null) addDubStatus(DubStatus.Dubbed, dubEpisodes)
                if (subEpisodes != null) addDubStatus(DubStatus.Subbed, subEpisodes)
            }
        }
    }

    /* ------------------------------------------------------------
     * Home
     * ------------------------------------------------------------ */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

        val data = getJson("/api/anime/home-bootstrap")
        if (data == null) {
            log { "HOME FAIL | no se pudo leer home-bootstrap" }
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val sections = ArrayList<HomePageList>(4)

        val latest = ArrayList<SearchResponse>()
        for (item in data.array("latest")) {
            val ep = item.int("epNumber")
            val mapped = toSearch(
                item,
                defaultSource = "anilist",
                preferBanner = false,
                nameSuffix = if (ep != null) "Ep $ep" else null
            )
            if (mapped != null) latest.add(mapped)
        }
        if (latest.isNotEmpty()) {
            sections.add(HomePageList("Últimos episodios", latest))
        }

        val hero = parseItems(data.optJSONArray("hero"), null, preferBanner = true)
        if (hero.isNotEmpty()) {
            sections.add(HomePageList("Portada", hero, isHorizontalImages = true))
        }

        val trending = parseItems(data.optJSONArray("trending"))
        if (trending.isNotEmpty()) {
            sections.add(HomePageList("Tendencias", trending))
        }

        val top = parseItems(data.optJSONArray("top"))
        if (top.isNotEmpty()) {
            sections.add(HomePageList("Top anime", top))
        }

        log { "HOME OK | secciones=${sections.size} | hero=${hero.size} | trending=${trending.size} | top=${top.size} | latest=${latest.size}" }
        return newHomePageResponse(sections, hasNext = false)
    }

    /* ------------------------------------------------------------
     * Búsqueda
     * ------------------------------------------------------------ */

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val data = getJson("/api/anime/search?q=$encoded") ?: return emptyList()
        val items = parseItems(data.optJSONArray("items"))
        log { "SEARCH | query=$query | resultados=${items.size}" }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /* ------------------------------------------------------------
     * Detalle
     * ------------------------------------------------------------ */

    private suspend fun fetchSeries(source: String, id: Long): JSONObject? {
        val primary = when (source) {
            "anilist" -> "/api/anime/ani/$id?phase=core&routes=e4"
            else -> "/api/anime/series/$id?phase=core&routes=e4"
        }
        getJson(primary)?.takeIf { it.has("title") }?.let { return it }

        val alternatives = buildList {
            if (source == "anilist") {
                if (id >= ANILIST_OFFSET) add("/api/anime/ani/${id - ANILIST_OFFSET}?phase=core&routes=e4")
                add("/api/anime/series/$id?phase=core&routes=e4")
            } else {
                add("/api/anime/ani/$id?phase=core&routes=e4")
                if (id >= ANILIST_OFFSET) add("/api/anime/series/${id - ANILIST_OFFSET}?phase=core&routes=e4")
            }
        }
        for (path in alternatives) {
            getJson(path)?.takeIf { it.has("title") }?.let {
                log { "LOAD FALLBACK | source=$source id=$id | path=$path" }
                return it
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: run {
            log { "LOAD FAIL | referencia inválida | ${short(url)}" }
            return null
        }
        val source = ref.source
        val id = ref.id

        val data = fetchSeries(source, id) ?: run {
            log { "LOAD FAIL | source=$source id=$id" }
            return null
        }

        val title = data.str("title") ?: return null
        val anilistId = data.str("anilistId")?.toLongOrNull()
        val typeRaw = (data.str("type") ?: "").uppercase()
        val canonical = seriesUrl(source, id)

        val episodes = data.array("episodes")
            .mapNotNull { ep ->
                val number = ep.optInt("number", -1)
                if (number < 0) null else number to ep
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        log { "LOAD | title=$title | source=$source id=$id | tipo=$typeRaw | episodios=${episodes.size}" }

        if (typeRaw == "MOVIE") {
            val ep = episodes.firstOrNull()?.first ?: 1
            return newMovieLoadResponse(
                title,
                canonical,
                TvType.AnimeMovie,
                episodeUrl(source, id, ep, anilistId)
            ) {
                posterUrl = cover(data.str("poster"))
                backgroundPosterUrl = cover(data.str("banner"))
                plot = data.str("synopsis")
                year = data.int("year")
                tags = genres(data)
            }
        }

        val subEpisodes = ArrayList<Episode>(episodes.size)
        val dubEpisodes = ArrayList<Episode>()
        for ((number, ep) in episodes) {
            val episode = newEpisode(
                episodeUrl(source, id, number, anilistId),
                initializer = {
                    val epTitle = ep.str("title")
                        ?.takeIf { it != "Episode $number" && it != "Episodio $number" }
                    this.name = epTitle ?: "Episodio $number"
                    this.episode = number
                    this.season = 1
                    ep.str("description")?.let { this.description = it }
                    cover(ep.str("thumbnail"))?.let { this.posterUrl = it }
                    val runtime = ep.optInt("runtimeSeconds", 0)
                    if (runtime > 0) this.runTime = (runtime / 60).coerceAtLeast(1)
                }
            )
            val hasSub = ep.optBoolean("sub", true)
            val hasDub = ep.optBoolean("dub", false)
            when {
                hasSub -> subEpisodes.add(episode)
                !hasDub -> subEpisodes.add(episode)
            }
            if (hasDub) dubEpisodes.add(episode)
        }

        log { "LOAD IDIOMAS | title=$title | sub=${subEpisodes.size} | dub=${dubEpisodes.size}" }

        return newAnimeLoadResponse(title, canonical, TvType.Anime) {
            posterUrl = cover(data.str("poster"))
            backgroundPosterUrl = cover(data.str("banner"))
            plot = data.str("synopsis")
            year = data.int("year")
            tags = genres(data)
            showStatus = when (data.str("status")?.lowercase()) {
                "currently airing", "airing", "hiatus" -> ShowStatus.Ongoing
                "finished", "completed" -> ShowStatus.Completed
                else -> null
            }
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private fun genres(data: JSONObject): List<String> {
        val arr = data.optJSONArray("genres") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val tag = arr.optString(i).trim()
            if (tag.isNotEmpty()) out.add(tag)
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
        val ref = parseRef(data) ?: run {
            log { "LINKS FAIL | referencia inválida | ${short(data)}" }
            return false
        }
        val source = ref.source
        val id = ref.id
        val episode = ref.episode ?: 1
        var anilistId = ref.anilistId
        if (anilistId == null && source == "anilist") anilistId = id

        log { "LINKS | source=$source id=$id ep=$episode anilist=${anilistId ?: "-"}" }

        val emitted = LinkedHashSet<String>()

        // 1) Beta: HLS master directo, sin Cloudflare.
        if (anilistId != null && emitBeta(anilistId, episode, emitted, callback)) {
            return true
        }

        // 2) Settlar: respaldo firmado por ani.pm.
        if (emitSettlar(source, id, anilistId, episode, emitted, subtitleCallback, callback)) {
            return true
        }

        log { "LINKS FAIL | sin fuentes | source=$source id=$id ep=$episode" }
        return false
    }

    /** beta.cno.to: master HLS con audio y subtítulos en varios idiomas. */
    private suspend fun emitBeta(
        anilistId: Long,
        episode: Int,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = getJson("/api/anime/beta-server/$anilistId")
        if (info == null || !info.optBoolean("available")) {
            log { "BETA | no disponible | anilist=$anilistId" }
            return false
        }

        val path = info.str("path")
        if (path == null) {
            log { "BETA | sin path | anilist=$anilistId" }
            return false
        }

        val listed = info.optJSONArray("episodes")
        val listedOk = listed == null ||
            (0 until listed.length()).any { listed.optInt(it) == episode }
        if (!listedOk) {
            log { "BETA | episodio $episode no listado | anilist=$anilistId" }
            return false
        }

        val origin = (info.str("origin") ?: BETA_ZONE).trimEnd('/')
        val master = buildBetaMaster(origin, path, episode) ?: run {
            log { "BETA | no se pudo construir master | path=$path" }
            return false
        }

        if (!verifyMaster(master, "$origin/")) {
            log { "BETA | master inválido | ${short(master)}" }
            return false
        }

        if (!emitted.add(master)) return false
        callback(newExtractorLink(
            source = this@AniPmProvider.name,
            name = "Ani.Pm · Beta (multi-audio)",
            url = master,
            type = ExtractorLinkType.M3U8
        ) {
            referer = "$origin/"
            quality = Qualities.Unknown.value
            headers = mapOf("User-Agent" to UA, "Accept" to "*/*")
        })
        log { "BETA OK | ep=$episode | ${short(master)}" }
        return true
    }

    private fun buildBetaMaster(origin: String, watchPath: String, episode: Int): String? {
        val tail = watchPath.trimEnd('/').substringAfterLast('/')
        val split = tail.lastIndexOf('-')
        if (split <= 0 || split >= tail.length - 1) return null
        val slug = tail.substring(0, split)
        val betaId = tail.substring(split + 1)
        if (slug.isBlank() || betaId.toLongOrNull() == null) return null
        val ep = String.format(java.util.Locale.US, "E%04d", episode)
        return "$origin/hls/$betaId-$slug/$ep/master.m3u8"
    }

    private suspend fun verifyMaster(url: String, referer: String): Boolean {
        return try {
            val response = app.get(
                url,
                headers = mapOf("User-Agent" to UA, "Accept" to "*/*", "Referer" to referer),
                timeout = 20L
            )
            response.code in 200..299 && response.text.trimStart().startsWith("#EXTM3U")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "VERIFY ERROR | ${e.javaClass.simpleName}: ${e.message}" }
            false
        }
    }

    private suspend fun emitSettlar(
        source: String,
        id: Long,
        anilistId: Long?,
        episode: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bootstraps = LinkedHashSet<String>()
        if (source == "anilist") {
            bootstraps.add("/api/anime/playback-bootstrap/anilist/$id")
        } else {
            bootstraps.add("/api/anime/playback-bootstrap/settlar/$id")
        }
        if (anilistId != null && anilistId != id) {
            bootstraps.add("/api/anime/playback-bootstrap/anilist/$anilistId")
        }
        if (source != "settlar") {
            bootstraps.add("/api/anime/playback-bootstrap/settlar/$id")
        }

        var found = false
        for (channel in listOf("sub", "dub")) {
            for (base in bootstraps) {
                val bootstrap = getJson("$base?ep=$episode&lang=$channel&backup=1") ?: continue
                val selection = bootstrap.str("settlarSelection") ?: continue
                if (resolveSettlar(selection, episode, channel, emitted, subtitleCallback, callback)) {
                    found = true
                }
                break
            }
        }
        return found
    }

    private suspend fun resolveSettlar(
        selection: String,
        episode: Int,
        channel: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val selectionEncoded = URLEncoder.encode(selection, "UTF-8")
        val session = getJson(
            "$WEB/api/anime/settlar/session?selection=$selectionEncoded&provider=anipm&ep=$episode&channel=$channel&telemetry=0"
        ) ?: run {
            log { "SETTLAR | sin sesión | canal=$channel" }
            return false
        }

        val embedUrl = session.str("embedUrl") ?: run {
            log { "SETTLAR | sesión sin embedUrl | canal=$channel" }
            return false
        }
        val token = Regex("""[?&]t=([^&]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: run {
            log { "SETTLAR | embedUrl sin token | ${short(embedUrl)}" }
            return false
        }

        val embed = getJson(
            "$SETTLAR/api/embed/session?t=$token",
            referer = "$SETTLAR/embed/v1"
        ) ?: run {
            log { "SETTLAR | no se pudo leer la sesión del embed (posible bloqueo Cloudflare) | canal=$channel" }
            return false
        }

        val stream = embed.str("source") ?: run {
            log { "SETTLAR | embed sin source | canal=$channel" }
            return false
        }
        if (!stream.startsWith("http")) return false
        if (!emitted.add(stream)) return false

        val label = if (channel == "dub") "Ani.Pm · Dub" else "Ani.Pm · Sub"
        callback(newExtractorLink(
            source = this@AniPmProvider.name,
            name = label,
            url = stream,
            type = ExtractorLinkType.M3U8
        ) {
            referer = "$SETTLAR/"
            quality = Qualities.Unknown.value
            headers = mapOf("User-Agent" to UA, "Accept" to "*/*")
        })

        val subtitles = embed.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val subUrl = sub.str("url") ?: continue
                val subLabel = sub.str("label") ?: "Subtítulos"
                subtitleCallback(newSubtitleFile(subLabel, subUrl))
            }
        }

        log { "SETTLAR OK | canal=$channel | ${short(stream)}" }
        return true
    }
}
