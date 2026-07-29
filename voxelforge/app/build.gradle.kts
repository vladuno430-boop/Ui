/*
 * :app — Android-модуль. Здесь только то, что физически не может жить
 * без Android: контекст OpenGL ES, обработка касаний, файловая песочница,
 * жизненный цикл Activity.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voxelforge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voxelforge.app"
        // API 24 (Android 7.0) — минимум, где OpenGL ES 3.1 доступен
        // на подавляющем большинстве устройств, а AHardwareBuffer уже есть.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Воксельный движок целиком в Kotlin — R8 в full mode заметно
            // сокращает и dex, и время старта.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}

/*
 * Исходники Kotlin лежат в src/main/kotlin, а не в src/main/java:
 * проект целиком на Kotlin, и каталог java вводил бы в заблуждение.
 */
android.sourceSets["main"].java.srcDirs("src/main/kotlin")
