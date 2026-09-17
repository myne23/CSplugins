package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import org.json.JSONObject

class ExampleProvider(
    private val plugin: ExamplePlugin
) : MainAPI() {

    override var mainUrl = "https://wooflix.media/"
    override var name = "W0Fl1X"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )
    override var lang = "es"
    override val hasMainPage = true


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        suspend fun tmdbList(
            path: String,
            forcedType: String? = null
        ): List<SearchResponse> {

            val url = "https://api.themoviedb.org/3/$path" +
                    "?api_key=e1a8efff4415028c5c266b3fcd50db6e" +
                    "&language=en-US&page=$page"

            val response = try {
                app.get(url)
            } catch (e: Exception) {
                return emptyList()
            }

            if (!response.isSuccessful) return emptyList()

            val results = try {
                JSONObject(response.text).optJSONArray("results")
            } catch (e: Exception) {
                return emptyList()
            } ?: return emptyList()

            return (0 until results.length()).mapNotNull { i ->
                val item = results.optJSONObject(i)
                    ?: return@mapNotNull null

                val type = forcedType ?: item.optString("media_type")

                if (type != "movie" && type != "tv") {
                    return@mapNotNull null
                }

                val id = item.optInt("id", 0)
                if (id == 0) return@mapNotNull null

                val title = if (type == "tv") {
                    item.optString("name")
                } else {
                    item.optString("title")
                }

                if (title.isBlank()) return@mapNotNull null

                val posterPath = item.optString("poster_path")

                val poster = if (posterPath.isNotBlank()) {
                    "https://image.tmdb.org/t/p/w500$posterPath"
                } else {
                    null
                }

                val year = if (type == "tv") {
                    item.optString("first_air_date")
                        .take(4)
                        .toIntOrNull()
                } else {
                    item.optString("release_date")
                        .take(4)
                        .toIntOrNull()
                }

                val resultUrl = if (type == "tv") {
                    "https://wooflix.media/play/tv/$id"
                } else {
                    "https://wooflix.media/play/movie/$id"
                }

                if (type == "tv") {
                    newTvSeriesSearchResponse(
                        title,
                        resultUrl,
                        TvType.TvSeries,
                        false
                    ) {
                        posterUrl = poster
                        this.year = year
                    }
                } else {
                    newMovieSearchResponse(
                        title,
                        resultUrl,
                        TvType.Movie,
                        false
                    ) {
                        posterUrl = poster
                        this.year = year
                    }
                }
            }
        }

        val home = tmdbList("trending/all/week")
        val movies = tmdbList("movie/popular", "movie")
        val tv = tmdbList("tv/popular", "tv")

        return newHomePageResponse(
            listOf(
                HomePageList("Home", home),
                HomePageList("Movies", movies),
                HomePageList("TV Shows", tv)
            ),
            hasNext = page < 5
        )
    }

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

        // === LATANIME TEST ===
        try {
            val latanimeTest = app.get("https://latanime.org/buscar?q=RWBY")

            Log.d("LATANIME_TEST", "SUCCESS=${latanimeTest.isSuccessful}")
            Log.d("LATANIME_TEST", "CODE=${latanimeTest.code}")
            Log.d("LATANIME_TEST", "SIZE=${latanimeTest.text.length}")
            Log.d("LATANIME_TEST", latanimeTest.text.take(500))
        } catch (e: Exception) {
            Log.e("LATANIME_TEST", "ERROR: ${e.message}", e)
        }
        // === END LATANIME TEST ===

        Log.d(
            "WOOFLIX_TEST",
            "loadLinks -> tmdb=$tmdbId kind=$kind season=$season episode=$episode"
        )
        Log.d("WOOFLIX_TEST", "=== ARRANCANDO VidLink ===")

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
            kotlinx.coroutines.withTimeout(8000L) {
            run {
                Log.d("WOOFLIX_TEST", "=== ENTRANDO al run de VidLink ===")
                Log.d("WOOFLIX_TEST", "=== A: antes del app.get de enc-dec ===")
                val tokenResponse = app.get(
                "https://enc-dec.app/api/enc-vidlink?text=$tmdbId",
                timeout = 8000
            ).text
                Log.d("WOOFLIX_TEST", "=== B: despues del app.get de enc-dec ===")

            val token = JSONObject(tokenResponse)
                .optString("result")
                .takeIf { it.isNotBlank() }

            if (token == null) {
                Log.d("WOOFLIX_TEST", "No VidLink token")
                return@run
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
            , timeout = 8000
            )

            if (!response.isSuccessful) {
                Log.d("WOOFLIX_TEST", "VidLink HTTP ${response.code}")
                return@run
            }

            val json = JSONObject(response.text)
            val stream = json.optJSONObject("stream")

            if (stream == null) {
                Log.d("WOOFLIX_TEST", "VidLink stream=null")
                return@run
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

            } // fin run VidLink
            }
            } catch (e: Exception) {
                Log.e("WOOFLIX_TEST", "VidLink timeout/error: ${e.message}")
            }

            // ------------------------------------------------------------
            // VidSrc / data.vidsrcme.ru (bloque independiente)
            // ------------------------------------------------------------
            Log.d("WOOFLIX_TEST", "=== ARRANCANDO VidSrc ===")
            try {
                val vidsrcApiUrl = if (kind == "tv") {
                    "https://data.vidsrcme.ru/api.php" +
                        "?type=tv&tmdb=$tmdbId" +
                        "&season=$season&episode=$episode&stream_urls"
                } else {
                    "https://data.vidsrcme.ru/api.php" +
                        "?type=movie&tmdb=$tmdbId&stream_urls"
                }

                val vidsrcHeaders = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "Origin" to "https://cloudorchestranova.com",
                    "Referer" to "https://cloudorchestranova.com/",
                    "User-Agent" to "Mozilla/5.0"
                )

                val vidsrcResponse = app.get(
                    vidsrcApiUrl,
                    headers = vidsrcHeaders,
                    timeout = 15000
                )

                if (!vidsrcResponse.isSuccessful) {
                    Log.d(
                        "WOOFLIX_TEST",
                        "VidSrc API HTTP ${vidsrcResponse.code}"
                    )
                } else {
                    val vidsrcJson = JSONObject(vidsrcResponse.text)
                    val encrypted = vidsrcJson
                        .optJSONObject("data")
                        ?.optString("stream_urls")
                        ?.takeIf { it.isNotBlank() }

                    val wasmUrl = vidsrcJson
                        .optJSONObject("vs")
                        ?.optString("wasm_url")
                        ?.takeIf { it.isNotBlank() }

                    if (encrypted == null || wasmUrl == null) {
                        val dataObject = vidsrcJson.optJSONObject("data")
                        val vsObject = vidsrcJson.optJSONObject("vs")

                        val encryptedType =
                            dataObject?.opt("stream_urls")?.javaClass?.name ?: "null"

                        Log.d(
                            "WOOFLIX_TEST",
                            "VidSrc DEBUG topKeys=${vidsrcJson.keys().asSequence().toList()} " +
                                "dataKeys=${dataObject?.keys()?.asSequence()?.toList()} " +
                                "vsKeys=${vsObject?.keys()?.asSequence()?.toList()} " +
                                "streamType=$encryptedType " +
                                "streamValue=${dataObject?.opt("stream_urls")} " +
                                "wasmUrl=$wasmUrl"
                        )

                        Log.d(
                            "WOOFLIX_TEST",
                            "VidSrc missing stream_urls or wasm_url"
                        )
                    } else {
                        fun readLeb(
                            bytes: ByteArray,
                            start: Int
                        ): Pair<Long, Int> {
                            var pos = start
                            var value = 0L
                            var shift = 0

                            while (pos < bytes.size) {
                                val b = bytes[pos].toInt() and 0xff
                                pos++

                                value = value or
                                    ((b and 0x7f).toLong() shl shift)

                                if ((b and 0x80) == 0) {
                                    return value to pos
                                }

                                shift += 7
                                if (shift > 63) break
                            }

                            return 0L to pos
                        }

                        data class WasmDataSegment(
                            val offset: Int,
                            val data: ByteArray
                        )

                        fun parseWasmDataSegments(
                            bytes: ByteArray
                        ): List<WasmDataSegment> {
                            val segments = mutableListOf<WasmDataSegment>()

                            if (
                                bytes.size < 8 ||
                                bytes[0] != 0x00.toByte() ||
                                bytes[1] != 0x61.toByte() ||
                                bytes[2] != 0x73.toByte() ||
                                bytes[3] != 0x6d.toByte()
                            ) {
                                return emptyList()
                            }

                            var pos = 8

                            while (pos < bytes.size) {
                                val sectionId = bytes[pos].toInt() and 0xff
                                pos++

                                val sectionLenResult = readLeb(bytes, pos)
                                val sectionLen = sectionLenResult.first.toInt()
                                pos = sectionLenResult.second

                                if (sectionLen < 0 || pos + sectionLen > bytes.size) {
                                    break
                                }

                                val sectionEnd = pos + sectionLen

                                if (sectionId == 11) {
                                    val countResult = readLeb(bytes, pos)
                                    val count = countResult.first.toInt()
                                    pos = countResult.second

                                    repeat(count) {
                                        if (pos >= sectionEnd) return@repeat

                                        val flagResult = readLeb(bytes, pos)
                                        val flag = flagResult.first.toInt()
                                        pos = flagResult.second

                                        // Active data segment, memory 0.
                                        // Flag 0: offset expression follows.
                                        // Flag 2: memory index follows, then offset expression.
                                        if (flag == 2) {
                                            val memResult = readLeb(bytes, pos)
                                            pos = memResult.second
                                        }

                                        if (flag == 0 || flag == 2) {
                                            if (pos >= sectionEnd) return@repeat

                                            val opcode = bytes[pos].toInt() and 0xff
                                            pos++

                                            if (opcode != 0x41) return@repeat

                                            val offsetResult = readLeb(bytes, pos)
                                            val offset = offsetResult.first.toInt()
                                            pos = offsetResult.second

                                            if (pos >= sectionEnd) return@repeat

                                            val endOpcode =
                                                bytes[pos].toInt() and 0xff
                                            pos++

                                            if (endOpcode != 0x0b) return@repeat

                                            val sizeResult = readLeb(bytes, pos)
                                            val size = sizeResult.first.toInt()
                                            pos = sizeResult.second

                                            if (
                                                size < 0 ||
                                                pos + size > sectionEnd
                                            ) {
                                                return@repeat
                                            }

                                            val data = bytes.copyOfRange(
                                                pos,
                                                pos + size
                                            )

                                            segments.add(
                                                WasmDataSegment(
                                                    offset,
                                                    data
                                                )
                                            )

                                            pos += size
                                        } else {
                                            // Passive / unsupported segment.
                                            val sizeResult = readLeb(bytes, pos)
                                            pos = sizeResult.second

                                            val size = sizeResult.first.toInt()

                                            if (
                                                size < 0 ||
                                                pos + size > sectionEnd
                                            ) {
                                                return@repeat
                                            }

                                            pos += size
                                        }
                                    }
                                }

                                pos = sectionEnd
                            }

                            return segments
                        }

                        fun rotl32(
                            value: Int,
                            shift: Int
                        ): Int {
                            return Integer.rotateLeft(value, shift)
                        }

                        fun chacha20Block(
                            key: ByteArray,
                            counter: Int,
                            nonce: ByteArray
                        ): ByteArray {
                            fun leWord(
                                bytes: ByteArray,
                                offset: Int
                            ): Int {
                                return (bytes[offset].toInt() and 0xff) or
                                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                                    ((bytes[offset + 3].toInt() and 0xff) shl 24)
                            }

                            fun quarterRound(
                                x: IntArray,
                                a: Int,
                                b: Int,
                                c: Int,
                                d: Int
                            ) {
                                x[a] = x[a] + x[b]
                                x[d] = rotl32(x[d] xor x[a], 16)

                                x[c] = x[c] + x[d]
                                x[b] = rotl32(x[b] xor x[c], 12)

                                x[a] = x[a] + x[b]
                                x[d] = rotl32(x[d] xor x[a], 8)

                                x[c] = x[c] + x[d]
                                x[b] = rotl32(x[b] xor x[c], 7)
                            }

                            val constants = byteArrayOf(
                                0x65, 0x78, 0x70, 0x61,
                                0x6e, 0x64, 0x20, 0x33,
                                0x32, 0x2d, 0x62, 0x79,
                                0x74, 0x65, 0x20, 0x6b
                            )

                            val state = IntArray(16)

                            for (i in 0 until 4) {
                                state[i] = leWord(constants, i * 4)
                            }

                            for (i in 0 until 8) {
                                state[4 + i] =
                                    leWord(key, i * 4)
                            }

                            state[12] = counter
                            state[13] = leWord(nonce, 0)
                            state[14] = leWord(nonce, 4)
                            state[15] = leWord(nonce, 8)

                            val working = state.copyOf()

                            repeat(10) {
                                quarterRound(
                                    working, 0, 4, 8, 12
                                )
                                quarterRound(
                                    working, 1, 5, 9, 13
                                )
                                quarterRound(
                                    working, 2, 6, 10, 14
                                )
                                quarterRound(
                                    working, 3, 7, 11, 15
                                )

                                quarterRound(
                                    working, 0, 5, 10, 15
                                )
                                quarterRound(
                                    working, 1, 6, 11, 12
                                )
                                quarterRound(
                                    working, 2, 7, 8, 13
                                )
                                quarterRound(
                                    working, 3, 4, 9, 14
                                )
                            }

                            val output = ByteArray(64)

                            for (i in 0 until 16) {
                                val v = working[i] + state[i]
                                output[i * 4] =
                                    (v and 0xff).toByte()
                                output[i * 4 + 1] =
                                    ((v ushr 8) and 0xff).toByte()
                                output[i * 4 + 2] =
                                    ((v ushr 16) and 0xff).toByte()
                                output[i * 4 + 3] =
                                    ((v ushr 24) and 0xff).toByte()
                            }

                            return output
                        }

                        fun decryptVidSrc(
                            encoded: String,
                            wasm: ByteArray
                        ): String {
                            val raw = android.util.Base64.decode(
                                encoded,
                                android.util.Base64.DEFAULT
                            )

                            if (raw.size < 13) {
                                throw IllegalArgumentException(
                                    "VidSrc ciphertext too short"
                                )
                            }

                            val segments = parseWasmDataSegments(wasm)

                            val segmentZero =
                                segments.firstOrNull {
                                    it.offset == 0 &&
                                        it.data.size >= 32
                                }?.data?.copyOfRange(0, 32)
                                    ?: throw IllegalStateException(
                                        "VidSrc key segment not found"
                                    )

                            val candidates = segments.filter {
                                it.offset != 0 &&
                                    it.data.size == 32
                            }

                            val nonce = raw.copyOfRange(0, 12)
                            val ciphertext =
                                raw.copyOfRange(12, raw.size)

                            val expectedPrefix =
                                "https://".toByteArray(Charsets.UTF_8)

                            var key: ByteArray? = null

                            for (candidate in candidates) {
                                val candidateKey =
                                    ByteArray(32)

                                for (i in 0 until 32) {
                                    candidateKey[i] =
                                        (
                                            segmentZero[i].toInt() xor
                                                candidate.data[i].toInt()
                                            ).toByte()
                                }

                                val block =
                                    chacha20Block(
                                        candidateKey,
                                        0,
                                        nonce
                                    )

                                var matches = true

                                for (i in expectedPrefix.indices) {
                                    val plain =
                                        (
                                            ciphertext[i].toInt() xor
                                                block[i].toInt()
                                            ).toByte()

                                    if (
                                        plain !=
                                        expectedPrefix[i]
                                    ) {
                                        matches = false
                                        break
                                    }
                                }

                                if (matches) {
                                    key = candidateKey
                                    break
                                }
                            }

                            val realKey = key
                                ?: throw IllegalStateException(
                                    "VidSrc dynamic key not found"
                                )

                            val output =
                                ByteArray(ciphertext.size)

                            var counter = 0

                            for (
                                pos in ciphertext.indices step 64
                            ) {
                                val block =
                                    chacha20Block(
                                        realKey,
                                        counter,
                                        nonce
                                    )

                                val end = minOf(
                                    pos + 64,
                                    ciphertext.size
                                )

                                for (i in pos until end) {
                                    output[i] =
                                        (
                                            ciphertext[i].toInt() xor
                                                block[i - pos].toInt()
                                            ).toByte()
                                }

                                counter++
                            }

                            return output.toString(
                                Charsets.UTF_8
                            )
                        }

                        val wasmResponse = app.get(
                            wasmUrl,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0",
                                "Referer" to "https://cloudorchestranova.com/"
                            ),
                            timeout = 15000
                        )

                        if (!wasmResponse.isSuccessful) {
                            Log.d(
                                "WOOFLIX_TEST",
                                "VidSrc WASM HTTP ${wasmResponse.code}"
                            )
                        } else {
                            val wasmBytes = wasmResponse.body.bytes()

                            val decodedStreams =
                                decryptVidSrc(
                                    encrypted,
                                    wasmBytes
                                )

                            val streamUrls =
                                decodedStreams
                                    .lines()
                                    .map { it.trim() }
                                    .filter {
                                        it.startsWith(
                                            "http://"
                                        ) ||
                                        it.startsWith(
                                            "https://"
                                        )
                                    }

                            Log.d(
                                "WOOFLIX_TEST",
                                "VidSrc decrypted streams=${streamUrls.size}"
                            )

                            val tokenCache =
                                mutableMapOf<String, String>()

                            suspend fun addToken(
                                originalUrl: String
                            ): String? {
                                return try {
                                    val uri =
                                        java.net.URI(
                                            originalUrl
                                        )

                                    val origin =
                                        "${uri.scheme}://${uri.host}"

                                    val cached =
                                        tokenCache[origin]

                                    val token =
                                        cached
                                            ?: run {
                                                val tokenResponse = app.get(
                                                    "$origin/generate.php",
                                                    headers = mapOf(
                                                        "Referer" to
                                                            "https://cloudorchestranova.com/",
                                                        "Origin" to
                                                            "https://cloudorchestranova.com",
                                                        "User-Agent" to
                                                            "Mozilla/5.0"
                                                    ),
                                                    timeout = 10000
                                                )

                                                Log.d(
                                                    "WOOFLIX_TEST",
                                                    "VidSrc token HTTP ${tokenResponse.code} host=$origin"
                                                )

                                                if (!tokenResponse.isSuccessful) {
                                                    return@run null
                                                }

                                                tokenResponse.text
                                                    .trim()
                                                    .also {
                                                        tokenCache[origin] = it
                                                    }
                                            }

                                    if (token.isNullOrBlank()) {
                                        null
                                    } else {
                                        val separator =
                                            if (
                                                uri.rawQuery
                                                    .isNullOrBlank()
                                            ) {
                                                "?"
                                            } else {
                                                "&"
                                            }

                                        "$originalUrl${separator}token=${
                                            java.net.URLEncoder.encode(
                                                token,
                                                "UTF-8"
                                            )
                                        }"
                                    }
                                } catch (e: Exception) {
                                    Log.d(
                                        "WOOFLIX_TEST",
                                        "VidSrc token error: ${e.message}"
                                    )
                                    null
                                }
                            }

                            for ((index, streamUrl) in
                                streamUrls.withIndex()
                            ) {
                                val finalUrl =
                                    addToken(streamUrl)

                                if (finalUrl == null) continue

                                callback(
                                    newExtractorLink(
                                        source = "VidSrc",
                                        name = "VidSrc ${index + 1}",
                                        url = finalUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        referer =
                                            "https://cloudorchestranova.com/"

                                        headers = mapOf(
                                            "Origin" to
                                                "https://cloudorchestranova.com",
                                            "Referer" to
                                                "https://cloudorchestranova.com/",
                                            "User-Agent" to
                                                "Mozilla/5.0"
                                        )

                                        quality =
                                            Qualities.Unknown.value
                                    }
                                )
                            }

                            Log.d(
                                "WOOFLIX_TEST",
                                "VidSrc done: links=${streamUrls.size}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "WOOFLIX_TEST",
                    "VidSrc resolver failed",
                    e
                )
            }

            // ------------------------------------------------------------
            // Cinezo / Flikhub
            // ------------------------------------------------------------
            if (kind == "tv") {
                try {
                    val cinezoUrl =
                        "https://proxy1.flikhub.net/tv" +
                            "?id=$tmdbId&season=$season&episode=$episode"

                    val cinezoResponse = app.get(
                        cinezoUrl,
                        headers = mapOf(
                            "Accept" to "text/event-stream",
                            "Origin" to "https://player.cinezo.live",
                            "Referer" to "https://player.cinezo.live/",
                            "User-Agent" to "Mozilla/5.0"
                        ),
                        timeout = 15000
                    )

                    var cinezoSources = 0
                    var cinezoSubtitles = 0
                    val cinezoSubtitleLanguages = mutableSetOf<String>()
                    val cinezoSubtitleCandidates = mutableListOf<Pair<String, String>>()

                    for (line in cinezoResponse.text.lines()) {
                        if (!line.startsWith("data:")) continue

                        val payload = line
                            .removePrefix("data:")
                            .trim()

                        if (payload.isBlank()) continue

                        try {
                            val event = JSONObject(payload)
                            val eventType = event.optString("type")

                            when (eventType) {
                                "meta" -> {
                                  val subtitles = event.optJSONArray("subtitles")

                                  if (subtitles != null) {
                                      for (i in 0 until subtitles.length()) {
                                          val subtitle = subtitles.optJSONObject(i) ?: continue

                                          val label = subtitle
                                              .optString("label")
                                              .ifBlank { "Unknown" }

                                          val file = subtitle
                                              .optString("file")
                                              .takeIf { it.isNotBlank() }
                                              ?: continue

                                          val normalizedLabel = label
                                              .trim()
                                              .replace(Regex("""\s*\d+$"""), "")
                                              .replace(Regex("""\s+hi$""", RegexOption.IGNORE_CASE), "")
                                              .trim()
                                              .lowercase()

                                          if (!cinezoSubtitleLanguages.add(normalizedLabel)) {
                                              continue
                                          }

                                          cinezoSubtitleCandidates.add(label to file)
                                      }
                                  }

                                  val selectedCinezoSubtitles = mutableListOf<Pair<String, String>>()

                                  // Spanish primero
                                  cinezoSubtitleCandidates
                                      .firstOrNull {
                                          it.first.trim()
                                              .replace(Regex("""\s*\d+$"""), "")
                                              .replace(Regex("""\s+hi$""", RegexOption.IGNORE_CASE), "")
                                              .trim()
                                              .equals("Spanish", ignoreCase = true)
                                      }
                                      ?.let { selectedCinezoSubtitles.add(it) }

                                  // English segundo
                                  cinezoSubtitleCandidates
                                      .firstOrNull {
                                          it.first.trim()
                                              .replace(Regex("""\s*\d+$"""), "")
                                              .replace(Regex("""\s+hi$""", RegexOption.IGNORE_CASE), "")
                                              .trim()
                                              .equals("English", ignoreCase = true)
                                      }
                                      ?.let {
                                          if (selectedCinezoSubtitles.none {
                                                  it.first.trim()
                                                      .replace(Regex("""\s*\d+$"""), "")
                                                      .replace(Regex("""\s+hi$""", RegexOption.IGNORE_CASE), "")
                                                      .trim()
                                                      .equals("English", ignoreCase = true)
                                              }) {
                                              selectedCinezoSubtitles.add(it)
                                          }
                                      }

                                  // Hasta 3 idiomas adicionales, máximo 5 en total
                                  for (candidate in cinezoSubtitleCandidates) {
                                      if (selectedCinezoSubtitles.size >= 5) break

                                      val language = candidate.first.trim()
                                          .replace(Regex("""\s*\d+$"""), "")
                                          .replace(Regex("""\s+hi$""", RegexOption.IGNORE_CASE), "")
                                          .trim()

                                      if (selectedCinezoSubtitles.none {
                                              it.first.trim()
                                                  .replace(Regex("""\s*\d+$"""), "")
                                                  .replace(Regex("""\s+hi$""", RegexOption.IGNORE_CASE), "")
                                                  .trim()
                                                  .equals(language, ignoreCase = true)
                                          }) {
                                          selectedCinezoSubtitles.add(candidate)
                                      }
                                  }

                                  for ((label, file) in selectedCinezoSubtitles) {
                                      subtitleCallback(
                                          SubtitleFile(
                                              "Cinezo - $label",
                                              file
                                          )
                                      )
                                      cinezoSubtitles++
                                  }

                                  Log.d(
                                      "WOOFLIX_TEST",
                                      "Cinezo subtitles seleccionados: $cinezoSubtitles/${cinezoSubtitleCandidates.size}"
                                  )
                              }
                              "source" -> {
                                    val source =
                                        event.optJSONObject("source")
                                            ?: continue

                                    val label = source
                                        .optString("label")
                                        .ifBlank {
                                            source.optString(
                                                "source",
                                                "Cinezo"
                                            )
                                        }

                                    val sourceUrl = source
                                        .optString("url")
                                        .takeIf { it.isNotBlank() }
                                        ?: continue

                                    val sourceId = source
                                        .optString("source")
                                        .lowercase()
                                    val sourceLabel = source
                                        .optString("label")
                                        .lowercase()

                                    if (
                                        sourceId == "berlin" ||
                                        sourceId == "cinefreak" ||
                                        sourceLabel == "berlin" ||
                                        sourceLabel == "cinefreak" ||
                                        sourceLabel.contains("berlin") ||
                                        sourceLabel.contains("cinefreak")
                                    ) {
                                        continue
                                    }

                                    // Cinezo sometimes reports "mp4" for
                                    // an HLS URL. Detect the real container
                                    // from the URL as well.
                                    val urlLower = sourceUrl
                                        .lowercase()

                                    val sourceType = source
                                        .optString("type")
                                        .lowercase()

                                    val linkType =
                                        when {
                                            sourceType == "dash" ||
                                                urlLower.contains(".mpd") ->
                                                ExtractorLinkType.DASH

                                            sourceType == "hls" ||
                                                sourceType == "m3u8" ||
                                                urlLower.contains(".m3u8") ->
                                                ExtractorLinkType.M3U8

                                            else ->
                                                ExtractorLinkType.VIDEO
                                        }

                                    // Cinezo embeds the real request headers
                                    // inside the source URL.
                                    val parsedHeaders =
                                        try {
                                            val uri =
                                                java.net.URI(sourceUrl)

                                            val query =
                                                uri.rawQuery.orEmpty()

                                            val headerParam =
                                                query
                                                    .split("&")
                                                    .firstOrNull {
                                                        it.startsWith(
                                                            "headers="
                                                        )
                                                    }
                                                    ?: query
                                                        .split("&")
                                                        .firstOrNull {
                                                            it.startsWith(
                                                                "proxyHeaders="
                                                            )
                                                        }

                                            if (headerParam != null) {
                                                val encoded =
                                                    headerParam.substringAfter(
                                                        "="
                                                    )

                                                val decoded =
                                                    java.net.URLDecoder.decode(
                                                        encoded,
                                                        "UTF-8"
                                                    )

                                                val headerJson =
                                                    JSONObject(decoded)

                                                buildMap {
                                                    val keys =
                                                        headerJson.keys()

                                                    while (keys.hasNext()) {
                                                        val key = keys.next()
                                                        put(
                                                            key,
                                                            headerJson.optString(
                                                                key
                                                            )
                                                        )
                                                    }
                                                }
                                            } else {
                                                emptyMap()
                                            }
                                        } catch (e: Exception) {
                                            Log.d(
                                                "WOOFLIX_TEST",
                                                "Cinezo header parse error: ${e.message}"
                                            )
                                            emptyMap()
                                        }

                                    val finalHeaders =
                                        if (parsedHeaders.isNotEmpty()) {
                                            parsedHeaders
                                        } else {
                                            mapOf(
                                                "Origin" to
                                                    "https://player.cinezo.live",
                                                "Referer" to
                                                    "https://player.cinezo.live/",
                                                "User-Agent" to
                                                    "Mozilla/5.0"
                                            )
                                        }

                                    callback(
                                        newExtractorLink(
                                            source = "Cinezo",
                                            name = "Cinezo - $label",
                                            url = sourceUrl,
                                            type = linkType
                                        ) {
                                            referer =
                                                finalHeaders["Referer"]
                                                    ?: "https://player.cinezo.live/"

                                            headers = finalHeaders
                                        }
                                    )

                                    Log.d(
                                        "WOOFLIX_TEST",
                                        "Cinezo link added: $label type=$linkType headers=${finalHeaders.keys}"
                                    )

                                    cinezoSources++
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(
                                "WOOFLIX_TEST",
                                "Cinezo event parse error: ${e.message}"
                            )
                        }
                    }

                    Log.d(
                        "WOOFLIX_TEST",
                        "Cinezo done: sources=$cinezoSources subtitles=$cinezoSubtitles"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "WOOFLIX_TEST",
                        "Cinezo failed",
                        e
                    )
                }
            }

            // ------------------------------------------------------------
            // Vidzee / core.vidzee.wtf
            // ------------------------------------------------------------
            Log.d("WOOFLIX_TEST", "=== ARRANCANDO Vidzee ===")
            try {
                val vidzeeSources = listOf(
                    "v4:English",
                    "v4:Hindi",
                    "v4:Spanish"
                )

                val vidzeeBase = if (kind == "tv") {
                    "https://core.vidzee.wtf/streams/tv/$tmdbId/$season/$episode"
                } else {
                    "https://core.vidzee.wtf/streams/movie/$tmdbId"
                }

                var vidzeeCount = 0

                for (source in vidzeeSources) {
                    try {
                        val url = "$vidzeeBase?s=" +
                            java.net.URLEncoder.encode(source, "UTF-8")

                        val response = app.get(
                            url,
                            headers = mapOf(
                                "Accept" to "*/*",
                                "Accept-Language" to "es-419,es;q=0.9",
                                "Origin" to "https://player.vidzee.wtf",
                                "Referer" to "https://player.vidzee.wtf/",
                                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
                                "Sec-Fetch-Dest" to "empty",
                                "Sec-Fetch-Mode" to "cors",
                                "Sec-Fetch-Site" to "same-site"
                            ),
                            timeout = 10000
                        )

                        if (!response.isSuccessful) {
                            Log.d(
                                "WOOFLIX_TEST",
                                "Vidzee $source HTTP ${response.code}"
                            )
                            continue
                        }

                        val json = JSONObject(response.text)

                        val streamUrl = json
                            .optString("url")
                            .takeIf { it.isNotBlank() }
                            ?: continue

                        val language = json.optString(
                            "language",
                            source
                        )

                        val headersObj = json.optJSONObject("headers")
                        val streamReferer = headersObj
                            ?.optString("Referer")
                            ?.takeIf { it.isNotBlank() }
                            ?: "https://player.vidzee.wtf/"

                        callback(
                            newExtractorLink(
                                source = "Vidzee",
                                name = "Vidzee - $language",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = streamReferer
                                headers = mapOf(
                                    "Referer" to streamReferer,
                                    "Origin" to "https://player.vidzee.wtf",
                                    "User-Agent" to "Mozilla/5.0"
                                )
                                quality = Qualities.Unknown.value
                            }
                        )

                        Log.d(
                            "WOOFLIX_TEST",
                            "Vidzee link added: $language"
                        )
                        vidzeeCount++
                    } catch (e: Exception) {
                        Log.d(
                            "WOOFLIX_TEST",
                            "Vidzee $source error: ${e.message}"
                        )
                    }
                }

                Log.d(
                    "WOOFLIX_TEST",
                    "Vidzee done: links=$vidzeeCount"
                )
            } catch (e: Exception) {
                Log.e("WOOFLIX_TEST", "Vidzee failed", e)
            }

            // ------------------------------------------------------------
            // Vidlove / api.vidlove.cc
            // ------------------------------------------------------------
            Log.d("WOOFLIX_TEST", "=== ARRANCANDO Vidlove ===")
            try {
                val vidloveSources = listOf("vidapi", "tcloud", "moviebox2")
                var vidloveCount = 0

                for (source in vidloveSources) {
                    try {
                        val url = if (kind == "tv") {
                            "https://api.vidlove.cc/tv" +
                                "?id=$tmdbId&season=$season&episode=$episode" +
                                "&mode=json&sources=$source"
                        } else {
                            "https://api.vidlove.cc/movie" +
                                "?id=$tmdbId&mode=json&sources=$source"
                        }

                        val response = app.get(
                            url,
                            headers = mapOf(
                                "Accept" to "application/json",
                                "Origin" to "https://player.vidlove.cc",
                                "Referer" to "https://player.vidlove.cc/",
                                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            ),
                            timeout = 15000
                        )

                        if (!response.isSuccessful) {
                            Log.d("WOOFLIX_TEST", "Vidlove $source HTTP ${response.code} body=${response.text.take(300)}")
                            continue
                        }

                        val body = response.text

                        // CloudStream ya parsea el SSE: la respuesta es el
                        // JSON del primer evento. A veces vienen multiples
                        // eventos separados por saltos dobles: procesamos cada uno.
                        val events = body.split("\n\n")
                            .flatMap { it.lines() }
                            .map { it.trim() }
                            .filter { it.startsWith("{") && it.endsWith("}") }
                            .ifEmpty { listOf(body.trim()) }

                        for (raw in events) {
                            try {
                                val event = JSONObject(raw)

// Source: puede venir en el nivel raiz o anidado
                                val sourceObj =
                                    event.optJSONObject("source")
                                        ?: event.optJSONObject("meta")
                                            ?.optJSONObject("source")

                                if (sourceObj == null || sourceObj.length() == 0) continue

                                val streamUrl = sourceObj.optString("url")
                                    .takeIf { it.isNotBlank() }
                                    ?: continue

                                val label = sourceObj.optString("label", source)

                                callback(
                                    newExtractorLink(
                                        source = "Vidlove",
                                        name = "Vidlove - $label",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        referer = "https://player.vidlove.cc/"
                                        headers = mapOf(
                                            "Referer" to "https://player.vidlove.cc/",
                                            "Origin" to "https://player.vidlove.cc",
                                            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                        )
                                        quality = Qualities.Unknown.value
                                    }
                                )

                                Log.d("WOOFLIX_TEST", "Vidlove link added: $label")
                                vidloveCount++
                            } catch (e: Exception) {
                                Log.d("WOOFLIX_TEST", "Vidlove event parse error: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("WOOFLIX_TEST", "Vidlove $source error: ${e.message}")
                    }
                }

                Log.d("WOOFLIX_TEST", "Vidlove done: links=$vidloveCount")
            } catch (e: Exception) {
                Log.e("WOOFLIX_TEST", "Vidlove failed", e)
            }


            // ==================== VAPLAYER ====================
            Log.d("WOOFLIX_TEST", "=== ARRANCANDO VaPlayer ===")

            try {
                val externalType = if (kind == "tv") "tv" else "movie"

                val externalUrl =
                    "https" + "://" +
                    "api.themoviedb.org/3/" +
                    "$externalType/$tmdbId/external_ids" +
                    "?api_key=e1a8efff4415028c5c266b3fcd50db6e"

                val externalResponse = app.get(
                    externalUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0"
                    ),
                    timeout = 15000
                )

                if (!externalResponse.isSuccessful) {
                    Log.d(
                        "WOOFLIX_TEST",
                        "VaPlayer external_ids HTTP ${externalResponse.code}"
                    )
                } else {
                    val externalJson = JSONObject(externalResponse.text)

                    val imdbId = externalJson.optString("imdb_id")
                        .takeIf { it.isNotBlank() }

                    if (imdbId == null) {
                        Log.d(
                            "WOOFLIX_TEST",
                            "VaPlayer: IMDb ID no encontrado para TMDB $tmdbId"
                        )
                    } else {
                        Log.d(
                            "WOOFLIX_TEST",
                            "VaPlayer IMDb resolved: $imdbId"
                        )

                        val referer =
                            "https" + "://" + "nextgencloudfabric.com/"

                        val vaUrl = if (kind == "tv") {
                            "https" + "://" +
                            "streamdata.vaplayer.ru/api.php" +
                            "?imdb=$imdbId&type=tv" +
                            "&season=$season&episode=$episode"
                        } else {
                            "https" + "://" +
                            "streamdata.vaplayer.ru/api.php" +
                            "?imdb=$imdbId&type=movie"
                        }

                        val vaResponse = app.get(
                            vaUrl,
                            headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0"
                            ),
                            timeout = 15000
                        )

                        if (!vaResponse.isSuccessful) {
                            Log.d(
                                "WOOFLIX_TEST",
                                "VaPlayer API HTTP ${vaResponse.code}"
                            )
                        } else {
                            val vaJson = JSONObject(vaResponse.text)
                            val dataObj = vaJson.optJSONObject("data")
                            val streams = dataObj?.optJSONArray("stream_urls")

                            if (streams == null || streams.length() == 0) {
                                Log.d(
                                    "WOOFLIX_TEST",
                                    "VaPlayer: no stream_urls"
                                )
                            } else {
                                var vaCount = 0

                                for (i in 0 until streams.length()) {
                                    val streamUrl = streams.optString(i)
                                        .takeIf { it.isNotBlank() }
                                        ?: continue

                                    callback(
                                        newExtractorLink(
                                            source = "VaPlayer",
                                            name = "VaPlayer - ${i + 1}",
                                            url = streamUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            headers = mapOf(
                                                "Referer" to referer,
                                                "User-Agent" to "Mozilla/5.0"
                                            )

                                            quality = Qualities.Unknown.value
                                        }
                                    )

                                    vaCount++

                                    Log.d(
                                        "WOOFLIX_TEST",
                                        "VaPlayer link added: ${i + 1}"
                                    )
                                }

                                Log.d(
                                    "WOOFLIX_TEST",
                                    "VaPlayer done: links=$vaCount"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "WOOFLIX_TEST",
                    "VaPlayer failed",
                    e
                )
            }

        var resolvedImdbId: String? = null

        // ==================== SUBDL SUBTITLE ====================
        Log.d("WOOFLIX_TEST", "=== ARRANCANDO SubDL SUBTITLE ===")

        try {
            val externalType = if (kind == "tv") "tv" else "movie"

            val externalUrl =
                "https" + "://" +
                "api.themoviedb.org/3/" +
                "$externalType/$tmdbId/external_ids" +
                "?api_key=e1a8efff4415028c5c266b3fcd50db6e"

            val externalResponse = app.get(
                externalUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0"
                ),
                timeout = 15000
            )

            if (!externalResponse.isSuccessful) {
                Log.d(
                    "WOOFLIX_TEST",
                    "SubDL external_ids HTTP ${externalResponse.code}"
                )
            } else {
                val externalJson = JSONObject(externalResponse.text)

                val imdbId = externalJson.optString("imdb_id")
                    .takeIf { it.isNotBlank() }

                resolvedImdbId = imdbId

                if (imdbId == null) {
                    Log.d(
                        "WOOFLIX_TEST",
                        "SubDL: IMDb ID no encontrado"
                    )
                } else {
                    Log.d(
                        "WOOFLIX_TEST",
                        "SubDL IMDb resolved: $imdbId"
                    )

                    val subDlApi =
                        com.lagradost.cloudstream3.syncproviders.providers.SubDlApi()

                    val subDlRepo =
                        com.lagradost.cloudstream3.syncproviders.SubtitleRepo(subDlApi)

                    val authData = subDlRepo.authData()

                    Log.d(
                        "WOOFLIX_TEST",
                        "SubDL authData: ${authData != null}"
                    )

                    if (authData == null) {
                        Log.d(
                            "WOOFLIX_TEST",
                            "SubDL: no hay sesión autenticada disponible"
                        )
                    } else {
                        val search =
                            com.lagradost.cloudstream3.subtitles
                                .AbstractSubtitleEntities.SubtitleSearch(
                                    "",
                                    "es",
                                    imdbId,
                                    tmdbId.toIntOrNull(),
                                    null,
                                    null,
                                    episode,
                                    season,
                                    null
                                )

                        Log.d(
                            "WOOFLIX_TEST",
                            "SubDL: ejecutando búsqueda ES..."
                        )

                        val results =
                            subDlApi.search(authData, search)

                        Log.d(
                            "WOOFLIX_TEST",
                            "SubDL resultados ES: ${results?.size ?: 0}"
                        )

                        val episodeTag =
                            if (kind == "tv" && season != null && episode != null) {
                                "S${season.toString().padStart(2, '0')}" +
                                    "E${episode.toString().padStart(2, '0')}"
                            } else {
                                null
                            }

                        results?.forEachIndexed { index, result ->
                            Log.d(
                                "WOOFLIX_TEST",
                                "SubDL[$index]: name=${result.name} lang=${result.lang}"
                            )

                            try {
                                val resource =
                                    subDlApi.resource(authData, result)

                                val subtitles =
                                    resource?.getSubtitles() ?: emptyList()

                                Log.d(
                                    "WOOFLIX_TEST",
                                    "SubDL[$index]: resources=${subtitles.size}"
                                )

                                subtitles.forEachIndexed { subIndex, subtitle ->
                                    val subtitleName = subtitle.name ?: ""

                                    val matchesEpisode =
                                        episodeTag == null ||
                                            subtitleName.contains(
                                                episodeTag,
                                                ignoreCase = true
                                            )

                                    Log.d(
                                        "WOOFLIX_TEST",
                                        "SubDL[$index][$subIndex]: " +
                                            "name=$subtitleName " +
                                            "match=$matchesEpisode"
                                    )

                                    if (matchesEpisode) {
                                        Log.d(
                                            "WOOFLIX_TEST",
                                            "SubDL CALLBACK: lang=Spanish url=${subtitle.url}"
                                        )
                                        try {
                                            val subPath = android.net.Uri.parse(subtitle.url).path ?: ""
                                            val subFile = java.io.File(subPath)
                                            Log.d(
                                                "WOOFLIX_TEST",
                                                "SubDL FILE: exists=${subFile.exists()} size=${subFile.length()} path=${subFile.path}"
                                            )
                                            if (subFile.exists()) {
                                                val preview = subFile.readText().take(150).replace("\n", " | ")
                                                Log.d(
                                                    "WOOFLIX_TEST",
                                                    "SubDL FILE PREVIEW: $preview"
                                                )

                                                val srtFile = java.io.File.createTempFile(
                                                    "subdl-",
                                                    ".srt",
                                                    subFile.parentFile
                                                )

                                                subFile.copyTo(srtFile, overwrite = true)

                                                Log.d(
                                                    "WOOFLIX_TEST",
                                                    "SubDL SRT COPY: exists=${srtFile.exists()} size=${srtFile.length()} url=${srtFile.toURI()}"
                                                )

                                                subtitleCallback(
                                                    SubtitleFile(
                                                        "Spanish",
                                                        srtFile.toURI().toString()
                                                    )
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e("WOOFLIX_TEST", "SubDL FILE READ ERROR", e)
                                        }
                                        Log.d(
                                            "WOOFLIX_TEST",
                                            "SubDL subtitle added: $subtitleName"
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(
                                    "WOOFLIX_TEST",
                                    "SubDL[$index]: resource failed",
                                    e
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(
                "WOOFLIX_TEST",
                "SubDL SUBTITLE failed",
                e
            )
        }

            // ==================== WYZIESUBS SUBTITLE ====================
        Log.d("WOOFLIX_TEST", "=== ARRANCANDO WyzieSubs ===")

        try {
            val wyzieApiKey = plugin.getWyzieApiKey()

            val wyzieImdbId = resolvedImdbId

            if (wyzieApiKey.isBlank()) {
                Log.d(
                    "WOOFLIX_TEST",
                    "WyzieSubs: API key no configurada"
                )
            } else if (wyzieImdbId == null) {
                Log.d(
                    "WOOFLIX_TEST",
                    "WyzieSubs: IMDb ID no disponible"
                )
            } else {
                val wyzieUrl = buildString {
                    append("https://sub.wyzie.io/search?")
                    append("id=$wyzieImdbId")

                if (kind == "tv" && season != null && episode != null) {
                    append("&season=$season")
                    append("&episode=$episode")
                }

                append("&source=all")
                append("&key=$wyzieApiKey")
            }

            val wyzieResponse = app.get(
                wyzieUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0"),
                timeout = 15000
            )

            Log.d("WOOFLIX_TEST", "WyzieSubs HTTP ${wyzieResponse.code}")

            if (wyzieResponse.isSuccessful) {
                val wyzieArray = org.json.JSONArray(wyzieResponse.text)

                Log.d(
                    "WOOFLIX_TEST",
                    "WyzieSubs resultados: ${wyzieArray.length()}"
                )

                for (i in 0 until wyzieArray.length()) {
                    val item = wyzieArray.getJSONObject(i)

                    val subtitleUrl = item.optString("url")
                    val language = item.optString("display")
                        .ifBlank { item.optString("language") }

                    if (subtitleUrl.isBlank() || language.isBlank()) continue

                    val normalizedLanguage = language.trim().lowercase()

                    val isSpanish =
                        normalizedLanguage == "spanish" ||
                        normalizedLanguage == "es" ||
                        normalizedLanguage == "spa" ||
                        normalizedLanguage.startsWith("spanish ")

                    val isEnglish =
                        normalizedLanguage == "english" ||
                        normalizedLanguage == "en" ||
                        normalizedLanguage == "eng" ||
                        normalizedLanguage.startsWith("english ")

                    if (!isSpanish && !isEnglish) continue

                    val outputLanguage = if (isSpanish) "Spanish" else "English"

                    Log.d(
                        "WOOFLIX_TEST",
                        "WyzieSubs[$i]: lang=$outputLanguage url=$subtitleUrl"
                    )

                    subtitleCallback(
                        SubtitleFile(outputLanguage, subtitleUrl)
                    )
                }
            } else {
                Log.d(
                    "WOOFLIX_TEST",
                    "WyzieSubs error: ${wyzieResponse.text.take(500)}"
                )
                }
            }
        } catch (e: Exception) {
            Log.e("WOOFLIX_TEST", "WyzieSubs failed", e)
        }

        return true
        }
    }
