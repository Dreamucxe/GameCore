package com.gamecore.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.emulator.EmulatorDescriptor
import com.gamecore.core.emulator.EmulatorRegistry
import com.gamecore.core.emulator.InstalledEmulator
import com.gamecore.core.emulator.RomEntry
import com.gamecore.core.emulator.RomMime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds installed emulators and builds the intents that hand a document to one.
 *
 * Detection is a package-name lookup, not a label search. The package name is the only identifier another
 * developer cannot change without becoming a different app; a label containing "emulator" is a claim, and
 * this repository does not turn claims into rows.
 *
 * The intent builder never promises that a hand-off will work. It asks `PackageManager.resolveActivity`
 * for each plausible MIME type, and returns the first intent Android actually resolves. When none resolves
 * the caller is told to open the emulator instead, which is a different, honest action — not a failed
 * launch dressed up as a success.
 */
@Singleton
class EmulatorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Every known emulator installed on this device, with the package actually found.
     *
     * A descriptor that lists several packages (PPSSPP's free and paid builds) contributes at most one row:
     * the first package found in declaration order. Two rows for one installed application would ask the
     * user to choose between the same thing twice.
     */
    suspend fun installed(): List<InstalledEmulator> = withContext(io) {
        val pm = context.packageManager
        EmulatorRegistry.all.mapNotNull { descriptor ->
            val packageName = descriptor.packages.firstOrNull { isInstalled(pm, it) }
                ?: return@mapNotNull null
            val label = runCatching {
                val info = applicationInfo(pm, packageName)
                TextSanitizer.sanitizeName(pm.getApplicationLabel(info).toString())
                    .ifBlank { descriptor.displayName }
            }.getOrDefault(descriptor.displayName)
            val version = runCatching {
                packageVersionName(pm, packageName)?.let { TextSanitizer.sanitizeName(it, maxLength = 32) }
            }.getOrNull()
            InstalledEmulator(
                descriptor = descriptor,
                packageName = packageName,
                label = label,
                versionName = version,
            )
        }.sortedWith(compareBy<InstalledEmulator> { it.label.lowercase(Locale.US) }.thenBy { it.packageName })
    }

    /**
     * The intent that hands [rom] to [emulator], or null when Android resolves no activity for it.
     *
     * A `content://` URI and `FLAG_GRANT_READ_URI_PERMISSION` are used together: the flag grants the target
     * app read access for this launch, and the persisted library grant is what lets GameCore produce the
     * URI again after a restart. No file path is exposed.
     */
    suspend fun handOffIntent(emulator: InstalledEmulator, rom: RomEntry): Intent? = withContext(io) {
        if (!emulator.handlesFiles) return@withContext null
        val uri = runCatching { Uri.parse(rom.uri) }.getOrNull() ?: return@withContext null
        val pm = context.packageManager
        for (mime in RomMime.candidates(rom.extension)) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(emulator.packageName)
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (resolve(pm, intent) != null) return@withContext intent
        }
        null
    }

    /** The emulator's own launcher screen, or null when it has no launcher activity. */
    suspend fun openIntent(emulator: InstalledEmulator): Intent? = withContext(io) {
        runCatching { context.packageManager.getLaunchIntentForPackage(emulator.packageName) }.getOrNull()
    }

    /** A browser intent for a download page, or null when the URL is missing or unparsable. */
    fun downloadIntent(descriptor: EmulatorDescriptor): Intent? {
        val url = descriptor.downloadUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** Whether [packageName] is installed, using the same lookup the detection path uses. */
    suspend fun isInstalled(packageName: String): Boolean = withContext(io) {
        isInstalled(context.packageManager, packageName)
    }

    // ---------------------------------------------------------------------- package-manager wrappers

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean = runCatching {
        applicationInfo(pm, packageName)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun applicationInfo(pm: PackageManager, packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getApplicationInfo(packageName, 0)
        }

    @Suppress("DEPRECATION")
    private fun packageVersionName(pm: PackageManager, packageName: String): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
        } else {
            pm.getPackageInfo(packageName, 0).versionName
        }

    @Suppress("DEPRECATION")
    private fun resolve(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
}
