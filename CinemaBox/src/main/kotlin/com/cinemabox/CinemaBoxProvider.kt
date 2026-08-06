package com.cinemabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty

class CinemaBoxProvider : MainAPI() {
    override var mainUrl = "https://cinema.albox.co"
    override var name = "Cinema Box"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36",
        "Accept" to "application/json, text/plain, */*",
        "app-version" to "1.10.1",
        "device-id" to "995266",
        "x-device-brand" to "pc",
        "x-local-before" to "true",
        "Referer" to mainUrl
    )

    // ================= 1. الصفحة الرئيسية المنظمة (Clean Dynamic Main Page) =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homeSections = ArrayList<HomePageList>()

        // 1.1 الكاروسيل العلوي الرئيسي فقط من الصفحة الرئيسية (Top Carousel)
        try {
            val homeUrl = "$mainUrl/api/v4/home"
            val homeResponse = app.get(homeUrl, headers = commonHeaders).parsedSafe<HomeResponse>()
            homeResponse?.sections?.firstOrNull { sec ->
                sec.data.orEmpty().any { it.type == "MOVIE" || it.type == "SERIES" }
            }?.let { carouselSec ->
                val items = carouselSec.data?.mapNotNull { item ->
                    val title = item.title ?: return@mapNotNull null
                    val id = item.id ?: return@mapNotNull null
                    val poster = item.style?.image
                    val isSeries = item.type == "SERIES"
                    val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
                    val itemUrl = "$mainUrl/show/$id"

                    if (tvType == TvType.TvSeries) {
                        newTvSeriesSearchResponse(title, itemUrl, tvType) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(title, itemUrl, tvType) {
                            this.posterUrl = poster
                        }
                    }
                }
                if (!items.isNullOrEmpty()) {
                    homeSections.add(HomePageList("ترند الأسبوع", items))
                }
            }
        } catch (_: Exception) {}

        // 1.2 الأقسام التسعة الرئيسية المخصصة لمكتبة سينما بوكس بالكامل
        val categories = listOf(
            "25" to "أفلام أجنبية",
            "24" to "أفلام عربية",
            "28" to "أفلام آسيوية",
            "31" to "أفلام أنمي",
            "26" to "أفلام تركية",
            "47" to "أفلام مدبلجة",
            "33" to "مسلسلات أجنبية",
            "34" to "مسلسلات عربية",
            "35" to "مسلسلات تركية"
        )

        categories.forEach { (catId, catName) ->
            try {
                val catUrl = "$mainUrl/api/v4/shows/shows/dynamic/$catId"
                val responseText = app.get(catUrl, headers = commonHeaders).text
                val dynamicResponse = tryParseJson<DynamicShowResponse>(responseText)

                val items = ArrayList<SearchResponse>()
                dynamicResponse?.sections?.forEach { sec ->
                    sec.data?.forEach { item ->
                        val title = item.title ?: return@forEach
                        val id = item.id ?: return@forEach
                        val poster = item.style?.image
                        val isSeries = item.type == "SERIES" || catName.contains("مسلسلات")
                        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
                        val itemUrl = "$mainUrl/show/$id"

                        items.add(
                            if (tvType == TvType.TvSeries) {
                                newTvSeriesSearchResponse(title, itemUrl, tvType) {
                                    this.posterUrl = poster
                                }
                            } else {
                                newMovieSearchResponse(title, itemUrl, tvType) {
                                    this.posterUrl = poster
                                }
                            }
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    val cleanList = items.distinctBy { it.name }
                    homeSections.add(HomePageList(catName, cleanList))
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(homeSections, false)
    }

    // ================= 2. البحث (Search) =================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/v4/search?term=$query&page_number=1&page_size=30"
        val response = app.get(url, headers = commonHeaders).parsedSafe<SearchApiResponse>()

        return response?.results?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val id = item.id ?: return@mapNotNull null
            val poster = item.style?.image
            val isSeries = item.type == "SERIES"
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            val itemUrl = "$mainUrl/show/$id"

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, itemUrl, tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            } else {
                newMovieSearchResponse(title, itemUrl, tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
        } ?: emptyList()
    }

    // ================= 3. تفاصيل العمل (Load Details) =================
    override suspend fun load(url: String): LoadResponse {
        val showId = url.split("/").getOrNull(url.split("/").size - 1)
            ?.substringBefore("?")
            ?.takeIf { it.all { char -> char.isDigit() } }
            ?: url.substringAfterLast("/")
                .substringBefore("?")
                .substringBefore(".json")

        val dynamicUrl = "$mainUrl/api/v4/shows/shows/dynamic/$showId"
        val responseText = app.get(dynamicUrl, headers = commonHeaders).text
        val dynamicResponse = tryParseJson<DynamicShowResponse>(responseText)

        val postInfo = dynamicResponse?.postInfo
        val title = postInfo?.title ?: "Cinema Box"
        val poster = postInfo?.image ?: postInfo?.logo
        val plotDesc = postInfo?.description
        val ratingScore = postInfo?.rating?.value?.let { Score.from(it, 10) }
        val genres = postInfo?.genres
        val isSeries = postInfo?.type == "SERIES"

        val episodesList = mutableListOf<Episode>()

        if (isSeries) {
            dynamicResponse?.sections?.forEach { sec ->
                val isEpisodesSection = sec.sectionType == "episodes" ||
                        sec.title?.lowercase()?.contains("episodes") == true ||
                        sec.title?.lowercase()?.contains("حلقات") == true

                if (isEpisodesSection) {
                    sec.data?.forEachIndexed { index, epItem ->
                        val epId = epItem.id ?: return@forEachIndexed
                        val epName = epItem.title?.takeIf { it.isNotBlank() } ?: "الحلقة ${index + 1}"
                        val epPoster = epItem.style?.image ?: poster

                        episodesList.add(
                            newEpisode("$mainUrl/api/v4/shows/episodes/$epId/files") {
                                this.name = epName
                                this.episode = index + 1
                                this.season = 1
                                this.posterUrl = epPoster
                            }
                        )
                    }
                }
            }
        }

        val isMovie = !isSeries || episodesList.isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/api/v4/shows/episodes/$showId/files") {
                this.posterUrl = poster
                this.plot = plotDesc
                this.tags = genres
                this.score = ratingScore
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = plotDesc
                this.tags = genres
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

        val fetchUrl = if (data.contains("http")) data else "$mainUrl/api/v4/shows/episodes/$data/files"
        val responseText = app.get(fetchUrl, headers = commonHeaders).text
        val filesResponse = tryParseJson<FilesApiResponse>(responseText)

        var foundLinks = false

        // 1. جلب الفيديوهات المباشرة بالدقات المختلفة (1080p, 720p, 480p)
        filesResponse?.videos?.forEach { video ->
            val linkUrl = video.url ?: return@forEach
            if (linkUrl.isNotBlank()) {
                val isHls = linkUrl.contains(".m3u8")
                val qualityName = video.quality ?: "Default"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $qualityName",
                        url = linkUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        getQualityFromName(qualityName)
                    }
                )
                foundLinks = true
            }
        }

        // 2. جلب الترجمات المتاحة
        filesResponse?.subtitles?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.srt ?: return@forEach
            val langName = sub.language ?: "ar"
            subtitleCallback(
                SubtitleFile(langName, subUrl)
            )
        }

        // 3. Fallback: البحث بـ Regex عن أي روابط MP4 أو M3U8 داخل النص
        if (!foundLinks && responseText.isNotBlank()) {
            val regex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")
            regex.findAll(responseText).forEach { match ->
                val linkUrl = match.value
                val isHls = linkUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = linkUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                foundLinks = true
            }
        }

        return foundLinks
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
        @JsonProperty("description") val description: String?,
        @JsonProperty("rating") val rating: Double?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("style") val style: Style?
    )

    data class Style(
        @JsonProperty("image") val image: String?
    )

    data class DynamicShowResponse(
        @JsonProperty("post_info") val postInfo: DynamicPostInfo?,
        @JsonProperty("sections") val sections: List<DynamicSection>?
    )

    data class DynamicPostInfo(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("logo") val logo: String?,
        @JsonProperty("background_image") val backgroundImage: String?,
        @JsonProperty("rating") val rating: RatingObj?,
        @JsonProperty("genres") val genres: List<String>?
    )

    data class RatingObj(
        @JsonProperty("value") val value: Double?
    )

    data class DynamicSection(
        @JsonProperty("title") val title: String?,
        @JsonProperty("section_type") val sectionType: String?,
        @JsonProperty("data") val data: List<DynamicItem>?
    )

    data class DynamicItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("style") val style: Style?
    )

    data class FilesApiResponse(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("show_id") val showId: Int?,
        @JsonProperty("show_title") val showTitle: String?,
        @JsonProperty("show_type") val showType: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("videos") val videos: List<VideoSource>?,
        @JsonProperty("subtitles") val subtitles: List<SubtitleSource>?,
        @JsonProperty("episodes") val episodes: List<CinemaBoxEpisode>?
    )

    data class CinemaBoxEpisode(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("season_number") val seasonNumber: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("videos") val videos: List<VideoSource>?,
        @JsonProperty("subtitles") val subtitles: List<SubtitleSource>?
    )

    data class VideoSource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?
    )

    data class SubtitleSource(
        @JsonProperty("vtt") val vtt: String?,
        @JsonProperty("srt") val srt: String?,
        @JsonProperty("language") val language: String?
    )
}
