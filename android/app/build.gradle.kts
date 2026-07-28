plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nightrun.x71"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nightrun.x71"
        minSdk = 24            // WebView with WebGL 2 is reliable from Android 7
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The game is plain files; nothing here should be compressed twice.
    androidResources {
        noCompress += listOf("webmanifest")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}

/**
 * Copies the web game into the APK's assets before every build, so `game/` in
 * the repository stays the single source of truth.
 */
val syncGame by tasks.registering(Copy::class) {
    from(rootProject.file("../game")) {
        exclude("**/.DS_Store")
    }
    into(layout.projectDirectory.dir("src/main/assets/game"))
}

tasks.named("preBuild") {
    dependsOn(syncGame)
}
