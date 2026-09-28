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

        val document = app.get(mainUrl).document

        val sections = ArrayList<HomePageList>()

        // ÚLTIMOS EPISODIOS
        val latestEpisodes = document
            .select("div.items article.item.se.episodes")
            .mapNotNull { parseEpisodeCard(it) }

        if (latestEpisodes.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimos episodios ⚡",
                    latestEpisodes
                )
            )
        }

        // ÚLTIMOS ANIMES AGREGADOS
        val latestAnimeHeader = document
            .select("header")
            .firstOrNull {
                it.text().contains("ÚLTIMOS ANIMES AGREGADOS")
            }

        val latestAnime = latestAnimeHeader
            ?.nextElementSibling()
            ?.nextElementSibling()
            ?.select("article.item")
            ?.mapNotNull { parseAnimeCard(it) }
            ?: emptyList()

        if (latestAnime.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimos animes agregados 💥",
                    latestAnime
                )
            )
        }

        // ÚLTIMAS PELÍCULAS AGREGADAS
        val latestMoviesHeader = document
            .select("header")
            .firstOrNull {
                it.text().contains("ÚLTIMAS PELICULAS AGREGADAS")
            }

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

        if (latestMovies.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimas películas agregadas 🎬",
                    latestMovies
                )
            )
        }

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
