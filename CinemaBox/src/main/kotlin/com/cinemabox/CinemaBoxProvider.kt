package com.cinemabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Calendar

class CinemaBoxProvider : MainAPI() {
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
            if (items.isNotEmpty() && !section.title.isNullOrEmpty()) {
                homeSections.add(HomePageList(section.title!!, items))
            }
        }

        return newHomePageResponse(homeSections, false)
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
            Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.YEAR)
        }
        val ratingScore = info.rating?.value?.let { Score.from(it, 10) }
        val isMovie = info.type == "MOVIE"

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, info.episodeId.toString()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.year = year
                this.tags = info.genres
                this.score = ratingScore
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            if (info.episodeId != null) {
                episodesList.add(
                    newEpisode(info.episodeId.toString()) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.year = year
                this.tags = info.genres
                this.score = ratingScore
            }
        }
    }

    // ================= 4. تشغيل السيرفرات واستخراج الروابط (Load Links) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || data == "null") return false

        val streamUrl = "$mainUrl/api/v4/episodes/$data"
        val responseText = app.get(streamUrl).text
        val episodeData = tryParseJson<EpisodeStreamResponse>(responseText)

        var foundLinks = false

        // 1. فحص السيرفرات الخارجية (Servers)
        episodeData?.servers?.forEach { server ->
            val linkUrl = server.url ?: server.link ?: return@forEach
            if (linkUrl.isNotBlank()) {
                loadExtractor(linkUrl, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // 2. فحص مصادر الفيديوهات المباشرة (Sources)
        episodeData?.sources?.forEach { source ->
            val linkUrl = source.file ?: source.url ?: return@forEach
            if (linkUrl.isNotBlank()) {
                val isHls = linkUrl.contains(".m3u8")
                callback(
                    ExtractorLink(
                        source = name,
                        name = name + (source.quality?.let { " ($it)" } ?: ""),
                        url = linkUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = isHls
                    )
                )
                foundLinks = true
            }
        }

        // 3. فحص روابط الفيديو المباشرة الفردية (url / stream_url / video_url / link)
        val directUrl = episodeData?.streamUrl ?: episodeData?.url ?: episodeData?.videoUrl ?: episodeData?.link
        if (!directUrl.isNullOrBlank()) {
            val isHls = directUrl.contains(".m3u8")
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = isHls
                )
            )
            foundLinks = true
        }

        // 4. Fallback: البحث بـ Regex عن أي روابط HLS (.m3u8) أو MP4 داخل النص
        if (!foundLinks) {
            val regex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")
            regex.findAll(responseText).forEach { match ->
                val linkUrl = match.value
                val isHls = linkUrl.contains(".m3u8")
                callback(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = linkUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = isHls
                    )
                )
                foundLinks = true
            }
        }

        return foundLinks || responseText.isNotBlank()
    }

    // ================= Data Models =================
    data class HomeResponse(
        @JsonProperty("sections") val sections: List<Section>?
    )

    data class Section(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("section_type") val sectionType: String?,
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

    data class RatingInfo(
        @JsonProperty("value") val value: Double?
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
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("rating") val rating: RatingInfo?
    )

    data class EpisodeStreamResponse(
        @JsonProperty("servers") val servers: List<StreamServer>?,
        @JsonProperty("sources") val sources: List<StreamSource>?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("stream_url") val streamUrl: String?,
        @JsonProperty("video_url") val videoUrl: String?,
        @JsonProperty("link") val link: String?
    )

    data class StreamServer(
        @JsonProperty("name") val name: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("link") val link: String?
    )

    data class StreamSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?
    )
}
