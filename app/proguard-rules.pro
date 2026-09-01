# R8 configuration for the release build.
#
# The default `proguard-android-optimize.txt` already keeps what the framework
# reflects on (Activities, Services, Views, Parcelable creators, annotations).
# What follows is only the set of things *this* app reflects on, or that a library
# reflects on inside this app. Everything else -- including the whole optimization
# engine, the Shizuku command table and the sampling code -- is deliberately left
# obfuscatable, which is the point of turning R8 on at all.

# ---------------------------------------------------------------- Kotlin runtime

# Coroutines' internal service loader and the atomicfu volatile fields.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ------------------------------------------------------------------------- Room

# Room generates an implementation per @Database and looks it up by name at
# runtime. Entities are read reflectively by the generated adapters, so their
# field names have to survive.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --------------------------------------------------------------------- SQLCipher

# The native library resolves these by JNI signature, so neither the class names
# nor the member names can move.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# ----------------------------------------------------------- security-crypto/Tink

# Tink registers its key managers through a service loader and reflects on the
# generated protobuf classes.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { *; }

# ----------------------------------------------------------------------- Shizuku

# ShizukuProvider is named in the manifest (through GameCore's subclass) and the
# binder handshake reaches it by reflection from the Shizuku server process.
# `Shizuku.newProcess` is itself reached by reflection from this app, so the
# method must keep its name.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ------------------------------------------------------------------------- Hilt

# Hilt's generated components are found by name from the generated Application.
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }

# --------------------------------------------------------------------- GameCore

# The overlay windows are inflated from a Service rather than an Activity, and
# ComposeView reaches its ViewTree owners reflectively on some API levels.
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }

# Enum valueOf() is used when reading persisted settings back out of the
# encrypted preference store, and R8 cannot see that call site because the name
# arrives as a string.
-keepclassmembers enum com.gamecore.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Line numbers make a release crash report actionable while still obfuscating
# every name. The mapping file stays out of the APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
