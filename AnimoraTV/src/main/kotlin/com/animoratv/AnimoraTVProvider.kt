package com.animoratv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimoraTVProvider : MainAPI() {

    override var mainUrl = "https://www.animoratv.com"
    override var name = "AnimoraTV"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries
    )

    override var lang = "es"

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/api/episodios/recientes?limite=36" to "Últimos episodios",
        "$mainUrl/api/animes/populares" to "Populares",
        "$mainUrl/api/animes?limite=24&pagina=1&sort=recientes" to "Explorar"
    )

    private suspend fun getJson(url: String): JSONObject {
        return JSONObject(app.get(url).text)
    }

    private fun parseAnimeList(json: JSONObject): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val animes = json.getJSONObject("data").getJSONArray("animes")

        for (i in 0 until animes.length()) {
            val anime = animes.getJSONObject(i)

            val title = anime.optString("titulo")
                .ifBlank { anime.optString("tituloIngles") }

            val slug = anime.optString("slug")

            if (title.isBlank() || slug.isBlank()) continue

            val response = newAnimeSearchResponse(
                title,
                "$mainUrl/anime/$slug",
                TvType.Anime
            )

            response.posterUrl =
                anime.optString("portada").ifBlank { null }

            results.add(response)
        }

        return results
    }

    private fun parseRecentEpisodes(json: JSONObject): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()

        val episodes =
            json.optJSONArray("data")
                ?: return results

        for (i in 0 until episodes.length()) {

            val episode =
                episodes.optJSONObject(i)
                    ?: continue

            val anime =
                episode.optJSONObject("anime")
                    ?: continue

            val title =
                anime.optString("titulo")
                    .ifBlank {
                        anime.optString("tituloIngles")
                    }

            val slug =
                anime.optString("slug")

            val number =
                episode.optInt("numero", 0)

            if (
                title.isBlank() ||
                slug.isBlank() ||
                number <= 0
            ) {
                continue
            }

            val response =
                newAnimeSearchResponse(
                    "$title - Episodio $number",
                    "$mainUrl/anime/$slug/episodio/$number",
                    TvType.Anime
                )

            response.posterUrl =
                episode
                    .optString("miniatura")
                    .ifBlank {
                        anime.optString("portada")
                    }
                    .ifBlank { null }

            results.add(response)
        }

        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val requestUrl =
            if (request.name == "Explorar") {
                "$mainUrl/api/animes?limite=24&pagina=$page&sort=recientes"
            } else {
                request.data
            }

        val results =
            if (request.name == "Últimos episodios") {
                parseRecentEpisodes(
                    getJson(requestUrl)
                )
            } else {
                parseAnimeList(
                    getJson(requestUrl)
                )
            }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = request.name == "Explorar" &&
                results.size >= 24
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        val encodedQuery =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val json =
            getJson(
                "$mainUrl/api/busqueda" +
                    "?termino=$encodedQuery" +
                    "&pagina=1&limite=30"
            )

        return parseAnimeList(json)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val animePart =
            url
                .substringAfter("/anime/", "")
                .substringBefore("/episodio/")
                .substringBefore("?")
                .trim()
                .removeSuffix("/")

        val slug =
            if (animePart.isNotBlank()) {
                animePart
            } else {
                url
                    .substringAfterLast("/anime/")
                    .substringBefore("?")
                    .trim()
            }

        if (slug.isBlank()) return null

        println(
            "AnimoraTV: load slug=$slug"
        )

        val animeJson =
            getJson(
                "$mainUrl/api/animes/$slug"
            )

        val anime =
            animeJson
                .getJSONObject("data")
                .getJSONObject("anime")

        val title =
            anime.optString("titulo")
                .ifBlank {
                    anime.optString("tituloIngles")
                }

        if (title.isBlank()) return null

        val episodesJson =
            getJson(
                "$mainUrl/api/animes/$slug/episodios"
            )

        val episodes =
            episodesJson
                .getJSONObject("data")
                .getJSONArray("episodios")

        val episodeList =
            ArrayList<Episode>()

        for (i in 0 until episodes.length()) {

            val episode =
                episodes.getJSONObject(i)

            val number =
                episode.optInt(
                    "numero",
                    i + 1
                )

            val episodeTitle =
                episode
                    .optString("titulo")
                    .ifBlank {
                        "Episodio $number"
                    }

            if (number <= 0) continue

            episodeList.add(
                newEpisode("$slug|$number") {

                    name = episodeTitle
                    this.episode = number
                    season = 1

                    description =
                        episode.optString(
                            "descripcion"
                        )

                    posterUrl =
                        episode
                            .optString("miniatura")
                            .ifBlank { null }
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            episodeList
        ) {

            posterUrl =
                anime
                    .optString("portada")
                    .ifBlank { null }

            plot =
                anime
                    .optString("sinopsis")
                    .ifBlank { null }

            year =
                anime
                    .optInt("anio")
                    .takeIf { it > 0 }
        }
    }

    private fun normalizeAnimeTitle(value: String): String {
        return value
            .lowercase()
            .replace(
                Regex(
                    """[^\p{L}\p{N}]+"""
                ),
                " "
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }

    private fun animeTitleScore(
        requested: String,
        candidate: String
    ): Int {

        val requestedNormalized =
            normalizeAnimeTitle(requested)

        val candidateNormalized =
            normalizeAnimeTitle(candidate)

        if (
            requestedNormalized == candidateNormalized
        ) {
            return 10000
        }

        val requestedWords =
            requestedNormalized
                .split(" ")
                .filter {
                    it.length >= 2
                }
                .toSet()

        val candidateWords =
            candidateNormalized
                .split(" ")
                .filter {
                    it.length >= 2
                }
                .toSet()

        if (
            requestedWords.isEmpty() ||
            candidateWords.isEmpty()
        ) {
            return 0
        }

        val common =
            requestedWords
                .intersect(candidateWords)
                .size

        var score =
            common * 100

        if (
            candidateNormalized.contains(
                "3rd season"
            ) ||
            candidateNormalized.contains(
                "2nd season"
            ) ||
            candidateNormalized.contains(
                "recap"
            ) ||
            candidateNormalized.contains(
                "ova"
            )
        ) {
            score -= 150
        }

        return score
    }

    private suspend fun findAnimeAVSlug(
        title: String
    ): String? {

        println(
            "AnimeAV: buscando título=$title"
        )

        val encoded =
            URLEncoder.encode(
                title,
                "UTF-8"
            )

        val searchUrl =
            "https://animeav1.com/catalogo?search=$encoded"

        println(
            "AnimeAV: search=$searchUrl"
        )

        val response =
            app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
                )
            )

        if (!response.isSuccessful) {
            println(
                "AnimeAV: search HTTP=${response.code}"
            )
            return null
        }

        val html =
            response.text

        val pattern =
            Regex(
                """<h3[^>]*>(.*?)</h3>[\s\S]*?<a[^>]+href="(/media/[^"]+)"""",
                RegexOption.IGNORE_CASE
            )

        val candidates =
            pattern
                .findAll(html)
                .mapNotNull { match ->

                    val candidateTitle =
                        match
                            .groupValues[1]
                            .replace(
                                Regex("<[^>]+>"),
                                ""
                            )
                            .trim()

                    val href =
                        match
                            .groupValues[2]
                            .trim()

                    if (
                        candidateTitle.isBlank() ||
                        href.isBlank()
                    ) {
                        null
                    } else {
                        Triple(
                            candidateTitle,
                            href,
                            animeTitleScore(
                                title,
                                candidateTitle
                            )
                        )
                    }
                }
                .toList()

        println(
            "AnimeAV: candidatos=${candidates.size}"
        )

        candidates
            .sortedByDescending {
                it.third
            }
            .take(10)
            .forEach {
                println(
                    "AnimeAV: candidato score=${it.third} " +
                        "title=${it.first} " +
                        "href=${it.second}"
                )
            }

        val best =
            candidates
                .maxByOrNull {
                    it.third
                }

        if (
            best == null ||
            best.third <= 0
        ) {
            println(
                "AnimeAV: no se encontró coincidencia"
            )
            return null
        }

        println(
            "AnimeAV: seleccionado " +
                "score=${best.third} " +
                "title=${best.first} " +
                "href=${best.second}"
        )

        return best.second
            .removePrefix("/media/")
            .trim()
            .removeSuffix("/")
    }

    private suspend fun findAnimeAVEpisodeUrl(
        animeSlug: String,
        episodeNumber: Int
    ): String? {

        val url =
            "https://animeav1.com/media/$animeSlug"

        println(
            "AnimeAV: serie=$url"
        )

        val response =
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
                )
            )

        if (!response.isSuccessful) {
            println(
                "AnimeAV: media HTTP=${response.code}"
            )
            return null
        }

        val html =
            response.text

        val episodesRegex =
            Regex(
                """episodes:\[(.*?)\]""",
                RegexOption.DOT_MATCHES_ALL
            )

        val episodesMatch =
            episodesRegex.find(html)

        if (episodesMatch == null) {
            println(
                "AnimeAV: no se encontró episodes[]"
            )
            return null
        }

        val episodePattern =
            Regex(
                """id:(\d+),number:(\d+)"""
            )

        val episode =
            episodePattern
                .findAll(
                    episodesMatch.groupValues[1]
                )
                .firstOrNull {
                    it.groupValues[2].toIntOrNull() ==
                        episodeNumber
                }

        if (episode == null) {
            println(
                "AnimeAV: episodio $episodeNumber no encontrado"
            )
            return null
        }

        val id =
            episode
                .groupValues[1]

        println(
            "AnimeAV: episodio=$episodeNumber id=$id"
        )

        /*
         * AnimeAV actualmente usa:
         *
         * /media/{slug}/{episode}
         *
         * aunque el ID interno también aparece en
         * el objeto episodes[].
         */
        return "https://animeav1.com/media/$animeSlug/$episodeNumber"
    }

    private fun decryptAnimeAV(
        inputHex: String,
        key: String,
        iv: String
    ): String {

        val cipher =
            Cipher.getInstance(
                "AES/CBC/PKCS5PADDING"
            )

        val secretKey =
            SecretKeySpec(
                key.toByteArray(Charsets.UTF_8),
                "AES"
            )

        val ivSpec =
            IvParameterSpec(
                iv.toByteArray(Charsets.UTF_8)
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            ivSpec
        )

        val bytes =
            inputHex
                .trim()
                .chunked(2)
                .map {
                    it.toInt(16).toByte()
                }
                .toByteArray()

        return String(
            cipher.doFinal(bytes),
            Charsets.UTF_8
        )
    }

    private suspend fun processAnimeAV(
        title: String,
        episodeNumber: Int,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            println(
                "AnimeAV: ===== INICIO ====="
            )

            println(
                "AnimeAV: title=$title episode=$episodeNumber"
            )

            val animeSlug =
                findAnimeAVSlug(
                    title
                )

            if (animeSlug == null) {
                println(
                    "AnimeAV: no se pudo encontrar anime"
                )
                return false
            }

            val episodeUrl =
                findAnimeAVEpisodeUrl(
                    animeSlug,
                    episodeNumber
                )

            if (episodeUrl == null) {
                println(
                    "AnimeAV: no se pudo encontrar episodio"
                )
                return false
            }

            println(
                "AnimeAV: episodeUrl=$episodeUrl"
            )

            val response =
                app.get(
                    episodeUrl,
                    headers = mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
                    )
                )

            if (!response.isSuccessful) {
                println(
                    "AnimeAV: episodio HTTP=${response.code}"
                )
                return false
            }

            val html =
                response.text

            val embedsMatch =
                Regex(
                    """embeds:\{(.*?)\},downloads""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(html)

            if (embedsMatch == null) {
                println(
                    "AnimeAV: no se encontró embeds"
                )
                return false
            }

            val embeds =
                embedsMatch.groupValues[1]

            val serverPattern =
                Regex(
                    """server:"([^"]+)",url:"([^"]+)""""
                )

            val servers =
                serverPattern
                    .findAll(embeds)
                    .map {
                        it.groupValues[1] to
                            it.groupValues[2]
                    }
                    .toList()

            println(
                "AnimeAV: servidores=${servers.size}"
            )

            var found = false

            /*
             * UPNShare se procesa directamente porque tenemos
             * el decoder AES y podemos obtener el HLS sin extractor.
             */
            val extractorServers =
                mutableListOf<Pair<String, String>>()

            for ((server, rawUrl) in servers) {

                println(
                    "AnimeAV: servidor=$server url=$rawUrl"
                )

                if (
                    server.equals(
                        "UPNShare",
                        ignoreCase = true
                    )
                ) {

                    try {

                        val hash =
                            rawUrl
                                .substringAfterLast("#")
                                .substringAfter("/")

                        if (hash.isBlank()) {
                            println(
                                "AnimeAV: UPNShare hash vacío"
                            )
                            continue
                        }

                        val apiUrl =
                            "https://animeav1.uns.bio/api/v1/video?id=$hash"

                        println(
                            "AnimeAV: UPNShare API=$apiUrl"
                        )

                        val apiResponse =
                            app.get(
                                apiUrl,
                                headers = mapOf(
                                    "User-Agent" to
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
                                )
                            )

                        if (!apiResponse.isSuccessful) {
                            println(
                                "AnimeAV: UPNShare HTTP=${apiResponse.code}"
                            )
                            continue
                        }

                        val encrypted =
                            apiResponse.text.trim()

                        val decrypted =
                            try {
                                decryptAnimeAV(
                                    encrypted,
                                    "kiemtienmua911ca",
                                    "1234567890oiuytr"
                                )
                            } catch (e: Exception) {
                                println(
                                    "AnimeAV: UPNShare AES error -> " +
                                        "${e.javaClass.simpleName}: ${e.message}"
                                )
                                continue
                            }

                        val json =
                            JSONObject(decrypted)

                        val hlsPath =
                            json.optString(
                                "hlsVideoTiktok"
                            )

                        if (hlsPath.isBlank()) {
                            println(
                                "AnimeAV: UPNShare no tiene hlsVideoTiktok"
                            )
                            continue
                        }

                        val hlsUrl =
                            if (
                                hlsPath.startsWith(
                                    "http://"
                                ) ||
                                hlsPath.startsWith(
                                    "https://"
                                )
                            ) {
                                hlsPath
                            } else {
                                "https://animeav1.uns.bio" +
                                    if (
                                        hlsPath.startsWith("/")
                                    ) {
                                        hlsPath
                                    } else {
                                        "/$hlsPath"
                                    }
                            }

                        val shouldEmit =
                            synchronized(emittedUrls) {
                                if (
                                    emittedUrls.contains(
                                        hlsUrl
                                    )
                                ) {
                                    false
                                } else {
                                    emittedUrls.add(
                                        hlsUrl
                                    )
                                    true
                                }
                            }

                        if (shouldEmit) {

                            callback(
                                newExtractorLink(
                                    name = "UPNShare",
                                    source = name,
                                    url = hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer =
                                        "https://animeav1.uns.bio/"

                                    quality =
                                        Qualities.Unknown.value
                                }
                            )

                            found = true

                            println(
                                "AnimeAV: UPNShare LINK EMITIDO " +
                                    "url=$hlsUrl"
                            )

                        } else {

                            println(
                                "AnimeAV: UPNShare LINK DUPLICADO"
                            )
                        }

                    } catch (e: Exception) {

                        println(
                            "AnimeAV: UPNShare ERROR -> " +
                                "${e.javaClass.simpleName}: " +
                                e.message
                        )
                    }

                    continue
                }

                /*
                 * MP4Upload, PDrain, Voe y Byse utilizan
                 * los extractores existentes de CloudStream.
                 *
                 * Los guardamos para procesarlos todos
                 * concurrentemente después de descubrir
                 * todos los servidores.
                 */
                if (
                    server.equals("MP4Upload", ignoreCase = true) ||
                    server.equals("PDrain", ignoreCase = true) ||
                    server.equals("Voe", ignoreCase = true) ||
                    server.equals("Byse", ignoreCase = true)
                ) {

                    extractorServers.add(
                        server to rawUrl
                    )

                    continue
                }

                println(
                    "AnimeAV: servidor ignorado=$server"
                )
            }

            /*
             * Procesamos MP4Upload, PDrain, Voe y Byse
             * en paralelo.
             *
             * Cada extractor tiene su propio timeout para
             * evitar que un servidor lento bloquee los demás.
             */
            if (extractorServers.isNotEmpty()) {

                println(
                    "AnimeAV: ===== EXTRACTORES PARALELOS ====="
                )

                val extractorResults =
                    coroutineScope {

                        extractorServers.map { (server, rawUrl) ->

                            async {

                                withTimeoutOrNull(7000L) {

                                    val before =
                                        synchronized(emittedUrls) {
                                            emittedUrls.size
                                        }

                                    println(
                                        "AnimeAV: $server -> loadExtractor"
                                    )

                                    val extractorCallback:
                                        (ExtractorLink) -> Unit = { link ->

                                        val shouldEmit =
                                            synchronized(emittedUrls) {
                                                if (
                                                    emittedUrls.contains(
                                                        link.url
                                                    )
                                                ) {
                                                    false
                                                } else {
                                                    emittedUrls.add(
                                                        link.url
                                                    )
                                                    true
                                                }
                                            }

                                        if (shouldEmit) {

                                            callback(link)

                                            println(
                                                "AnimeAV: $server LINK EMITIDO " +
                                                    "name=${link.name} " +
                                                    "quality=${link.quality} " +
                                                    "type=${link.type} " +
                                                    "url=${link.url}"
                                            )

                                        } else {

                                            println(
                                                "AnimeAV: $server LINK DUPLICADO " +
                                                    "url=${link.url}"
                                            )
                                        }
                                    }

                                    try {

                                        loadExtractor(
                                            rawUrl,
                                            "https://animeav1.com/",
                                            subtitleCallback,
                                            extractorCallback
                                        )

                                    } catch (e: Exception) {

                                        println(
                                            "AnimeAV: $server ERROR -> " +
                                                "${e.javaClass.simpleName}: " +
                                                e.message
                                        )
                                    }

                                    val after =
                                        synchronized(emittedUrls) {
                                            emittedUrls.size
                                        }

                                    if (after > before) {

                                        println(
                                            "AnimeAV: $server OK"
                                        )

                                        true

                                    } else {

                                        println(
                                            "AnimeAV: $server SIN LINKS"
                                        )

                                        false
                                    }

                                } ?: run {

                                    println(
                                        "AnimeAV: $server TIMEOUT 7000ms"
                                    )

                                    false
                                }
                            }
                        }.awaitAll()
                    }

                if (extractorResults.any { it }) {
                    found = true
                }
            }

            println(
                "AnimeAV: ===== FINAL found=$found ====="
            )

            found

        } catch (e: Exception) {

            println(
                "AnimeAV: ERROR GENERAL -> " +
                    "${e.javaClass.simpleName}: " +
                    e.message
            )

            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println(
            "AnimoraTV: loadLinks data=$data"
        )

        val parts = data.split("|")

        if (parts.size < 2) {
            println(
                "AnimoraTV: ERROR formato data inválido"
            )
            return false
        }

        val rawSlug = parts[0]

        val episodeNumber =
            parts[1].toIntOrNull()

        if (episodeNumber == null) {
            println(
                "AnimoraTV: ERROR episodio inválido=${parts[1]}"
            )
            return false
        }

        val slug =
            rawSlug
                .substringAfterLast("/anime/")
                .substringAfterLast("/")
                .trim()
                .removeSuffix("/")

        println(
            "AnimoraTV: slug=$slug episode=$episodeNumber"
        )

        val apiUrl =
            "$mainUrl/api/video/$slug/$episodeNumber/fuentes"

        println(
            "AnimoraTV: API $apiUrl"
        )

        return try {

            var found = false
            var totalServers = 0
            var totalExtractedLinks = 0
            var megaEmitted = false

            val emittedUrls =
                mutableSetOf<String>()

            /*
             * ============================================================
             * ANIMEAV ASYNC
             * ============================================================
             *
             * AnimeAV arranca ANTES de consultar las fuentes de Animora,
             * por lo que el timeout de Animora ya no lo bloquea.
             */
            println(
                "AnimoraTV: ===== INICIANDO ANIMEAV EN PARALELO ====="
            )

            val animeAvJob =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.currentCoroutineContext()
                ).async {

                    try {

                        val animeTitle =
                            try {

                                val animeJson =
                                    getJson(
                                        "$mainUrl/api/animes/$slug"
                                    )

                                val anime =
                                    animeJson
                                        .optJSONObject("data")
                                        ?.optJSONObject("anime")

                                anime
                                    ?.optString("titulo")
                                    ?.ifBlank {
                                        anime.optString(
                                            "tituloIngles"
                                        )
                                    }
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }

                            } catch (e: Exception) {

                                println(
                                    "AnimeAV: ERROR obteniendo título Animora -> " +
                                        "${e.javaClass.simpleName}: " +
                                        e.message
                                )

                                null
                            }

                        if (animeTitle == null) {

                            println(
                                "AnimeAV: no se pudo obtener título de Animora"
                            )

                            false

                        } else {

                            println(
                                "AnimeAV: título obtenido=$animeTitle"
                            )

                            withTimeoutOrNull(10000L) {

                                processAnimeAV(
                                    animeTitle,
                                    episodeNumber,
                                    emittedUrls,
                                    subtitleCallback,
                                    callback
                                )

                            } ?: run {

                                println(
                                    "AnimeAV: TIMEOUT general=10000ms"
                                )

                                false
                            }
                        }

                    } catch (e: Exception) {

                        println(
                            "AnimeAV: ERROR paralelo -> " +
                                "${e.javaClass.simpleName}: " +
                                e.message
                        )

                        false
                    }
                }

            /*
             * ============================================================
             * ANIMORA / FUENTES
             * ============================================================
             */

            val response =
                try {
                    app.get(
                        apiUrl,
                        headers = mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
                            "Accept" to
                                "application/json, text/plain, */*",
                            "Referer" to
                                "$mainUrl/",
                            "Origin" to
                                mainUrl
                        )
                    )
                } catch (e: Exception) {

                    println(
                        "AnimoraTV: ERROR API fuentes -> " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )

                    null
                }

            val fuentes =
                if (response == null) {

                    println(
                        "AnimoraTV: continuando con AnimeAV"
                    )

                    org.json.JSONArray()

                } else if (!response.isSuccessful) {

                    println(
                        "AnimoraTV: ERROR HTTP ${response.code}, " +
                            "continuando con AnimeAV"
                    )

                    org.json.JSONArray()

                } else {

                    println(
                        "AnimoraTV: HTTP ${response.code}"
                    )

                    val root =
                        JSONObject(response.text)

                    val dataObject =
                        root.optJSONObject("data")
                            ?: JSONObject()

                    dataObject.optJSONArray("fuentes")
                        ?: org.json.JSONArray()
                }

            println(
                "AnimoraTV: fuentes=${fuentes.length()}"
            )

            /*
             * Primero recopilamos TODOS los servidores.
             */
            val allServers =
                mutableListOf<JSONObject>()

            val seenServerUrls =
                mutableSetOf<String>()

            for (i in 0 until fuentes.length()) {

                val fuente =
                    fuentes.optJSONObject(i)
                        ?: continue

                val servidores =
                    fuente.optJSONArray("servidores")
                        ?: org.json.JSONArray()

                println(
                    "AnimoraTV: FUENTE ${i + 1} " +
                        "servidores=${servidores.length()}"
                )

                for (j in 0 until servidores.length()) {

                    val servidor =
                        servidores.optJSONObject(j)
                            ?: continue

                    val urlVideo =
                        servidor.optString("urlVideo")

                    if (urlVideo.isBlank()) {
                        continue
                    }

                    if (!seenServerUrls.add(urlVideo)) {
                        println(
                            "AnimoraTV: servidor duplicado -> ignorado: $urlVideo"
                        )
                        continue
                    }

                    allServers.add(servidor)
                }
            }

            totalServers = allServers.size

            println(
                "AnimoraTV: servidores totales=$totalServers"
            )

            suspend fun processServer(
                servidor: JSONObject
            ) {

                val urlVideo =
                    servidor.optString("urlVideo")

                val calidad =
                    servidor.optString("calidad")

                val provider =
                    servidor.optString("proveedor")

                if (urlVideo.isBlank()) {
                    return
                }

                val qualityValue =
                    calidad
                        .filter { it.isDigit() }
                        .toIntOrNull()
                        ?: Qualities.Unknown.value

                val isMega =
                    provider.equals(
                        "mega",
                        ignoreCase = true
                    ) ||
                    urlVideo.contains(
                        "mega.nz/",
                        ignoreCase = true
                    )

                if (isMega) {

                    if (megaEmitted) {

                        println(
                            "AnimoraTV: MEGA duplicado -> ignorado"
                        )

                        return
                    }

                    try {

                        println(
                            "AnimoraTV: MEGA detectado -> $urlVideo"
                        )

                        var handle = ""
                        var key = ""

                        /*
                         * Mega puede entregar distintos formatos de embed:
                         *
                         *   /embed/HANDLE#KEY
                         *   /embed/#!HANDLE!KEY
                         *   /embed/!HANDLE!KEY
                         *
                         * Extraemos primero todo lo que venga después de
                         * /embed/ y luego detectamos cómo están separados
                         * el handle y la key.
                         */

                        val megaPart =
                            Regex(
                                """mega\.nz/[^?#]*embed/([^?#]+)""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(urlVideo)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.trim()
                                ?.trimEnd('/')

                        if (!megaPart.isNullOrBlank()) {

                            val fragment =
                                megaPart
                                    .trimStart('!', '#')

                            val hashIndex =
                                fragment.indexOf('#')

                            if (hashIndex > 0) {

                                handle =
                                    fragment
                                        .substring(0, hashIndex)
                                        .trim()
                                        .trimStart('!', '#')

                                key =
                                    fragment
                                        .substring(hashIndex + 1)
                                        .trim()

                            } else {

                                val bangIndex =
                                    fragment.indexOf('!')

                                if (bangIndex > 0) {

                                    handle =
                                        fragment
                                            .substring(0, bangIndex)
                                            .trim()

                                    key =
                                        fragment
                                            .substring(bangIndex + 1)
                                            .trim()
                                }
                            }
                        }

                        /*
                         * Fallback para pequeñas variaciones futuras de Mega:
                         * si no encontramos los datos usando /embed/, buscamos
                         * directamente el patrón HANDLE + separador + KEY.
                         */
                        if (
                            handle.isBlank() ||
                            key.isBlank()
                        ) {

                            val fallback =
                                Regex(
                                    """(?:embed/|/)([^#!?/\s]+)[#!]([^#!?\s]+)""",
                                    RegexOption.IGNORE_CASE
                                )
                                    .find(urlVideo)

                            if (fallback != null) {

                                handle =
                                    fallback
                                        .groupValues[1]
                                        .trim()
                                        .trimStart('!', '#')

                                key =
                                    fallback
                                        .groupValues[2]
                                        .trim()
                            }
                        }

                        if (
                            handle.isBlank() ||
                            key.isBlank()
                        ) {

                            println(
                                "AnimoraTV: MEGA handle/key vacío"
                            )

                            return
                        }

                        megaEmitted = true

                        println(
                            "AnimoraTV: MEGA handle=$handle"
                        )

                        val localUrl =
                            MegaLocalServer.start(
                                handle,
                                key
                            )

                        callback(
                            newExtractorLink(
                                name = "Mega",
                                source = name,
                                url = localUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {

                                referer =
                                    "$mainUrl/"

                                quality =
                                    qualityValue
                            }
                        )

                        found = true
                        totalExtractedLinks++

                        println(
                            "AnimoraTV: MEGA LINK EMITIDO " +
                                "url=$localUrl"
                        )

                    } catch (e: Exception) {

                        println(
                            "AnimoraTV: MEGA ERROR -> " +
                                "${e.javaClass.simpleName}: " +
                                e.message
                        )
                    }

                    return
                }

                val isHls =
                    provider.equals(
                        "hls",
                        ignoreCase = true
                    ) ||
                    urlVideo
                        .substringBefore("?")
                        .endsWith(
                            ".m3u8",
                            ignoreCase = true
                        )

                println(
                    "AnimoraTV: servidor " +
                        "provider=$provider " +
                        "quality=$calidad " +
                        "hls=$isHls"
                )

                if (isHls) {

                    try {

                        println(
                            "AnimoraTV: HLS directo -> $urlVideo"
                        )

                        val hlsReferer =
                            try {

                                val refValue =
                                    urlVideo
                                        .substringAfter(
                                            "ref=",
                                            ""
                                        )
                                        .substringBefore("&")

                                if (
                                    refValue.isNotBlank()
                                ) {

                                    URLDecoder.decode(
                                        refValue,
                                        "UTF-8"
                                    )

                                } else {

                                    "$mainUrl/"
                                }

                            } catch (_: Exception) {

                                "$mainUrl/"
                            }

                        println(
                            "AnimoraTV: HLS referer -> $hlsReferer"
                        )

                        callback(
                            newExtractorLink(
                                name = provider,
                                source = name,
                                url = urlVideo,
                                type = ExtractorLinkType.M3U8
                            ) {

                                referer =
                                    hlsReferer

                                quality =
                                    qualityValue
                            }
                        )

                        found = true
                        totalExtractedLinks++

                        println(
                            "AnimoraTV: HLS LINK EMITIDO " +
                                "provider=$provider " +
                                "quality=$qualityValue"
                        )

                    } catch (e: Exception) {

                        println(
                            "AnimoraTV: HLS ERROR " +
                                "provider=$provider -> " +
                                e.message
                        )
                    }

                    return
                }

                val hostReferer =
                    try {

                        val uri =
                            URI(urlVideo)

                        val host =
                            uri.host ?: ""

                        if (host.isBlank()) {
                            "$mainUrl/"
                        } else {
                            "${uri.scheme ?: "https"}://$host/"
                        }

                    } catch (_: Exception) {

                        "$mainUrl/"
                    }

                val referers =
                    if (
                        hostReferer.equals(
                            "$mainUrl/",
                            ignoreCase = true
                        )
                    ) {

                        listOf(
                            "$mainUrl/"
                        )

                    } else {

                        listOf(
                            hostReferer,
                            "$mainUrl/"
                        )
                    }

                var emitted = 0
                var successfulReferer: String? = null

                val extractorCallback:
                    (ExtractorLink) -> Unit = { link ->

                    synchronized(emittedUrls) {

                        if (
                            emittedUrls.contains(
                                link.url
                            )
                        ) {

                            println(
                                "AnimoraTV: LINK DUPLICADO " +
                                    "ignorado " +
                                    "name=${link.name} " +
                                    "provider=$provider " +
                                    "url=${link.url}"
                            )

                        } else {

                            emittedUrls.add(
                                link.url
                            )

                            emitted++
                            totalExtractedLinks++

                            println(
                                "AnimoraTV: EXTRACTED LINK " +
                                    "provider=$provider " +
                                    "name=${link.name} " +
                                    "quality=${link.quality} " +
                                    "type=${link.type} " +
                                    "url=${link.url}"
                            )

                            callback(link)
                        }
                    }
                }

                for (
                    (attempt, referer)
                    in referers.withIndex()
                ) {

                    if (
                        successfulReferer != null
                    ) {
                        break
                    }

                    val before =
                        emitted

                    try {

                        println(
                            "AnimoraTV: loadExtractor " +
                                "intento=${attempt + 1} " +
                                "provider=$provider " +
                                "referer=$referer " +
                                "url=$urlVideo"
                        )

                        loadExtractor(
                            urlVideo,
                            referer,
                            subtitleCallback,
                            extractorCallback
                        )

                        if (
                            emitted > before
                        ) {

                            successfulReferer =
                                referer

                            found = true

                            println(
                                "AnimoraTV: loadExtractor OK " +
                                    "provider=$provider " +
                                    "referer=$referer " +
                                    "links=${emitted - before}"
                            )

                        } else {

                            println(
                                "AnimoraTV: loadExtractor SIN LINKS " +
                                    "provider=$provider " +
                                    "referer=$referer"
                            )
                        }

                    } catch (e: Exception) {

                        println(
                            "AnimoraTV: loadExtractor ERROR " +
                                "provider=$provider " +
                                "referer=$referer -> " +
                                e.message
                        )
                    }
                }

                if (
                    successfulReferer == null
                ) {

                    println(
                        "AnimoraTV: EXTRACTOR FALLÓ " +
                            "provider=$provider " +
                            "hostReferer=$hostReferer"
                    )
                }
            }

            /*
             * PASADA 1:
             * HLS/directos.
             */
            println(
                "AnimoraTV: ===== PASADA HLS ====="
            )

            for (servidor in allServers) {

                val urlVideo =
                    servidor.optString("urlVideo")

                val provider =
                    servidor.optString("proveedor")

                val isMega =
                    provider.equals(
                        "mega",
                        ignoreCase = true
                    ) ||
                    urlVideo.contains(
                        "mega.nz/",
                        ignoreCase = true
                    )

                val isHls =
                    provider.equals(
                        "hls",
                        ignoreCase = true
                    ) ||
                    urlVideo
                        .substringBefore("?")
                        .endsWith(
                            ".m3u8",
                            ignoreCase = true
                        )

                if (!isMega && isHls) {
                    processServer(servidor)
                }
            }

            /*
             * PASADA 2:
             * Mega.
             */
            println(
                "AnimoraTV: ===== PASADA MEGA ====="
            )

            for (servidor in allServers) {

                val urlVideo =
                    servidor.optString("urlVideo")

                val provider =
                    servidor.optString("proveedor")

                val isMega =
                    provider.equals(
                        "mega",
                        ignoreCase = true
                    ) ||
                    urlVideo.contains(
                        "mega.nz/",
                        ignoreCase = true
                    )

                if (isMega) {
                    processServer(servidor)
                }
            }

            /*
             * PASADA 3:
             * Extractores en paralelo.
             */
            println(
                "AnimoraTV: ===== PASADA EXTRACTORES PARALELA ====="
            )

            val extractorServers =
                allServers.filter { servidor ->

                    val urlVideo =
                        servidor.optString("urlVideo")

                    val provider =
                        servidor.optString("proveedor")

                    val isMega =
                        provider.equals(
                            "mega",
                            ignoreCase = true
                        ) ||
                        urlVideo.contains(
                            "mega.nz/",
                            ignoreCase = true
                        )

                    val isHls =
                        provider.equals(
                            "hls",
                            ignoreCase = true
                        ) ||
                        urlVideo
                            .substringBefore("?")
                            .endsWith(
                                ".m3u8",
                                ignoreCase = true
                            )

                    !isMega && !isHls
                }

            println(
                "AnimoraTV: extractores paralelos=" +
                    extractorServers.size
            )

            coroutineScope {

                extractorServers
                    .map { servidor ->

                        async {

                            val provider =
                                servidor.optString(
                                    "proveedor"
                                )

                            val completed =
                                withTimeoutOrNull(7000L) {

                                    try {

                                        processServer(
                                            servidor
                                        )

                                        true

                                    } catch (e: Exception) {

                                        println(
                                            "AnimoraTV: EXTRACTOR TASK ERROR provider=" +
                                                provider +
                                                " -> " +
                                                "${e.javaClass.simpleName}: " +
                                                e.message
                                        )

                                        true
                                    }
                                }

                            if (completed == null) {

                                println(
                                    "AnimoraTV: EXTRACTOR TIMEOUT provider=" +
                                        provider +
                                        " limite=7000ms"
                                )
                            }
                        }
                    }
                    .awaitAll()
            }

            /*
             * ============================================================
             * ESPERAR ANIMEAV
             * ============================================================
             */
            println(
                "AnimoraTV: ===== ESPERANDO ANIMEAV ====="
            )

            val animeAvFound =
                try {

                    animeAvJob.await()

                } catch (e: Exception) {

                    println(
                        "AnimeAV: ERROR await -> " +
                            "${e.javaClass.simpleName}: " +
                            e.message
                    )

                    false
                }

            if (animeAvFound) {
                found = true
            }

            println(
                "AnimoraTV: AnimeAV resultado=$animeAvFound"
            )

            println(
                "AnimoraTV: FINAL " +
                    "found=$found " +
                    "totalServers=$totalServers " +
                    "totalExtractedLinks=$totalExtractedLinks"
            )

            found

        } catch (e: Exception) {

            println(
                "AnimoraTV: ERROR loadLinks -> " +
                    "${e.javaClass.simpleName}: " +
                    e.message
            )

            false
        }
    }
}
