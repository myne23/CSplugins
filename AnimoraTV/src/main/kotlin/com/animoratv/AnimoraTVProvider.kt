package com.animoratv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class AnimoraTVProvider : MainAPI() {

    override var mainUrl = "https://www.animoratv.com"
    override var name = "AnimoraTV"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)
    override var lang = "es"

    private fun parseAnimeList(json: String): List<SearchResponse> {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: return emptyList()
        val animes = data.optJSONArray("animes") ?: return emptyList()

        val results = mutableListOf<SearchResponse>()

        for (i in 0 until animes.length()) {
            val anime = animes.optJSONObject(i) ?: continue

            val title = anime.optString(
                "titulo",
                anime.optString("tituloIngles", "Sin título")
            )

            val slug = anime.optString("slug")
            if (slug.isBlank()) continue

            val poster = anime.optString("portada").takeIf { it.isNotBlank() }

            results.add(
                newAnimeSearchResponse(
                    title,
                    "$mainUrl/anime/$slug",
                    TvType.Anime
                ) {
                    this.posterUrl = poster
                }
            )
        }

        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        val endpoints = listOf(
            "Tendencias" to "$mainUrl/api/animes/tendencias",
            "Populares" to "$mainUrl/api/animes/populares"
        )

        for ((title, url) in endpoints) {
            try {
                val response = app.get(url)

                if (!response.isSuccessful) continue

                val results = parseAnimeList(response.text)

                if (results.isNotEmpty()) {
                    sections.add(
                        HomePageList(
                            title,
                            results
                        )
                    )
                }
            } catch (e: Exception) {
                println(
                    "AnimoraTV: error home $title -> ${e.message}"
                )
            }
        }

        return newHomePageResponse(
            sections,
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(
                query,
                "UTF-8"
            )

            val url =
                "$mainUrl/api/busqueda?termino=$encodedQuery&pagina=1&limite=30"

            val response = app.get(url)

            if (!response.isSuccessful) {
                println(
                    "AnimoraTV: search HTTP ${response.code}"
                )
                emptyList()
            } else {
                parseAnimeList(response.text)
            }
        } catch (e: Exception) {
            println(
                "AnimoraTV: search error ${e.message}"
            )
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url
            .substringAfterLast("/anime/")
            .substringAfterLast("/")
            .trim()
            .removeSuffix("/")

        println(
            "AnimoraTV: load slug=$slug"
        )

        val animeResponse =
            app.get("$mainUrl/api/animes/$slug")

        val animeRoot =
            JSONObject(animeResponse.text)

        val animeData =
            animeRoot.optJSONObject("data")
                ?: JSONObject()

        val title =
            animeData.optString(
                "titulo",
                animeData.optString(
                    "tituloIngles",
                    slug
                )
            )

        val poster =
            animeData.optString("portada")
                .takeIf { it.isNotBlank() }

        val episodesResponse =
            app.get("$mainUrl/api/animes/$slug/episodios")

        val episodesRoot =
            JSONObject(episodesResponse.text)

        val episodesData =
            episodesRoot.optJSONObject("data")
                ?: JSONObject()

        val episodes =
            episodesData.optJSONArray("episodios")
                ?: org.json.JSONArray()

        val episodeList =
            mutableListOf<Episode>()

        for (i in 0 until episodes.length()) {
            val episode =
                episodes.optJSONObject(i)
                    ?: continue

            val number =
                episode.optInt(
                    "numero",
                    i + 1
                )

            val titleEpisode =
                episode.optString(
                    "titulo",
                    "Episodio $number"
                )

            episodeList.add(
                newEpisode("$slug|$number") {
                    name = titleEpisode
                    this.episode = number
                    season = 1
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            episodeList
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println(
            "AnimoraTV: loadLinks data=$data"
        )

        val parts =
            data.split("|")

        if (parts.size < 2) {
            println(
                "AnimoraTV: ERROR formato data inválido"
            )
            return false
        }

        val rawSlug =
            parts[0]

        val episodeNumber =
            parts[1].toIntOrNull()

        if (episodeNumber == null) {
            println(
                "AnimoraTV: ERROR episodio inválido=${parts[1]}"
            )
            return false
        }

        val slug =
            rawSlug
                .substringAfterLast("/anime/")
                .substringAfterLast("/")
                .trim()
                .removeSuffix("/")

        println(
            "AnimoraTV: slug=$slug episode=$episodeNumber"
        )

        val apiUrl =
            "$mainUrl/api/video/$slug/$episodeNumber/fuentes"

        println(
            "AnimoraTV: API $apiUrl"
        )

        return try {

            val response =
                app.get(apiUrl)

            println(
                "AnimoraTV: HTTP ${response.code}"
            )

            if (!response.isSuccessful) {
                println(
                    "AnimoraTV: ERROR HTTP ${response.code}"
                )
                return false
            }

            val root =
                JSONObject(response.text)

            val dataObject =
                root.optJSONObject("data")
                    ?: JSONObject()

            val fuentes =
                dataObject.optJSONArray("fuentes")
                    ?: org.json.JSONArray()

            println(
                "AnimoraTV: fuentes=${fuentes.length()}"
            )

            var found = false
            var totalServers = 0
            var totalExtractedLinks = 0

            for (i in 0 until fuentes.length()) {

                val fuente =
                    fuentes.optJSONObject(i)
                        ?: continue

                val servidores =
                    fuente.optJSONArray("servidores")
                        ?: org.json.JSONArray()

                println(
                    "AnimoraTV: FUENTE ${i + 1} " +
                    "servidores=${servidores.length()}"
                )

                for (j in 0 until servidores.length()) {

                    val servidor =
                        servidores.optJSONObject(j)
                            ?: continue

                    val urlVideo =
                        servidor.optString("urlVideo")

                    val calidad =
                        servidor.optString("calidad")

                    val provider =
                        servidor.optString("proveedor")

                    if (urlVideo.isBlank()) {
                        println(
                            "AnimoraTV: SKIP provider=$provider URL vacía"
                        )
                        continue
                    }

                    totalServers++

                    val qualityValue =
                        calidad
                            .filter { it.isDigit() }
                            .toIntOrNull()
                            ?: Qualities.Unknown.value

                    val isHls =
                        provider.equals(
                            "hls",
                            ignoreCase = true
                        ) ||
                        urlVideo.endsWith(
                            ".m3u8",
                            ignoreCase = true
                        )

                    println(
                        "AnimoraTV: servidor " +
                        "provider=$provider " +
                        "quality=$calidad " +
                        "hls=$isHls"
                    )

                    if (isHls) {

                        try {

                            println(
                                "AnimoraTV: HLS directo -> $urlVideo"
                            )

                            callback(
                                newExtractorLink(
                                    name = provider,
                                    source = name,
                                    url = urlVideo,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer = "$mainUrl/"
                                    quality = qualityValue
                                }
                            )

                            found = true
                            totalExtractedLinks++

                            println(
                                "AnimoraTV: HLS LINK EMITIDO " +
                                "provider=$provider " +
                                "quality=$qualityValue"
                            )

                        } catch (e: Exception) {

                            println(
                                "AnimoraTV: HLS ERROR " +
                                "provider=$provider -> ${e.message}"
                            )
                        }

                        continue
                    }

                    var emitted = 0

                    val extractorCallback:
                        (ExtractorLink) -> Unit = { link ->

                        emitted++
                        totalExtractedLinks++

                        println(
                            "AnimoraTV: EXTRACTED LINK " +
                            "provider=$provider " +
                            "name=${link.name} " +
                            "quality=${link.quality} " +
                            "type=${link.type} " +
                            "url=${link.url}"
                        )

                        callback(link)
                    }

                    try {

                        println(
                            "AnimoraTV: intentando loadExtractor " +
                            "provider=$provider"
                        )

                        loadExtractor(
                            urlVideo,
                            "$mainUrl/",
                            subtitleCallback,
                            extractorCallback
                        )

                        if (emitted > 0) {

                            found = true

                            println(
                                "AnimoraTV: loadExtractor DONE " +
                                "provider=$provider " +
                                "links=$emitted"
                            )

                        } else {

                            println(
                                "AnimoraTV: loadExtractor SIN LINKS " +
                                "provider=$provider"
                            )
                        }

                    } catch (e: Exception) {

                        println(
                            "AnimoraTV: loadExtractor ERROR " +
                            "provider=$provider -> ${e.message}"
                        )
                    }
                }
            }

            println(
                "AnimoraTV: FINAL " +
                "found=$found " +
                "totalServers=$totalServers " +
                "totalExtractedLinks=$totalExtractedLinks"
            )

            found

        } catch (e: Exception) {

            println(
                "AnimoraTV: ERROR loadLinks -> ${e.message}"
            )

            false
        }
    }
}
