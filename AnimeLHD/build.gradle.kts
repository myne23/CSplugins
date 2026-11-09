import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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
    buildFeatures {
        buildConfig = true
    }
}

// AnimeLHD uses CloudStream APIs compiled for JVM 11.
// Keep this override local to this plugin; the repository defaults to JVM 1.8.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
