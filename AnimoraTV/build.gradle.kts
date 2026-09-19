dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "AnimoraTV provider"
    authors = listOf("mmilew")

    status = 1

    tvTypes = listOf("TvSeries")

    requiresResources = true
    language = "es"

    iconUrl = "https://www.animoratv.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
