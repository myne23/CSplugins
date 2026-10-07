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

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private fun Element.getImageUrl(): String? {
        val img = this.selectFirst("img") ?: return null
        return img.attr("data-src").takeIf { it.isNotBlank() }
            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun setAnimeOnlinePosterHeaders(response: Any) {
        try {
            val setter = response.javaClass.methods.firstOrNull {
                it.name == "setPosterHeaders" && it.parameterTypes.size == 1
            }

            setter?.invoke(
                response,
                mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to "Mozilla/5.0"
                )
            )

            println("AnimeOnline POSTER_HEADERS -> class=${response.javaClass.simpleName} setter=${setter != null}")
        } catch (e: Exception) {
            println("AnimeOnline POSTER_HEADERS ERROR -> ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseAnimeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        val rawImage = article.getImageUrl()
        val poster = rawImage?.let { fixUrl(it) }
        println("AnimeOnline IMAGE anime -> title=$title raw=$rawImage fixed=$poster")
        return newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            setAnimeOnlinePosterHeaders(this)
        }
    }

    private fun parseEpisodeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href*='/episodio/']")?.attr("href") ?: return null
        val title = article.selectFirst(".data h3")?.text()?.trim() ?: return null
        val rawImage = article.getImageUrl()
        val poster = rawImage?.let { fixUrl(it) }
        println("AnimeOnline IMAGE episode -> title=$title raw=$rawImage fixed=$poster")
        val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()
        return newAnimeSearchResponse(if (!episodeTitle.isNullOrBlank()) "$title - $episodeTitle" else title, fixUrl(link), TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            setAnimeOnlinePosterHeaders(this)
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
        val testImage = "https://ww3.animeonline.ninja/wp-content/uploads/2026/10/6hBvqy6OKi0OcFLQOeulmlC5hr0.jpg"

        try {
            val noReferer = app.get(testImage)
            println("AnimeOnline IMGTEST noReferer -> code=${noReferer.code} type=${noReferer.headers["Content-Type"]} bytes=${noReferer.text.length}")
        } catch (e: Exception) {
            println("AnimeOnline IMGTEST noReferer ERROR -> ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val withReferer = app.get(
                testImage,
                referer = "$mainUrl/"
            )
            println("AnimeOnline IMGTEST withReferer -> code=${withReferer.code} type=${withReferer.headers["Content-Type"]} bytes=${withReferer.text.length}")
        } catch (e: Exception) {
            println("AnimeOnline IMGTEST withReferer ERROR -> ${e.javaClass.simpleName}: ${e.message}")
        }
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
                val poster = article.getImageUrl()?.let { fixUrl(it) }
                newMovieSearchResponse(title, fixUrl(link), TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
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

            val poster = article.getImageUrl()?.let { fixUrl(it) }
            val fixedLink = fixUrl(link)
            val type = if (fixedLink.contains("/pelicula/")) TvType.Movie else TvType.Anime
            
            val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()
            val finalTitle = if (!episodeTitle.isNullOrBlank()) "$title - $episodeTitle" else title

            if (type == TvType.Movie) {
                newMovieSearchResponse(finalTitle, fixedLink, TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
                }
            } else {
                newAnimeSearchResponse(finalTitle, fixedLink, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
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
            val poster = item.getImageUrl()?.let { fixUrl(it) }
            val fixedLink = fixUrl(link)

            when {
                fixedLink.contains("/pelicula/") -> {
                    newMovieSearchResponse(title, fixedLink, TvType.Movie) {
                        this.posterUrl = fixUrlNull(poster)
                        setAnimeOnlinePosterHeaders(this)
                    }
                }
                else -> {
                    newAnimeSearchResponse(title, fixedLink, TvType.Anime) {
                        this.posterUrl = fixUrlNull(poster)
                        setAnimeOnlinePosterHeaders(this)
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val title = document.select("h1").lastOrNull()?.text()?.trim() ?: return null

        println("AnimeOnline DEBUG load -> title=$title")
        println("AnimeOnline DEBUG url -> $url")

        val mainPoster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        println("AnimeOnline DEBUG og:image -> ${mainPoster ?: "NO ENCONTRADO"}")

        val allImages = document.select("img").mapNotNull { img ->
            val src = img.attr("src").trim()
            val dataSrc = img.attr("data-src").trim()
            val lazySrc = img.attr("data-lazy-src").trim()
            val original = img.attr("data-original").trim()

            val value = when {
                src.isNotBlank() && !src.startsWith("data:") -> "src=$src"
                dataSrc.isNotBlank() -> "data-src=$dataSrc"
                lazySrc.isNotBlank() -> "data-lazy-src=$lazySrc"
                original.isNotBlank() -> "data-original=$original"
                else -> null
            }

            value?.let {
                val classes = img.className().trim()
                "class='$classes' $it"
            }
        }

        println("AnimeOnline DEBUG images -> count=${allImages.size}")

        allImages.take(30).forEachIndexed { index, image ->
            println("AnimeOnline DEBUG image[$index] -> $image")
        }

        val episodeElements = document.select(
            "#seasons .episodios li, .episodios li, article.episode-card-item"
        )

        println(
            "AnimeOnline DEBUG episodeElements -> count=${episodeElements.size}"
        )

        episodeElements.take(30).forEachIndexed { index, element ->
            val episodeText = element.text().trim()
            val episodeImages = element.select("img").mapNotNull { img ->
                listOf(
                    img.attr("src").trim(),
                    img.attr("data-src").trim(),
                    img.attr("data-lazy-src").trim(),
                    img.attr("data-original").trim()
                ).firstOrNull { value ->
                    value.isNotBlank() && !value.startsWith("data:")
                }
            }

            println(
                "AnimeOnline DEBUG episode[$index] -> " +
                    "text='$episodeText' images=$episodeImages"
            )
        }

        var description: String? = null

        for (heading in document.select("h2")) {
            if (heading.text().trim().equals("Sinopsis", ignoreCase = true)) {
                val headingHtml = heading.parent()?.html() ?: ""
                val marker = "Sinopsis"
                val markerIndex = headingHtml.indexOf(marker, ignoreCase = true)
                if (markerIndex >= 0) {
                    val afterMarker = headingHtml.substring(markerIndex + marker.length)
                    val stripped = afterMarker
                        .replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    if (stripped.isNotBlank()) {
                        description = stripped
                    }
                }
                break
            }
        }

        val seasons = document.select("#seasons > .se-c").mapIndexedNotNull { index, seasonElement ->
            val seasonNumber = seasonElement
                .selectFirst(".se-q .se-t")
                ?.text()
                ?.trim()
                ?.toIntOrNull()
                ?: (index + 1)

            val episodeElements = seasonElement.select(".episodios li")

            val episodes = episodeElements.mapIndexedNotNull { episodeIndex, element ->
                val linkElement = element.selectFirst("a[href]")
                val href = linkElement?.attr("href")?.trim()

                if (href.isNullOrBlank()) {
                    return@mapIndexedNotNull null
                }

                val name = linkElement.text().trim().ifBlank { "Episodio" }
                val episodeNumber = episodeIndex + 1

                val episodeImage: Element? = element.selectFirst("img")
                val episodePoster = episodeImage?.let { img ->
                    listOf(
                        img.attr("src").trim(),
                        img.attr("data-src").trim(),
                        img.attr("data-lazy-src").trim(),
                        img.attr("data-original").trim()
                    ).firstOrNull { value ->
                        value.isNotBlank() && !value.startsWith("data:")
                    }
                }

                newEpisode(fixUrl(href)) {
                    this.name = name
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = episodePoster?.let { fixUrlNull(it) }

                    try {
                        val setter = this.javaClass.methods.firstOrNull {
                            it.name == "setPosterHeaders" && it.parameterTypes.size == 1
                        }

                        println(
                            "AnimeOnline EPISODE_POSTER_HEADERS -> " +
                            "class=${this.javaClass.simpleName} setter=${setter != null}"
                        )
                    } catch (e: Exception) {
                        println(
                            "AnimeOnline EPISODE_POSTER_HEADERS ERROR -> " +
                            "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }

            if (episodes.isEmpty()) null else seasonNumber to episodes
        }

        if (seasons.isEmpty()) return null

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = mainPoster?.let { fixUrlNull(it) }
            setAnimeOnlinePosterHeaders(this)
            this.plot = description
            seasons.forEach { (_, episodeList) ->
                addEpisodes(DubStatus.Subbed, episodeList)
            }
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
