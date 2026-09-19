package com.animoratv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject

class AnimoraTVProvider : MainAPI() {

    override var mainUrl = "https://www.animoratv.com"
    override var name = "AnimoraTV"

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override var lang = "es"

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/api/animes/tendencias" to "Tendencias",
        "$mainUrl/api/animes/populares" to "Populares"
    )

    private suspend fun getJson(url: String): JSONObject {
        return JSONObject(app.get(url).text)
    }

    private fun parseAnimeList(json: JSONObject): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()

        val animes = json
            .getJSONObject("data")
            .getJSONArray("animes")

        for (i in 0 until animes.length()) {
            val anime = animes.getJSONObject(i)

            val title = anime.optString("titulo")
                .ifBlank { anime.optString("tituloIngles") }

            val slug = anime.optString("slug")

            if (title.isBlank() || slug.isBlank()) continue

            val response = newAnimeSearchResponse(
                title,
                "$mainUrl/anime/$slug",
                TvType.TvSeries
            )

            response.posterUrl = anime.optString("portada")
                .ifBlank { null }

            results.add(response)
        }

        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val results = parseAnimeList(
            getJson(request.data)
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {
        val encodedQuery = java.net.URLEncoder.encode(
            query,
            "UTF-8"
        )

        val json = getJson(
            "$mainUrl/api/busqueda?termino=$encodedQuery&pagina=1&limite=30"
        )

        return parseAnimeList(json)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val slug = url
            .substringAfterLast("/anime/")
            .substringBefore("?")
            .trim()

        if (slug.isBlank()) return null

        val animeJson = getJson(
            "$mainUrl/api/animes/$slug"
        )

        val anime = animeJson
            .getJSONObject("data")
            .getJSONObject("anime")

        val title = anime.optString("titulo")
            .ifBlank { anime.optString("tituloIngles") }

        if (title.isBlank()) return null

        val episodesJson = getJson(
            "$mainUrl/api/animes/$slug/episodios"
        )

        val episodes = episodesJson
            .getJSONObject("data")
            .getJSONArray("episodios")

        val episodeList = ArrayList<Episode>()

        for (i in 0 until episodes.length()) {
            val episode = episodes.getJSONObject(i)

            val number = episode.optInt("numero", i + 1)

            val episodeTitle = episode.optString("titulo")
                .ifBlank { "Episodio $number" }

            val episodeId = episode.optString("_id")

            if (episodeId.isBlank()) continue

            episodeList.add(
                newEpisode(episodeId) {
                    name = episodeTitle
                    this.episode = number
                    this.season = 1
                    description = episode.optString("descripcion")
                    posterUrl = episode.optString("miniatura").ifBlank { null }
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {
            posterUrl = anime.optString("portada").ifBlank { null }
            plot = anime.optString("sinopsis").ifBlank { null }
            year = anime.optInt("anio").takeIf { it > 0 }
        }
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
