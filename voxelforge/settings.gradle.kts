/*
 * Корневые настройки Gradle-сборки VoxelForge.
 *
 * Проект намеренно разделён на два модуля:
 *  - :core — чистая Kotlin/JVM библиотека без единой зависимости от Android.
 *            Здесь живёт вся игровая логика: генерация мира, чанки, меширование,
 *            освещение, физика, инвентарь, крафт, сохранения. Её можно
 *            компилировать и тестировать на десктопе за секунды.
 *  - :app  — Android-приложение: OpenGL ES 3.0 рендер, сенсорное управление, UI.
 *
 * Такое разделение — прямое следствие принципа инверсии зависимостей (SOLID):
 * логика не знает о платформе, платформа знает о логике.
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VoxelForge"

include(":core")
include(":app")
