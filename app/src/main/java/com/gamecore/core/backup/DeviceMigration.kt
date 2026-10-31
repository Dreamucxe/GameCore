package com.gamecore.core.backup

import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.ResolutionScale
import kotlin.math.abs

/**
 * What the migration report found about one thing in a backup.
 *
 * A check is a statement about a *field*, not about the profile as a whole. A backup carrying a
 * 120 Hz pin onto a 60 Hz panel is one check; a backup carrying a 1440p resolution override onto a
 * 1080p panel is another; a backup from a phone to a tablet is a third. The screen prints them as a
 * list of sentences, which is more use than a single "compatible / not compatible" verdict that
 * would hide which half of a profile needs review.
 */
enum class MigrationSeverity {
    /** Worth knowing, no action expected. */
    INFO,

    /** A setting named in the backup may not exist on this device. Worth reading, not a blocker. */
    WARNING,
}

/** One check the engine produced, or the whole report. */
data class MigrationCheck(
    val severity: MigrationSeverity,
    /** Which setting the check is about, in the words the restore screen uses. */
    val title: String,
    /** The sentence a user reads. Names the field, never promises a result. */
    val detail: String,
)

/**
 * Whether a profile's settings can be honoured on this device.
 *
 * Computed from the backup's own device snapshot and the current device's, before the user confirms
 * a restore. The rules below are all of the form "this field may not be available here", never
 * "this profile will work" — an app cannot invent a refresh rate, and the phrase that helps a user
 * is the one that names what does not exist rather than the one that reassures.
 */
object DeviceMigration {

    /**
     * Compares the two devices and the backup's profile settings.
     *
     * [backup] is the snapshot carried in the file; [current] is this device's. Either may be null,
     * and null is an honest answer rather than an error: a file with no device section was written
     * before this feature existed (or hand-edited), and a current device whose panel could not be
     * read cannot be compared against anything. Both cases return an empty report, and the restore
     * screen says "unknown origin" instead of guessing.
     *
     * [profileRefreshHz], [profileResolution] and [profileDisplaySize] are the settings the file's
     * profiles ask for, gathered by the orchestrator because the profile layer owns their shape.
     * [profileCount] is how many profiles the file carries. Its default is -1, "not reported":
     * zero is a fact the caller has to state, because "this file has no profiles" is a sentence to
     * show and "this test did not ask about profiles" is not.
     */
    fun report(
        backup: DeviceSnapshot?,
        current: DeviceSnapshot?,
        profileCount: Int = -1,
        profileRefreshHz: List<Float> = emptyList(),
        profileResolution: List<ResolutionScale> = emptyList(),
        profileDisplaySizes: List<DisplaySize> = emptyList(),
    ): List<MigrationCheck> {
        if (backup == null || current == null) return emptyList()
        return buildList {
            // ── the device itself
            if (backup.model != current.model || backup.manufacturer != current.manufacturer) {
                add(
                    MigrationCheck(
                        severity = MigrationSeverity.INFO,
                        title = "Coming from another device",
                        detail = "This backup was written on a ${backup.label}. Settings that were " +
                            "measured on that device may not behave the same way here.",
                    ),
                )
            }

            backup.sdkInt?.let { backupSdk ->
                current.sdkInt?.let { currentSdk ->
                    if (backupSdk > currentSdk) {
                        add(
                            MigrationCheck(
                                severity = MigrationSeverity.WARNING,
                                title = "Newer Android version",
                                detail = "The backup was written on Android SDK $backupSdk and this " +
                                    "device runs $currentSdk. Anything that version added, and this " +
                                    "one does not have, may not be available.",
                            ),
                        )
                    }
                }
            }

            // ── refresh rate
            val backupPeak = backup.peakRefreshRateHz
            val currentPeak = current.peakRefreshRateHz
            val pinned = profileRefreshHz.filter { it > 0f }
            if (backupPeak != null && currentPeak != null) {
                if (currentPeak < backupPeak) {
                    add(
                        MigrationCheck(
                            severity = MigrationSeverity.WARNING,
                            title = "Refresh rate",
                            detail = "This device's panel goes up to ${"%.0f".format(currentPeak)} Hz " +
                                "and the backup's went to ${"%.0f".format(backupPeak)} Hz. Any profile " +
                                "pinning a rate above this device's own may not be honoured.",
                        ),
                    )
                }
                pinned.filter { it > currentPeak }.distinct().forEach { rate ->
                    add(
                        MigrationCheck(
                            severity = MigrationSeverity.WARNING,
                            title = "Refresh rate pinned",
                            detail = "A profile asks for ${"%.0f".format(rate)} Hz. This device's " +
                                "panel goes up to ${"%.0f".format(currentPeak)} Hz, so that rate may " +
                                "not be available here.",
                        ),
                    )
                }
            }

            // ── resolution
            val backupPanel = panelOf(backup)
            val currentPanel = panelOf(current)
            if (backupPanel != null && currentPanel != null && !sameShape(backupPanel, currentPanel)) {
                add(
                    MigrationCheck(
                        severity = MigrationSeverity.WARNING,
                        title = "Display size",
                        detail = "The backup's panel was ${backupPanel.label} and this one is " +
                            "${currentPanel.label}. A resolution scale is a percentage of the panel, " +
                            "so the same percentage lands on a different number of pixels here.",
                    ),
                )
            }
            profileResolution.forEach { scale ->
                val target = currentPanel?.let { scale.sizeFor(it) }
                if (target != null && target.shortSide < MIN_USABLE_SHORT_SIDE) {
                    add(
                        MigrationCheck(
                            severity = MigrationSeverity.WARNING,
                            title = "Resolution override",
                            detail = "This device's panel is ${currentPanel.label}, and a profile asks " +
                                "for ${scale.label} of it — ${target.label}. That is below the smallest " +
                                "size GameCore will set, so it may not be honoured.",
                        ),
                    )
                }
            }

            // ── custom display sizes
            profileDisplaySizes.filter { it.shortSide > 0 && it.longSide > 0 }.forEach { size ->
                val panel = currentPanel ?: return@forEach
                if (size.longSide > panel.longSide || size.shortSide > panel.shortSide) {
                    add(
                        MigrationCheck(
                            severity = MigrationSeverity.WARNING,
                            title = "Custom display size",
                            detail = "A profile asks for ${size.label}, which is larger than this " +
                                "device's ${panel.label} panel. It may not be honoured here.",
                        ),
                    )
                }
            }

            // ── profiles carrying all of the above
            if (profileCount == 0) {
                add(
                    MigrationCheck(
                        severity = MigrationSeverity.INFO,
                        title = "No profiles in this file",
                        detail = "The file carries settings and no game profiles, so nothing about " +
                            "this device's display or refresh rates is being asked for.",
                    ),
                )
            }
        }
    }

