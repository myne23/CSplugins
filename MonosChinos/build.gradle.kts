dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
}

version = 3

cloudstream {
    description = "MonosChinos — anime sub español"
    authors = listOf("mmilew")

    status = 1

    tvTypes = listOf(
        "Anime",
        "TvSeries",
        "AnimeMovie"
    )

    requiresResources = false
    language = "es"

    iconUrl = "https://monoschinos.st/favicon.ico"
}
