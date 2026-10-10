package com.gamecore.ui.plugins

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.Compatibility
import com.gamecore.core.plugin.InstallOutcome
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.RiskTier

/**
 * The plugin manager's screen state, and the pure functions that produce it.
 *
 * This file exists so that the two pieces of judgement the manager screen depends on — what one row says
 * about a plugin, and what the app tells the user after an install attempt — are plain functions over
 * plain data rather than code inside a composable. Both are exactly the kind of logic that is wrong in a
 * way nobody notices: a row that reports a suspended plugin as running, or an install that failed for a
 * reason the user is never told. Neither can be unit-tested through a `@Composable`, and neither should
 * have to be, because neither needs Compose, Android or a registry to decide its answer.
 *
 * Three decisions worth stating:
 *
 *  - **A row is a snapshot, not a handle.** [PluginRow] carries strings and verdicts, never an
 *    [InstalledPlugin] and never its [com.gamecore.core.plugin.PluginGrant]. A composable holding a row
 *    cannot reach a plugin's grant, its bytes or its namespace keys, so the presentation layer's whole
 *    reach over a plugin stays the handful of methods on
 *    [com.gamecore.core.plugin.PluginRegistry] — which is also the list a reviewer has to check.
 *  - **Plugin-supplied text is sanitised here, not at the `Text` call.** The manifest codec already
 *    sanitised these strings on the way in, and this does it again on the way out, because
 *    [TextSanitizer] is explicitly a both-boundaries tool: a grant store written by an older build, or a
 *    bundled asset that predates a tightening of the codec, still reaches this function. Doing it in
 *    [rowFor] rather than at each render site means a new row of the card cannot forget.
 *  - **Nothing here builds markup.** Every string below is destined for a `Text` and nothing else — no
 *    `AnnotatedString` assembled from plugin input, no URL a plugin named, no clickable span. A plugin
 *    is data, and the furthest that data gets is a glyph.
 */

/** A plugin's description is a paragraph in the package and one flowing line in a list row. */
private const val MAX_SUMMARY_LENGTH = 160

/** For turning the loader's byte ceilings into the figure a user recognises. */
private const val BYTES_PER_MIB = 1024 * 1024

/** A version name is `1.4.2`-shaped; anything near this cap is a plugin padding a list row. */
private const val MAX_VERSION_LENGTH = 24

/** A suspension detail sits in a badge beside the row, so it is capped tighter than the summary. */
private const val MAX_DETAIL_LENGTH = 120

/**
 * One installed plugin as a list row.
 *
 * [enabled] and [suspended] are both carried because the user needs to tell them apart: disabled is the
 * choice they made, suspended is this run going wrong, and a single "off" would merge a decision with a
 * failure. [compatibility] is carried rather than reduced to a boolean so the row can print the real
 * reason ("this plugin needs a newer version of GameCore") instead of greying a switch out silently —
 * §32's rule about a control that does nothing applies to a control that *explains* nothing just as much.
 *
 * [highestTier] is nullable where [com.gamecore.core.plugin.PluginManifest.highestTier] is not. A plugin
 * that requests no permission at all has no risk to report, and `RiskTier.LOW` on that row would be a
 * badge claiming the app assessed something it never had to.
 *
 * Every field is defaulted so a test, or a preview, can name only the one it is about.
 */
data class PluginRow(
    val id: String = "",
    val name: String = "",
    val developer: String = "",
    val versionName: String = "",
    val summary: String = "",
    val origin: PluginOrigin = PluginOrigin.SIDELOADED,
    val enabled: Boolean = false,
    val suspended: Boolean = false,
    val suspensionDetail: String? = null,
    val compatibility: Compatibility = Compatibility.Supported,
    val declares: Set<PluginSection> = emptySet(),
    val highestTier: RiskTier? = null,
    val isThemeSource: Boolean = false,
    val canBeRemoved: Boolean = true,
)

