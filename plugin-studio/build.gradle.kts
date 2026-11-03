// The plugin studio: the shared half of the Plugin Maker.
//
// Everything in this module is about *authoring* a GameCore plugin — the draft model, the
// validation rules, the `.gcplugin` composer and the editor UI. It is an Android library
// rather than part of `:app` because two apps need it: the standalone Plugin Maker
// (`com.gamecore.pluginmaker`) and GameCore itself, which will grow a "Create plugin"
// entry that opens the same editor.
//
// Nothing here depends on GameCore's own code. The studio writes the package format from
// the documented rules, and `:app` has a test that decodes what the studio writes with
// GameCore's real codecs — so the two can never drift apart silently.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gamecore.studio"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    // The save-file flow uses the storage access framework, which is an activity contract.
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    // Test classpath only. The importer and the draft codec use org.json, and the stub inside
    // android.jar returns defaults under unit tests — which would let a parse test pass without
    // parsing anything. The real implementation never reaches either APK.
    testImplementation(libs.org.json)
}
