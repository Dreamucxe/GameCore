package com.gamecore.ui.settings

import android.net.Uri
import com.gamecore.core.model.AppSettings

/**
 * §22's settings screen state.
 *
 * [settings] is the whole [AppSettings], for the same reason the profile editor holds a whole
 * [com.gamecore.core.model.GameProfile]: this screen's purpose is to read and write every field of it.
 * §24A.2 forbids handing *internal or platform* objects to composables — a `PackageInfo`, a
 * `PerformanceSnapshot` — not the user's own stored preferences, which have no hidden surface behind them.
 * The one field the screen never shows is `hasSeenIntroduction`, which is not a preference.
 *
 * The three numeric fields below are held separately rather than read off [settings] because they are
 * driven by sliders. A slider whose value comes straight from the stored setting either writes to the
 * encrypted preferences file on every frame of a drag, or does not move; these carry the value under the
 * thumb, and the store is written once when the finger lifts.
 *
 * [isPersisting] is the honest half of an encrypted store. If the Keystore-backed preferences file cannot
 * be opened — a corrupted master key, a device where the provider is broken — GameCore keeps working from
 * memory, and every change on this screen then lasts until the process ends. That is a fact the user is
 * entitled to before they spend a minute setting things up, so it gets a banner rather than a log line.
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoaded: Boolean = false,
    val isPersisting: Boolean = true,
    val uiScalePercent: Int = 100,
    val detectionSeconds: Int = (AppSettings.DEFAULT_DETECTION_INTERVAL / 1_000L).toInt(),
    val sampleSeconds: Int = (AppSettings.DEFAULT_SAMPLE_INTERVAL / 1_000L).toInt(),
    val profileCount: Int = 0,
    val sessionCount: Int = 0,
    val layoutCount: Int = 0,
    /**
     * The latency host as typed, which is not yet the stored one.
     *
     * A host is stored on commit rather than per keystroke: "1.1.1." is a state every valid address
     * passes through, and rejecting it mid-word would make the field impossible to type into.
     */
    val latencyHostDraft: String = AppSettings.DEFAULT_LATENCY_HOST,
    val isExporting: Boolean = false,
    val export: ExportNote? = null,
    val pendingClearHistory: Boolean = false,
    val pendingResetSettings: Boolean = false,
    val message: String? = null,
) {
    val hasHistory: Boolean get() = sessionCount > 0

    /** True when the typed host differs from the stored one, so the screen can offer to apply it. */
    val isHostEdited: Boolean get() = latencyHostDraft.trim() != settings.latencyHost
}

/**
 * The outcome of the last export, held so the share sheet can be offered after the file is written.
 *
 * [uri] is null when the file exists but the provider refused a `content://` grant for it. The export
 * still happened and the text still says so — a successful write is not reported as a failure because a
 * share sheet could not be offered for it.
 */
data class ExportNote(val text: String, val uri: Uri? = null, val isProblem: Boolean = false)

/** The interval sliders' bounds in seconds, derived from [AppSettings]'s own millisecond bounds. */
internal val DETECTION_SECONDS_RANGE: IntRange =
    (AppSettings.MIN_DETECTION_INTERVAL / 1_000L).toInt()..
        (AppSettings.MAX_DETECTION_INTERVAL / 1_000L).toInt()

internal val SAMPLE_SECONDS_RANGE: IntRange =
    (AppSettings.MIN_SAMPLE_INTERVAL / 1_000L).toInt()..(AppSettings.MAX_SAMPLE_INTERVAL / 1_000L).toInt()
