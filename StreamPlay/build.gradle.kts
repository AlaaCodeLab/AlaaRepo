@file:Suppress("UnstableApiUsage")

import java.util.Properties

version = 653

android {
    namespace = "com.phisher98"

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        val localProperties = project.rootProject.file("local.properties")
        if (localProperties.exists()) {
            localProperties.inputStream().use(properties::load)
        }

        fun configValue(name: String): String =
            properties.getProperty(name) ?: System.getenv(name) ?: ""

        android.buildFeatures.buildConfig=true
        buildConfigField("String", "TMDB_API", "\"${configValue("TMDB_API")}\"")
        buildConfigField("String", "ZSHOW_API", "\"${configValue("ZSHOW_API")}\"")
        buildConfigField("String", "ANICHI_API", "\"${configValue("ANICHI_API")}\"")
        buildConfigField("String", "KissKh", "\"${configValue("KissKh")}\"")
        buildConfigField("String", "KisskhSub", "\"${configValue("KisskhSub")}\"")
        buildConfigField("String", "SUPERSTREAM_THIRD_API", "\"${configValue("SUPERSTREAM_THIRD_API")}\"")
        buildConfigField("String", "SUPERSTREAM_FOURTH_API", "\"${configValue("SUPERSTREAM_FOURTH_API")}\"")
        buildConfigField("String", "SUPERSTREAM_FIRST_API", "\"${configValue("SUPERSTREAM_FIRST_API")}\"")
        buildConfigField("String", "PROXYAPI", "\"${configValue("PROXYAPI")}\"")
        buildConfigField("String", "KAISVA", "\"${configValue("KAISVA")}\"")
        buildConfigField("String", "MOVIEBOX_SECRET_KEY_ALT", "\"${configValue("MOVIEBOX_SECRET_KEY_ALT")}\"")
        buildConfigField("String", "MOVIEBOX_SECRET_KEY_DEFAULT", "\"${configValue("MOVIEBOX_SECRET_KEY_DEFAULT")}\"")
        buildConfigField("String", "KAIMEG", "\"${configValue("KAIMEG")}\"")
        buildConfigField("String", "KAIDEC", "\"${configValue("KAIDEC")}\"")
        buildConfigField("String", "KAIENC", "\"${configValue("KAIENC")}\"")
        buildConfigField("String", "VideasyDEC", "\"${configValue("VideasyDEC")}\"")
        buildConfigField("String", "YFXENC", "\"${configValue("YFXENC")}\"")
        buildConfigField("String", "YFXDEC", "\"${configValue("YFXDEC")}\"")
        buildConfigField("String", "NuvFeb", "\"${configValue("NuvFeb")}\"")
        buildConfigField("String", "ANICHI_APP", "\"${configValue("ANICHI_APP")}\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
}

cloudstream {
    language = "en"
    description = "#1 best extension based on MultiAPI"
    authors = listOf("Phisher98", "Hexated")
    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Anime",
        "Movie",
        "Cartoon",
        "AnimeMovie"
    )
    iconUrl = "https://i3.wp.com/yt3.googleusercontent.com/ytc/AIdro_nCBArSmvOc6o-k2hTYpLtQMPrKqGtAw_nC20rxm70akA=s900-c-k-c0x00ffffff-no-rj?ssl=1"
    requiresResources = true
    isCrossPlatform = false
}
