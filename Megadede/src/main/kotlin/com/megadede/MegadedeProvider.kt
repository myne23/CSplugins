package com.megadede

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    companion object {
        private const val TAG = "MegadedeProvider"

        // true = muestra los logs de debug (los Log.e de errores siempre se muestran).
        // En false no se construye ni un solo string de log, por eso es más rápido.
        private val DEBUG = false

        // false = /load no baja la página de cada episodio (poster/sinopsis por episodio).
        // Es lo más lento de load() en series; apagarlo lo acelera muchísimo.
        private val FETCH_EPISODE_METADATA = true
        private const val EPISODE_METADATA_MAX = 30

        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"

        // ---------- Regex compiladas UNA sola vez ----------
        private val OPT_I = RegexOption.IGNORE_CASE
        private val OPT_ID = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        private val RX_ARTICLE = Regex(
            """<article[^>]*class=["'][^"']*mv[^"']*["'][^>]*>(.*?)</article>""", OPT_ID
        )
        private val RX_HREF_ANY = Regex("""href=["']([^"']+)["']""", OPT_I)
        private val RX_HREF_MEDIA = Regex(
            """href=["'](/(?:pelicula|serie|anime)/[^"']+)["']""", OPT_I
        )
        private val RX_IMG_ANY = Regex(
            """<img[^>]+(?:src|data-src)=["']([^"']+)["']""", OPT_I
        )
        private val RX_IMG_TAG = Regex("""<img[^>]+src=["']([^"']+)["'][^>]*>""", OPT_I)
        private val RX_ALT = Regex("""alt=["']([^"']+)["']""", OPT_I)
        private val RX_TITLE_ATTR = Regex("""title=["']([^"']+)["']""", OPT_I)
        private val RX_HEADING = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", OPT_ID)
        private val RX_TAGS_STRIP = Regex("""<[^>]+>""")
        private val RX_TAGS_CLEAN = Regex("""<[^>]*>""")
        private val RX_SPACES = Regex("""\s+""")
        private val RX_TITLE_VER = Regex("""^Ver\s+""", OPT_I)
        private val RX_TITLE_ONLINE_1 = Regex("""\s+-\s+Ver online.*$""", OPT_I)
        private val RX_TITLE_ONLINE_2 = Regex("""\s+\(\d{4}\)\s+online.*$""", OPT_I)

        private val RX_H1 = Regex("""<h1[^>]*>(.*?)</h1>""", OPT_ID)
        private val RX_OG_IMAGE = Regex(
            """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", OPT_I
        )
        private val RX_YEAR = Regex("""\b(19|20)\d{2}\b""")
        private val RX_META_DESC = Regex(
            """<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']+)["']""",
            OPT_I
        )
        private val RX_H2_DESC = Regex(
            """<h2[^>]*class=["'][^"']*description[^"']*["'][^>]*>(.*?)</h2>""", OPT_ID
        )
        private val RX_EPISODE = Regex(
            """href\s*=\s*["']([^"']*/temporada/(\d+)/capitulo/(\d+)[^"']*)["']""", OPT_I
        )

        private val RX_CHANGE_SERVER = Regex("""changeServer\(\s*['"]([^'"]+)['"]""", OPT_I)
        private val RX_VIDURL_IFRAME = Regex(
            """<iframe[^>]+src=["']([^"']*/vidurl/[^"']+)["']""", OPT_I
        )

        // Vidhide
        private val RX_PACKED = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)\)\)"""
        )
        private val RX_WORD = Regex("""\b\w+\b""")
        private val RX_HLS_KEYS = listOf("hls4", "hls2", "hls3").map {
            Regex("""["']$it["']\s*:\s*["']([^"']+)["']""")
        }

        // Embed69
        private val RX_POW_CHALLENGE = Regex("""POW_CHALLENGE\s*=\s*['"]([^'"]+)['"]""", OPT_I)
        private val RX_POW_SALT = Regex("""POW_SALT\s*=\s*['"]([^'"]+)['"]""", OPT_I)
        private val RX_POW_DIFFICULTY = Regex("""POW_DIFFICULTY\s*=\s*(\d+)""", OPT_I)
        private val RX_DATA_LINK = Regex("""let\s+dataLink\s*=\s*(\[[\s\S]*?\]);""", OPT_I)

        // Voe
        private val RX_VOE_REDIRECT = Regex(
            """window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]"""
        )
        private val RX_VOE_CONFIG = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']"""
        )
        private val RX_VOE_CONFIG_STRICT = Regex(
            """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>"""
        )
        private val RX_VOE_CSRF = Regex("""name=["']_token["'][^>]+value=["']([^"']+)["']""")
        private val RX_VOE_CHALLENGE_URL = Regex("""<altcha-widget[^>]+challenge=["']([^"']+)["']""")
        private val RX_VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")

        private val VOE_SEPARATORS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    }

    private val voePowMutex = Mutex()

    // Las lambdas son inline: con DEBUG = false el mensaje ni se construye.
    private inline fun logd(message: () -> String) {
        if (DEBUG) Log.d(TAG, message())
    }

    private fun Throwable.rethrowIfCancelled() {
        if (this is kotlinx.coroutines.CancellationException) throw this
    }

    // ======================================================================
    //  HOME
    // ======================================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = when (request.name) {
            "Películas" -> "$mainUrl/peliculas"
            "Series" -> "$mainUrl/series"
            "Animes" -> "$mainUrl/animes"
            else -> mainUrl
        }

        val url = if (page > 1) "$baseUrl?page=$page" else baseUrl

        logd { "HOME request='${request.name}' page=$page url=$url" }

        val response = app.get(url, headers = mapOf("Referer" to mainUrl))

        if (!response.isSuccessful) {
            return newHomePageResponse(request.name, emptyList())
        }

        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (match in RX_ARTICLE.findAll(response.text)) {
            val article = match.groupValues[1]

            val href = RX_HREF_ANY.find(article)?.groupValues?.getOrNull(1) ?: continue
            val absolute = absoluteUrl(href)

            val type = when {
                absolute.contains("/anime/") -> TvType.Anime
                absolute.contains("/serie/") -> TvType.TvSeries
                absolute.contains("/pelicula/") -> TvType.Movie
                else -> continue
            }

            if (!seen.add(absolute)) continue

            val poster = RX_IMG_ANY.find(article)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { absoluteUrl(it) }

            val title = RX_HEADING.find(article)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(RX_TAGS_STRIP, "")
                ?.trim()
                ?: continue

            results.add(searchItem(title, absolute, type, poster, true))
        }

        logd { "HOME results=${results.size}" }

        return newHomePageResponse(request.name, results)
    }

    private fun searchItem(
        title: String,
        url: String,
        type: TvType,
        poster: String?,
        fix: Boolean
    ): SearchResponse {
        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime, fix) {
                this.posterUrl = poster
            }

            TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie, fix) {
                this.posterUrl = poster
            }

            else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix) {
                this.posterUrl = poster
            }
        }
    }

    // ======================================================================
    //  SEARCH
    // ======================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?s=$encoded"

        logd { "SEARCH url=$url" }

        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl))

            if (!response.isSuccessful) {
                return emptyList()
            }

            val results = ArrayList<SearchResponse>()
            val seen = HashSet<String>()

            for (match in RX_ARTICLE.findAll(response.text)) {
                val article = match.groupValues[1]

                val href = RX_HREF_MEDIA.find(article)?.groupValues?.getOrNull(1) ?: continue

                if (!seen.add(href)) continue

                val image = RX_IMG_TAG.find(article)

                val poster = image
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }

                val alt = image?.value?.let {
                    RX_ALT.find(it)?.groupValues?.getOrNull(1)
                }

                val titleAttr = image?.value?.let {
                    RX_TITLE_ATTR.find(it)?.groupValues?.getOrNull(1)
                }

                val title = cleanHtml(titleAttr ?: alt ?: "")
                    .replace(RX_TITLE_VER, "")
                    .replace(RX_TITLE_ONLINE_1, "")
                    .replace(RX_TITLE_ONLINE_2, "")
                    .trim()

                if (title.isBlank()) continue

                val type = when {
                    href.startsWith("/anime/") -> TvType.Anime
                    href.startsWith("/serie/") -> TvType.TvSeries
                    else -> TvType.Movie
                }

                results.add(searchItem(title, absoluteUrl(href), type, poster, false))
            }

            logd { "SEARCH resultados=${results.size}" }

            results
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "SEARCH ERROR", e)
            emptyList()
        }
    }

    // ======================================================================
    //  LOAD
    // ======================================================================

    override suspend fun load(url: String): LoadResponse? {
        logd { "LOAD url=$url" }

        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl))

            if (!response.isSuccessful) {
                Log.e(TAG, "LOAD HTTP ERROR: ${response.code}")
                return null
            }

            val html = response.text

            val title = RX_H1.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { cleanHtml(it).trim() }
                ?: "Megadede"

            val poster = RX_OG_IMAGE.find(html)?.groupValues?.getOrNull(1)

            val year = RX_YEAR.find(html)?.value?.toIntOrNull()

            val description = RX_META_DESC.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { cleanHtml(it).trim() }
                ?.takeIf { it.isNotBlank() }
                ?: RX_H2_DESC.find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { cleanHtml(it).trim() }

            val isAnime = url.contains("/anime/")

            if (url.contains("/serie/") || isAnime) {
                val matches = RX_EPISODE.findAll(html).toList()

                logd { "LOAD matches episodios=${matches.size}" }

                // Se decide con el total crudo (igual que antes) para no cambiar
                // qué series bajan metadata de episodios.
                val fetchMeta = FETCH_EPISODE_METADATA && matches.size <= EPISODE_METADATA_MAX

                val episodes = coroutineScope {
                    matches
                        // Antes se bajaba la página de cada episodio repetido y
                        // recién después se descartaban los duplicados.
                        .distinctBy { absoluteUrl(it.groupValues[1]) }
                        .map { match ->
                            async {
                                val season = match.groupValues[2].toIntOrNull()
                                    ?: return@async null
                                val episode = match.groupValues[3].toIntOrNull()
                                    ?: return@async null
                                val episodeUrl = absoluteUrl(match.groupValues[1])

                                var episodePoster: String? = poster
                                var episodeDescription: String? = null

                                if (fetchMeta) {
                                    try {
                                        val episodeHtml = app.get(episodeUrl).text

                                        episodePoster = RX_OG_IMAGE.find(episodeHtml)
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?: poster

                                        episodeDescription = RX_META_DESC.find(episodeHtml)
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?.let { cleanHtml(it).trim() }
                                            ?.takeIf {
                                                it.isNotBlank() &&
                                                    !it.startsWith(
                                                        "No se encontró una sinopsis",
                                                        ignoreCase = true
                                                    )
                                            }
                                    } catch (e: Exception) {
                                        e.rethrowIfCancelled()
                                        logd { "LOAD error metadata S${season}E$episode: ${e.message}" }
                                    }
                                }

                                newEpisode(episodeUrl) {
                                    this.name = "Episodio $episode"
                                    this.season = season
                                    this.episode = episode
                                    this.posterUrl = episodePoster ?: poster
                                    this.description = episodeDescription
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                }

                val sortedEpisodes = episodes.sortedWith(
                    compareBy<Episode> { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )

                logd { "LOAD episodios finales=${sortedEpisodes.size}" }

                return newTvSeriesLoadResponse(
                    title,
                    url,
                    if (isAnime) TvType.Anime else TvType.TvSeries,
                    sortedEpisodes
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                }
            }

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "LOAD ERROR", e)
            null
        }
    }

    // ======================================================================
    //  LOAD LINKS
    // ======================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data)

        logd { "LINKS pageUrl=$pageUrl" }

        return try {
            val response = app.get(pageUrl, headers = mapOf("Referer" to mainUrl))

            if (!response.isSuccessful) {
                return false
            }

            val html = response.text

            // LinkedHashSet: mantiene el orden y descarta repetidos.
            val servers = LinkedHashSet<String>()

            for (m in RX_CHANGE_SERVER.findAll(html)) {
                servers.add(absoluteUrl(m.groupValues[1]))
            }

            findVidUrl(html)?.let { servers.add(it) }

            logd { "LINKS total servidores: ${servers.size}" }

            val found = AtomicBoolean(false)

            // Embed69 y los servidores directos ahora corren EN PARALELO.
            // Antes los directos esperaban a que terminara el PoW de Embed69.
            coroutineScope {
                for (server in servers) {
                    if (server.contains("/vidurl/", ignoreCase = true)) {
                        launch { processEmbed69(server, subtitleCallback, callback, found) }
                    } else {
                        launch {
                            processDirectServer(server, pageUrl, subtitleCallback, callback, found)
                        }
                    }
                }
            }

            logd { "LINKS resultado final: found=${found.get()}" }

            found.get()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "LINKS ERROR", e)
            false
        }
    }

    private suspend fun processEmbed69(
        vidUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {
        val links = try {
            extractEmbed69Links(vidUrl)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            // Si Embed69 falla ya no se cae todo loadLinks.
            Log.e(TAG, "LINKS Embed69 ERROR: $vidUrl", e)
            return
        }

        logd { "LINKS Embed69 devolvio ${links.size} links" }

        coroutineScope {
            for ((serverName, language, realUrl) in links) {
                launch {
                    processEmbedLink(
                        serverName,
                        language,
                        realUrl,
                        vidUrl,
                        subtitleCallback,
                        callback,
                        found
                    )
                }
            }
        }
    }

    private suspend fun processEmbedLink(
        serverName: String,
        language: String,
        realUrl: String,
        serverUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {
        if (realUrl.isBlank()) {
            logd { "LINKS $serverName URL vacía" }
            return
        }

        val languageLabel = when (language.uppercase()) {
            "LAT" -> "lat"
            "SUB" -> "sub"
            "ESP" -> "esp"
            else -> language.lowercase()
        }

        val displayName = when {
            serverName.equals("vidhide", true) -> "Vidhide"
            serverName.equals("streamwish", true) -> "Streamwish"
            serverName.equals("voe", true) -> "Voe"
            else -> serverName
        }

        val labeledName = "$displayName $languageLabel"

        logd { "LINKS Embed69 resultado: server=$serverName language=$language url=$realUrl" }

        if (serverName.equals("vidhide", true)) {
            try {
                val hlsUrl = withTimeoutOrNull(10000L) {
                    extractVidhideLink(realUrl)
                }

                if (!hlsUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            "Vidhide",
                            labeledName,
                            hlsUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            referer = realUrl
                        }
                    )

                    found.set(true)

                    logd { "LINK EMITIDO: Vidhide $labeledName url=$hlsUrl" }
                } else {
                    logd { "LINKS Vidhide: no se obtuvo HLS" }
                }
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                Log.e(TAG, "LINKS Vidhide ERROR", e)
            }

            return
        }

        if (serverName.equals("voe", true)) {
            try {
                val hlsUrl = withTimeoutOrNull(45000L) {
                    withContext(Dispatchers.Default) {
                        extractVoeLink(realUrl)
                    }
                }

                if (!hlsUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            "Voe",
                            labeledName,
                            hlsUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            referer = realUrl
                        }
                    )

                    found.set(true)

                    logd { "LINK EMITIDO: Voe $labeledName url=$hlsUrl" }
                } else {
                    logd { "LINKS Voe: no se obtuvo HLS" }
                }
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                Log.e(TAG, "LINKS Voe ERROR", e)
            }

            return
        }

        val isStreamwish = displayName.equals("Streamwish", ignoreCase = true)

        try {
            val completed = withTimeoutOrNull(20000L) {
                val streamwishLinks = java.util.Collections.synchronizedList(
                    mutableListOf<ExtractorLink>()
                )

                val extractorCallback: (ExtractorLink) -> Unit = { link ->
                    if (isStreamwish) {
                        streamwishLinks.add(link)
                    } else {
                        logd { "LINK EMITIDO: $displayName $labeledName url=${link.url}" }

                        callback(
                            ExtractorLink(
                                source = displayName,
                                name = labeledName,
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type
                            )
                        )
                        found.set(true)
                    }
                }

                loadExtractor(
                    realUrl,
                    serverUrl,
                    subtitleCallback,
                    extractorCallback
                )

                if (isStreamwish) {
                    if (streamwishLinks.isEmpty()) {
                        callback(newExtractorLink(displayName, labeledName, realUrl))
                        found.set(true)
                    } else {
                        val snapshot = streamwishLinks.toList()

                        val masterLink = snapshot.firstOrNull { link ->
                            link.url.substringBefore('?')
                                .substringAfterLast('/')
                                .let {
                                    it.equals("master.m3u8", ignoreCase = true) ||
                                        it.equals("master.txt", ignoreCase = true)
                                }
                        }

                        val linksToEmit = if (masterLink != null) listOf(masterLink) else snapshot

                        for (link in linksToEmit) {
                            callback(
                                ExtractorLink(
                                    source = displayName,
                                    name = labeledName,
                                    url = link.url,
                                    referer = link.referer,
                                    quality = link.quality,
                                    type = link.type
                                )
                            )
                        }

                        found.set(true)
                    }
                }

                true
            }

            if (completed == null) {
                logd { "LINKS EXTRACTOR TIMEOUT: name=$displayName limite=20000ms" }
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "LINKS loadExtractor ERROR: name=$displayName url=$realUrl", e)

            try {
                callback(newExtractorLink(displayName, labeledName, realUrl))
                found.set(true)
            } catch (fallbackError: Exception) {
                fallbackError.rethrowIfCancelled()
                Log.e(TAG, "LINKS fallback ERROR: name=$displayName", fallbackError)
            }
        }
    }

    private suspend fun processDirectServer(
        serverUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {
        logd { "LINKS servidor directo: $serverUrl" }

        try {
            val extractorCallback: (ExtractorLink) -> Unit = { link ->
                logd { "LINK EMITIDO: ${link.source} ${link.name} url=${link.url}" }
                callback(link)
                found.set(true)
            }

            val completed = withTimeoutOrNull(20000L) {
                loadExtractor(
                    serverUrl,
                    pageUrl,
                    subtitleCallback,
                    extractorCallback
                )
                true
            }

            if (completed == null) {
                logd { "LINKS EXTRACTOR TIMEOUT servidor directo: $serverUrl limite=20000ms" }
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "LINKS servidor directo ERROR: $serverUrl", e)
        }
    }

    private fun findVidUrl(html: String): String? {
        return RX_VIDURL_IFRAME.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { absoluteUrl(it) }
    }

    // ======================================================================
    //  VIDHIDE
    // ======================================================================

    private suspend fun extractVidhideLink(embedUrl: String): String? {
        return try {
            val response = app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            )

            if (!response.isSuccessful) {
                logd { "Vidhide: HTTP ${response.code}" }
                return null
            }

            val match = RX_PACKED.find(response.text)

            if (match == null) {
                logd { "Vidhide: Packer no encontrado" }
                return null
            }

            val base = match.groupValues[2].toInt()
            val dictionary = match.groupValues[4].split("|")

            val unpacked = unpackPacker(match.groupValues[1], base, dictionary)

            // Prioridad: hls4 -> hls2 -> hls3
            val hlsUrl = RX_HLS_KEYS
                .asSequence()
                .mapNotNull { it.find(unpacked)?.groupValues?.getOrNull(1) }
                .firstOrNull { it.isNotBlank() }

            if (hlsUrl == null) {
                logd { "Vidhide: no se encontró hls4/hls2/hls3" }
                return null
            }

            if (hlsUrl.startsWith("http")) hlsUrl else "https://morencius.com$hlsUrl"
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "Vidhide ERROR", e)
            null
        }
    }

    /**
     * Desempaquetado p.a.c.k.e.r en UNA sola pasada.
     * Antes se compilaba una Regex por cada palabra del diccionario y se recorría
     * todo el texto cientos de veces.
     */
    private fun unpackPacker(packed: String, base: Int, dictionary: List<String>): String {
        return RX_WORD.replace(packed) { m ->
            val token = m.value

            if (token.length > 1 && token[0] == '0') {
                return@replace token
            }

            var value = 0

            for (ch in token) {
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'z' -> ch - 'a' + 10
                    else -> -1
                }

                if (digit < 0 || digit >= base) return@replace token

                value = value * base + digit

                if (value >= dictionary.size) return@replace token
            }

            val word = dictionary[value]

            if (word.isEmpty()) token else word
        }
    }

    // ======================================================================
    //  EMBED69
    // ======================================================================

    private suspend fun extractEmbed69Links(
        vidUrl: String
    ): List<Triple<String, String, String>> {
        val response = app.get(vidUrl, headers = mapOf("Referer" to mainUrl))

        if (!response.isSuccessful) {
            return emptyList()
        }

        val html = response.text

        val challenge = RX_POW_CHALLENGE.find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val salt = RX_POW_SALT.find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val difficulty = RX_POW_DIFFICULTY.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 3

        val dataLinkText = RX_DATA_LINK.find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val nonce = withContext(Dispatchers.Default) {
            solvePow(challenge, difficulty)
        }

        logd { "Embed69 PoW nonce=$nonce difficulty=$difficulty" }

        val key = sha256Bytes("$challenge$nonce$salt")

        val array = JSONArray(dataLinkText)
        val results = ArrayList<Triple<String, String, String>>()
        val seenProviders = HashSet<String>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val language = item.optString("video_language", "UNK").uppercase()
            val embeds = item.optJSONArray("sortedEmbeds") ?: continue

            for (j in 0 until embeds.length()) {
                val embed = embeds.getJSONObject(j)

                val serverName = embed.optString("servername")
                val encrypted = embed.optString("link")

                if (serverName.isBlank() || encrypted.isBlank()) continue

                if (!seenProviders.add("${serverName.lowercase()}|$language")) continue

                // Un link mal cifrado ya no tira abajo todos los demás.
                val decrypted = try {
                    decryptAes(encrypted, key)
                } catch (e: Exception) {
                    logd { "Embed69 no se pudo descifrar $serverName/$language: ${e.message}" }
                    continue
                }

                results.add(Triple(serverName, language, decrypted))
            }
        }

        return results
    }

    /**
     * Antes: sha256 devolvía un hex con String.format por cada byte (x32 por intento)
     * y se comparaba con startsWith. Ahora se reutiliza el MessageDigest y se
     * comparan los nibbles directamente sobre los bytes. Mismo resultado, muchísimo
     * más rápido.
     */
    private suspend fun solvePow(
        challenge: String,
        difficulty: Int
    ): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val challengeBytes = challenge.toByteArray(StandardCharsets.UTF_8)

        var nonce = 0L

        while (true) {
            if ((nonce and 0x3FFL) == 0L) {
                currentCoroutineContext().ensureActive()
            }

            md.update(challengeBytes)
            md.update(nonce.toString().toByteArray(StandardCharsets.US_ASCII))

            if (startsWithZeroNibbles(md.digest(), difficulty)) {
                return nonce
            }

            nonce++
        }
    }

    private fun startsWithZeroNibbles(digest: ByteArray, nibbles: Int): Boolean {
        if (nibbles > digest.size * 2) return false

        for (i in 0 until nibbles) {
            val b = digest[i shr 1].toInt()
            val nibble = if ((i and 1) == 0) (b ushr 4) and 0x0F else b and 0x0F

            if (nibble != 0) return false
        }

        return true
    }

    private fun decryptAes(
        encrypted: String,
        key: ByteArray
    ): String {
        val data = Base64.decode(encrypted, Base64.DEFAULT)

        val iv = data.copyOfRange(0, 16)
        val ciphertext = data.copyOfRange(16, data.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )

        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun sha256Bytes(value: String): ByteArray {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
    }

    // ======================================================================
    //  VOE
    // ======================================================================

    private fun jsonStringField(json: String, key: String): String? {
        return Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
    }

    private fun jsonIntField(json: String, key: String): Int? {
        return Regex(""""$key"\s*:\s*(\d+)""").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private suspend fun extractVoeLink(embedUrl: String): String? {
        return try {
            val uaHeaders = mapOf("User-Agent" to USER_AGENT)

            val firstResponse = app.get(embedUrl, headers = uaHeaders)

            if (!firstResponse.isSuccessful) {
                logd { "Voe: HTTP inicial ${firstResponse.code}" }
                return null
            }

            // Voe redirige a uno de sus dominios CDN.
            val redirectUrl = RX_VOE_REDIRECT.find(firstResponse.text)
                ?.groupValues
                ?.getOrNull(1)

            val realUrl = redirectUrl ?: embedUrl

            val pageResponse = if (realUrl != embedUrl) {
                app.get(realUrl, headers = uaHeaders)
            } else {
                firstResponse
            }

            var html = pageResponse.text

            // Si ya tenemos el JSON real, no hace falta ALTCHA.
            var encodedConfig = RX_VOE_CONFIG.find(html)?.groupValues?.getOrNull(1)

            if (encodedConfig.isNullOrBlank()) {
                logd { "Voe: ALTCHA requerido" }

                val csrf = RX_VOE_CSRF.find(html)?.groupValues?.getOrNull(1)
                val challengeUrl = RX_VOE_CHALLENGE_URL.find(html)?.groupValues?.getOrNull(1)

                if (csrf.isNullOrBlank() || challengeUrl.isNullOrBlank()) {
                    logd { "Voe: no se encontró CSRF o challenge" }
                    return null
                }

                val challengeResponse = app.get(
                    challengeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to realUrl
                    )
                )

                if (!challengeResponse.isSuccessful) {
                    logd { "Voe ALTCHA challenge HTTP ${challengeResponse.code}" }
                    return null
                }

                val challengeJson = challengeResponse.text

                val algorithm = jsonStringField(challengeJson, "algorithm")?.replace("\\/", "/")
                val cost = jsonIntField(challengeJson, "cost")
                val keyLength = jsonIntField(challengeJson, "keyLength")
                val keyPrefix = jsonStringField(challengeJson, "keyPrefix")
                val nonce = jsonStringField(challengeJson, "nonce")
                val salt = jsonStringField(challengeJson, "salt")

                if (
                    algorithm.isNullOrBlank() ||
                    cost == null ||
                    keyLength == null ||
                    keyPrefix.isNullOrBlank() ||
                    nonce.isNullOrBlank() ||
                    salt.isNullOrBlank()
                ) {
                    logd { "Voe ALTCHA: parámetros incompletos" }
                    return null
                }

                if (!algorithm.equals("PBKDF2/SHA-256", ignoreCase = true)) {
                    logd { "Voe ALTCHA: algoritmo recibido=${algorithm.take(80)}" }
                    return null
                }

                var solvedCounter = -1
                var solvedKey = ""
                var powElapsedMs = 0.0

                val saltBytes = hexToBytes(salt)
                val nonceBytes = hexToBytes(nonce)
                val powMac = Mac.getInstance("HmacSHA256")
                val offset = nonceBytes.size
                val passwordBytes = ByteArray(offset + 4)
                System.arraycopy(nonceBytes, 0, passwordBytes, 0, offset)

                voePowMutex.withLock {
                    val powStart = System.nanoTime()

                    for (counter in 0 until 1_000_000) {
                        if (counter % 100 == 0) {
                            currentCoroutineContext().ensureActive()
                        }

                        passwordBytes[offset] = (counter ushr 24).toByte()
                        passwordBytes[offset + 1] = (counter ushr 16).toByte()
                        passwordBytes[offset + 2] = (counter ushr 8).toByte()
                        passwordBytes[offset + 3] = counter.toByte()

                        val derived = pbkdf2Sha256(
                            passwordBytes,
                            saltBytes,
                            cost,
                            keyLength,
                            powMac
                        )

                        if (matchesHexPrefix(derived, keyPrefix)) {
                            solvedCounter = counter
                            solvedKey = bytesToHex(derived)
                            break
                        }
                    }

                    powElapsedMs = (System.nanoTime() - powStart) / 1_000_000.0
                }

                if (solvedCounter < 0) {
                    logd { "Voe ALTCHA: PoW no resuelto" }
                    return null
                }

                logd { "Voe ALTCHA resuelto: counter=$solvedCounter time=${powElapsedMs}ms" }

                // ALTCHA v2 espera el challenge como objeto JSON, no como string.
                val altchaPayload =
                    """{"challenge":$challengeJson,"solution":{"counter":$solvedCounter,"derivedKey":"$solvedKey","time":$powElapsedMs}}"""

                val payloadB64 = java.util.Base64
                    .getEncoder()
                    .encodeToString(altchaPayload.toByteArray(Charsets.UTF_8))

                // Solo las cookies de la página del formulario (sesión del CSRF).
                val voeCookies = pageResponse.cookies.entries
                    .joinToString("; ") { (k, v) -> "$k=$v" }

                val postResponse = app.post(
                    realUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
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
                    logd { "Voe ALTCHA POST HTTP ${postResponse.code}" }
                    return null
                }

                html = postResponse.text

                encodedConfig = RX_VOE_CONFIG_STRICT.find(html)?.groupValues?.getOrNull(1)
            }

            if (encodedConfig.isNullOrBlank()) {
                logd { "Voe: config JSON no encontrada" }
                return null
            }

            val decodedConfig = decodeVoeConfig(encodedConfig)

            if (decodedConfig.isNullOrBlank()) {
                logd { "Voe: no se pudo decodificar config" }
                return null
            }

            val source = RX_VOE_SOURCE.find(decodedConfig)?.groupValues?.getOrNull(1)

            if (source.isNullOrBlank()) {
                logd { "Voe: source HLS no encontrada" }
                return null
            }

            val result = source
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            logd { "Voe HLS encontrado: $result" }

            result
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.e(TAG, "Voe ERROR", e)
            null
        }
    }

    private fun decodeVoeConfig(encoded: String): String? {
        return try {
            // 1. ROT13
            val rot = StringBuilder(encoded.length)

            for (char in encoded) {
                rot.append(
                    when (char) {
                        in 'A'..'Z' -> ((char.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                        in 'a'..'z' -> ((char.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                        else -> char
                    }
                )
            }

            var value = rot.toString()

            // 2. Separadores -> _
            for (separator in VOE_SEPARATORS) {
                value = value.replace(separator, "_")
            }

            // 3. Eliminar _
            value = value.replace("_", "")

            // 4. Base64
            val bytes = java.util.Base64.getDecoder().decode(value)

            // 5. Restar 3 a cada byte (in-place)
            for (i in bytes.indices) {
                bytes[i] = (bytes[i].toInt() - 3).toByte()
            }

            // 6. Invertir
            bytes.reverse()

            // 7. Base64 otra vez
            val decoded = java.util.Base64
                .getDecoder()
                .decode(String(bytes, Charsets.UTF_8))

            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Voe decode ERROR", e)
            null
        }
    }

    /**
     * PBKDF2-HMAC-SHA256. Este es el loop más pesado de Voe (cost x hasta 1M intentos).
     * Optimización: la salida de cada HMAC se escribe sobre el mismo buffer
     * (mac.doFinal(buffer, 0)) en vez de crear un ByteArray nuevo por iteración.
     */
    private fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
        mac: Mac
    ): ByteArray {
        mac.init(SecretKeySpec(password, "HmacSHA256"))

        val blockCount = (keyLength + mac.macLength - 1) / mac.macLength
        val output = ByteArray(blockCount * mac.macLength)
        var outputOffset = 0

        for (block in 1..blockCount) {
            val blockSalt = salt + byteArrayOf(
                ((block ushr 24) and 0xff).toByte(),
                ((block ushr 16) and 0xff).toByte(),
                ((block ushr 8) and 0xff).toByte(),
                (block and 0xff).toByte()
            )

            val u = mac.doFinal(blockSalt)
            val t = u.copyOf()

            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)

                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            System.arraycopy(t, 0, output, outputOffset, t.size)
            outputOffset += t.size
        }

        return output.copyOf(keyLength)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        var index = 0

        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            result[index++] = digits[value ushr 4]
            result[index++] = digits[value and 0x0f]
        }

        return String(result)
    }

    private fun matchesHexPrefix(bytes: ByteArray, prefix: String): Boolean {
        if (prefix.length > bytes.size * 2) return false

        for (i in prefix.indices) {
            val value = bytes[i / 2].toInt() and 0xff
            val nibble = if (i % 2 == 0) value ushr 4 else value and 0x0f

            if (Character.digit(prefix[i], 16) != nibble) return false
        }

        return true
    }

    private fun hexToBytes(value: String): ByteArray {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    // ======================================================================
    //  UTILIDADES
    // ======================================================================

    private fun absoluteUrl(url: String): String {
        val value = url.trim()

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }

        return if (value.startsWith("/")) "$mainUrl$value" else "$mainUrl/$value"
    }

    private fun cleanHtml(value: String): String {
        return value
            .replace(RX_TAGS_CLEAN, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(RX_SPACES, " ")
            .trim()
    }
}
