package com.megadede

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull


class MegadedeProvider : MainAPI() {

    override var mainUrl = "https://megadede.mobi"
    override var name = "Megadede"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    override var lang = "es"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "peliculas" to "Películas",
        "series" to "Series",
        "animes" to "Animes"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val startTime = System.nanoTime()

        val baseUrl = when (request.name) {
            "Películas" -> "$mainUrl/peliculas"
            "Series" -> "$mainUrl/series"
            "Animes" -> "$mainUrl/animes"
            else -> mainUrl
        }

        val url = if (page > 1) {
            "$baseUrl?page=$page"
        } else {
            baseUrl
        }

        Log.d(
            "MegadedeProvider",
            "HOME request='${request.name}' page=$page url=$url"
        )

        val getStart = System.nanoTime()

        val response = app.get(
            url,
            headers = mapOf(
                "Referer" to mainUrl
            )
        )

        val getMs = (System.nanoTime() - getStart) / 1_000_000

        Log.d(
            "MegadedeProvider",
            "HOME GET terminado: success=${response.isSuccessful} code=${response.code} time=${getMs}ms"
        )

        if (!response.isSuccessful) {
            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        val parseStart = System.nanoTime()

        val html = response.text
        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        val articleRegex = Regex(
            """<article[^>]*class=["'][^"']*mv[^"']*["'][^>]*>(.*?)</article>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )

        for (match in articleRegex.findAll(html)) {
            val article = match.groupValues[1]

            val href = Regex(
                """href=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(article)
                ?.groupValues
                ?.getOrNull(1)
                ?: continue

            val absolute = absoluteUrl(href)

            val isAnime = absolute.contains("/anime/")
            val isSeries = absolute.contains("/serie/")
            val isMovie = absolute.contains("/pelicula/")

            if (!isAnime && !isSeries && !isMovie) {
                continue
            }

            if (!seen.add(absolute)) {
                continue
            }

            val poster = Regex(
                """<img[^>]+(?:src|data-src)=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(article)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { absoluteUrl(it) }

            val title = Regex(
                """<h[1-6][^>]*>(.*?)</h[1-6]>""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            )
                .find(article)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
                ?: continue

            when {
                isAnime -> {
                    results.add(
                        newAnimeSearchResponse(
                            title,
                            absolute,
                            TvType.Anime
                        ) {
                            this.posterUrl = poster
                        }
                    )
                }

                isSeries -> {
                    results.add(
                        newTvSeriesSearchResponse(
                            title,
                            absolute,
                            TvType.TvSeries
                        ) {
                            this.posterUrl = poster
                        }
                    )
                }

                isMovie -> {
                    results.add(
                        newMovieSearchResponse(
                            title,
                            absolute,
                            TvType.Movie
                        ) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }

        val parseMs = (System.nanoTime() - parseStart) / 1_000_000
        val totalMs = (System.nanoTime() - startTime) / 1_000_000

        Log.d(
            "MegadedeProvider",
            "HOME parse terminado: results=${results.size} html=${html.length} time=${parseMs}ms total=${totalMs}ms"
        )

        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("MegadedeProvider", "SEARCH llamado: $query")

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?s=$encoded"

        Log.d("MegadedeProvider", "SEARCH URL: $url")

        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            )

            Log.d(
                "MegadedeProvider",
                "SEARCH response: success=${response.isSuccessful} code=${response.code} size=${response.text.length}"
            )

            if (!response.isSuccessful) {
                return emptyList()
            }

            val html = response.text
            val results = mutableListOf<SearchResponse>()
            val seen = HashSet<String>()

            val articleRegex = Regex(
                """<article[^>]*class=["'][^"']*mv[^"']*["'][^>]*>(.*?)</article>""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            )

            val articles = articleRegex.findAll(html).toList()

            Log.d(
                "MegadedeProvider",
                "SEARCH articles encontrados: ${articles.size}"
            )

            for (match in articles) {
                val article = match.groupValues[1]

                val href = Regex(
                    """href=["'](/(?:pelicula|serie|anime)/[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )
                    .find(article)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: continue

                if (!seen.add(href)) {
                    continue
                }

                val image = Regex(
                    """<img[^>]+src=["']([^"']+)["'][^>]*>""",
                    RegexOption.IGNORE_CASE
                ).find(article)

                val poster = image
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }

                val alt = image
                    ?.value
                    ?.let {
                        Regex(
                            """alt=["']([^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(it)
                            ?.groupValues
                            ?.getOrNull(1)
                    }

                val titleAttr = image
                    ?.value
                    ?.let {
                        Regex(
                            """title=["']([^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(it)
                            ?.groupValues
                            ?.getOrNull(1)
                    }

                var title = titleAttr ?: alt ?: ""

                title = cleanHtml(title)
                    .replace(
                        Regex(
                            """^Ver\s+""",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .replace(
                        Regex(
                            """\s+-\s+Ver online.*$""",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .replace(
                        Regex(
                            """\s+\(\d{4}\)\s+online.*$""",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .trim()

                if (title.isBlank()) {
                    continue
                }

                Log.d(
                    "MegadedeProvider",
                    "SEARCH resultado: title='$title' href='$href' poster='$poster'"
                )

                val absolute = absoluteUrl(href)

                when {
                    href.startsWith("/anime/") -> {
                        results.add(
                            newAnimeSearchResponse(
                                title,
                                absolute,
                                TvType.Anime,
                                false
                            ) {
                                this.posterUrl = poster
                            }
                        )
                    }

                    href.startsWith("/serie/") -> {
                        results.add(
                            newTvSeriesSearchResponse(
                                title,
                                absolute,
                                TvType.TvSeries,
                                false
                            ) {
                                this.posterUrl = poster
                            }
                        )
                    }

                    href.startsWith("/pelicula/") -> {
                        results.add(
                            newMovieSearchResponse(
                                title,
                                absolute,
                                TvType.Movie,
                                false
                            ) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            Log.d(
                "MegadedeProvider",
                "SEARCH resultados finales: ${results.size}"
            )

            results
        } catch (e: Exception) {
            Log.e(
                "MegadedeProvider",
                "SEARCH ERROR",
                e
            )
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("MegadedeProvider", "LOAD llamado: $url")

        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            )

            Log.d(
                "MegadedeProvider",
                "LOAD response: success=${response.isSuccessful} code=${response.code} size=${response.text.length}"
            )

            if (!response.isSuccessful) {
                Log.e(
                    "MegadedeProvider",
                    "LOAD HTTP ERROR: ${response.code}"
                )
                return null
            }

            val html = response.text

            Log.d(
                "MegadedeProvider",
                "LOAD HTML temporada=${html.contains("/temporada/")}"
            )

            Log.d(
                "MegadedeProvider",
                "LOAD HTML capitulo=${html.contains("/capitulo/")}"
            )

            val title = Regex(
                """<h1[^>]*>(.*?)</h1>""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { cleanHtml(it).trim() }
                ?: "Megadede"

            Log.d(
                "MegadedeProvider",
                "LOAD title='$title'"
            )

            val poster = Regex(
                """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

            val year = Regex(
                """\b(19|20)\d{2}\b"""
            )
                .find(html)
                ?.value
                ?.toIntOrNull()

            val description =
                Regex(
                    """<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { cleanHtml(it).trim() }
                    ?.takeIf { it.isNotBlank() }
                    ?: Regex(
                        """<h2[^>]*class=["'][^"']*description[^"']*["'][^>]*>(.*?)</h2>""",
                        setOf(
                            RegexOption.IGNORE_CASE,
                            RegexOption.DOT_MATCHES_ALL
                        )
                    )
                        .find(html)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { cleanHtml(it).trim() }

            val pageUrl = url

            Log.d(
                "MegadedeProvider",
                "LOAD tipo: anime=${pageUrl.contains("/anime/")} serie=${pageUrl.contains("/serie/")}"
            )

            if (pageUrl.contains("/serie/") || pageUrl.contains("/anime/")) {

                Log.d(
                    "MegadedeProvider",
                    "LOAD entrando parser de episodios"
                )

                val episodes = mutableListOf<Episode>()

                val episodeRegex = Regex(
                    """<a[^>]+href=["']([^"']*/temporada/(\d+)/capitulo/(\d+)[^"']*)["'][^>]*>(.*?)</a>""",
                    setOf(
                        RegexOption.IGNORE_CASE,
                        RegexOption.DOT_MATCHES_ALL
                    )
                )

                val matches = episodeRegex.findAll(html).toList()

                Log.d(
                    "MegadedeProvider",
                    "LOAD matches episodios=${matches.size}"
                )

                for (match in matches) {
                    val href = match.groupValues[1]
                    val season = match.groupValues[2].toIntOrNull() ?: continue
                    val episode = match.groupValues[3].toIntOrNull() ?: continue
                    val cardHtml = match.groupValues[4]

                    val episodeTitle = Regex(
                        """<p[^>]*>(.*?)</p>""",
                        setOf(
                            RegexOption.IGNORE_CASE,
                            RegexOption.DOT_MATCHES_ALL
                        )
                    )
                        .find(cardHtml)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { cleanHtml(it).trim() }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Episodio $episode"

                    val episodeUrl = absoluteUrl(href)

                    var episodePoster: String? = poster
                    var episodeDescription: String? = null

                    if (matches.size <= 30) {
                        try {
                            val episodeHtml = app.get(episodeUrl).text

                            episodePoster = Regex(
                                """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(episodeHtml)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?: poster

                            episodeDescription = Regex(
                                """<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']+)["']""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(episodeHtml)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.let { cleanHtml(it).trim() }
                                ?.takeIf {
                                    it.isNotBlank() &&
                                    !it.startsWith("No se encontró una sinopsis", ignoreCase = true)
                                }
                        } catch (e: Exception) {
                            Log.d(
                                "MegadedeProvider",
                                "LOAD error metadata episodio S$season E$episode: ${e.message}"
                            )
                        }
                    }

                    Log.d(
                        "MegadedeProvider",
                        "LOAD episodio S$season E$episode '$episodeTitle' poster=${episodePoster != null}"
                    )

                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.season = season
                            this.episode = episode
                            this.posterUrl = episodePoster ?: poster
                            this.description = episodeDescription
                        }
                    )
                }

                val sortedEpisodes = episodes
                    .distinctBy { it.data }
                    .sortedWith(
                        compareBy<Episode> { it.season ?: 0 }
                            .thenBy { it.episode ?: 0 }
                    )

                Log.d(
                    "MegadedeProvider",
                    "LOAD episodios finales=${sortedEpisodes.size}"
                )

                return newTvSeriesLoadResponse(
                    title,
                    pageUrl,
                    if (pageUrl.contains("/anime/")) TvType.Anime else TvType.TvSeries,
                    sortedEpisodes
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                }
            }

            Log.d(
                "MegadedeProvider",
                "LOAD entrando parser pelicula"
            )

            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }

        } catch (e: Exception) {
            Log.e(
                "MegadedeProvider",
                "LOAD ERROR",
                e
            )
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            "MegadedeProvider",
            "LINKS llamado: data=$data"
        )

        val pageUrl = absoluteUrl(data)

        Log.d(
            "MegadedeProvider",
            "LINKS pageUrl=$pageUrl"
        )

        return try {
            val response = app.get(
                pageUrl,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            )

            Log.d(
                "MegadedeProvider",
                "LINKS page response: success=${response.isSuccessful} code=${response.code} size=${response.text.length}"
            )

            if (!response.isSuccessful) {
                return false
            }

            val html = response.text
            val servers = mutableListOf<String>()

            Regex(
                """changeServer\(\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach {
                val rawServer = it.groupValues[1]
                val server = absoluteUrl(rawServer)

                if (!servers.contains(server)) {
                    servers.add(server)

                    Log.d(
                        "MegadedeProvider",
                        "LINKS servidor encontrado: $server"
                    )
                }
            }

            val iframeUrl = findVidUrl(html)

            Log.d(
                "MegadedeProvider",
                "LINKS iframe/vidurl: $iframeUrl"
            )

            if (
                iframeUrl != null &&
                !servers.contains(iframeUrl)
            ) {
                servers.add(iframeUrl)

                Log.d(
                    "MegadedeProvider",
                    "LINKS iframe agregado: $iframeUrl"
                )
            }

            Log.d(
                "MegadedeProvider",
                "LINKS total servidores: ${servers.size}"
            )

            var found = false

            /*
             * Embed69 se resuelve primero porque necesitamos sus URLs
             * antes de poder lanzar Vidhide / Streamwish / Voe.
             */
            val embed69Servers = servers.filter {
                it.contains("/vidurl/", ignoreCase = true)
            }

            val directServers = servers.filter {
                !it.contains("/vidurl/", ignoreCase = true)
            }

            for (server in embed69Servers) {
                val serverUrl = absoluteUrl(server)

                Log.d(
                    "MegadedeProvider",
                    "LINKS procesando Embed69: $serverUrl"
                )

                val links = extractEmbed69Links(serverUrl)

                Log.d(
                    "MegadedeProvider",
                    "LINKS Embed69 devolvio ${links.size} links"
                )

                if (links.isNotEmpty()) {
                    coroutineScope {
                        links.map { (serverName, encoded) ->
                            async {
                                val realUrl = encoded

                                if (realUrl.isBlank()) {
                                    Log.d(
                                        "MegadedeProvider",
                                        "LINKS $serverName URL vacía"
                                    )
                                    return@async
                                }

                                val language = "LAT"

                                val displayName = when {
                                    serverName.equals("vidhide", true) -> "Vidhide"
                                    serverName.equals("streamwish", true) -> "Streamwish"
                                    serverName.equals("voe", true) -> "Voe"
                                    else -> serverName
                                }

                                Log.d(
                                    "MegadedeProvider",
                                    "LINKS Embed69 resultado: server=$serverName language=$language url=$realUrl"
                                )

                                if (serverName.equals("vidhide", true)) {
                                    try {
                                        Log.d(
                                            "MegadedeProvider",
                                            "LINKS Vidhide directo: $realUrl"
                                        )

                                        val hlsUrl = withTimeoutOrNull(7000L) {
                                            extractVidhideLink(realUrl)
                                        }

                                        if (!hlsUrl.isNullOrBlank()) {
                                            Log.d(
                                                "MegadedeProvider",
                                                "LINKS Vidhide HLS encontrado: $hlsUrl"
                                            )

                                            callback(
                                                newExtractorLink(
                                                    "Vidhide",
                                                    "Vidhide",
                                                    hlsUrl,
                                                    ExtractorLinkType.M3U8
                                                ) {
                                                    referer = realUrl
                                                }
                                            )

                                            found = true

                                            Log.d(
                                                "MegadedeProvider",
                                                "LINK EMITIDO: source=Vidhide name=Vidhide url=$hlsUrl quality=0"
                                            )
                                        } else {
                                            Log.d(
                                                "MegadedeProvider",
                                                "LINKS Vidhide directo: no se obtuvo HLS"
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(
                                            "MegadedeProvider",
                                            "LINKS Vidhide ERROR",
                                            e
                                        )
                                    }

                                    return@async
                                }

                                if (serverName.equals("voe", true)) {
                                    try {
                                        Log.d(
                                            "MegadedeProvider",
                                            "LINKS Voe directo: $realUrl"
                                        )

                                        val hlsUrl = withTimeoutOrNull(25000L) {
                                            withContext(Dispatchers.Default) {
                                                extractVoeLink(realUrl)
                                            }
                                        }

                                        if (!hlsUrl.isNullOrBlank()) {
                                            Log.d(
                                                "MegadedeProvider",
                                                "LINKS Voe HLS encontrado: $hlsUrl"
                                            )

                                            callback(
                                                newExtractorLink(
                                                    "Voe",
                                                    "Voe",
                                                    hlsUrl,
                                                    ExtractorLinkType.M3U8
                                                ) {
                                                    referer = realUrl
                                                }
                                            )

                                            found = true

                                            Log.d(
                                                "MegadedeProvider",
                                                "LINK EMITIDO: source=Voe name=Voe url=$hlsUrl quality=0"
                                            )
                                        } else {
                                            Log.d(
                                                "MegadedeProvider",
                                                "LINKS Voe: no se obtuvo HLS"
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(
                                            "MegadedeProvider",
                                            "LINKS Voe ERROR",
                                            e
                                        )
                                    }

                                    return@async
                                }

                                Log.d(
                                    "MegadedeProvider",
                                    "LINKS llamando loadExtractor: name=$displayName url=$realUrl"
                                )

                                try {
                                    val completed = withTimeoutOrNull(7000L) {
                                        var extractorFound = false

                                        val extractorCallback: (ExtractorLink) -> Unit = { link ->
                                            Log.d(
                                                "MegadedeProvider",
                                                "LINK EMITIDO: source=${link.source} name=${link.name} url=${link.url} quality=${link.quality}"
                                            )

                                            callback(link)
                                            extractorFound = true
                                            found = true
                                        }

                                        loadExtractor(
                                            realUrl,
                                            serverUrl,
                                            subtitleCallback,
                                            extractorCallback
                                        )

                                        Log.d(
                                            "MegadedeProvider",
                                            "LINKS loadExtractor terminado: name=$displayName extractorFound=$extractorFound found=$found"
                                        )

                                        true
                                    }

                                    if (completed == null) {
                                        Log.d(
                                            "MegadedeProvider",
                                            "LINKS EXTRACTOR TIMEOUT: name=$displayName limite=7000ms"
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        "MegadedeProvider",
                                        "LINKS loadExtractor ERROR: name=$displayName url=$realUrl",
                                        e
                                    )

                                    try {
                                        callback(
                                            newExtractorLink(
                                                displayName,
                                                displayName,
                                                realUrl
                                            )
                                        )

                                        found = true

                                        Log.d(
                                            "MegadedeProvider",
                                            "LINKS fallback directo agregado: name=$displayName"
                                        )
                                    } catch (fallbackError: Exception) {
                                        Log.e(
                                            "MegadedeProvider",
                                            "LINKS fallback ERROR: name=$displayName",
                                            fallbackError
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            /*
             * Servidores que no vienen dentro de Embed69.
             * También se procesan en paralelo para evitar que uno lento
             * bloquee los demás.
             */
            if (directServers.isNotEmpty()) {
                coroutineScope {
                    directServers.map { server ->
                        async {
                            val serverUrl = absoluteUrl(server)

                            Log.d(
                                "MegadedeProvider",
                                "LINKS servidor directo: $serverUrl"
                            )

                            try {
                                val completed = withTimeoutOrNull(7000L) {
                                    val extractorCallback: (ExtractorLink) -> Unit = { link ->
                                        Log.d(
                                            "MegadedeProvider",
                                            "LINK EMITIDO: source=${link.source} name=${link.name} url=${link.url} quality=${link.quality}"
                                        )

                                        callback(link)
                                        found = true
                                    }

                                    loadExtractor(
                                        serverUrl,
                                        pageUrl,
                                        subtitleCallback,
                                        extractorCallback
                                    )

                                    Log.d(
                                        "MegadedeProvider",
                                        "LINKS loadExtractor terminado servidor directo: $serverUrl found=$found"
                                    )

                                    true
                                }

                                if (completed == null) {
                                    Log.d(
                                        "MegadedeProvider",
                                        "LINKS EXTRACTOR TIMEOUT servidor directo: $serverUrl limite=7000ms"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(
                                    "MegadedeProvider",
                                    "LINKS servidor directo ERROR: $serverUrl",
                                    e
                                )
                            }
                        }
                    }.awaitAll()
                }
            }

            Log.d(
                "MegadedeProvider",
                "LINKS resultado final: found=$found"
            )

            found
        } catch (e: Exception) {
            Log.e(
                "MegadedeProvider",
                "LINKS ERROR",
                e
            )
            false
        }
    }

    private fun findVidUrl(html: String): String? {
        val iframe = Regex(
            """<iframe[^>]+src=["']([^"']*/vidurl/[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

        return iframe?.let { absoluteUrl(it) }
    }


    private suspend fun extractVidhideLink(embedUrl: String): String? {
        return try {
            Log.d(
                "MegadedeProvider",
                "Vidhide directo: GET $embedUrl"
            )

            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                )
            )

            if (!response.isSuccessful) {
                Log.d(
                    "MegadedeProvider",
                    "Vidhide directo: HTTP ${response.code}"
                )
                return null
            }

            val html = response.text

            val packedRegex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\)\)"""
            )

            val match = packedRegex.find(html)

            if (match == null) {
                Log.d(
                    "MegadedeProvider",
                    "Vidhide directo: Packer no encontrado"
                )
                return null
            }

            var packed = match.groupValues[1]
            val base = match.groupValues[2].toInt()
            var count = match.groupValues[3].toInt()
            val dictionary = match.groupValues[4].split("|")

            fun toBase36(value: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyz"

                if (value == 0) {
                    return "0"
                }

                var n = value
                val result = StringBuilder()

                while (n > 0) {
                    result.append(chars[n % 36])
                    n /= 36
                }

                return result.reverse().toString()
            }

            while (count-- > 0) {
                if (count >= dictionary.size) {
                    continue
                }

                val word = dictionary[count]

                if (word.isEmpty()) {
                    continue
                }

                val token = toBase36(count)

                packed = packed.replace(
                    Regex("""\b${Regex.escape(token)}\b"""),
                    word
                )
            }

            val hls4 = Regex(
                """["']hls4["']\s*:\s*["']([^"']+)["']"""
            ).find(packed)?.groupValues?.getOrNull(1)

            if (hls4.isNullOrBlank()) {
                Log.d(
                    "MegadedeProvider",
                    "Vidhide directo: hls4 no encontrado"
                )
                return null
            }

            val result = if (hls4.startsWith("http")) {
                hls4
            } else {
                "https://morencius.com$hls4"
            }

            Log.d(
                "MegadedeProvider",
                "Vidhide directo: HLS=$result"
            )

            result
        } catch (e: Exception) {
            Log.e(
                "MegadedeProvider",
                "Vidhide directo ERROR",
                e
            )
            null
        }
    }

    private suspend fun extractEmbed69Links(
        vidUrl: String
    ): List<Pair<String, String>> {

        val response = app.get(
            vidUrl,
            headers = mapOf(
                "Referer" to mainUrl
            )
        )

        if (!response.isSuccessful) {
            return emptyList()
        }

        val html = response.text

        val challenge = Regex(
            """POW_CHALLENGE\s*=\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val salt = Regex(
            """POW_SALT\s*=\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val difficulty = Regex(
            """POW_DIFFICULTY\s*=\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 3

        val dataLinkText = Regex(
            """let\s+dataLink\s*=\s*(\[[\s\S]*?\]);""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val nonce = solvePow(
            challenge,
            difficulty
        )

        Log.d(
            "MegadedeProvider",
            "Embed69 PoW nonce=$nonce challenge=$challenge difficulty=$difficulty"
        )

        val keyHash = sha256(
            "$challenge$nonce$salt"
        )

        val array = JSONArray(dataLinkText)
        val results = mutableListOf<Pair<String, String>>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val embeds = item.optJSONArray("sortedEmbeds")
                ?: continue

            for (j in 0 until embeds.length()) {
                val embed = embeds.getJSONObject(j)

                val serverName = embed.optString("servername")
                val encrypted = embed.optString("link")

                if (
                    serverName.isBlank() ||
                    encrypted.isBlank()
                ) {
                    continue
                }

                results.add(
                    serverName to decryptAes(
                        encrypted,
                        keyHash
                    )
                )
            }
        }

        return results
    }

    private fun solvePow(
        challenge: String,
        difficulty: Int
    ): Long {

        val prefix = "0".repeat(difficulty)
        var nonce = 0L

        while (true) {
            val hash = sha256(
                "$challenge$nonce"
            )

            if (hash.startsWith(prefix)) {
                return nonce
            }

            nonce++
        }
    }

    private fun decryptAes(
        encrypted: String,
        keyHash: String
    ): String {

        val key = hexToBytes(keyHash)
        val data = Base64.decode(
            encrypted,
            Base64.DEFAULT
        )

        val iv = data.copyOfRange(
            0,
            16
        )

        val ciphertext = data.copyOfRange(
            16,
            data.size
        )

        val cipher = Cipher.getInstance(
            "AES/CBC/PKCS5Padding"
        )

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(
                key,
                "AES"
            ),
            IvParameterSpec(iv)
        )

        return String(
            cipher.doFinal(ciphertext),
            StandardCharsets.UTF_8
        )
    }

    private fun sha256(
        value: String
    ): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(
                value.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(it)
            }
    }

    private fun hexToBytes(
        value: String
    ): ByteArray {

        return ByteArray(
            value.length / 2
        ) { index ->
            value.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16).toByte()
        }
    }

    private fun absoluteUrl(
        url: String
    ): String {
        val value = url.trim()

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        return if (value.startsWith("/")) {
            "$mainUrl$value"
        } else {
            "$mainUrl/$value"
        }
    }

    private fun cleanHtml(
        value: String
    ): String {
        return value
            .replace(
                Regex("""<[^>]*>"""),
                " "
            )
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }

    private fun extractTitle(
        html: String
    ): String {
        return Regex(
            """<h1[^>]*>(.*?)</h1>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { cleanHtml(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "Sin título"
    }

    private fun extractPoster(
        html: String
    ): String? {

        val poster = Regex(
            """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

        if (!poster.isNullOrBlank()) {
            return poster
        }

        return Regex(
            """<img[^>]+src=["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractYear(
        html: String
    ): Int? {

        val year = Regex(
            """(?:movie-meta|year)[^>]*>.*?(\d{4})""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (year != null) {
            return year
        }

        return Regex(
            """\b(19\d{2}|20\d{2})\b"""
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractDescription(
        html: String
    ): String? {
        return Regex(
            """<h2[^>]*class=["'][^"']*description[^"']*["'][^>]*>(.*?)</h2>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { cleanHtml(it) }
            ?.takeIf { it.isNotBlank() }
    }

private suspend fun extractVoeLink(embedUrl: String): String? {
    return try {
        Log.d(
            "MegadedeProvider",
            "Voe directo: GET $embedUrl"
        )

        val firstResponse = app.get(
            embedUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
            )
        )

        if (!firstResponse.isSuccessful) {
            Log.d(
                "MegadedeProvider",
                "Voe directo: HTTP inicial ${firstResponse.code}"
            )
            return null
        }

        var html = firstResponse.text

        // Voe redirige a uno de sus dominios CDN.
        val redirectUrl = Regex(
            """window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]"""
        ).find(html)?.groupValues?.getOrNull(1)

        val realUrl = redirectUrl ?: embedUrl

        Log.d(
            "MegadedeProvider",
            "Voe URL real: $realUrl"
        )

        val pageResponse = if (realUrl != embedUrl) {
            app.get(
                realUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                )
            )
        } else {
            firstResponse
        }

        html = pageResponse.text

        Log.d(
            "MegadedeProvider",
            "Voe página headers: ${pageResponse.headers}"
        )

        Log.d(
            "MegadedeProvider",
            "Voe página cookies: ${pageResponse.cookies}"
        )

        // Si ya tenemos el JSON real, no hace falta ALTCHA.
        var encodedConfig = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']"""
        ).find(html)?.groupValues?.getOrNull(1)

        if (encodedConfig.isNullOrBlank()) {
            Log.d(
                "MegadedeProvider",
                "Voe: ALTCHA requerido"
            )

            val csrf = Regex(
                """name=["']_token["'][^>]+value=["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1)

            val challengeUrl = Regex(
                """<altcha-widget[^>]+challenge=["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1)

            if (csrf.isNullOrBlank() || challengeUrl.isNullOrBlank()) {
                Log.d(
                    "MegadedeProvider",
                    "Voe: no se encontró CSRF o challenge"
                )
                return null
            }

            Log.d(
                "MegadedeProvider",
                "Voe ALTCHA challenge: $challengeUrl"
            )

            val challengeResponse = app.get(
                challengeUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36",
                    "Referer" to realUrl
                )
            )

            if (!challengeResponse.isSuccessful) {
                Log.d(
                    "MegadedeProvider",
                    "Voe ALTCHA challenge HTTP ${challengeResponse.code}"
                )
                return null
            }

            val challengeJson = challengeResponse.text

            val algorithm = Regex(
                """"algorithm"\s*:\s*"([^"]+)""""
            ).find(challengeJson)?.groupValues?.getOrNull(1)

            val cost = Regex(
                """"cost"\s*:\s*(\d+)"""
            ).find(challengeJson)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val keyLength = Regex(
                """"keyLength"\s*:\s*(\d+)"""
            ).find(challengeJson)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val keyPrefix = Regex(
                """"keyPrefix"\s*:\s*"([^"]+)""""
            ).find(challengeJson)?.groupValues?.getOrNull(1)

            val nonce = Regex(
                """"nonce"\s*:\s*"([^"]+)""""
            ).find(challengeJson)?.groupValues?.getOrNull(1)

            val salt = Regex(
                """"salt"\s*:\s*"([^"]+)""""
            ).find(challengeJson)?.groupValues?.getOrNull(1)

            if (
                algorithm.isNullOrBlank() ||
                cost == null ||
                keyLength == null ||
                keyPrefix.isNullOrBlank() ||
                nonce.isNullOrBlank() ||
                salt.isNullOrBlank()
            ) {
                Log.d(
                    "MegadedeProvider",
                    "Voe ALTCHA: parámetros incompletos"
                )
                return null
            }

            Log.d(
                "MegadedeProvider",
                "Voe ALTCHA: algorithm=$algorithm cost=$cost keyLength=$keyLength prefix=$keyPrefix"
            )

            var solvedCounter = -1
            var solvedKey = ""

            val saltBytes = hexToBytes(salt)
            val nonceBytes = hexToBytes(nonce)

            val powStart = System.nanoTime()

            for (counter in 0 until 1_000_000) {
                val counterBytes = byteArrayOf(
                    ((counter ushr 24) and 0xff).toByte(),
                    ((counter ushr 16) and 0xff).toByte(),
                    ((counter ushr 8) and 0xff).toByte(),
                    (counter and 0xff).toByte()
                )

                val passwordBytes =
                    nonceBytes + counterBytes

                val derived = pbkdf2Sha256(
                    passwordBytes,
                    saltBytes,
                    cost,
                    keyLength
                )

                val hex = derived.joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }

                if (hex.startsWith(keyPrefix)) {
                    solvedCounter = counter
                    solvedKey = hex
                    break
                }
            }

            if (solvedCounter < 0) {
                Log.d(
                    "MegadedeProvider",
                    "Voe ALTCHA: PoW no resuelto"
                )
                return null
            }

            val powElapsedMs =
                (System.nanoTime() - powStart) / 1_000_000.0

            Log.d(
                "MegadedeProvider",
                "Voe ALTCHA resuelto: counter=$solvedCounter key=$solvedKey time=${powElapsedMs}ms"
            )

            // ALTCHA v2 espera el challenge como objeto JSON,
            // no como string. Conservamos exactamente el JSON
            // devuelto por el endpoint de challenge.
            val altchaPayload = """
                {"challenge":$challengeJson,"solution":{"counter":$solvedCounter,"derivedKey":"$solvedKey","time":$powElapsedMs}}
            """.trimIndent()

            Log.d(
                "MegadedeProvider",
                "Voe ALTCHA payload: challenge object + solution"
            )

            val payloadB64 = java.util.Base64
                .getEncoder()
                .encodeToString(
                    altchaPayload.toByteArray(Charsets.UTF_8)
                )

            // Usamos únicamente las cookies de la página que contiene
            // el formulario ALTCHA. El challenge no debe reemplazar
            // la sesión asociada al CSRF de esa página.
            val voeCookies = pageResponse.cookies.entries
                .joinToString("; ") { (name, value) ->
                    "$name=$value"
                }

            Log.d(
                "MegadedeProvider",
                "Voe ALTCHA cookies de sesión: ${pageResponse.cookies.keys}"
            )

            val postResponse = app.post(
                realUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36",
                    "Referer" to realUrl,
                    "Origin" to "https://katherineschoolphone.com",
                    "Cookie" to voeCookies,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "_token" to csrf,
                    "access" to "0",
                    "altcha" to payloadB64
                )
            )

            if (!postResponse.isSuccessful) {
                Log.d(
                    "MegadedeProvider",
                    "Voe ALTCHA POST HTTP ${postResponse.code}"
                )
                return null
            }

            html = postResponse.text

            Log.d(
                "MegadedeProvider",
                "Voe página después de ALTCHA: ${html.length} bytes"
            )
            Log.d(
                "MegadedeProvider",
                "Voe HTML después de ALTCHA:\n${html.take(4000)}"
            )
            encodedConfig = Regex(
                """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>"""
            ).find(html)?.groupValues?.getOrNull(1)
        }

        if (encodedConfig.isNullOrBlank()) {
            Log.d(
                "MegadedeProvider",
                "Voe: config JSON no encontrada"
            )
            return null
        }

        Log.d(
            "MegadedeProvider",
            "Voe config encontrada: ${encodedConfig.length} chars"
        )

        val decodedConfig = decodeVoeConfig(encodedConfig)

        if (decodedConfig.isNullOrBlank()) {
            Log.d(
                "MegadedeProvider",
                "Voe: no se pudo decodificar config"
            )
            return null
        }

        Log.d(
            "MegadedeProvider",
            "Voe config decodificada: $decodedConfig"
        )

        val source = Regex(
            """"source"\s*:\s*"([^"]+)""""
        ).find(decodedConfig)?.groupValues?.getOrNull(1)

        if (source.isNullOrBlank()) {
            Log.d(
                "MegadedeProvider",
                "Voe: source HLS no encontrada"
            )
            return null
        }

        val result = source
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        Log.d(
            "MegadedeProvider",
            "Voe HLS encontrado: $result"
        )

        result
    } catch (e: Exception) {
        Log.e(
            "MegadedeProvider",
            "Voe directo ERROR",
            e
        )
        null
    }
}

private fun decodeVoeConfig(encoded: String): String? {
    return try {
        // 1. ROT13
        var value = buildString {
            for (char in encoded) {
                append(
                    when (char) {
                        in 'A'..'Z' ->
                            ((char.code - 'A'.code + 13) % 26 + 'A'.code).toChar()

                        in 'a'..'z' ->
                            ((char.code - 'a'.code + 13) % 26 + 'a'.code).toChar()

                        else -> char
                    }
                )
            }
        }

        // 2. Separadores -> _
        val separators = listOf(
            "@$",
            "^^",
            "~@",
            "%?",
            "*~",
            "!!",
            "#&"
        )

        for (separator in separators) {
            value = value.replace(separator, "_")
        }

        // 3. Eliminar _
        value = value.replace("_", "")

        // 4. Base64
        var bytes = java.util.Base64
            .getDecoder()
            .decode(value)

        // 5. Restar 3 a cada byte
        bytes = bytes.map {
            (it.toInt() - 3).toByte()
        }.toByteArray()

        // 6. Invertir
        bytes.reverse()

        // 7. Base64 otra vez
        val decoded = java.util.Base64
            .getDecoder()
            .decode(String(bytes, Charsets.UTF_8))

        String(decoded, Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e(
            "MegadedeProvider",
            "Voe decode ERROR",
            e
        )
        null
    }
}


private fun pbkdf2Sha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLength: Int
): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")

    val blockCount =
        (keyLength + mac.macLength - 1) / mac.macLength

    val output = ByteArray(blockCount * mac.macLength)

    var outputOffset = 0

    for (block in 1..blockCount) {
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                password,
                "HmacSHA256"
            )
        )

        val blockSalt = salt + byteArrayOf(
            ((block ushr 24) and 0xff).toByte(),
            ((block ushr 16) and 0xff).toByte(),
            ((block ushr 8) and 0xff).toByte(),
            (block and 0xff).toByte()
        )

        var u = mac.doFinal(blockSalt)
        val t = u.copyOf()

        for (i in 1 until iterations) {
            mac.init(
                javax.crypto.spec.SecretKeySpec(
                    password,
                    "HmacSHA256"
                )
            )

            u = mac.doFinal(u)

            for (j in t.indices) {
                t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
        }

        System.arraycopy(
            t,
            0,
            output,
            outputOffset,
            t.size
        )

        outputOffset += t.size
    }

    return output.copyOf(keyLength)
}

private fun jsonString(value: String): String {
    return org.json.JSONObject.quote(value)
}
}