/**
 * Everything the manager screen draws.
 *
 * [isLoaded] is separate from `rows.isEmpty()` on purpose, and the screen renders the two differently:
 * "this device has no plugins" and "the plugin directory has not been read yet" are different facts and
 * an empty list that means either is the §24 lie this field exists to prevent.
 *
 * [message] and [pendingRemoval] are the transient pair. They are not derived from the registry, which
 * is why [PluginsViewModel] keeps them in a flow of their own.
 */
data class PluginsUiState(
    val isLoaded: Boolean = false,
    val rows: List<PluginRow> = emptyList(),
    val killSwitchOn: Boolean = false,
    val activeThemeName: String? = null,
    val message: String? = null,
    val pendingRemoval: PluginRow? = null,
) {
    val hasPlugins: Boolean get() = rows.isNotEmpty()

    /**
     * Whether any plugin is running *right now*.
     *
     * The kill switch is part of the answer rather than a separate caveat: with it on, the permission
     * gate denies every call from every plugin, so a screen reporting a plugin as active while the
     * switch is on would be describing an intention rather than what the device is doing.
     */
    val hasAnyActive: Boolean
        get() = !killSwitchOn && rows.any { it.enabled && !it.suspended && it.compatibility.isRunnable }
}

/**
 * One plugin, flattened into a row.
 *
 * [activeThemeId] is passed in rather than read from the plugin, because "supplies the theme the app is
 * wearing" is a fact about the registry's single selection and not about the plugin — two plugins can
 * both declare a theme and only one of them is in force.
 *
 * [PluginRow.name] falls back to the id when the sanitiser leaves nothing behind. A package whose name
 * is entirely format characters is rare but not impossible, and a nameless row with a switch on it is
 * the one row a user cannot reason about.
 */
internal fun rowFor(plugin: InstalledPlugin, activeThemeId: String?): PluginRow {
    val manifest = plugin.manifest
    val name = TextSanitizer.sanitizeName(manifest.name)
    return PluginRow(
        id = manifest.id,
        name = name.ifBlank { manifest.id },
        developer = TextSanitizer.sanitizeName(manifest.developer),
        versionName = TextSanitizer.sanitizeName(manifest.versionName, MAX_VERSION_LENGTH),
        // `sanitizeName` and not `sanitizeMultiline`: a row shows one flowing block of text, and a
        // description carrying forty newlines would otherwise own the height of the whole list.
        summary = TextSanitizer.sanitizeName(manifest.description, MAX_SUMMARY_LENGTH),
        origin = plugin.origin,
        enabled = plugin.enabled,
        suspended = plugin.suspended,
        suspensionDetail = plugin.suspensionDetail
            ?.let { TextSanitizer.sanitizeName(it, MAX_DETAIL_LENGTH) }
            ?.takeIf { it.isNotBlank() },
        compatibility = manifest.compatibility,
        declares = manifest.declares,
        // Null rather than LOW when nothing was requested — see [PluginRow.highestTier].
        highestTier = manifest.permissions.maxOfOrNull { it.permission.tier },
        isThemeSource = activeThemeId != null && activeThemeId == manifest.id,
        canBeRemoved = plugin.canBeRemoved,
    )
}

/**
 * What the app says after trying to install a package.
 *
 * Exhaustive over [InstallOutcome] and, inside the refusal, over every
 * [PluginPackageLoader.Reason], so a reason added to the loader is a compile error here rather than a
 * package that fails with no explanation. Each branch names the real cause: "install failed" tells a
 * developer nothing they can act on and tells a user nothing about whether the file or the app is at
 * fault.
 *
 * The loader's own [InstallOutcome.Rejected.detail] is appended where there is one, sanitised first,
 * because that detail is the offending entry's name — attacker-controlled text out of a zip the user
 * just picked, and the one string on this screen that never passed through the manifest codec.
 */
