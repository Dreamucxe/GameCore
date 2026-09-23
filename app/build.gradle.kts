import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gamecore"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gamecore"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "3.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room exports its schema so a future migration can be tested against the
        // real historical schema instead of a hand-written approximation.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

        // SQLCipher ships a prebuilt .so per ABI and nothing else in the app uses
        // native code. Listing the two ARM ABIs keeps the APK from carrying two
        // x86 libraries totalling ~11 MB that no phone will ever load.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Optional release signing. `assembleRelease` produces a signed, installable
    // APK when keystore.properties is present and an unsigned one when it is not,
    // rather than failing the build for a contributor who has no key.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasKeystore = keystorePropsFile.exists()
    val keystoreProps = Properties().apply {
        if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 with obfuscation and resource shrinking, per the security
            // requirements: the optimization engine's internal logic and the
            // exact shell command strings should not be legible in a decompiled
            // APK. Keep rules are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    lint {
        // Lint runs as its own Gradle invocation on the ARM build host rather than
        // inside `assembleRelease`. The same checks run either way; what changes is
        // that lint's whole-program analysis does not share a JVM with R8 and
        // dexing, which together exhaust metaspace when the Kotlin compiler is
        // also in-process and there is no daemon.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.window)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // The overlay windows host Compose content outside an Activity, which needs a
    // real LifecycleOwner and SavedStateRegistryOwner on the service side.
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.security.crypto)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    implementation(libs.coroutines.android)

    // Shizuku, for the optional elevated capabilities. Both artifacts must be
    // `implementation`: `provider` supplies rikka.shizuku.ShizukuProvider, whose
    // subclass the manifest declares, and Android builds every declared provider
    // during process start. A compileOnly dependency there installs cleanly and
    // then kills the app on launch with ClassNotFoundException.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Local JVM tests. Everything under src/test is written against the pure,
    // Android-free seams -- the samplers, the aggregators, the capability
    // reasoning, the profile/HUD serialisation, the ViewModel state reducers --
    // so no framework double is needed and the suite runs on any JDK.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Test classpath only: the Aim Lab config codec parses with org.json, and the
    // version inside android.jar is a stub. See the catalog entry.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
}
