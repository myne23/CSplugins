dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
}

version = 1

cloudstream {
    description = "Megadede provider"
    authors = listOf("mmilew")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    requiresResources = false
    language = "es"

    iconUrl = "https://megadede.com/favicon.ico"
}
