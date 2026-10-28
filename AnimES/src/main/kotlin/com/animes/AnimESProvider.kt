package com.animes

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/* ====================================================================
 * Config
 * ==================================================================== */

// Poné true para ver los logs (println) mientras debuggeás.
private const val DEBUG = false

// Si es true, los links que salen de extractores se etiquetan con su origen
// ("Voe · Animora" / "Voe · AnimeAV") para distinguir los que parecen repetidos.
private const val TAG_LINKS = true

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"

private const val AV_BASE = "https://animeav1.com"
private const val UPN_BASE = "https://animeav1.uns.bio"
private const val UPN_KEY = "kiemtienmua911ca"
private const val UPN_IV = "1234567890oiuytr"

private const val EXTRACTOR_TIMEOUT_MS = 7_000L
private const val ANIMEAV_TIMEOUT_MS = 12_000L
private const val HOME_CACHE_MS = 180_000L

private const val TAG_ANIMEYT = "AnimeYT"
private const val TAG_ANIMEAV = "AnimeAV"

private const val SECTION_RECENT = "Últimos episodios"
private const val SECTION_RESUB = "Resubidos"
private const val SECTION_EXPLORE = "Explorar"
private const val SECTION_MOVIES = "Películas"

private fun log(msg: String) {
    if (DEBUG) println("AnimES: $msg")
}

/* ====================================================================
 * Regex precompilados (antes se recompilaban en cada llamada)
 * ==================================================================== */

private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
private val DIACRITICS = Regex("""\p{M}+""")
private val HTML_TAG = Regex("<[^>]+>")

