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
private const val DEBUG = true

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
private val AV_EMBEDS = Regex("""embeds\s*:\s*\{([\s\S]*?)\}\s*,\s*downloads""", RegexOption.DOT_MATCHES_ALL)
private val AV_SERVER = Regex("""["']?(?:server|name|provider|host)["']?\s*:\s*["']([^"']+)["']\s*,\s*["']?(?:url|link|embed|src)["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val AV_LANGUAGE_BLOCK = Regex("""["']?(SUB|DUB)["']?\s*:\s*\[([\s\S]*?)\]""", RegexOption.IGNORE_CASE)
private val AV_URL_FIELD = Regex("""["']?(?:url|link|embed|src)["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val AV_SERVER_FIELD = Regex("""["']?(?:server|name|provider|host)["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

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

private fun inferSeasonNumber(value: String): Int {
    val normalized = normalizeTitle(value)
    for (season in 5 downTo 2) {
        val ordinal = when (season) { 2 -> "2nd"; 3 -> "3rd"; else -> season.toString() + "th" }
        if (Regex("""\b(?:season|temporada)\s*$season\b|\b$ordinal\s+season\b|\bseason\s+$season\b""").containsMatchIn(normalized)) return season
    }
    val slug = value.lowercase().substringBefore("?").trimEnd('/')
    return when {
        Regex("""-(?:v|5)$""").containsMatchIn(slug) -> 5
        Regex("""-(?:iv|4)$""").containsMatchIn(slug) -> 4
        Regex("""-(?:iii|3)$""").containsMatchIn(slug) -> 3
        Regex("""-(?:ii|2)$""").containsMatchIn(slug) -> 2
        else -> 1
    }
}

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
    // VOE publica varias representaciones del mismo vídeo (index/master HLS y MP4)
    // con hosts CDN y tokens distintos. El ID estable permite conservar una sola.
    if (url.contains("cloudwindow-route.com", true)) {
        val voeId = Regex("""/engine/(?:hls2-c|hls2|download)/\d+/(?:\d+/)?([a-z0-9]{12,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
        if (!voeId.isNullOrBlank()) return "voe:$voeId"
    }

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
        "$mainUrl/?animes-home=recent" to SECTION_RECENT,
        "$mainUrl/tag/resubido/" to SECTION_RESUB,
        "$mainUrl/tv/" to SECTION_EXPLORE,
        "$mainUrl/pelicula/" to SECTION_MOVIES
    )
    private val apiHeaders: Map<String, String>
        get() = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8", "Referer" to "$mainUrl/")
    private val animeAvSlugCache = ConcurrentHashMap<String, String>()

    // Cache corto para que la sección de recientes conserve el mismo orden al volver
    // a la pantalla y no repita las peticiones mientras las carátulas terminan de cargar.
    @Volatile private var recentCacheAt = 0L
    @Volatile private var recentCacheItems: List<SearchResponse> = emptyList()

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
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(inner)?.value
                ?.let { posterFromImgTag(it, mainUrl) }
            found[key] = newAnimeSearchResponse(title, "$mainUrl/$kind/$slug/", if (kind == "pelicula") TvType.AnimeMovie else TvType.Anime) {
                posterUrl = poster
            }
        }
        return found.values.toList()
    }

    private fun extractPosterFromHtml(html: String, baseUrl: String): String? {
        fun resolveImage(rawValue: String?): String? {
            val raw = rawValue.orEmpty().trim().substringBefore(",").substringBefore(" ")
                .replace("&amp;", "&").replace("\\/", "/")
            if (raw.isBlank() || raw.startsWith("data:", true) || raw.startsWith("javascript:", true) ||
                raw.contains("placeholder", true) || raw.contains("blank.", true) ||
                raw.contains("lazyload", true) || raw.contains("no-image", true) ||
                raw.contains("default-avatar", true) || raw.contains("transparent", true) ||
                raw.contains("logo", true) && !raw.contains("cdn.", true)) return null
            val url = try { URI(baseUrl).resolve(raw).toString() } catch (_: Exception) { raw }
            return url.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }

        // Primero priorizar imágenes marcadas explícitamente como portada/póster.
        val imageTags = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).toList()
        for (imageMatch in imageTags) {
            val tag = imageMatch.value
            val alt = Regex("""\balt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1).orEmpty()
            if (!alt.contains("portada", true) && !alt.contains("póster", true) &&
                !alt.contains("poster", true) && !alt.contains("cover", true)) continue
            for (attr in listOf("data-lazy-src", "data-src", "data-original", "data-image", "data-url", "src")) {
                val raw = Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)
                resolveImage(raw)?.let { return it }
            }
        }

        // Los atributos de meta pueden aparecer en cualquier orden.
        val metaTags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (tagMatch in metaTags) {
            val tag = tagMatch.value
            val key = Regex("""\b(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)?.lowercase().orEmpty()
            if (key !in setOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")) continue
            val raw = Regex("""\bcontent\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)
            resolveImage(raw)?.let { return it }
        }

        for (imageMatch in imageTags) {
            val tag = imageMatch.value
            for (attr in listOf("data-lazy-src", "data-src", "data-original", "data-image", "data-url",
                "data-srcset", "srcset", "src")) {
                val raw = Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)
                resolveImage(raw)?.let { return it }
            }
        }

        val cssImages = Regex("""(?:background-image\s*:\s*url\(|data-bg=["']|data-background=["'])([^)"']+)""", RegexOption.IGNORE_CASE)
        for (match in cssImages.findAll(html)) resolveImage(match.groupValues[1])?.let { return it }
        return null
    }

    private fun posterFromImgTag(img: String, baseUrl: String): String? {
        val attrs = listOf("data-lazy-src", "data-src", "data-original", "data-image", "data-url",
            "data-srcset", "srcset", "src")
        for (attr in attrs) {
            val raw = Regex("""\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(img)?.groupValues?.get(1)?.substringBefore(",")?.trim()?.substringBefore(" ")
                ?.replace("&amp;", "&") ?: continue
            if (raw.isBlank() || raw.startsWith("data:", true) || raw.contains("placeholder", true) ||
                raw.contains("blank.", true) || raw.contains("lazyload", true) ||
                raw.contains("no-image", true) || raw.contains("transparent", true)) continue
            val resolved = try { URI(baseUrl).resolve(raw).toString() } catch (_: Exception) { raw }
            if (resolved.startsWith("http://", true) || resolved.startsWith("https://", true)) return resolved
        }
        return null
    }

    private fun extractPosterNear(html: String, position: Int, baseUrl: String): String? {
        // Buscar solo dentro de una ventana local: no recorrer el documento entero
        // para cada episodio, porque el listado puede contener muchas decenas de enlaces.
        val start = (position - 900).coerceAtLeast(0)
        val end = (position + 700).coerceAtMost(html.length)
        val window = html.substring(start, end)
        val localPosition = position - start
        val before = window.substring(0, localPosition)
        val boundary = maxOf(
            before.lastIndexOf("</article>", ignoreCase = true),
            before.lastIndexOf("</li>", ignoreCase = true),
            before.lastIndexOf("</div>", ignoreCase = true)
        )
        val imageStart = before.lastIndexOf("<img", ignoreCase = true)
        if (imageStart > boundary) {
            val tagEnd = window.indexOf(">", imageStart)
            if (tagEnd > imageStart && tagEnd < localPosition) {
                posterFromImgTag(window.substring(imageStart, tagEnd + 1), baseUrl)?.let { return it }
            }
        }
        val after = window.substring(localPosition)
        val img = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(after)?.value
        return img?.let { posterFromImgTag(it, baseUrl) }
    }

    private suspend fun fillMissingPosters(items: List<SearchResponse>, sourceBase: String): List<SearchResponse> {
        fun needsPoster(item: SearchResponse): Boolean {
            val poster = item.posterUrl.orEmpty()
            return poster.isBlank() || poster.startsWith("data:", true) ||
                poster.contains("placeholder", true) || poster.contains("blank.", true) ||
                poster.contains("lazyload", true) || poster.contains("no-image", true) ||
                poster.contains("default-avatar", true) || poster.contains("transparent", true) ||
                poster.contains("missing", true) || poster.contains("no-thumbnail", true)
        }

        val targets = items.filter { needsPoster(it) || sourceBase == AV_BASE }
            .distinctBy { it.url }
        val recovered = ConcurrentHashMap<String, String>()
        val headers = if (sourceBase == AV_BASE) {
            mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9", "Referer" to "$AV_BASE/")
        } else apiHeaders

        // No pedir la misma ficha una vez por episodio. Limitar concurrencia evita que
        // el sitio bloquee las peticiones cuando se publican varios capítulos juntos.
        for (batch in targets.chunked(5)) {
            coroutineScope {
                batch.map { item -> async {
                    try {
                        val html = withTimeoutOrNull(4500) { app.get(item.url, headers = headers).text } ?: ""
                        val poster = extractPosterFromHtml(html, item.url)
                        if (!poster.isNullOrBlank()) recovered[item.url] = poster
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // La miniatura original se conserva si no se pudo recuperar otra.
                    }
                } }.awaitAll()
            }
        }
        return items.onEach { item ->
            val recoveredPoster = recovered[item.url]
            if (!recoveredPoster.isNullOrBlank() && (needsPoster(item) || sourceBase == AV_BASE)) {
                item.posterUrl = recoveredPoster
            }
        }
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
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.value
                ?.let { posterFromImgTag(it, mainUrl) }
                ?: extractPosterNear(html, m.range.first, mainUrl)
            val item = newAnimeSearchResponse("${titleFromSlug(seriesSlug)} - Episodio $number", "$mainUrl/tv/$seriesSlug/", TvType.Anime) {
                posterUrl = poster
            }
            val previous = found[path]
            if (previous == null) found[path] = item
            else if (previous.posterUrl.isNullOrBlank() && !poster.isNullOrBlank()) previous.posterUrl = poster
        }
        return found.values.toList()
    }

    private data class AnimeYtEpisodeBrowser(val animeId: String, val blocks: Int, val endpoint: String)

    private fun extractAnimeAvCards(html: String): List<SearchResponse> {
        data class Card(var title: String, var poster: String?)
        val found = linkedMapOf<String, Card>()
        val anchors = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchors.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val p = try { URI(AV_BASE).resolve(href).path?.trimEnd('/') ?: href } catch (_: Exception) { href }
            val match = Regex("""^/media/([^/]+)$""", RegexOption.IGNORE_CASE).matchEntire(p) ?: continue
            val slug = match.groupValues[1]
            val inner = m.groupValues[2]
            val img = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(inner)?.value
            val alt = img?.let {
                Regex("""\balt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
            }?.let(::cleanHtml)?.replace(Regex("""^(?:portada|p[oó]ster|poster)\s+(?:de\s+)?""", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()
            val anchorText = cleanHtml(inner).takeIf { it.isNotBlank() && !it.equals(alt, true) }.orEmpty()
            val title = when {
                anchorText.isNotBlank() && !anchorText.startsWith("Ver ", true) -> anchorText
                alt.isNotBlank() -> alt
                else -> titleFromSlug(slug)
            }
            val poster = img?.let { posterFromImgTag(it, AV_BASE) }
                ?: extractPosterNear(html, m.range.first, AV_BASE)
            val previous = found[slug]
            if (previous == null) {
                found[slug] = Card(title, poster)
            } else {
                if (previous.poster.isNullOrBlank() && !poster.isNullOrBlank()) previous.poster = poster
                if ((previous.title.isBlank() || previous.title.startsWith("Portada ", true) ||
                        previous.title.startsWith("Póster ", true) || previous.title.startsWith("Poster ", true)) &&
                    title.isNotBlank()) previous.title = title
            }
        }
        return found.map { (slug, card) ->
            newAnimeSearchResponse("\${card.title} •AnimeAV", "$AV_BASE/media/$slug/", TvType.Anime) {
                posterUrl = card.poster
            }
        }
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
            val poster = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.value
                ?.let { posterFromImgTag(it, AV_BASE) }
                ?: extractPosterNear(html, m.range.first, AV_BASE)
            val item = newAnimeSearchResponse("$title - Episodio $number •AnimeAV", "$AV_BASE/media/$slug/", TvType.Anime) {
                posterUrl = poster
            }
            val key = "$slug/$number"
            val previous = found[key]
            if (previous == null) found[key] = item
            else if (previous.posterUrl.isNullOrBlank() && !poster.isNullOrBlank()) previous.posterUrl = poster
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
        if (matched.isNotEmpty() && matched.take(8).map { it.url }.joinToString("|") != baseline) {
            return matched.sortedByDescending { animeTitleScore(query, it.name) }.take(40)
        }

        // Respaldo equivalente al índice incremental de AnimeYT/Kino: recorrer el catálogo
        // por tandas y detenerse cuando aparezcan coincidencias, sin lanzar 60 peticiones juntas.
        val catalog = mutableListOf<SearchResponse>()
        val homeHtml = try { withTimeoutOrNull(8000) { app.get("$mainUrl/", headers = apiHeaders).text } ?: "" } catch (_: Exception) { "" }
        catalog += extractAnimeCards(homeHtml, "tv")
        catalog += extractAnimeCards(sectionHtml(homeHtml, SECTION_RESUB), "tv")
        for (start in 2..60 step 12) {
            val end = minOf(start + 11, 60)
            val batch = coroutineScope {
                (start..end).map { page -> async {
                    try {
                        val html = withTimeoutOrNull(8000) { app.get("$mainUrl/tv/page/$page/", headers = apiHeaders).text } ?: ""
                        extractAnimeCards(html, "tv")
                    } catch (_: Exception) { emptyList() }
                } }.awaitAll().flatten()
            }
            catalog += batch
            val found = (baseCards + catalog).filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
            if (found.isNotEmpty()) return found.sortedByDescending { animeTitleScore(query, it.name) }.take(40)
        }
        return (baseCards + catalog).filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
            .sortedByDescending { animeTitleScore(query, it.name) }.take(40)
    }

    private suspend fun searchAnimeAvResults(query: String): List<SearchResponse> {
        val aliases = mapOf(
            "link click" to listOf("shiguang dailiren", "shiguang dailiren ii", "shiguang dailiren iii"),
            "linkclick" to listOf("shiguang dailiren", "shiguang dailiren ii", "shiguang dailiren iii"),
            "the daily life of the immortal king" to listOf("xian wang de richang shenghuo")
        )
        val directAliasSlugs = mapOf(
            "link click" to listOf("shiguang-dailiren", "shiguang-dailiren-ii", "shiguang-dailiren-iii"),
            "linkclick" to listOf("shiguang-dailiren", "shiguang-dailiren-ii", "shiguang-dailiren-iii")
        )
        val queryKey = normalizeTitle(query)
        val directSlugs = directAliasSlugs[queryKey] ?: directAliasSlugs[queryKey.replace(" ", "")].orEmpty()
        val headers = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9", "Referer" to "$AV_BASE/")
        // Los alias conocidos se consultan primero: evita lanzar decenas de búsquedas
        // que no filtran el catálogo y después abrir cada ficha por separado.
        if (directSlugs.isNotEmpty()) {
            val direct = coroutineScope {
                directSlugs.map { slug -> async {
                    val url = "$AV_BASE/media/$slug/"
                    try {
                        val response = withTimeoutOrNull(6000) { app.get(url, headers = headers) } ?: return@async null
                        if (!response.isSuccessful) return@async null
                        val html = response.text
                        val pageTitle = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
                            .map { it.value }
                            .firstNotNullOfOrNull { tag ->
                                val key = Regex("""\b(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                    .find(tag)?.groupValues?.get(1)?.lowercase()
                                if (key != "og:title") null else Regex("""\bcontent\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                    .find(tag)?.groupValues?.get(1)?.let(::cleanHtml)
                            }?.replace(Regex("""\s*[-|]\s*AnimeAV1.*$""", RegexOption.IGNORE_CASE), "")
                                ?: titleFromSlug(slug)
                        newAnimeSearchResponse("$pageTitle •AnimeAV", url, TvType.Anime) {
                            posterUrl = extractPosterFromHtml(html, url)
                        }
                    } catch (_: Exception) { null }
                } }.awaitAll().filterNotNull()
            }
            if (direct.isNotEmpty()) return direct
        }

        val queryForms = linkedSetOf(query.trim())
        aliases[queryKey.replace(" ", "")]?.let(queryForms::addAll)
        aliases[queryKey]?.let(queryForms::addAll)
        val urls = queryForms.flatMap { form ->
            val encoded = URLEncoder.encode(form, "UTF-8")
            listOf("$AV_BASE/catalogo?search=$encoded", "$AV_BASE/catalogo?q=$encoded")
        }.distinct()
        val results = coroutineScope {
            urls.map { url -> async {
                try {
                    val html = withTimeoutOrNull(6500) { app.get(url, headers = headers).text } ?: ""
                    extractAnimeAvCards(html)
                } catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        val aliasesForQuery = mapOf(
            "link click" to setOf("shiguang dailiren", "shiguang dailiren ii", "shiguang dailiren iii"),
            "linkclick" to setOf("shiguang dailiren", "shiguang dailiren ii", "shiguang dailiren iii"),
            "the daily life of the immortal king" to setOf("xian wang de richang shenghuo")
        )
        val aliasTitles = aliasesForQuery[queryKey] ?: aliasesForQuery[queryKey.replace(" ", "")].orEmpty()
        val matched = results.filter { result ->
            isRelevantSearchTitle(query, result.name) ||
                normalizeTitle(result.name.removeSuffix(" •AnimeAV")).let { it in aliasTitles || aliasTitles.any(it::contains) }
        }.distinctBy { it.url }
        if (matched.isNotEmpty()) {
            val ordered = matched.sortedByDescending { result ->
                val title = normalizeTitle(result.name.removeSuffix(" •AnimeAV"))
                if (title in aliasTitles) 9000 else animeTitleScore(query, result.name)
            }.take(40)
            return ordered
        }

        // El catálogo de AnimeAV está paginado; si el filtro del sitio no se refleja en el HTML,
        // recorrer las páginas disponibles para no limitar la búsqueda a los primeros resultados.
        val pages = listOf("$AV_BASE/catalogo", "$AV_BASE/") + (2..10).map { "$AV_BASE/catalogo?page=$it" }
        val fallback = coroutineScope {
            pages.map { url -> async {
                try {
                    val html = withTimeoutOrNull(9000) { app.get(url, headers = headers).text } ?: ""
                    extractAnimeAvCards(html)
                } catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
        }
        val fallbackMatches = fallback.filter { isRelevantSearchTitle(query, it.name) }.distinctBy { it.url }
            .sortedByDescending { animeTitleScore(query, it.name) }.take(40)
        return fallbackMatches
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
            val seasonNumber = inferSeasonNumber("$title $slug")
            items.putIfAbsent(epPath, number to newEpisode("$slug|$number|$safeTitle|$epPath") {
                // Evitar textos de botones como "Ver desde el episodio 1".
                name = "EP $number T$seasonNumber"
                episode = number; season = seasonNumber; posterUrl = poster
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
            val now = System.currentTimeMillis()
            val cached = recentCacheItems
            if (cached.isNotEmpty() && now - recentCacheAt < HOME_CACHE_MS) {
                return newHomePageResponse(request.name, cached)
            }

            val (ytHtml, avHtml) = coroutineScope {
                val yt = async { try { app.get("$mainUrl/", headers = apiHeaders).text } catch (_: Exception) { "" } }
                val av = async { try { app.get("$AV_BASE/", headers = mapOf("User-Agent" to UA, "Accept-Language" to "es-ES,es;q=0.9", "Referer" to "$AV_BASE/")).text } catch (_: Exception) { "" } }
                yt.await() to av.await()
            }

            // Usar únicamente las carátulas incluidas en las páginas de inicio.
            // No abrir fichas individuales: evita peticiones extra y reduce el retraso visual.
            val ytItems = extractLatestEpisodes(sectionHtml(ytHtml, SECTION_RECENT).ifBlank { ytHtml })
            val avItems = extractAnimeAvLatestEpisodes(avHtml)
            // AnimeYT primero para conservar la fuente principal al inicio; AnimeAV se agrega después.
            val items = (ytItems + avItems).distinctBy { it.url + "|" + it.name }

            if (items.isNotEmpty()) {
                recentCacheItems = items
                recentCacheAt = now
            }
            return newHomePageResponse(request.name, items)
        }
        val url = when (request.name) {
            SECTION_RESUB -> if (page <= 1) "$mainUrl/tag/resubido/" else "$mainUrl/tag/resubido/page/$page/"
            SECTION_EXPLORE -> if (page <= 1) "$mainUrl/tv/" else "$mainUrl/tv/page/$page/"
            SECTION_MOVIES -> if (page <= 1) "$mainUrl/pelicula/" else "$mainUrl/pelicula/page/$page/"
            else -> "$mainUrl/"
        }
        val html = try { app.get(url, headers = apiHeaders).text } catch (_: Exception) { "" }
        val items = when (request.name) {
            // La página /tag/resubido/ no siempre contiene un encabezado "Resubidos";
            // recortar por heading vaciaba la categoría. Parsear la página completa.
            SECTION_RESUB -> extractAnimeCards(html, "tv")
            SECTION_MOVIES -> extractAnimeCards(html, "pelicula")
            SECTION_EXPLORE -> extractAnimeCards(html, "tv")
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && request.name in setOf(SECTION_RESUB, SECTION_EXPLORE, SECTION_MOVIES))
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
        fun metaContent(key: String): String? = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.firstNotNullOfOrNull { tag ->
                val metaKey = Regex("""\b(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)?.lowercase()
                if (metaKey != key.lowercase()) null else Regex("""\bcontent\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)
            }
        val title = metaContent("og:title")?.let(::cleanHtml)
            ?.replace(Regex("""\s*[-|]\s*AnimeAV1.*$""", RegexOption.IGNORE_CASE), "")
            ?: Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::cleanHtml)
            ?: titleFromSlug(slug)
        val poster = extractPosterFromHtml(html, canonical)
        val plot = Regex("""<meta[^>]+name=["']description["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(::cleanHtml)
        val seasonNumber = inferSeasonNumber("$title $slug")
        val episodes = linkedMapOf<Int, Episode>()
        val anchors = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val safeTitle = title.replace("|", " ")
        for (m in anchors.findAll(html)) {
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val epPath = try { URI(AV_BASE).resolve(href).path ?: href } catch (_: Exception) { href }
            val epMatch = Regex("""^/media/([^/]+)/([0-9]+)/?$""", RegexOption.IGNORE_CASE).matchEntire(epPath) ?: continue
            if (epMatch.groupValues[1] != slug) continue
            val number = epMatch.groupValues[2].toIntOrNull() ?: continue
            // El sitio suele poner "Ver [título] [n]" en el enlace; no usarlo como
            // nombre del capítulo porque CloudStream puede interpretarlo como una acción.
            val epTitle = "Episodio $number T$seasonNumber"
            episodes.putIfAbsent(number, newEpisode("av|$slug|$number|$safeTitle|$epPath") {
                name = epTitle; episode = number; season = seasonNumber; posterUrl = poster
            })
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, canonical, TvType.Anime, episodes.toSortedMap().values.toList()) {
            posterUrl = poster; this.plot = plot
            seasonNames = listOf(SeasonData(season = seasonNumber, name = "Temporada $seasonNumber", displaySeason = seasonNumber))
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
            // No convertir los bloques del sitio en temporadas: todos los capítulos
            // pertenecen a la temporada 1 y CloudStream agrupa la lista por rangos.
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

    private fun unpackPackedScripts(input: String): String {
        val output = StringBuilder()
        val packed = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\(['"]([\s\S]*?)['"],\s*(\d+),\s*(\d+),\s*['"]([\s\S]*?)['"]\.split\(['"]\|['"]\)""",
            RegexOption.IGNORE_CASE
        )
        for (match in packed.findAll(input)) {
            try {
                val payload = match.groupValues[1].replace("\\'", "'")
                val radix = match.groupValues[2].toInt()
                var count = match.groupValues[3].toInt()
                val words = match.groupValues[4].split("|")
                if (radix < 2 || radix > 62 || count > 10000) continue
                fun encode(n: Int): String {
                    if (n < radix) return if (n > 35) (n + 29).toChar().toString() else n.toString(radix)
                    return encode(n / radix) + if (n % radix > 35) (n % radix + 29).toChar() else (n % radix).toString(radix)
                }
                val dictionary = HashMap<String, String>()
                while (count > 0) {
                    count--
                    dictionary[encode(count)] = words.getOrNull(count).orEmpty().ifBlank { encode(count) }
                }
                val decoded = Regex("""\b\w+\b""").replace(payload) { token -> dictionary[token.value] ?: token.value }
                output.append('\n').append(decoded)
            } catch (_: Exception) { }
        }
        return output.toString()
    }

    private fun collectSourceUrls(html: String, baseUrl: String): LinkedHashSet<String> {
        val found = linkedSetOf<String>()
        val source = (html + "\n" + unpackPackedScripts(html))
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&#038;", "&")

        fun add(value: String?) {
            val raw = value.orEmpty().trim().trimEnd(',', ';', ')', ']', '\\')
            if (raw.isBlank() || raw.startsWith("data:", true) ||
                raw.startsWith("javascript:", true) || raw.startsWith("about:", true)) return
            val resolved = try { URI(baseUrl).resolve(raw).toString() } catch (_: Exception) { raw }
            if (!resolved.startsWith("http://", true) && !resolved.startsWith("https://", true)) return
            val uri = try { URI(resolved) } catch (_: Exception) { return }
            val host = uri.host.orEmpty().lowercase().removePrefix("www.")
            val path = uri.path.orEmpty()
            val lowerPath = path.lowercase()
            val media = Regex("""\.(?:m3u8|mp4|mpd|webm)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)
            val mytsumiContainer = host == "mytsumi.com" &&
                ((lowerPath == "/container.php" && uri.rawQuery.orEmpty().contains(Regex("""(?:^|&)id="""))) ||
                    lowerPath == "/multiplayer/options.php")
            val okruPlayer = host == "ok.ru" && Regex("""/videoembed/\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)
            if (host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") || host == "youtu.be") return
            val knownProvider = host == "archive.org" || host.endsWith(".archive.org") ||
                host == "ok.ru" || host.endsWith(".ok.ru") ||
                host.contains("bysekoze") || host.contains("bysesukior") || host == "n1mwq.org" ||
                host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") ||
                host == "mega.nz" || host.endsWith(".mega.nz") || host == "mega.io" || host.endsWith(".mega.io") ||
                host == "fireload.com" || host.endsWith(".fireload.com")
            val asset = Regex("""\.(?:png|jpe?g|gif|webp|svg|css|js|ico|woff2?)(?:$|\?)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(uri.toString())
            val embedPath = Regex("""(?:embed|/e/|/v/|player|video|iframe|/d/|/file/)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(path)
            val adHost = Regex("""(?:doubleclick|googlesyndication|googleadservices|adservice)""", RegexOption.IGNORE_CASE).containsMatchIn(host)
            if (adHost || asset) return
            when {
                media -> found += resolved
                mytsumiContainer -> found += resolved
                okruPlayer -> found += resolved
                knownProvider -> found += resolved
                host != "animeyt.cc" && !host.endsWith(".animeyt.cc") && embedPath -> found += resolved
            }
        }

        val tagRegex = Regex("""<(?:iframe|a|button|source|video)\b[^>]*>""", RegexOption.IGNORE_CASE)
        val attrNames = listOf("src", "data-src", "data-lazy-src", "href", "data-href", "data-url",
            "data-player-url", "data-video", "data-embed")
        for (m in tagRegex.findAll(source)) {
            for (attr in attrNames) {
                val a = Regex("""\b$attr\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(m.value) ?: continue
                add(a.groupValues.drop(1).firstOrNull { it.isNotBlank() })
            }
        }
        val urlRegex = Regex("""https?:\\?/\\?/[^\s"'<>\\)]+""", RegexOption.IGNORE_CASE)
        for (m in urlRegex.findAll(source)) add(m.value)
        val optionsRegex = Regex("""https?:\\?/\\?/(?:www\\?\.)?mytsumi\.com\\?/multiplayer\\?/options\.php[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
        for (m in optionsRegex.findAll(source)) add(m.value.replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true))
        return found
    }


    private val byseUa = "Mozilla/5.0 (Linux; Android 6.0.1; SM-G5510 Build/MMB29M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.125 Mobile Safari/537.36 SamsungBrowser/7.2"

    private fun byseB64Decode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        return java.util.Base64.getDecoder().decode(normalized + "=".repeat((4 - normalized.length % 4) % 4))
    }

    private fun byseB64Encode(value: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)

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
                log("Byse PoW resuelto bits=" + difficulty + " intentos=" + (counter + 1))
                return counter.toString()
            }
            counter++
            if (counter % 256 == 0) delay(1)
        }
        log("Byse PoW agotado bits=" + difficulty + " intentos=" + counter)
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

    private suspend fun bysePostJson(url: String, headers: Map<String, String>, payload: JSONObject, timeoutSeconds: Int = 15): JSONObject {
        val response = app.post(
            url,
            headers = headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            json = JsonAsString(JSONObject().put("json", payload).toString()),
            timeout = timeoutSeconds
        )
        if (response.code !in 200..299) error("HTTP " + response.code + " " + URI(url).path)
        return JSONObject(response.text)
    }

    /** Resolución manual de Bysekoze siguiendo el flujo de AnimeYT/Kino. */
    private suspend fun resolveBysekoze(embed: String, sourcePage: String, sink: LinkSink) {
        val input = runCatching { URI(embed) }.getOrNull() ?: return
        val code = Regex("""/(?:e|embed|sovm9|boh)/([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(input.path.orEmpty())?.groupValues?.getOrNull(1) ?: return
        val initialHeaders = mapOf("User-Agent" to byseUa, "Referer" to sourcePage)
        try {
            val page = app.get(embed, headers = initialHeaders, timeout = 9)
            if (page.code !in 200..399) error("embed HTTP " + page.code)
            val pageUrl = page.url.ifBlank { embed }
            val origin = runCatching { URI(pageUrl).let { it.scheme + "://" + it.authority } }
                .getOrDefault(input.scheme + "://" + input.authority)
            val pagePath = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault(input.path.orEmpty())
            val embedMode = Regex("""/(?:e|embed|sovm9|boh)/""", RegexOption.IGNORE_CASE).containsMatchIn(pagePath)
            val prefix = origin + "/api/videos/" + code + if (embedMode) "/embed" else ""
            val headers = initialHeaders + mapOf(
                "Origin" to origin,
                "Referer" to pageUrl,
                "X-Embed-Origin" to "https://mytsumi.com",
                "X-Embed-Referer" to "https://mytsumi.com/",
                "X-Embed-Parent" to embed
            )
            val settingsResponse = app.get(prefix + "/settings", headers = headers, timeout = 12)
            if (settingsResponse.code !in 200..299) error("settings HTTP " + settingsResponse.code)
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
                val public = pair.public as java.security.interfaces.ECPublicKey
                val signer = java.security.Signature.getInstance("SHA256withECDSA")
                signer.initSign(pair.private)
                signer.update(nonce.toByteArray(Charsets.UTF_8))
                val signature = byseB64Encode(byseDerSignatureToP1363(signer.sign()))
                val random = java.security.SecureRandom().nextInt().toUInt().toLong() / 4294967295.0
                fun fpHash(n: Double): String = byseB64Encode(byseSha256(n.toString()))
                val client = JSONObject()
                    .put("user_agent", byseUa).put("architecture", "arm").put("bitness", "32")
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
                    .put("x", byseB64Encode(byseFixedUnsigned(public.w.affineX)))
                    .put("y", byseB64Encode(byseFixedUnsigned(public.w.affineY)))
                    .put("ext", true).put("key_ops", JSONArray().put("verify"))
                val attest = bysePostJson(origin + "/api/videos/access/attest", headers,
                    JSONObject().put("viewer_id", "").put("device_id", "").put("challenge_id", challengeId)
                        .put("nonce", nonce).put("signature", signature).put("public_key", jwk)
                        .put("client", client).put("storage", JSONObject())
                        .put("attributes", JSONObject().put("entropy", "high")), 20)
                if (attest.optString("token").isBlank() || attest.optString("viewer_id").isBlank() || attest.optString("device_id").isBlank()) {
                    error("atestación incompleta")
                }
                fingerprint = JSONObject().put("token", attest.optString("token"))
                    .put("viewer_id", attest.optString("viewer_id")).put("device_id", attest.optString("device_id"))
                if (attest.has("confidence")) fingerprint.put("confidence", attest.opt("confidence"))
                val captcha = bysePostJson(prefix + "/captcha", headers, JSONObject().put("fingerprint", fingerprint), 15)
                val powNonce = captcha.optString("pow_nonce")
                val powToken = captcha.optString("pow_token")
                if (powNonce.isBlank() || powToken.isBlank()) error("desafío PoW incompleto")
                val solution = solveBysePow(powNonce, captcha.optInt("pow_difficulty", -1)) ?: error("PoW agotó el tiempo")
                val verified = bysePostJson(prefix + "/captcha/verify", headers,
                    JSONObject().put("pow_token", powToken).put("solution", solution).put("fingerprint", fingerprint), 15)
                captchaToken = verified.optString("token")
                if (captchaToken.isBlank()) error("verificación CAPTCHA sin token")
            }
            val playbackHeaders = headers + if (captchaToken.isBlank()) emptyMap() else mapOf("X-Captcha-Token" to captchaToken)
            val playback = bysePostJson(prefix + "/playback", playbackHeaders,
                if (settings.optBoolean("captcha_required", false)) JSONObject().put("fingerprint", fingerprint) else JSONObject(), 20)
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
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, byseB64Decode(encrypted.optString("iv"))))
                    sources = JSONObject(String(cipher.doFinal(byseB64Decode(encrypted.optString("payload"))), Charsets.UTF_8)).optJSONArray("sources")
                }
            }
            if (sources == null || sources.length() == 0) error("playback sin fuentes")
            val choices = (0 until sources.length()).mapNotNull { i ->
                val item = sources.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                if (url.isBlank()) null else Pair(item.optString("label", item.optString("name", "Bysekoze")), url)
            }.sortedByDescending { Regex("""(\d{3,4})\s*[pP]?""").find(it.first)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 }
            val chosen = choices.firstOrNull { it.second.startsWith("http", true) } ?: choices.firstOrNull()
                ?: error("fuentes sin URL")
            val streamUrl = URI(origin).resolve(chosen.second).toString()
            val streamHeaders = mapOf(
                "User-Agent" to byseUa, "Referer" to pageUrl, "Origin" to origin,
                "X-Embed-Origin" to "https://mytsumi.com", "X-Embed-Referer" to "https://mytsumi.com/",
                "X-Embed-Parent" to embed
            )
            val isHls = Regex("""\.m3u8(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(streamUrl)
            val isDash = Regex("""\.mpd(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(streamUrl)
            sink.emit(newExtractorLink(
                source = name,
                name = ("Bysekoze · " + chosen.first).take(48),
                url = streamUrl,
                type = if (isHls || isDash) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = pageUrl
                quality = getQualityFromName(chosen.first)
                headers = streamHeaders
            }, TAG_ANIMEYT)
            log("Byse resuelto host=" + URI(streamUrl).host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Byse resolver ERROR " + input.host + ": " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private suspend fun loadAnimeYtSources(
        pageUrl: String,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val requestedUrl = if (pageUrl.startsWith("http://", true) || pageUrl.startsWith("https://", true)) pageUrl
        else try { URI(mainUrl).resolve(if (pageUrl.startsWith("/")) pageUrl else "/" + pageUrl).toString() }
        catch (_: Exception) { mainUrl + "/" + pageUrl.trimStart('/') }
        var sourcePage = requestedUrl
        var html = try {
            app.get(requestedUrl, headers = apiHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("AnimeYT page fetch failed " + requestedUrl + ": " + e.message); return }

        var candidates = collectSourceUrls(html, sourcePage)

        // Igual que Kino: las fichas de películas pueden no tener el reproductor.
        // El enlace real está en una página independiente /<id>/anime/<slug>/.
        // No depender de candidates.isEmpty(): la ficha puede contener enlaces ajenos
        // (publicidad u otros iframes) y aun así necesitar seguir la ruta de reproducción.
        val playbackPaths = linkedSetOf<String>()
        val decodedPage = html
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("&#x2f;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
            .replace("&#x3d;", "=", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#039;", "'", ignoreCase = true)
            .replace("&#8217;", "'", ignoreCase = true)
            .replace("&amp;", "&")
        val pathAttrRegex = Regex("""\b(?:href|data-href|data-url|data-src)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        for (m in pathAttrRegex.findAll(decodedPage)) {
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            val absolute = runCatching { URI(sourcePage).resolve(raw).toString() }.getOrDefault(raw)
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")
            if (Regex("""^/\d+/anime/[^/?#]+/?$""", RegexOption.IGNORE_CASE).matches(path)) {
                playbackPaths += URI(absolute).let { it.scheme + "://" + it.authority + path.trimEnd('/') + "/" }
            }
        }
        val rawPathRegex = Regex("""(?:https?:\\?/\\?/[^\\s"'<>\\]+)?(/\d+/anime/[^\s"'<>\\?#&]+)""", RegexOption.IGNORE_CASE)
        for (m in rawPathRegex.findAll(decodedPage)) {
            val path = m.groupValues[1].trimEnd('/', '\\', ',', '.', ';', ')')
            if (Regex("""^/\d+/anime/[^/]+$""", RegexOption.IGNORE_CASE).matches(path)) {
                playbackPaths += "$mainUrl$path/"
            }
        }
        val percentDecodedPage = runCatching { URLDecoder.decode(decodedPage, "UTF-8") }.getOrDefault(decodedPage)
        for (m in rawPathRegex.findAll(percentDecodedPage)) {
            val path = m.groupValues[1].trimEnd('/', '\\', ',', '.', ';', ')')
            if (Regex("""^/\d+/anime/[^/]+$""", RegexOption.IGNORE_CASE).matches(path)) playbackPaths += "$mainUrl$path/"
        }
        var playbackFetched = 0
        for (playbackUrl in playbackPaths.take(5)) {
            if (playbackUrl.trimEnd('/') == requestedUrl.trimEnd('/')) continue
            val playbackHtml = try {
                app.get(playbackUrl, headers = apiHeaders + mapOf("Referer" to sourcePage)).text
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log("AnimeYT playback page failed " + playbackUrl + ": " + e.message)
                continue
            }
            playbackFetched++
            val found = collectSourceUrls(playbackHtml, playbackUrl)
            if (found.isNotEmpty()) {
                val usefulFound = found.filterNot { candidate ->
                    val host = runCatching { URI(candidate).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
                    host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") || host == "youtu.be"
                }
                val hasRealContainer = usefulFound.any {
                    it.contains("mytsumi.com/container.php", true) ||
                        it.contains("mytsumi.com/multiplayer/options.php", true) ||
                        it.contains("mytsumi.com/multiplayer/contenedor.php", true)
                }
                if (hasRealContainer) {
                    candidates = LinkedHashSet(usefulFound)
                    sourcePage = playbackUrl
                    html = playbackHtml
                } else if (usefulFound.isNotEmpty()) {
                    candidates.addAll(usefulFound)
                    sourcePage = playbackUrl
                }
                log("AnimeYT playback page " + playbackUrl + " sources=" + found.size +
                    " useful=" + usefulFound.size + " mytsumi=" + hasRealContainer)
            }
        }
        if (playbackFetched > 0) {
            log("AnimeYT playback fallback pages=" + playbackFetched + " candidates=" + candidates.size)
        }
        fun isContainer(url: String): Boolean =
            url.contains("mytsumi.com/container.php", true) ||
                url.contains("mytsumi.com/multiplayer/contenedor.php", true) ||
                url.contains("mytsumi.com/multiplayer/options.php", true)
        fun absolute(base: String, value: String): String = try {
            URI(base).resolve(value.replace("\\/", "/").replace("&amp;", "&")).toString()
        } catch (_: Exception) { value }

        val containers = candidates.filter(::isContainer).toMutableSet()
        for (container in containers.toList()) {
            if (!container.contains("/multiplayer/options.php", true)) continue
            val optionsHtml = try { app.get(container, headers = apiHeaders + mapOf("Referer" to sourcePage)).text }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("MyTsumi options failed: " + e.message); "" }
            val encoded = Regex("""(?:const|let|var)\s+azakuEncodedURL\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE).find(optionsHtml)?.groupValues?.get(1)
            if (!encoded.isNullOrBlank()) {
                val decoded = try {
                    val normalized = encoded.replace('-', '+').replace('_', '/')
                    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
                    String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
                } catch (_: Exception) { "" }
                if (decoded.startsWith("http", true) && decoded.contains("mytsumi.com/multiplayer/contenedor.php", true)) containers += decoded
            }
        }

        val resolvedCandidates = linkedSetOf<String>()
        resolvedCandidates += candidates.filterNot(::isContainer)
        var scriptsFetched = 0
        for (container in containers) {
            val openUrl = if (Regex("""[?&]open=1(?:&|$)""").containsMatchIn(container)) container
                else container + if (container.contains("?")) "&open=1" else "?open=1"
            val containerHtml = try { app.get(openUrl, headers = apiHeaders + mapOf("Referer" to sourcePage)).text }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("MyTsumi container failed: " + e.message); "" }

            resolvedCandidates += collectSourceUrls(containerHtml, openUrl).filterNot(::isContainer)
            fun addVideoTabs(source: String, base: String) {
                val tabs = Regex("""(?:const|let|var)\s+videoTabs\s*=\s*(\[[\s\S]*?\])\s*;""",
                    RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1) ?: return
                try {
                    val array = JSONArray(tabs)
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val raw = item.optString("url")
                        if (raw.isNotBlank()) {
                            val resolved = absolute(base, raw)
                            if (!isContainer(resolved)) resolvedCandidates += resolved
                        }
                    }
                } catch (e: Exception) { log("MyTsumi videoTabs parse failed: " + e.message) }
            }
            addVideoTabs(containerHtml, openUrl)

            // Igual que Kino: inspeccionar hasta tres scripts propios de MyTsumi,
            // porque algunos proveedores solo aparecen en el JavaScript externo.
            val scriptUrls = Regex("""<script\b[^>]*\bsrc\s*=\s*(?:"([^"]+)"|'([^']+)')[^>]*>""",
                RegexOption.IGNORE_CASE).findAll(containerHtml)
                .mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() } }
                .map { absolute(openUrl, it) }
                .filter {
                    val host = runCatching { URI(it).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
                    host == "mytsumi.com" || host.endsWith(".mytsumi.com")
                }.distinct().take(3).toList()
            for (scriptUrl in scriptUrls) {
                if (scriptsFetched >= 3) break
                scriptsFetched++
                val script = try { app.get(scriptUrl, headers = apiHeaders + mapOf("Referer" to openUrl)).text }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { log("MyTsumi script failed " + scriptUrl + ": " + e.message); continue }
                resolvedCandidates += collectSourceUrls(script, openUrl).filterNot(::isContainer)
                addVideoTabs(script, openUrl)
            }
            log("MyTsumi container " + openUrl.substringBefore('?') + " scripts=" + scriptUrls.size + " resolved=" + resolvedCandidates.size)
        }

        // Fireload suele presentar páginas HTML de descarga con una falsa extensión .mp4.
        val usableCandidates = resolvedCandidates.filterNot { candidate ->
            val uri = runCatching { URI(candidate) }.getOrNull()
            val host = uri?.host.orEmpty().lowercase().removePrefix("www.")
            host == "fireload.com" && Regex("""/[0-9a-f]{12,}/""", RegexOption.IGNORE_CASE).containsMatchIn(uri?.path.orEmpty())
        }.distinct()

        log("AnimeYT source candidates=" + usableCandidates.joinToString(" || ") { candidate ->
            val uri = runCatching { URI(candidate) }.getOrNull()
            (uri?.host.orEmpty() + uri?.path.orEmpty()).take(180)
        })
        for (candidate in usableCandidates) {
            safely("AnimeYT candidate") {
                when {
                    runCatching { URI(candidate).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
                        .let { it.contains("bysekoze.com") || it.contains("bysesukior.com") || it == "n1mwq.org" || it.endsWith(".n1mwq.org") } -> {
                        resolveBysekoze(candidate, sourcePage, sink)
                    }
                    candidate.contains("mega.nz/", true) || candidate.contains("mega.io/", true) -> {
                        if (parseMega(candidate) != null) emitMega(candidate, getQualityFromName(candidate), sink)
                        else runExtractor(candidate, listOf(sourcePage, "$mainUrl/", "https://mytsumi.com/").distinct(), TAG_ANIMEYT, sink, subtitleCallback)
                    }
                    candidate.substringBefore("?").matches(Regex(""".*\.(?:m3u8|mp4|mpd|webm)$""", RegexOption.IGNORE_CASE)) -> {
                        val providerName = when {
                            candidate.contains("archive.org", true) -> "Archive.org"
                            candidate.contains("mediafire.com", true) -> "MediaFire"
                            candidate.contains("ok.ru", true) -> "OK.ru"
                            candidate.contains("abyssplayer.com", true) -> "AbyssPlayer"
                            candidate.contains("bysekoze", true) || candidate.contains("bysesukior", true) -> "Byse"
                            candidate.contains("fireload.com", true) -> "Fireload"
                            candidate.contains("mega.nz", true) || candidate.contains("mega.io", true) -> "MEGA"
                            else -> runCatching { URI(candidate).host.orEmpty().removePrefix("www.") }
                                .getOrDefault("AnimeYT").ifBlank { "AnimeYT" }
                        }
                        sink.emit(newExtractorLink(source = name, name = providerName, url = candidate,
                            type = if (candidate.substringBefore("?").endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            referer = sourcePage
                            quality = getQualityFromName(candidate)
                        }, TAG_ANIMEYT)
                    }
                    else -> runExtractor(candidate, listOf(sourcePage, "$mainUrl/", "https://mytsumi.com/").distinct(), TAG_ANIMEYT, sink, subtitleCallback)
                }
            }
        }
        log("AnimeYT pageCandidates=" + candidates.size + " containers=" + containers.size + " resolved=" + resolvedCandidates.size +
            " usable=" + usableCandidates.size + " scriptsFetched=" + scriptsFetched + " url=" + sourcePage)
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

        val hasVoeHls = collected.any {
            it.name.contains("voe", true) &&
                !it.name.contains("mp4", true) &&
                (it.url.contains(".m3u8", true) || it.type == ExtractorLinkType.M3U8)
        }
        for (link in collected) {
            // Si VOE entrega HLS y MP4 del mismo reproductor, conservar HLS.
            if (tag.contains(TAG_ANIMEAV, true) && hasVoeHls &&
                link.name.contains("voe mp4", true)) {
                log("VOE MP4 duplicado ignorado; se conserva HLS")
                continue
            }
            sink.emit(link, tag)
        }
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

        // Si cambió el envoltorio JS de "embeds", buscar SUB/DUB en toda la página
        // en vez de descartar el episodio antes de inspeccionar sus servidores.
        val embeds = AV_EMBEDS.find(res.text)?.groupValues?.get(1) ?: res.text

        val servers = linkedSetOf<Triple<String, String, String>>()
        fun normalizeServerUrl(value: String): String {
            val decoded = value.replace("\\/", "/").replace("\\u0026", "&", true)
                .replace("&amp;", "&").trim()
            return if (decoded.startsWith("//")) "https:$decoded" else decoded
        }
        fun addServer(server: String, value: String, language: String) {
            val url = normalizeServerUrl(value)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
            if (url.contains("animeav1.com", true) || url.contains("zilla-networks.com", true) ||
                url.contains("google", true) && url.contains("analytics", true)) return
            servers += Triple(server.trim(), url, language)
        }
        // Hay versiones del reproductor que serializan los servidores como SUB: { HLS: "url" }
        // en lugar de SUB: [{ server: "HLS", url: "..." }]. Leer también esos pares clave/URL.
        val namedUrl = Regex(
            """["']?(HLS|UPNShare|Voe|Byse|Mega|MP4Upload|Archive\.org|OK\.ru|Bysekoze|AbyssPlayer)["']?\s*:\s*["'](https?:\\?/\\?/[^"']+|//[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        for (languageMatch in AV_LANGUAGE_BLOCK.findAll(embeds)) {
            val language = if (languageMatch.groupValues[1].equals("SUB", true)) "SUB" else "LAT"
            for (match in namedUrl.findAll(languageMatch.groupValues[2])) {
                addServer(match.groupValues[1], match.groupValues[2], language)
            }
        }

        // Algunas fichas (como Shiguang Dailiren III) exponen los botones del reproductor
        // fuera de los arrays SUB/DUB, o dentro de un objeto con estructura distinta.
        // Leer los pares nombre/URL globalmente y deducir el idioma por el bloque precedente.
        for (match in namedUrl.findAll(embeds)) {
            val before = embeds.substring(0, match.range.first)
            val dubAt = before.lastIndexOf("DUB", ignoreCase = true)
            val subAt = before.lastIndexOf("SUB", ignoreCase = true)
            val language = if (dubAt > subAt) "LAT" else "SUB"
            addServer(match.groupValues[1], match.groupValues[2], language)
        }
        for (languageMatch in AV_LANGUAGE_BLOCK.findAll(embeds)) {
            val language = if (languageMatch.groupValues[1].equals("SUB", true)) "SUB" else "LAT"
            val block = languageMatch.groupValues[2]
            for (match in AV_SERVER.findAll(block)) {
                val rawUrl = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&", true)
                servers += Triple(match.groupValues[1], rawUrl, language)
            }
            for (obj in Regex("""\{[^{}]{0,1800}\}""", RegexOption.DOT_MATCHES_ALL).findAll(block)) {
                val server = AV_SERVER_FIELD.find(obj.value)?.groupValues?.get(1) ?: continue
                val rawUrl = AV_URL_FIELD.find(obj.value)?.groupValues?.get(1) ?: continue
                addServer(server, rawUrl, language)
            }
        }
        if (servers.isEmpty()) {
            for (obj in Regex("""\{[^{}]{0,1800}\}""", RegexOption.DOT_MATCHES_ALL).findAll(embeds)) {
                val server = AV_SERVER_FIELD.find(obj.value)?.groupValues?.get(1) ?: continue
                val rawUrl = AV_URL_FIELD.find(obj.value)?.groupValues?.get(1) ?: continue
                val dubAt = embeds.lastIndexOf("DUB", obj.range.first, true)
                val subAt = embeds.lastIndexOf("SUB", obj.range.first, true)
                val language = if (dubAt > subAt) "LAT" else "SUB"
                addServer(server, rawUrl, language)
            }
        }
        val uniqueServers = servers.filter { (_, rawUrl, _) -> sink.claimEmbed(rawUrl) }

        log("AnimeAV servidores únicos=" + uniqueServers.size)

        // UPNShare ya no bloquea el lanzamiento de los extractores: todo en paralelo.
        coroutineScope {
            uniqueServers
                .map { (server, rawUrl, language) ->
                    async {
                        val animeAvTag = "$TAG_ANIMEAV · $language"
                      safely("AnimeAV $language $server") {
                            when (server.lowercase()) {
                                "upnshare" ->
                                    withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                        loadUpnShare(rawUrl, sink, animeAvTag)
                                    }

                                else ->
                                    // No descartar servidores nuevos por su nombre: delegarlos al extractor.
                                    runExtractor(
                                        url = rawUrl,
                                        referers = listOf("$AV_BASE/", "$AV_BASE/media/$animeSlug/$episodeNumber"),
                                        tag = animeAvTag,
                                        sink = sink,
                                        subtitleCallback = subtitleCallback
                                    )
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
