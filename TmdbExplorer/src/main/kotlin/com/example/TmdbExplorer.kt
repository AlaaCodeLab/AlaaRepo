package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.ActorData

class TmdbExplorer : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "TMDB Explorer"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false

    // 🎯 1. وضع المفتاح مباشرة لضمان العمل في GitHub Actions
    private val apiKey = "231dddd0aa034e0379b327f40b9c251b"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    // ---------- Data classes ----------
    data class TmdbListResponse(
        @JsonProperty("results") val results: List<TmdbItem> = emptyList()
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null
    )

    data class TmdbGenre(@JsonProperty("name") val name: String)

    data class TmdbCompany(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("logo_path") val logoPath: String? = null
    )

    data class TmdbCastMember(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )

    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCastMember> = emptyList())

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null
    )

    data class TmdbDetail(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre> = emptyList(),
        @JsonProperty("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason> = emptyList()
    )

    // ---------- Helpers ----------
    private fun TmdbItem.toSearchResponse(defaultIsMovie: Boolean = true): SearchResponse? {
        val isMovie = if (mediaType != null) mediaType == "movie" else defaultIsMovie
        val displayName = title ?: name ?: return null
        val poster = posterPath?.let { imageBase + it }
        val itemUrl = "$mainUrl/movie_or_tv/${if (isMovie) "movie" else "tv"}/$id"

        return if (isMovie) {
            newMovieSearchResponse(displayName, itemUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(displayName, itemUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ---------- Main page ----------
    override val mainPage = mainPageOf(
        "trending/all/day" to "الأكثر رواجاً اليوم",
        "movie/popular" to "أفلام شائعة",
        "tv/popular" to "مسلسلات شائعة",
        "movie/top_rated" to "أفلام الأعلى تقييماً",
        "tv/top_rated" to "مسلسلات الأعلى تقييماً",
        "movie/now_playing" to "أفلام تُعرض الآن",
        "movie/upcoming" to "أفلام قادمة",
        "discover/movie?with_companies=420" to "Marvel Studios",
        "discover/movie?with_companies=3" to "Pixar",
        "discover/movie?with_companies=174" to "Warner Bros. Pictures"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl/${request.data}${separator}api_key=$apiKey&page=$page&language=ar-SA"
        val res = app.get(url).text
        val parsed = tryParseJson<TmdbListResponse>(res)

        // 🎯 معالجة نوع المحتوى الافتراضي بناءً على رابط الطلب (في حال غياب media_type)
        val isTvRequest = request.data.startsWith("tv/")
        val list = parsed?.results?.mapNotNull { it.toSearchResponse(defaultIsMovie = !isTvRequest) } ?: emptyList()
        return newHomePageResponse(request.name, list)
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/multi?api_key=$apiKey&query=$query&include_adult=false&language=ar-SA"
        val res = app.get(url).text
        val parsed = tryParseJson<TmdbListResponse>(res) ?: return emptyList()
        return parsed.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .mapNotNull { it.toSearchResponse() }
    }

    // ---------- Load details ----------
    override suspend fun load(url: String): LoadResponse? {
        val regex = Regex("movie_or_tv/(movie|tv)/(\\d+)")
        val match = regex.find(url) ?: return null
        val type = match.groupValues[1]
        val id = match.groupValues[2]

        val detailUrl = "$mainUrl/$type/$id?api_key=$apiKey&append_to_response=credits&language=ar-SA"
        val res = app.get(detailUrl).text
        val detail = tryParseJson<TmdbDetail>(res) ?: return null

        val displayName = detail.title ?: detail.name ?: "Unknown"
        val poster = detail.posterPath?.let { imageBase + it }
        val background = detail.backdropPath?.let { imageBase + it }
        val actors = detail.credits?.cast?.take(15)?.map {
            ActorData(
                Actor(it.name, it.profilePath?.let { p -> imageBase + p }),
                roleString = it.character
            )
        } ?: emptyList()

        val tags = detail.genres.map { it.name } + detail.productionCompanies.map { it.name }
        val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()

        val calculatedScore = detail.voteAverage?.let { Score.from(it, 10) }

        return if (type == "movie") {
            newMovieLoadResponse(displayName, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = detail.overview
                this.tags = tags
                this.year = year
                this.score = calculatedScore
                this.duration = detail.runtime
                this.actors = actors
            }
        } else {
            val episodes = detail.seasons.map { season ->
                newEpisode(url) {
                    this.name = season.name ?: "Season ${season.seasonNumber}"
                    this.season = season.seasonNumber
                    this.episode = 1
                }
            }
            newTvSeriesLoadResponse(displayName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = detail.overview
                this.tags = tags
                this.year = year
                this.score = calculatedScore
                this.actors = actors
            }
        }
    }

    // ---------- No playback: explore-only provider ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}