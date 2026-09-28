package com.animeonline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimeOnlineProvider : MainAPI() {

    override var mainUrl = "https://ww3.animeonline.ninja"
    override var name = "AnimeOnline"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries
    )

    override var lang = "es"

    private val testUrl =
        "https://strm3.uqload.vc/hls2/02/02006/3pu70qn8uyul_n/master.m3u8?t=dYDM5uK76QfJ2L7vW4xk0ZgF7zVI28FGERMjvbunRI8&s=1790553444&e=14400&v=207157&i=201.178&sp=0"

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        return listOf(
            newAnimeSearchResponse(
                "AnimeOnline Uqload TEST",
                "$mainUrl/test",
                TvType.Anime
            )
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val episodeList =
            ArrayList<Episode>()

        episodeList.add(
            newEpisode(testUrl) {
                name = "Uqload HLS Test"
                episode = 1
                season = 1
            }
        )

        return newTvSeriesLoadResponse(
            "AnimeOnline Uqload TEST",
            url,
            TvType.Anime,
            episodeList
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback(
            ExtractorLink(
                source = "Uqload",
                name = "Uqload HLS",
                url = data,
                referer = "https://uqload.vc/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }
}
