package com.gamecore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.AppSettings
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.ExportResult
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.HudLayoutRepository
import com.gamecore.data.repository.SessionExporter
import com.gamecore.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §22's settings, and the two destructive actions that live with them.
 *
 * Every control writes through to [SecurePreferenceStore], which normalises and stores on the spot. There
 * is no save button and no draft of the whole settings object: a preference the user changed and then lost
 * by navigating away is a bug they will report as "the app forgets my settings". The three sliders and the
 * latency field are the exceptions and they are exceptions about *when* the write happens, never whether
 * it does — a slider stores on release, the host field on commit.
 *
 * The counts across the bottom sections come from the repositories rather than being passed in from the
 * screens that own them, so the numbers beside "Game profiles" and "Clear history" are the database's
 * answer and not a figure carried across a navigation.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val sessions: SessionRepository,
    private val exporter: SessionExporter,
    profiles: GameProfileRepository,
    layouts: HudLayoutRepository,
) : ViewModel() {

    /**
     * What this screen owns rather than observes.
     *
     * [scaleDraft] is null unless a drag is in progress, so a scale changed anywhere else — a reset, another
     * process — is picked up immediately instead of being held off by a draft that was never released.
     */
    private data class LocalState(
        val isLoaded: Boolean = false,
        val isPersisting: Boolean = true,
        val scaleDraft: Int? = null,
        val hostDraft: String? = null,
        val isExporting: Boolean = false,
        val export: ExportNote? = null,
        val pendingClearHistory: Boolean = false,
        val pendingResetSettings: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<SettingsUiState> = combine(
        preferences.settings,
        profiles.profileCount,
        sessions.sessionCount,
        layouts.layouts.map { it.size },
        local,
    ) { settings, profileCount, sessionCount, layoutCount, own ->
        SettingsUiState(
            settings = settings,
            isLoaded = own.isLoaded,
            isPersisting = own.isPersisting,
            uiScalePercent = own.scaleDraft ?: settings.uiScalePercent,
            detectionSeconds = (settings.detectionIntervalMillis / 1_000L).toInt(),
            sampleSeconds = (settings.sampleIntervalMillis / 1_000L).toInt(),
            profileCount = profileCount,
            sessionCount = sessionCount,
            layoutCount = layoutCount,
            latencyHostDraft = own.hostDraft ?: settings.latencyHost,
            isExporting = own.isExporting,
            export = own.export,
            pendingClearHistory = own.pendingClearHistory,
            pendingResetSettings = own.pendingResetSettings,
            message = own.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        SettingsUiState(),
    )

    /**
     * Opens the encrypted store off the main thread before asking whether it opened.
     *
     * [SecurePreferenceStore.isPersisting] is a property, and reading it is what forces the Keystore work
     * if nothing has yet. Doing that here rather than in the `combine` keeps a settings screen from being
     * the thing that blocks the first frame on a cold start.
     */
    init {
        viewModelScope.launch {
            preferences.preload()
            local.value = local.value.copy(isLoaded = true, isPersisting = preferences.isPersisting)
        }
    }

    // ------------------------------------------------------------------------------ the settings

    /**
     * Applies one change to the stored settings.
     *
     * A single transform rather than twenty named setters, matching the profile editor: every switch and
     * every choice on this screen is one `copy` of one field, and a wrapper per field would be twenty
     * functions that each add a name and no behaviour. [SecurePreferenceStore.updateSettings] normalises
     * and writes, so clamping lives in the store rather than at each call site.
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        preferences.updateSettings(transform)
    }

    /** During the drag. The app does not rescale until [commitScale]. */
    fun setUiScale(percent: Int) {
        local.value = local.value.copy(scaleDraft = percent)
    }

    fun commitScale() {
        val draft = local.value.scaleDraft ?: return
        local.value = local.value.copy(scaleDraft = null)
        update { it.copy(uiScalePercent = draft) }
    }

    /**
     * The intervals are stepped in whole seconds rather than dragged.
     *
     * A slider over 1 000–30 000 ms puts every value a person would actually choose in the first eighth of
     * its travel, and the difference between a two- and a three-second sample interval is a decision, not a
     * gesture. One tap, one write, no draft to go stale.
     */
    fun stepDetectionInterval(delta: Int) = update {
        val seconds = (it.detectionIntervalMillis / 1_000L).toInt() + delta
        it.copy(detectionIntervalMillis = seconds.coerceIn(DETECTION_SECONDS_RANGE) * 1_000L)
    }

    fun stepSampleInterval(delta: Int) = update {
        val seconds = (it.sampleIntervalMillis / 1_000L).toInt() + delta
        it.copy(sampleIntervalMillis = seconds.coerceIn(SAMPLE_SECONDS_RANGE) * 1_000L)
    }

    // --------------------------------------------------------------------------------- network

    fun setLatencyHost(value: String) {
        local.value = local.value.copy(hostDraft = value)
    }

    /**
     * Stores the typed host, or says why it was not stored.
     *
     * Validated rather than trusted, for two reasons that are both about the user. A host with a space, a
     * scheme or a path in it resolves to nothing, and the network row would then read "no reply came back"
     * forever with no hint that the address is the problem. And this string is stored, then rendered in the
     * HUD and on the performance screen, so §24A.4 applies to it exactly as it does to a crosshair name.
     */
    fun commitLatencyHost() {
        val typed = local.value.hostDraft ?: return
        val host = typed.trim()
        if (host.isEmpty()) {
            local.value = local.value.copy(hostDraft = null)
            return
        }
        if (!isPlausibleHost(host)) {
            local.value = local.value.copy(message = INVALID_HOST)
            return
        }
        local.value = local.value.copy(hostDraft = null)
        update { it.copy(latencyHost = host) }
    }

    fun resetLatencyHost() {
        local.value = local.value.copy(hostDraft = null)
        update { it.copy(latencyHost = AppSettings.DEFAULT_LATENCY_HOST) }
    }

    /**
     * A hostname or an IP literal, and nothing else.
     *
     * Deliberately not a full RFC check: this decides whether a string is worth handing to
     * `InetSocketAddress`, and the authoritative answer to "does this host exist" is the connection
     * attempt itself. What it does rule out is everything that is certainly not a host — whitespace,
     * a scheme, a port, a path, credentials — which is the class of mistake a person actually makes
     * when they paste a server address in.
     */
    private fun isPlausibleHost(host: String): Boolean {
        if (host.length > MAX_HOST_LENGTH) return false
        if (host.none { it.isLetterOrDigit() }) return false
        if (host.any { it.isWhitespace() || it.isISOControl() }) return false
        if (host.any { it in FORBIDDEN_HOST_CHARACTERS }) return false
        // An IPv6 literal is the one legitimate use of ':' here; a port or a scheme is not.
        if (host.contains(':') && !host.all { it == ':' || it == '.' || it.isDigitOrHex() }) return false
        return host.split('.').none { label -> label.startsWith('-') || label.endsWith('-') }
    }

    private fun Char.isDigitOrHex(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'

    // ------------------------------------------------------------------------------------- data

    /**
     * Writes the CSV, then reports what happened in the same words on every path.
     *
     * The button is disabled while this runs rather than queueing a second export: two exports a second
     * apart produce two near-identical files, and the second one is never what the user wanted.
     */
    fun export() {
        if (local.value.isExporting) return
        local.value = local.value.copy(isExporting = true, export = null)
        viewModelScope.launch {
            val result = exporter.export()
            local.value = local.value.copy(isExporting = false, export = noteFor(result))
        }
    }

    private fun noteFor(result: ExportResult): ExportNote = when (result) {
        is ExportResult.Written -> ExportNote(
            text = "${result.fileName} · ${Formatters.count(result.sessionCount, "session")} · " +
                Formatters.bytes(result.sizeBytes),
            uri = result.uri,
        )
        ExportResult.Empty -> ExportNote(
            text = "There is no history to export yet.",
            isProblem = true,
        )
        is ExportResult.Failed -> ExportNote(
            text = "The file could not be written (${result.detail}). Free some storage and try again.",
            isProblem = true,
        )
    }

    fun dismissExport() {
        local.value = local.value.copy(export = null)
    }

    /** Both destructive actions always ask, whatever `confirmBeforeDiscard` says: neither is undoable. */
    fun askClearHistory() {
        if (state.value.sessionCount == 0) return
        local.value = local.value.copy(pendingClearHistory = true)
    }

    fun confirmClearHistory() {
        viewModelScope.launch {
            sessions.clearHistory()
            local.value = local.value.copy(
                pendingClearHistory = false,
                export = null,
                message = "History cleared. Every session and all of its samples are gone from this device.",
            )
        }
    }

    fun askResetSettings() {
        local.value = local.value.copy(pendingResetSettings = true)
    }

    /**
     * Puts every preference back to its default. Touches no recorded data.
     *
     * Two separate buttons for two separate destructions: someone who wants their accent colour back
     * should not lose six weeks of session history to get it, and the confirmation dialogs say which
     * one each is.
     */
    fun confirmResetSettings() {
        preferences.resetToDefaults()
        local.value = local.value.copy(
            pendingResetSettings = false,
            scaleDraft = null,
            hostDraft = null,
            message = "Settings are back to their defaults. Nothing recorded was touched.",
        )
    }

    fun cancelPending() {
        local.value = local.value.copy(pendingClearHistory = false, pendingResetSettings = false)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val MAX_HOST_LENGTH = 253

        /** Characters that mean the string is a URL, a socket address or a credential, not a host. */
        val FORBIDDEN_HOST_CHARACTERS = charArrayOf('/', '\\', '@', '?', '#', '"', '\'', '%', '[', ']')

        const val INVALID_HOST =
            "That does not look like a hostname or an IP address. Enter just the host — no scheme, port " +
                "or path — for example 1.1.1.1 or dns.google."
    }
}
