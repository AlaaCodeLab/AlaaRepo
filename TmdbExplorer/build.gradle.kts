import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "استكشف أفلام ومسلسلات وشركات إنتاج TMDB بشكل تفصيلي"
    authors = listOf("alaasroot")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = true
    language = "en"
    iconUrl = "https://www.themoviedb.org/assets/2/apple-touch-icon-57ed4b3b0450fd5e9a0c20f34e814b82adaa1085c297d20d63e83e0537d1b8d1.png"
}

android {
    defaultConfig {
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${localProperties.getProperty("TMDB_API_KEY", "")}\""
        )
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}