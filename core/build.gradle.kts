// Pure Kotlin/JVM module: media model, sorting, search, grouping, edit maths.
// No Android dependencies, so its tests run anywhere with a JDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Target bytecode 17 but build with whatever JDK (17+) is installed; no toolchain download needed.
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
