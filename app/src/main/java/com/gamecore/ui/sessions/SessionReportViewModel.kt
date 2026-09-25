package com.gamecore.ui.sessions

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.LatencyLog
import com.gamecore.core.model.LatencyVerdict
import com.gamecore.core.model.SessionCard
import com.gamecore.core.model.SessionSample
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.CardResult
import com.gamecore.data.repository.SampleExportResult
import com.gamecore.data.repository.SampleExporter
import com.gamecore.data.repository.SessionCardRenderer
import com.gamecore.data.repository.SessionRepository
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.autoRange
import com.gamecore.ui.components.readoutOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §21's report: one session, its aggregates, and the graphs its samples draw.
 *
 * Loaded once rather than observed. A finished session does not change — every figure on this screen was
 * settled the moment recording stopped — so a flow here would be a subscription that never fires. The one
 * thing that can change is the session ceasing to exist, which this screen does itself and reports through
 * [SessionReportUiState.isDeleted] so the caller can navigate back.
 *
 * The samples are reduced to `List<Float>` per series before they leave this class. §24A.2 asks that the UI
 * receive only what it draws, and what a graph draws is floats — not 1,800 rows carrying a session id, an
 * elapsed time and seven nullable readings each.
 */
@HiltViewModel
class SessionReportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SessionRepository,
    private val preferences: SecurePreferenceStore,
    private val cards: SessionCardRenderer,
    private val sampleExporter: SampleExporter,
) : ViewModel() {

    /**
     * Read as either a `Long` or a `String`, so this does not depend on the route's `NavType`.
     *
     * A route whose argument type changes should fail to find a session and say so, rather than resolve to
     * a different one.
     */
    private val sessionId: Long = savedState.get<Long>(Destination.ARG_ID)
        ?: savedState.get<String>(Destination.ARG_ID)?.toLongOrNull()
        ?: MISSING_ID

    private val editing = MutableStateFlow(
        SessionReportUiState(confirmBeforeDelete = preferences.settings.value.confirmBeforeDiscard),
    )

    val state: StateFlow<SessionReportUiState> = editing.asStateFlow()

    /**
     * The loaded session, kept for the shareable card.
     *
     * §24A.2 governs what the UI receives, and this is not that — no composable can see it. The card needs
     * the session's own fields rather than this screen's strings, and re-reading the row to draw a picture
     * of data already in memory would be a second query for nothing.
     */
    private var loaded: GameSession? = null

    /** The URI of a card that has been written and not yet handed to the share sheet. Never in state. */
    private var pending: Uri? = null

    /**
     * The URI and MIME of a sample export written and not yet shared. Never in state, for the same reason
     * [pending] is not: the read grant stays out of the presentation layer, which is handed a flag.
     */
    private var pendingExport: Uri? = null
    private var pendingExportMime: String = ""

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val session = if (sessionId == MISSING_ID) null else repository.session(sessionId)
            if (session == null) {
                editing.value = editing.value.copy(
                    isLoading = false,
                    isMissing = true,
                    title = "Session",
                    subtitle = "This session is no longer here",
                )
                return@launch
            }
            loaded = session
            val samples = repository.samples(session.id)
            editing.value = editing.value.copy(
                isLoading = false,
                isMissing = false,
                title = session.gameLabel,
                subtitle = subtitleOf(session),
                headline = headlineOf(session),
                figures = figuresOf(session),
                graphs = graphsOf(samples),
                confidence = confidenceOf(session, samples.size),
                interruption = interruptionOf(session),
                profileNote = if (session.profileApplied) PROFILE_APPLIED else PROFILE_NOT_APPLIED,
            )
        }
    }

    /** Asks first unless the user turned that off, exactly as the history list does. */
    fun askDelete() {
        if (editing.value.confirmBeforeDelete) {
            editing.value = editing.value.copy(pendingDelete = true)
        } else {
            confirmDelete()
        }
    }

    fun cancelDelete() {
        editing.value = editing.value.copy(pendingDelete = false)
    }

    /**
     * Deletes the session and its samples, then says so through [SessionReportUiState.isDeleted].
     *
     * The screen navigates on that flag rather than this class holding a navigation callback: a ViewModel
     * that pops a back stack is a ViewModel that has to know what is underneath it.
     */
    fun confirmDelete() {
        if (sessionId == MISSING_ID) return
        viewModelScope.launch {
            repository.delete(sessionId)
            editing.value = editing.value.copy(pendingDelete = false, isDeleted = true)
        }
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    // ------------------------------------------------------------------------ the shareable card

    /**
     * Draws the card for this session and reports it ready through [SessionReportUiState.cardReady].
     *
     * What the card says comes from [SessionCard] rather than from the strings on this screen. The two are
     * saying the same things about the same session, but a row here is read next to a graph, a caveat and a
     * settings screen, while a card is read alone in somebody's chat window — so the card states its own
     * caveats in its own words, and that decision lives in a model with a test around it.
     *
     * The accent is the user's own choice, because the card is this app's output and should look like the
     * app they configured. Everything else about it is fixed, dark included.
     */
    fun shareCard() {
        val session = loaded ?: return
        if (editing.value.isRenderingCard) return
        editing.value = editing.value.copy(isRenderingCard = true, message = null)
        viewModelScope.launch {
            val result = cards.render(
                card = SessionCard.from(session),
                accentArgb = preferences.settings.value.accent.argb,
            )
            pending = (result as? CardResult.Written)?.uri
            editing.value = editing.value.copy(
                isRenderingCard = false,
                cardReady = pending != null,
                message = when {
                    pending != null -> null
                    result is CardResult.Written -> CARD_NOT_SHAREABLE
                    else -> CARD_FAILED
                },
            )
        }
    }

    /**
     * The share sheet's intent for the card that was just written, or null when there is none.
     *
     * Built here rather than in the composable so the `content://` URI and its read grant never reach the
     * presentation layer — the same reason the captures list hands out an `Intent` instead of a `Uri`.
     */
    fun cardIntent(): Intent? {
        val uri = pending ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(CARD_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Consumes the one-shot, and says so when nothing on the device would take the image. */
    fun cardShared(shared: Boolean) {
        pending = null
        editing.value = editing.value.copy(
            cardReady = false,
            message = if (shared) editing.value.message else CARD_NO_APP,
        )
    }

    // ------------------------------------------------------------------------ the sample export

    /**
     * Writes this session's raw per-sample series out as a file and hands it to the share sheet (§19).
     *
     * The delta to [shareCard]: that draws a picture of the aggregates for a chat window; this exports the
     * numbers behind them for a spreadsheet. The one-tap-to-share shape is identical — write, then fire the
     * sheet through [SessionReportUiState.sampleExportReady] — so a file the user never asked to keep is not
     * left sitting in the app's storage with a screen to manage it.
     *
     * An [SampleExportResult.Empty] is not an error: a session with no samples has nothing to export and
     * says so, the same judgement the aggregate exporter makes about empty history.
     */
    fun exportSamples(format: SampleExporter.Format) {
        if (sessionId == MISSING_ID) return
        if (editing.value.isExportingSamples) return
        editing.value = editing.value.copy(isExportingSamples = true, message = null)
        viewModelScope.launch {
            val result = sampleExporter.export(sessionId, format)
            val written = result as? SampleExportResult.Written
            pendingExport = written?.uri
            pendingExportMime = written?.mimeType.orEmpty()
            editing.value = editing.value.copy(
                isExportingSamples = false,
                sampleExportReady = pendingExport != null,
                message = when (result) {
                    is SampleExportResult.Written ->
                        if (pendingExport != null) null else SAMPLES_NOT_SHAREABLE
                    SampleExportResult.Empty -> SAMPLES_EMPTY
                    is SampleExportResult.Failed -> SAMPLES_FAILED
                },
            )
        }
    }

    /**
     * The share-sheet intent for the file that was just written, or null when there is none.
     *
     * Built here for the same reason [cardIntent] is: the `content://` URI and its one-shot read grant
     * never reach a composable. The MIME rides along on the result rather than being inferred from the
     * name, so a `.csv` and a `.json` are each offered to the apps that actually read them.
     */
    fun sampleExportIntent(): Intent? {
        val uri = pendingExport ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(pendingExportMime.ifEmpty { "text/csv" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Consumes the one-shot, and says so when nothing on the device would open the file. */
    fun sampleExportShared(shared: Boolean) {
        pendingExport = null
        editing.value = editing.value.copy(
            sampleExportReady = false,
            message = if (shared) editing.value.message else SAMPLES_NO_APP,
        )
    }

    // ------------------------------------------------------------------------ the header

    private fun subtitleOf(session: GameSession): String {
        val day = Formatters.relativeDay(session.startedAtMillis)
        val start = Formatters.clockTime(session.startedAtMillis)
        val end = session.endedAtMillis?.let { Formatters.clockTime(it) }
        return if (end == null) "$day · from $start" else "$day · $start – $end"
    }

    /**
     * The three figures across the top: how long, what the battery did, how hot it got.
     *
     * Chosen because they are the three that are measurable on every device GameCore runs on. Processor and
     * memory averages are real on most and absent on some, so they belong in the list below where an
     * absence can carry its reason.
     */
    private fun headlineOf(session: GameSession): List<Readout> {
        val duration = Formatters.durationCoarse(session.durationMillis())
        return listOf(
            readoutOf(
                label = "Duration",
                value = if (session.hasCompleteDuration) duration else "≥ $duration",
            ),
            batteryHeadline(session),
            readoutOf(
                label = "Peak heat",
                value = session.peakTemperatureDeciCelsius?.let { Formatters.temperature(it) } ?: ABSENT,
                tone = when {
                    session.peakTemperatureDeciCelsius == null -> Tone.Muted
                    session.peakTemperatureDeciCelsius >= WARM_DECI_CELSIUS -> Tone.Warning
                    else -> Tone.Neutral
                },
            ),
        )
    }

    /**
     * The headline battery figure is points lost, not a rate.
     *
     * The rate is the more useful number and it gets its own row below, where it can say why it is missing.
     * Up here it would be the one figure in the strip that is sometimes absent on a perfectly good session —
     * a twelve-minute game on a charger — and points lost is a measurement in every case.
     */
    private fun batteryHeadline(session: GameSession): Readout {
        val drain = session.drain()
        return readoutOf(
            label = "Battery",
            value = drain?.let { "${it.pointsLost}%" } ?: ABSENT,
            tone = if (drain == null) Tone.Muted else Tone.Neutral,
        )
    }

    // ------------------------------------------------------------------------ the figures

    /**
     * Everything measured, each row carrying either a number or the reason there isn't one.
     *
     * The order is fixed rather than "available first", so a user comparing two sessions of the same game
     * finds the same row in the same place — including on the device where half of them read "—".
     */
    private fun figuresOf(session: GameSession): List<Readout> = listOf(
        drainRow(session),
        averageRow(
            label = "Processor use",
            average = session.averageCpuPercent,
            peak = session.peakCpuPercent,
            meaningful = session.hasMeaningfulAggregates,
            format = { Formatters.percentValue(it) },
            absentDetail = CPU_ABSENT,
            detail = "Device-wide, every app. Android does not expose a single app's share.",
        ),
        averageRow(
            label = "Memory in use",
            average = session.averageMemoryPercent,
            peak = session.peakMemoryPercent,
            meaningful = session.hasMeaningfulAggregates,
            format = { Formatters.percentValue(it) },
            absentDetail = MEMORY_ABSENT,
            detail = "Of the whole device's RAM, as Android reports it.",
        ),
        averageRow(
            label = "Temperature",
            average = session.averageTemperatureDeciCelsius?.toFloat(),
            peak = session.peakTemperatureDeciCelsius?.toFloat(),
            meaningful = session.hasMeaningfulAggregates,
            format = { Formatters.temperature(it.toInt()) },
            absentDetail = TEMPERATURE_ABSENT,
            detail = "From whichever thermal zone or battery sensor this build exposes.",
        ),
        refreshRateRow(session),
        frameRateRow(session),
        latencyRow(session),
        connectionRow(session),
        colourRow(session),
        readoutOf(
            label = "Samples taken",
            value = session.sampleCount.toString(),
            detail = "One set of readings each time the sampler ran.",
            tone = if (session.hasMeaningfulAggregates) Tone.Neutral else Tone.Muted,
        ),
    )

    /**
     * The colour preset that was on screen while the session ran.
     *
     * Three states, and telling them apart is the point of the row. A name with values means a preset
     * was applied and confirmed. Values with no name means the preset's name has since been emptied or
     * renamed away, which is exactly why the values are stored beside it. Neither reads as absent,
     * because "no preset was applied for this game" and "this session predates GameCore recording the
     * display's colour" are not distinguishable from the row — so the detail names both rather than
     * choosing one.
     *
     * The name goes in the value and the figures go in the detail, rather than
     * [GameSession.colorSummary] in a single string: a correction can change eight fields at once, and
     * that is a paragraph rather than a figure.
     */
    private fun colourRow(session: GameSession): Readout {
        if (!session.hasColorReading) {
            return readoutOf(
                label = "Screen colour",
                value = ABSENT,
                detail = "No colour preset was applied for this game, or this session was recorded " +
                    "before GameCore recorded the display's colour.",
                tone = Tone.Muted,
            )
        }
        return readoutOf(
            label = "Screen colour",
            value = session.colorPresetName?.takeIf { it.isNotBlank() } ?: "Custom values",
            detail = session.colorCorrection?.summary?.let {
                "$it. What the preset asked for; anything this display had no control for was " +
                    "reported as unreachable when the profile was applied."
            } ?: "The preset's values were not recorded with this session.",
            tone = Tone.Neutral,
        )
    }

    /**
     * The drain rate, or the reason it cannot be quoted.
     *
     * Three distinct absences and they are not the same fact: no end level recorded, a charger connected,
     * or a session too short for one percentage point of quantisation not to dominate. Collapsing them into
     * one "—" would leave the user unable to tell a limitation from a mistake.
     */
    private fun drainRow(session: GameSession): Readout {
        val drain = session.drain() ?: return readoutOf(
            label = "Battery drain",
            value = ABSENT,
            detail = "No battery level was recorded at the end of this session.",
            tone = Tone.Muted,
        )
        val perHour = drain.percentPerHour ?: return readoutOf(
            label = "Battery drain",
            value = ABSENT,
            detail = if (drain.wasCharging) {
                "A charger was connected during this session, so a drain rate would describe the charger."
            } else {
                "Under five minutes. A rate from a session this short is mostly rounding."
            },
            tone = Tone.Muted,
        )
        return readoutOf(
            label = "Battery drain",
            value = Formatters.percentValue(perHour, decimals = 1) + " / hour",
            detail = "${drain.startPercent}% → ${drain.endPercent}% over " +
                Formatters.durationCoarse(drain.elapsedMillis),
            tone = Tone.Neutral,
        )
    }

    /**
     * An average with its peak beside it, or an absence with its reason.
     *
     * Two different absences share this function: the metric was never readable on this device, and it was
     * readable but there are too few samples for a mean to describe anything. [GameSession.hasMeaningfulAggregates]
     * draws the line at ten, and below it the report shows the graph and no summary.
     */
    private fun averageRow(
        label: String,
        average: Float?,
        peak: Float?,
        meaningful: Boolean,
        format: (Float) -> String,
        absentDetail: String,
        detail: String,
    ): Readout {
        if (average == null) {
            return readoutOf(label = label, value = ABSENT, detail = absentDetail, tone = Tone.Muted)
        }
        if (!meaningful) {
            return readoutOf(
                label = label,
                value = ABSENT,
                detail = "Too few samples for an average. The graph below shows what was taken.",
                tone = Tone.Muted,
            )
        }
        return readoutOf(
            label = label,
            value = format(average),
            detail = peak?.let { "Peak ${format(it)}. $detail" } ?: detail,
            tone = Tone.Neutral,
        )
    }

    private fun refreshRateRow(session: GameSession): Readout = readoutOf(
        label = "Refresh rate",
        value = session.averageRefreshRate?.let { Formatters.hertz(it) } ?: ABSENT,
        detail = if (session.averageRefreshRate == null) {
            "The display's rate was not readable while this session ran."
        } else {
            "Averaged over the samples. What the display reported, not what was requested."
        },
        tone = if (session.averageRefreshRate == null) Tone.Muted else Tone.Neutral,
    )

    /**
     * §24B's FPS row, on the screen where a fake one would be most tempting.
     *
     * A recorded session either has frame-timing samples on it or it does not, and on most devices it does
     * not, because a reliable per-frame signal is not available through public APIs without root or
     * instrumentation. Nothing here derives a figure from the refresh rate or from anything else: the row
     * says the device could not measure it, which is the truth and is more useful than a number.
     */
    private fun frameRateRow(session: GameSession): Readout = readoutOf(
        label = "Frame rate",
        value = session.averageFrameRate?.let { Formatters.hertz(it) } ?: "Not available",
        detail = if (session.averageFrameRate == null) {
            "This device exposes no reliable per-frame timing to an ordinary app, so none was recorded. " +
                "GameCore does not estimate one from the refresh rate."
        } else {
            "From the platform's own frame-timing report for this session."
        },
        tone = if (session.averageFrameRate == null) Tone.Muted else Tone.Neutral,
    )

    /**
     * The average round trip, or which of several absences applies.
     *
     * The old single sentence — "measurement was off, or no reply came back" — was two unrelated facts
     * offered as a guess, and the probe log now knows which of them it was. A dash beside "no probe was
     * sent" is a setting; a dash beside "none of 340 probes completed" is a network.
     */
    private fun latencyRow(session: GameSession): Readout {
        val average = session.averageLatencyMillis
        val log = session.latencyLog
        return readoutOf(
            label = "Network latency",
            value = average?.let { Formatters.millis(it) } ?: ABSENT,
            detail = when {
                average != null ->
                    "Averaged over the samples. A TCP handshake with the host set in Settings — not a " +
                        "ping, and not the game's own server."

                log == null -> "No latency figure was recorded for this session."

                log.attempts == 0 ->
                    "No probe was sent: latency measurement is off in Settings, or the device was not " +
                        "connected while this game was open."

                log.completedProbes == 0 ->
                    "None of the ${Formatters.count(log.attempts, "probe")} sent during this session " +
                        "completed, so there is no round trip to average."

                // Probes completed but none reached a stored sample — a session that ended between the
                // probe and the next tick. Rare, and worth saying rather than papering over.
                else -> "Probes completed, but none of them landed in a stored sample, so this session " +
                    "has no average to show."
            },
            tone = if (average == null) Tone.Muted else Tone.Neutral,
        )
    }

    /**
     * Whether the connection held up, from GameCore's own probes rather than from the average.
     *
     * The average is the wrong figure for this question: a session that sat at 30 ms and jumped to 900 ms
     * eleven times averages out near 40 ms and reads as a good connection, which is precisely the session
     * a player remembers as unplayable. The log counts the events instead.
     *
     * A null log is its own answer — sessions recorded before the log existed had nothing counted, and
     * saying "stable" about those would be inventing the measurement. [LatencyVerdict] carries the word
     * and [LatencyLog.summary] the figures behind it.
     */
    private fun connectionRow(session: GameSession): Readout {
        val log = session.latencyLog ?: return readoutOf(
            label = "Connection",
            value = ABSENT,
            detail = "This session was recorded before GameCore kept a log of its own probes.",
            tone = Tone.Muted,
        )
        return readoutOf(
            label = "Connection",
            value = log.verdict.label,
            detail = log.summary,
            tone = when (log.verdict) {
                LatencyVerdict.STABLE -> Tone.Good
                LatencyVerdict.SPIKY -> Tone.Warning
                LatencyVerdict.UNRELIABLE, LatencyVerdict.DOWN -> Tone.Danger
                LatencyVerdict.UNMEASURED -> Tone.Muted
            },
        )
    }

    // ------------------------------------------------------------------------ the graphs

    /**
     * The samples, reduced to the series each plot draws.
     *
     * `mapNotNull` per series rather than one pass with zeroes substituted: a device that could read the
     * processor but not the temperature produces a full CPU line and no thermal line, and that is the
     * honest picture. A `?: 0f` here would draw a session that ran at absolute zero.
     *
     * Percentages plot against 0–100 so the height of the line means something; temperature and frame rate
     * auto-scale, because 38–44 °C against 0–100 is a flat line that hides the movement the graph is for.
     *
     * The latency line is the one series whose points are not one reading each, and it carries a note
     * saying so: probes are sent every fifth sample and the samples in between repeat the last result, so
     * a single slow handshake draws as a plateau rather than as the spike it was. The `Connection` figure
     * above counts the events; this shows roughly when they happened.
     */
    private fun graphsOf(samples: List<SessionSample>): List<SessionGraph> {
        val cpu = samples.mapNotNull { it.cpuPercent }
        val memory = samples.mapNotNull { it.memoryPercent }
        val temperature = samples.mapNotNull { it.temperatureDeciCelsius?.let { deci -> deci / 10f } }
        val battery = samples.mapNotNull { it.batteryPercent?.toFloat() }
        val frames = samples.mapNotNull { it.frameRate }
        val latency = samples.mapNotNull { it.latencyMillis?.toFloat() }
        return listOf(
            SessionGraph(
                title = "Processor and memory",
                points = cpu,
                range = 0f..100f,
                unit = "%",
                seriesLabel = "Processor",
                secondary = memory,
                secondaryLabel = "Memory",
                emptyMessage = "No processor samples were recorded for this session.",
            ),
            SessionGraph(
                title = "Temperature",
                points = temperature,
                range = if (temperature.isEmpty()) 20f..50f else autoRange(temperature),
                unit = "°C",
                seriesLabel = "Temperature",
                emptyMessage = "No temperature was readable while this session ran.",
                tone = Tone.Warning,
            ),
            SessionGraph(
                title = "Battery level",
                points = battery,
                range = 0f..100f,
                unit = "%",
                seriesLabel = "Charge",
                emptyMessage = "No battery levels were recorded for this session.",
            ),
            SessionGraph(
                title = "Frame rate",
                points = frames,
                range = if (frames.isEmpty()) 0f..60f else autoRange(frames),
                unit = "fps",
                seriesLabel = "Frames",
                emptyMessage = "This device does not expose reliable frame timing, so none was recorded.",
            ),
            SessionGraph(
                title = "Network latency",
                points = latency,
                range = if (latency.isEmpty()) 0f..200f else autoRange(latency),
                unit = "ms",
                seriesLabel = "Round trip",
                emptyMessage = "No probe completed during this session, so there is nothing to plot.",
                note = "Each probe holds its value until the next one, so a slow handshake draws wider " +
                    "than the moment it happened. Nothing here is a lost packet.",
            ),
        )
    }

    // ------------------------------------------------------------------------ what the figures rest on

    /**
     * The sentence that makes the rest of the screen readable.
     *
     * Every average above is an average of *these* samples over *this* long. Printing that once, plainly,
     * is what separates a report from a set of numbers — and on a session with four samples it is the part
     * that matters most.
     */
    private fun confidenceOf(session: GameSession, sampleCount: Int): String {
        val duration = Formatters.durationCoarse(session.durationMillis())
        if (sampleCount == 0) {
            return "No samples were recorded for this session. The duration and the battery levels are " +
                "still measurements; there is nothing else to report."
        }
        val each = if (sampleCount > 1) {
            val interval = session.durationMillis() / (sampleCount - 1)
            " — about one every ${Formatters.durationCoarse(interval)}"
        } else {
            ""
        }
        val head = "${Formatters.count(sampleCount, "sample")} over $duration$each."
        return if (session.hasMeaningfulAggregates) {
            "$head Averages are over all of them."
        } else {
            "$head Fewer than ${GameSession.MIN_SAMPLES_FOR_AVERAGES} is too few to average, so the " +
                "figures above show what was taken rather than a summary."
        }
    }

    /** Null unless the recording was cut short, in which case the duration is a floor and this says why. */
    private fun interruptionOf(session: GameSession): String? {
        val reason = session.stopReason ?: return null
        if (reason.durationIsComplete) return null
        return "${reason.label}. The session was closed from its last sample, so the duration above is a " +
            "lower bound — the game may well have run on."
    }

    private companion object {
        const val MISSING_ID = -1L
        const val WARM_DECI_CELSIUS = 420

        const val CARD_MIME = "image/png"
        const val CARD_FAILED = "The card could not be created. Storage may be full."
        const val CARD_NOT_SHAREABLE = "The card was created, but this device would not share it."
        const val CARD_NO_APP = "No app on this device can accept an image."

        const val SAMPLES_EMPTY = "This session has no samples to export."
        const val SAMPLES_FAILED = "The samples could not be exported. Storage may be full."
        const val SAMPLES_NOT_SHAREABLE =
            "The file was written, but this device would not share it."
        const val SAMPLES_NO_APP = "No app on this device can open that file."

        const val PROFILE_APPLIED =
            "A profile was applied for this game when it started, and the settings it changed were put " +
                "back when it closed."
        const val PROFILE_NOT_APPLIED =
            "No profile was applied for this session, so nothing about the device was changed while it ran."

        const val CPU_ABSENT =
            "Processor use was not readable on this device. Recent Android builds restrict /proc to an " +
                "app's own process."
        const val MEMORY_ABSENT = "No memory readings were recorded for this session."
        const val TEMPERATURE_ABSENT =
            "No thermal zone or battery sensor was readable while this session ran."
    }
}
