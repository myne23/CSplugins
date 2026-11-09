dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 19

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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
}
