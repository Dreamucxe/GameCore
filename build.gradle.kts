// Top-level build file. Plugin versions live in gradle/libs.versions.toml and are
// declared here with `apply false` so each module opts in without repeating a
// version number.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
