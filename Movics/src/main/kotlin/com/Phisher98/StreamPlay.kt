package com.phisher98

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

open class StreamPlay(val sharedPref: SharedPreferences? = null) : MainAPI() {
    override var name = "Movics"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val hasChromecastSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    init {
        if (sharedPref != null) {
            companionSharedPref = sharedPref
        }
    }

    val token: String?
        get() = (sharedPref ?: companionSharedPref)?.getString("token", null)

    val langCode: String?
        get() = (sharedPref ?: companionSharedPref)?.getString("tmdb_language_code", "en-US")

    val wyziekey: String?
        get() = (sharedPref ?: companionSharedPref)?.getString("wyzie_key", null)


    val wpRedisInterceptor by lazy { CloudflareKiller() }

    /** AUTHOR : hexated & Phisher & Code */
    companion object {
        var companionSharedPref: SharedPreferences? = null

        /** TOOLS */
        private const val OFFICIAL_TMDB_URL = "https://api.themoviedb.org/3"
        private const val Cinemeta = "https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb"
        private const val REMOTE_PROXY_LIST = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Proxylist.txt"
        private const val apiKey = BuildConfig.TMDB_API
        private var currentBaseUrl: String? = null


        private val apiMutex = Mutex() // Prevents race conditions
        private const val TAG = "StreamPlay"
        suspend fun getApiBase(): String {
            StreamPlayCache.getCachedApiBase()?.let {
                currentBaseUrl = it
                return it
            }

            currentBaseUrl?.let { return it }
            return apiMutex.withLock {
                currentBaseUrl?.let { return it }

                if (checkConnectivity(OFFICIAL_TMDB_URL)) {
                    Log.d(TAG, "✅ Using official TMDB API")
                    currentBaseUrl = OFFICIAL_TMDB_URL
                    StreamPlayCache.cacheApiBase(OFFICIAL_TMDB_URL, success = true)
                    return OFFICIAL_TMDB_URL
                }

                val proxies = fetchProxyList()
                if (proxies.isEmpty()) {
                    Log.e(TAG, "❌ No proxies found, falling back to official")
                    StreamPlayCache.cacheApiBase(OFFICIAL_TMDB_URL, success = false)
                    return OFFICIAL_TMDB_URL
                }

                val workingProxy = coroutineScope {
                    val deferredChecks = proxies.map { proxy ->
                        async {
                            if (checkConnectivity(proxy)) proxy else null
                        }
                    }
                    deferredChecks.awaitAll().firstOrNull { it != null }
                }

                if (workingProxy != null) {
                    Log.d(TAG, "✅ Switched to proxy: $workingProxy")
                    currentBaseUrl = workingProxy
                    StreamPlayCache.cacheApiBase(workingProxy, success = true)
                    return workingProxy
                }

                Log.e(TAG, "❌ All proxies failed, fallback to official")
                currentBaseUrl = OFFICIAL_TMDB_URL
                StreamPlayCache.cacheApiBase(OFFICIAL_TMDB_URL, success = false)
                OFFICIAL_TMDB_URL
            }
        }

        private suspend fun checkConnectivity(url: String): Boolean {
            val testUrl = "$url/configuration?api_key=$apiKey"

            return withTimeoutOrNull(2000) { // 2s timeout max
                try {
                    val response = app.get(
                        testUrl,
                        timeout = 1500, // Fast socket timeout
                        headers = mapOf("Cache-Control" to "no-cache")
                    )
                    response.code == 200 || response.code == 304
                } catch (_: Exception) {
                    false
                }
            } ?: false
        }

        private suspend fun fetchProxyList(): List<String> = try {
            val response = app.get(REMOTE_PROXY_LIST, timeout = 5000).text
            val json = JSONObject(response)
            val arr = json.getJSONArray("proxies")

            // Convert to list and clean strings
            (0 until arr.length()).map { arr.getString(it).trim().removeSuffix("/") }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching proxy list: ${e.message}")
            emptyList()
        }

        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        private var cachedDomains: DomainsParser? = null

        suspend fun getDomains(forceRefresh: Boolean = false): DomainsParser? {
            if (cachedDomains == null || forceRefresh) {
                try {
                    cachedDomains = app.get(DOMAINS_URL).parsedSafe<DomainsParser>()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return cachedDomains
        }

        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val jikanAPI = "https://api.jikan.moe/v4"

        const val reanime = "https://reanime.to"
        const val kissKhAPI = "https://kisskh.nl"
        const val watchSomuchAPI = "https://watchsomuch.tv" // sub only
        const val nineTvAPI = "https://moviesapi.club"
        const val zshowAPI = BuildConfig.ZSHOW_API
        const val allmovielandAPI = "https://allmovieland.io"
        const val animetoshoAPI = "https://animetosho.xyz"
        const val nepuAPI = "https://nepu.to"
        const val dahmerMoviesAPI = "https://a.111477.xyz"
        const val animepaheAPI = "https://animepahe.pw"
        const val SubtitlesAPI = "https://opensubtitles-v3.strem.io"
        const val WyZIESUBAPI = "https://sub.wyzie.ru"
        const val WYZIESubsAPI = "https://sub.wyzie.ru"
        const val RiveStreamAPI = "https://www.rivestream.app"
        const val thrirdAPI = BuildConfig.SUPERSTREAM_THIRD_API
        const val fourthAPI = BuildConfig.SUPERSTREAM_FOURTH_API
        const val NuvFeb = BuildConfig.NuvFeb
        const val KickassAPI = "https://kaa.lt"
        const val Vidsrcxyz = "https://vidsrc-embed.su"
        const val movieBox= "https://api.inmoviebox.com"
        const val vidrock = "https://vidrock.ru"
        const val vidlink = "https://vidlink.pro"
        const val vidfastProApi = "https://vidfast.pro"
        const val videasyAPI = "https://api.videasy.net"
        const val moviesClubApi = "https://moviesapi.club"
        const val cinemacity = "https://cinemacity.cc"
        const val hexaSU = "https://theemoviedb.hexa.su"
        const val mappleAPI = "https://mapple.uk"
        const val twoEmbedAPI = "https://www.2embed.cc"
        const val xpassAPI = "https://play.xpass.top"
        const val vaplayer = "https://streamdata.vaplayer.ru"
        const val peachifyAPI = "https://peachify.top"
        const val anineko = "https://anineko.to"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private val builtInMainPage = listOf(
        "/trending/all/day?api_key=$apiKey&region=US" to "Trending",
        "/trending/movie/week?api_key=$apiKey&region=US&with_original_language=en" to "Popular Movies",
        "/trending/tv/week?api_key=$apiKey&region=US&with_original_language=en" to "Popular TV Shows",
        "/tv/airing_today?api_key=$apiKey&region=US&with_original_language=en" to "Airing Today TV Shows",
        "/discover/tv?api_key=$apiKey&with_networks=213" to "Netflix",
        "/discover/tv?api_key=$apiKey&with_networks=1024" to "Amazon",
        "/discover/tv?api_key=$apiKey&with_networks=2739" to "Disney+",
        "/discover/tv?api_key=$apiKey&with_networks=453" to "Hulu",
        "/discover/tv?api_key=$apiKey&with_networks=2552" to "Apple TV+",
        "/discover/tv?api_key=$apiKey&with_networks=49" to "HBO",
        "/discover/tv?api_key=$apiKey&with_networks=4330" to "Paramount+",
        "/discover/tv?api_key=$apiKey&with_networks=3353" to "Peacock",
        "/discover/movie?api_key=$apiKey&sort_by=popularity.desc&with_origin_country=IN&release_date.gte=${getDate().lastWeekStart}&release_date.lte=${getDate().today}" to "Trending Indian Movies",
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().today}&air_date.gte=${getDate().today}" to "Airing Today Anime",
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().nextWeek}&air_date.gte=${getDate().today}" to "On The Air Anime",
        "/discover/movie?api_key=$apiKey&with_keywords=210024|222243" to "Anime Movies",
        "/movie/top_rated?api_key=$apiKey&region=US" to "Top Rated Movies",
        "/tv/top_rated?api_key=$apiKey&region=US" to "Top Rated TV Shows",
        "/discover/tv?api_key=$apiKey&with_original_language=ko" to "Korean Shows",
        "/discover/tv?api_key=$apiKey&with_genres=99" to "Documentary",
    )

    override val mainPage by lazy {
        val custom = MovicsCustomSections.load(sharedPref).map {
            MovicsCustomSections.requestData(it.id) to it.name
        }
        mainPageOf(*(builtInMainPage + custom).toTypedArray())
    }

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) { "https://image.tmdb.org/t/p/original/$link"
        } else link
    }

    private suspend fun resolveApiBase(): String {
        StreamPlayCache.getCachedApiBase()?.let {
            currentBaseUrl = it
            return it
        }

        currentBaseUrl?.let { return it }

        return try {
            getApiBase().also {
                sharedPref?.edit { putString("cached_api_base", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving API base: ${e.message}")
            sharedPref?.edit { remove("cached_api_base") }
            currentBaseUrl = null
            getApiBase()
        }
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val customId = MovicsCustomSections.requestId(request.data)
        if (customId != null) {
            val section = MovicsCustomSections.load(sharedPref).firstOrNull { it.id == customId }
                ?: return newHomePageResponse(request.name, emptyList(), false)
            return getCustomMainPage(page, request, section)
        }

        val tmdbAPI = resolveApiBase()

        val adultQuery = if (settingsForProvider.enableAdult) {
            ""
        } else {
            "&without_keywords=190370|13059|226161|195669"
        }

        val type = if (request.data.contains("/movie")) "movie" else "tv"

        val home = app.get(
            url = "$tmdbAPI${request.data}$adultQuery&language=$langCode&page=$page",
            timeout = 10000,
        ).parsed<Results>().results?.mapNotNull {
            it.toSearchResponse(type)
        } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    private suspend fun getCustomMainPage(
        page: Int,
        request: MainPageRequest,
        section: MovicsCustomSection,
    ): HomePageResponse {
        return runCatching {
            when (section.category) {
                MovicsSectionCategory.PEOPLE -> personSection(request, section, page)
                MovicsSectionCategory.PERSON_WORKS -> personWorksSection(request, section)
                MovicsSectionCategory.MOVIES -> exactMediaSection(request, section.copy(mediaType = "movie"))
                MovicsSectionCategory.TV_SHOWS -> exactMediaSection(request, section.copy(mediaType = "tv"))
                MovicsSectionCategory.COLLECTIONS -> collectionSection(request, section)
                MovicsSectionCategory.TMDB_LIST -> listSection(request, section)
                MovicsSectionCategory.TMDB_LINK -> tmdbLinkSection(request, section, page)
                MovicsSectionCategory.LANGUAGE -> languageSection(request, section, page)
                MovicsSectionCategory.KEYWORDS -> discoverSection(request, section, page, "with_keywords")
                MovicsSectionCategory.COMPANIES -> discoverSection(request, section, page, "with_companies")
                MovicsSectionCategory.NETWORKS -> discoverSection(request, section.copy(mediaType = "tv"), page, "with_networks")
                MovicsSectionCategory.GENRES -> discoverSection(request, section, page, "with_genres")
            }
        }.getOrElse { error ->
            Log.e(TAG, "Custom section '${section.name}' failed: ${error.message}")
            throw ErrorLoadingException("Unable to load ${section.name}: ${error.message ?: "TMDB error"}")
        }
    }

    private fun ids(value: String): List<Int> = value.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .distinct()

    private fun apiUrl(base: String, path: String, page: Int? = null): String {
        val separator = if ('?' in path) '&' else '?'
        val pageQuery = page?.let { "&page=$it" }.orEmpty()
        return "$base$path${separator}api_key=$apiKey&language=$langCode$pageQuery"
    }

    private fun JSONObject.toMediaSearch(defaultType: String): SearchResponse? {
        val resolvedType = optString("media_type").takeIf { it == "movie" || it == "tv" }
            ?: defaultType.takeIf { it == "movie" || it == "tv" }
            ?: when {
                optString("title").isNotBlank() || optString("release_date").isNotBlank() -> "movie"
                optString("name").isNotBlank() || optString("first_air_date").isNotBlank() -> "tv"
                else -> "movie"
            }
        val media = Media(
            id = optInt("id").takeIf { it > 0 },
            name = optString("name").takeIf { it.isNotBlank() },
            title = optString("title").takeIf { it.isNotBlank() },
            originalTitle = optString("original_title").takeIf { it.isNotBlank() },
            originalName = optString("original_name").takeIf { it.isNotBlank() },
            mediaType = resolvedType,
            posterPath = optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            voteAverage = optDouble("vote_average").takeIf { !it.isNaN() },
        )
        return newMovieSearchResponse(
            media.title ?: media.name ?: media.originalTitle ?: media.originalName ?: return null,
            Data(id = media.id, type = resolvedType, movicsCustom = true).toJson(),
            if (resolvedType == "tv") TvType.TvSeries else TvType.Movie,
        ) {
            posterUrl = getImageUrl(media.posterPath)
            score = Score.from10(media.voteAverage)
        }
    }

    private fun JSONArray.mediaItems(defaultType: String): List<SearchResponse> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toMediaSearch(defaultType)?.let(::add)
        }
    }

    private suspend fun fetchPersonWorks(personId: Int, mediaType: String): List<SearchResponse> = coroutineScope {
        val filterType = mediaType.takeIf { it == "movie" || it == "tv" }
        val movies = async {
            if (filterType == "tv") emptyList() else runCatching {
                JSONObject(app.get(apiUrl(resolveApiBase(), "/person/$personId/movie_credits")).text)
                    .optJSONArray("cast")?.mediaItems("movie").orEmpty()
            }.getOrDefault(emptyList())
        }
        val shows = async {
            if (filterType == "movie") emptyList() else runCatching {
                JSONObject(app.get(apiUrl(resolveApiBase(), "/person/$personId/tv_credits")).text)
                    .optJSONArray("cast")?.mediaItems("tv").orEmpty()
            }.getOrDefault(emptyList())
        }
        (movies.await() + shows.await()).distinctBy { it.url }
    }

    private suspend fun personWorksSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
    ): HomePageResponse = coroutineScope {
        val works = ids(section.value).map { personId ->
            async { fetchPersonWorks(personId, section.mediaType) }
        }.awaitAll().flatten().distinctBy { it.url }
        newHomePageResponse(request.name, works, false)
    }

    private suspend fun pagedMedia(
        path: String,
        page: Int,
        defaultType: String,
    ): Pair<List<SearchResponse>, Boolean> {
        val base = resolveApiBase()
        val json = JSONObject(app.get(apiUrl(base, path, page)).text)
        val items = json.optJSONArray("results")?.mediaItems(defaultType).orEmpty()
        val totalPages = json.optInt("total_pages", page)
        return items to (page < totalPages)
    }

    private suspend fun discoverSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
        page: Int,
        filter: String,
    ): HomePageResponse {
        val values = section.value.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")
        val mediaTypes = when (section.mediaType) {
            "movie" -> listOf("movie")
            "tv" -> listOf("tv")
            else -> listOf("movie", "tv")
        }
        val results = coroutineScope {
            mediaTypes.map { mediaType ->
                async {
                    pagedMedia(
                        "/discover/$mediaType?$filter=$values&sort_by=popularity.desc",
                        page,
                        mediaType,
                    )
                }
            }.awaitAll()
        }
        return newHomePageResponse(
            request.name,
            results.flatMap { it.first }.distinctBy { it.url },
            results.any { it.second },
        )
    }

    private suspend fun languageSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
        page: Int,
    ): HomePageResponse = coroutineScope {
        val mediaTypes = when (section.mediaType) {
            "movie" -> listOf("movie")
            "tv" -> listOf("tv")
            else -> listOf("movie", "tv")
        }
        val languages = section.value.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val results = languages.flatMap { language ->
            mediaTypes.map { mediaType ->
                async {
                    pagedMedia(
                        "/discover/$mediaType?with_original_language=$language&sort_by=popularity.desc",
                        page,
                        mediaType,
                    )
                }
            }
        }.awaitAll()
        val items = results.flatMap { it.first }.distinctBy { it.url }
        newHomePageResponse(request.name, items, results.any { it.second })
    }

    private suspend fun exactMediaSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
    ): HomePageResponse = coroutineScope {
        val base = resolveApiBase()
        val items = ids(section.value).map { id ->
            async {
                runCatching {
                    JSONObject(app.get(apiUrl(base, "/${section.mediaType}/$id")).text)
                        .toMediaSearch(section.mediaType)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        newHomePageResponse(request.name, items, false)
    }

    private suspend fun collectionSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
    ): HomePageResponse = coroutineScope {
        val base = resolveApiBase()
        val items = ids(section.value).map { id ->
            async {
                runCatching {
                    JSONObject(app.get(apiUrl(base, "/collection/$id")).text)
                        .optJSONArray("parts")?.mediaItems("movie").orEmpty()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.url }
        newHomePageResponse(request.name, items, false)
    }

    private suspend fun listSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
    ): HomePageResponse = coroutineScope {
        val base = resolveApiBase()
        val items = ids(section.value).map { id ->
            async {
                runCatching {
                    JSONObject(app.get(apiUrl(base, "/list/$id")).text)
                        .optJSONArray("items")?.mediaItems(section.mediaType).orEmpty()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.url }
        newHomePageResponse(request.name, items, false)
    }

    private fun JSONObject.toPersonSearch(mediaType: String): SearchResponse? {
        val personId = optInt("id").takeIf { it > 0 } ?: return null
        val personName = optString("name").takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(
            personName,
            Data(id = personId, type = "person", filterType = mediaType, movicsCustom = true).toJson(),
            TvType.TvSeries,
        ) {
            posterUrl = getImageUrl(optString("profile_path").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    private suspend fun personSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
        page: Int,
    ): HomePageResponse = coroutineScope {
        val base = resolveApiBase()
        val personIds = ids(section.value)
        val people: List<SearchResponse>
        val hasNext: Boolean
        if (personIds.isEmpty()) {
            val json = JSONObject(app.get(apiUrl(base, "/person/popular", page)).text)
            val array = json.optJSONArray("results") ?: JSONArray()
            people = buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toPersonSearch(section.mediaType)?.let(::add)
                }
            }
            hasNext = page < json.optInt("total_pages", page)
        } else {
            people = personIds.map { id ->
                async {
                    runCatching {
                        JSONObject(app.get(apiUrl(base, "/person/$id")).text).toPersonSearch(section.mediaType)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            hasNext = false
        }
        newHomePageResponse(request.name, people, hasNext)
    }

    private suspend fun tmdbLinkSection(
        request: MainPageRequest,
        section: MovicsCustomSection,
        page: Int,
    ): HomePageResponse {
        val uri = URI(section.value.trim())
        val segments = uri.path.split('/').filter { it.isNotBlank() }
        val first = segments.getOrNull(0)?.lowercase() ?: throw IllegalArgumentException("Invalid TMDB link")
        val second = segments.getOrNull(1)?.lowercase()
        val numericId = second?.substringBefore('-')?.toIntOrNull()
        return when (first) {
            "person" -> if (numericId != null) {
                personSection(request, section.copy(category = MovicsSectionCategory.PEOPLE, value = numericId.toString()), page)
            } else {
                val base = resolveApiBase()
                val json = JSONObject(app.get(apiUrl(base, "/person/popular", page)).text)
                val array = json.optJSONArray("results") ?: JSONArray()
                val people = buildList {
                    for (index in 0 until array.length()) array.optJSONObject(index)?.toPersonSearch(section.mediaType)?.let(::add)
                }
                newHomePageResponse(request.name, people, page < json.optInt("total_pages", page))
            }
            "collection" -> collectionSection(request, section.copy(value = numericId?.toString() ?: error("Collection ID missing")))
            "list" -> listSection(request, section.copy(value = numericId?.toString() ?: error("List ID missing")))
            "keyword" -> discoverSection(request, section.copy(value = numericId?.toString() ?: error("Keyword ID missing")), page, "with_keywords")
            "company" -> discoverSection(request, section.copy(value = numericId?.toString() ?: error("Company ID missing")), page, "with_companies")
            "network" -> discoverSection(request, section.copy(mediaType = "tv", value = numericId?.toString() ?: error("Network ID missing")), page, "with_networks")
            "genre" -> discoverSection(request, section.copy(value = numericId?.toString() ?: error("Genre ID missing")), page, "with_genres")
            "discover" -> {
                val mediaType = second?.takeIf { it == "movie" || it == "tv" } ?: section.mediaType
                val query = uri.rawQuery?.takeIf { it.isNotBlank() }
                    ?: "sort_by=popularity.desc"
                val (items, hasNext) = pagedMedia("/discover/$mediaType?$query", page, mediaType)
                newHomePageResponse(request.name, items, hasNext)
            }
            "movie", "tv" -> {
                if (numericId != null) {
                    exactMediaSection(request, section.copy(mediaType = first, value = numericId.toString()))
                } else {
                    val endpoint = second?.replace('-', '_') ?: "popular"
                    val (items, hasNext) = pagedMedia("/$first/$endpoint", page, first)
                    newHomePageResponse(request.name, items, hasNext)
                }
            }
            else -> throw IllegalArgumentException("Unsupported TMDB link path: /$first")
        }
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        val resolvedType = mediaType ?: type
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: originalName ?: return null,
            Data(id = id, type = resolvedType).toJson(),
            if (resolvedType == "tv") TvType.TvSeries else TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score= Score.from10(voteAverage)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val tmdbAPI = resolveApiBase()

        return app.get("$tmdbAPI/search/multi?api_key=$apiKey&language=$langCode&query=$query&page=$page&include_adult=${settingsForProvider.enableAdult}")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse()
            }?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val tmdbAPI = resolveApiBase()

        val data = parseJson<Data>(url)
        if (data.type == "person") {
            return loadPerson(url, data, tmdbAPI)
        }
        val type = getType(data.type)

        val cacheKey = "metadata_${data.id}_${data.type}_$langCode"
        val cached = StreamPlayCache.getCachedMetadata(cacheKey)
        if (cached != null) {
            try {
                return parseJson<LoadResponse>(cached)
            } catch (e: Exception) {
                Log.e("StreamPlay", "Failed to parse cached metadata: ${e.message}")
            }
        }

        val append = "alternative_titles,credits,external_ids,videos,recommendations,images"

        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=$langCode&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=$langCode&append_to_response=$append"
        }

        // English data only if different from current language
        val enResUrl = if (langCode != "en-US") {
            if (type == TvType.Movie) {
                "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=en-US"
            } else {
                "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=en-US"
            }
        } else null

        var res: MediaDetail? = null
        var enRes: MediaDetail? = null
        var logoUrl: String? = null
        var cineRes: CinemetaRes? = null

        coroutineScope {
            val resDeferred = async {
                withTimeoutOrNull(8000.milliseconds) {
                    app.get(resUrl).parsedSafe<MediaDetail>()
                }
            }

            val enResDeferred = async {
                if (enResUrl == null) null
                else withTimeoutOrNull(5000.milliseconds) {
                    app.get(enResUrl).parsedSafe<MediaDetail>()
                }
            }

            val logoDeferred = async {
                withTimeoutOrNull(3000.milliseconds) {
                    val tempRes = resDeferred.await()
                    fetchTmdbLogoUrl(
                        tmdbAPI = tmdbAPI,
                        apiKey = apiKey,
                        type = type,
                        tmdbId = tempRes?.id,
                        appLangCode = langCode ?: "en"
                    )
                }
            }

            val cineDeferred = async {
                val tempRes = resDeferred.await()
                if (tempRes?.external_ids?.imdb_id != null) {
                    withTimeoutOrNull(3000.milliseconds) {
                        val cinetype = if (type == TvType.TvSeries) "series" else "movie"
                        app.get("$Cinemeta/meta/$cinetype/${tempRes.external_ids.imdb_id}.json")
                            .parsedSafe<CinemetaRes>()
                    }
                } else null
            }

            res = resDeferred.await() ?: throw ErrorLoadingException("Invalid Json Response")

            enRes = enResDeferred.await()
            logoUrl = logoDeferred.await()
            cineRes = cineDeferred.await()
        }

        res ?: throw ErrorLoadingException("Invalid Json Response")

        val enTitle = if (langCode == "en-US") res.title ?: res.name else enRes?.title ?: enRes?.name
        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }

        val isCartoon = genres?.contains("Animation") ?: false
        val isAnime = isCartoon && (res.original_language == "zh" || res.original_language == "ja")
        val isAsian = !isAnime && (res.original_language == "zh" || res.original_language == "ko")
        val isBollywood = res.production_countries?.any { it.name == "India" } ?: false

        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(
                Actor(name, getImageUrl(cast.profilePath)), roleString = cast.character
            )
        } ?: emptyList()

        val recommendations = res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }
        val trailer = res.videos?.results.orEmpty().filter { it.type == "Trailer" }.map { "https://www.youtube.com/watch?v=${it.key}" }.reversed().ifEmpty { res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" } ?: emptyList() }

        val comingSoonFlag = when (res.status?.lowercase()) {
            "released" -> false
            "post production", "in production", "planned" -> true
            else -> isUpcoming(releaseDate)
        }

        if (type == TvType.TvSeries) {
            val lastSeason = res.last_episode_to_air?.season_number
            val episodes = coroutineScope {
                val semaphore = Semaphore(10)

                res.seasons?.map { season ->
                    async {
                        semaphore.withPermit {
                            withTimeoutOrNull(5000.milliseconds) {
                                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=$langCode")
                                    .parsedSafe<MediaDetailEpisodes>()
                                    ?.episodes
                                    ?.map { eps ->
                                        newEpisode(
                                            LinkData(
                                                data.id,
                                                res.external_ids?.imdb_id,
                                                res.external_ids?.tvdb_id,
                                                data.type,
                                                eps.seasonNumber,
                                                eps.episodeNumber,
                                                eps.id,
                                                title = enTitle,
                                                year = season.airDate?.split("-")?.first()
                                                    ?.toIntOrNull(),
                                                orgTitle = orgTitle,
                                                isAnime = isAnime,
                                                airedYear = year,
                                                lastSeason = lastSeason,
                                                epsTitle = eps.name,
                                                jpTitle = res.alternative_titles?.results
                                                    ?.firstOrNull {
                                                        it.iso_3166_1 == "JP" &&
                                                                it.type?.equals("romaji", ignoreCase = true) == true
                                                    }?.title,
                                                date = season.airDate,
                                                airedDate = res.releaseDate ?: res.firstAirDate,
                                                isAsian = isAsian,
                                                isBollywood = isBollywood,
                                                isCartoon = isCartoon,
                                                alttitle = res.title,
                                                nametitle = res.name
                                            ).toJson()
                                        ) {
                                            this.name =
                                                eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                                            this.season = eps.seasonNumber
                                            this.episode = eps.episodeNumber
                                            this.posterUrl = getImageUrl(eps.stillPath)
                                            this.score = Score.from10(eps.voteAverage)
                                            this.description = eps.overview
                                            this.runTime = eps.runTime
                                        }.apply {
                                            this.addDate(eps.airDate)
                                        }

                                    }
                            }
                        }
                    }
                }?.awaitAll()?.filterNotNull()?.flatten() ?: listOf()
            }
            if (isAnime) {
                val imdbId = res.external_ids?.imdb_id.orEmpty()
                val animeVideos = cineRes?.meta?.videos?.filter { it.season != 0 } ?: emptyList()
                val jpTitle = res.alternative_titles?.results
                    ?.firstOrNull {
                        it.iso_3166_1 == "JP" &&
                                it.type?.equals("romaji", ignoreCase = true) == true
                    }?.title
                    ?: cineRes?.meta?.name
                val syncMetaData = withTimeoutOrNull(4000.milliseconds) {
                    app.get("https://api.ani.zip/mappings?imdb_id=$imdbId").text
                }
                val animeMetaData = syncMetaData?.let { parseAnimeData(it) }
                val kitsuid = animeMetaData?.mappings?.kitsuid

                val subbedList = animeVideos.map { video ->
                    val videoYear = video.released?.split("-")?.firstOrNull()?.toIntOrNull()
                        ?: cineRes?.meta?.year?.toIntOrNull() ?: 0

                    newEpisode(
                        LinkData(
                            id = data.id,
                            imdbId = imdbId,
                            tvdbId = res.external_ids?.tvdb_id,
                            type = data.type,
                            season = video.season,
                            episode = video.episode,
                            title = title,
                            year = videoYear,
                            orgTitle = orgTitle,
                            isAnime = true,
                            airedYear = year,
                            epsTitle = video.title,
                            jpTitle = jpTitle,
                            date = video.released,
                            airedDate = res.releaseDate ?: res.firstAirDate,
                            isAsian = isAsian,
                            isBollywood = isBollywood,
                            isCartoon = isCartoon,
                            alttitle = res.title,
                            nametitle = res.name,
                            isDub = false
                        ).toJson()
                    ) {
                        this.name = video.title + if (isUpcoming(video.released)) " • [UPCOMING]" else ""
                        this.season = video.season
                        this.episode = video.episode
                        this.posterUrl = video.thumbnail
                        this.description = video.overview
                        addDate(video.released)
                    }
                }

                val dubbedList = subbedList.map { ep ->
                    ep.copy(data = ep.data.replace("\"isDub\":false", "\"isDub\":true"))
                }

                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    addEpisodes(DubStatus.Subbed, subbedList)
                    addEpisodes(DubStatus.Dubbed, dubbedList)
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bgPoster
                    try { this.logoUrl = logoUrl } catch(_:Throwable){}
                    this.year = year
                    this.plot = res.overview
                    this.tags = keywords?.map { it.replaceFirstChar { c -> c.titlecase() } }
                        ?.takeIf { it.isNotEmpty() } ?: genres
                    this.score = Score.from10(res.vote_average.toString())
                    this.showStatus = getStatus(res.status)
                    this.recommendations = recommendations
                    this.actors = actors
                    addTrailer(trailer)
                    try { addKitsuId(kitsuid) } catch(_:Throwable){}
                    this.contentRating = cineRes?.meta?.appExtras?.certification
                    addImdbId(imdbId)
                }
            } else {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bgPoster
                    this.year = year
                    this.plot = res.overview
                    this.tags = keywords?.map { word -> word.replaceFirstChar { it.titlecase() } }
                        ?.takeIf { it.isNotEmpty() } ?: genres
                    this.score = Score.from10(res.vote_average.toString())
                    this.showStatus = getStatus(res.status)
                    this.recommendations = recommendations
                    this.actors = actors
                    try { this.logoUrl = logoUrl } catch(_:Throwable){}
                    this.contentRating = cineRes?.meta?.appExtras?.certification
                    addTrailer(trailer)
                    addImdbId(res.external_ids?.imdb_id)
                }
            }
        } else {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.external_ids?.imdb_id,
                    res.external_ids?.tvdb_id,
                    data.type,
                    title = enTitle,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                    airedDate = res.releaseDate ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood,
                    alttitle = res.title,
                    nametitle = res.name,
                    isMovie = true
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = comingSoonFlag
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords?.map { word -> word.replaceFirstChar { it.titlecase() } }
                    ?.takeIf { it.isNotEmpty() } ?: genres
                try { this.logoUrl = logoUrl } catch(_:Throwable){}
                this.score = Score.from10(res.vote_average.toString())
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = cineRes?.meta?.appExtras?.certification
                addTrailer(trailer)
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    private suspend fun loadPerson(url: String, data: Data, tmdbAPI: String): LoadResponse? {
        val personId = data.id ?: return null
        val filterType = data.filterType?.takeIf { it == "movie" || it == "tv" }
        val (json, works) = coroutineScope {
            val detail = async {
                JSONObject(app.get(apiUrl(tmdbAPI, "/person/$personId")).text)
            }
            val credits = async { fetchPersonWorks(personId, filterType ?: "mixed") }
            detail.await() to credits.await()
        }
        val personName = json.optString("name").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Invalid TMDB person")

        val birthday = json.optString("birthday").takeIf { it.isNotBlank() && it != "null" }
        val place = json.optString("place_of_birth").takeIf { it.isNotBlank() && it != "null" }
        val biography = json.optString("biography").takeIf { it.isNotBlank() }
        val worksSummary = if (works.isEmpty()) {
            "No TMDB works were returned for this person."
        } else {
            "${works.size} works are available in the Recommendations section."
        }
        return newTvSeriesLoadResponse(personName, url, TvType.TvSeries, emptyList()) {
            posterUrl = getOriImageUrl(json.optString("profile_path").takeIf { it.isNotBlank() && it != "null" })
            year = birthday?.substringBefore('-')?.toIntOrNull()
            plot = listOfNotNull(biography, worksSummary).joinToString("\n\n")
            tags = listOfNotNull(
                json.optString("known_for_department").takeIf { it.isNotBlank() },
                place,
                filterType?.let { if (it == "tv") "TV Shows" else "Movies" },
            )
            recommendations = works
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val res = parseJson<LinkData>(data)

        val stremioAddons = StreamPlayStremioAddonSettings.getDynamicStremioMap(
            sharedPref,
            res.imdbId,
            res.season,
            res.episode,
            subtitleCallback,
            callback
        ).values

        val allProviders = buildProviders()
        val disabledProviderIds = sharedPref?.getStringSet("disabled_providers", null)

        val activeProviders = if (disabledProviderIds.isNullOrEmpty()) {
            allProviders
        } else {
            allProviders.filterNot { disabledProviderIds.contains(it.id) }
        }

        val prioritizedProviders = activeProviders.sortedByDescending { provider ->
            StreamPlayCache.getProviderPriorityScore(provider.id)
        }

        val brokenCount = activeProviders.count {
            StreamPlayCache.getProviderStats(it.id).isCircuitBroken
        }
        if (brokenCount > 0) {
            Log.d(TAG, "📉 $brokenCount slow/failing providers moved to end of queue")
        }

        if (prioritizedProviders.isEmpty() && stremioAddons.isEmpty()) return@coroutineScope true

        val authToken = token.orEmpty()
        val concurrency = sharedPref?.getInt("provider_concurrency", 15)?.coerceIn(8, 50) ?: 20

        val totalProviders = prioritizedProviders.size + stremioAddons.size
        val linksFound = java.util.concurrent.atomic.AtomicInteger(0)
        val providersCompleted = java.util.concurrent.atomic.AtomicInteger(0)

        Log.d(TAG, "🚀 Starting $totalProviders providers (concurrency: $concurrency, prioritized by success rate)")

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            callback(link)
        }

        val executionList: List<suspend () -> Unit> = prioritizedProviders.map { provider ->
            suspend {
                val startTime = System.currentTimeMillis()
                var success = false

                runCatching {
                    provider.invoke(res, subtitleCallback, wrappedCallback, authToken, dahmerMoviesAPI)
                    success = true
                }.onFailure { e ->
                    Log.w(TAG, "Provider ${provider.id} failed, retrying: ${e.message}")
                    kotlinx.coroutines.delay(2000.milliseconds)
                    runCatching {
                        provider.invoke(res, subtitleCallback, wrappedCallback, authToken, dahmerMoviesAPI)
                        success = true
                        Log.d(TAG, "✅ Retry succeeded: ${provider.id}")
                    }.onFailure { retryError ->
                        Log.e(TAG, "Provider ${provider.id} failed after retry: ${retryError.message}")
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                StreamPlayCache.recordProviderExecution(provider.id, success, duration)

                val completed = providersCompleted.incrementAndGet()
                if (completed % 10 == 0 || completed == totalProviders) {
                    Log.d(TAG, "⏳ Progress: $completed/$totalProviders providers")
                }
                Unit
            }
        } + stremioAddons.map { addon ->
            suspend {
                runCatching {
                    addon()
                }
                val completed = providersCompleted.incrementAndGet()
                if (completed % 10 == 0 || completed == totalProviders) {
                    Log.d(TAG, "⏳ Progress: $completed/$totalProviders providers")
                }
            }
        }

        runLimitedAsync(
            concurrency = concurrency,
            *executionList.toTypedArray()
        )

        Log.d(TAG, "✅ Finished: $totalProviders providers checked, ${linksFound.get()} links found")
        true
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val epid: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
        val isBollywood: Boolean = false,
        val isCartoon: Boolean = false,
        val alttitle: String? = null,
        val nametitle: String? = null,
        val isDub: Boolean = false,
        val isMovie: Boolean? = false,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
        val filterType: String? = null,
        val movicsCustom: Boolean = false,
    )

    data class Results(
        @param:JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("media_type") val mediaType: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Genres(
        @get:JsonProperty("id") val id: Int? = null,
        @get:JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @get:JsonProperty("id") val id: Int? = null,
        @get:JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @get:JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @get:JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @get:JsonProperty("id") val id: Int? = null,
        @get:JsonProperty("name") val name: String? = null,
        @get:JsonProperty("season_number") val seasonNumber: Int? = null,
        @get:JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @get:JsonProperty("id") val id: Int? = null,
        @get:JsonProperty("name") val name: String? = null,
        @get:JsonProperty("original_name") val originalName: String? = null,
        @get:JsonProperty("character") val character: String? = null,
        @get:JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @get:JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @get:JsonProperty("id") val id: Int? = null,
        @get:JsonProperty("name") val name: String? = null,
        @get:JsonProperty("overview") val overview: String? = null,
        @get:JsonProperty("air_date") val airDate: String? = null,
        @get:JsonProperty("still_path") val stillPath: String? = null,
        @get:JsonProperty("vote_average") val voteAverage: Double? = null,
        @get:JsonProperty("episode_number") val episodeNumber: Int? = null,
        @get:JsonProperty("season_number") val seasonNumber: Int? = null,
        @get:JsonProperty("runtime") val runTime: Int? = null
    )

    data class MediaDetailEpisodes(
        @get:JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @get:JsonProperty("key") val key: String? = null,
        @get:JsonProperty("type") val type: String? = null,
    )

    data class ResultsTrailer(
        @get:JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class AltTitles(
        @get:JsonProperty("iso_3166_1") val iso_3166_1: String? = null,
        @get:JsonProperty("title") val title: String? = null,
        @get:JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @get:JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ExternalIds(
        @get:JsonProperty("imdb_id") val imdb_id: String? = null,
        @get:JsonProperty("tvdb_id") val tvdb_id: Int? = null,
    )

    data class Credits(
        @get:JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @get:JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @get:JsonProperty("episode_number") val episode_number: Int? = null,
        @get:JsonProperty("season_number") val season_number: Int? = null,
    )

    data class ProductionCountries(
        @get:JsonProperty("name") val name: String? = null,
    )

    data class MediaDetail(
        @get:JsonProperty("id") val id: Int? = null,
        @get:JsonProperty("imdb_id") val imdbId: String? = null,
        @get:JsonProperty("title") val title: String? = null,
        @get:JsonProperty("name") val name: String? = null,
        @get:JsonProperty("original_title") val originalTitle: String? = null,
        @get:JsonProperty("original_name") val originalName: String? = null,
        @get:JsonProperty("poster_path") val posterPath: String? = null,
        @get:JsonProperty("backdrop_path") val backdropPath: String? = null,
        @get:JsonProperty("release_date") val releaseDate: String? = null,
        @get:JsonProperty("first_air_date") val firstAirDate: String? = null,
        @get:JsonProperty("overview") val overview: String? = null,
        @get:JsonProperty("runtime") val runtime: Int? = null,
        @get:JsonProperty("vote_average") val vote_average: Any? = null,
        @get:JsonProperty("original_language") val original_language: String? = null,
        @get:JsonProperty("status") val status: String? = null,
        @get:JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @get:JsonProperty("keywords") val keywords: KeywordResults? = null,
        @get:JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @get:JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @get:JsonProperty("videos") val videos: ResultsTrailer? = null,
        @get:JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @get:JsonProperty("credits") val credits: Credits? = null,
        @get:JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @get:JsonProperty("alternative_titles") val alternative_titles: ResultsAltTitles? = null,
        @get:JsonProperty("production_countries") val production_countries: ArrayList<ProductionCountries>? = arrayListOf(),
    )
}
