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
        
        val home = document.select("article.donghua-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href")?.toString() ?: return null
        val href = fixUrl(link)
        
        // Evitamos encadenar inferencias complejas
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
}
