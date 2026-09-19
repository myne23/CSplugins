package com.animoratv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

            if (number <= 0) continue

            episodeList.add(
                newEpisode("$slug|$number") {
                    name = episodeTitle
                    this.episode = number
                    season = 1
                    description = episode.optString("descripcion")
                    posterUrl = episode.optString("miniatura")
                        .ifBlank { null }
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {
            posterUrl = anime.optString("portada")
                .ifBlank { null }

            plot = anime.optString("sinopsis")
                .ifBlank { null }

            year = anime.optInt("anio")
                .takeIf { it > 0 }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("AnimoraTV: loadLinks() data=$data")

        val parts = data.split("|", limit = 2)

        if (parts.size != 2) {
            println("AnimoraTV: ERROR data invalido")
            return false
        }

        val rawSlug = parts[0]
        val episodeNumber = parts[1].toIntOrNull()

        if (episodeNumber == null) {
            println("AnimoraTV: ERROR numero episodio invalido")
            return false
        }

        val slug = rawSlug
            .substringAfterLast("/anime/")
            .substringAfterLast("/")
            .trim()
            .removeSuffix("/")

        if (slug.isBlank()) {
            println("AnimoraTV: ERROR slug invalido")
            return false
        }

        println("AnimoraTV: slug=$slug episodio=$episodeNumber")

        val apiUrl =
            "$mainUrl/api/video/$slug/$episodeNumber/fuentes"

        println("AnimoraTV: consultando $apiUrl")

        val response = try {
            app.get(apiUrl)
        } catch (e: Exception) {
            println("AnimoraTV: ERROR request=${e.message}")
            return false
        }

        println("AnimoraTV: HTTP ${response.code}")

        val json = try {
            JSONObject(response.text)
        } catch (e: Exception) {
            println("AnimoraTV: ERROR JSON=${e.message}")
            return false
        }

        val dataObject = json.optJSONObject("data")

        if (dataObject == null) {
            println("AnimoraTV: ERROR no existe data")
            return false
        }

        val fuentes = dataObject.optJSONArray("fuentes")

        if (fuentes == null) {
            println("AnimoraTV: ERROR no existe fuentes")
            return false
        }

        println("AnimoraTV: fuentes=${fuentes.length()}")

        var found = false
        var totalServers = 0
        var extractorCalls = 0

        for (i in 0 until fuentes.length()) {

            val fuente = fuentes.optJSONObject(i)
                ?: continue

            val indiceFuente = fuente.optInt(
                "indiceFuente",
                i + 1
            )

            val servidores = fuente.optJSONArray("servidores")
                ?: continue

            println(
                "AnimoraTV: fuente=$indiceFuente servidores=${servidores.length()}"
            )

            for (j in 0 until servidores.length()) {

                val servidor = servidores.optJSONObject(j)
                    ?: continue

                val urlVideo = servidor
                    .optString("urlVideo")
                    .trim()

                if (urlVideo.isBlank()) continue

                val provider = servidor
                    .optString("proveedor")
                    .ifBlank { "Servidor" }

                val qualityName = servidor
                    .optString("calidad")
                    .lowercase()

                val quality = when {
                    qualityName.contains("2160") ||
                    qualityName.contains("4k") -> 2160

                    qualityName.contains("1440") -> 1440
                    qualityName.contains("1080") -> 1080
                    qualityName.contains("720") -> 720
                    qualityName.contains("480") -> 480
                    qualityName.contains("360") -> 360
                    qualityName.contains("240") -> 240

                    else -> Qualities.Unknown.value
                }

                val isHls =
                    provider.equals(
                        "hls",
                        ignoreCase = true
                    ) ||
                    urlVideo
                        .substringBefore("?")
                        .lowercase()
                        .endsWith(".m3u8")

                println(
                    "AnimoraTV: servidor provider=$provider " +
                    "quality=$quality hls=$isHls"
                )

                if (isHls) {

                    println(
                        "AnimoraTV: HLS directo -> $urlVideo"
                    )

                    callback(
                        newExtractorLink(
                            source = "AnimoraTV",
                            name = "AnimoraTV - $provider",
                            url = urlVideo,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = "$mainUrl/"
                            this.quality = quality
                        }
                    )

                    found = true
                    totalServers++

                } else {

                    println(
                        "AnimoraTV: intentando loadExtractor " +
                        "provider=$provider url=$urlVideo"
                    )

                    try {
                        loadExtractor(
                            urlVideo,
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )

                        extractorCalls++

                        println(
                            "AnimoraTV: loadExtractor OK " +
                            "provider=$provider"
                        )

                        found = true
                        totalServers++

                    } catch (e: Exception) {

                        println(
                            "AnimoraTV: loadExtractor ERROR " +
                            "provider=$provider error=${e.message}"
                        )
                    }
                }
            }
        }

        println(
            "AnimoraTV: FINAL found=$found " +
            "totalServers=$totalServers " +
            "extractorCalls=$extractorCalls"
        )

        return found
    }
}
