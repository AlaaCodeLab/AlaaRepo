package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://cinema.albox.co"
    override var name = "Cinema Box"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ================= 1. الصفحة الرئيسية (Main Page) =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/api/v4/home"
        val response = app.get(url).parsedSafe<HomeResponse>()
        val homeSections = ArrayList<HomePageList>()

        response?.sections?.forEach { section ->
            val items = ArrayList<SearchResponse>()
            section.data?.forEach { item ->
                val title = item.title ?: return@forEach
                val id = item.id ?: return@forEach
                val poster = item.style?.image
                val tvType = if (item.type == "SERIES") TvType.TvSeries else TvType.Movie

                items.add(
                    if (tvType == TvType.TvSeries) {
                        newTvSeriesSearchResponse(title, "$mainUrl/api/v4/shows/$id", tvType) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(title, "$mainUrl/api/v4/shows/$id", tvType) {
                            this.posterUrl = poster
                        }
                    }
                )
            }
            if (items.isNotEmpty() && !section.title.isNullEmpty()) {
                homeSections.add(HomePageList(section.title!!, items))
            }
        }

        return HomePageResponse(homeSections)
    }

    // ================= 2. البحث (Search) =================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/v4/search?term=$query&page_number=1&page_size=20"
        val response = app.get(url).parsedSafe<SearchApiResponse>()

        return response?.results?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val id = item.id ?: return@mapNotNull null
            val poster = item.style?.image
            val tvType = if (item.type == "SERIES") TvType.TvSeries else TvType.Movie

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, "$mainUrl/api/v4/shows/$id", tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            } else {
                newMovieSearchResponse(title, "$mainUrl/api/v4/shows/$id", tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
        } ?: emptyList()
    }

    // ================= 3. تفاصيل العمل (Load Details) =================
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).parsedSafe<ShowDetailResponse>()
        val info = response?.postInfo ?: throw ErrorLoadingException("Failed to load show details")

        val title = info.title ?: ""
        val poster = info.image
        val background = info.backgroundImage
        val description = info.description
        val year = info.releaseDate?.toLongOrNull()?.let {
            java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.YEAR)
        }
        val isMovie = info.type == "MOVIE"

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, info.episodeId.toString()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.year = year
                this.tags = info.genres
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            if (info.episodeId != null) {
                episodesList.add(
                    Episode(
                        data = info.episodeId.toString(),
                        name = title,
                        season = 1,
                        episode = 1
                    )
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.year = year
                this.tags = info.genres
            }
        }
    }

    // ================= 4. تشغيل السيرفرات (Load Links) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = "$mainUrl/api/v4/episodes/$data"
        val response = app.get(streamUrl).text
        return true
    }

    // ================= Data Models =================
    data class HomeResponse(
        @JsonProperty("sections") val sections: List<Section>?
    )

    data class Section(
        @JsonProperty("title") val title: String?,
        @JsonProperty("data") val data: List<ShowItem>?
    )

    data class SearchApiResponse(
        @JsonProperty("results") val results: List<ShowItem>?
    )

    data class ShowItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("style") val style: Style?
    )

    data class Style(
        @JsonProperty("image") val image: String?
    )

    data class ShowDetailResponse(
        @JsonProperty("post_info") val postInfo: PostInfo?
    )

    data class PostInfo(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("background_image") val backgroundImage: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("episode_id") val episodeId: Int?,
        @JsonProperty("genres") val genres: List<String>?
    )
}
