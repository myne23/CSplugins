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
        val response = app.get(
            mainUrl,
            headers = mapOf(
                "Referer" to mainUrl
            )
        )

        if (!response.isSuccessful) {
            return newHomePageResponse(
                request.name,
                emptyList()
            )
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

            if (!absolute.contains("/pelicula/") &&
                !absolute.contains("/serie/")
            ) {
                continue
            }

            if (!seen.add(absolute)) {
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
                """<h[1-6][^>]*>(.*?)</h[1-6]>""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            )
                .find(article)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { cleanHtml(it) }
                ?.takeIf { it.isNotBlank() }
                ?: continue

            if (absolute.contains("/serie/")) {
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

        return newHomePageResponse(
            request.name,
            results
        )
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

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absoluteUrl(url)

        Log.d(
            "MegadedeProvider",
            "LOAD llamado: $pageUrl"
        )

        return try {
            val response = app.get(
                pageUrl,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            )

            if (!response.isSuccessful) {
                return null
            }

            val html = response.text
            val title = extractTitle(html)
            val poster = extractPoster(html)
            val year = extractYear(html)
            val description = extractDescription(html)

            if (pageUrl.contains("/serie/")) {
                val episodes = mutableListOf<Episode>()

                val episodeRegex = Regex(
                    """href=["']([^"']*/temporada/(\d+)/capitulo/(\d+)[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                )

                for (match in episodeRegex.findAll(html)) {
                    val href = match.groupValues[1]
                    val season = match.groupValues[2].toIntOrNull() ?: continue
                    val episode = match.groupValues[3].toIntOrNull() ?: continue

                    episodes.add(
                        newEpisode(absoluteUrl(href)) {
                            this.name = "Episodio $episode"
                            this.season = season
                            this.episode = episode
                        }
                    )
                }

                val sortedEpisodes = episodes.distinctBy { it.data }
                    .sortedWith(
                        compareBy<Episode> {
                            it.season ?: 0
                        }.thenBy {
                            it.episode ?: 0
                        }
                    )

                val seasonMap = sortedEpisodes
                    .groupBy { it.season ?: 1 }

                val seasonData = seasonMap
                    .toSortedMap()
                    .map { (season, eps) ->
                        newEpisode(
                            data = eps.firstOrNull()?.data ?: pageUrl
                        ) {
                            this.name = "Temporada $season"
                            this.season = season
                            this.episode = 0
                        }
                    }

                return newTvSeriesLoadResponse(
                    title,
                    pageUrl,
                    TvType.TvSeries,
                    sortedEpisodes
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                }
            }

            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl
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

            for (server in servers) {
                val serverUrl = absoluteUrl(server)

                Log.d(
                    "MegadedeProvider",
                    "LINKS procesando servidor: $serverUrl"
                )

                if (serverUrl.contains("/vidurl/", ignoreCase = true)) {
                    Log.d(
                        "MegadedeProvider",
                        "LINKS detectado Embed69 vidurl"
                    )

                    val links = extractEmbed69Links(serverUrl)

                    Log.d(
                        "MegadedeProvider",
                        "LINKS Embed69 devolvio ${links.size} links"
                    )

                    for ((serverName, encoded) in links) {
                        val realUrl = encoded

                        if (realUrl.isBlank()) {
                            Log.d(
                                "MegadedeProvider",
                                "LINKS $serverName URL vacía"
                            )
                            continue
                        }

                        val language = "LAT"

                        Log.d(
                            "MegadedeProvider",
                            "LINKS Embed69 resultado: server=$serverName language=$language url=$realUrl"
                        )

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

                        // Vidhide: extracción directa del HLS.
                        if (serverName.equals("vidhide", true)) {
                            Log.d(
                                "MegadedeProvider",
                                "LINKS Vidhide directo: $realUrl"
                            )

                            val hlsUrl = extractVidhideLink(realUrl)

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

                            continue
                        }

                        Log.d(
                            "MegadedeProvider",
                            "LINKS llamando loadExtractor: name=$displayName url=$realUrl"
                        )

                        try {
                            val extractorCallback: (ExtractorLink) -> Unit = { link ->
                                Log.d(
                                    "MegadedeProvider",
                                    "LINK EMITIDO: source=${link.source} name=${link.name} url=${link.url} quality=${link.quality}"
                                )

                                callback(link)
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
                                "LINKS loadExtractor terminado: name=$displayName found=$found"
                            )
                        } catch (e: Exception) {
                            Log.e(
                                "MegadedeProvider",
                                "LINKS loadExtractor ERROR: name=$displayName url=$realUrl",
                                e
                            )

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
                        }
                    }
                } else {
                    Log.d(
                        "MegadedeProvider",
                        "LINKS servidor no Embed69, intentando loadExtractor: $serverUrl"
                    )

                    try {
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
                    } catch (e: Exception) {
                        Log.e(
                            "MegadedeProvider",
                            "LINKS servidor directo ERROR: $serverUrl",
                            e
                        )
                    }
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
}
