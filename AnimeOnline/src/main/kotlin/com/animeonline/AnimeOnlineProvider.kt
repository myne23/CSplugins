@file:Suppress("DEPRECATION")
package com.animeonline

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.reflect.Method
import java.net.URLEncoder
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AnimeOnlineProvider : MainAPI() {

    override var mainUrl = "https://ww3.animeonline.ninja"
    override var name = "AnimeOnline"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries
    )

    override var lang = "es"
    override val hasMainPage = true

    companion object {
        private const val TAG = "AnimeOnline"

        // true = logs de debug. En false no se arma ningún string de log.
        private val DEBUG = false

        // false = los capítulos usan el poster de la serie (como lo tenías funcionando).
        // true  = usa la miniatura propia de cada capítulo y, si no hay, el poster de la serie.
        private val USE_EPISODE_THUMBNAILS = false

        // false = la pantalla de detalle (banner/poster de la serie) NO manda posterHeaders.
        // Kino mostraba el banner en gris con esos headers. Los catálogos y el buscador no cambian.
        private val LOAD_POSTER_HEADERS = false

        private const val PLAYER_TIMEOUT_MS = 25_000L

        private const val HEADER_LATEST_ANIME = "ÚLTIMOS ANIMES AGREGADOS"
        private const val HEADER_LATEST_MOVIES = "ÚLTIMAS PELÍCULAS AGREGADAS"

        // Regex compiladas una sola vez
        private val RX_GO_TO_PLAYER = Regex("""go_to_player\(['"]([^'"]+)['"]\)""")
        private val RX_EMBED_URL = Regex(""""embed_url"\s*:\s*"([^"]+)"""")
        private val RX_UQLOAD_PACKED = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\)""")
        private val RX_UQLOAD_FILE = Regex("""file:\s*["'](https[^"']+)["']""")
        private val RX_NUMERANDO = Regex("""(\d+)\s*-\s*(\d+)""")
        private val RX_SPACES = Regex("""\s+""")
        private val RX_HEADING_TAG = Regex("""h[1-6]""")
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val posterHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0"
    )

    // Con DEBUG = false el mensaje ni se construye (lambda inline).
    private inline fun dbg(message: () -> String) {
        if (DEBUG) Log.d(TAG, message())
    }

    // ======================================================================
    //  IMÁGENES
    // ======================================================================

    private fun Element.getImageUrl(): String? {
        val img = this.selectFirst("img") ?: return null
        return img.attr("data-src").trim().takeIf { it.isNotBlank() }
            ?: img.attr("data-lazy-src").trim().takeIf { it.isNotBlank() }
            ?: img.attr("data-original").trim().takeIf { it.isNotBlank() }
            ?: img.attr("src").trim().takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun Element.cardPoster(): String? = getImageUrl()?.let { fixUrl(it) }

    // Miniatura propia de un capítulo (mismo orden de atributos que tenías).
    private fun episodeThumb(li: Element): String? {
        val img = li.selectFirst("img") ?: return null
        return listOf(
            img.attr("src").trim(),
            img.attr("data-src").trim(),
            img.attr("data-lazy-src").trim(),
            img.attr("data-original").trim()
        ).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    // Se usa reflexión para que compile con versiones de CloudStream que no
    // tienen posterHeaders. El Method se busca UNA vez por clase (antes se
    // recorrían todos los métodos de la clase por cada item).
    private val posterSetterCache = ConcurrentHashMap<Class<*>, Optional<Method>>()

    private fun applyPosterHeaders(response: Any) {
        try {
            val setter = posterSetterCache.getOrPut(response.javaClass) {
                Optional.ofNullable(
                    response.javaClass.methods.firstOrNull {
                        it.name == "setPosterHeaders" && it.parameterTypes.size == 1
                    }
                )
            }.orElse(null)

            setter?.invoke(response, posterHeaders)
        } catch (e: Exception) {
            dbg { "POSTER_HEADERS ERROR -> ${e.javaClass.simpleName}: ${e.message}" }
        }
    }

    // ======================================================================
    //  CARDS
    // ======================================================================

    private fun buildCard(
        title: String,
        url: String,
        poster: String?,
        isMovie: Boolean
    ): SearchResponse {
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                applyPosterHeaders(this)
            }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                applyPosterHeaders(this)
            }
        }
    }

    private fun parseAnimeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        return buildCard(title, fixUrl(link), article.cardPoster(), false)
    }

    private fun parseMovieCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        return buildCard(title, fixUrl(link), article.cardPoster(), true)
    }

    private fun parseEpisodeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href*='/episodio/']")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()
        val finalTitle = if (!episodeTitle.isNullOrBlank()) "$title - $episodeTitle" else title
        return buildCard(finalTitle, fixUrl(link), article.cardPoster(), false)
    }

    private fun parseGenericCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href") ?: return null

        val title = article.selectFirst(".data h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: article.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val fixedLink = fixUrl(link)

        val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()
        val finalTitle = if (!episodeTitle.isNullOrBlank()) "$title - $episodeTitle" else title

        return buildCard(
            finalTitle,
            fixedLink,
            article.cardPoster(),
            fixedLink.contains("/pelicula/")
        )
    }

    // ======================================================================
    //  HOME
    // ======================================================================

    override val mainPage = mainPageOf(
        "$mainUrl/inicio/" to "Inicio",
        "$mainUrl/tendencias/page/" to "Tendencias 🔥",
        "$mainUrl/genero/accion/page/" to "Acción",
        "$mainUrl/genero/comedia/page/" to "Comedia",
        "$mainUrl/genero/romance/page/" to "Romance",
        "$mainUrl/genero/aventura/page/" to "Aventura"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Inicio") {
            return getHomeSections(page, request)
        }

        val url = request.data + page.toString()

        val document = app.get(
            url,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val items = document.select("article.item").mapNotNull { parseGenericCard(it) }

        dbg { "HOME ${request.name}: page=$page url=$url parsed=${items.size}" }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private suspend fun getHomeSections(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

        val document = app.get(
            request.data,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val sections = ArrayList<HomePageList>(3)

        val latestEpisodes = document
            .select("div.items article.item.se.episodes")
            .mapNotNull { parseEpisodeCard(it) }

        if (latestEpisodes.isNotEmpty()) {
            sections.add(HomePageList("Últimos episodios ⚡", latestEpisodes))
        }

        // Una sola pasada por los <header> (antes se recorrían dos veces y se
        // calculaba text() de cada uno en cada pasada).
        var animeHeader: Element? = null
        var moviesHeader: Element? = null

        for (header in document.select("header")) {
            val text = header.text()

            if (animeHeader == null && text.contains(HEADER_LATEST_ANIME, ignoreCase = true)) {
                animeHeader = header
            }

            if (moviesHeader == null && text.contains(HEADER_LATEST_MOVIES, ignoreCase = true)) {
                moviesHeader = header
            }

            if (animeHeader != null && moviesHeader != null) break
        }

        val latestAnime = articlesAfter(animeHeader).mapNotNull { parseAnimeCard(it) }

        if (latestAnime.isNotEmpty()) {
            sections.add(HomePageList("Últimos animes agregados 💥", latestAnime))
        }

        val latestMovies = articlesAfter(moviesHeader).mapNotNull { parseMovieCard(it) }

        if (latestMovies.isNotEmpty()) {
            sections.add(HomePageList("Últimas películas agregadas 🎬", latestMovies))
        }

        dbg { "HOME Inicio totalSections=${sections.size}" }

        return newHomePageResponse(sections, hasNext = false)
    }

    private fun articlesAfter(header: Element?): List<Element> {
        return header
            ?.nextElementSibling()
            ?.nextElementSibling()
            ?.select("article.item")
            ?: emptyList()
    }

    // ======================================================================
    //  SEARCH
    // ======================================================================

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        return try {
            val document = app.get(
                "$mainUrl/search/?s=$encodedQuery",
                referer = "$mainUrl/",
                interceptor = cloudflareKiller
            ).document

            document.select(".result-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null

                val title = item.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: item.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val fixedLink = fixUrl(link)

                buildCard(title, fixedLink, item.cardPoster(), fixedLink.contains("/pelicula/"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "SEARCH ERROR", e)
            emptyList()
        }
    }

    // ======================================================================
    //  LOAD
    // ======================================================================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val title = document.select("h1").lastOrNull()?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val mainPoster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        dbg { "LOAD title=$title url=$url poster=$mainPoster" }

        val description = extractSynopsis(document)

        val episodes = ArrayList<Episode>()

        document.select("#seasons > .se-c").forEachIndexed { index, seasonElement ->
            val seasonNumber = seasonElement
                .selectFirst(".se-q .se-t")
                ?.text()
                ?.trim()
                ?.toIntOrNull()
                ?: (index + 1)

            val seasonEpisodes = ArrayList<Episode>()

            seasonElement.select(".episodios li").forEachIndexed { episodeIndex, li ->
                val linkElement = li.selectFirst("a[href]") ?: return@forEachIndexed
                val href = linkElement.attr("href").trim()

                if (href.isBlank()) return@forEachIndexed

                // Número real del capítulo ("1 - 5" -> 5); si no existe, el orden.
                val episodeNumber = li.selectFirst(".numerando")
                    ?.text()
                    ?.let { RX_NUMERANDO.find(it)?.groupValues?.getOrNull(2)?.toIntOrNull() }
                    ?: (episodeIndex + 1)

                val episodeName = linkElement.text().trim()
                    .ifBlank { li.selectFirst(".episodiotitle")?.text()?.trim().orEmpty() }
                    .ifBlank { "Episodio $episodeNumber" }

                val thumb = if (USE_EPISODE_THUMBNAILS) {
                    episodeThumb(li)?.let { fixUrl(it) } ?: mainPoster
                } else {
                    mainPoster
                }

                seasonEpisodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = thumb
                    }
                )
            }

            episodes.addAll(seasonEpisodes.sortedBy { it.episode ?: 0 })
        }

        if (episodes.isEmpty()) {
            // Las películas no tienen #seasons: antes devolvían null y no se podían abrir.
            if (url.contains("/pelicula/")) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = mainPoster
                    if (LOAD_POSTER_HEADERS) applyPosterHeaders(this)
                    this.plot = description
                }
            }

            return null
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = mainPoster
            if (LOAD_POSTER_HEADERS) applyPosterHeaders(this)
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /**
     * Sinopsis limpia. Antes se sacaba del HTML crudo con regex y quedaban
     * entidades sin decodificar (&amp;, &quot;, &nbsp;...) y todo en un solo
     * bloque. Ahora se usa el texto ya decodificado por Jsoup y se separa
     * por párrafos.
     */
    private fun extractSynopsis(document: Document): String? {
        val heading = document.select("h2").firstOrNull {
            it.text().trim().equals("Sinopsis", ignoreCase = true)
        } ?: return null

        val parent = heading.parent()
        val paragraphs = ArrayList<String>()

        // Dooplay: <h2>Sinopsis</h2><div class="wp-content"><p>...</p></div>
        val content = parent?.selectFirst(".wp-content")

        if (content != null) {
            content.select("p").forEach { p ->
                cleanText(p.text()).takeIf { it.isNotBlank() }?.let { paragraphs.add(it) }
            }

            if (paragraphs.isEmpty()) {
                cleanText(content.text()).takeIf { it.isNotBlank() }?.let { paragraphs.add(it) }
            }
        }

        // Fallback: hermanos siguientes hasta el próximo encabezado.
        if (paragraphs.isEmpty()) {
            var sibling = heading.nextElementSibling()

            while (sibling != null && !RX_HEADING_TAG.matches(sibling.tagName())) {
                cleanText(sibling.text()).takeIf { it.isNotBlank() }?.let { paragraphs.add(it) }
                sibling = sibling.nextElementSibling()
            }
        }

        // Último recurso: lo que había después de "Sinopsis" en el contenedor.
        if (paragraphs.isEmpty() && parent != null) {
            val afterHeading = cleanText(
                parent.text().substringAfter(heading.text(), "")
            )

            if (afterHeading.isNotBlank()) paragraphs.add(afterHeading)
        }

        return paragraphs.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun cleanText(value: String): String {
        return value
            .replace('\u00A0', ' ')
            .replace(RX_SPACES, " ")
            .trim()
    }

    // ======================================================================
    //  LINKS
    // ======================================================================

    private data class PlayerEntry(
        val server: String,
        val url: String,
        val lang: String
    )

    private suspend fun resolveUqload(
        url: String,
        referer: String,
        serverName: String,
        language: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(url, referer = referer).text

            val packedScript = RX_UQLOAD_PACKED.find(response)?.value

            val htmlToSearch = if (packedScript != null) {
                JsUnpacker(packedScript).unpack() ?: response
            } else {
                response
            }

            val fileUrl = RX_UQLOAD_FILE.find(htmlToSearch)?.groupValues?.getOrNull(1)

            if (!fileUrl.isNullOrBlank()) {
                val isM3u8 = fileUrl.contains(".m3u8")

                callback(
                    ExtractorLink(
                        source = serverName,
                        name = "${serverName.uppercase()} · $language",
                        url = fileUrl,
                        referer = "https://uqload.vc/",
                        quality = Qualities.Unknown.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )

                true
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dbg { "UQLOAD ERROR $url -> ${e.message}" }
            false
        }
    }

    private suspend fun processPlayer(
        entry: PlayerEntry,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        linkCount: AtomicInteger
    ) {
        if (entry.url.contains("uqload", ignoreCase = true)) {
            if (resolveUqload(entry.url, pageUrl, entry.server, entry.lang, callback)) {
                linkCount.incrementAndGet()
            }
            return
        }

        loadExtractor(
            url = entry.url,
            referer = pageUrl,
            subtitleCallback = subtitleCallback,
            callback = { link ->
                val modifiedLink = ExtractorLink(
                    source = link.source,
                    name = "${link.name.substringBefore(" · ").substringBefore(" (").trim().uppercase()} · ${entry.lang}",
                    url = link.url,
                    referer = link.referer,
                    quality = link.quality,
                    type = link.type,
                    // Antes se perdían los headers que pedía el extractor.
                    headers = link.headers
                )

                linkCount.incrementAndGet()
                callback(modifiedLink)
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/", interceptor = cloudflareKiller).document

        val postId = document.selectFirst("[data-post]")?.attr("data-post")?.trim()

        if (postId.isNullOrBlank()) return false

        // Las películas usan type=movie; si falla se prueba con tv.
        val apiTypes = if (data.contains("/pelicula/")) listOf("movie", "tv") else listOf("tv")

        var embedUrl: String? = null

        for (type in apiTypes) {
            val apiResponse = app.get(
                "$mainUrl/wp-json/dooplayer/v1/post/$postId?type=$type&source=1",
                referer = data,
                interceptor = cloudflareKiller
            )

            // Antes, si faltaba "embed_url", substringAfter/Before devolvía basura ("{")
            // que no estaba en blanco y se intentaba abrir como si fuera una URL.
            embedUrl = RX_EMBED_URL.find(apiResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.takeIf { it.isNotBlank() }

            if (embedUrl != null) break
        }

        val finalEmbedUrl = embedUrl ?: return false

        val embedDocument = app.get(
            finalEmbedUrl,
            referer = data,
            interceptor = cloudflareKiller
        ).document

        val entries = ArrayList<PlayerEntry>()

        for (block in embedDocument.select(".OD")) {
            val blockLang = when {
                block.hasClass("OD_SUB") -> "SUB"
                block.hasClass("OD_LAT") -> "LAT"
                block.hasClass("OD_ES") -> "ES"
                block.hasClass("OD_EN") -> "EN"
                else -> "SUB"
            }

            for (item in block.select("li[onclick*='go_to_player']")) {
                val server = item.selectFirst("span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Servidor"

                val sourceUrl = RX_GO_TO_PLAYER.find(item.attr("onclick"))
                    ?.groupValues
                    ?.getOrNull(1)

                if (sourceUrl.isNullOrBlank()) continue
                if (sourceUrl.contains("filemo", ignoreCase = true)) continue

                entries.add(PlayerEntry(server, sourceUrl, blockLang))
            }
        }

        dbg { "LINKS servidores=${entries.size}" }

        val linkCount = AtomicInteger(0)

        // Todos los servidores en paralelo y con timeout: antes iban de a uno y
        // un servidor lento o caído frenaba a todos los que venían después.
        coroutineScope {
            entries
                .distinctBy { it.url to it.lang }
                .map { entry ->
                    async {
                        try {
                            withTimeoutOrNull(PLAYER_TIMEOUT_MS) {
                                processPlayer(entry, data, subtitleCallback, callback, linkCount)
                                true
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            dbg { "LINKS ERROR ${entry.url}: ${e.message}" }
                        }
                    }
                }
                .awaitAll()
        }

        return linkCount.get() > 0
    }
}
