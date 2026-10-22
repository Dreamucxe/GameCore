package com.gamecore.data.icon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.gamecore.MainActivity
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.plugin.icon.AppIconSlot
import com.gamecore.core.plugin.icon.PluginIconArtwork
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The only place in the app that turns "which icon" into a launcher entry.
 *
 * **How a launcher icon can change at all.** Android resolves a launcher icon from the installed
 * package's manifest, and no API accepts a bitmap for it. Every app that offers icon switching does
 * what this file does: declares one `activity-alias` per icon, each with its own `android:icon`, and
 * enables exactly one. The aliases are in `AndroidManifest.xml`; [AppIconSlot] names them; this class
 * switches between them.
 *
 * **The invariant.** At every observable moment, at least one alias is enabled. A device with none is
 * a device where GameCore has no launcher entry at all, which the user cannot undo from the app
 * because there is nothing to tap. So a switch enables the new alias *before* it disables the old
 * ones, and if enabling throws, nothing is disabled — the worst outcome is briefly two icons, which
 * is recoverable, rather than none.
 *
 * **Arbitrary artwork.** A package's image cannot become the launcher icon, but the system will build
 * a home-screen shortcut from a bitmap the host hands it. [pinArtwork] is that path, and it is
 * deliberately a separate, explicit action: it creates an icon rather than replacing one, so the app
 * never silently changes what the home screen contains.
 *
 * `DONT_KILL_APP` on every call: killing the process to change an icon would drop an overlay session
 * and lose the user's place, for a cosmetic change.
 */
@Singleton
class AppIconApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** The alias currently enabled, read from the system rather than from our own storage. */
    suspend fun currentSlot(): AppIconSlot = withContext(io) { currentSlotBlocking() }

    private fun currentSlotBlocking(): AppIconSlot =
        AppIconSlot.entries.firstOrNull { isEffectivelyEnabled(it) } ?: AppIconSlot.FALLBACK

    /**
     * Whether this alias is enabled *now*.
     *
     * `getComponentEnabledSetting` returns `DEFAULT` for an alias that has never been touched at
     * runtime, and `DEFAULT` means "whatever the manifest said" — which is `true` for
     * [AppIconSlot.DEFAULT] and `false` for every other slot. Collapsing `DEFAULT` to `true` would
     * report five enabled icons on a fresh install, so the manifest is consulted for that state.
     */
    private fun isEffectivelyEnabled(slot: AppIconSlot): Boolean {
        val pm = context.packageManager
        return when (pm.getComponentEnabledSetting(componentFor(slot))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            else -> manifestEnabled(slot)
        }
    }

    private fun manifestEnabled(slot: AppIconSlot): Boolean = try {
        context.packageManager
            .getActivityInfo(componentFor(slot), PackageManager.MATCH_DISABLED_COMPONENTS)
            .enabled
    } catch (_: PackageManager.NameNotFoundException) {
        // An alias this build ships but the installed APK does not (a downgrade, or a slot added in
        // a newer build than the one running). Treat as absent rather than enabled.
        false
    }

    /**
     * Switch to [slot], or leave everything alone if it is already in force.
     *
     * Returns false only when the system refused the enabling call. A false means *nothing changed*,
     * which the caller is free to report; it never means "half switched".
     */
    suspend fun apply(slot: AppIconSlot): Boolean = withContext(io) {
        if (currentSlotBlocking() == slot) return@withContext true

        val pm = context.packageManager
        try {
            pm.setComponentEnabledSetting(
                componentFor(slot),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (_: IllegalArgumentException) {
            // The component is not in the manifest of the installed APK.
            return@withContext false
        } catch (_: SecurityException) {
            return@withContext false
        }

        // Only now that a launcher entry exists may the others go.
        for (other in AppIconSlot.entries) {
            if (other == slot) continue
            try {
                pm.setComponentEnabledSetting(
                    componentFor(other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (_: IllegalArgumentException) {
                // Not in this APK; nothing to disable.
            }
        }
        true
    }

    /**
     * Ask the launcher to pin a shortcut wearing [bytes] as its icon.
     *
     * Returns [PinResult] rather than a bare boolean because the three failures need three different
     * sentences: an unsupported launcher, artwork the host will not decode, and the user tapping
     * "cancel" are not the same thing.
     */
    suspend fun pinArtwork(bytes: ByteArray?, label: String): PinResult = withContext(io) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return@withContext PinResult.Unsupported
        }
        val bitmap = decodeBounded(bytes) ?: return@withContext PinResult.Unreadable
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithBitmap(bitmap))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER),
            )
            .build()
        val requested = try {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (_: IllegalArgumentException) {
            return@withContext PinResult.Unsupported
        }
        if (requested) PinResult.Requested else PinResult.Unsupported
    }

    /**
     * Decode plugin artwork with both caps applied: [PluginIconArtwork.isAcceptable] on the bytes and
     * [PluginIconArtwork.MAX_EDGE_PX] on the header's dimensions.
     *
     * `inJustDecodeBounds` reads the header without allocating the bitmap, which is the whole point:
     * a 40000×40000 PNG is a valid PNG and would be a gigabyte in memory.
     */
    private fun decodeBounded(bytes: ByteArray?): Bitmap? {
        if (bytes == null || !PluginIconArtwork.isAcceptable(bytes)) return null
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, header)
        if (header.outWidth <= 0 || header.outHeight <= 0) return null
        if (header.outWidth > PluginIconArtwork.MAX_EDGE_PX || header.outHeight > PluginIconArtwork.MAX_EDGE_PX) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun componentFor(slot: AppIconSlot): ComponentName =
        ComponentName(context.packageName, context.packageName + slot.componentSuffix)

    /** What [pinArtwork] did. */
    enum class PinResult {
        /** The launcher was asked and the request was accepted. */
        Requested,

        /** This launcher does not support pinned shortcuts. */
        Unsupported,

        /** The artwork was not a decodable PNG/WebP within the caps. */
        Unreadable,
    }

    private companion object {
        /**
         * One shortcut id for the whole feature.
         *
         * Re-pinning with the same id replaces the previous shortcut's contents where the launcher
         * allows it, so a second pin does not accumulate icons.
         */
        const val SHORTCUT_ID = "gamecore_plugin_icon"
    }
}
