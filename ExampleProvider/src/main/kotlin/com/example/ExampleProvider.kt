package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import org.json.JSONObject

class ExampleProvider : MainAPI() {

    override var mainUrl = "https://wooflix.media/"
    override var name = "WOOFLIX"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )
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

        val isMovie = "/movie/" in url

        val tmdbId = url.substringAfterLast("/").toIntOrNull()
        ?: return if (isMovie) {
            newMovieLoadResponse(
                "Error",
                url,
                TvType.Movie,
                url
            )
        } else {
            newTvSeriesLoadResponse(
                "Error",
                url,
                TvType.TvSeries,
                emptyList()
            )
        }

        val endpoint = if (isMovie) {
            "movie/$tmdbId"
        } else {
            "tv/$tmdbId"
        }

        val detailsUrl =
        "https://api.themoviedb.org/3/$endpoint" +
        "?api_key=e1a8efff4415028c5c266b3fcd50db6e" +
        "&language=en-US"

        val details = try {
            app.get(detailsUrl)
        } catch (e: Exception) {
            Log.d("CINEZO_TEST", "LOAD DETAILS ERROR: ${e.message}")

            return if (isMovie) {
                newMovieLoadResponse(
                    "Error",
                    url,
                    TvType.Movie,
                    url
                )
            } else {
                newTvSeriesLoadResponse(
                    "Error",
                    url,
                    TvType.TvSeries,
                    emptyList()
                )
            }
        }

        if (!details.isSuccessful) {
            Log.d("CINEZO_TEST", "LOAD DETAILS STATUS: ${details.code}")

            return if (isMovie) {
                newMovieLoadResponse(
                    "Error",
                    url,
                    TvType.Movie,
                    url
                )
            } else {
                newTvSeriesLoadResponse(
                    "Error",
                    url,
                    TvType.TvSeries,
                    emptyList()
                )
            }
        }

        val json = try {
            JSONObject(details.text)
        } catch (e: Exception) {
            Log.d("CINEZO_TEST", "LOAD DETAILS JSON ERROR: ${e.message}")

            return if (isMovie) {
                newMovieLoadResponse(
                    "Error",
                    url,
                    TvType.Movie,
                    url
                )
            } else {
                newTvSeriesLoadResponse(
                    "Error",
                    url,
                    TvType.TvSeries,
                    emptyList()
                )
            }
        }

        val title = if (isMovie) {
            json.optString(
                "title",
                json.optString("original_title", "Unknown")
            )
        } else {
            json.optString(
                "name",
                json.optString("original_name", "Unknown")
            )
        }

        val posterPath = json.optString("poster_path")

        val contentPosterUrl = if (posterPath.isNotBlank()) {
            "https://image.tmdb.org/t/p/w500$posterPath"
        } else {
            null
        }

        val overview = json.optString("overview")

        val contentYear = if (isMovie) {
            json.optString("release_date")
            .take(4)
            .toIntOrNull()
        } else {
            json.optString("first_air_date")
            .take(4)
            .toIntOrNull()
        }

        if (isMovie) {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = contentPosterUrl
                this.year = contentYear
                plot = overview
            }

        } else {

            val status = when (json.optString("status")) {
                "Ended", "Canceled" -> ShowStatus.Completed
                "Returning Series", "In Production" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }

            val seasons = json.optJSONArray("seasons")
            val episodes = mutableListOf<Episode>()

            if (seasons != null) {
                for (i in 0 until seasons.length()) {

                    val seasonObject = seasons.optJSONObject(i)
                    ?: continue

                    val seasonNumber =
                    seasonObject.optInt("season_number", -1)

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

                        val seasonEpisodes =
                        seasonJson.optJSONArray("episodes")
                        ?: continue

                        for (j in 0 until seasonEpisodes.length()) {

                            val ep =
                            seasonEpisodes.optJSONObject(j)
                            ?: continue

                            val episodeNumber =
                            ep.optInt("episode_number", -1)

                            if (episodeNumber <= 0) continue

                                val episodeName =
                                ep.optString(
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
                this.posterUrl = contentPosterUrl
                this.year = contentYear
                plot = overview
                showStatus = status
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d("WOOFLIX_TEST", "DATA = $data")

        val parts = data.substringAfterLast("/").split("|")
        if (parts.isEmpty()) return false

            val tmdbId = parts[0]
            val isMovie = parts.getOrNull(1) == "movie"
            val season = if (!isMovie) parts.getOrNull(1)?.toIntOrNull() else null
            val episode = if (!isMovie) parts.getOrNull(2)?.toIntOrNull() else null

            var linksFound = 0

            // ============================================================
            // VIDLINK DIRECT API
            // ============================================================
            try {
                Log.d(
                    "WOOFLIX_TEST",
                    "VIDLINK -> tmdb=$tmdbId season=$season episode=$episode movie=$isMovie"
                )

                // VidLink requires an encoded TMDB ID.
                val tokenResponse = app.get(
                    "https://enc-dec.app/api/enc-vidlink?text=$tmdbId",
                    timeout = 15
                )

                val tokenJson = JSONObject(tokenResponse.text)
                val token = tokenJson.optString("result")

                if (token.isBlank()) {
                    Log.d("WOOFLIX_TEST", "VIDLINK -> token EMPTY")
                } else {
                    Log.d(
                        "WOOFLIX_TEST",
                        "VIDLINK -> token=${token.take(20)}..."
                    )

                    val apiUrl = if (isMovie) {
                        "https://vidlink.pro/api/b/movie/$token?multiLang=0"
                    } else {
                        "https://vidlink.pro/api/b/tv/$token/$season/$episode?multiLang=0"
                    }

                    Log.d("WOOFLIX_TEST", "VIDLINK API -> $apiUrl")

                    val response = app.get(
                        apiUrl,
                        headers = mapOf(
                            "Origin" to "https://vidlink.pro",
                            "Referer" to "https://vidlink.pro/",
                            "User-Agent" to USER_AGENT
                        ),
                        timeout = 20
                    )

                    Log.d(
                        "WOOFLIX_TEST",
                        "VIDLINK STATUS -> ${response.code}"
                    )

                    val json = JSONObject(response.text)

                    // ------------------------------------------------------------
                    // VIDEO SOURCES
                    // ------------------------------------------------------------
                    val stream = json.optJSONObject("stream")

                    if (stream != null) {
                        val qualities = stream.optJSONObject("qualities")

                        if (qualities != null) {
                            val qualityKeys = qualities.keys()

                            while (qualityKeys.hasNext()) {
                                val qualityKey = qualityKeys.next()
                                val qualityObject = qualities.optJSONObject(qualityKey)

                                if (qualityObject == null) continue

                                    val videoUrl = qualityObject.optString("url")
                                    val videoType = qualityObject.optString("type", "mp4")

                                    if (videoUrl.isBlank()) continue

                                        val quality = qualityKey.toIntOrNull()
                                        ?: Qualities.Unknown.value

                                        Log.d(
                                            "WOOFLIX_TEST",
                                            "VIDLINK LINK -> ${quality}p | $videoType | $videoUrl"
                                        )

                                        callback(
                                            newExtractorLink(
                                                "VidLink",
                                                "VidLink ${quality}p",
                                                videoUrl,
                                                ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = quality
                                                this.referer = "https://vidlink.pro/"
                                                this.headers = mapOf(
                                                    "Origin" to "https://vidlink.pro",
                                                    "Referer" to "https://vidlink.pro/",
                                                    "User-Agent" to USER_AGENT
                                                )
                                            }
                                        )

                                        linksFound++
                            }
                        }
                    }

                    // ------------------------------------------------------------
                    // SUBTITLES
                    // ------------------------------------------------------------
                    if (stream != null) {
                        val captions = stream.optJSONArray("captions")

                        if (captions != null) {
                            for (i in 0 until captions.length()) {
                                val subtitle = captions.optJSONObject(i) ?: continue

                                val subtitleUrl = subtitle.optString("url")
                                val language = subtitle.optString("language", "Unknown")

                                if (subtitleUrl.isBlank()) continue

                                    Log.d(
                                        "WOOFLIX_TEST",
                                        "VIDLINK SUBTITLE -> $language | $subtitleUrl"
                                    )

                                    subtitleCallback(
                                        newSubtitleFile(
                                            language,
                                            subtitleUrl
                                        )
                                    )
                            }
                        }
                    }

                    Log.d(
                        "WOOFLIX_TEST",
                        "VIDLINK DONE -> links=$linksFound"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "WOOFLIX_TEST",
                    "VIDLINK ERROR -> ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
            }

            // ============================================================
            // CINEZO FALLBACK
            // ============================================================
            try {
                if (isMovie) {
                    Log.d(
                        "WOOFLIX_TEST",
                        "CINEZO -> movie skipped for now"
                    )
                } else {
                    Log.d(
                        "WOOFLIX_TEST",
                        "TRYING -> Cinezo"
                    )

                    val cinezoUrl =
                    "https://proxy1.flikhub.net/tv?id=$tmdbId&season=$season&episode=$episode"

                    val response = app.get(
                        cinezoUrl,
                        headers = mapOf(
                            "Accept" to "text/event-stream",
                            "Referer" to "https://player.cinezo.live/",
                            "Origin" to "https://player.cinezo.live",
                            "User-Agent" to USER_AGENT
                        ),
                        timeout = 30
                    )

                    Log.d(
                        "WOOFLIX_TEST",
                        "Cinezo status=${response.code}"
                    )

                    val lines = response.text.lines()

                    for (line in lines) {
                        if (!line.startsWith("data:")) continue

                            val jsonText = line.removePrefix("data:").trim()
                            if (jsonText.isBlank()) continue

                                try {
                                    val json = JSONObject(jsonText)

                                    val subtitles = json.optJSONArray("subtitles")
                                    if (subtitles != null) {
                                        for (i in 0 until subtitles.length()) {
                                            val sub = subtitles.optJSONObject(i) ?: continue

                                            val url = sub.optString("url")
                                            val language = sub.optString("language", "Unknown")

                                            if (url.isNotBlank()) {
                                                Log.d(
                                                    "WOOFLIX_TEST",
                                                    "SUBTITLE -> $language"
                                                )

                                                subtitleCallback(
                                                    newSubtitleFile(
                                                        language,
                                                        url
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    val sources = json.optJSONArray("sources")
                                    if (sources != null) {
                                        for (i in 0 until sources.length()) {
                                            val source = sources.optJSONObject(i) ?: continue

                                            val name = source.optString("name", "Cinezo")
                                            val url = source.optString("url")

                                            if (url.isBlank()) continue

                                                val type = if (
                                                    url.contains(".m3u8", ignoreCase = true)
                                                ) {
                                                    ExtractorLinkType.M3U8
                                                } else {
                                                    ExtractorLinkType.VIDEO
                                                }

                                                Log.d(
                                                    "WOOFLIX_TEST",
                                                    "CINEZO LINK -> $name | $url"
                                                )

                                                callback(
                                                    newExtractorLink(
                                                        "Cinezo",
                                                        name,
                                                        url,
                                                        type
                                                    ) {
                                                        this.referer = "https://player.cinezo.live/"
                                                    }
                                                )

                                                linksFound++
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                    }

                    Log.d(
                        "WOOFLIX_TEST",
                        "CINEZO DONE -> total links=$linksFound"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "WOOFLIX_TEST",
                    "CINEZO ERROR -> ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
            }

            return linksFound > 0
    }
}
