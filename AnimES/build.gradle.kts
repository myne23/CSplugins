dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "AnimES provider"
    authors = listOf("mmilew")

    status = 1

    tvTypes = listOf("Anime", "TvSeries", "AnimeMovie")

    requiresResources = true
    language = "es"

    iconUrl = "https://animeyt.cc/"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
