package com.phisher98

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

enum class MovicsSectionCategory(val label: String) {
    PEOPLE("People"),
    PERSON_WORKS("Person works"),
    MOVIES("Movies"),
    TV_SHOWS("TV Shows"),
    COLLECTIONS("Collections"),
    KEYWORDS("Keywords"),
    COMPANIES("Companies"),
    NETWORKS("Networks"),
    LANGUAGE("Original language"),
    TMDB_LIST("TMDB list"),
    TMDB_LINK("TMDB link"),
    GENRES("Genres")
}

data class MovicsCustomSection(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val category: MovicsSectionCategory,
    val mediaType: String,
    val value: String,
)

object MovicsCustomSections {
    const val PREF_KEY = "movics_custom_tmdb_sections_v1"
    const val REQUEST_PREFIX = "movics-custom://"

    fun load(sharedPref: SharedPreferences?): List<MovicsCustomSection> {
        val raw = sharedPref?.getString(PREF_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val category = runCatching {
                        MovicsSectionCategory.valueOf(item.optString("category"))
                    }.getOrNull() ?: continue
                    if (name.isBlank()) continue
                    add(
                        MovicsCustomSection(
                            id = item.optLong("id", System.currentTimeMillis() + index),
                            name = name,
                            category = category,
                            mediaType = item.optString("mediaType", "general")
                                .takeIf { it in setOf("general", "movie", "tv", "mixed") } ?: "general",
                            value = item.optString("value").trim(),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(sharedPref: SharedPreferences, sections: List<MovicsCustomSection>) {
        val array = JSONArray()
        sections.forEach { section ->
            array.put(
                JSONObject()
                    .put("id", section.id)
                    .put("name", section.name)
                    .put("category", section.category.name)
                    .put("mediaType", section.mediaType)
                    .put("value", section.value)
            )
        }
        sharedPref.edit { putString(PREF_KEY, array.toString()) }
    }

    fun requestData(id: Long): String = "$REQUEST_PREFIX$id"

    fun requestId(data: String): Long? = data
        .takeIf { it.startsWith(REQUEST_PREFIX) }
        ?.removePrefix(REQUEST_PREFIX)
        ?.toLongOrNull()
}
