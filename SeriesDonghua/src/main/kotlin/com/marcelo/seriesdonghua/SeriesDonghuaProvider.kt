package com.marcelo.seriesdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(url).document
        val items = mutableListOf<HomePageList>()

        if (page == 1 && request.name == "Nuevos Episodios") {
            val featuredDocument = app.get("$mainUrl/donghuas-en-emision/").document
            val featured = featuredDocument
                .select("article.donghua-card")
                .mapNotNull { it.toSearchResult() }
                .take(10)

            if (featured.isNotEmpty()) {
                items.add(HomePageList("Destacados", featured))
            }
        }

        val home = document
            .select("article.donghua-card")
            .mapNotNull { it.toSearchResult() }

        if (home.isNotEmpty()) {
            items.add(HomePageList(request.name, home))
        }

        return newHomePageResponse(
            items,
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a")?.attr("href")?.toString()
            ?: return null

        val href = fixUrl(link)

        var title = selectFirst(".card-title")?.text()?.trim()

        if (title.isNullOrBlank()) {
            title = selectFirst("a")?.attr("title")?.trim()
        }

        if (title.isNullOrBlank()) {
            return null
        }

        var imgUrl = selectFirst("img")?.attr("src")?.toString()

        if (imgUrl.isNullOrBlank()) {
            imgUrl = selectFirst("img")?.attr("data-src")?.toString()
        }

        if (imgUrl.isNullOrBlank()) {
            imgUrl = selectFirst("img")?.attr("data-lazy-src")?.toString()
        }

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = fixUrlNull(imgUrl)
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val document = app.get(
            "$mainUrl/buscar.php?s=$encodedQuery"
        ).document

        val results = document
            .select("article.donghua-card")
            .mapNotNull { it.toSearchResult() }
            .toMutableList()

        try {
            val donghuaWorldDocument = app.get(
                "https://donghuaworld.com/?s=$encodedQuery"
            ).document

            val normalizedQuery = query
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

            val donghuaWorldResult = donghuaWorldDocument
                .select("a[href*='/anime/'][itemprop='url'][title]")
                .mapNotNull { element ->
                    val href = element
                        .attr("href")
                        .trim()

                    val title = element
                        .attr("title")
                        .trim()

                    if (
                        href.isBlank() ||
                        title.isBlank() ||
                        href.contains("/anime/?")
                    ) {
                        return@mapNotNull null
                    }

                    val normalizedTitle = title
                        .lowercase()
                        .replace(Regex("[^a-z0-9]+"), " ")
                        .trim()

                    if (normalizedTitle.contains(" movie ")) {
                        return@mapNotNull null
                    }

                    val score = when {
                        normalizedTitle == normalizedQuery -> 100
                        normalizedTitle.startsWith("$normalizedQuery ") -> 95
                        normalizedTitle.contains(normalizedQuery) -> 90
                        normalizedQuery.contains(normalizedTitle) -> 80
                        else -> 0
                    }

                    if (score > 0) {
                        Triple(
                            score,
                            title,
                            fixUrl(href)
                        )
                    } else {
                        null
                    }
                }
                .distinctBy { it.third }
                .sortedByDescending { it.first }
                .firstOrNull()

            if (donghuaWorldResult != null) {
                val (_, title, href) = donghuaWorldResult

                println(
                    "SeriesDonghua: DonghuaWorld search -> $title | $href"
                )

                results.add(
                    newAnimeSearchResponse(
                        "$title · DonghuaWorld",
                        href,
                        TvType.Anime
                    )
                )
            } else {
                println(
                    "SeriesDonghua: DonghuaWorld no encontró resultado para '$query'"
                )
            }
        } catch (e: Exception) {
            println(
                "SeriesDonghua: ERROR búsqueda DonghuaWorld -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document
            .selectFirst("h1.hero-title")
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst("img.hero-poster")
            ?.attr("src")
            ?.toString()

        val plot = document
            .select(".glass-panel p")
            .joinToString("\n") { it.text() }

        val tags = document
            .select(".genre-pill-list a.genre-pill")
            .map { it.text() }

        val episodes = document
            .select("article.episode-card-item")
            .mapNotNull { epElement ->
                val epLink = epElement
                    .selectFirst("a")
                    ?.attr("href")
                    ?.toString()
                    ?: return@mapNotNull null

                val epNum = epElement
                    .attr("data-ep")
                    .toIntOrNull()

                val epTitle = epElement
                    .selectFirst(".card-title")
                    ?.text()
                    ?.toString()

                val epPoster = epElement
                    .selectFirst("img")
                    ?.attr("src")
                    ?.toString()

                newEpisode(epLink) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(epPoster)
                }
            }
            .reversed()

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.tags = tags
            this.addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SeriesDonghua: loadLinks data=$data")

        val pageResponse = try {
            app.get(data)
        } catch (e: Exception) {
            println(
                "SeriesDonghua: ERROR obteniendo episodio -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            return false
        }

        val document = pageResponse.document

        val videoId = document
            .selectFirst("[data-video-id]")
            ?.attr("data-video-id")
            ?.trim()

        val csrf = document
            .selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.trim()

        if (videoId.isNullOrBlank()) {
            println("SeriesDonghua: ERROR no se encontró data-video-id")
            return false
        }

        if (csrf.isNullOrBlank()) {
            println("SeriesDonghua: ERROR no se encontró CSRF")
            return false
        }

        val cookies = pageResponse.cookies.entries
            .joinToString("; ") { (cookieName, cookieValue) ->
                "$cookieName=$cookieValue"
            }

        println(
            "SeriesDonghua: videoId=$videoId cookies=${pageResponse.cookies.keys}"
        )

        val servers = listOf(
            "Dailymotion" to 0,
            "OK.ru" to 1,
            "Rumble" to 2,
            "Filemoon" to 3,
            "VOE" to 4
        )

        var linkCount = 0

        for ((serverName, serverIndex) in servers) {
            try {
                println(
                    "SeriesDonghua: solicitando $serverName index=$serverIndex"
                )

                val connection = URL(
                    "$mainUrl/api/player/get-server"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true

                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                )
                connection.setRequestProperty(
                    "Referer",
                    data
                )
                connection.setRequestProperty(
                    "Origin",
                    mainUrl
                )
                connection.setRequestProperty(
                    "Cookie",
                    cookies
                )
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
                connection.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
                )
                connection.setRequestProperty(
                    "X-CSRF-TOKEN",
                    csrf
                )
                connection.setRequestProperty(
                    "X-Requested-With",
                    "XMLHttpRequest"
                )

                val requestBody = JSONObject()
                    .put("video_id", videoId.toInt())
                    .put("server_index", serverIndex)
                    .toString()

                connection.outputStream.use { output ->
                    output.write(
                        requestBody.toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode

                val responseText = try {
                    if (responseCode in 200..299) {
                        connection.inputStream
                            .bufferedReader()
                            .use { it.readText() }
                    } else {
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: ""
                    }
                } finally {
                    connection.disconnect()
                }

                println(
                    "SeriesDonghua: $serverName HTTP=$responseCode " +
                        "response=${responseText.take(300)}"
                )

                if (responseCode !in 200..299) {
                    continue
                }

                val json = try {
                    JSONObject(responseText)
                } catch (e: Exception) {
                    println(
                        "SeriesDonghua: $serverName JSON inválido -> " +
                            "${e.message}"
                    )
                    continue
                }

                if (!json.optBoolean("success", false)) {
                    println(
                        "SeriesDonghua: $serverName API success=false"
                    )
                    continue
                }

                val embedUrl = json
                    .optString("embed_url")
                    .trim()

                if (embedUrl.isBlank()) {
                    println(
                        "SeriesDonghua: $serverName sin embed_url"
                    )
                    continue
                }

                println(
                    "SeriesDonghua: $serverName embed=$embedUrl"
                )

                if (serverName == "Dailymotion") {
                    val dailymotionLinks = loadDailymotionLinks(
                        embedUrl = embedUrl,
                        subtitleCallback = subtitleCallback
                    )

                    if (dailymotionLinks.isNotEmpty()) {
                        dailymotionLinks.forEach { link ->
                            linkCount++
                            callback(link)

                            println(
                                "SeriesDonghua: LINK Dailymotion -> ${link.url}"
                            )
                        }
                    } else {
                        println(
                            "SeriesDonghua: Dailymotion manual sin links"
                        )
                    }

                    continue
                }

                if (serverName == "OK.ru") {
                    val okLink = loadOkRuLinks(embedUrl)

                    if (okLink != null) {
                        linkCount++
                        callback(okLink)

                        println(
                            "SeriesDonghua: LINK OK.ru -> ${okLink.url}"
                        )
                    } else {
                        println(
                            "SeriesDonghua: OK.ru sin HLS"
                        )
                    }

                    continue
                }

                if (serverName == "VOE") {
                    val voeUrl = extractVoeLink(embedUrl)

                    if (voeUrl != null) {
                        val voeLink = newExtractorLink(
                            source = "VOE",
                            name = "VOE",
                            url = voeUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = embedUrl
                            quality = 0
                        }

                        linkCount++
                        callback(voeLink)

                        println(
                            "SeriesDonghua: LINK VOE -> $voeUrl"
                        )
                    } else {
                        println(
                            "SeriesDonghua: VOE sin HLS"
                        )
                    }

                    continue
                }

                loadExtractor(
                    url = embedUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        if (
                            serverName == "Rumble" &&
                            !link.url.contains("rumble.com/hls-vod/")
                        ) {
                            println(
                                "SeriesDonghua: Rumble descartado -> ${link.url}"
                            )
                        } else if (
                    serverName == "Filemoon" &&
                    (
                        link.type != ExtractorLinkType.M3U8 ||
                        !link.url.contains("master.m3u8")
                    )
                ) {
                    println(
                        "SeriesDonghua: Filemoon descartado -> ${link.url}"
                    )
                } else {
                            val modifiedLink = ExtractorLink(
                                source = link.source,
                                name = "${link.name} · $serverName",
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type
                            )

                            linkCount++
                            callback(modifiedLink)

                            println(
                                "SeriesDonghua: LINK $serverName -> ${link.url}"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                println(
                    "SeriesDonghua: ERROR $serverName -> " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        println(
            "SeriesDonghua: buscando fuente adicional DonghuaWorld"
        )

        try {
            val donghuaLinks = loadDonghuaWorldLinks(
                data = data,
                subtitleCallback = subtitleCallback
            )

            donghuaLinks.forEach { link ->
                val modifiedLink = ExtractorLink(
                    source = "DonghuaWorld",
                    name = "DonghuaWorld · Dailymotion",
                    url = link.url,
                    referer = link.referer,
                    quality = link.quality,
                    type = link.type
                )

                linkCount++
                callback(modifiedLink)

                println(
                    "SeriesDonghua: LINK DonghuaWorld Dailymotion -> ${link.url}"
                )
            }
        } catch (e: Exception) {
            println(
                "SeriesDonghua: ERROR DonghuaWorld -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        println(
            "SeriesDonghua: loadLinks final linkCount=$linkCount"
        )

        return linkCount > 0
    }

    private suspend fun loadDonghuaWorldLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val match = Regex(
            "/([^/]+)-episodio-(\\d+)/?$"
        ).find(data)

        if (match == null) {
            println(
                "SeriesDonghua: DonghuaWorld no pudo interpretar data=$data"
            )
            return emptyList()
        }

        val seriesSlug = match.groupValues[1]
        val episodeNumber = match.groupValues[2]

        val searchQuery = seriesSlug
            .replace("-", " ")
            .trim()

        println(
            "SeriesDonghua: DonghuaWorld búsqueda='$searchQuery' episodio=$episodeNumber"
        )

        val encodedQuery = URLEncoder
            .encode(searchQuery, "UTF-8")
            .replace("+", "+")

        val searchUrl = "https://donghuaworld.com/?s=$encodedQuery"

        val searchResponse = app.get(searchUrl)

        val normalizedSearch = searchQuery
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        val seriesCandidates = searchResponse.document
            .select("a[href*='/anime/'][itemprop='url']")
            .mapNotNull { element ->
                val href = element.attr("href").trim()
                val title = element
                    .attr("title")
                    .trim()
                    .ifBlank { element.text().trim() }

                if (
                    href.isBlank() ||
                    href.contains("/anime/?")
                ) {
                    return@mapNotNull null
                }

                val normalizedTitle = title
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), " ")
                    .trim()

                val normalizedSlug = href
                    .substringAfter("/anime/")
                    .trim('/')
                    .lowercase()

                val isMovie = normalizedSlug.contains("movie") ||
                    normalizedTitle.contains(" movie ")

                val score = when {
                    normalizedTitle == normalizedSearch && !isMovie -> 100
                    normalizedTitle.startsWith("$normalizedSearch ") && !isMovie -> 95
                    normalizedTitle.contains(normalizedSearch) && !isMovie -> 90
                    normalizedTitle == normalizedSearch -> 50
                    normalizedTitle.startsWith("$normalizedSearch ") -> 40
                    normalizedTitle.contains(normalizedSearch) -> 30
                    else -> 0
                }

                if (score > 0) {
                    Triple(score, title, fixUrl(href))
                } else {
                    null
                }
            }
            .distinctBy { it.third }
            .sortedByDescending { it.first }

        val seriesCandidate = seriesCandidates.firstOrNull()

        if (seriesCandidates.isNotEmpty()) {
            println(
                "SeriesDonghua: DonghuaWorld candidatos=" +
                    seriesCandidates.joinToString(" | ") {
                        "${it.first}:${it.second}:${it.third}"
                    }
            )
        }

        val seriesUrl = seriesCandidate?.third

        if (seriesUrl == null) {
            println(
                "SeriesDonghua: DonghuaWorld no encontró serie para '$searchQuery'"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: DonghuaWorld serie -> $seriesUrl"
        )

        val seriesResponse = app.get(seriesUrl)

        val episodeUrl = seriesResponse.document
            .select("a[href]")
            .mapNotNull { element ->
                val href = element.attr("href").trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val normalized = href
                    .substringBefore("?")
                    .substringBefore("#")

                val episodeRegex = Regex(
                    "episode-$episodeNumber(?:-|/)"
                )

                if (episodeRegex.containsMatchIn(normalized)) {
                    fixUrl(href)
                } else {
                    null
                }
            }
            .distinct()
            .firstOrNull()

        if (episodeUrl == null) {
            println(
                "SeriesDonghua: DonghuaWorld no encontró episodio $episodeNumber"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: DonghuaWorld episodio -> $episodeUrl"
        )

        val episodeResponse = app.get(episodeUrl)

        val embedUrl = episodeResponse.document
            .select("iframe[src*='geo.dailymotion.com'][src*='video=']")
            .mapNotNull { iframe ->
                iframe.attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .firstOrNull()

        if (embedUrl == null) {
            println(
                "SeriesDonghua: DonghuaWorld no encontró DM Player"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: DonghuaWorld DM Player -> $embedUrl"
        )

        return loadDailymotionLinks(
            embedUrl = embedUrl,
            subtitleCallback = subtitleCallback
        )
    }

    private suspend fun loadDailymotionLinks(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        println(
            "SeriesDonghua: Dailymotion resolving metadata endpoint=$embedUrl"
        )

        val videoId = embedUrl
            .substringAfter("video=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }

        if (videoId == null) {
            println(
                "SeriesDonghua: Dailymotion could not extract video id"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: Dailymotion access_id=$videoId"
        )

        val metadataUrl = "https://geo.dailymotion.com/videos/$videoId"

        val response = try {
            app.get(
                metadataUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Referer" to embedUrl,
                    "User-Agent" to
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                )
            )
        } catch (e: Exception) {
            println(
                "SeriesDonghua: Dailymotion metadata ERROR -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: Dailymotion metadata status=${response.code}"
        )

        val json = try {
            JSONObject(response.text)
        } catch (e: Exception) {
            println(
                "SeriesDonghua: Dailymotion metadata JSON ERROR -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            return emptyList()
        }

        val streamUrl = try {
            json
                .getJSONObject("stream")
                .optString("url")
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

        if (streamUrl == null) {
            println(
                "SeriesDonghua: Dailymotion stream.url not found"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: Dailymotion stream.url=$streamUrl"
        )

        val links = mutableListOf<ExtractorLink>()

        links.add(
            newExtractorLink(
                name = "Dailymotion",
                source = "Dailymotion",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "https://geo.dailymotion.com/"
                quality = 0
            }
        )

        println(
            "SeriesDonghua: Dailymotion manual linkCount=${links.size}"
        )

        return links
    }

    private suspend fun loadOkRuLinks(
        embedUrl: String
    ): ExtractorLink? {
        return try {
            println(
                "SeriesDonghua: OK.ru GET $embedUrl"
            )

            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (X11; Linux x86_64) " +
                        "AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                )
            )

            println(
                "SeriesDonghua: OK.ru HTTP=${response.code} " +
                "size=${response.text.length}"
            )

            if (!response.isSuccessful) {
                println(
                    "SeriesDonghua: OK.ru HTTP no exitoso"
                )
                return null
            }

            val normalized = response.text
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")
                .replace("\\\\/", "/")
                .replace("\\\\", "\\")

            val hlsMatch = Regex(
                """"hlsManifestUrl":"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).find(normalized)

            if (hlsMatch == null) {
                println(
                    "SeriesDonghua: OK.ru hlsManifestUrl NO encontrado"
                )
                return null
            }

            val hlsUrl = hlsMatch.groupValues[1]
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")

            println(
                "SeriesDonghua: OK.ru HLS encontrado -> $hlsUrl"
            )

            newExtractorLink(
                source = "OK.ru",
                name = "OK.ru",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = 0
            }
        } catch (e: Exception) {
            println(
                "SeriesDonghua: OK.ru ERROR -> " +
                "${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private suspend fun extractVoeLink(
        embedUrl: String
    ): String? {
        return try {
            println(
                "SeriesDonghua: VOE directo GET $embedUrl"
            )

            val firstResponse = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (X11; Linux x86_64) " +
                        "AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                )
            )

            if (!firstResponse.isSuccessful) {
                println(
                    "SeriesDonghua: VOE HTTP inicial ${firstResponse.code}"
                )
                return null
            }

            var html = firstResponse.text

            val redirectUrl = Regex(
                """window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]"""
            ).find(html)?.groupValues?.getOrNull(1)

            val realUrl = redirectUrl ?: embedUrl

            println(
                "SeriesDonghua: VOE URL real -> $realUrl"
            )

            val pageResponse = if (realUrl != embedUrl) {
                app.get(
                    realUrl,
                    headers = mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (X11; Linux x86_64) " +
                            "AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
                    )
                )
            } else {
                firstResponse
            }

            html = pageResponse.text

            println(
                "SeriesDonghua: VOE página size=${html.length}"
            )

            var encodedConfig = Regex(
                """<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1)

            if (encodedConfig.isNullOrBlank()) {
                println(
                    "SeriesDonghua: VOE ALTCHA requerido"
                )

                val csrf = Regex(
                    """name=["']_token["'][^>]+value=["']([^"']+)["']"""
                ).find(html)?.groupValues?.getOrNull(1)

                val challengeUrl = Regex(
                    """<altcha-widget[^>]+challenge=["']([^"']+)["']"""
                ).find(html)?.groupValues?.getOrNull(1)

                if (
                    csrf.isNullOrBlank() ||
                    challengeUrl.isNullOrBlank()
                ) {
                    println(
                        "SeriesDonghua: VOE no se encontró CSRF o challenge"
                    )
                    return null
                }

                println(
                    "SeriesDonghua: VOE ALTCHA challenge -> $challengeUrl"
                )

                val challengeResponse = app.get(
                    challengeUrl,
                    headers = mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (X11; Linux x86_64) " +
                            "AppleWebKit/537.36 Chrome/151.0 Safari/537.36",
                        "Referer" to realUrl
                    )
                )

                if (!challengeResponse.isSuccessful) {
                    println(
                        "SeriesDonghua: VOE ALTCHA challenge HTTP " +
                        "${challengeResponse.code}"
                    )
                    return null
                }

                val challengeJson = challengeResponse.text

                val algorithm = Regex(
                    """"algorithm"\s*:\s*"([^"]+)""""
                ).find(challengeJson)?.groupValues?.getOrNull(1)

                val cost = Regex(
                    """"cost"\s*:\s*(\d+)"""
                ).find(challengeJson)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                val keyLength = Regex(
                    """"keyLength"\s*:\s*(\d+)"""
                ).find(challengeJson)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

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
                    println(
                        "SeriesDonghua: VOE ALTCHA parámetros incompletos"
                    )
                    return null
                }

                println(
                    "SeriesDonghua: VOE ALTCHA " +
                    "algorithm=$algorithm cost=$cost " +
                    "keyLength=$keyLength prefix=$keyPrefix"
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
                    println(
                        "SeriesDonghua: VOE ALTCHA PoW no resuelto"
                    )
                    return null
                }

                val powElapsedMs =
                    (System.nanoTime() - powStart) / 1_000_000.0

                println(
                    "SeriesDonghua: VOE ALTCHA resuelto " +
                    "counter=$solvedCounter time=${powElapsedMs}ms"
                )

                val altchaPayload = """
                    {"challenge":$challengeJson,"solution":{"counter":$solvedCounter,"derivedKey":"$solvedKey","time":$powElapsedMs}}
                """.trimIndent()

                val payloadB64 = java.util.Base64
                    .getEncoder()
                    .encodeToString(
                        altchaPayload.toByteArray(Charsets.UTF_8)
                    )

                val voeCookies = pageResponse.cookies.entries
                    .joinToString("; ") { (name, value) ->
                        "$name=$value"
                    }

                val postResponse = app.post(
                    realUrl,
                    headers = mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (X11; Linux x86_64) " +
                            "AppleWebKit/537.36 Chrome/151.0 Safari/537.36",
                        "Referer" to realUrl,
                        "Origin" to "https://katherineschoolphone.com",
                        "Cookie" to voeCookies,
                        "Content-Type" to
                            "application/x-www-form-urlencoded"
                    ),
                    data = mapOf(
                        "_token" to csrf,
                        "access" to "0",
                        "altcha" to payloadB64
                    )
                )

                if (!postResponse.isSuccessful) {
                    println(
                        "SeriesDonghua: VOE ALTCHA POST HTTP " +
                        "${postResponse.code}"
                    )
                    return null
                }

                html = postResponse.text

                println(
                    "SeriesDonghua: VOE página después ALTCHA " +
                    "size=${html.length}"
                )

                encodedConfig = Regex(
                    """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>"""
                ).find(html)?.groupValues?.getOrNull(1)
            }

            if (encodedConfig.isNullOrBlank()) {
                println(
                    "SeriesDonghua: VOE config JSON no encontrada"
                )
                return null
            }

            println(
                "SeriesDonghua: VOE config encontrada " +
                "${encodedConfig.length} chars"
            )

            val decodedConfig = decodeVoeConfig(encodedConfig)

            if (decodedConfig.isNullOrBlank()) {
                println(
                    "SeriesDonghua: VOE no se pudo decodificar config"
                )
                return null
            }

            val source = Regex(
                """"source"\s*:\s*"([^"]+)""""
            ).find(decodedConfig)
                ?.groupValues
                ?.getOrNull(1)

            if (source.isNullOrBlank()) {
                println(
                    "SeriesDonghua: VOE source HLS no encontrada"
                )
                return null
            }

            val result = source
                .replace("\\\\/", "/")
                .replace("\\u0026", "&")

            println(
                "SeriesDonghua: VOE HLS encontrado -> $result"
            )

            result
        } catch (e: Exception) {
            println(
                "SeriesDonghua: VOE directo ERROR -> " +
                "${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private fun decodeVoeConfig(
        encoded: String
    ): String? {
        return try {
            var value = buildString {
                for (char in encoded) {
                    append(
                        when (char) {
                            in 'A'..'Z' ->
                                (
                                    (char.code - 'A'.code + 13) % 26 +
                                    'A'.code
                                ).toChar()

                            in 'a'..'z' ->
                                (
                                    (char.code - 'a'.code + 13) % 26 +
                                    'a'.code
                                ).toChar()

                            else -> char
                        }
                    )
                }
            }

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

            value = value.replace("_", "")

            var bytes = java.util.Base64
                .getDecoder()
                .decode(value)

            bytes = bytes.map {
                (it.toInt() - 3).toByte()
            }.toByteArray()

            bytes.reverse()

            val decoded = java.util.Base64
                .getDecoder()
                .decode(
                    String(bytes, Charsets.UTF_8)
                )

            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            println(
                "SeriesDonghua: VOE decode ERROR -> ${e.message}"
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

        val output =
            ByteArray(blockCount * mac.macLength)

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
                    t[j] = (
                        t[j].toInt() xor u[j].toInt()
                    ).toByte()
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

    private fun hexToBytes(
        value: String
    ): ByteArray {
        val clean = value.trim()

        if (clean.length % 2 != 0) {
            throw IllegalArgumentException(
                "Hex inválido: longitud impar"
            )
        }

        return ByteArray(clean.length / 2) { index ->
            clean.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16).toByte()
        }
    }

}
