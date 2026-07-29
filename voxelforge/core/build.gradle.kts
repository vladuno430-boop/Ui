/*
 * :core — чистая JVM-библиотека с игровой логикой.
 * Никаких android-зависимостей: это гарантирует, что логику можно
 * прогнать юнит-тестами на CI без эмулятора.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Строгий режим: предупреждения компилятора не должны накапливаться.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
