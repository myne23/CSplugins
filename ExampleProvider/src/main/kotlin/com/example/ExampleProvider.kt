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
        val parts = data.split("|")

        val tmdbId = parts.getOrNull(0) ?: return false
        val kind = parts.getOrNull(1) ?: return false

        val season = if (kind == "tv") parts.getOrNull(2)?.toIntOrNull() else null
        val episode = if (kind == "tv") parts.getOrNull(3)?.toIntOrNull() else null

        Log.d("WOOFLIX_TEST", "loadLinks -> tmdb=$tmdbId kind=$kind season=$season episode=$episode")

        try {
            // ------------------------------------------------------------
            // 1. Obtener token de VidLink
            // ------------------------------------------------------------
            val tokenResponse = app.get(
                "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
            ).text

            val token = JSONObject(tokenResponse)
            .optString("result")
            .takeIf { it.isNotBlank() }

            if (token == null) {
                Log.d("WOOFLIX_TEST", "No VidLink token")
                return false
            }

            // ------------------------------------------------------------
            // 2. Pedir DASH/HEVC, que es la variante que VidLink
            //    utiliza en navegadores compatibles
            // ------------------------------------------------------------
            val streamUrl = if (kind == "tv") {
                "https://vidlink.pro/api/b/tv/$token/$season/$episode?multiLang=0&_=${System.nanoTime()}"
            } else {
                "https://vidlink.pro/api/b/movie/$token?multiLang=0&_=${System.nanoTime()}"
            }

            val response = app.get(
                streamUrl,
                headers = mapOf(
                    "Origin" to "https://vidlink.pro",
                    "Referer" to "https://vidlink.pro/",
                    "X-Playback-Environment" to "dash-hevc",
                    "Cache-Control" to "no-cache",
                    "User-Agent" to "Mozilla/5.0"
                )
            )

            if (!response.isSuccessful) {
                Log.d("WOOFLIX_TEST", "VidLink HTTP ${response.code}")
                return false
            }

            val json = JSONObject(response.text)
            val stream = json.optJSONObject("stream")

            if (stream == null) {
                Log.d("WOOFLIX_TEST", "VidLink stream=null")
                return false
            }

            // ------------------------------------------------------------
            // 3. Captions
            // ------------------------------------------------------------
            val captions = stream.optJSONArray("captions")

            if (captions != null) {
                for (i in 0 until captions.length()) {
                    val caption = captions.optJSONObject(i) ?: continue

                    val url = caption.optString("url")
                    .takeIf { it.isNotBlank() }
                    ?: continue

                    val language = caption.optString("language")
                    .ifBlank { caption.optString("lang") }
                    .ifBlank { "Unknown" }

                    subtitleCallback(
                        SubtitleFile(
                            language,
                            url
                        )
                    )
                }
            }

            // ------------------------------------------------------------
            // 4. DASH playlist
            // ------------------------------------------------------------
            val playlist = stream.optString("playlist")
            .takeIf { it.isNotBlank() }

            val requiresProxy = stream.optBoolean("requiresProxy", false)

            if (playlist != null && stream.optString("deliveryType") == "dash") {
                try {
                    val originalUri = java.net.URI(playlist)

                    val path = originalUri.rawPath ?: return false
                    val origin = "${originalUri.scheme}://${originalUri.host}"

                    val playlistHeaders =
                    stream.optJSONObject("playlistHeaders")

                    val cookie = playlistHeaders
                    ?.optString("Cookie")
                    ?.takeIf { it.isNotBlank() }

                    if (cookie != null && requiresProxy) {
                        val sc = android.util.Base64.encodeToString(
                            cookie.toByteArray(Charsets.UTF_8),
                                                                    android.util.Base64.URL_SAFE or
                                                                    android.util.Base64.NO_WRAP or
                                                                    android.util.Base64.NO_PADDING
                        )

                        val proxyUrl =
                        "https://noon.mooncase.online/sacdn$path" +
                        "?host=${java.net.URLEncoder.encode(origin, "UTF-8")}" +
                        "&sc=$sc"

                        Log.d("WOOFLIX_TEST", "DASH proxy=$proxyUrl")

                        callback(
                            newExtractorLink(
                                source = "VidLink",
                                name = "VidLink DASH HEVC 1080p",
                                url = proxyUrl,
                                type = ExtractorLinkType.DASH
                            ) {
                                referer = "https://vidlink.pro/"
                                headers = mapOf(
                                    "Origin" to "https://vidlink.pro",
                                    "Referer" to "https://vidlink.pro/",
                                    "User-Agent" to "Mozilla/5.0"
                                )
                                quality = Qualities.P1080.value
                            }
                        )

                        Log.d("WOOFLIX_TEST", "DASH link added")
                        return true
                    }
                } catch (e: Exception) {
                    Log.e("WOOFLIX_TEST", "DASH proxy error", e)
                }
            }

            // ------------------------------------------------------------
            // 5. Fallback: MP4 qualities
            // ------------------------------------------------------------
            val qualities = stream.optJSONObject("qualities")

            if (qualities != null) {
                val keys = qualities.keys()

                while (keys.hasNext()) {
                    val qualityKey = keys.next()
                    val qualityObj = qualities.optJSONObject(qualityKey) ?: continue

                    val url = qualityObj.optString("url")
                    .takeIf { it.isNotBlank() }
                    ?: continue

                    val quality = qualityKey.toIntOrNull() ?: 0

                    callback(
                        newExtractorLink(
                            source = "VidLink",
                            name = "VidLink ${qualityKey}p",
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = "https://vidlink.pro/"
                            headers = mapOf(
                                "Origin" to "https://vidlink.pro",
                                "Referer" to "https://vidlink.pro/",
                                "User-Agent" to "Mozilla/5.0"
                            )
                            this.quality = quality
                        }
                    )
                }
            }

            return true

        } catch (e: Exception) {
            Log.e("WOOFLIX_TEST", "VidLink resolver failed", e)
            return false
        }
    }

}
