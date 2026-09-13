package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import org.json.JSONObject

class ExampleProvider : MainAPI() {

    override var mainUrl = "https://wooflix.media/"
    override var name = "Cinezo Test"

    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "es"
    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

            val url =
            "https://api.themoviedb.org/3/search/multi" +
            "?api_key=e1a8efff4415028c5c266b3fcd50db6e" +
            "&language=en-US" +
            "&query=${query.replace(" ", "%20")}"

            val response = try {
                app.get(url)
            } catch (e: Exception) {
                Log.d("CINEZO_TEST", "SEARCH ERROR: ${e.message}")
                return emptyList()
            }

            if (!response.isSuccessful) {
                Log.d("CINEZO_TEST", "SEARCH STATUS: ${response.code}")
                return emptyList()
            }

            val json = try {
                JSONObject(response.text)
            } catch (e: Exception) {
                Log.d("CINEZO_TEST", "SEARCH JSON ERROR: ${e.message}")
                return emptyList()
            }

            val results = json.optJSONArray("results") ?: return emptyList()

            val searchResults = mutableListOf<SearchResponse>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue

                val mediaType = item.optString("media_type")

                if (mediaType != "tv" && mediaType != "movie") {
                    continue
                }

                val id = item.optInt("id", 0)
                if (id == 0) continue

                    val title = if (mediaType == "tv") {
                        item.optString(
                            "name",
                            item.optString("original_name")
                        )
                    } else {
                        item.optString(
                            "title",
                            item.optString("original_title")
                        )
                    }

                    if (title.isBlank()) continue

                        val posterPath = item.optString("poster_path")
                        val poster = if (posterPath.isNotBlank()) {
                            "https://image.tmdb.org/t/p/w500$posterPath"
                        } else {
                            null
                        }

                        val year = if (mediaType == "tv") {
                            item.optString("first_air_date").take(4).toIntOrNull()
                        } else {
                            item.optString("release_date").take(4).toIntOrNull()
                        }

                        val resultUrl = if (mediaType == "tv") {
                            "https://wooflix.media/play/tv/$id"
                        } else {
                            "https://wooflix.media/play/movie/$id"
                        }

