# The studio ships inside two apps, so its public surface has to survive R8 in both.
# The draft model and the composer are plain Kotlin with no reflection, so the default rules
# keep them; this file only stops the editor's composables from being renamed away in a
# way that would break a host that calls them by name.
-keep class com.gamecore.studio.model.** { *; }
