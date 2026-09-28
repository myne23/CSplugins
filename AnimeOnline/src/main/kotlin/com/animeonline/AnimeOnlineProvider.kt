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

        println("=== ANIMEONLINE SEASON HTML ===")

        val document = app.get(
            url,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val header = document
            .select("h2, h3")
            .firstOrNull {
                it.text().contains("Temporadas y episodios", ignoreCase = true)
            }

        if (header == null) {
            println("SEASON HEADER: NOT FOUND")
            return null
        }

        val container = header.nextElementSibling()

        if (container == null) {
            println("CONTAINER: NOT FOUND")
            return null
        }

        val html = container.outerHtml()

        println("HTML LENGTH: ${html.length}")
        println(html.take(15000))

        println("=== ANIMEONLINE SEASON HTML END ===")

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