private val AV_CARD = Regex(
    """<h3[^>]*>(.*?)</h3>[\s\S]*?<a[^>]+href="(/media/[^"]+)"""",
    RegexOption.IGNORE_CASE
)
private val AV_EMBEDS = Regex("""embeds:\{(.*?)\},downloads""", RegexOption.DOT_MATCHES_ALL)
private val AV_SERVER = Regex("""server:"([^"]+)",url:"([^"]+)"""")
private val AV_LANGUAGE_BLOCK = Regex("""(SUB|DUB):\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)

private val MEGA_EMBED = Regex("""mega\.nz/[^?#]*embed/([^?#]+)""", RegexOption.IGNORE_CASE)
private val MEGA_FALLBACK =
    Regex("""(?:embed/|/)([^#!?/\s]+)[#!]([^#!?\s]+)""", RegexOption.IGNORE_CASE)

/* ====================================================================
 * Helpers de título (para encontrar el anime en AnimeAV)
 * ==================================================================== */

private val STOPWORDS = setOf(
    "no", "wa", "ga", "to", "wo", "ni", "na", "the", "of", "and", "in",
    "de", "la", "el", "los", "las", "y", "a"
)

// Si el candidato tiene una de estas palabras y lo pedido NO, se penaliza.
private val MARKERS = setOf(
    "ova", "ovas", "oav", "recap", "special", "specials", "especial",
    "movie", "film", "pelicula", "2nd", "3rd", "4th", "5th"
)

private fun normalizeTitle(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(NON_ALNUM, " ")
        .trim()

private fun titleWords(normalized: String): Set<String> =
    normalized
        .split(" ")
        .filter { (it.length >= 2 || (it.isNotEmpty() && it.all(Char::isDigit))) && it !in STOPWORDS }
        .toSet()

private fun animeTitleScore(requested: String, candidate: String): Int {
    val r = normalizeTitle(requested)
    val c = normalizeTitle(candidate)

    if (r == c) return 10_000

    val rw = titleWords(r)
    val cw = titleWords(c)
    if (rw.isEmpty() || cw.isEmpty()) return 0

    val common = rw.intersect(cw).size
    if (common == 0) return 0

    // Tiene que coincidir al menos la mitad de lo pedido
    if (common.toFloat() / rw.size < 0.5f) return 0

    var score = common * 100
    score -= (cw.size - common) * 10 // desempata a favor del título más parecido
    for (m in MARKERS) {
        if (m in cw && m !in rw) score -= 150
    }
    return score
}

/* ====================================================================
 * Dedupe
 * ==================================================================== */

// Parámetros que cambian en cada request pero no cambian el video.
private val VOLATILE_PARAMS = setOf(
    "token", "tok", "expires", "expire", "exp", "sig", "signature",
    "ts", "t", "e", "st", "md5", "ref", "referer", "_"
)

/**
 * Clave para detectar "el mismo" link/embed:
 * sin esquema, sin www, sin "/" final y sin parámetros volátiles.
 * El fragmento (#...) se conserva porque ahí viajan los IDs (UPNShare, Mega).
 */
private fun dedupeKey(url: String): String {
    val u = url.trim().substringAfter("://").removePrefix("www.")
    val fragment = u.substringAfter("#", "")
    val noFrag = u.substringBefore("#")
    val path = noFrag.substringBefore("?").trimEnd('/')
    val host = path.substringBefore("/").lowercase()
    val rest = path.substringAfter("/", "")
    val query = noFrag
        .substringAfter("?", "")
        .split("&")
        .filter { it.isNotBlank() && it.substringBefore("=").lowercase() !in VOLATILE_PARAMS }
        .sorted()
        .joinToString("&")

    return buildString {
        append(host)
        if (rest.isNotEmpty()) append('/').append(rest)
        if (query.isNotEmpty()) append('?').append(query)
        if (fragment.isNotEmpty()) append('#').append(fragment)
    }
}

private fun isMegaServer(provider: String, url: String): Boolean =
    provider.equals("mega", ignoreCase = true) || url.contains("mega.nz/", ignoreCase = true)

private fun isHlsServer(provider: String, url: String): Boolean =
    provider.equals("hls", ignoreCase = true) ||
        url.substringBefore("?").endsWith(".m3u8", ignoreCase = true)

private suspend fun safely(label: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("ERROR $label -> ${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * Punto único de salida de links: deduplica, cuenta y serializa el callback.
 * `count` es lo que decide el return de loadLinks (ya no depende de flags sueltos).
 */
private class LinkSink(private val callback: (ExtractorLink) -> Unit) {

    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()
    private val emitted = AtomicInteger(0)

    val megaClaimed = AtomicBoolean(false)
    val count: Int get() = emitted.get()

    /** true si es la primera vez que vemos este embed (entre Animora y AnimeAV). */
    fun claimEmbed(url: String): Boolean = seen.add("embed:" + dedupeKey(url))

    suspend fun emit(link: ExtractorLink, tag: String?): Boolean {
        if (!seen.add("link:" + dedupeKey(link.url))) {
            log("link duplicado ignorado: ${link.url}")
            return false
        }

        val out =
            if (tag == null || !TAG_LINKS) {
                link
            } else {
                newExtractorLink(
                    source = link.source,
                    name = "${link.name} · $tag",
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            }

        emitted.incrementAndGet()
        synchronized(lock) { callback(out) }
        log("LINK ${out.name} q=${out.quality} ${out.url}")
        return true
    }
}

/* ====================================================================
 * Provider
 * ==================================================================== */

class AnimESProvider : MainAPI() {
    override var mainUrl = "https://animeyt.cc"
    override var name = "AnimES"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.AnimeMovie)
    override var lang = "es"
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/" to SECTION_RECENT,
        "$mainUrl/" to SECTION_RESUB,
        "$mainUrl/tv/" to SECTION_EXPLORE,
        "$mainUrl/pelicula/" to SECTION_MOVIES
    )
    private val apiHeaders: Map<String, String>
        get() = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8", "Referer" to "$mainUrl/")
    private val animeAvSlugCache = ConcurrentHashMap<String, String>()

    private fun cleanHtml(value: String): String = value.replace(HTML_TAG, " ")
        .replace("&nbsp;", " ", ignoreCase = true).replace("&#8211;", "–").replace("&#8212;", "—")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun absoluteAnimeUrl(value: String): String = try { URI(mainUrl).resolve(value).toString() } catch (_: Exception) { value }
    private fun pathOf(value: String): String = try { URI(value).path ?: value } catch (_: Exception) { value.substringBefore("?").substringBefore("#") }
    private fun titleFromSlug(slug: String): String = slug.removeSuffix("/")
        .replace(Regex("-temporada-\\d+.*$"), "").replace(Regex("-capitulo-\\d+(?:-\\d+)?(?:-\\d{4})?$"), "")
        .replace("-", " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("\\b\\p{L}")) { it.value.uppercase() }

    private fun extractAnimeCards(html: String, expectedKind: String = "tv"): List<SearchResponse> {
        val titles = linkedMapOf<String, String>()
        val headingRe = Regex("""<h[1-4][^>]*>\s*<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in headingRe.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val path = pathOf(absoluteAnimeUrl(href)).trimEnd('/')
            val title = cleanHtml(m.groupValues[2])
            if (title.isNotBlank()) titles.putIfAbsent(path, title)
        }
        val found = linkedMapOf<String, SearchResponse>()
        val anchorRe = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchorRe.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val path = pathOf(absoluteAnimeUrl(href)).trimEnd('/')
            val match = Regex("""^/(tv|pelicula)/([^/]+)$""", RegexOption.IGNORE_CASE).matchEntire(path) ?: continue
            val kind = match.groupValues[1].lowercase()
            if (expectedKind != "any" && kind != expectedKind) continue
            val slug = match.groupValues[2]
            if (slug == "page") continue
            val key = "$kind/$slug"
            if (found.containsKey(key)) continue
            val inner = m.groupValues[2]
            val title = titles[path].orEmpty().ifBlank {
                Regex("""<img\b[^>]*\balt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
            }.ifBlank { titleFromSlug(slug) }
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(inner)?.value?.let { img ->
                listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                    Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(img)?.groupValues?.get(1)
                }
            }?.let(::absoluteAnimeUrl)
            found[key] = newAnimeSearchResponse(title, "$mainUrl/$kind/$slug/", if (kind == "pelicula") TvType.AnimeMovie else TvType.Anime) {
                posterUrl = poster
            }
        }
        return found.values.toList()
    }

    private fun extractLatestEpisodes(html: String): List<SearchResponse> {
        val found = linkedMapOf<String, SearchResponse>()
        val anchorRe = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchorRe.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val path = pathOf(absoluteAnimeUrl(href))
            val epMatch = Regex("""^/(\d+)/anime/([^/]+)/?$""", RegexOption.IGNORE_CASE).matchEntire(path) ?: continue
            val epSlug = epMatch.groupValues[2]
            val numMatch = Regex("""-capitulo-(\d+)(?:-(5))?(?:-\d{4})?$""", RegexOption.IGNORE_CASE).find(epSlug) ?: continue
            if (numMatch.groupValues[2].isNotBlank()) continue
            val number = numMatch.groupValues[1].toIntOrNull() ?: continue
            val seriesSlug = epSlug.replace(Regex("-capitulo-\\d+(?:-\\d+)?(?:-\\d{4})?$"), "")
            if (seriesSlug.isBlank()) continue
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.value?.let { img ->
                listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                    Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(img)?.groupValues?.get(1)
                }
            }?.let(::absoluteAnimeUrl)
            found.putIfAbsent(path, newAnimeSearchResponse("${titleFromSlug(seriesSlug)} - Episodio $number", "$mainUrl/tv/$seriesSlug/", TvType.Anime) {
                posterUrl = poster
            })
        }
        return found.values.toList()
    }

    private data class AnimeYtEpisodeBrowser(val animeId: String, val blocks: Int, val endpoint: String)

    private fun extractAnimeAvCards(html: String): List<SearchResponse> {
        val titles = linkedMapOf<String, String>()
        val headingRe = Regex("""<h[1-4][^>]*>\s*<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in headingRe.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val p = try { URI(AV_BASE).resolve(href).path?.trimEnd('/') ?: href } catch (_: Exception) { href }
            val t = cleanHtml(m.groupValues[2])
            if (t.isNotBlank()) titles.putIfAbsent(p, t)
        }
        val found = linkedMapOf<String, SearchResponse>()
        val anchors = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchors.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val p = try { URI(AV_BASE).resolve(href).path?.trimEnd('/') ?: href } catch (_: Exception) { href }
            val match = Regex("""^/media/([^/]+)$""", RegexOption.IGNORE_CASE).matchEntire(p) ?: continue
            val slug = match.groupValues[1]
            val inner = m.groupValues[2]
            val title = titles[p].orEmpty().ifBlank {
                Regex("""<img\b[^>]*\balt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
            }.ifBlank { titleFromSlug(slug) }
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(inner)?.value?.let { img ->
                listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                    Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(img)?.groupValues?.get(1)
                }
            }?.let { try { URI(AV_BASE).resolve(it).toString() } catch (_: Exception) { it } }
            found.putIfAbsent(slug, newAnimeSearchResponse("$title •AnimeAV", "$AV_BASE/media/$slug/", TvType.Anime) { posterUrl = poster })
        }
        return found.values.toList()
    }

    private fun extractAnimeAvLatestEpisodes(html: String): List<SearchResponse> {
        val found = linkedMapOf<String, SearchResponse>()
        val anchors = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchors.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val p = try { URI(AV_BASE).resolve(href).path ?: href } catch (_: Exception) { href }
            val match = Regex("""^/media/([^/]+)/([0-9]+)/?$""", RegexOption.IGNORE_CASE).matchEntire(p) ?: continue
            val slug = match.groupValues[1]
            val number = match.groupValues[2].toIntOrNull() ?: continue
            val title = titleFromSlug(slug)
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.value?.let { img ->
                listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                    Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(img)?.groupValues?.get(1)
                }
            }?.let { try { URI(AV_BASE).resolve(it).toString() } catch (_: Exception) { it } }
            found.putIfAbsent("$slug/$number", newAnimeSearchResponse("$title - Episodio $number •AnimeAV", "$AV_BASE/media/$slug/", TvType.Anime) { posterUrl = poster })
        }
        return found.values.toList()
    }

    private fun isRelevantSearchTitle(query: String, candidate: String): Boolean {
        val cleanCandidate = candidate.replace(Regex("""\s*•\s*AnimeAV\s*$""", RegexOption.IGNORE_CASE), "")
        val q = normalizeTitle(query)
        val c = normalizeTitle(cleanCandidate)
        return q.isNotBlank() && (q == c || c.contains(q) || q.contains(c) || animeTitleScore(query, cleanCandidate) > 0)
    }

    private suspend fun searchAnimeYt(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val baseHtml = try { withTimeoutOrNull(7000) { app.get("$mainUrl/tv/", headers = apiHeaders).text } ?: "" } catch (_: Exception) { "" }
        val baseCards = extractAnimeCards(baseHtml, "tv")
        val baseline = baseCards.take(8).map { it.url }.joinToString("|")
        val tries = listOf("q", "buscar", "search", "busqueda", "s").map { "$mainUrl/tv/?$it=$encoded" } + listOf("$mainUrl/?s=$encoded")
        val candidates = coroutineScope {
            tries.map { url -> async {
                try {
                    val html = withTimeoutOrNull(7000) { app.get(url, headers = apiHeaders).text } ?: ""
                    extractAnimeCards(html, "tv")
                } catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        val matched = candidates.filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
        if (matched.isNotEmpty() && matched.take(8).map { it.url }.joinToString("|") != baseline) return matched.sortedByDescending { animeTitleScore(query, it.name) }
        val pages = (2..12).map { "$mainUrl/tv/page/$it/" } + listOf("$mainUrl/")
        val catalog = coroutineScope {
            pages.map { url -> async {
                try {
                    val html = withTimeoutOrNull(8000) { app.get(url, headers = apiHeaders).text } ?: ""
                    val extras = if (url == "$mainUrl/") extractAnimeCards(sectionHtml(html, SECTION_RESUB), "tv") else emptyList()
                    extractAnimeCards(html, "tv") + extras
                } catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        return (baseCards + catalog).filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
            .sortedByDescending { animeTitleScore(query, it.name) }.take(40)
    }

    private suspend fun searchAnimeAvResults(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val headers = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9", "Referer" to "$AV_BASE/")
        val urls = listOf("$AV_BASE/catalogo?search=$encoded", "$AV_BASE/catalogo?q=$encoded", "$AV_BASE/catalogo?buscar=$encoded", "$AV_BASE/catalogo?s=$encoded", "$AV_BASE/?s=$encoded", "$AV_BASE/?q=$encoded")
        val results = coroutineScope {
            urls.map { url -> async {
                try {
                    val html = withTimeoutOrNull(8000) { app.get(url, headers = headers).text } ?: ""
                    extractAnimeAvCards(html)
                } catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        val matched = results.filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
        if (matched.isNotEmpty()) return matched.sortedByDescending { animeTitleScore(query, it.name) }.take(40)
        val fallback = coroutineScope {
            listOf("$AV_BASE/catalogo", "$AV_BASE/").map { url -> async {
                try {
                    val html = withTimeoutOrNull(9000) { app.get(url, headers = headers).text } ?: ""
                    extractAnimeAvCards(html)
                } catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        return fallback.filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
            .sortedByDescending { animeTitleScore(query, it.name) }.take(40)
    }

    private fun episodeBrowserInfo(html: String): AnimeYtEpisodeBrowser? {
        val section = Regex("""<section\b[^>]*data-abismo-episode-browser[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
        fun attr(name: String): String = Regex("""\b""" + Regex.escape(name) + """\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(section)?.groupValues?.get(1).orEmpty()
        val animeId = attr("data-anime-id")
        val blocks = attr("data-blocks").toIntOrNull() ?: return null
        val endpoint = attr("data-endpoint")
        if (animeId.isBlank() || endpoint.isBlank() || blocks < 1) return null
        val absolute = try { URI(mainUrl).resolve(endpoint).toString() } catch (_: Exception) { endpoint }
        return AnimeYtEpisodeBrowser(animeId, blocks.coerceAtMost(100), absolute)
    }

    private suspend fun fetchAnimeYtEpisodeBlock(info: AnimeYtEpisodeBrowser, block: Int): String? {
        val separator = if (info.endpoint.contains("?")) "&" else "?"
        val url = info.endpoint + separator + "anime_id=" + URLEncoder.encode(info.animeId, "UTF-8") + "&block=" + block + "&order=ASC"
        for (attempt in 0..2) {
            try {
                val raw = withTimeoutOrNull(7000) { app.get(url, headers = apiHeaders).text } ?: continue
                val blockHtml = JSONObject(raw).optString("html")
                if (blockHtml.isNotBlank()) return blockHtml
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { log("AnimeYT episode block request failed") }
        }
        return null
    }

    private fun parseAnimeYtEpisodes(html: String, slug: String, title: String, poster: String?, seasonBlock: Int): List<Pair<Int, Episode>> {
        val items = linkedMapOf<String, Pair<Int, Episode>>()
        val anchors = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val safeTitle = title.replace("|", " ")
        for (m in anchors.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val epPath = pathOf(absoluteAnimeUrl(href))
            val epMatch = Regex("""^/([0-9]+)/anime/([^/]+)/?$""", RegexOption.IGNORE_CASE).matchEntire(epPath) ?: continue
            val epSlug = epMatch.groupValues[2]
            val numMatch = Regex("""-capitulo-([0-9]+)(?:-([0-9]+))?(?:-[0-9]{4})?$""", RegexOption.IGNORE_CASE).find(epSlug) ?: continue
            if (numMatch.groupValues[2].isNotBlank() && numMatch.groupValues[2] != "5") continue
            val number = numMatch.groupValues[1].toIntOrNull() ?: continue
            if (number < 1) continue
            items.putIfAbsent(epPath, number to newEpisode("$slug|$number|$safeTitle|$epPath") {
                name = cleanHtml(m.groupValues[2]).ifBlank { "Episodio $number" }
                episode = number; season = seasonBlock; posterUrl = poster
            })
        }
        return items.values.sortedBy { it.first }
    }


    private fun sectionHtml(html: String, label: String): String {
        val headings = Regex("""<h([1-6])\b[^>]*>[\s\S]*?</h\1>""", RegexOption.IGNORE_CASE).findAll(html).toList()
        val normalize: (String) -> String = { cleanHtml(it).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ") }
        val current = headings.firstOrNull { normalize(it.value).contains(normalize(label)) } ?: return ""
        val level = Regex("""<h(\d)""", RegexOption.IGNORE_CASE).find(current.value)?.groupValues?.get(1)?.toIntOrNull() ?: 6
        val next = headings.firstOrNull { it.range.first > current.range.first && ((Regex("""<h(\d)""", RegexOption.IGNORE_CASE).find(it.value)?.groupValues?.get(1)?.toIntOrNull() ?: 6) <= level) }
        return html.substring(current.range.last + 1, next?.range?.first ?: html.length)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == SECTION_RECENT) {
            val (ytHtml, avHtml) = coroutineScope {
                val yt = async { try { app.get("$mainUrl/", headers = apiHeaders).text } catch (_: Exception) { "" } }
                val av = async { try { app.get("$AV_BASE/", headers = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9", "Referer" to "$AV_BASE/")).text } catch (_: Exception) { "" } }
                yt.await() to av.await()
            }
            val ytItems = extractLatestEpisodes(sectionHtml(ytHtml, SECTION_RECENT).ifBlank { ytHtml })
            val avItems = extractAnimeAvLatestEpisodes(avHtml)
            return newHomePageResponse(request.name, (avItems + ytItems).distinctBy { it.url + "|" + it.name })
        }
        val url = when (request.name) {
            SECTION_EXPLORE -> if (page <= 1) "$mainUrl/tv/" else "$mainUrl/tv/page/$page/"
            SECTION_MOVIES -> if (page <= 1) "$mainUrl/pelicula/" else "$mainUrl/pelicula/page/$page/"
            else -> "$mainUrl/"
        }
        val html = try { app.get(url, headers = apiHeaders).text } catch (_: Exception) { "" }
        val items = when (request.name) {
            SECTION_RESUB -> extractAnimeCards(sectionHtml(html, SECTION_RESUB), "tv")
            SECTION_MOVIES -> extractAnimeCards(html, "pelicula")
            SECTION_EXPLORE -> extractAnimeCards(html, "tv")
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && items.size >= 18 && request.name in setOf(SECTION_EXPLORE, SECTION_MOVIES))
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val (yt, av) = coroutineScope {
            val animeYt = async { try { searchAnimeYt(query) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
            val animeAv = async { try { searchAnimeAvResults(query) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
            animeYt.await() to animeAv.await()
        }
        return (yt + av).distinctBy { it.url }
            .sortedByDescending { animeTitleScore(query, it.name.replace(Regex("""\s*•\s*AnimeAV\s*$""", RegexOption.IGNORE_CASE), "")) }
    }

    private suspend fun loadAnimeAvPage(pageUrl: String): LoadResponse? {
        val path = try { URI(pageUrl).path?.trimEnd('/') ?: return null } catch (_: Exception) { return null }
        val match = Regex("""^/media/([^/]+)$""", RegexOption.IGNORE_CASE).matchEntire(path) ?: return null
        val slug = match.groupValues[1]
        val canonical = "$AV_BASE/media/$slug/"
        val headers = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9", "Referer" to "$AV_BASE/")
        val html = try { app.get(canonical, headers = headers).text } catch (_: Exception) { return null }
        val title = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(::cleanHtml)?.replace(Regex("""\s*[-|]\s*AnimeAV1.*$""", RegexOption.IGNORE_CASE), "") ?: titleFromSlug(slug)
        val poster = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { try { URI(AV_BASE).resolve(it).toString() } catch (_: Exception) { it } }
        val plot = Regex("""<meta[^>]+name=["']description["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(::cleanHtml)
        val episodes = linkedMapOf<Int, Episode>()
        val anchors = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val safeTitle = title.replace("|", " ")
        for (m in anchors.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val epPath = try { URI(AV_BASE).resolve(href).path ?: href } catch (_: Exception) { href }
            val epMatch = Regex("""^/media/([^/]+)/([0-9]+)/?$""", RegexOption.IGNORE_CASE).matchEntire(epPath) ?: continue
            if (epMatch.groupValues[1] != slug) continue
            val number = epMatch.groupValues[2].toIntOrNull() ?: continue
            val epTitle = cleanHtml(m.groupValues[2]).ifBlank { "Episodio $number" }
            episodes.putIfAbsent(number, newEpisode("av|$slug|$number|$safeTitle|$epPath") {
                name = epTitle; episode = number; season = 1; posterUrl = poster
            })
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, canonical, TvType.Anime, episodes.toSortedMap().values.toList()) {
            posterUrl = poster; this.plot = plot
            seasonNames = listOf(SeasonData(season = 1, name = "Temporada 1", displaySeason = 1))
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith(AV_BASE, ignoreCase = true)) return loadAnimeAvPage(url)
        val path = pathOf(url).trimEnd('/')
        val match = Regex("""^/(tv|pelicula)/([^/]+)$""", RegexOption.IGNORE_CASE).matchEntire(path) ?: return null
        val kind = match.groupValues[1].lowercase()
        val slug = match.groupValues[2]
        val pageUrl = "$mainUrl/$kind/$slug/"
        val html = try { app.get(pageUrl, headers = apiHeaders).text } catch (e: Exception) { log("Load failed $pageUrl: ${e.message}"); return null }
        val title = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::cleanHtml)
            ?: Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::cleanHtml)
            ?: titleFromSlug(slug)
        val poster = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::absoluteAnimeUrl)
        val plot = Regex("""<meta[^>]+name=["']description["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::cleanHtml)
        if (kind == "pelicula") return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, "movie|$pageUrl|$title") {
            posterUrl = poster
            this.plot = plot
        }

        val browser = episodeBrowserInfo(html)
        val titleForAv = title.replace("|", " ").ifBlank { titleFromSlug(slug) }
        val initialEpisodes = parseAnimeYtEpisodes(html, slug, titleForAv, poster, 1)
        val episodeItems = mutableListOf<Pair<Int, Episode>>()
        if (browser != null && browser.blocks > 1) {
            val blockHtmls = mutableMapOf<Int, String>()
            var start = 0
            while (start < browser.blocks) {
                val end = minOf(start + 6, browser.blocks)
                val batch = coroutineScope {
                    (start until end).map { block -> async { block to fetchAnimeYtEpisodeBlock(browser, block) } }.awaitAll()
                }
                for ((block, blockHtml) in batch) if (!blockHtml.isNullOrBlank()) blockHtmls[block] = blockHtml
                start = end
            }
            for (block in 0 until browser.blocks) {
                val blockHtml = blockHtmls[block] ?: continue
                val from = block * 50 + 1
                val to = (block + 1) * 50
                episodeItems += parseAnimeYtEpisodes(blockHtml, slug, titleForAv, poster, block + 1).filter { it.first in from..to }
            }
            if (episodeItems.isEmpty()) episodeItems += initialEpisodes
        } else {
            episodeItems += initialEpisodes
        }
        val dedupedEpisodes = episodeItems.distinctBy { it.second.data }
            .sortedWith(compareBy<Pair<Int, Episode>> { it.second.season ?: 1 }.thenBy { it.first })
        if (dedupedEpisodes.isEmpty()) { log("No episodes found for " + pageUrl); return null }
        val maxEpisode = dedupedEpisodes.maxOfOrNull { it.first } ?: 0
        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, dedupedEpisodes.map { it.second }) {
            posterUrl = poster
            this.plot = plot
            if (browser != null && browser.blocks > 1) {
                seasonNames = (0 until browser.blocks).map { block ->
                    val from = block * 50 + 1
                    val to = if (block == browser.blocks - 1) maxOf(maxEpisode, browser.blocks * 50) else (block + 1) * 50
                    SeasonData(season = block + 1, name = "Capítulos " + from + "-" + to, displaySeason = block + 1)
                }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val sink = LinkSink(callback)
        if (data.startsWith("movie|")) {
            val movieUrl = data.split("|", limit = 3).getOrNull(1) ?: return false
            safely("AnimeYT movie") { loadAnimeYtSources(movieUrl, sink, subtitleCallback) }
            return sink.count > 0
        }
        if (data.startsWith("av|")) {
            val parts = data.split("|", limit = 5)
            if (parts.size < 4) return false
            val avSlug = parts[1].trim()
            val episodeNumber = parts[2].trim().toIntOrNull() ?: return false
            val title = parts[3].trim().ifBlank { titleFromSlug(avSlug) }
            safely("AnimeAV direct") {
                withTimeoutOrNull(ANIMEAV_TIMEOUT_MS) {
                    loadAnimeAv(listOf(title), episodeNumber, sink, subtitleCallback, explicitSlug = avSlug)
                }
            }
            return sink.count > 0
        }
        val parts = data.split("|", limit = 4)
        if (parts.size < 2) return false
        val slug = parts[0].trim()
        val episodeNumber = parts[1].trim().toIntOrNull() ?: return false
        val title = parts.getOrNull(2).orEmpty().trim().ifBlank { titleFromSlug(slug) }
        val episodePath = parts.getOrNull(3).orEmpty().trim()
        safely("loadLinks") {
            coroutineScope {
                val animeAv = async { safely("AnimeAV") { withTimeoutOrNull(ANIMEAV_TIMEOUT_MS) { loadAnimeAv(listOf(title), episodeNumber, sink, subtitleCallback) } } }
                val animeYt = async { safely("AnimeYT") { loadAnimeYtSources(episodePath.ifBlank { "$mainUrl/tv/$slug/" }, sink, subtitleCallback) } }
                animeAv.await()
                animeYt.await()
            }
        }
        log("FINAL links=${sink.count}")
        return sink.count > 0
    }

    private suspend fun loadAnimeYtSources(pageUrl: String, sink: LinkSink, subtitleCallback: (SubtitleFile) -> Unit) {
        val html = try { app.get(pageUrl, headers = apiHeaders + mapOf("Referer" to "$mainUrl/")).text }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("AnimeYT episode fetch failed $pageUrl: ${e.message}"); return }
        val candidates = linkedSetOf<String>()
        val attrs = Regex("""<(?:iframe|source|video)\b[^>]*(?:src|data-src)\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        for (m in attrs.findAll(html)) {
            val raw = m.groupValues[1].replace("&amp;", "&").trim()
            if (raw.isNotBlank() && !raw.startsWith("data:") && !raw.startsWith("javascript:")) candidates += absoluteAnimeUrl(raw)
        }
        val quotedUrls = Regex("""https?:\\?/\\?/[^"'<>\s]+""", RegexOption.IGNORE_CASE)
        for (m in quotedUrls.findAll(html)) {
            val raw = m.value.replace("\\/", "/").replace("&amp;", "&").trimEnd(',', ';', ')')
            if (raw.contains("mytsumi.com", true) || raw.contains("ok.ru", true) || raw.contains("mega.nz", true) ||
                raw.contains("abyssplayer", true) || raw.contains("bysesukior", true) || raw.contains(".m3u8", true) || raw.contains(".mp4", true)) candidates += raw
        }
        for (candidate in candidates.take(16)) {
            if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true)) {
                try {
                    sink.emit(newExtractorLink(source = name, name = "AnimeYT", url = candidate,
                        type = if (candidate.substringBefore("?").endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = pageUrl
                        quality = getQualityFromName(candidate)
                    }, TAG_ANIMEYT)
                } catch (e: Exception) { log("Direct link failed: ${e.message}") }
            } else runExtractor(candidate, listOf(pageUrl, "$mainUrl/", "https://mytsumi.com/").distinct(), TAG_ANIMEYT, sink, subtitleCallback)
        }
        log("AnimeYT candidates=${candidates.size} url=$pageUrl")
    }
    private fun parseMega(url: String): Pair<String, String>? {
        var handle = ""
        var key = ""

        val part = MEGA_EMBED.find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trimEnd('/')

        if (!part.isNullOrBlank()) {
            val fragment = part.trimStart('!', '#')
            val hashIndex = fragment.indexOf('#')

            if (hashIndex > 0) {
                handle = fragment.substring(0, hashIndex).trim().trimStart('!', '#')
                key = fragment.substring(hashIndex + 1).trim()
            } else {
                val bangIndex = fragment.indexOf('!')
                if (bangIndex > 0) {
                    handle = fragment.substring(0, bangIndex).trim()
                    key = fragment.substring(bangIndex + 1).trim()
                }
            }
        }

        if (handle.isBlank() || key.isBlank()) {
            MEGA_FALLBACK.find(url)?.let {
                handle = it.groupValues[1].trim().trimStart('!', '#')
                key = it.groupValues[2].trim()
            }
        }

        return if (handle.isBlank() || key.isBlank()) null else handle to key
    }

    private suspend fun emitMega(urlVideo: String, qualityValue: Int, sink: LinkSink) {
        val (handle, key) = parseMega(urlVideo) ?: run {
            log("MEGA handle/key vacío")
            return
        }

        // Solo un Mega por episodio
        if (!sink.megaClaimed.compareAndSet(false, true)) {
            log("MEGA duplicado ignorado")
            return
        }

        val localUrl = MegaLocalServer.start(handle, key)

        sink.emit(
            newExtractorLink(
                name = "Mega",
                source = name,
                url = localUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                referer = "$mainUrl/"
                quality = qualityValue
            },
            null
        )
    }

    /* ------------------------------------------------------------
     * Extractores (compartido por Animora y AnimeAV)
     * ------------------------------------------------------------ */

    /**
     * Corre loadExtractor con timeout. Los links se juntan en una cola y se emiten
     * al terminar (o al vencer el timeout), así no se pierde nada de lo ya extraído.
     */
    private suspend fun runExtractor(
        url: String,
        referers: List<String>,
        tag: String,
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
                    log("loadExtractor ERROR referer=$ref url=$url -> ${e.message}")
                }

                if (collected.isNotEmpty()) break
            }
            true
        }

        if (finished == null) log("TIMEOUT extractor $url")

        for (link in collected) sink.emit(link, tag)
    }

    /* ------------------------------------------------------------
     * AnimeAV
     * ------------------------------------------------------------ */

    private suspend fun loadAnimeAv(
        titles: List<String>,
        episodeNumber: Int,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit,
        explicitSlug: String? = null
    ) {
        val animeSlug = explicitSlug ?: resolveAnimeAvSlug(titles.take(2)) ?: run {
            log("AnimeAV: anime no encontrado")
            return
        }

        // Antes: media page (HTML pesado + regex) -> episode page.
        // Ahora vamos directo a la página del episodio; si no existe, devuelve 404.
        val res = app.get(
            "$AV_BASE/media/$animeSlug/$episodeNumber",
            headers = mapOf("User-Agent" to UA)
        )

        if (!res.isSuccessful) {
            log("AnimeAV: episodio HTTP=${res.code}")
            return
        }

        val embeds = AV_EMBEDS.find(res.text)?.groupValues?.get(1) ?: run {
            log("AnimeAV: no se encontró embeds")
            return
        }

        val servers = AV_LANGUAGE_BLOCK
            .findAll(embeds)
            .flatMap { languageMatch ->
                val language = if (languageMatch.groupValues[1] == "SUB") "SUB" else "LAT"
                AV_SERVER.findAll(languageMatch.groupValues[2]).map { match ->
                    Triple(match.groupValues[1], match.groupValues[2], language)
                }.toList()
            }
            .filter { (_, rawUrl, _) -> sink.claimEmbed(rawUrl) }
            .toList()

        log("AnimeAV servidores únicos=${servers.size}")

        // UPNShare ya no bloquea el lanzamiento de los extractores: todo en paralelo.
        coroutineScope {
            servers
                .map { (server, rawUrl, language) ->
                    async {
                        val animeAvTag = "$TAG_ANIMEAV · $language"
                      safely("AnimeAV $language $server") {
                            when (server.lowercase()) {
                                "upnshare" ->
                                    withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                        loadUpnShare(rawUrl, sink, animeAvTag)
                                    }

                                "mp4upload", "pdrain", "voe", "byse" ->
                                    runExtractor(
                                        url = rawUrl,
                                        referers = listOf("$AV_BASE/"),
                                        tag = animeAvTag,
                                        sink = sink,
                                        subtitleCallback = subtitleCallback
                                    )

                                else -> log("AnimeAV: servidor ignorado=$server idioma=$language")
                            }
                        }
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun resolveAnimeAvSlug(titles: List<String>): String? {

        for (t in titles) {
            animeAvSlugCache[normalizeTitle(t)]?.let { return it }
        }

        for (t in titles) {
            val slug = searchAnimeAv(t) ?: continue
            titles.forEach { animeAvSlugCache[normalizeTitle(it)] = slug }
            return slug
        }

        return null
    }

    private suspend fun searchAnimeAv(title: String): String? {

        val encoded = URLEncoder.encode(title, "UTF-8")

        val res = app.get(
            "$AV_BASE/catalogo?search=$encoded",
            headers = mapOf("User-Agent" to UA)
        )

        if (!res.isSuccessful) {
            log("AnimeAV: search HTTP=${res.code}")
            return null
        }

        val best = AV_CARD
            .findAll(res.text)
            .mapNotNull { m ->
                val candidateTitle = m.groupValues[1]
                    .replace(HTML_TAG, "")
                    .replace("&amp;", "&")
                    .trim()
                val href = m.groupValues[2].trim()

                if (candidateTitle.isBlank() || href.isBlank()) null
                else href to animeTitleScore(title, candidateTitle)
            }
            .maxByOrNull { it.second }

        if (best == null || best.second <= 0) {
            log("AnimeAV: sin coincidencia para '$title'")
            return null
        }

        log("AnimeAV: '$title' -> ${best.first} (score=${best.second})")

        return best.first
            .removePrefix("/media/")
            .trim()
            .removeSuffix("/")
    }

    private fun decryptAnimeAv(inputHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(UPN_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(UPN_IV.toByteArray(Charsets.UTF_8))
        )

        val hex = inputHex.trim()
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }

        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private suspend fun loadUpnShare(rawUrl: String, sink: LinkSink, tag: String) {

        val hash = rawUrl.substringAfterLast("#").substringAfter("/")
        if (hash.isBlank()) return

        val res = app.get(
            "$UPN_BASE/api/v1/video?id=$hash",
            headers = mapOf("User-Agent" to UA)
        )

        if (!res.isSuccessful) {
            log("UPNShare HTTP=${res.code}")
            return
        }

        val hlsPath = JSONObject(decryptAnimeAv(res.text))
            .optString("hlsVideoTiktok")

        if (hlsPath.isBlank()) return

        val hlsUrl =
            if (hlsPath.startsWith("http://") || hlsPath.startsWith("https://")) hlsPath
            else "$UPN_BASE/${hlsPath.trimStart('/')}"

        sink.emit(
            newExtractorLink(
                name = "UPNShare",
                source = name,
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$UPN_BASE/"
                quality = Qualities.Unknown.value
            },
            tag
        )
    }
}
