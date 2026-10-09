package com.animoratv

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
private const val ANIMORA_API_TIMEOUT_MS = 10_000L
private const val HOME_CACHE_MS = 180_000L

private const val TAG_ANIMORA = "Animora"
private const val TAG_ANIMEAV = "AnimeAV"

private const val SECTION_RECENT = "Últimos episodios"
private const val SECTION_EXPLORE = "Explorar"

private fun log(msg: String) {
    if (DEBUG) println("AnimoraTV: $msg")
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

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/* ====================================================================
 * Provider
 * ==================================================================== */

class AnimoraTVProvider : MainAPI() {

    override var mainUrl = "https://www.animoratv.com"
    override var name = "AnimoraTV"

    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries)

    override var lang = "es"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/api/episodios/recientes?limite=36" to SECTION_RECENT,
        "$mainUrl/api/animes/populares" to "Populares",
        "$mainUrl/api/animes?limite=24&pagina=1&sort=recientes" to SECTION_EXPLORE
    )

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    private val jsonCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    // título normalizado -> slug de AnimeAV (evita repetir la búsqueda en cada episodio)
    private val animeAvSlugCache = ConcurrentHashMap<String, String>()

    /* ------------------------------------------------------------
     * HTTP / JSON
     * ------------------------------------------------------------ */

    /**
     * GET JSON con headers, reintentos y caché opcional.
     * Devuelve null si falla (antes un 403/429/HTML rompía todo con una excepción,
     * que es la causa más probable de que la página principal a veces no cargue).
     */
    private suspend fun getJson(
        url: String,
        cacheMs: Long = 0L,
        retries: Int = 2
    ): JSONObject? {

        if (cacheMs > 0) {
            val cached = jsonCache[url]
            if (cached != null && System.currentTimeMillis() - cached.first < cacheMs) {
                return cached.second
            }
        }

        for (attempt in 0..retries) {
            try {
                val res = app.get(url, headers = apiHeaders)

                if (res.isSuccessful) {
                    val json = JSONObject(res.text)
                    if (cacheMs > 0) jsonCache[url] = System.currentTimeMillis() to json
                    return json
                }

                log("HTTP ${res.code} en $url (intento ${attempt + 1})")
                if (res.code == 404) return null

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("getJson ERROR $url -> ${e.javaClass.simpleName}: ${e.message}")
            }

            if (attempt < retries) delay(400L * (attempt + 1))
        }

        return null
    }

    /* ------------------------------------------------------------
     * Parsers (tolerantes a cambios de forma en la respuesta)
     * ------------------------------------------------------------ */

    private fun animeArray(json: JSONObject): JSONArray? =
        when (val data = json.opt("data")) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("animes")
            else -> json.optJSONArray("animes")
        }

    private fun parseAnimeList(json: JSONObject): List<SearchResponse> =
        (animeArray(json)?.objects() ?: emptyList())
            .mapNotNull { anime ->
                val title = anime.optString("titulo").ifBlank { anime.optString("tituloIngles") }
                val slug = anime.optString("slug")
                if (title.isBlank() || slug.isBlank()) return@mapNotNull null

                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    posterUrl = anime.optString("portada").ifBlank { null }
                }
            }
            .distinctBy { it.url }

    private fun parseRecentEpisodes(json: JSONObject): List<SearchResponse> =
        (json.optJSONArray("data")?.objects() ?: emptyList())
            .mapNotNull { episode ->
                val anime = episode.optJSONObject("anime") ?: return@mapNotNull null

                val title = anime.optString("titulo").ifBlank { anime.optString("tituloIngles") }
                val slug = anime.optString("slug")
                val number = episode.optInt("numero", 0)
                if (title.isBlank() || slug.isBlank() || number <= 0) return@mapNotNull null

                newAnimeSearchResponse(
                    "$title - Episodio $number",
                    "$mainUrl/anime/$slug/episodio/$number",
                    TvType.Anime
                ) {
                    posterUrl = episode.optString("miniatura")
                        .ifBlank { anime.optString("portada") }
                        .ifBlank { null }
                }
            }
            .distinctBy { it.url }

    /* ------------------------------------------------------------
     * Main page / search / load
     * ------------------------------------------------------------ */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val isExplore = request.name == SECTION_EXPLORE

        val url =
            if (isExplore) "$mainUrl/api/animes?limite=24&pagina=$page&sort=recientes"
            else request.data

        val json = getJson(url, cacheMs = HOME_CACHE_MS)

        val items = when {
            json == null -> emptyList()
            request.name == SECTION_RECENT -> parseRecentEpisodes(json)
            else -> parseAnimeList(json)
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = isExplore && items.size >= 24
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$mainUrl/api/busqueda?termino=$encoded&pagina=1&limite=30")
            ?: return emptyList()
        return parseAnimeList(json)
    }

    override suspend fun load(url: String): LoadResponse? {

        val slug = url
            .substringAfter("/anime/", "")
            .substringBefore("/episodio/")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .removeSuffix("/")

        if (slug.isBlank()) return null

        // Las dos requests no dependen una de la otra -> en paralelo
        val (animeJson, episodesJson) = coroutineScope {
            val a = async { getJson("$mainUrl/api/animes/$slug") }
            val e = async { getJson("$mainUrl/api/animes/$slug/episodios") }
            a.await() to e.await()
        }

        val anime = animeJson
            ?.optJSONObject("data")
            ?.optJSONObject("anime")
            ?: return null

        val titulo = anime.optString("titulo")
        val tituloEn = anime.optString("tituloIngles")
        val title = titulo.ifBlank { tituloEn }
        if (title.isBlank()) return null

        // Los títulos viajan en `data` para que loadLinks no tenga que pedirlos otra vez.
        val titlesData = listOf(titulo, tituloEn)
            .joinToString("|") { it.replace("|", " ").trim() }

        val episodeList = (episodesJson
            ?.optJSONObject("data")
            ?.optJSONArray("episodios")
            ?.objects()
            ?: emptyList())
            .mapIndexedNotNull { i, ep ->

                val number = ep.optInt("numero", i + 1)
                if (number <= 0) return@mapIndexedNotNull null

                val epTitle = ep.optString("titulo").ifBlank { "Episodio $number" }
                val epDesc = ep.optString("descripcion").ifBlank { null }
                val epPoster = ep.optString("miniatura").ifBlank { null }

                newEpisode("$slug|$number|$titlesData") {
                    this.name = epTitle
                    this.episode = number
                    this.season = 1
                    this.description = epDesc
                    this.posterUrl = epPoster
                }
            }
            .sortedBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodeList) {
            posterUrl = anime.optString("portada").ifBlank { null }
            plot = anime.optString("sinopsis").ifBlank { null }
            year = anime.optInt("anio").takeIf { it > 0 }
        }
    }

    /* ------------------------------------------------------------
     * loadLinks
     * ------------------------------------------------------------ */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // formato: slug|episodio|titulo|tituloIngles  (los 2 últimos son opcionales)
        val parts = data.split("|", limit = 4)
        if (parts.size < 2) return false

        val slug = parts[0]
            .substringAfterLast("/anime/")
            .substringAfterLast("/")
            .trim()
            .removeSuffix("/")

        val episodeNumber = parts[1].trim().toIntOrNull() ?: return false

        val titles = parts
            .drop(2)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        log("loadLinks slug=$slug ep=$episodeNumber titles=$titles")

        val sink = LinkSink(callback)

        // Todo corre dentro de un único scope: si el usuario sale de la pantalla,
        // se cancela todo (antes AnimeAV quedaba en un scope suelto sin cancelar).
        safely("loadLinks") {
            coroutineScope {

                val animeAv = async {
                    safely("AnimeAV") {
                        val resolved = titles.ifEmpty { fetchAnimoraTitles(slug) }
                        if (resolved.isNotEmpty()) {
                            withTimeoutOrNull(ANIMEAV_TIMEOUT_MS) {
                                loadAnimeAv(resolved, episodeNumber, sink, subtitleCallback)
                            }
                        }
                    }
                }

                val animora = async {
                    safely("Animora") {
                        loadAnimoraSources(slug, episodeNumber, sink, subtitleCallback)
                    }
                }

                animeAv.await()
                animora.await()
            }
        }

        log("FINAL links=${sink.count}")
        return sink.count > 0
    }

    private suspend fun fetchAnimoraTitles(slug: String): List<String> {
        val anime = getJson("$mainUrl/api/animes/$slug")
            ?.optJSONObject("data")
            ?.optJSONObject("anime")
            ?: return emptyList()

        return listOf(anime.optString("titulo"), anime.optString("tituloIngles"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /* ------------------------------------------------------------
     * Animora
     * ------------------------------------------------------------ */

    private suspend fun loadAnimoraSources(
        slug: String,
        episodeNumber: Int,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val res = withTimeoutOrNull(ANIMORA_API_TIMEOUT_MS) {
            try {
                app.get("$mainUrl/api/video/$slug/$episodeNumber/fuentes", headers = apiHeaders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("API fuentes ERROR -> ${e.message}")
                null
            }
        }

        if (res == null || !res.isSuccessful) {
            log("API fuentes sin respuesta válida (${res?.code})")
            return
        }

        val fuentes = JSONObject(res.text)
            .optJSONObject("data")
            ?.optJSONArray("fuentes")
            ?.objects()
            ?: return

        // Dedupe ANTES de extraer: no gastamos requests en embeds repetidos
        // (ni dentro de Animora ni los que AnimeAV ya reclamó).
        val servers = fuentes
            .flatMap { it.optJSONArray("servidores")?.objects() ?: emptyList() }
            .filter { it.optString("urlVideo").isNotBlank() }
            .filter { sink.claimEmbed(it.optString("urlVideo")) }

        log("Animora servidores únicos=${servers.size}")

        // Todo en paralelo: HLS directo, Mega y extractores (antes eran 3 pasadas secuenciales).
        coroutineScope {
            servers
                .map { servidor ->
                    async {
                        safely("Animora server") {
                            processAnimoraServer(servidor, sink, subtitleCallback)
                        }
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun processAnimoraServer(
        servidor: JSONObject,
        sink: LinkSink,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val urlVideo = servidor.optString("urlVideo")
        val provider = servidor.optString("proveedor")
        val qualityValue = servidor.optString("calidad")
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: Qualities.Unknown.value

        // ---- Mega ----
        if (isMegaServer(provider, urlVideo)) {
            emitMega(urlVideo, qualityValue, sink)
            return
        }

        // ---- HLS directo ----
        if (isHlsServer(provider, urlVideo)) {

            val hlsReferer = try {
                val ref = urlVideo.substringAfter("ref=", "").substringBefore("&")
                if (ref.isNotBlank()) URLDecoder.decode(ref, "UTF-8") else "$mainUrl/"
            } catch (_: Exception) {
                "$mainUrl/"
            }

            sink.emit(
                newExtractorLink(
                    name = provider.ifBlank { "HLS" },
                    source = name,
                    url = urlVideo,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = hlsReferer
                    quality = qualityValue
                },
                null
            )
            return
        }

        // ---- Extractores de CloudStream ----
        val hostReferer = try {
            val uri = URI(urlVideo)
            val host = uri.host.orEmpty()
            if (host.isBlank()) "$mainUrl/" else "${uri.scheme ?: "https"}://$host/"
        } catch (_: Exception) {
            "$mainUrl/"
        }

        runExtractor(
            url = urlVideo,
            referers = listOf(hostReferer, "$mainUrl/").distinct(),
            tag = TAG_ANIMORA,
            sink = sink,
            subtitleCallback = subtitleCallback
        )
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
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val animeSlug = resolveAnimeAvSlug(titles.take(2)) ?: run {
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

        val servers = AV_SERVER
            .findAll(embeds)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .filter { (_, rawUrl) -> sink.claimEmbed(rawUrl) }

        log("AnimeAV servidores únicos=${servers.size}")

        // UPNShare ya no bloquea el lanzamiento de los extractores: todo en paralelo.
        coroutineScope {
            servers
                .map { (server, rawUrl) ->
                    async {
                        safely("AnimeAV $server") {
                            when (server.lowercase()) {
                                "upnshare" ->
                                    withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                        loadUpnShare(rawUrl, sink)
                                    }

                                "mp4upload", "pdrain", "voe", "byse" ->
                                    runExtractor(
                                        url = rawUrl,
                                        referers = listOf("$AV_BASE/"),
                                        tag = TAG_ANIMEAV,
                                        sink = sink,
                                        subtitleCallback = subtitleCallback
                                    )

                                else -> log("AnimeAV: servidor ignorado=$server")
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

    private suspend fun loadUpnShare(rawUrl: String, sink: LinkSink) {

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
            null
        )
    }
}