internal fun messageFor(outcome: InstallOutcome): String = when (outcome) {
    is InstallOutcome.Installed -> {
        val name = TextSanitizer.sanitizeName(outcome.plugin.name).ifBlank { outcome.plugin.id }
        val replaced = outcome.replaced
        val lead = if (replaced == null) {
            "$name is installed."
        } else {
            "$name is installed, over the copy that was already here."
        }
        "$lead It is off, and stays off until you grant it the permissions it asks for — installing a " +
            "plugin is not the same as agreeing to what it wants. Open it to read what it asks for and " +
            "decide."
    }

    is InstallOutcome.Rejected -> {
        val detail = TextSanitizer.sanitizeName(outcome.detail, MAX_DETAIL_LENGTH)
        val reason = rejectionText(outcome.reason)
        if (detail.isBlank()) reason else "$reason ($detail)"
    }

    // The verdict already reads as a sentence to the user, so it is quoted rather than re-worded: one
    // copy of "this plugin needs a newer version of GameCore" means the install screen and the row
    // badge cannot drift into saying different things about the same package.
    is InstallOutcome.Incompatible ->
        "That package was not installed. ${outcome.compatibility.reason}"

    is InstallOutcome.StorageFailed -> {
        val detail = TextSanitizer.sanitizeName(outcome.detail, MAX_DETAIL_LENGTH)
        val lead = "That package is a valid plugin, but it could not be stored — either this device has " +
            "no room left or GameCore's plugin folder was not available. Nothing was installed, and " +
            "freeing some space and picking the file again is the whole of the fix."
        if (detail.isBlank()) lead else "$lead ($detail)"
    }
}

/**
 * The refusal, in the loader's own terms.
 *
 * Deliberately specific per reason. A developer who shipped a package with an undeclared section and a
 * user who downloaded a truncated file are both owed the sentence that tells them which of the two
 * happened, and a shared "that file is not a valid plugin" would make the two indistinguishable.
 */
private fun rejectionText(reason: PluginPackageLoader.Reason): String = when (reason) {
    PluginPackageLoader.Reason.EMPTY ->
        "That file is empty, so there was nothing to install."

    PluginPackageLoader.Reason.MALFORMED ->
        "That file is not a plugin package. A .gcplugin is a zip archive, and this one could not be " +
            "opened as one — a part-finished download does this."

    PluginPackageLoader.Reason.PACKAGE_TOO_LARGE ->
        "That file is larger than the ${PluginPackageLoader.MAX_PACKAGE_BYTES / BYTES_PER_MIB} MB a " +
            "plugin package is allowed to be, so GameCore stopped reading it rather than holding it."

    PluginPackageLoader.Reason.ENTRY_TOO_LARGE ->
        "One file inside that package unpacks to more than a plugin is allowed to carry, so it was " +
            "refused before anything was unpacked."

    PluginPackageLoader.Reason.TOO_MANY_ENTRIES ->
        "That package holds more than the ${PluginPackageLoader.MAX_ENTRIES} files a plugin may carry."

    PluginPackageLoader.Reason.UNSAFE_PATH ->
        "A file inside that package is named in a way that would write outside the plugin's own folder. " +
            "GameCore refuses the package rather than correcting the name."

    PluginPackageLoader.Reason.DUPLICATE_ENTRY ->
        "That package carries the same file twice, so which of the two it meant is undecidable."

    PluginPackageLoader.Reason.MISSING_MANIFEST ->
        "That package has no manifest file, so there is nothing in it saying what it is or what it wants."

    PluginPackageLoader.Reason.MANIFEST_REJECTED ->
        "That package's manifest was refused. The detail in brackets is the exact check it failed."

    PluginPackageLoader.Reason.UNDECLARED_SECTION ->
        "That package carries a section its manifest never declared. A plugin has to say what it " +
            "brings, and content that was not announced is refused rather than loaded quietly."

    PluginPackageLoader.Reason.MISSING_DECLARED_SECTION ->
        "That package's manifest declares a section the archive does not contain, so it would load as " +
            "something other than what it claims to be."

    PluginPackageLoader.Reason.DISALLOWED_FILE ->
        "That package carries a file that belongs to none of the sections a plugin may contain."
}