                        if (mediaType == "tv") {
                            searchResults.add(
                                newTvSeriesSearchResponse(
                                    title,
                                    resultUrl,
                                    TvType.TvSeries,
                                    false
                                ) {
                                    posterUrl = poster
                                    this.year = year
                                }
                            )
                        } else {
                            searchResults.add(
                                newMovieSearchResponse(
                                    title,
                                    resultUrl,
                                    TvType.Movie,
                                    false
                                ) {
                                    posterUrl = poster
                                    this.year = year
                                }
                            )
                        }
            }

            return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url.substringAfterLast("/").toIntOrNull()
        ?: return newTvSeriesLoadResponse(
            "Error",
            url,
            TvType.TvSeries,
            emptyList()
        )

        val detailsUrl =
        "https://api.themoviedb.org/3/tv/$tmdbId" +
        "?api_key=e1a8efff4415028c5c266b3fcd50db6e" +
        "&language=en-US"

        val details = try {
            app.get(detailsUrl)
        } catch (e: Exception) {
            Log.d("CINEZO_TEST", "LOAD DETAILS ERROR: ${e.message}")
            return newTvSeriesLoadResponse(
                "Error",
                url,
                TvType.TvSeries,
                emptyList()
            )
        }

        if (!details.isSuccessful) {
            Log.d("CINEZO_TEST", "LOAD DETAILS STATUS: ${details.code}")
            return newTvSeriesLoadResponse(
                "Error",
                url,
                TvType.TvSeries,
                emptyList()
            )
        }

        val json = try {
            JSONObject(details.text)
        } catch (e: Exception) {
            Log.d("CINEZO_TEST", "LOAD DETAILS JSON ERROR: ${e.message}")
            return newTvSeriesLoadResponse(
                "Error",
                url,
                TvType.TvSeries,
                emptyList()
            )
        }

        val title = json.optString(
            "name",
            json.optString("original_name", "Unknown")
        )

        val posterPath = json.optString("poster_path")
        val seriesPosterUrl = if (posterPath.isNotBlank()) {
            "https://image.tmdb.org/t/p/w500$posterPath"
        } else {
            null
        }

        val overview = json.optString("overview")

        val seriesYear = json.optString("first_air_date")
        .take(4)
        .toIntOrNull()

        val status = when (json.optString("status")) {
            "Ended", "Canceled" -> ShowStatus.Completed
            "Returning Series", "In Production" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val seasons = json.optJSONArray("seasons")
        val episodes = mutableListOf<Episode>()

        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val season = seasons.optJSONObject(i) ?: continue
                val seasonNumber = season.optInt("season_number", -1)

                if (seasonNumber <= 0) continue

                    val seasonUrl =
                    "https://api.themoviedb.org/3/tv/$tmdbId/season/$seasonNumber" +
                    "?api_key=e1a8efff4415028c5c266b3fcd50db6e" +
                    "&language=en-US"

                    val seasonResponse = try {
                        app.get(seasonUrl)
                    } catch (e: Exception) {
                        Log.d(
                            "CINEZO_TEST",
                            "SEASON $seasonNumber ERROR: ${e.message}"
                        )
                        continue
                    }

                    if (!seasonResponse.isSuccessful) {
                        Log.d(
                            "CINEZO_TEST",
                            "SEASON $seasonNumber STATUS: ${seasonResponse.code}"
                        )
                        continue
                    }

                    val seasonJson = try {
                        JSONObject(seasonResponse.text)
                    } catch (e: Exception) {
                        continue
                    }

                    val seasonEpisodes = seasonJson.optJSONArray("episodes")
                    ?: continue

                    for (j in 0 until seasonEpisodes.length()) {
                        val ep = seasonEpisodes.optJSONObject(j) ?: continue

                        val episodeNumber = ep.optInt("episode_number", -1)
                        if (episodeNumber <= 0) continue

                            val episodeName = ep.optString(
                                "name",
                                "Episode $episodeNumber"
                            )

                            episodes.add(
                                newEpisode(
                                    "$tmdbId|$seasonNumber|$episodeNumber"
                                ) {
                                    name = episodeName
                                    this.season = seasonNumber
                                    this.episode = episodeNumber
                                }
                            )
                    }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = seriesPosterUrl
            this.year = seriesYear
            plot = overview
            showStatus = status
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d("CINEZO_TEST", "========== loadLinks() LLAMADO ==========")
        Log.d("CINEZO_TEST", "DATA = $data")
        Log.d("CINEZO_TEST", "isCasting = $isCasting")

        val parts = data.split("|")

        if (parts.size != 3) {
            return false
        }

        val tmdbId = parts[0].substringAfterLast("/")
        val season = parts[1]
        val episode = parts[2]

        val url =
        "https://proxy1.flikhub.net/tv?id=$tmdbId&season=$season&episode=$episode"

        val response = try {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "text/event-stream",
                    "Referer" to "https://player.cinezo.live/"
                )
            )
        } catch (e: Exception) {
            println("CINEZO ERROR: ${e.message}")
            return false
        }

        println("CINEZO STATUS: ${response.code}")
        println("CINEZO RESPONSE LENGTH: ${response.text.length}")

        if (!response.isSuccessful) {
            return false
        }

        for (line in response.text.lines()) {

            if (!line.startsWith("data: ")) {
                continue
            }

            val json = try {
                JSONObject(line.removePrefix("data: "))
            } catch (_: Exception) {
                continue
            }

            println("CINEZO EVENT: ${json.optString("type")}")

            when (json.optString("type")) {

                "meta" -> {
                    val subtitles = json.optJSONArray("subtitles")

                    if (subtitles != null) {
                        for (i in 0 until subtitles.length()) {

                            val subtitle = subtitles.optJSONObject(i)
                            ?: continue

                            val label = subtitle.optString("label")
                            val file = subtitle.optString("file")

                            if (file.isNotBlank()) {
                                subtitleCallback(
                                    newSubtitleFile(label, file)
                                )
                            }
                        }
                    }
                }

                "source" -> {
                    val source = json.optJSONObject("source")
                    ?: continue

                    val sourceName = source.optString("source")
                    val label = source.optString("label")
                    val sourceUrl = source.optString("url")
                    val type = source.optString("type")

                    println(
                        "CINEZO SOURCE: $sourceName | $label | $type | $sourceUrl"
                    )

                    if (sourceUrl.isBlank()) {
                        continue
                    }

                    val linkType = when (type.lowercase()) {
                        "dash" -> ExtractorLinkType.DASH
                        "m3u8" -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            sourceName.ifBlank { "Cinezo" },
                            label.ifBlank { sourceName },
                            sourceUrl,
                            linkType
                        ) {
                            referer = "https://player.cinezo.live/"
                            quality = Qualities.Unknown.value
                        }
                    )
                }

                "done" -> {
                    println("CINEZO DONE")
                    break
                }
            }
        }

        return true
    }
}
