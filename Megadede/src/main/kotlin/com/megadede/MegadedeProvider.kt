package com.megadede

import android.util.Base64
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

    override var mainUrl = "https://megadede.mobi/"
    override var name = "Megadede"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override var lang = "es"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "https://megadede.mobi/search?s=vikingos" to "Megadede"
    )

    private fun absoluteUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> mainUrl.removeSuffix("/") + url
            else -> "$mainUrl$url"
        }
    }

    private fun cleanHtml(text: String): String {
        return text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractTitle(html: String): String {
        return Regex(
            """<h1[^>]*>(.*?)</h1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let(::cleanHtml)
            ?.takeIf { it.isNotBlank() }
            ?: "Megadede"
    }

    private fun extractPoster(html: String): String? {
        return Regex(
            """<div class="movie-poster"[^>]*>\s*<img[^>]+src="([^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractYear(html: String): Int? {
        return Regex(
            """<div class="movie-meta"[\s\S]*?<span[^>]*>\s*<i[^>]*>\s*</i>\s*(\d{4})\s*</span>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""")
                .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractDescription(html: String): String? {
        return Regex(
            """<h2 class="description"[^>]*>(.*?)</h2>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let(::cleanHtml)
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = app.get("$mainUrl/search?s=$encoded")

        if (!response.isSuccessful) return emptyList()

        val html = response.text
        val results = mutableListOf<SearchResponse>()

        val regex = Regex(
            """<a[^>]+href="(/(?:pelicula|serie)/[^"]+)"[^>]*>[\s\S]*?<img[^>]+(?:alt|title)="([^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val seen = HashSet<String>()

        for (match in regex.findAll(html)) {
            val href = match.groupValues[1]
            val title = cleanHtml(match.groupValues[2])

            if (!seen.add(href)) continue
            if (title.isBlank()) continue

            val type = if (href.startsWith("/serie/")) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

            val poster = Regex(
                """<a[^>]+href="${Regex.escape(href)}"[\s\S]*?<img[^>]+src="([^"]+)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(html)?.groupValues?.getOrNull(1)

            results.add(
                if (type == TvType.Movie) {
                    newMovieSearchResponse(
                        title,
                        absoluteUrl(href),
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                    }
                } else {
                    newTvSeriesSearchResponse(
                        title,
                        absoluteUrl(href),
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                }
            )
        }

        return results
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
