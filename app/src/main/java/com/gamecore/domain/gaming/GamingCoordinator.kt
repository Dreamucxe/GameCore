package com.gamecore.domain.gaming

import com.gamecore.core.model.ChangeOrigin
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.MemoryReclaimReport
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.ProfileApplication
import com.gamecore.core.model.RestoreReport
import com.gamecore.core.model.StopReason
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.ColorPresetRepository
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.domain.memory.BackgroundAppReclaimer
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What GameCore is doing about the game in front, as one object.
 *
 * Carries only what the notification and the Home card read — §24A.2 — which is why the profile is
 * represented by [ProfileApplication] and a label rather than by the [GameProfile] itself: the
 * overlay does not need the user's brightness preference to draw a session timer.
 */
data class GamingState(
    /** The package being tracked, or null when nothing is. */
    val playing: String? = null,
    val gameLabel: String = "",
    val startedAtMillis: Long = 0L,
    /** Null while the profile is still being applied, and when auto-apply is switched off. */
    val application: ProfileApplication? = null,
    /** The outcome of the last restore, kept so an incomplete one can be reported and retried. */
    val lastRestore: RestoreReport? = null,
    /**
     * What the launch's memory pass did, or null when the profile did not ask for one.
     *
     * Its own field rather than part of [application], because it is not part of applying a profile:
     * nothing here is written to the device, nothing is restored on exit, and the two exhaustive
     * `when`s over [com.gamecore.core.model.OptimizationAction] should not grow a case for an action
     * that has no restore point. It is a reading about other apps, taken once, shown once, and not
     * stored anywhere.
     */
    val reclaim: MemoryReclaimReport? = null,
) {
    val isTracking: Boolean get() = playing != null

    companion object {
        val IDLE = GamingState()
    }
}

/**
 * Turns detected games into applied profiles, live sampling and recorded sessions, in that order.
 *
 * The one place where the gaming pieces meet. [GameDetector] says what happened, [ProfileApplier]
 * changes the device, [SessionRecorder] writes the record, and [PerformanceMonitor] provides the
 * samples; none of them know about each other, and the order they run in is the substance of this
 * class:
 *
 *  - **Apply before recording.** The session row records whether a profile was applied, and a
 *    profile whose refresh-rate pin was rejected should be recorded as applied-with-problems rather
 *    than not applied. Recording first would have to guess.
 *  - **Restore after ending.** The last sample belongs to the session, and it is taken while the
 *    profile is still in force. Restoring first would put a sample of the *restored* device into the
 *    record of the *optimized* one.
 *  - **One sampling job, cancelled and joined.** [SessionRecorder] buffers samples and writes them in
 *    batches under a lock; a sampler still running while the session is being closed could append to
 *    a session that has already been aggregated. `cancelAndJoin` before `end` makes that impossible.
 *  - **Free memory last, if at all.** [BackgroundAppReclaimer] is the one step here that acts on the
 *    user's *other* apps, it is off unless a profile asks for it, and it is the slowest — so it runs
 *    once everything the user is waiting for is already done.
 *
 * Nothing here decides *whether* to act — a profile that is disabled, a user who has switched auto-
 * apply or session tracking off, and a game with no profile at all are all filtered before anything
 * is written. Those are settings, and this class reads them rather than second-guessing them.
 */
