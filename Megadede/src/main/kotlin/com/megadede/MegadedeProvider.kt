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

class MegadedeProvider : MainAPI() {

    override var mainUrl = "https://megadede.mobi"
    override var name = "Megadede"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override var lang = "es"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "megadede" to "Megadede"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        Log.d("MegadedeProvider", "HOME llamado: page=$page")

        return try {
            val response = app.get(
                mainUrl,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            )

            Log.d(
                "MegadedeProvider",
                "HOME response: success=${response.isSuccessful} code=${response.code} size=${response.text.length}"
            )

            if (!response.isSuccessful) {
                return newHomePageResponse(emptyList())
            }

            val html = response.text
            val homeLists = mutableListOf<HomePageList>()

            val sections = listOf(
                "Últimos Episodios",
                "Últimas Películas",
                "Últimas Series"
            )

            for (sectionTitle in sections) {
                val headingRegex = Regex(
                    """<h3[^>]*>\s*${Regex.escape(sectionTitle)}\s*</h3>""",
                    RegexOption.IGNORE_CASE
                )

                val heading = headingRegex.find(html) ?: continue

                val sectionStart = html.lastIndexOf(
                    "<section",
                    heading.range.first,
                    ignoreCase = true
                )

                val sectionEnd = html.indexOf(
                    "</section>",
                    heading.range.last + 1,
                    ignoreCase = true
                )

                if (sectionStart < 0 || sectionEnd < 0) {
                    continue
                }

                val sectionHtml = html.substring(
                    sectionStart,
                    sectionEnd + "</section>".length
                )

                val articleRegex = Regex(
                    """<article[^>]*class=["'][^"']*mv[^"']*["'][^>]*>(.*?)</article>""",
                    setOf(
                        RegexOption.IGNORE_CASE,
                        RegexOption.DOT_MATCHES_ALL
                    )
                )

                val items = mutableListOf<SearchResponse>()
                val seen = HashSet<String>()

                for (articleMatch in articleRegex.findAll(sectionHtml)) {
                    val article = articleMatch.groupValues[1]

                    val href = Regex(
                        """href=["']([^"']*(?:/serie/|/pelicula/|/anime/)[^"']*)["']""",
                        RegexOption.IGNORE_CASE
                    ).find(article)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: continue

                    val cleanHref = href
                        .replace("&amp;", "&")
                        .trim()

                    if (cleanHref.isBlank() || !seen.add(cleanHref)) {
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

                    val title = Regex(
                        """<h4[^>]*>(.*?)</h4>""",
                        setOf(
                            RegexOption.IGNORE_CASE,
                            RegexOption.DOT_MATCHES_ALL
                        )
                    )
                        .find(article)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { cleanHtml(it).trim() }
                        ?: continue

                    if (title.isBlank()) {
                        continue
                    }

                    val absolute = absoluteUrl(cleanHref)

                    when {
                        cleanHref.contains("/pelicula/") -> {
                            items.add(
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

                        cleanHref.contains("/serie/") -> {
                            val seriesUrl = cleanHref.substringBefore("/temporada/")

                            if (seen.add("SERIES:$seriesUrl")) {
                                items.add(
                                    newTvSeriesSearchResponse(
                                        title,
                                        absoluteUrl(seriesUrl),
                                        TvType.TvSeries,
                                        false
                                    ) {
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        }

                        cleanHref.contains("/anime/") -> {
                            Log.d(
                                "MegadedeProvider",
                                "HOME anime omitido por ahora: $title -> $cleanHref"
                            )
                        }
                    }
                }

                if (items.isNotEmpty()) {
                    Log.d(
                        "MegadedeProvider",
                        "HOME sección '$sectionTitle': ${items.size} resultados"
                    )

                    homeLists.add(
                        HomePageList(
                            sectionTitle,
                            items,
                            isHorizontalImages = true
                        )
                    )
                }
            }

            Log.d(
                "MegadedeProvider",
                "HOME listas finales: ${homeLists.size}"
            )

            newHomePageResponse(homeLists)
        } catch (e: Exception) {
            Log.e(
                "MegadedeProvider",
                "HOME ERROR",
                e
            )

            newHomePageResponse(emptyList())
        }
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
                    """href=["'](/(?:pelicula|serie)/[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(article)?.groupValues?.getOrNull(1)
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
                        ).find(it)?.groupValues?.getOrNull(1)
                    }

                val titleAttr = image
                    ?.value
                    ?.let {
                        Regex(
                            """title=["']([^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        ).find(it)?.groupValues?.getOrNull(1)
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

                if (href.startsWith("/serie/")) {
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
                } else {
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
    override suspend fun load(url: String): LoadResponse {
        val isMovie = "/pelicula/" in url
        val response = app.get(url)

        if (!response.isSuccessful) {
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

        val html = response.text
        val title = extractTitle(html)
        val poster = extractPoster(html)
        val year = extractYear(html)
        val description = extractDescription(html)

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                plot = description
            }
        }

        val episodes = mutableListOf<Episode>()

        val episodeRegex = Regex(
            """<a[^>]+href="(/serie/[^"]+/temporada/(\d+)/capitulo/(\d+))"[^>]*>([\s\S]*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val seenEpisodes = HashSet<String>()

        for (match in episodeRegex.findAll(html)) {
            val href = match.groupValues[1]
            val season = match.groupValues[2].toIntOrNull() ?: continue
            val episode = match.groupValues[3].toIntOrNull() ?: continue

            if (!seenEpisodes.add(href)) continue

            val block = match.groupValues[4]
            val episodeName = cleanHtml(block)
                .replace(
                    Regex(
                        """^\s*(?:Episodio|Capítulo|Capitulo)\s*[\d.:-]*\s*""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()
                .ifBlank {
                    "Episodio $episode"
                }

            episodes.add(
                newEpisode(absoluteUrl(href)) {
                    name = episodeName
                    this.season = season
                    this.episode = episode
                }
            )
        }

        episodes.sortWith(
            compareBy<Episode> { it.season }
                .thenBy { it.episode }
        )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.year = year
            plot = description
            showStatus = ShowStatus.Completed
        }
    }

    private fun absoluteUrl(url: String): String {
        val value = url.trim()

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }

        return if (value.startsWith("/")) {
            "$mainUrl$value"
        } else {
            "$mainUrl/$value"
        }
    }

    private fun cleanHtml(value: String): String {
        return value
            .replace(Regex("""<[^>]*>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractTitle(html: String): String {
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

    private fun extractPoster(html: String): String? {
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

    private fun extractYear(html: String): Int? {
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

        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractDescription(html: String): String? {
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

    private fun findVidUrl(html: String): String? {
        val relative = Regex(
            """changeServer\(\s*['"](/vidurl/[^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        if (relative != null) {
            return absoluteUrl(relative)
        }

        return Regex(
            """<iframe[^>]+id=["']player["'][^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let(::absoluteUrl)
    }

    private fun sha256Bytes(value: String): ByteArray {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256Hex(value: String): String {
        return sha256Bytes(value).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun solvePow(
        challenge: String,
        difficulty: Int
    ): Pair<Long, ByteArray> {

        var nonce = 0L
        val prefix = "0".repeat(difficulty)

        while (true) {
            val hash = sha256Hex("$challenge$nonce")

            if (hash.startsWith(prefix)) {
                return Pair(
                    nonce,
                    sha256Bytes("$challenge$nonce")
                )
            }

            nonce++
        }
    }

    private fun decryptEmbed69(
        encrypted: String,
        aesKey: ByteArray
    ): String? {
        return try {
            val raw = Base64.decode(
                encrypted,
                Base64.DEFAULT
            )

            if (raw.size <= 16) return null

            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)

            val cipher = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            val key = SecretKeySpec(
                aesKey.copyOf(32),
                "AES"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                IvParameterSpec(iv)
            )

            String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractEmbed69Links(
        embedUrl: String
    ): List<Pair<String, String>> {

        val response = app.get(
            embedUrl,
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
        ).find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val difficulty = Regex(
            """POW_DIFFICULTY\s*=\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: 3

        val salt = Regex(
            """POW_SALT\s*=\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val dataText = Regex(
            """let\s+dataLink\s*=\s*(\[[\s\S]*?\]);""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val (nonce, _) = solvePow(
            challenge,
            difficulty
        )

        val aesKey = sha256Bytes(
            "$challenge$nonce$salt"
        )

        val array = try {
            JSONArray(dataText)
        } catch (_: Exception) {
            return emptyList()
        }

        val links = mutableListOf<Pair<String, String>>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val language = item.optString(
                "video_language",
                ""
            )

            val embeds = item.optJSONArray(
                "sortedEmbeds"
            ) ?: continue

            for (j in 0 until embeds.length()) {
                val embed = embeds.optJSONObject(j)
                    ?: continue

                val server = embed.optString(
                    "servername",
                    ""
                )

                val encrypted = embed.optString(
                    "link",
                    ""
                )

                if (
                    server.isBlank() ||
                    encrypted.isBlank()
                ) {
                    continue
                }

                val decrypted = decryptEmbed69(
                    encrypted,
                    aesKey
                ) ?: continue

                if (
                    decrypted.startsWith("http://") ||
                    decrypted.startsWith("https://")
                ) {
                    links.add(
                        Pair(
                            server,
                            "$language|$decrypted"
                        )
                    )
                }
            }
        }

        return links
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var pageUrl = data

        if (!pageUrl.startsWith("http")) {
            pageUrl = absoluteUrl(pageUrl)
        }

        val pageResponse = app.get(
            pageUrl,
            headers = mapOf(
                "Referer" to mainUrl
            )
        )

        if (!pageResponse.isSuccessful) {
            return false
        }

        val html = pageResponse.text

        val servers = mutableListOf<String>()

        Regex(
            """changeServer\(\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            val server = it.groupValues[1]

            if (!servers.contains(server)) {
                servers.add(server)
            }
        }

        val iframeUrl = findVidUrl(html)

        if (
            iframeUrl != null &&
            !servers.contains(iframeUrl)
        ) {
            servers.add(iframeUrl)
        }

        var found = false

        for (server in servers) {

            val serverUrl = absoluteUrl(server)

            if (
                serverUrl.contains(
                    "/vidurl/",
                    ignoreCase = true
                )
            ) {
                val links = extractEmbed69Links(
                    serverUrl
                )

                for ((serverName, encoded) in links) {

                    val parts = encoded.split(
                        "|",
                        limit = 2
                    )

                    val language = parts
                        .getOrNull(0)
                        .orEmpty()

                    val realUrl = parts
                        .getOrNull(1)
                        .orEmpty()

                    if (realUrl.isBlank()) continue

                    val displayName = when {
                        serverName.equals(
                            "vidhide",
                            true
                        ) -> "Vidhide"

                        serverName.equals(
                            "streamwish",
                            true
                        ) -> "Streamwish"

                        serverName.equals(
                            "voe",
                            true
                        ) -> "Voe"

                        serverName.equals(
                            "rapidvideo",
                            true
                        ) -> "Rapidvideo"

                        else -> serverName
                    }

                    val label = if (
                        language.isNotBlank()
                    ) {
                        "$displayName $language"
                    } else {
                        displayName
                    }

                    try {
                        loadExtractor(
                            realUrl,
                            serverUrl,
                            subtitleCallback,
                            callback
                        )

                        found = true
                    } catch (_: Exception) {
                        callback(
                            newExtractorLink(
                                source = "Embed69",
                                name = label,
                                url = realUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = serverUrl
                                quality = Qualities.Unknown.value
                            }
                        )

                        found = true
                    }
                }

            } else if (
                serverUrl.contains(
                    "uqlink.php",
                    ignoreCase = true
                )
            ) {

                try {
                    loadExtractor(
                        serverUrl,
                        pageUrl,
                        subtitleCallback,
                        callback
                    )

                    found = true
                } catch (_: Exception) {
                    callback(
                        newExtractorLink(
                            source = "Uqload",
                            name = "Uqload",
                            url = serverUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = pageUrl
                            quality = Qualities.Unknown.value
                        }
                    )

                    found = true
                }
            }
        }

        return found
    }
}
