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
                "$tmdbId|movie"
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
                                        "$tmdbId|tv|$seasonNumber|$episodeNumber"
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

        val rawTmdbId = parts.getOrNull(0) ?: return false
        val tmdbId = rawTmdbId.substringAfterLast("/").toIntOrNull()?.toString()
            ?: return false

        val kind = parts.getOrNull(1) ?: return false
        val season = if (kind == "tv") parts.getOrNull(2)?.toIntOrNull() else null
        val episode = if (kind == "tv") parts.getOrNull(3)?.toIntOrNull() else null

        Log.d(
            "WOOFLIX_TEST",
            "loadLinks -> tmdb=$tmdbId kind=$kind season=$season episode=$episode"
        )

        fun base64Url(value: String): String {
            return android.util.Base64.encodeToString(
                value.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_WRAP or
                    android.util.Base64.NO_PADDING
            )
        }

        fun proxyMp4(
            originalUrl: String,
            headersObject: JSONObject?
        ): String? {
            return try {
                val uri = java.net.URI(originalUrl)
                val path = uri.rawPath ?: return null

                val query = uri.rawQuery ?: ""
                val allowed = mutableListOf<String>()

                if (query.isNotBlank()) {
                    query.split("&").forEach { item ->
                        val key = item.substringBefore("=", item)
                        if (
                            key == "auth" ||
                            key == "expires" ||
                            key == "hash" ||
                            key == "key" ||
                            key == "sign" ||
                            key == "t" ||
                            key == "token"
                        ) {
                            allowed.add(item)
                        }
                    }
                }

                val parts = mutableListOf<String>()

                if (allowed.isNotEmpty()) {
                    parts.addAll(allowed)
                }

                if (headersObject != null && headersObject.length() > 0) {
                    parts.add(
                        "headers=" + java.net.URLEncoder.encode(
                            headersObject.toString(),
                            "UTF-8"
                        )
                    )
                }

                parts.add(
                    "host=" + java.net.URLEncoder.encode(
                        "${uri.scheme}://${uri.host}",
                        "UTF-8"
                    )
                )

                "https://noon.mooncase.online/mp$path?${parts.joinToString("&")}"
            } catch (e: Exception) {
                Log.e("WOOFLIX_TEST", "MP4 proxy error", e)
                null
            }
        }

        fun proxyHls(
            playlist: String,
            headersObject: JSONObject?
        ): String? {
            return try {
                val uri = java.net.URI(playlist)
                val path = uri.rawPath ?: return null

                val query = uri.rawQuery ?: ""
                val params = mutableListOf<String>()

                if (query.isNotBlank()) {
                    query.split("&").forEach { item ->
                        val key = item.substringBefore("=", item)

                        // Mirrors VidLink's HLS proxy behavior:
                        // exclude headers/host from copied query params.
                        if (key != "headers" && key != "host") {
                            params.add(item)
                        }
                    }
                }

                if (headersObject != null && headersObject.length() > 0) {
                    params.add(
                        "headers=" + java.net.URLEncoder.encode(
                            headersObject.toString(),
                            "UTF-8"
                        )
                    )
                }

                params.add(
                    "host=" + java.net.URLEncoder.encode(
                        "${uri.scheme}://${uri.host}",
                        "UTF-8"
                    )
                )

                "https://noon.mooncase.online/proxy$path?${params.joinToString("&")}"
            } catch (e: Exception) {
                Log.e("WOOFLIX_TEST", "HLS proxy error", e)
                null
            }
        }

        fun proxyDash(
            playlist: String,
            cookie: String?
        ): String? {
            return try {
                if (cookie.isNullOrBlank()) return null

                val uri = java.net.URI(playlist)
                val path = uri.rawPath ?: return null
                val host = "${uri.scheme}://${uri.host}"

                val sc = base64Url(cookie)

                "https://noon.mooncase.online/sacdn$path" +
                    "?host=${java.net.URLEncoder.encode(host, "UTF-8")}" +
                    "&sc=$sc"
            } catch (e: Exception) {
                Log.e("WOOFLIX_TEST", "DASH proxy error", e)
                null
            }
        }

        try {
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

            val streamUrl = if (kind == "tv") {
                "https://vidlink.pro/api/b/tv/$token/$season/$episode" +
                    "?multiLang=0&_=${System.nanoTime()}"
            } else {
                "https://vidlink.pro/api/b/movie/$token" +
                    "?multiLang=0&_=${System.nanoTime()}"
            }

            // First request asks VidLink for its richest playback format.
            // If it comes back as file/mp4 we still handle that below.
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

            val deliveryType = stream.optString("deliveryType")
                .lowercase()

            val streamType = stream.optString("type")
                .lowercase()

            Log.d(
                "WOOFLIX_TEST",
                "VidLink stream type=$streamType delivery=$deliveryType"
            )

            // ------------------------------------------------------------
            // Captions
            // ------------------------------------------------------------
            val captions = stream.optJSONArray("captions")

            if (captions != null) {
                for (i in 0 until captions.length()) {
                    val caption = captions.optJSONObject(i) ?: continue

                    val captionUrl = caption.optString("url")
                        .takeIf { it.isNotBlank() }
                        ?: continue

                    val language =
                        caption.optString("language")
                            .ifBlank { caption.optString("lang") }
                            .ifBlank { "Unknown" }

                    subtitleCallback(
                        SubtitleFile(
                            language,
                            captionUrl
                        )
                    )
                }
            }

            // ------------------------------------------------------------
            // DASH
            // ------------------------------------------------------------
            val playlist = stream.optString("playlist")
                .takeIf { it.isNotBlank() }

            if (
                playlist != null &&
                (deliveryType == "dash" || streamType == "dash")
            ) {
                val playlistHeaders =
                    stream.optJSONObject("playlistHeaders")

                val cookie = playlistHeaders
                    ?.optString("Cookie")
                    ?.takeIf { it.isNotBlank() }

                val dashUrl = if (stream.optBoolean("requiresProxy", false)) {
                    proxyDash(playlist, cookie)
                } else {
                    playlist
                }

                if (dashUrl != null) {
                    val quality = stream.optInt("height", 0).let {
                        if (it > 0) it else Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            source = "VidLink",
                            name = "VidLink DASH HEVC",
                            url = dashUrl,
                            type = ExtractorLinkType.DASH
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

                    Log.d("WOOFLIX_TEST", "DASH link added")
                }
            }

            // ------------------------------------------------------------
            // HLS
            // ------------------------------------------------------------
            if (
                playlist != null &&
                (deliveryType == "hls" || streamType == "hls")
            ) {
                val playlistHeaders =
                    stream.optJSONObject("playlistHeaders")

                val hlsUrl = if (stream.optBoolean("requiresProxy", false)) {
                    proxyHls(playlist, playlistHeaders)
                } else {
                    playlist
                }

                if (hlsUrl != null) {
                    callback(
                        newExtractorLink(
                            source = "VidLink",
                            name = "VidLink HLS",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = "https://vidlink.pro/"
                            headers = mapOf(
                                "Origin" to "https://vidlink.pro",
                                "Referer" to "https://vidlink.pro/",
                                "User-Agent" to "Mozilla/5.0"
                            )
                        }
                    )

                    Log.d("WOOFLIX_TEST", "HLS link added")
                }
            }

            // ------------------------------------------------------------
            // FILE / MP4 qualities
            // ------------------------------------------------------------
            val qualities = stream.optJSONObject("qualities")
            var fileLinks = 0

            if (qualities != null) {
                val keys = qualities.keys()

                while (keys.hasNext()) {
                    val qualityKey = keys.next()
                    val qualityObj = qualities.optJSONObject(qualityKey)
                        ?: continue

                    val originalUrl = qualityObj.optString("url")
                        .takeIf { it.isNotBlank() }
                        ?: continue

                    val quality = qualityKey.toIntOrNull() ?: 0

                    val qualityHeaders =
                        qualityObj.optJSONObject("headers")

                    val requiresProxy =
                        qualityObj.optBoolean("requiresProxy", false)

                    val finalUrl = if (requiresProxy) {
                        proxyMp4(originalUrl, qualityHeaders)
                    } else {
                        originalUrl
                    } ?: continue

                    callback(
                        newExtractorLink(
                            source = "VidLink",
                            name = "VidLink ${qualityKey}p",
                            url = finalUrl,
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

                    fileLinks++
                }
            }

            Log.d(
                "WOOFLIX_TEST",
                "VidLink done: delivery=$deliveryType fileLinks=$fileLinks"
            )

            // ------------------------------------------------------------
            // Additional providers
            // ------------------------------------------------------------
            val externalSources = if (kind == "tv") {
                listOf(
                    Triple(
                        "VidZee",
                        "https://player.vidzee.wtf/embed/tv/$tmdbId/$season/$episode",
                        "https://player.vidzee.wtf/"
                    ),
                    Triple(
                        "VidSrc",
                        "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode",
                        "https://vidsrc.to/"
                    ),
                    Triple(
                        "Mapple",
                        "https://mapple.uk/watch/tv/$tmdbId-$season-$episode",
                        "https://mapple.uk/"
                    ),
                    Triple(
                        "VidEasy",
                        "https://player.videasy.net/tv/$tmdbId/$season/$episode",
                        "https://player.videasy.net/"
                    ),
                    Triple(
                        "MoviesAPI",
                        "https://moviesapi.to/tv/$tmdbId-$season-$episode",
                        "https://moviesapi.to/"
                    )
                )
            } else {
                listOf(
                    Triple(
                        "VidZee",
                        "https://player.vidzee.wtf/embed/movie/$tmdbId",
                        "https://player.vidzee.wtf/"
                    ),
                    Triple(
                        "VidSrc",
                        "https://vidsrc.to/embed/movie/$tmdbId",
                        "https://vidsrc.to/"
                    ),
                    Triple(
                        "Mapple",
                        "https://mapple.uk/watch/movie/$tmdbId",
                        "https://mapple.uk/"
                    ),
                    Triple(
                        "VidEasy",
                        "https://player.videasy.net/movie/$tmdbId",
                        "https://player.videasy.net/"
                    ),
                    Triple(
                        "MoviesAPI",
                        "https://moviesapi.to/movie/$tmdbId",
                        "https://moviesapi.to/"
                    )
                )
            }

            for ((sourceName, sourceUrl, sourceReferer) in externalSources) {
                try {
                    val matched = loadExtractor(
                        sourceUrl,
                        sourceReferer,
                        subtitleCallback,
                        callback
                    )

                    Log.d(
                        "WOOFLIX_TEST",
                        "$sourceName extractor matched=$matched"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "WOOFLIX_TEST",
                        "$sourceName failed",
                        e
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
