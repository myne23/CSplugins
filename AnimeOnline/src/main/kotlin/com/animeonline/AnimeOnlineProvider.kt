@file:Suppress("DEPRECATION")
package com.animeonline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeOnlineProvider : MainAPI() {

    override var mainUrl = "https://ww3.animeonline.ninja"
    override var name = "AnimeOnline"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries
    )

    override var lang = "es"
    override val hasMainPage = true

    // Esta es la clase nativa que lanza el menú de Bypass por WebView
    private val cloudflareKiller by lazy { CloudflareKiller() }

    // Cabeceras limpias, sin forzar cookies manuales para no romper las miniaturas
    private val posterHeaders: Map<String, String>
        get() = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        )

    private fun parseAnimeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        val image = article.selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src")?.takeIf { it.isNotBlank() }
        return newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
        }
    }

    private fun parseEpisodeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href*='/episodio/']")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        val image = article.selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src")?.takeIf { it.isNotBlank() }
        val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()
        return newAnimeSearchResponse(if (!episodeTitle.isNullOrBlank()) "$title - $episodeTitle" else title, fixUrl(link), TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
        }
    }

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
            if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
            
            val response = app.get(request.data, referer = "$mainUrl/", interceptor = cloudflareKiller)
            val document = response.document
            val sections = ArrayList<HomePageList>()

            val latestEpisodes = document.select("div.items article.item.se.episodes").mapNotNull { parseEpisodeCard(it) }
            if (latestEpisodes.isNotEmpty()) sections.add(HomePageList("Últimos episodios ⚡", latestEpisodes))

            val latestAnimeHeader = document.select("header").firstOrNull { it.text().contains("ÚLTIMOS ANIMES AGREGADOS") }
            val latestAnime = latestAnimeHeader?.nextElementSibling()?.nextElementSibling()?.select("article.item")?.mapNotNull { parseAnimeCard(it) } ?: emptyList()
            if (latestAnime.isNotEmpty()) sections.add(HomePageList("Últimos animes agregados 💥", latestAnime))

            val latestMoviesHeader = document.select("header").firstOrNull { it.text().contains("ÚLTIMAS PELICULAS AGREGADAS") }
            val latestMovies = latestMoviesHeader?.nextElementSibling()?.nextElementSibling()?.select("article.item")?.mapNotNull { article ->
                val link = article.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val title = article.selectFirst(".data h3")?.text()?.trim() ?: return@mapNotNull null
                val image = article.selectFirst("img")
                val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src")?.takeIf { it.isNotBlank() }
                newMovieSearchResponse(title, fixUrl(link), TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            } ?: emptyList()
            if (latestMovies.isNotEmpty()) sections.add(HomePageList("Últimas peliculas agregadas 🎬", latestMovies))

            return newHomePageResponse(sections, hasNext = false)
        }

        val url = request.data + page.toString()
        val document = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller).document

        val items = document.select("article.item").mapNotNull { article ->
            val link = article.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = article.selectFirst(".data h3")?.text()?.trim()
                ?: article.selectFirst(".title")?.text()?.trim()
                ?: return@mapNotNull null

            val image = article.selectFirst("img")
            val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")?.takeIf { it.isNotBlank() }

            val fixedLink = fixUrl(link)
            val type = if (fixedLink.contains("/pelicula/")) TvType.Movie else TvType.Anime
            
            val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()
            val finalTitle = if (!episodeTitle.isNullOrBlank()) "$title - $episodeTitle" else title

            if (type == TvType.Movie) {
                newMovieSearchResponse(finalTitle, fixedLink, TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            } else {
                newAnimeSearchResponse(finalTitle, fixedLink, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            "$mainUrl/search/?s=$encodedQuery",
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        return document.select(".result-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = item.selectFirst("h3")?.text()?.trim() ?: item.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
            val image = item.selectFirst("img")
            val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src")?.takeIf { it.isNotBlank() }
            val fixedLink = fixUrl(link)

            when {
                fixedLink.contains("/pelicula/") -> {
                    newMovieSearchResponse(title, fixedLink, TvType.Movie) {
                        this.posterUrl = poster
                        this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                    }
                }
                fixedLink.contains("/episodio/") -> {
                    newAnimeSearchResponse(title, fixedLink, TvType.Anime) {
                        this.posterUrl = poster
                        this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                    }
                }
                else -> {
                    newAnimeSearchResponse(title, fixedLink, TvType.Anime) {
                        this.posterUrl = poster
                        this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val title = document.select("h1").lastOrNull()?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
        var description: String? = null

        for (heading in document.select("h2")) {
            if (heading.text().trim().equals("Sinopsis", ignoreCase = true)) {
                val headingHtml = heading.parent()?.html() ?: ""
                val marker = "Sinopsis"
                val markerIndex = headingHtml.indexOf(marker, ignoreCase = true)
                if (markerIndex >= 0) {
                    val afterMarker = headingHtml.substring(markerIndex + marker.length)
                    val stripped = afterMarker.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                    if (stripped.isNotBlank()) description = stripped
                }
                break
            }
        }

        val seasons = document.select("#seasons > .se-c").mapIndexedNotNull { index, seasonElement ->
            val seasonNumber = seasonElement.selectFirst(".se-q .se-t")?.text()?.trim()?.toIntOrNull() ?: (index + 1)
            val episodeLinks = seasonElement.select(".episodios a[href]")

            val episodes = episodeLinks.mapIndexedNotNull { episodeIndex, element ->
                val href = element.attr("href").trim()
                if (href.isBlank()) return@mapIndexedNotNull null
                val name = element.text().trim().ifBlank { "Episodio" }
                val episodeNumber = episodeIndex + 1

                newEpisode(fixUrl(href)) {
                    this.name = name
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = poster
                }
            }
            if (episodes.isEmpty()) null else seasonNumber to episodes
        }

        if (seasons.isEmpty()) return null
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            posterHeaders = this@AnimeOnlineProvider.posterHeaders
            this.plot = description
            seasons.forEach { (_, episodeList) -> addEpisodes(DubStatus.Subbed, episodeList) }
        }
    }

    private suspend fun resolveUqload(url: String, referer: String, serverName: String, language: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val response = app.get(url, referer = referer).text
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\)""")
            val packedScript = packedRegex.find(response)?.value
            val htmlToSearch = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response
            val fileUrl = Regex("""file:\s*["'](https[^"']+)["']""").find(htmlToSearch)?.groupValues?.get(1)
            
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
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val postId = document.selectFirst("[data-post]")?.attr("data-post")?.trim()
        if (postId.isNullOrBlank()) return false

        val apiResponse = app.get("$mainUrl/wp-json/dooplayer/v1/post/$postId?type=tv&source=1", referer = data, interceptor = cloudflareKiller)
        val embedUrl = apiResponse.text.substringAfter("\"embed_url\":\"").substringBefore("\"").replace("\\/", "/")
        if (embedUrl.isBlank()) return false

        val embedDocument = app.get(embedUrl, referer = data, interceptor = cloudflareKiller).document
        var linkCount = 0

        embedDocument.select(".OD").forEach { block ->
            val blockLang = when {
                block.hasClass("OD_SUB") -> "SUB"
                block.hasClass("OD_LAT") -> "LAT"
                block.hasClass("OD_ES") -> "ES"
                block.hasClass("OD_EN") -> "EN"
                else -> "SUB"
            }

            block.select("li[onclick*='go_to_player']").forEach { item ->
                val server = item.selectFirst("span")?.text()?.trim() ?: "Servidor"
                val sourceUrl = Regex("""go_to_player\(['"]([^'"]+)['"]\)""").find(item.attr("onclick"))?.groupValues?.getOrNull(1)
                
                if (sourceUrl.isNullOrBlank()) return@forEach

                if (sourceUrl.contains("uqload", ignoreCase = true)) {
                    if (resolveUqload(sourceUrl, data, server, blockLang, callback)) linkCount++
                } else if (sourceUrl.contains("filemo", ignoreCase = true)) {
                    return@forEach 
                } else {
                    loadExtractor(url = sourceUrl, referer = data, subtitleCallback = subtitleCallback, callback = { link ->
                        val modifiedLink = ExtractorLink(
                            source = link.source,
                            name = "${link.name.substringBefore(" · ").substringBefore(" (").trim().uppercase()} · $blockLang",
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type
                        )
                        linkCount++
                        callback(modifiedLink)
                    })
                }
            }
        }
        return linkCount > 0
    }
}
