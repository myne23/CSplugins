package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

class ExampleProvider : MainAPI() {

    override var mainUrl = "https://wooflix.media/"
    override var name = "Cinezo Test"

    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "es"
    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        return listOf(
            newTvSeriesSearchResponse(
                "Breaking Bad",
                "https://wooflix.media/play/tv/1396",
                TvType.TvSeries,
                false
            ) {
                posterUrl =
                "https://image.tmdb.org/t/p/w500/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg"
                year = 2008
            }
        )
    }

    override suspend fun load(url: String): LoadResponse {

        val episodes = listOf(
            newEpisode("1396|1|1") {
                name = "Pilot"
                season = 1
                episode = 1
            }
        )

        return newTvSeriesLoadResponse(
            "Breaking Bad",
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl =
            "https://image.tmdb.org/t/p/w500/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg"
            year = 2008
            plot =
            "A chemistry teacher diagnosed with cancer turns to manufacturing methamphetamine."
            showStatus = ShowStatus.Completed
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split("|")

        if (parts.size != 3) {
            return false
        }

        val tmdbId = parts[0]
        val season = parts[1]
        val episode = parts[2]

        val url =
        "https://proxy1.flikhub.net/tv?id=$tmdbId&season=$season&episode=$episode"

        val response = try {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "text/event-stream",
                    "Referer" to "https://player.cinezo.live/"
                )
            )
        } catch (e: Exception) {
            println("CINEZO ERROR: ${e.message}")
            return false
        }

        println("CINEZO STATUS: ${response.code}")
        println("CINEZO RESPONSE LENGTH: ${response.text.length}")

        if (!response.isSuccessful) {
            return false
        }

        for (line in response.text.lines()) {

            if (!line.startsWith("data: ")) {
                continue
            }

            val json = try {
                JSONObject(line.removePrefix("data: "))
            } catch (_: Exception) {
                continue
            }

            println("CINEZO EVENT: ${json.optString("type")}")

            when (json.optString("type")) {

                "meta" -> {
                    val subtitles = json.optJSONArray("subtitles")

                    if (subtitles != null) {
                        for (i in 0 until subtitles.length()) {

                            val subtitle = subtitles.optJSONObject(i)
                            ?: continue

                            val label = subtitle.optString("label")
                            val file = subtitle.optString("file")

                            if (file.isNotBlank()) {
                                subtitleCallback(
                                    newSubtitleFile(label, file)
                                )
                            }
                        }
                    }
                }

                "source" -> {
                    val source = json.optJSONObject("source")
                    ?: continue

                    val sourceName = source.optString("source")
                    val label = source.optString("label")
                    val sourceUrl = source.optString("url")
                    val type = source.optString("type")

                    println(
                        "CINEZO SOURCE: $sourceName | $label | $type | $sourceUrl"
                    )

                    if (sourceUrl.isBlank()) {
                        continue
                    }

                    val linkType = when (type.lowercase()) {
                        "dash" -> ExtractorLinkType.DASH
                        "m3u8" -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            sourceName.ifBlank { "Cinezo" },
                            label.ifBlank { sourceName },
                            sourceUrl,
                            linkType
                        ) {
                            referer = "https://player.cinezo.live/"
                            quality = Qualities.Unknown.value
                        }
                    )
                }

                "done" -> {
                    println("CINEZO DONE")
                    break
                }
            }
        }

        return true
    }
}
