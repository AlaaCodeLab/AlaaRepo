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
    description = "إضافة سينما بوكس - Cinema Box"
    authors = listOf("alaasroot")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = true
    language = "ar"
}

android {
    namespace = "com.cinemabox"
    defaultConfig {
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
