plugins {
    // Pure Kotlin/JVM only. No Android or KSP plugins here on purpose: this module is a
    // build-time tool that authors a `.gcplugin` package, it ships nothing into the app, and
    // the extra Android/KSP compilations are exactly what the one-module decision in
    // settings.gradle.kts is avoiding on this ARM host.
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
