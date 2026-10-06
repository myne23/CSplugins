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

        return document
            .select("article.donghua-card")
            .mapNotNull { it.toSearchResult() }
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

                loadExtractor(
                    url = embedUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
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
                )
            } catch (e: Exception) {
                println(
                    "SeriesDonghua: ERROR $serverName -> " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        println(
            "SeriesDonghua: loadLinks final linkCount=$linkCount"
        )

        return linkCount > 0
    }

    private suspend fun loadDailymotionLinks(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        println(
            "SeriesDonghua: Dailymotion manual GET embed=$embedUrl"
        )

        val embedResponse = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36",
                    "Referer" to "$mainUrl/",
                    "Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to
                        "es-AR,es;q=0.9,en;q=0.8",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site"
                )
            )
        } catch (e: Exception) {
            println(
                "SeriesDonghua: Dailymotion embed ERROR -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            return emptyList()
        }

        val html = embedResponse.text

        println(
            "SeriesDonghua: Dailymotion embed HTML length=${html.length}"
        )

        val normalizedHtml = html
            .replace("\\\\/", "/")
            .replace("\\\\\\\\/", "/")

        val manifestMarker = "/cdn/manifest/video/"
        val manifestMarkerIndex = normalizedHtml.indexOf(manifestMarker)

        println(
            "SeriesDonghua: Dailymotion manifestMarkerIndex=$manifestMarkerIndex"
        )

        val manifestWordIndex = normalizedHtml.indexOf("manifest")
        println(
            "SeriesDonghua: Dailymotion manifestWordIndex=$manifestWordIndex"
        )

        val criticalIndex = normalizedHtml.indexOf("criticalMetadata")
        println(
            "SeriesDonghua: Dailymotion criticalMetadataIndex=$criticalIndex"
        )

        val streamIndex = normalizedHtml.indexOf("stream")
        println(
            "SeriesDonghua: Dailymotion streamIndex=$streamIndex"
        )

        val debugTerms = listOf(
            "endpoints",
            "embed_url",
            "lib_url",
            "metadata",
            "access_id",
            "video_id",
            "player_id",
            "api",
            "graphql",
            "dmp_fetchAndStoreMetadata"
        )

        for (term in debugTerms) {
            var searchFrom = 0
            var count = 0

            while (true) {
                val found = normalizedHtml.indexOf(term, searchFrom)

                if (found < 0 || count >= 5) {
                    break
                }

                println(
                    "SeriesDonghua: Dailymotion TERM=$term INDEX=$found"
                )

                val debugStart = maxOf(0, found - 500)
                val debugEnd = minOf(
                    normalizedHtml.length,
                    found + 2500
                )

                println(
                    "SeriesDonghua: Dailymotion TERM_DEBUG=$term " +
                        normalizedHtml.substring(debugStart, debugEnd)
                )

                searchFrom = found + term.length
                count++
            }
        }

        val manifestUrl = if (manifestMarkerIndex >= 0) {
            val protocolStart = normalizedHtml.lastIndexOf(
                "https://",
                manifestMarkerIndex
            )

            println(
                "SeriesDonghua: Dailymotion protocolStart=$protocolStart"
            )

            if (protocolStart >= 0) {
                val urlEnd = normalizedHtml.indexOf(
                    '"',
                    manifestMarkerIndex
                )

                println(
                    "SeriesDonghua: Dailymotion urlEnd=$urlEnd"
                )

                if (urlEnd > manifestMarkerIndex) {
                    normalizedHtml.substring(
                        protocolStart,
                        urlEnd
                    ).trim()
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }

        if (manifestUrl.isNullOrBlank()) {
            println(
                "SeriesDonghua: Dailymotion ERROR no se encontró manifest"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: Dailymotion manifest=$manifestUrl"
        )

        val manifestText = try {
            app.get(
                manifestUrl,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36",
                    "Referer" to "https://geo.dailymotion.com/",
                    "Origin" to "https://geo.dailymotion.com/"
                )
            ).text
        } catch (e: Exception) {
            println(
                "SeriesDonghua: Dailymotion manifest ERROR -> " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            return emptyList()
        }

        println(
            "SeriesDonghua: Dailymotion manifest length=${manifestText.length}"
        )

        val links = mutableListOf<ExtractorLink>()

        val lines = manifestText.lines()

        for (index in lines.indices) {
            val line = lines[index].trim()

            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue
            }

            val streamUrl = lines
                .drop(index + 1)
                .firstOrNull { it.trim().startsWith("http") }
                ?.trim()
                ?.substringBefore("#")
                ?.trim()

            if (streamUrl.isNullOrBlank()) {
                continue
            }

            val resolution = Regex(
                """RESOLUTION=(\d+)x(\d+)"""
            )
                .find(line)

            val height = resolution
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
                ?: 0

            val name = Regex(
                """NAME="([^"]+)"""
            )
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (height > 0) "${height}p" else "Auto"

            val qualityValue = when {
                height >= 1080 -> 1080
                height >= 720 -> 720
                height >= 480 -> 480
                height >= 360 -> 360
                else -> 0
            }

              links.add(
                  newExtractorLink(
                      name = "Dailymotion · $name",
                      source = "Dailymotion",
                      url = streamUrl,
                      type = ExtractorLinkType.M3U8
                  ) {
                      referer = "https://geo.dailymotion.com/"
                      quality = qualityValue
                  }
              )

            println(
                "SeriesDonghua: Dailymotion variant $name -> $streamUrl"
            )
        }

        println(
            "SeriesDonghua: Dailymotion manual variants=${links.size}"
        )

        return links
    }
}
