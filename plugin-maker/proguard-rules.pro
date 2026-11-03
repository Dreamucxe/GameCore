# The maker's own rules. Compose, the activity contract and the studio's public model
# are covered by consumer rules; nothing here is app-specific beyond keeping the
# entry point and the studio's model intact for the release build.
-keep class com.gamecore.pluginmaker.MainActivity { *; }
-keep class com.gamecore.studio.model.** { *; }
