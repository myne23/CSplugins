package com.animeonline

import com.lagradost.cloudstream3.*
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
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        println("=== ANIMEONLINE HOME START ===")
        println("URL: $mainUrl/inicio/")
        println("PAGE: $page")

        val response = app.get("$mainUrl/inicio/")
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
            "$mainUrl/search/?s=$encodedQuery"
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
                }
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
