package com.gamecore.pluginmaker

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * The GameCore that is installed on this phone.
 *
 * Only the two numbers that matter to a person making a plugin: which version
 * is on the phone, and the `versionCode` that version carries. A plugin
 * declares the oldest GameCore it can run against as a `versionCode`, so the
 * number is the one the two are actually compared by.
 */
data class InstalledGameCore(
    /** The human version, e.g. `4.0.0`. Empty when the package carries no name. */
    val versionName: String,
    /** The monotonic version number, e.g. `50`. */
    val versionCode: Long,
)

/** The package GameCore ships under. */
const val GAMECORE_PACKAGE = "com.gamecore"

/** The package a debug build of GameCore installs under (the app module adds `.debug`). */
const val GAMECORE_DEBUG_PACKAGE = "com.gamecore.debug"

/**
 * The packages that count as GameCore, in the order they are looked for.
 *
 * The release build comes first: it is what a user has. The debug build is
 * there because a maker installed next to a locally built GameCore should
 * still be able to see it, and the suffix is the app module's, not ours.
 */
private val GAMECORE_PACKAGES = listOf(GAMECORE_PACKAGE, GAMECORE_DEBUG_PACKAGE)

/**
 * Reads the installed GameCore, or returns null when there is none.
 *
 * The package manager is the only thing that can answer this, and answering
 * it needs no permission: reading a package's own version is a query, not a
 * grant. From API 30 the package has to be *visible* for the query to succeed,
 * which is why the manifest declares GameCore under `<queries>`.
 *
 * A package that is not installed is not an error — it is the answer — so the
 * lookup moves on rather than throwing.
 */
fun readInstalledGameCore(packageManager: PackageManager): InstalledGameCore? {
    for (candidate in GAMECORE_PACKAGES) {
        val info = try {
            packageManager.getPackageInfo(candidate, 0)
        } catch (notThere: PackageManager.NameNotFoundException) {
            null
        }
        if (info != null) {
            return InstalledGameCore(
                versionName = info.versionName.orEmpty(),
                versionCode = info.versionCodeLong(),
            )
        }
    }
    return null
}

/**
 * The intent that starts GameCore, or null when it is not on this phone.
 *
 * The same candidate packages as the version lookup, so that the button and
 * the status line can never disagree about whether GameCore is installed.
 */
fun gameCoreLaunchIntent(packageManager: PackageManager): Intent? {
    for (candidate in GAMECORE_PACKAGES) {
        val intent = packageManager.getLaunchIntentForPackage(candidate)
        if (intent != null) return intent
    }
    return null
}

/**
 * The `versionCode` as a [Long], on every API level the maker runs on.
 *
 * `getLongVersionCode()` arrived in API 28; before that the same number came
 * out of the `int` field, widened. A `versionCode` above `Int.MAX_VALUE` is
 * not a thing that can be expressed before 28, so nothing is lost.
 */
private fun PackageInfo.versionCodeLong(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

/**
 * What to tell the user about the GameCore on this phone.
 *
 * A pure function on purpose: the sentence is the one piece of this file that
 * has to be *right* rather than merely working, and a JVM test can check it
 * without an Android device, a package manager or a phone.
 */
fun gameCoreStatusSentence(installed: InstalledGameCore?): String =
    if (installed == null) {
        MakerStrings.GAME_CORE_NOT_INSTALLED
    } else {
        installedGameCoreSentence(installed.versionName, installed.versionCode)
    }

/**
 * The sentence for a phone that has GameCore on it.
 *
 * The version is named rather than implied: the user is about to decide
 * whether the plugin they are making will run here, and "will it run on this
 * phone" is answered by a version number and nothing else.
 */
fun installedGameCoreSentence(versionName: String, versionCode: Long): String {
    val name = versionName.trim()
    val label = if (name.isEmpty()) MakerStrings.GAME_CORE else "${MakerStrings.GAME_CORE} $name"
    return "$label (version $versionCode) is installed — the plugin you make will run here."
}
