package com.cinemabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        "Referer" to mainUrl
    )

    // ================= 1. الصفحة الرئيسية (Main Page) =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/api/v4/home"
        val response = app.get(url, headers = commonHeaders).parsedSafe<HomeResponse>()
        val homeSections = ArrayList<HomePageList>()

        response?.sections?.forEach { section ->
            val items = ArrayList<SearchResponse>()
            section.data?.forEach { item ->
                val title = item.title ?: return@forEach
                val id = item.id ?: return@forEach
                val poster = item.style?.image
                val isSeries = item.type == "SERIES"
                val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

                // حفظ البيانات الغنية لضمان عدم التداخل وعرض القصة والتفاصيل بالكامل
                val payload = LoadDataPayload(
                    id = id,
                    title = title,
                    poster = poster,
                    type = item.type,
                    description = item.description,
                    rating = item.rating,
                    genres = item.genres
                )
                val payloadJson = toJson(payload)

                items.add(
                    if (tvType == TvType.TvSeries) {
                        newTvSeriesSearchResponse(title, payloadJson, tvType) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(title, payloadJson, tvType) {
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
        val response = app.get(url, headers = commonHeaders).parsedSafe<SearchApiResponse>()

        return response?.results?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val id = item.id ?: return@mapNotNull null
            val poster = item.style?.image
            val isSeries = item.type == "SERIES"
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            val payload = LoadDataPayload(
                id = id,
                title = title,
                poster = poster,
                type = item.type,
                year = item.year
            )
            val payloadJson = toJson(payload)

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, payloadJson, tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            } else {
                newMovieSearchResponse(title, payloadJson, tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
        } ?: emptyList()
    }

    // ================= 3. تفاصيل العمل (Load Details) =================
    override suspend fun load(url: String): LoadResponse {
        val payload = tryParseJson<LoadDataPayload>(url)
        val showId = payload?.id ?: url.substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid Show ID")

        val filesUrl = "$mainUrl/api/v4/shows/episodes/$showId/files"
        val responseText = app.get(filesUrl, headers = commonHeaders).text
        val filesResponse = tryParseJson<FilesApiResponse>(responseText)

        val title = payload?.title ?: filesResponse?.showTitle ?: "Cinema Box"
        val poster = payload?.poster ?: filesResponse?.image
        val description = payload?.description?.takeIf { it.isNotBlank() }
        val ratingScore = payload?.rating?.let { Score.from(it, 10) }
        val year = payload?.year
        val genres = payload?.genres

        val isMovie = (payload?.type == "MOVIE" || filesResponse?.showType == "MOVIE") || filesResponse?.episodes.isNullOrEmpty()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, filesUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.score = ratingScore
            }
        } else {
            val episodesList = mutableListOf<Episode>()

            filesResponse?.episodes?.forEachIndexed { index, ep ->
                val epId = ep.id?.toString() ?: showId.toString()
                val epNum = ep.episodeNumber ?: (index + 1)
                val seasonNum = ep.seasonNumber ?: 1
                val epName = ep.title?.takeIf { it.isNotBlank() } ?: "الحلقة $epNum"

                episodesList.add(
                    newEpisode("$mainUrl/api/v4/shows/episodes/$epId/files") {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = ep.image ?: poster
                    }
                )
            }

            if (episodesList.isEmpty()) {
                episodesList.add(
                    newEpisode(filesUrl) {
                        this.name = "$title - الحلقة 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
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
    data class LoadDataPayload(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("genres") val genres: List<String>? = null
    )

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
