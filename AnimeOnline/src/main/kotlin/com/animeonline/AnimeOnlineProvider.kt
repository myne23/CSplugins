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

    private val posterHeaders: Map<String, String>
        get() {
            val base = mutableMapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            )

            runCatching {
                cloudflareKiller.getCookieHeaders(mainUrl).toMap()
            }.getOrNull()?.forEach { (k, v) ->
                base[k] = v
            }

            return base
        }

    private fun parseAnimeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href")
            ?: return null

        val title = article.selectFirst(".data h3")?.text()?.trim()
            ?: return null

        val image = article.selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }

        return newAnimeSearchResponse(
            title,
            fixUrl(link),
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
        }
    }

    private fun parseEpisodeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href*='/episodio/']")?.attr("href")
            ?: return null

        val title = article.selectFirst(".data h3")?.text()?.trim()
            ?: return null

        val image = article.selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }

        val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()

        return newAnimeSearchResponse(
            if (!episodeTitle.isNullOrBlank()) {
                "$title - $episodeTitle"
            } else {
                title
            },
            fixUrl(link),
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        println("=== ANIMEONLINE HOME START ===")
        println("URL: $mainUrl/inicio/")
        println("PAGE: $page")

        val response = app.get(
            "$mainUrl/inicio/",
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        )

        println("HTTP RESPONSE RECEIVED")

        val document = response.document
        println("DOCUMENT TITLE: ${document.title()}")
        println("ARTICLE COUNT: ${document.select("article").size}")
        println("ITEM COUNT: ${document.select("article.item").size}")
        println("EPISODE ARTICLE COUNT: ${document.select("div.items article.item.se.episodes").size}")

        val sections = ArrayList<HomePageList>()

        val latestEpisodes = document
            .select("div.items article.item.se.episodes")
            .mapNotNull { parseEpisodeCard(it) }

        println("LATEST EPISODES PARSED: ${latestEpisodes.size}")

        if (latestEpisodes.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimos episodios ⚡",
                    latestEpisodes
                )
            )
        }

        val latestAnimeHeader = document
            .select("header")
            .firstOrNull {
                it.text().contains("ÚLTIMOS ANIMES AGREGADOS")
            }

        println("LATEST ANIME HEADER FOUND: ${latestAnimeHeader != null}")

        val latestAnime = latestAnimeHeader
            ?.nextElementSibling()
            ?.nextElementSibling()
            ?.select("article.item")
            ?.mapNotNull { parseAnimeCard(it) }
            ?: emptyList()

        println("LATEST ANIME PARSED: ${latestAnime.size}")

        if (latestAnime.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimos animes agregados 💥",
                    latestAnime
                )
            )
        }

        val latestMoviesHeader = document
            .select("header")
            .firstOrNull {
                it.text().contains("ÚLTIMAS PELICULAS AGREGADAS")
            }

        println("LATEST MOVIES HEADER FOUND: ${latestMoviesHeader != null}")

        val latestMovies = latestMoviesHeader
            ?.nextElementSibling()
            ?.nextElementSibling()
            ?.select("article.item")
            ?.mapNotNull { article ->
                val link = article.selectFirst("a[href]")?.attr("href")
                    ?: return@mapNotNull null

                val title = article.selectFirst(".data h3")?.text()?.trim()
                    ?: return@mapNotNull null

                val image = article.selectFirst("img")
                val poster = image?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() }

                newMovieSearchResponse(
                    title,
                    fixUrl(link),
                    TvType.Movie
                ) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            }
            ?: emptyList()

        println("LATEST MOVIES PARSED: ${latestMovies.size}")

        println("TOTAL SECTIONS: ${sections.size}")
        println("=== ANIMEONLINE HOME END ===")

        return newHomePageResponse(
            sections,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val document = app.get(
            "$mainUrl/search/?s=$encodedQuery",
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        return document
            .select("article.item")
            .mapNotNull { article ->

                val link = article.selectFirst("a[href]")?.attr("href")
                    ?: return@mapNotNull null

                val title = article.selectFirst(".data h3")?.text()?.trim()
                    ?: return@mapNotNull null

                val image = article.selectFirst("img")
                val poster = image?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() }

                newAnimeSearchResponse(
                    title,
                    fixUrl(link),
                    TvType.Anime
                ) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        println("=== ANIMEONLINE LOAD ENTRY ===")
        println("ANIMEONLINE STEP 1")

        val document = app.get(
            url,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val title = document
            .select("h1")
            .lastOrNull()
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

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

        val seasons = document
            .select("#seasons > .se-c")
            .mapIndexedNotNull { index, seasonElement ->

                val seasonNumber = seasonElement
                    .selectFirst(".se-q .se-t")
                    ?.text()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: (index + 1)

                val episodeLinks = seasonElement
                    .select(".episodios a[href]")

                val episodeImages = seasonElement
                    .select(".episodios img")

                if (index == 0) {
                    println("=== ANIMEONLINE EPISODE IMAGE DEBUG ===")
                    println("EPISODE LINKS: ${episodeLinks.size}")
                    println("ALL IMAGES IN SEASON: ${episodeImages.size}")

                    val firstImage = episodeImages.firstOrNull()
                    if (firstImage != null) {
                        println("FIRST IMAGE HTML: ${firstImage.outerHtml()}")
                        println("FIRST IMAGE SRC: ${firstImage.attr("src")}")
                        println("FIRST IMAGE DATA-SRC: ${firstImage.attr("data-src")}")
                        println("FIRST IMAGE DATA-LAZY-SRC: ${firstImage.attr("data-lazy-src")}")
                        println("FIRST IMAGE SRCSET: ${firstImage.attr("srcset")}")
                        println("FIRST IMAGE STYLE: ${firstImage.attr("style")}")
                    }

                    val firstImageContainer = seasonElement
                        .select(".episodios .imagen")
                        .firstOrNull()

                    if (firstImageContainer != null) {
                        println("FIRST IMAGE CONTAINER HTML: ${firstImageContainer.outerHtml()}")
                    }

                    val firstEpisodeLink = episodeLinks.firstOrNull()
                    if (firstEpisodeLink != null) {
                        println("FIRST EPISODE LINK HTML: ${firstEpisodeLink.outerHtml()}")
                    }
                }

                val episodes = episodeLinks
                    .mapIndexedNotNull { episodeIndex, element ->

                        val href = element.attr("href").trim()
                        if (href.isBlank()) return@mapIndexedNotNull null

                        val name = element.text()
                            .trim()
                            .ifBlank { "Episodio" }

                        val episodeNumber = episodeIndex + 1

                        var episodePoster: String? = null

                        if (episodeIndex < episodeImages.size) {
                            val image = episodeImages[episodeIndex]

                            val dataSrc = image.attr("data-src")
                            val lazySrc = image.attr("data-lazy-src")
                            val src = image.attr("src")
                            val srcset = image.attr("srcset")

                            episodePoster = when {
                                dataSrc.isNotBlank() -> dataSrc
                                lazySrc.isNotBlank() -> lazySrc
                                src.isNotBlank() -> src
                                srcset.isNotBlank() -> srcset.substringBefore(",").trim().substringBefore(" ")
                                else -> null
                            }
                        }

                        newEpisode(fixUrl(href)) {
                            this.name = name
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = episodePoster
                        }
                    }

                if (episodes.isEmpty()) {
                    null
                } else {
                    seasonNumber to episodes
                }
            }

        if (seasons.isEmpty()) return null

        println("=== ANIMEONLINE LOAD ===")
        println("TITLE: $title")
        println("MAIN POSTER: $poster")
        println("DESCRIPTION: $description")
        println("SEASONS: ${seasons.size}")
        println("EPISODES: ${seasons.sumOf { it.second.size }}")

        seasons.forEach {
            println("SEASON ${it.first}: ${it.second.size} episodes")
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = poster
            posterHeaders = this@AnimeOnlineProvider.posterHeaders
            this.plot = description

            seasons.forEach { (_, episodeList) ->
                addEpisodes(
                    DubStatus.Subbed,
                    episodeList
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== ANIMEONLINE DOOPLAYER API TEST ===")
        println("DATA: $data")

        val document = app.get(
            data,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val postId = document
            .selectFirst("[data-post]")
            ?.attr("data-post")
            ?.trim()

        println("POST ID: $postId")

        if (postId.isNullOrBlank()) {
            println("NO POST ID")
            return false
        }

        val type = "tv"
        val source = "1"

        val apiUrl =
            "$mainUrl/wp-json/dooplayer/v1/post/$postId?type=$type&source=$source"

        println("API URL: $apiUrl")

        val response = app.get(
            apiUrl,
            referer = data,
            interceptor = cloudflareKiller
        )

        println("API STATUS: ${response.code}")
        println("API CONTENT TYPE: ${response.headers["Content-Type"]}")

        val body = response.text

        println("API BODY LENGTH: ${body.length}")
        println("API BODY: $body")

        return false
    }
}
