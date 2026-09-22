package com.animoratv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
            java.net.URLEncoder.encode(
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

            val response =
                app.get(apiUrl)

            println(
                "AnimoraTV: HTTP ${response.code}"
            )

            if (!response.isSuccessful) {
                println(
                    "AnimoraTV: ERROR HTTP ${response.code}"
                )
                return false
            }

            val root =
                JSONObject(response.text)

            val dataObject =
                root.optJSONObject("data")
                    ?: JSONObject()

            val fuentes =
                dataObject.optJSONArray("fuentes")
                    ?: org.json.JSONArray()

            println(
                "AnimoraTV: fuentes=${fuentes.length()}"
            )

            var found = false
            var totalServers = 0
            var totalExtractedLinks = 0
            var megaEmitted = false

            val emittedUrls =
                mutableSetOf<String>()

            /*
             * Primero recopilamos TODOS los servidores.
             *
             * Esto nos permite procesarlos globalmente por prioridad:
             *
             * 1. HLS directo
             * 2. Extractores normales
             * 3. Mega
             *
             * Así un Mega que aparezca primero en la API nunca bloquea
             * la entrega de una fuente HLS rápida.
             */

            val allServers =
                mutableListOf<JSONObject>()

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

                    allServers.add(servidor)
                }
            }

            totalServers = allServers.size

            println(
                "AnimoraTV: servidores totales=$totalServers"
            )

            /*
             * Procesamos cada servidor con la misma lógica anterior,
             * pero en el orden de prioridad elegido.
             */
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

                        val modernMatch =
                            Regex(
                                """mega\.nz/embed/([^#!?]+)#(.+)""",
                                RegexOption.IGNORE_CASE
                            ).find(urlVideo)

                        if (modernMatch != null) {

                            handle =
                                modernMatch
                                    .groupValues[1]
                                    .trim()

                            key =
                                modernMatch
                                    .groupValues[2]
                                    .trim()

                        } else {

                            val oldMatch =
                                Regex(
                                    """mega\.nz/embed/#!([^!]+)!(.+)""",
                                    RegexOption.IGNORE_CASE
                                ).find(urlVideo)

                            if (oldMatch != null) {

                                handle =
                                    oldMatch
                                        .groupValues[1]
                                        .trim()

                                key =
                                    oldMatch
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

                                    java.net.URLDecoder.decode(
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
                            java.net.URI(urlVideo)

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
             *
             * Estos son los que queremos que aparezcan primero
             * en CloudStream.
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
             * Mega se registra inmediatamente después de HLS.
             *
             * Así queda disponible antes de empezar los
             * extractores que pueden tardar varios segundos.
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
             * Todos los servidores que necesitan extractor.
             *
             * HLS y Mega ya fueron emitidos antes.
             *
             * Los extractores se ejecutan EN PARALELO para que
             * un servidor lento (savefiles, VidGuard, etc.) no
             * bloquee a los demás.
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
                            try {
                                processServer(servidor)
                            } catch (e: Exception) {
                                println(
                                    "AnimoraTV: EXTRACTOR TASK ERROR -> " +
                                        "${e.javaClass.simpleName}: " +
                                        e.message
                                )
                            }
                        }
                    }
                    .awaitAll()
            }

            println(
                "AnimoraTV: FINAL " +
                    "found=$found " +
                    "totalServers=$totalServers " +
                    "totalExtractedLinks=$totalExtractedLinks"
            )

            found

        } catch (e: Exception) {

            println(
                "AnimoraTV: ERROR loadLinks -> ${e.message}"
            )

            false
        }
    }
}
