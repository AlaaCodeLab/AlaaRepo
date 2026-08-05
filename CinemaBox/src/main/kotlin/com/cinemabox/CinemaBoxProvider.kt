package com.cinemabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import java.net.URLDecoder

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

    private fun String.encode(): String = try { URLEncoder.encode(this, "UTF-8") } catch (_: Exception) { this }
    private fun String.decode(): String = try { URLDecoder.decode(this, "UTF-8") } catch (_: Exception) { this }

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
                val poster = item.style?.image ?: ""
                val type = item.type ?: "MOVIE"
                val desc = (item.description ?: "").encode()
                val rating = item.rating?.toString() ?: ""

                val tvType = if (type == "SERIES") TvType.TvSeries else TvType.Movie

                // تضمين البيانات الغنية محلياً لضمان عدم التداخل وعرض القصة بالكامل
                val itemDataUrl = "$mainUrl/api/v4/shows/episodes/$id/files?title=${title.encode()}&type=$type&poster=${poster.encode()}&desc=$desc&rating=$rating"

                items.add(
                    if (tvType == TvType.TvSeries) {
                        newTvSeriesSearchResponse(title, itemDataUrl, tvType) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(title, itemDataUrl, tvType) {
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
            val poster = item.style?.image ?: ""
            val type = item.type ?: "MOVIE"
            val year = item.year?.toString() ?: ""

            val tvType = if (type == "SERIES") TvType.TvSeries else TvType.Movie
            val itemDataUrl = "$mainUrl/api/v4/shows/episodes/$id/files?title=${title.encode()}&type=$type&poster=${poster.encode()}&year=$year"

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, itemDataUrl, tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            } else {
                newMovieSearchResponse(title, itemDataUrl, tvType) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
        } ?: emptyList()
    }

    // ================= 3. تفاصيل العمل (Load Details) =================
    override suspend fun load(url: String): LoadResponse {
        val queryParams = url.substringAfter("?", "").split("&").associate {
            val parts = it.split("=")
            (parts.getOrNull(0) ?: "") to (parts.getOrNull(1)?.decode() ?: "")
        }

        val showId = url.substringBefore("?").split("/").getOrNull(url.substringBefore("?").split("/").size - 2)
            ?.toIntOrNull()
            ?: url.substringBefore("?").substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid Show ID")

        val filesUrl = "$mainUrl/api/v4/shows/episodes/$showId/files"
        val responseText = app.get(filesUrl, headers = commonHeaders).text
        val filesResponse = tryParseJson<FilesApiResponse>(responseText)

        val title = queryParams["title"]?.takeIf { it.isNotBlank() } ?: filesResponse?.showTitle ?: "Cinema Box"
        val poster = queryParams["poster"]?.takeIf { it.isNotBlank() } ?: filesResponse?.image
        val description = queryParams["desc"]?.takeIf { it.isNotBlank() }
        val ratingScore = queryParams["rating"]?.toDoubleOrNull()?.let { Score.from(it, 10) }
        val year = queryParams["year"]?.toIntOrNull()
        val type = queryParams["type"] ?: filesResponse?.showType

        val isMovie = type == "MOVIE" || filesResponse?.showType == "MOVIE" || filesResponse?.episodes.isNullOrEmpty()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, filesUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
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