    /**
     * The panel as a [DisplaySize], or null when the snapshot did not carry one.
     *
     * A zero in either field — a device whose size could not be read — reads as absent rather than as
     * a 0×0 panel, which would otherwise fail every comparison in the report.
     */
    private fun panelOf(snapshot: DeviceSnapshot): DisplaySize? {
        val width = snapshot.panelWidthPixels ?: return null
        val height = snapshot.panelHeightPixels ?: return null
        if (width <= 0 || height <= 0) return null
        return DisplaySize(width, height)
    }

    /**
     * Size within a few percent: a rounding difference, not a different panel.
     *
     * The aspect is compared as a *fraction* of itself, not as an absolute difference. A 5-pixel
     * reading error on a 1080-wide panel moves the ratio from 2.222 to 2.212 — 0.0102 absolute, but
     * only 0.46% of the number being compared. A fixed absolute epsilon would call that a different
     * panel while accepting a much larger error on a squarer one, which is the wrong way round.
     */
    private fun sameShape(a: DisplaySize, b: DisplaySize): Boolean {
        val largerAspect = maxOf(a.aspect, b.aspect)
        val aspectClose = largerAspect == 0f ||
            abs(a.aspect - b.aspect) / largerAspect < ASPECT_TOLERANCE
        val shortSideClose = abs(a.shortSide - b.shortSide) < minOf(a.shortSide, b.shortSide) * 0.05f
        return aspectClose && shortSideClose
    }

    /** Below this a display is not readable enough for the app to set it. */
    private const val MIN_USABLE_SHORT_SIDE = 480

    private const val ASPECT_TOLERANCE = 0.01f
}
