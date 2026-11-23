package com.monoschinos

import android.util.Base64 as AndroidBase64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/* ====================================================================
 * Config
 * ==================================================================== */

private const val DEBUG = true
private const val TAG = "MonosChinos"

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val BYSE_UA =
    "Mozilla/5.0 (Linux; Android 6.0.1; SM-G5510 Build/MMB29M) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/59.0.3071.125 Mobile Safari/537.36 SamsungBrowser/7.2"

private const val VOE_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"

private const val EXTRACTOR_TIMEOUT_MS = 9_000L
private const val SOURCE_TIMEOUT_MS = 35_000L
private const val VOE_TIMEOUT_NS = 25_000_000_000L
private val VOE_HEX_CHARS = "0123456789abcdef".toCharArray()

private val OPT_I = RegexOption.IGNORE_CASE
private val OPT_ID = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

private val RX_ARTICLE = Regex("""<article[^>]*>([\s\S]*?)</article>""", OPT_ID)
private val RX_HREF_ANIME = Regex("""href=["']([^"']*/anime/[^"']+)["']""", OPT_I)
private val RX_HREF_ANY = Regex("""href=["']([^"']+)["']""", OPT_I)
private val RX_DATA_SRC = Regex("""data-src=["']([^"']+)["']""", OPT_I)
private val RX_IMG_SRC = Regex("""<img[^>]+src=["']([^"']+)["']""", OPT_I)
private val RX_ALT = Regex("""alt=["']([^"']+)["']""", OPT_I)
private val RX_HEADING = Regex("""<h[1-6][^>]*>([\s\S]*?)</h[1-6]>""", OPT_ID)
private val RX_TAGS = Regex("""<[^>]+>""")
private val RX_SPACES = Regex("""\s+""")
private val RX_H1 = Regex("""<h1[^>]*>([\s\S]*?)</h1>""", OPT_ID)
private val RX_OG_IMAGE = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", OPT_I)
private val RX_META_DESC = Regex(
    """<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']+)["']""",
    OPT_I
)
private val RX_PLOT_P = Regex("""<p[^>]+text-white/75[^>]*>([\s\S]*?)</p>""", OPT_ID)
private val RX_GENRE = Regex("""href=["'][^"']*/genero/[^"']+["'][^>]*>([^<]+)<""", OPT_I)
private val RX_AJAX = Regex("""data-ajax=["'][^"']*/ajax/ajax_pagination/(\d+)["']""", OPT_I)
private val RX_YEAR = Regex("""\b(?:19|20)\d{2}\b""")
private val RX_PLAYER = Regex("""data-player=["']([^"']+)["']""", OPT_I)
private val RX_DIRECT = Regex("""\.(?:m3u8|mp4|mkv|webm)(?:$|[?#])""", OPT_I)
private val RX_M3U8_URL = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""", OPT_I)
private val RX_M3U8_FILE = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", OPT_I)

private val RX_MEGA = Regex(
    """mega\.(?:nz|io)/(?:embed|file)/([^#?/]+)#([^?#/]+)""",
    OPT_I
)
private val RX_MEGA_ALT = Regex(
    """mega\.(?:nz|io)/[#!]?([^#!?/]+)[#!]([^#!?/]+)""",
    OPT_I
)

private val VOE_REDIRECT = Regex("""window\.location\.href\s*=\s*['"]([^'"]+/e/[^'"]+)['"]""")
private val VOE_CONFIG = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*(?:\[\s*)?["']([^"']+)["']""")
private val VOE_CONFIG_STRICT =
    Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>""")
private val VOE_TOKEN = Regex("""name=["']_token["'][^>]+value=["']([^"']+)["']""")
private val VOE_CHALLENGE = Regex("""<altcha-widget[^>]+challenge=["']([^"']+)["']""")
private val VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")

/* ====================================================================
 * Helpers
 * ==================================================================== */

private inline fun log(message: () -> String) {
    if (DEBUG) Log.d(TAG, message())
}

private fun decodeEntities(value: String): String =
    value
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#34;", "\"", ignoreCase = true)
        .replace("&#x22;", "\"", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&aacute;", "á", ignoreCase = true)
        .replace("&eacute;", "é", ignoreCase = true)
        .replace("&iacute;", "í", ignoreCase = true)
        .replace("&oacute;", "ó", ignoreCase = true)
        .replace("&uacute;", "ú", ignoreCase = true)
        .replace("&ntilde;", "ñ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

private fun cleanText(value: String): String =
    decodeEntities(RX_TAGS.replace(value, " "))
        .replace(RX_SPACES, " ")
        .trim()

private class LinkSink(private val callback: (ExtractorLink) -> Unit) {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()
    private val emitted = AtomicInteger()

    val count: Int get() = emitted.get()

    suspend fun emit(link: ExtractorLink): Boolean {
        if (!seen.add(link.url)) return false
        synchronized(lock) { callback(link) }
        emitted.incrementAndGet()
        log { "LINK ${link.name} q=${link.quality} ${link.url.take(140)}" }
        return true
    }
}

/* ====================================================================
 * Provider
 * ==================================================================== */

class MonosChinosProvider : MainAPI() {

    override var mainUrl = "https://monoschinos.st"
    override var name = "MonosChinos"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.AnimeMovie)
    override var lang = "es"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/emision" to "En emisión",
        "$mainUrl/animes" to "Catálogo",
        "$mainUrl/genero/accion" to "Acción",
        "$mainUrl/genero/drama" to "Drama",
        "$mainUrl/genero/comedia" to "Comedia",
        "$mainUrl/genero/ciencia-ficcion" to "Ciencia Ficción"
    )

    private val siteHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Referer" to "$mainUrl/"
        )

    private val ajaxHeaders: Map<String, String>
        get() = siteHeaders + mapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )

    private fun parseCards(html: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (match in RX_ARTICLE.findAll(html)) {
            val article = match.groupValues[1]
            val href = RX_HREF_ANIME.find(article)?.groupValues?.getOrNull(1)
                ?: RX_HREF_ANY.find(article)?.groupValues?.getOrNull(1)
                ?: continue
            if (!href.contains("/anime/", true)) continue

            val url = when {
                href.startsWith("http", true) -> href
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "https://monoschinos.st$href"
                else -> "https://monoschinos.st/$href"
            }
            if (!seen.add(url)) continue

            val title = RX_ALT.find(article)?.groupValues?.getOrNull(1)
                ?.let { cleanText(it) }
                ?.takeIf { it.isNotBlank() && !it.equals("anime", true) }
                ?: RX_HEADING.find(article)?.groupValues?.get(1)?.let { cleanText(it) }
                ?: continue
            if (title.isBlank()) continue

            val poster = (RX_DATA_SRC.find(article)?.groupValues?.getOrNull(1)
                ?: RX_IMG_SRC.find(article)?.groupValues?.getOrNull(1))
                ?.let { cleanText(it) }
                ?.takeIf { !it.contains("capblank", true) && !it.endsWith("anime.png", true) }

            out.add(
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        log { "parseCards -> ${out.size}" }
        return out
    }

    /* ------------------------------ HOME ------------------------------ */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.ifBlank { mainUrl }
        val url = if (page > 1) {
            if (base.contains("?")) "$base&p=$page" else "$base?p=$page"
        } else base

        val items = try {
            parseCards(app.get(url, headers = siteHeaders, timeout = 20).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "HOME ERROR ${request.name}: ${e.message}" }
            emptyList()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && items.size >= 20)
    }

    /* ----------------------------- SEARCH ----------------------------- */

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        return try {
            parseCards(app.get(url, headers = siteHeaders, timeout = 20).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "SEARCH ERROR '$query': ${e.message}" }
            emptyList()
        }
    }

    /* ------------------------------ LOAD ------------------------------ */

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absoluteUrl(url)
        return try {
            if (pageUrl.contains("/ver/", true)) loadEpisodePage(pageUrl)
            else loadSeriePage(pageUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "LOAD ERROR $pageUrl: ${e.message}" }
            null
        }
    }

    private suspend fun loadSeriePage(pageUrl: String): LoadResponse? {
        val html = app.get(pageUrl, headers = siteHeaders, timeout = 20).text

        val title = RX_H1.find(html)?.groupValues?.get(1)?.let { cleanText(it) }
            ?: pageUrl.substringAfterLast("/").replace('-', ' ')
            ?: return null

        val poster = RX_OG_IMAGE.find(html)?.groupValues?.getOrNull(1)
            ?: RX_DATA_SRC.find(html)?.groupValues?.getOrNull(1)

        val plot = (RX_META_DESC.find(html)?.groupValues?.getOrNull(1)
            ?: RX_PLOT_P.find(html)?.groupValues?.getOrNull(1))
            ?.let { cleanText(it) }
            ?.replace(Regex("""\s*Mira todos los episodios.*$""", OPT_I), "")
            ?.trim()

        val year = RX_YEAR.find(html)?.value?.toIntOrNull()
        val tags = RX_GENRE.findAll(html).map { cleanText(it.groupValues[1]) }.toSet().filter { it.isNotBlank() }

        val episodes = ArrayList<Episode>()
        val id = RX_AJAX.find(html)?.groupValues?.getOrNull(1)
        if (!id.isNullOrBlank()) {
            try {
                val info = JSONObject(app.get("$mainUrl/ajax/ajax_pagination/$id", headers = ajaxHeaders, timeout = 15).text)
                val paginateUrl = info.optString("paginate_url").replace("\\/", "/")
                if (paginateUrl.isNotBlank()) {
                    val capsJson = app.get(paginateUrl, headers = ajaxHeaders, timeout = 15).text
                    val caps = JSONObject(capsJson).optJSONArray("caps")
                    if (caps != null) {
                        for (i in 0 until caps.length()) {
                            val cap = caps.optJSONObject(i) ?: continue
                            val number = cap.optInt("episodio", 0)
                            val epUrl = cap.optString("url").replace("\\/", "/")
                            if (number <= 0 || epUrl.isBlank()) continue
                            episodes.add(
                                newEpisode(absoluteUrl(epUrl)) {
                                    this.name = "Episodio $number"
                                    this.episode = number
                                    this.season = 1
                                }
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log { "EPISODES AJAX ERROR id=$id: ${e.message}" }
            }
        }

        if (episodes.isEmpty()) {
            val first = RX_HREF_ANY.find(html)?.groupValues?.getOrNull(1)
            if (!first.isNullOrBlank() && first.contains("/ver/", true)) {
                val num = Regex("""-episodio-(\d+)""", OPT_I).find(first)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                episodes.add(
                    newEpisode(absoluteUrl(first)) {
                        this.name = "Episodio $num"
                        this.episode = num
                        this.season = 1
                    }
                )
            }
        }

        log { "LOAD $title episodes=${episodes.size} poster=$poster" }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun loadEpisodePage(pageUrl: String): LoadResponse? {
        val html = app.get(pageUrl, headers = siteHeaders, timeout = 20).text
        val animeUrl = RX_HREF_ANIME.find(html)?.groupValues?.get(1)?.let { absoluteUrl(it) } ?: mainUrl
        val number = Regex("""-episodio-(\d+)""", OPT_I).find(pageUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""Capítulo\s*(\d+)""", OPT_I).find(cleanText(html))?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 1
        val title = RX_H1.find(html)?.groupValues?.get(1)?.let { cleanText(it) }
            ?: "Episodio $number"

        val episode = newEpisode(pageUrl) {
            this.name = "Episodio $number"
            this.episode = number
            this.season = 1
        }
        return newTvSeriesLoadResponse(title, animeUrl, TvType.Anime, listOf(episode)) {
            this.plot = RX_META_DESC.find(html)?.groupValues?.getOrNull(1)?.let { cleanText(it) }
        }
    }

    /* ---------------------------- LINKS ------------------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data)
        val html = try {
            app.get(pageUrl, headers = siteHeaders, timeout = 20).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "LOAD_LINKS page ERROR: ${e.message}" }
            return false
        }

        val embeds = RX_PLAYER.findAll(html)
            .map { it.groupValues[1].trim() }
            .distinct()
            .mapNotNull { encoded ->
                try {
                    val decoded = String(AndroidBase64.decode(encoded, AndroidBase64.DEFAULT), Charsets.UTF_8)
                    decodeEntities(decoded).trim().takeIf { it.startsWith("http", true) }
                } catch (e: Exception) {
                    encoded.takeIf { it.startsWith("http", true) }
                }
            }
            .distinct()
            .toList()

        log { "LOAD_LINKS embeds=${embeds.size} page=$pageUrl" }
        if (embeds.isEmpty()) return false

        val sink = LinkSink(callback)
        coroutineScope {
            embeds.map { embed ->
                async {
                    try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            resolveSource(embed, pageUrl, sink, subtitleCallback)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log { "SOURCE ERROR $embed -> ${e.javaClass.simpleName}: ${e.message}" }
                    }
                }
            }.awaitAll()
        }

        log { "LOAD_LINKS final links=${sink.count}" }
        return sink.count > 0
    }

    private suspend fun resolveSource(
        url: String,
        pageUrl: String,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")

        when {
            url.contains("mega.nz", true) || url.contains("mega.io", true) -> {
                emitMega(url, sink)
            }

            host.contains("byse") || host == "n1mwq.org" || host.endsWith(".n1mwq.org") -> {
                val before = sink.count
                loadByseManual(url, "Filemoon", pageUrl) { link -> sink.emit(link); Unit }
                if (sink.count == before) {
                    runExtractor(url, listOf(pageUrl, "$mainUrl/"), sink, subtitleCallback)
                }
            }

            host.contains("voe") || host.contains("gam") -> {
                val before = sink.count
                loadVoe(url, "Voe") { link -> sink.emit(link); Unit }
                if (sink.count == before) {
                    runExtractor(url, listOf(pageUrl, "$mainUrl/"), sink, subtitleCallback)
                }
            }

            host.contains("lulu") -> {
                val before = sink.count
                loadLulu(url, "LuluStream") { link -> sink.emit(link); Unit }
                if (sink.count == before) {
                    runExtractor(url, listOf(pageUrl, "$mainUrl/"), sink, subtitleCallback)
                }
            }

            RX_DIRECT.containsMatchIn(url.substringBefore("?")) -> {
                val isHls = url.substringBefore("?").endsWith(".m3u8", true)
                sink.emit(
                    newExtractorLink(
                        source = name,
                        name = host.ifBlank { "Directo" },
                        url = url,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = pageUrl
                        quality = getQualityFromName(url)
                        headers = mapOf("User-Agent" to UA, "Referer" to pageUrl)
                    }
                )
            }

            else -> runExtractor(url, listOf(pageUrl, "$mainUrl/"), sink, subtitleCallback)
        }
    }

    private fun parseMega(url: String): Pair<String, String>? {
        RX_MEGA.find(url)?.let { return it.groupValues[1] to it.groupValues[2] }
        RX_MEGA_ALT.find(url)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }

    private suspend fun emitMega(url: String, sink: LinkSink) {
        val (handle, key) = parseMega(url) ?: run {
            log { "MEGA handle/key no encontrado en $url" }
            return
        }
        val localUrl = MegaLocalServer.start(handle, key)
        sink.emit(
            newExtractorLink(
                source = name,
                name = "MEGA",
                url = localUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                referer = "$mainUrl/"
                quality = getQualityFromName(url)
            }
        )
    }

    private suspend fun runExtractor(
        url: String,
        referers: List<String>,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val collected = ConcurrentLinkedQueue<ExtractorLink>()
        val finished = withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
            for (ref in referers) {
                try {
                    loadExtractor(url, ref, subtitleCallback) { link -> collected.add(link) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log { "loadExtractor ERROR ref=$ref url=$url -> ${e.message}" }
                }
                if (collected.isNotEmpty()) break
            }
            true
        }
        if (finished == null) log { "extractor TIMEOUT $url" }

        val hasVoeHls = collected.any {
            it.name.contains("voe", true) &&
                (it.url.contains(".m3u8", true) || it.type == ExtractorLinkType.M3U8)
        }
        for (link in collected) {
            if (hasVoeHls && link.name.contains("voe mp4", true)) continue
            sink.emit(link)
        }
    }

    private suspend fun detectHlsQuality(url: String, headers: Map<String, String>): Int =
        try {
            val manifest = withTimeoutOrNull(6_000L) {
                app.get(url, headers = headers, timeout = 6).text
            }.orEmpty()
            Regex("""RESOLUTION\s*=\s*\d+\s*x\s*(\d+)""", OPT_I)
                .findAll(manifest)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .filter { it in 144..4320 }
                .maxOrNull() ?: Qualities.Unknown.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Qualities.Unknown.value
        }

    /* ------------------------- BYSE / FILEMOON ------------------------ */

    private fun byseB64Decode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(normalized + "=".repeat((4 - normalized.length % 4) % 4))
    }

    private fun byseB64Encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun byseSha256(value: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun byseRotate(value: Int, count: Int): Int = Integer.rotateLeft(value, count)

    private fun byseMix(state: IntArray) {
        state[0] += state[1]
        state[3] = byseRotate(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = byseRotate(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = byseRotate(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = byseRotate(state[1] xor state[2], 7)
    }

    private fun byseWorkHash(input: String): IntArray {
        val state = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())
        val memory = IntArray(512)
        for (ch in input) {
            state[0] = byseRotate(state[0] + ch.code, 7)
            byseMix(state)
        }
        repeat(8) { byseMix(state) }
        for (i in 0 until 512) {
            byseMix(state)
            memory[i] = state[0] xor state[2]
        }
        repeat(2) {
            for (i in 0 until 512) {
                val a = memory[i] and 511
                var c = memory[i] + memory[a]
                c = byseRotate(c, 13)
                c = c xor (memory[(i + 1) and 511] * 2654435761L.toInt())
                memory[i] = c
                state[0] = state[0] xor c
                byseMix(state)
            }
        }
        return IntArray(8) { i ->
            byseMix(state)
            var v = state[0]
            for (j in i * 64 until (i + 1) * 64) {
                val d = memory[j]
                v += d
                v = byseRotate(v, 5)
                v = v xor (d * 2246822519L.toInt())
            }
            v xor state[2]
        }
    }

    private fun byseLeadingZeroBits(words: IntArray): Int {
        var count = 0
        for (word in words) {
            if (word == 0) count += 32
            else return count + Integer.numberOfLeadingZeros(word)
        }
        return count
    }

    private suspend fun solveBysePow(nonce: String, difficulty: Int): String? {
        if (difficulty < 0 || difficulty > 32) return null
        if (difficulty <= 0) return "0"
        val started = System.nanoTime()
        var counter = 0
        while (counter < 200000 && (System.nanoTime() - started) / 1000000 < 14000) {
            if (byseLeadingZeroBits(byseWorkHash(nonce + ":" + counter)) >= difficulty) {
                return counter.toString()
            }
            counter++
            if (counter % 256 == 0) delay(1)
        }
        return null
    }

    private fun byseFixedUnsigned(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray().let {
            if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        val out = ByteArray(32)
        val copy = raw.takeLast(32).toByteArray()
        System.arraycopy(copy, 0, out, 32 - copy.size, copy.size)
        return out
    }

    private fun byseDerSignatureToP1363(der: ByteArray): ByteArray {
        var offset = 0
        if (der.isEmpty() || der[offset++].toInt() != 0x30) error("firma ECDSA DER inválida")
        var sequenceLength = der[offset++].toInt() and 0xff
        if (sequenceLength and 0x80 != 0) {
            val count = sequenceLength and 0x7f
            sequenceLength = 0
            repeat(count) { sequenceLength = (sequenceLength shl 8) or (der[offset++].toInt() and 0xff) }
        }
        if (der[offset++].toInt() != 0x02) error("firma ECDSA r inválida")
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        if (der[offset++].toInt() != 0x02) error("firma ECDSA s inválida")
        val sLength = der[offset++].toInt() and 0xff
        val ss = der.copyOfRange(offset, offset + sLength)
        fun fit(bytes: ByteArray): ByteArray {
            val unsigned = if (bytes.size > 32 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
            return ByteArray(32).also { out ->
                val v = unsigned.takeLast(32).toByteArray()
                System.arraycopy(v, 0, out, 32 - v.size, v.size)
            }
        }
        return fit(r) + fit(ss)
    }

    private suspend fun bysePostJson(
        url: String,
        headers: Map<String, String>,
        payload: JSONObject,
        timeoutSeconds: Long = 15L
    ): JSONObject {
        val response = app.post(
            url,
            headers = headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            json = JsonAsString(payload.toString()),
            timeout = timeoutSeconds
        )
        if (response.code !in 200..299) error("HTTP ${response.code} ${URI(url).path}")
        return JSONObject(response.text)
    }

    private suspend fun loadByseManual(
        embed: String,
        prefix: String,
        sourcePage: String,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        val input = runCatching { URI(embed) }.getOrNull() ?: return
        val code = Regex("""/(?:e|embed|sovm9|boh)/([a-z0-9_-]+)""", OPT_I)
            .find(input.path.orEmpty())?.groupValues?.getOrNull(1) ?: return
        val initialHeaders = mapOf("User-Agent" to BYSE_UA, "Referer" to sourcePage)

        try {
            val page = app.get(embed, headers = initialHeaders, timeout = 9)
            if (page.code !in 200..399) error("embed HTTP ${page.code}")
            val pageUrl = page.url.ifBlank { embed }
            val origin = runCatching { URI(pageUrl).let { it.scheme + "://" + it.authority } }
                .getOrDefault(input.scheme + "://" + input.authority)
            val pagePath = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault(input.path.orEmpty())
            val embedMode = Regex("""/(?:e|embed|sovm9|boh)/""", OPT_I).containsMatchIn(pagePath)
            val apiPrefix = origin + "/api/videos/" + code + if (embedMode) "/embed" else ""
            val headers = initialHeaders + mapOf(
                "Origin" to origin,
                "Referer" to pageUrl,
                "X-Embed-Origin" to mainUrl,
                "X-Embed-Referer" to "$mainUrl/",
                "X-Embed-Parent" to embed
            )

            val settingsResponse = app.get(apiPrefix + "/settings", headers = headers, timeout = 12)
            if (settingsResponse.code !in 200..299) error("settings HTTP ${settingsResponse.code}")
            val settings = JSONObject(settingsResponse.text)

            var fingerprint: JSONObject? = null
            var captchaToken = ""
            if (settings.optBoolean("captcha_required", false)) {
                val challenge = bysePostJson(origin + "/api/videos/access/challenge", headers, JSONObject(), 15)
                val nonce = challenge.optString("nonce")
                val challengeId = challenge.optString("challenge_id")
                if (nonce.isBlank() || challengeId.isBlank()) error("challenge incompleto")

                val generator = java.security.KeyPairGenerator.getInstance("EC")
                generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                val pair = generator.generateKeyPair()
                val publicKey = pair.public as java.security.interfaces.ECPublicKey
                val signer = java.security.Signature.getInstance("SHA256withECDSA")
                signer.initSign(pair.private)
                signer.update(nonce.toByteArray(Charsets.UTF_8))
                val signature = byseB64Encode(byseDerSignatureToP1363(signer.sign()))
                val random = java.security.SecureRandom().nextInt().toUInt().toLong() / 4294967295.0
                fun fpHash(n: Double): String = byseB64Encode(byseSha256(n.toString()))

                val client = JSONObject()
                    .put("user_agent", BYSE_UA).put("architecture", "arm").put("bitness", "32")
                    .put("platform", "Android").put("platform_version", "6.0.1").put("model", "SM-G5510")
                    .put("ua_full_version", "59.0.3071.125")
                    .put("brand_full_versions", JSONArray().put(JSONObject().put("brand", "SamsungBrowser").put("version", "7.2")))
                    .put("pixel_ratio", 1).put("screen_width", 1280).put("screen_height", 720).put("color_depth", 24)
                    .put("languages", JSONArray().put("en-US")).put("timezone", "America/New_York")
                    .put("hardware_concurrency", 4).put("device_memory", 2).put("touch_points", 1)
                    .put("canvas_hash", fpHash(random)).put("audio_hash", fpHash(random + 1))
                    .put("fonts_hash", fpHash(random + 3)).put("codecs_hash", fpHash(random + 4))
                    .put("media_devices", "ai1ao1vi3").put("pointer_type", "coarse")
                    .put("extra", JSONObject().put("vendor", "Samsung").put("appVersion", "7.2"))

                val jwk = JSONObject().put("kty", "EC").put("crv", "P-256")
                    .put("x", byseB64Encode(byseFixedUnsigned(publicKey.w.affineX)))
                    .put("y", byseB64Encode(byseFixedUnsigned(publicKey.w.affineY)))
                    .put("ext", true).put("key_ops", JSONArray().put("verify"))

                val attest = bysePostJson(
                    origin + "/api/videos/access/attest", headers,
                    JSONObject().put("viewer_id", "").put("device_id", "")
                        .put("challenge_id", challengeId).put("nonce", nonce)
                        .put("signature", signature).put("public_key", jwk)
                        .put("client", client).put("storage", JSONObject())
                        .put("attributes", JSONObject().put("entropy", "high")), 20
                )
                if (attest.optString("token").isBlank() || attest.optString("viewer_id").isBlank() ||
                    attest.optString("device_id").isBlank()
                ) error("atestación incompleta")

                fingerprint = JSONObject().put("token", attest.optString("token"))
                    .put("viewer_id", attest.optString("viewer_id"))
                    .put("device_id", attest.optString("device_id"))
                if (attest.has("confidence")) fingerprint.put("confidence", attest.opt("confidence"))

                val captcha = bysePostJson(apiPrefix + "/captcha", headers, JSONObject().put("fingerprint", fingerprint), 15)
                val powNonce = captcha.optString("pow_nonce")
                val powToken = captcha.optString("pow_token")
                if (powNonce.isBlank() || powToken.isBlank()) error("desafío PoW incompleto")
                val solution = solveBysePow(powNonce, captcha.optInt("pow_difficulty", -1)) ?: error("PoW agotó el tiempo")
                val verified = bysePostJson(
                    apiPrefix + "/captcha/verify", headers,
                    JSONObject().put("pow_token", powToken).put("solution", solution).put("fingerprint", fingerprint), 15
                )
                captchaToken = verified.optString("token")
                if (captchaToken.isBlank()) error("verificación CAPTCHA sin token")
            }

            val playbackHeaders = headers + if (captchaToken.isBlank()) emptyMap() else mapOf("X-Captcha-Token" to captchaToken)
            val playback = bysePostJson(
                apiPrefix + "/playback", playbackHeaders,
                if (settings.optBoolean("captcha_required", false)) JSONObject().put("fingerprint", fingerprint) else JSONObject(),
                20
            )

            var sources = playback.optJSONArray("sources")
            if (sources == null) {
                val encrypted = playback.optJSONObject("playback")
                val parts = encrypted?.optJSONArray("key_parts")
                if (encrypted != null && parts != null && parts.length() > 0) {
                    var keys = (0 until parts.length()).mapNotNull { parts.optString(it).takeIf(String::isNotBlank) }
                    val version = encrypted.optInt("version", 0)
                    if (version in 1..keys.size) keys = listOf(keys[version - 1], keys[keys.size - version])
                    val key = keys.flatMap { byseB64Decode(it).toList() }.toByteArray()
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, byseB64Decode(encrypted.optString("iv")))
                    )
                    sources = JSONObject(String(cipher.doFinal(byseB64Decode(encrypted.optString("payload"))), Charsets.UTF_8))
                        .optJSONArray("sources")
                }
            }
            if (sources == null || sources.length() == 0) error("playback sin fuentes")

            val choices = (0 until sources.length()).mapNotNull { i ->
                val item = sources.optJSONObject(i) ?: return@mapNotNull null
                val src = item.optString("url").trim()
                if (src.isBlank()) null else Pair(item.optString("label", item.optString("name", "Byse")), src)
            }.sortedByDescending { Regex("""(\d{3,4})\s*[pP]?""").find(it.first)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 }

            val chosen = choices.firstOrNull { it.second.startsWith("http", true) } ?: choices.firstOrNull()
                ?: error("fuentes sin URL")
            val streamUrl = URI(origin).resolve(chosen.second).toString()
            val streamHeaders = mapOf(
                "User-Agent" to BYSE_UA, "Referer" to pageUrl, "Origin" to origin,
                "X-Embed-Origin" to mainUrl, "X-Embed-Referer" to "$mainUrl/", "X-Embed-Parent" to embed
            )
            val isHls = Regex("""\.m3u8(?:$|[?#])""", OPT_I).containsMatchIn(streamUrl)
            val isDash = Regex("""\.mpd(?:$|[?#])""", OPT_I).containsMatchIn(streamUrl)
            var actualQuality = getQualityFromName(chosen.first)
            if (isHls) {
                val heights = detectHlsQuality(streamUrl, streamHeaders)
                if (heights != Qualities.Unknown.value) actualQuality = heights
            }
            val qualityLabel = when {
                actualQuality >= 2160 -> "2160p"
                actualQuality >= 1440 -> "1440p"
                actualQuality >= 1080 -> "1080p"
                actualQuality >= 720 -> "720p"
                actualQuality >= 480 -> "480p"
                actualQuality >= 360 -> "360p"
                else -> "Auto"
            }
            emit(
                newExtractorLink(
                    source = name,
                    name = "$prefix · $qualityLabel",
                    url = streamUrl,
                    type = if (isHls || isDash) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = pageUrl
                    quality = actualQuality
                    this.headers = streamHeaders
                }
            )
            log { "Byse resuelto host=${URI(streamUrl).host}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "Byse ERROR ${input.host}: ${e.javaClass.simpleName}: ${e.message}" }
        }
    }

    /* ------------------------------- VOE ------------------------------ */

    private suspend fun loadVoe(embedUrl: String, prefix: String, emit: suspend (ExtractorLink) -> Unit) {
        try {
            val sourceUrl = withTimeoutOrNull(VOE_TIMEOUT_NS / 1_000_000L) { resolveVoe(embedUrl) }
            if (sourceUrl.isNullOrBlank()) {
                log { "VOE FAIL $embedUrl" }
                return
            }
            val quality = detectHlsQuality(sourceUrl, mapOf("Referer" to embedUrl, "User-Agent" to VOE_UA))
            emit(
                newExtractorLink(
                    source = "VOE",
                    name = "$prefix · VOE",
                    url = sourceUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = embedUrl
                    this.quality = quality
                }
            )
            log { "VOE EMIT $sourceUrl" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log { "VOE ERROR ${e.javaClass.simpleName}: ${e.message}" }
        }
    }

    private suspend fun resolveVoe(embedUrl: String): String? {
        val headers = mapOf("User-Agent" to VOE_UA)
        val first = app.get(embedUrl, headers = headers, timeout = 15)
        if (!first.isSuccessful) return null

        val redirect = VOE_REDIRECT.find(first.text)?.groupValues?.getOrNull(1)
        val realUrl = redirect ?: embedUrl
        val page = if (redirect != null) app.get(realUrl, headers = headers, timeout = 15) else first

        var encoded = VOE_CONFIG.find(page.text)?.groupValues?.getOrNull(1)
        if (encoded.isNullOrBlank()) {
            val solved = solveVoeAltcha(realUrl, page.text, page.cookies) ?: return null
            encoded = VOE_CONFIG_STRICT.find(solved)?.groupValues?.getOrNull(1)
        }
        if (encoded.isNullOrBlank()) return null

        val decoded = decodeVoeConfig(encoded) ?: return null
        return VOE_SOURCE.find(decoded)?.groupValues?.getOrNull(1)
            ?.replace("\\\\/", "/")
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
    }

    private suspend fun solveVoeAltcha(realUrl: String, html: String, pageCookies: Map<String, String>): String? {
        val csrf = VOE_TOKEN.find(html)?.groupValues?.getOrNull(1)
        val challengeUrl = VOE_CHALLENGE.find(html)?.groupValues?.getOrNull(1)
        if (csrf.isNullOrBlank() || challengeUrl.isNullOrBlank()) return null

        val challengeResponse = app.get(
            challengeUrl,
            headers = mapOf("User-Agent" to VOE_UA, "Referer" to realUrl),
            timeout = 12
        )
        if (!challengeResponse.isSuccessful) return null

        val challengeJson = challengeResponse.text
        val algorithm = jsonString(challengeJson, "algorithm")
        val cost = jsonInt(challengeJson, "cost")
        val keyLength = jsonInt(challengeJson, "keyLength")
        val keyPrefix = jsonString(challengeJson, "keyPrefix")
        val nonce = jsonString(challengeJson, "nonce")
        val salt = jsonString(challengeJson, "salt")
        if (algorithm.isNullOrBlank() || cost == null || keyLength == null ||
            keyPrefix.isNullOrBlank() || nonce.isNullOrBlank() || salt.isNullOrBlank()
        ) return null

        val saltBytes = hexToBytes(salt)
        val nonceBytes = hexToBytes(nonce)
        val mac = Mac.getInstance("HmacSHA256")
        var solvedCounter = -1
        var solvedKey = ""
        val powStart = System.nanoTime()

        for (counter in 0 until 1_000_000) {
            if ((counter and 0xff) == 0 && System.nanoTime() - powStart > VOE_TIMEOUT_NS) break
            val counterBytes = byteArrayOf(
                ((counter ushr 24) and 0xff).toByte(),
                ((counter ushr 16) and 0xff).toByte(),
                ((counter ushr 8) and 0xff).toByte(),
                (counter and 0xff).toByte()
            )
            val derived = pbkdf2Sha256(mac, nonceBytes + counterBytes, saltBytes, cost, keyLength)
            val hex = bytesToHex(derived)
            if (hex.startsWith(keyPrefix)) {
                solvedCounter = counter
                solvedKey = hex
                break
            }
        }
        if (solvedCounter < 0) return null

        val elapsed = (System.nanoTime() - powStart) / 1_000_000.0
        val payload = """{"challenge":$challengeJson,"solution":{"counter":$solvedCounter,"derivedKey":"$solvedKey","time":$elapsed}}"""
        val payloadB64 = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val cookies = pageCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val origin = "https://" + realUrl.substringAfter("://").substringBefore("/")

        val post = app.post(
            realUrl,
            headers = mapOf(
                "User-Agent" to VOE_UA,
                "Referer" to realUrl,
                "Origin" to origin,
                "Cookie" to cookies,
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            data = mapOf("_token" to csrf, "access" to "0", "altcha" to payloadB64),
            timeout = 15
        )
        return if (post.isSuccessful) post.text else null
    }

    private fun jsonString(json: String, key: String): String? =
        Regex("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)

    private fun jsonInt(json: String, key: String): Int? =
        Regex("\"" + key + "\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun decodeVoeConfig(encoded: String): String? = try {
        var value = buildString {
            for (char in encoded) {
                append(
                    when (char) {
                        in 'A'..'Z' -> ((char.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                        in 'a'..'z' -> ((char.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                        else -> char
                    }
                )
            }
        }
        for (separator in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            value = value.replace(separator, "_")
        }
        value = value.replace("_", "")
        var bytes = Base64.getDecoder().decode(value)
        bytes = bytes.map { (it.toInt() - 3).toByte() }.toByteArray()
        bytes.reverse()
        String(Base64.getDecoder().decode(String(bytes, Charsets.UTF_8)), Charsets.UTF_8)
    } catch (e: Exception) {
        log { "VOE decode ERROR ${e.message}" }
        null
    }

    private fun pbkdf2Sha256(mac: Mac, password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLength = mac.macLength
        val blockCount = (keyLength + hashLength - 1) / hashLength
        val output = ByteArray(blockCount * hashLength)
        for (block in 1..blockCount) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    ((block ushr 24) and 0xff).toByte(),
                    ((block ushr 16) and 0xff).toByte(),
                    ((block ushr 8) and 0xff).toByte(),
                    (block and 0xff).toByte()
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, output, (block - 1) * hashLength, hashLength)
        }
        return output.copyOf(keyLength)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = VOE_HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = VOE_HEX_CHARS[v and 0x0f]
        }
        return String(chars)
    }

    private fun hexToBytes(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "Hex inválido" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /* ------------------------------ LULU ------------------------------ */

    private suspend fun loadLulu(embed: String, prefix: String, emit: suspend (ExtractorLink) -> Unit) {
        val res = app.get(embed, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"), timeout = 12)
        if (!res.isSuccessful) {
            log { "Lulu HTTP ${res.code}" }
            return
        }
        val unpacked = try {
            getAndUnpack(res.text)
        } catch (e: Exception) {
            res.text
        }
        val m3u8 = RX_M3U8_FILE.find(unpacked)?.groupValues?.getOrNull(1)
            ?: RX_M3U8_URL.find(unpacked)?.value
            ?: RX_M3U8_URL.find(res.text)?.value
            ?: run {
                log { "Lulu: sin m3u8" }
                return
            }
        val origin = runCatching { URI(embed).let { it.scheme + "://" + it.authority } }.getOrDefault(mainUrl)
        emit(
            newExtractorLink(
                source = "LuluStream",
                name = "$prefix · LuluStream",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$origin/"
                quality = detectHlsQuality(m3u8, mapOf("User-Agent" to UA, "Referer" to "$origin/"))
            }
        )
    }

    private fun absoluteUrl(href: String): String = when {
        href.startsWith("http", true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$mainUrl$href"
        else -> "$mainUrl/$href"
    }
}
