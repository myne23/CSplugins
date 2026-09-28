package com.animeonline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeOnlineProvider : MainAPI() {

    override var mainUrl = "https://ww3.animeonline.ninja"
    override var name = "AnimeOnline"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries
    )

    override var lang = "es"
    override val hasMainPage = true

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val posterHeaders: Map<String, String>
        get() {
            val base = mutableMapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            )

            runCatching {
                cloudflareKiller.getCookieHeaders(mainUrl).toMap()
            }.getOrNull()?.forEach { (k, v) ->
                base[k] = v
            }

            return base
        }

    private fun parseAnimeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href]")?.attr("href")
            ?: return null

        val title = article.selectFirst(".data h3")?.text()?.trim()
            ?: return null

        val image = article.selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }

        return newAnimeSearchResponse(
            title,
            fixUrl(link),
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
        }
    }

    private fun parseEpisodeCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a[href*='/episodio/']")?.attr("href")
            ?: return null

        val title = article.selectFirst(".data h3")?.text()?.trim()
            ?: return null

        val image = article.selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }

        val episodeTitle = article.selectFirst(".epiposter h4")?.text()?.trim()

        return newAnimeSearchResponse(
            if (!episodeTitle.isNullOrBlank()) {
                "$title - $episodeTitle"
            } else {
                title
            },
            fixUrl(link),
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val response = app.get(
            "$mainUrl/inicio/",
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        )

        val document = response.document
        val sections = ArrayList<HomePageList>()

        val latestEpisodes = document
            .select("div.items article.item.se.episodes")
            .mapNotNull { parseEpisodeCard(it) }

        if (latestEpisodes.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimos episodios ⚡",
                    latestEpisodes
                )
            )
        }

        val latestAnimeHeader = document
            .select("header")
            .firstOrNull {
                it.text().contains("ÚLTIMOS ANIMES AGREGADOS")
            }

        val latestAnime = latestAnimeHeader
            ?.nextElementSibling()
            ?.nextElementSibling()
            ?.select("article.item")
            ?.mapNotNull { parseAnimeCard(it) }
            ?: emptyList()

        if (latestAnime.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimos animes agregados 💥",
                    latestAnime
                )
            )
        }

        val latestMoviesHeader = document
            .select("header")
            .firstOrNull {
                it.text().contains("ÚLTIMAS PELICULAS AGREGADAS")
            }

        val latestMovies = latestMoviesHeader
            ?.nextElementSibling()
            ?.nextElementSibling()
            ?.select("article.item")
            ?.mapNotNull { article ->
                val link = article.selectFirst("a[href]")?.attr("href")
                    ?: return@mapNotNull null

                val title = article.selectFirst(".data h3")?.text()?.trim()
                    ?: return@mapNotNull null

                val image = article.selectFirst("img")
                val poster = image?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() }

                newMovieSearchResponse(
                    title,
                    fixUrl(link),
                    TvType.Movie
                ) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            }
            ?: emptyList()

        if (latestMovies.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "Últimas peliculas agregadas 🎬",
                    latestMovies
                )
            )
        }

        return newHomePageResponse(
            sections,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val document = app.get(
            "$mainUrl/search/?s=$encodedQuery",
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        return document
            .select("article.item")
            .mapNotNull { article ->

                val link = article.selectFirst("a[href]")?.attr("href")
                    ?: return@mapNotNull null

                val title = article.selectFirst(".data h3")?.text()?.trim()
                    ?: return@mapNotNull null

                val image = article.selectFirst("img")
                val poster = image?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() }

                newAnimeSearchResponse(
                    title,
                    fixUrl(link),
                    TvType.Anime
                ) {
                    this.posterUrl = poster
                    this.posterHeaders = this@AnimeOnlineProvider.posterHeaders
                }
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val document = app.get(
            url,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val title = document
            .select("h1")
            .lastOrNull()
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        var description: String? = null

        for (heading in document.select("h2")) {
            if (heading.text().trim().equals("Sinopsis", ignoreCase = true)) {
                val headingHtml = heading.parent()?.html() ?: ""

                val marker = "Sinopsis"
                val markerIndex = headingHtml.indexOf(marker, ignoreCase = true)

                if (markerIndex >= 0) {
                    val afterMarker = headingHtml.substring(markerIndex + marker.length)

                    val stripped = afterMarker
                        .replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    if (stripped.isNotBlank()) {
                        description = stripped
                    }
                }
                break
            }
        }

        val seasons = document
            .select("#seasons > .se-c")
            .mapIndexedNotNull { index, seasonElement ->

                val seasonNumber = seasonElement
                    .selectFirst(".se-q .se-t")
                    ?.text()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: (index + 1)

                val episodeLinks = seasonElement
                    .select(".episodios a[href]")

                val episodeImages = seasonElement
                    .select(".episodios img")

                val episodes = episodeLinks
                    .mapIndexedNotNull { episodeIndex, element ->

                        val href = element.attr("href").trim()
                        if (href.isBlank()) return@mapIndexedNotNull null

                        val name = element.text()
                            .trim()
                            .ifBlank { "Episodio" }

                        val episodeNumber = episodeIndex + 1

                        var episodePoster: String? = null

                        if (episodeIndex < episodeImages.size) {
                            val image = episodeImages[episodeIndex]

                            val dataSrc = image.attr("data-src")
                            val lazySrc = image.attr("data-lazy-src")
                            val src = image.attr("src")
                            val srcset = image.attr("srcset")

                            episodePoster = when {
                                dataSrc.isNotBlank() -> dataSrc
                                lazySrc.isNotBlank() -> lazySrc
                                src.isNotBlank() -> src
                                srcset.isNotBlank() -> srcset.substringBefore(",").trim().substringBefore(" ")
                                else -> null
                            }
                        }

                        newEpisode(fixUrl(href)) {
                            this.name = name
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = episodePoster
                        }
                    }

                if (episodes.isEmpty()) {
                    null
                } else {
                    seasonNumber to episodes
                }
            }

        if (seasons.isEmpty()) return null

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = poster
            posterHeaders = this@AnimeOnlineProvider.posterHeaders
            this.plot = description

            seasons.forEach { (_, episodeList) ->
                addEpisodes(
                    DubStatus.Subbed,
                    episodeList
                )
            }
        }
    }

    // --- FUNCIÓN DE FALLBACK PARA UQLOAD CON JS UNPACKER ---
    private suspend fun resolveUqload(
        url: String,
        referer: String,
        serverName: String,
        language: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(url, referer = referer).text
            
            // Buscar el script empaquetado de JS (eval(function(p,a,c,k,e,d)...))
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\)""")
            val packedScript = packedRegex.find(response)?.value
            
            // Desempaquetar el código si existe, de lo contrario buscar en el texto plano
            val htmlToSearch = if (packedScript != null) {
                JsUnpacker(packedScript).unpack() ?: response
            } else {
                response
            }
            
            // Buscar file: "url"
            val fileUrl = Regex("""file:\s*["'](https[^"']+)["']""").find(htmlToSearch)?.groupValues?.get(1)

            if (!fileUrl.isNullOrBlank()) {
                val isM3u8 = fileUrl.contains(".m3u8")
                callback(
                    ExtractorLink(
                        source = "Uqload",
                        name = "$serverName - $language",
                        url = fileUrl,
                        referer = "https://uqload.vc/",
                        quality = Qualities.Unknown.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== ANIMEONLINE ALL SOURCES EXTRACTOR TEST ===")

        val document = app.get(
            data,
            referer = "$mainUrl/",
            interceptor = cloudflareKiller
        ).document

        val postId = document
            .selectFirst("[data-post]")
            ?.attr("data-post")
            ?.trim()

        if (postId.isNullOrBlank()) {
            return false
        }

        val apiUrl =
            "$mainUrl/wp-json/dooplayer/v1/post/$postId?type=tv&source=1"

        val apiResponse = app.get(
            apiUrl,
            referer = data,
            interceptor = cloudflareKiller
        )

        val embedUrl = apiResponse.text
            .substringAfter("\"embed_url\":\"", "")
            .substringBefore("\"")
            .replace("\\/", "/")

        if (embedUrl.isBlank()) {
            return false
        }

        val embedDocument = app.get(
            embedUrl,
            referer = data,
            interceptor = cloudflareKiller
        ).document

        val languageBlocks = embedDocument.select(".OD")

        var sourceCount = 0
        var linkCount = 0

        languageBlocks.forEach { block ->
            val language = when {
                block.hasClass("OD_SUB") -> "SUB"
                block.hasClass("OD_LAT") -> "LAT"
                block.hasClass("OD_ES") -> "ES"
                block.hasClass("OD_EN") -> "EN"
                else -> "UNKNOWN"
            }

            block.select("li[onclick*='go_to_player']").forEach { item ->
                val server = item
                    .selectFirst("span")
                    ?.text()
                    ?.trim()
                    ?: "UNKNOWN"

                val onclick = item.attr("onclick")

                val sourceUrl = Regex(
                    """go_to_player\(['"]([^'"]+)['"]\)"""
                )
                    .find(onclick)
                    ?.groupValues
                    ?.getOrNull(1)

                if (sourceUrl.isNullOrBlank()) return@forEach

                sourceCount++
                val currentSource = sourceCount

                var foundLink = false

                val result = loadExtractor(
                    url = sourceUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        foundLink = true
                        linkCount++

                        println("LINK FOUND[$currentSource] LANG=$language SERVER=$server NAME=${link.name}")
                        callback(link)
                    }
                )

                // FALLBACK MANUAL (Si el extractor no resolvió y la URL es de Uqload)
                if (!foundLink && sourceUrl.contains("uqload", ignoreCase = true)) {
                    println("FALLBACK[$currentSource] Intentando resolver Uqload manualmente...")
                    
                    val uqloadResolved = resolveUqload(sourceUrl, data, server, language, callback)
                    
                    if (uqloadResolved) {
                        foundLink = true
                        linkCount++
                        println("FALLBACK[$currentSource] ÉXITO: Uqload resuelto manualmente.")
                    } else {
                        println("FALLBACK[$currentSource] FALLÓ: No se encontró media en Uqload.")
                    }
                }
            }
        }

        return linkCount > 0
    }
}
