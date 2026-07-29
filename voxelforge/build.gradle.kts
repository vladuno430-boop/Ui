/*
 * Корневой build-скрипт. Плагины объявляются, но не применяются здесь —
 * их применяют модули. Это избавляет от загрузки Android-плагина в :core.
 */
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}

/** Единая точка правки версий — чтобы не разъезжались между модулями. */
extra["javaVersion"] = JavaVersion.VERSION_17