@Singleton
class GamingCoordinator @Inject constructor(
    private val detector: GameDetector,
    private val profiles: GameProfileRepository,
    private val applier: ProfileApplier,
    private val recorder: SessionRecorder,
    private val monitor: PerformanceMonitor,
    private val apps: InstalledAppLister,
    private val overlays: OverlayController,
    private val reclaimer: BackgroundAppReclaimer,
    private val colorPresets: ColorPresetRepository,
    private val preferences: SecurePreferenceStore,
) {

    private val state = MutableStateFlow(GamingState.IDLE)

    /** What is being tracked and what was done about it. Read by the service and the dashboard. */
    val gaming: StateFlow<GamingState> = state.asStateFlow()

    /** The session being written, for a live duration and drain figure. */
    val session get() = recorder.active

    /** The sampling and recording job for the current game. Null when nothing is being recorded. */
    private var sampling: Job? = null

    /**
     * Handles detected games until the caller's scope is cancelled.
     *
     * `coroutineScope` rather than an injected application scope, deliberately: the sampling child
     * launched from here must not outlive the service that called this. A detection service that is
     * stopped — because the user switched detection off, or because the system took it down — leaves
     * nothing behind reading `/proc/stat` every two seconds, which is what §26 asks for.
     *
     * The detector's channel is a rendezvous, so this body runs to completion before the next event is
     * received. That is what makes a straight switch between two games safe: the stop's restore
     * finishes before the new profile is applied, so the second write cannot be undone by the first
     * game's restore point.
     */
    suspend fun run(): Unit = coroutineScope {
        val scope = this
        detector.events().collect { event ->
            when (event) {
                is GameEvent.Started -> onStarted(event, scope)
                is GameEvent.Stopped -> onStopped(event)
            }
        }
    }

    /**
     * Ends the current session from outside — the overlay's stop button, or a deliberate shutdown.
     *
     * Routed through the detector rather than calling [onStopped] here, so that the stop is one of
     * [GameWatch]'s own transitions and the game is suppressed until the user leaves it. Closing the
     * session directly would have the detector announce the same game again on its next poll.
     */
    fun stopCurrent(reason: StopReason = StopReason.STOPPED_BY_USER) {
        detector.requestStop(reason)
    }

    /**
     * Drops the last launch's memory summary once the user has read it.
     *
     * Cleared here rather than remembered as "dismissed" by whatever showed it. The report belongs to a
     * launch, and a flag on a screen would either outlive that launch — swallowing the next game's
     * summary — or be lost on a configuration change and bring the banner back. Nothing is undone by
     * this: the apps stay closed, the sentence about them stops being on screen.
     */
    fun dismissReclaim() {
        state.value = state.value.copy(reclaim = null)
    }

    /**
     * Closes an open session because the *host* is going away, not because the game stopped.
     *
     * [stopCurrent] cannot do this. It routes through the detector so the stop becomes one of
     * [GameWatch]'s transitions, and the loop that would pick it up is a child of the caller's scope —
     * the very scope that is about to be cancelled. The request would be conflated into a channel nobody
     * reads again, and the session would be left open for the next launch's repair pass to close with
     * `PROCESS_DEATH`: a worse record than the truth, because the app was stopped deliberately and knew
     * the duration exactly.
     *
     * Bounded, because the caller is a service's `onDestroy` blocking its thread. The order inside
     * [onStopped] is what makes a timeout survivable: sampling is stopped and the row is closed before
     * the device is put back, so a restore that hangs on a dead Shizuku binder costs the restore — which
     * stays in the restore table and is offered on the Home screen at the next launch — and not the
     * session.
     */
    suspend fun shutdown(reason: StopReason) {
        val playing = state.value.playing
        if (playing == null && !recorder.isRecording) return
        withTimeoutOrNull(SHUTDOWN_BUDGET_MILLIS) {
            onStopped(
                GameEvent.Stopped(
                    packageName = playing.orEmpty(),
                    atMillis = System.currentTimeMillis(),
                    reason = reason,
                ),
            )
        }
    }

    // --------------------------------------------------------------------------- a game starts

    /**
     * Applies the profile, starts recording, frees memory, and only as far as the settings allow.
     *
     * The profile is re-read rather than trusted from the detector's tracked set: the set is observed
     * asynchronously, so a profile disabled or deleted in the second between the poll and this call
     * would otherwise be applied to a game the user just stopped tracking.
     *
     * The state is published *before* the profile is applied, because applying can take a second or
     * two of shell round trips and the notification should already name the game by then.
     */
    private suspend fun onStarted(event: GameEvent.Started, scope: CoroutineScope) {
        val profile = profiles.profileFor(event.packageName)?.takeIf { it.isEnabled } ?: return
        val settings = preferences.settings.value

        // The installed label wins over the stored one, so a game renamed by an update appears in
        // history under the name the user sees in their launcher.
        val label = apps.describe(event.packageName)?.label ?: profile.label
        state.value = GamingState(
            playing = event.packageName,
            gameLabel = label,
            startedAtMillis = event.atMillis,
        )

        // Before the profile, not after. Applying it is a second or two of shell round trips, and the
        // overlay is the only visible sign that GameCore noticed the game at all — a button that arrives
        // once the loading screen is over reads as a coincidence rather than as a feature.
        overlays.applyProfile(profile, label)

        val application = if (settings.autoApplyProfiles) {
            // Automatic, so a setting the user changed by hand mid-session is left as they left it
            // rather than being written over by the profile every time the game comes back.
            applier.apply(profile, event.atMillis, ChangeOrigin.AUTOMATIC)
        } else {
            null
        }
        state.value = state.value.copy(application = application)

        if (settings.trackSessions && profile.trackSession) {
            startRecording(
                event = event,
                label = label,
                // Confirmed changes only. An unverified write — the MediaTek refresh-rate case — is
                // not counted as applied anywhere else in the app, and a session row claiming the
                // device was optimized when GameCore could not read the change back would be exactly
                // the invented statistic §24 forbids.
                applied = (application?.appliedCount ?: 0) > 0,
                colour = appliedColour(profile, application),
                scope = scope,
            )
        }

        if (profile.freeRamOnLaunch) freeMemoryFor(event)
    }

    /**
     * Closes background apps for a profile that asked for it, last of everything this launch does.
     *
     * Last on purpose. The pass is two to four seconds of shell round trips — a dump, one `am kill`
     * per app, a settle, then a second dump to check — and every step above it is something the user
     * is waiting for: the overlay, the settings that make the game run the way they configured it,
     * and the session row's start. Running it first would buy the game its memory a couple of seconds
     * sooner and delay all three, which is the wrong trade for a switch that is off by default.
     *
     * The recording is deliberately already running by the time this starts, so the samples taken
     * across the pass are in the session like any others. That is the honest record: the memory line
     * of a session that began with a reclaim should show what actually happened to memory, not skip
     * the interval GameCore was responsible for.
     *
     * The report is dropped rather than published if the game is no longer the one being tracked.
     * [shutdown] runs outside the detector's loop — a service being destroyed while this pass is in
     * flight — and a banner about the game the user just left, attached to whatever state replaced it,
     * would be worse than no banner.
     */
    private suspend fun freeMemoryFor(event: GameEvent.Started) {
        val report = reclaimer.reclaim(event.packageName)
        if (state.value.playing == event.packageName) {
            state.value = state.value.copy(reclaim = report)
        }
    }

    /**
     * The colour preset the session should record, or null if it should record none.
     *
     * Read back by id rather than taken from the result, because an [OptimizationResult] carries a
     * sentence and not the fourteen values behind it, and the row wants both.
     *
     * Null unless the colour step reported [OptimizationResult.isApplied], which is the same rule
     * [ProfileApplication.appliedCount] uses and holds here for the same reason: a preset whose writes
     * the device accepted but would not read back has not been confirmed, and a history row naming the
     * colour a screen was in when GameCore does not know it got there is an invented statistic. A
     * profile with no preset, auto-apply switched off, and a device that refused the keys all record as
     * a session that left the screen alone — which is what happened in all three cases.
     *
     * What is stored is the preset as the user authored it, including any field this device had no sink
     * for. That is the reproducible fact months later; which of those fields the display could express
     * belongs to the moment the profile was applied, and [ProfileApplication] said so there.
     */
    private suspend fun appliedColour(
        profile: GameProfile,
        application: ProfileApplication?,
    ): ColorPreset? {
        val presetId = profile.colorPresetId ?: return null
        val applied = application?.results?.any {
            it.action == OptimizationAction.APPLY_COLOR_CORRECTION && it.isApplied
        } == true
        return if (applied) colorPresets.preset(presetId) else null
    }

    /**
     * Opens the session row and feeds it samples for as long as the game runs.
     *
     * The opening sample comes from the monitor's own stream rather than a one-off read, because
     * subscribing is what starts the sampling loop and the first sample it produces is the one the
     * session should open with — [PerformanceMonitor.sampleOnce] between two ticks shortens the next
     * tick's CPU delta. It is still the fallback: a session that opens with no battery reading has no
     * drain figure at all, which is worse than one whose first sample is a second late.
     *
     * The gap between that first collection ending and the sampling collection starting does not stop
     * the loop, because the monitor's `WhileSubscribed` has a grace period an order of magnitude longer
     * than the gap.
     *
     * Samples are filtered by capture time rather than taken as they arrive: [PerformanceMonitor]
     * publishes a `StateFlow`, so a new collector is handed the current value immediately and the
     * opening sample would otherwise be recorded twice.
     *
     * The probes are collected separately from the samples, and from the monitor's own probe stream
     * rather than off the snapshots, because a snapshot repeats the last probe's outcome on every tick
     * between probes: folding snapshots would count one handshake five times over and could not tell a
     * probe that failed from a tick that never probed. It is a child of [sampling] so that `cancelAndJoin`
     * in [onStopped] covers both, and it starts after the row exists — a probe offered before `begin`
     * has nowhere to go and is dropped by the recorder.
     */
    private suspend fun startRecording(
        event: GameEvent.Started,
        label: String,
        applied: Boolean,
        colour: ColorPreset?,
        scope: CoroutineScope,
    ) {
        monitor.watch(event.packageName)
        monitor.clearHistory()
        sampling = scope.launch {
            val opening = withTimeoutOrNull(OPENING_TIMEOUT_MILLIS) {
                monitor.snapshots.filterNotNull().first { it.capturedAtMillis >= event.atMillis }
            } ?: monitor.sampleOnce()

            recorder.begin(
                packageName = event.packageName,
                gameLabel = label,
                profileApplied = applied,
                opening = opening,
                nowMillis = event.atMillis,
                // Resolved before this coroutine was launched, not here. The opening sample can take
                // several seconds to arrive, and reading the preset back at that point would risk
                // recording a name the user renamed in the meantime rather than the one that was
                // applied.
                colorPresetName = colour?.name,
                colorCorrection = colour?.correction,
            )

            launch { monitor.latencyProbes.collect { recorder.offerProbe(it) } }

            var recordedUpTo = opening.capturedAtMillis
            monitor.snapshots.filterNotNull().collect { snapshot ->
                if (snapshot.capturedAtMillis <= recordedUpTo) return@collect
                recordedUpTo = snapshot.capturedAtMillis
                recorder.offer(snapshot, snapshot.capturedAtMillis)
            }
        }
    }

    // ---------------------------------------------------------------------------- a game stops

    /**
     * Stops sampling, closes the record, then puts the device back — in that order.
     *
     * The session is dated from [GameEvent.Stopped.atMillis], which for a game that left the
     * foreground is the moment it left rather than the moment the grace window expired. Using `now`
     * here would credit every session with the grace period.
     *
     * Restore runs unconditionally, even for a game whose profile was never applied. It is not undoing
     * *this* session — [ProfileApplier.restore] unwinds every recorded restore point, including ones a
     * previous session failed to unwind — so skipping it when nothing was applied would leave a device
     * pinned by an earlier game.
     */
    private suspend fun onStopped(event: GameEvent.Stopped) {
        // Cancelled *and joined* before the session is closed. The recorder batches samples under a
        // lock, and a sampler still running while `end` aggregates could append to a session that has
        // already been totalled. A cancel that lands before `begin` leaves no row, or an empty one that
        // `SessionRepository.repairUnfinished` deletes — the right outcome for a game that stopped
        // before its first sample.
        sampling?.cancelAndJoin()
        sampling = null

        // The game's overlays go before its settings do. A crosshair left on the launcher for the length
        // of a restore — which can be several seconds of failed shell calls — is the most visible way to
        // look broken, and the manual state the user had before the game is what comes back.
        overlays.clearProfile()

        monitor.watch(null)
        recorder.end(event.reason, event.atMillis)
        val restore = applier.restore()
        monitor.clearHistory()
        state.value = GamingState(lastRestore = restore)
    }

    private companion object {
        /**
         * How long to wait for the sampling loop's first reading before taking a one-off sample.
         *
         * Generous, because the first sample of a session is the most expensive one: the storage read,
         * the frame-timing capability probe and the first `/proc/stat` pair all happen inside it, and on
         * a device busy launching a game that is not instant. The fallback exists for the case where it
         * never arrives at all, not to shave a second off a normal start.
         */
        const val OPENING_TIMEOUT_MILLIS = 8_000L

        /**
         * How long [shutdown] may take before it is abandoned.
         *
         * The caller is a `runBlocking` in a service's `onDestroy`, so this is time the main thread spends
         * blocked. Two and a half seconds is enough for a sample flush and a session close on a busy
         * device, and short enough that a shell call to a Shizuku service that has already died does not
         * turn a stop into a freeze.
         */
        const val SHUTDOWN_BUDGET_MILLIS = 2_500L
    }
}
