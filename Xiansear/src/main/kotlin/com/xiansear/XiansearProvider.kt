package com.xiansear

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private const val WEB = "https://www.xiansear.net"
private const val TAG = "Xiansear"
private const val PAGE_SIZE = 60
private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

private val HEADERS = mapOf(
    "User-Agent" to UA,
    "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
)

private fun log(message: String) {
    Log.d(TAG, message)
}

private fun short(url: String): String =
    if (url.length <= 140) url else url.take(137) + "..."

private data class RawEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String?
)

private data class PlayerSource(
    val label: String,
    val url: String,
    val kind: String,
    val count: Int
)

private data class EpisodeInfo(
    val id: String?,
    val sources: List<PlayerSource>,
    val title: String?,
    val poster: String?,
    val plot: String?
)

private data class LinkSpec(
    val label: String,
    val url: String,
    val type: ExtractorLinkType,
    val headers: Map<String, String>
)

private data class SubtitleSpec(
    val label: String,
    val url: String
)

private data class ResolvedSources(
    val links: List<LinkSpec>,
    val subtitles: List<SubtitleSpec>
)

class XiansearProvider : MainAPI() {

    override var mainUrl = WEB
    override var name = "Xiansear"
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
        "episodios" to "Últimos episodios",
        "catalogo" to "Catálogo",
        "todos" to "Todos (A-Z)"
    )

    private var catalogCache: List<SearchResponse>? = null
    private var donghuaCache: List<SearchResponse>? = null
    private val episodeCache = ConcurrentHashMap<String, EpisodeInfo>()

    /* ------------------------------------------------------------
     * HTTP
     * ------------------------------------------------------------ */

    private suspend fun get(url: String, referer: String? = null): String? {
        return try {
            val headers = if (referer.isNullOrBlank()) HEADERS else HEADERS + mapOf("Referer" to referer)
            val response = app.get(url, headers = headers, timeout = 30L)
            if (response.code !in 200..299) {
                log("GET | HTTP ${response.code} | ${short(url)}")
            }
            response.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("GET ERROR | ${e.javaClass.simpleName}: ${e.message} | ${short(url)}")
            null
        }
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document? {
        val html = get(url, referer) ?: return null
        return try {
            Jsoup.parse(html, url)
        } catch (e: Exception) {
            log("PARSE ERROR | ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /* ------------------------------------------------------------
     * Portada
     * ------------------------------------------------------------ */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val safePage = page.coerceAtLeast(1)
        return when (request.data) {
            "inicio" -> {
                if (safePage > 1) newHomePageResponse(request.name, emptyList(), hasNext = false)
                else homePage()
            }
            "episodios" -> episodesPage(safePage, request)
            "catalogo" -> catalogPage(safePage, request)
            "todos" -> allPage(safePage, request)
            else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    /**
     * La portada ya trae los últimos episodios y las series en tendencia.
     * Una sola petición mantiene la carga rápida en el teléfono.
     */
    private suspend fun homePage(): HomePageResponse {
        val doc = getDoc("$WEB/", "$WEB/")
            ?: return newHomePageResponse(emptyList(), hasNext = false)

        val episodes = parseEpisodeCards(doc)
        val series = parseSeriesCards(doc)

        val sections = ArrayList<HomePageList>()
        if (episodes.isNotEmpty()) {
            sections.add(HomePageList("Últimos episodios", episodes))
        }
        if (series.isNotEmpty()) {
            sections.add(HomePageList("Tendencias", series.take(30)))
        }

        log("HOME | episodios=${episodes.size} | tendencias=${series.size}")
        return newHomePageResponse(sections, hasNext = false)
    }

    private suspend fun episodesPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$WEB/episodios" else "$WEB/episodios?page=$page"
        val doc = getDoc(url, "$WEB/")
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = parseEpisodeCards(doc)
        val maxPage = Regex("""/episodios\?page=(\d+)""")
            .findAll(doc.html())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: page

        val hasNext = items.isNotEmpty() && page < maxPage
        log("EPISODIOS | pagina=$page | items=${items.size} | max=$maxPage")
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private suspend fun catalogPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = allCatalog()
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private suspend fun allPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = allDonghuas()
        val from = (page - 1) * PAGE_SIZE
        if (from >= items.size) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val to = minOf(from + PAGE_SIZE, items.size)
        return newHomePageResponse(
            request.name,
            items.subList(from, to),
            hasNext = to < items.size
        )
    }

    private suspend fun allCatalog(): List<SearchResponse> {
        catalogCache?.let { return it }
        val doc = getDoc("$WEB/catalogo", "$WEB/") ?: return emptyList()
        val items = parseSeriesCards(doc)
        if (items.isNotEmpty()) catalogCache = items
        log("CATALOGO | series=${items.size}")
        return items
    }

    private suspend fun allDonghuas(): List<SearchResponse> {
        donghuaCache?.let { return it }
        val doc = getDoc("$WEB/donghuas", "$WEB/") ?: return emptyList()
        val items = parseSeriesCards(doc)
        if (items.isNotEmpty()) donghuaCache = items
        log("DONGHUAS | series=${items.size}")
        return items
    }

    /* ------------------------------------------------------------
     * Buscador
     * ------------------------------------------------------------ */

    override suspend fun search(query: String): List<SearchResponse>? {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val body = get("$WEB/api/search?q=$encoded", "$WEB/") ?: return emptyList()
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            log("SEARCH JSON | ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }

        val results = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val type = item.optString("contentType").trim().lowercase()
            if (type == "novela") continue

            val title = item.optString("seriesName").trim()
            val path = item.optString("url").trim()
            if (title.isEmpty() || path.isEmpty()) continue

            val href = absolute(path) ?: continue
            val poster = absolute(item.optString("iconUrl"))

            if (type == "pelicula") {
                out.add(newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                    posterUrl = poster
                })
            } else {
                out.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    posterUrl = poster
                })
            }
        }

        log("SEARCH | q=$clean | resultados=${out.size}")
        return out
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /* ------------------------------------------------------------
     * Ficha de serie / película
     * ------------------------------------------------------------ */

    override suspend fun load(url: String): LoadResponse? {
        val target = url.substringBefore('#').trim()
        if (target.isEmpty()) return null

        if (target.contains("/ver/donghua/")) {
            return loadEpisode(target)
        }
        if (!target.contains("/serie/")) return null

        val doc = getDoc(target, "$WEB/") ?: return null
        val objects = ldObjects(doc)
        val movieObj = objects.firstOrNull { typeOf(it).equals("Movie", true) }
        val seriesObj = objects.firstOrNull { typeOf(it).equals("TVSeries", true) }
        val meta = movieObj ?: seriesObj ?: return null
        val isMovie = movieObj != null

        val title = meta.optString("name").trim()
            .ifBlank { doc.title().substringBefore(" · ").trim() }
        if (title.isBlank()) return null

        val poster = absolute(meta.optString("image"))
        val plot = meta.optString("description").trim().takeIf { it.isNotEmpty() }
        val tags = stringsOf(meta.opt("genre"))
        val slug = target.substringAfter("/serie/", "")
            .substringBefore('/')
            .substringBefore('?')
            .trim()
        if (slug.isBlank()) return null

        val flight = extractFlight(doc.html())
        val rows = parseRows(flight).sortedWith(compareBy({ it.season }, { it.number }))
        val status = statusOf(doc)

        log("LOAD | $title | movie=$isMovie | episodios=${rows.size} | slug=$slug")

        if (isMovie) {
            val movieEpisodeUrl = rows.firstOrNull()?.let { episodeUrl(slug, it) }
                ?: firstEpisodeHref(doc)
                ?: return null
            return newMovieLoadResponse(title, target, TvType.AnimeMovie, movieEpisodeUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plot
                tags = tags
            }
        }

        val episodes = rows.map { row ->
            newEpisode(episodeUrl(slug, row), initializer = {
                this.name = episodeName(title, row)
                this.episode = row.number
                this.season = row.season
            })
        }

        return newAnimeLoadResponse(title, target, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = plot
            tags = tags
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun loadEpisode(target: String): LoadResponse? {
        val info = episodeData(target) ?: return null
        val title = info.title ?: return null
        return newMovieLoadResponse(title, target, TvType.AnimeMovie, target) {
            posterUrl = info.poster
            backgroundPosterUrl = info.poster
            plot = info.plot
        }
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
        val episodeUrl = resolveEpisodeUrl(data) ?: run {
            log("LINKS | sin URL de episodio | ${short(data)}")
            return false
        }
        val info = episodeData(episodeUrl) ?: run {
            log("LINKS | sin datos de episodio | ${short(episodeUrl)}")
            return false
        }

        val sources = info.sources
        val episodeId = info.id
        val wantsWyvern = sources.any { it.kind.equals("wyvern", true) } || sources.isEmpty()
        val wantsRumble = sources.any { it.url.contains("rumble", true) } || sources.isEmpty()
        val dailymotionIds = sources
            .filter { it.url.contains("dailymotion", true) || it.url.contains("dai.ly", true) }
            .mapNotNull { dailymotionId(it.url) }
            .distinct()
        val okRuUrls = sources
            .filter { it.url.contains("ok.ru", true) || it.url.contains("okru", true) }
            .map { it.url }
            .distinct()
        val wyvernCount = sources.firstOrNull { it.kind.equals("wyvern", true) }?.count ?: 1

        val resolved = coroutineScope {
            val jobs = ArrayList<Deferred<ResolvedSources>>()
            if (episodeId != null && wantsWyvern) {
                jobs.add(async { resolveWyvern(episodeId, wyvernCount) })
            }
            if (episodeId != null && wantsRumble) {
                jobs.add(async { resolveRumble(episodeId) })
            }
            dailymotionIds.forEach { id ->
                jobs.add(async { resolveDailymotion(id) })
            }
            okRuUrls.forEach { url ->
                jobs.add(async { resolveOkRu(url, episodeUrl) })
            }
            jobs.awaitAll()
        }

        val seenLinks = HashSet<String>()
        val seenSubtitles = HashSet<String>()
        var emitted = 0

        for (result in resolved) {
            for (subtitle in result.subtitles) {
                if (seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(newSubtitleFile(subtitle.label, subtitle.url) {
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "$WEB/"
                        )
                    })
                    log("SUB | ${subtitle.label} | ${short(subtitle.url)}")
                }
            }
            for (link in result.links) {
                if (!link.url.startsWith("http") || !seenLinks.add(link.url)) continue
                callback(newExtractorLink(
                    source = name,
                    name = "$name · ${link.label}",
                    url = link.url,
                    type = link.type
                ) {
                    referer = "$WEB/"
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to UA) + link.headers
                })
                emitted++
                log("LINK | ${link.label} | ${short(link.url)}")
            }
        }

        // Servidores iframe que no tienen resolución propia aquí.
        for (source in sources) {
            val lower = source.url.lowercase()
            if (source.kind.equals("wyvern", true)) continue
            if (lower.contains("rumble") || lower.contains("dailymotion") || lower.contains("dai.ly")) continue
            if (lower.contains("ok.ru") || lower.contains("okru")) continue
            if (!source.url.startsWith("http")) continue
            val collected = ArrayList<ExtractorLink>()
            try {
                loadExtractor(source.url, episodeUrl, subtitleCallback) { link ->
                    if (link.url.startsWith("http") && seenLinks.add(link.url)) {
                        collected.add(link)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("EXTRACTOR ERROR | ${short(source.url)} | ${e.javaClass.simpleName}: ${e.message}")
            }
            for (link in collected) {
                callback(newExtractorLink(
                    source = name,
                    name = "$name · ${link.name.ifBlank { source.label }}",
                    url = link.url,
                    type = link.type
                ) {
                    referer = link.referer
                    quality = link.quality
                    headers = link.headers
                })
                emitted++
            }
        }

        // Último recurso: si el proxy de Rumble falló, probar el embed.
        if (emitted == 0) {
            val rumble = sources.firstOrNull { it.url.contains("rumble", true) }?.url
            if (!rumble.isNullOrBlank()) {
                val collected = ArrayList<ExtractorLink>()
                try {
                    loadExtractor(rumble, episodeUrl, subtitleCallback) { link ->
                        if (link.url.startsWith("http") && seenLinks.add(link.url)) {
                            collected.add(link)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("RUMBLE FALLBACK ERROR | ${e.javaClass.simpleName}: ${e.message}")
                }
                for (link in collected) {
                    callback(newExtractorLink(
                        source = name,
                        name = "$name · Rumble",
                        url = link.url,
                        type = link.type
                    ) {
                        referer = link.referer
                        quality = link.quality
                        headers = link.headers
                    })
                    emitted++
                }
            }
        }

        log("LINKS | emitidos=$emitted | ${short(episodeUrl)}")
        return emitted > 0
    }

    private suspend fun resolveEpisodeUrl(data: String): String? {
        val target = data.substringBefore('#').trim()
        if (target.contains("/ver/donghua/")) return target
        if (!target.contains("/serie/")) return null

        val doc = getDoc(target, "$WEB/") ?: return null
        val slug = target.substringAfter("/serie/", "")
            .substringBefore('/')
            .substringBefore('?')
            .trim()
        if (slug.isBlank()) return null

        val rows = parseRows(extractFlight(doc.html()))
        val first = rows.minWithOrNull(compareBy({ it.season }, { it.number })) ?: return null
        return episodeUrl(slug, first)
    }

    /**
     * La página del episodio pesa bastante; se guarda solo lo necesario para
     * que abrir el reproductor no vuelva a descargarla.
     */
    private suspend fun episodeData(url: String): EpisodeInfo? {
        episodeCache[url]?.let { return it }

        val html = get(url, "$WEB/") ?: return null
        val flight = extractFlight(html)
        val doc = try {
            Jsoup.parse(html, url)
        } catch (_: Exception) {
            null
        }
        val episodeObj = doc?.let { document ->
            ldObjects(document).firstOrNull {
                val type = typeOf(it)
                type.equals("VideoObject", true) || type.equals("TVEpisode", true)
            }
        }

        val id = Regex("\"episodeId\":\"([0-9a-fA-F-]{36})\"")
            .find(flight)?.groupValues?.getOrNull(1)
        val sources = parseSources(flight)
        val title = cleanTitle(jsonStringField(flight, "episodeTitle"))
            ?: cleanTitle(episodeObj?.optString("name"))
        val poster = absolute(jsonStringField(flight, "posterUrl"))
            ?: absolute(firstString(episodeObj?.opt("thumbnailUrl")))
            ?: absolute(firstString(episodeObj?.opt("image")))
        val plot = jsonStringField(flight, "synopsisShort")?.trim()?.takeIf { it.isNotEmpty() }
            ?: episodeObj?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }

        if (id == null && sources.isEmpty() && title == null) return null

        val info = EpisodeInfo(id, sources, title, poster, plot)
        if (episodeCache.size > 16) episodeCache.clear()
        episodeCache[url] = info
        return info
    }

    private suspend fun resolveWyvern(episodeId: String, count: Int): ResolvedSources {
        val links = ArrayList<LinkSpec>()
        val subtitles = ArrayList<SubtitleSpec>()
        val total = count.coerceIn(1, 6)

        for (i in 0 until total) {
            try {
                val body = get("$WEB/api/wyvern/meta?episodeId=$episodeId&i=$i", "$WEB/")
                    ?: continue
                val obj = JSONObject(body)
                if (!obj.optBoolean("ok", true)) continue

                val hls = obj.optString("hls").trim()
                if (hls.isNotEmpty()) {
                    absolute(hls)?.let { streamUrl ->
                        val title = obj.optString("title").trim()
                        val label = if (total > 1) {
                            "Wyvern ${title.ifBlank { "v${i + 1}" }}"
                        } else {
                            "Wyvern"
                        }
                        links.add(LinkSpec(
                            label = label,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf("Referer" to "$WEB/", "Origin" to WEB)
                        ))
                    }
                }

                val subtitleArray = obj.optJSONArray("subtitles")
                if (subtitleArray != null) {
                    for (j in 0 until subtitleArray.length()) {
                        val sub = subtitleArray.optJSONObject(j) ?: continue
                        val subUrl = absolute(sub.optString("src")) ?: continue
                        val label = sub.optString("label").trim().ifBlank { "Español" }
                        subtitles.add(SubtitleSpec(label, subUrl))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("WYVERN ERROR | i=$i | ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return ResolvedSources(links, subtitles)
    }

    private suspend fun resolveRumble(episodeId: String): ResolvedSources {
        val links = ArrayList<LinkSpec>()
        try {
            val body = get("$WEB/api/rumble/proxy?episodeId=$episodeId", "$WEB/")
                ?: return ResolvedSources(emptyList(), emptyList())
            val obj = JSONObject(body)
            val u = obj.optJSONObject("u")
            val hls = u?.optJSONObject("hls")

            val master = hls?.optJSONObject("auto")?.optString("url")?.trim().orEmpty()
                .ifBlank { hls?.optString("url")?.trim().orEmpty() }

            if (master.isNotBlank()) {
                links.add(LinkSpec(
                    label = "Rumble",
                    url = master,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to "https://rumble.com/", "Origin" to "https://rumble.com")
                ))
            } else {
                val variants = u?.optJSONObject("ua")?.optJSONObject("tar")
                if (variants != null) {
                    val best = variants.keys().asSequence()
                        .mapNotNull { key ->
                            key.toIntOrNull()?.let { quality ->
                                quality to variants.optJSONObject(key)?.optString("url").orEmpty()
                            }
                        }
                        .filter { it.second.isNotBlank() }
                        .maxByOrNull { it.first }
                    if (best != null) {
                        links.add(LinkSpec(
                            label = "Rumble ${best.first}p",
                            url = best.second,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf("Referer" to "https://rumble.com/")
                        ))
                    }
                }
                if (links.isEmpty()) {
                    val tar = u?.optJSONObject("tar")?.optString("url").orEmpty()
                    if (tar.isNotBlank()) {
                        links.add(LinkSpec("Rumble", tar, ExtractorLinkType.M3U8, emptyMap()))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("RUMBLE ERROR | ${e.javaClass.simpleName}: ${e.message}")
        }
        return ResolvedSources(links, emptyList())
    }

    private suspend fun resolveDailymotion(id: String): ResolvedSources {
        val links = ArrayList<LinkSpec>()
        try {
            val headers = mapOf(
                "Accept" to "application/json",
                "Origin" to "https://geo.dailymotion.com",
                "Referer" to "https://geo.dailymotion.com/",
                "X-Request-Origin" to "https://geo.dailymotion.com",
                "User-Agent" to UA
            )
            val response = app.get(
                "https://geo.dailymotion.com/videos/$id",
                headers = headers,
                timeout = 20L
            )
            if (response.code in 200..299) {
                val obj = JSONObject(response.text)
                val streamUrl = obj.optJSONObject("stream")?.optString("url")?.trim().orEmpty()
                if (streamUrl.isNotBlank()) {
                    links.add(LinkSpec(
                        label = "Dailymotion",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "Referer" to "https://geo.dailymotion.com/",
                            "Origin" to "https://geo.dailymotion.com"
                        )
                    ))
                }
            } else {
                log("DM | HTTP ${response.code} | $id")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("DM ERROR | $id | ${e.javaClass.simpleName}: ${e.message}")
        }
        return ResolvedSources(links, emptyList())
    }

    /**
     * Los episodios antiguos del sitio suelen tener solo OK.ru.
     * La página del embed trae MP4 por calidad y, casi siempre, un manifest
     * DASH en "metadataUrl"; se emiten ambos para tener respaldo.
     */
    private suspend fun resolveOkRu(url: String, referer: String?): ResolvedSources {
        val links = ArrayList<LinkSpec>()
        try {
            val embedUrl = url.trim()
                .let { if (it.startsWith("//")) "https:$it" else it }
                .replace("://m.ok.ru", "://ok.ru")
                .replace("/video/", "/videoembed/")

            val text = fetchOkRuText(embedUrl, referer) ?: run {
                log("OKRU | sin datos | ${short(embedUrl)}")
                return ResolvedSources(emptyList(), emptyList())
            }

            val linkHeaders = mapOf(
                "Referer" to embedUrl,
                "Origin" to "https://ok.ru"
            )

            val videosRaw = Regex(""""videos"\s*:\s*(\[[^\]]*])""")
                .find(text)?.groupValues?.getOrNull(1)
            if (videosRaw != null) {
                val videos = JSONArray(videosRaw)
                val ranked = ArrayList<Pair<Int, String>>()
                for (i in 0 until videos.length()) {
                    val video = videos.optJSONObject(i) ?: continue
                    val stream = video.optString("url").trim()
                    if (stream.isEmpty()) continue
                    val quality = when (video.optString("name").lowercase()) {
                        "mobile" -> 144
                        "lowest" -> 240
                        "low" -> 360
                        "sd" -> 480
                        "hd" -> 720
                        "full" -> 1080
                        "quad" -> 1440
                        "ultra" -> 2160
                        else -> 0
                    }
                    ranked.add(quality to stream)
                }
                ranked.sortedByDescending { it.first }
                    .distinctBy { it.second }
                    .take(2)
                    .forEach { (quality, stream) ->
                        links.add(LinkSpec(
                            label = if (quality > 0) "OK.ru ${quality}p" else "OK.ru",
                            url = stream,
                            type = ExtractorLinkType.VIDEO,
                            headers = linkHeaders
                        ))
                    }
            }

            val metadata = Regex(""""metadataUrl"\s*:\s*"([^"]+)"""")
                .find(text)?.groupValues?.getOrNull(1)
            if (!metadata.isNullOrBlank()) {
                links.add(LinkSpec(
                    label = "OK.ru (DASH)",
                    url = metadata,
                    type = ExtractorLinkType.DASH,
                    headers = linkHeaders
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("OKRU ERROR | ${e.javaClass.simpleName}: ${e.message}")
        }
        return ResolvedSources(links, emptyList())
    }

    private suspend fun fetchOkRuText(embedUrl: String, referer: String?): String? {
        val candidates = listOfNotNull(referer, "https://ok.ru/").distinct()
        for (ref in candidates) {
            try {
                val response = app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept" to "*/*",
                        "Origin" to "https://ok.ru",
                        "Referer" to ref,
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site"
                    ),
                    timeout = 25L
                )
                if (response.code !in 200..299) continue

                var text = response.text
                    .replace("&quot;", "\"")
                    .replace("&#34;", "\"")
                    .replace("&amp;", "&")
                    .replace("\\/", "/")
                text = Regex("""\\u([0-9A-Fa-f]{4})""").replace(text) { match ->
                    val code = match.groupValues[1].toIntOrNull(16)
                    if (code != null) code.toChar().toString() else match.value
                }
                if (text.contains("\"videos\"") || text.contains("\"metadataUrl\"")) return text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("OKRU GET | ${e.javaClass.simpleName}: ${e.message} | $ref")
            }
        }
        return null
    }

    private fun dailymotionId(url: String): String? {
        val patterns = listOf(
            Regex("""[?&]video=([A-Za-z0-9]+)"""),
            Regex("""dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)"""),
            Regex("""dai\.ly/([A-Za-z0-9]+)""")
        )
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    /* ------------------------------------------------------------
     * Tarjetas
     * ------------------------------------------------------------ */

    private fun parseEpisodeCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (anchor in doc.select("a[href]")) {
            val rawHref = anchor.attr("href").trim()
            if (!rawHref.startsWith("/ver/donghua/")) continue

            val href = rawHref.substringBefore('?').substringBefore('#')
            if (href.length <= "/ver/donghua/".length || !seen.add(href)) continue

            val image = anchor.selectFirst("img")
            val poster = imageUrl(image)
            val cardText = anchor.text().trim()
            val title = anchor.attr("title").trim()
                .ifBlank { image?.attr("alt")?.trim().orEmpty() }
                .ifBlank { anchor.selectFirst("p")?.text()?.trim().orEmpty() }
                .ifBlank { anchor.selectFirst("h3")?.text()?.trim().orEmpty() }
                .ifBlank { cardText }
            if (title.isBlank()) continue

            val episode = Regex("""episodio-(\d+)""").find(href)?.groupValues?.getOrNull(1)
            val displayName = when {
                episode == null -> title
                title.contains("Ep $episode", true) || title.contains("Episodio $episode", true) -> title
                else -> "$title · Ep $episode"
            }

            val absoluteHref = absolute(href) ?: continue
            out.add(newAnimeSearchResponse(displayName, absoluteHref, TvType.Anime) {
                posterUrl = poster
            })
        }

        return out
    }

    private fun parseSeriesCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (anchor in doc.select("a[href]")) {
            val rawHref = anchor.attr("href").trim()
            if (!rawHref.startsWith("/serie/")) continue

            val href = rawHref.substringBefore('?').substringBefore('#')
            if (href.length <= "/serie/".length || !seen.add(href)) continue

            val image = anchor.selectFirst("img")
            val cardText = anchor.text().trim()
            val title = image?.attr("alt")?.trim().orEmpty()
                .ifBlank { anchor.attr("title").trim() }
                .ifBlank { anchor.selectFirst("p")?.text()?.trim().orEmpty() }
                .ifBlank { anchor.selectFirst("h3")?.text()?.trim().orEmpty() }
                .ifBlank { cardText }
            if (title.isBlank()) continue

            val poster = imageUrl(image)
            val absoluteHref = absolute(href) ?: continue
            val isMovie = cardText.contains("pelicula", true) ||
                cardText.contains("película", true)

            if (isMovie) {
                out.add(newMovieSearchResponse(title, absoluteHref, TvType.AnimeMovie) {
                    posterUrl = poster
                })
            } else {
                out.add(newAnimeSearchResponse(title, absoluteHref, TvType.Anime) {
                    posterUrl = poster
                })
            }
        }

        return out
    }

    /* ------------------------------------------------------------
     * Utilidades de datos
     * ------------------------------------------------------------ */

    private fun parseRows(flight: String): List<RawEpisode> {
        val rows = jsonArrayAfter(flight, "\"rows\":") ?: return emptyList()
        val out = ArrayList<RawEpisode>(rows.length())

        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val id = row.optString(0).trim()
            if (id.isEmpty()) continue

            val season = row.optInt(1, 1).coerceAtLeast(1)
            val number = row.optInt(2, i + 1).coerceAtLeast(1)
            val meta = row.optString(10).trim()
            val title = if (meta.startsWith("{")) {
                try {
                    JSONObject(meta).optString("metaTitle").trim().takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            out.add(RawEpisode(id, season, number, title))
        }

        return out
    }

    private fun parseSources(flight: String): List<PlayerSource> {
        val sources = jsonArrayAfter(flight, "\"sources\":") ?: return emptyList()
        val out = ArrayList<PlayerSource>(sources.length())

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val url = source.optString("url").trim()
            val kind = source.optString("kind").trim()
            val label = source.optString("label").trim()
                .ifBlank { source.optString("id").trim() }
                .ifBlank { "Servidor" }
            val count = source.optInt("wyvernCount", 1).coerceAtLeast(1)
            out.add(PlayerSource(label, url, kind, count))
        }

        return out
    }

    private fun ldObjects(doc: Document): List<JSONObject> {
        val out = ArrayList<JSONObject>()

        for (script in doc.select("script[type=application/ld+json]")) {
            val data = script.data().trim().ifEmpty { script.html().trim() }
            if (data.isEmpty()) continue
            try {
                when (val value = JSONTokener(data).nextValue()) {
                    is JSONObject -> out.add(value)
                    is JSONArray -> {
                        for (i in 0 until value.length()) {
                            (value.opt(i) as? JSONObject)?.let { out.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                log("LDJSON ERROR | ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return out
    }

    private fun typeOf(obj: JSONObject): String {
        return when (val type = obj.opt("@type")) {
            is String -> type
            is JSONArray -> type.optString(0)
            else -> ""
        }
    }

    private fun stringsOf(value: Any?): List<String> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { i ->
                value.optString(i).trim().takeIf { it.isNotEmpty() }
            }
            is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun firstString(value: Any?): String? {
        return when (value) {
            is String -> value
            is JSONArray -> if (value.length() > 0) value.optString(0) else null
            else -> null
        }
    }

    private fun jsonStringField(text: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = text.indexOf(marker)
        if (start < 0) return null

        var i = start + marker.length
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val next = text[i + 1]
                when (next) {
                    '"', '\\', '/' -> out.append(next)
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 6 <= text.length) {
                            val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(next)
                    }
                    else -> out.append(next)
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Next.js entrega los datos como trozos de self.__next_f.push([1,"..."]).
     * Se unen y se desescapan una vez para poder leer el JSON interno.
     */
    private fun extractFlight(html: String): String {
        val marker = "self.__next_f.push([1,"
        val out = StringBuilder(html.length / 2)
        var index = 0

        while (true) {
            val start = html.indexOf(marker, index)
            if (start < 0) break

            var i = start + marker.length
            while (i < html.length && html[i].isWhitespace()) i++
            if (i >= html.length || html[i] != '"') {
                index = i
                continue
            }
            i++

            val contentStart = i
            var escaped = false
            while (i < html.length) {
                val c = html[i]
                if (escaped) {
                    escaped = false
                    i++
                    continue
                }
                if (c == '\\') {
                    escaped = true
                    i++
                    continue
                }
                if (c == '"') break
                i++
            }
            if (i >= html.length) break

            out.append(unescapeJs(html.substring(contentStart, i)))
            index = i + 1
        }

        return out.toString()
    }

    private fun unescapeJs(value: String): String {
        if (!value.contains('\\')) return value

        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }

            val next = value[i + 1]
            when (next) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    if (i + 6 <= value.length) {
                        val code = value.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    out.append(next)
                }
                else -> out.append(next)
            }
            i += 2
        }

        return out.toString()
    }

    private fun jsonArrayAfter(text: String, key: String): JSONArray? {
        var from = 0
        while (true) {
            val keyIndex = text.indexOf(key, from)
            if (keyIndex < 0) return null

            val start = text.indexOf('[', keyIndex + key.length)
            if (start < 0) return null

            val raw = extractBracketed(text, start, '[', ']')
            if (raw != null) {
                try {
                    return JSONArray(raw)
                } catch (_: Exception) {
                    // Puede ser una aparición del texto dentro de una cadena.
                }
            }

            from = keyIndex + key.length
        }
    }

    private fun extractBracketed(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }

        return null
    }

    private fun episodeUrl(slug: String, episode: RawEpisode): String =
        "$WEB/ver/donghua/$slug/temporada-${episode.season}-episodio-${episode.number}"

    private fun episodeName(series: String, episode: RawEpisode): String {
        val fallback = "Episodio ${episode.number}"
        val raw = episode.title?.trim().orEmpty()
        if (raw.isEmpty()) return fallback

        var clean = raw
        if (clean.startsWith(series, ignoreCase = true)) {
            clean = clean.substring(series.length).trim(' ', ':', '-', '·')
        }
        if (clean.matches(Regex("""(?i)temporada\s+\d+\s+episodio\s+\d+"""))) {
            return fallback
        }
        return clean.ifBlank { fallback }
    }

    private fun cleanTitle(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val clean = value
            .replace(Regex("""\s+Sub\s+Español\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return clean.ifEmpty { value }
    }

    private fun statusOf(doc: Document): ShowStatus? {
        val text = doc.text()
        return when {
            text.contains("En emisión", true) ||
                text.contains("En emision", true) -> ShowStatus.Ongoing
            text.contains("Finalizada", true) ||
                text.contains("Completada", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun firstEpisodeHref(doc: Document): String? {
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (href.startsWith("/ver/donghua/")) return absolute(href)
        }
        return null
    }

    /* ------------------------------------------------------------
     * Utilidades de URL
     * ------------------------------------------------------------ */

    private fun absolute(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("data:")) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$WEB$value"
            else -> "$WEB/$value"
        }
    }

    private fun imageUrl(element: Element?): String? {
        if (element == null) return null

        var raw = listOf("src", "data-src", "data-lazy-src", "data-original")
            .asSequence()
            .map { element.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }

        if (raw.isNullOrBlank()) {
            val srcset = element.attr("srcset").trim()
            if (srcset.isNotEmpty()) {
                val candidates = srcset.split(',').mapNotNull { part ->
                    val bits = part.trim().split(Regex("""\s+"""))
                    val url = bits.firstOrNull()?.trim().orEmpty()
                    val width = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                    if (url.isEmpty()) null else url to (width ?: 0)
                }
                raw = candidates
                    .filter { it.second in 200..700 }
                    .maxByOrNull { it.second }?.first
                    ?: candidates.maxByOrNull { it.second }?.first
            }
        }
        if (raw.isNullOrBlank()) return null

        var url = absolute(raw) ?: return null
        if (url.contains("/_next/image")) {
            val encoded = Regex("""[?&]url=([^&]+)""").find(url)?.groupValues?.getOrNull(1)
            if (encoded != null) {
                try {
                    url = URLDecoder.decode(encoded, "UTF-8")
                } catch (_: Exception) {
                    // Se deja la URL original.
                }
            }
        }
        return url
    }
}
