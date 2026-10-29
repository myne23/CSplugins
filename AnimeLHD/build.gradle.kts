dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "AnimeLHD (animelatinohd.com): anime sub, latino y castellano"
    authors = listOf("mmilew")

    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    requiresResources = false
    language = "es"

    iconUrl = "https://www.animelatinohd.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
