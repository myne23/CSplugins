package com.marcelo.seriesdonghua

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class SeriesDonghuaProvider : MainAPI() {
    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/episodios/" to "Nuevos Episodios",
        "$mainUrl/donghuas-en-emision/" to "En Emisión",
        "$mainUrl/todos-los-donghuas/" to "Catálogo Completo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val items = mutableListOf<HomePageList>()

        // Solo extraemos el Slider si estamos en la primera página
        if (page == 1 && request.name == "Nuevos Episodios") {
            val sliderDocument = app.get(mainUrl).document
            val sliderItems = sliderDocument.select(".hero-slide-item").mapNotNull {
                val link = it.selectFirst("a.btn-stream")?.attr("href")?.toString() ?: return@mapNotNull null
                val title = it.selectFirst(".hero-title")?.text()?.toString() ?: return@mapNotNull null
                val img = it.selectFirst("img.hero-poster")?.attr("src")?.toString()
                
                newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) {
                    this.posterUrl = fixUrlNull(img)
                }
            }
            if (sliderItems.isNotEmpty()) {
                items.add(HomePageList("Destacados", sliderItems))
            }
        }

        val home = document.select("article.donghua-card").mapNotNull {
            it.toSearchResult()
        }
        items.add(HomePageList(request.name, home))

        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href")?.toString() ?: return null
        val href = fixUrl(link)
        
        var title = this.selectFirst(".card-title")?.text()?.toString()
        if (title.isNullOrBlank()) {
            title = this.selectFirst("a")?.attr("title")?.toString()
        }
        if (title.isNullOrBlank()) return null
        
        var imgUrl = this.selectFirst("img")?.attr("src")?.toString()
        if (imgUrl.isNullOrBlank()) {
            imgUrl = this.selectFirst("img")?.attr("data-src")?.toString()
        }
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(imgUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar.php?s=$query").document
        return document.select("article.donghua-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.hero-title")?.text()?.toString() ?: return null
        val poster = document.selectFirst("img.hero-poster")?.attr("src")?.toString()
        
        val plot = document.select(".glass-panel p").joinToString("\n") { it.text() }
        
        val tags = document.select(".genre-pill-list a.genre-pill").map { it.text() }

        val episodes = document.select("article.episode-card-item").mapNotNull { epElement ->
            val epLink = epElement.selectFirst("a")?.attr("href")?.toString() ?: return@mapNotNull null
            val epNum = epElement.attr("data-ep").toIntOrNull()
            val epTitle = epElement.selectFirst(".card-title")?.text()?.toString()
            val epPoster = epElement.selectFirst("img")?.attr("src")?.toString()
            
            newEpisode(epLink) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = fixUrlNull(epPoster)
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.tags = tags
            this.addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}
