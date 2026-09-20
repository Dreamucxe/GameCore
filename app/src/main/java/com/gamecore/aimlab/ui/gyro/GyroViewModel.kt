package com.gamecore.aimlab.ui.gyro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.DifficultyParameters
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.TrackingAccumulator
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.runtime.AimGyroReader
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/**
 * Runs one gyro session and keeps the screen's state while it happens.
 *
 * The shape is the section's idiom, and the important part of it is that **collection is what runs the
 * loop**: [state] folds the user's choices together with the active loop's frames and is shared with
 * `WhileSubscribed`, so when the screen stops collecting, the frames flow is cancelled, the loop's
 * `awaitClose` fires and the run is abandoned without being written (§19/§29).
 *
 * What is specific to this mode is the second live thing: the gyroscope. [AimGyroReader] registers its
 * sensor listener when its flow is collected and unregisters in `awaitClose`, so the sensor's lifetime is
 * exactly the lifetime of that collection — and that collection is deliberately placed *inside* [loopFrames]
 * rather than in `viewModelScope`. The difference is the whole point: a job launched into `viewModelScope`
 * outlives the screen, so a user who leaves mid-run would keep the gyroscope powered until the view model
 * was destroyed, which on a screen left in the back stack can be minutes (§20/§29). Collected from inside
 * the shared graph, the sensor is unregistered by the same cancellation that stops the loop's ticker: when
 * the screen stops collecting and the grace window expires, or when [active] is nulled, whichever is first.
 *
 * So there is no gyro job field and no `stopGyro()` to forget to call. The sensor is powered only while a
 * run exists *and* someone is watching it, never while the user reads the setup card, and never after.
 *
 * A device without a gyroscope is answered once, at construction, and the answer is carried in the state.
 * Nothing simulates a sample for it (§30) — the screen explains the limitation instead.
 *
 * Nothing in here invents a number. The live figures are the loop's frames plus the engine's own
 * [TrackingAccumulator] run over them; the result card is the summary the loop produced on stop; and
 * whether the run was kept is the repository's answer rather than an assumption.
 */
@HiltViewModel
class GyroViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val loops: Provider<AimTrainingLoop3D>,
    private val gyroReader: AimGyroReader,
) : ViewModel() {

    /**
     * The starting state, with the capability question already answered.
     *
     * It is also `stateIn`'s initial value, so the screen never renders a frame in which the availability
     * of the sensor is unknown — no Start button flashes up on a device that has no gyroscope.
     */
    private val initial = GyroState(gyroAvailable = gyroReader.isAvailable())

    /** What the user chose and where the run has got to. Everything except the frames and the profiles. */
    private val local = MutableStateFlow(initial)

    /**
     * The loop of the run in progress, or null between runs.
     *
     * Switching this is how the screen starts and stops collecting frames: a non-null value makes
     * [loopFrames] collect that loop (which is what makes it tick), and nulling it cancels the collection.
     */
    private val active = MutableStateFlow<AimTrainingLoop3D?>(null)

    /**
     * The live time-on-target measure for the run in progress.
     *
     * The engine's own accumulator, fed the frames the screen is handed, so "on target" means exactly what
     * it means inside the loop. Replaced per run; the final figure still comes from the loop's summary.
     */
    private var onTargetAccumulator: TrackingAccumulator? = null

    /** The elapsed time of the last frame folded into [onTargetAccumulator], so no frame is counted twice. */
    private var lastSampledMillis = 0L

    /**
     * Guards the end of a run so it happens exactly once.
     *
     * The loop emits a final finished frame and can replay it to a late subscriber, and the user can also
     * end a run early — without this, a session could be stopped twice or saved twice.
     */
    private var finishing = false

    /**
     * The active loop's frames, or a single null when there is no run.
     *
     * Sampling and finishing are handled here rather than in the [combine] below because this is the one
     * place a frame is seen exactly once, as it arrives.
     *
     * The gyroscope is collected in here too, as a child of the same `channelFlow` the frames are sent
     * through, because the sensor must live and die with the run rather than with the view model. Anything
     * that ends this inner flow — a new value on [active], the screen unsubscribing past the grace window,
     * the view model being cleared — cancels that child, which closes [AimGyroReader.stream] and unregisters
     * the listener. The child is also cancelled explicitly once the frames flow completes, so a run that
     * ends on its own releases the sensor at that moment instead of waiting for [finishRun] to null [active].
     *
     * Deltas are fed straight into the loop and are never emitted downstream: the sensor reports far faster
     * than the ticker, and turning every sample into a state emission would drive recomposition at the
     * sensor's rate. The loop absorbs them into plain fields and the ticker publishes the result (§21).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loopFrames: Flow<TrainingFrame?> = active
        .flatMapLatest { loop ->
            if (loop == null) {
                flowOf<TrainingFrame?>(null)
            } else {
                channelFlow<TrainingFrame?> {
                    val gyro = launch {
                        gyroReader.stream().collect { delta ->
                            // Gyro aim is never "aiming down sights" here: this screen has no ADS control,
                            // so claiming one would apply an ADS multiplier the user never asked for.
                            // Gyro deltas are integrated radians; onGyro runs them through the 3D look
                            // pipeline (gyro sensitivity) to turn the device rotation into camera yaw/pitch.
                            loop.onGyro(delta.dx, delta.dy)
                        }
                    }
                    loop.frames.collect { frame -> send(frame) }
                    gyro.cancel()
                }
            }
        }
        .onEach { frame -> if (frame != null) observe(frame) }

    val state: StateFlow<GyroState> = combine(
        local,
        loopFrames,
        repository.sensitivities,
        repository.layouts,
    ) { base, frame, profiles, layouts ->
        // Fold in the saved profiles and control layouts, re-resolving the chosen layout by id so an edit in
        // the control editor is reflected here.
        val withProfiles = base.copy(
            sensitivities = profiles,
            layouts = layouts,
            layout = base.layout?.let { chosen -> layouts.firstOrNull { it.id == chosen.id } },
        )
        // A null frame means "no run" — setup and result own the whole screen then, and the frame the state
        // already holds (none) is the correct one.
        if (frame == null) {
            withProfiles
        } else {
            withProfiles.copy(frame = frame, onTargetFraction = liveOnTargetFraction())
        }
    }
        // Fires when the screen stops collecting and the grace window expires — which is also when the loop
        // and the gyroscope are cancelled. A run cannot survive that, so the state must not claim it did.
        .onCompletion { abandonRun() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = initial,
        )

    // ------------------------------------------------------------------------------ setup

    fun onDifficultySelected(difficulty: Difficulty) {
        local.update { if (it.phase == GyroPhase.Running) it else it.copy(difficulty = difficulty) }
    }

    /**
     * Picks a sensitivity profile, or clears it when the selected one is tapped again.
     *
     * Clearing has to be possible: the profile is optional, "none" is a real choice that means the engine's
     * default gyro scale, and a first tap that could never be undone would take it away permanently.
     */
    fun onSensitivitySelected(profile: SensitivityProfile) {
        local.update {
            when {
                it.phase == GyroPhase.Running -> it
                it.sensitivity?.id == profile.id -> it.copy(sensitivity = null)
                else -> it.copy(sensitivity = profile)
            }
        }
    }

    fun onDurationChanged(seconds: Int) {
        val clamped = seconds.coerceIn(GyroState.DURATION_RANGE)
        local.update { if (it.phase == GyroPhase.Running) it else it.copy(durationSeconds = clamped) }
    }

    /** Picks the on-screen control layout (HUD) for the run, or clears it — null means no controls. */
    fun onLayoutSelected(layout: ControlLayout?) {
        local.update { if (it.phase == GyroPhase.Running) it else it.copy(layout = layout) }
    }

    // ------------------------------------------------------------------------------ the run

    /**
     * Starts a run with the current choices. Also what "retry" does, which is why it clears the last result.
     *
     * The loop is started before it is published to [active] — the ticker only advances a running loop, so
     * ordering it this way means the very first frame the screen sees is already a frame of the new run, and
     * the gyroscope, which begins with that same publication, can never deliver a delta to a loop that has
     * not been configured yet.
     *
     * A device with no gyroscope is refused here as well as in the UI. The screen offers no Start button in
     * that case, so this is belt and braces rather than a second code path: without the sensor the run
     * would be a minute of a crosshair that cannot move, which is not a session.
     */
    fun start() {
        val current = local.value
        if (current.phase == GyroPhase.Running || !current.gyroAvailable) return

        finishing = false
        // The same radius the loop will use, from the same single source, so the live "on target" rule and
        // the engine's are the same rule rather than two that happen to agree.
        onTargetAccumulator = TrackingAccumulator(DifficultyParameters.forLevel(current.difficulty).targetRadius)
        lastSampledMillis = 0L

        val loop = loops.get()
        loop.start(
            TrainingConfig(
                mode = TrainingMode.GYRO,
                difficulty = current.difficulty,
                sensitivity = current.sensitivity,
                durationSeconds = current.durationSeconds,
                layoutId = current.layout?.id,
            ),
        )
        local.value = current.copy(
            phase = GyroPhase.Running,
            loop = loop,
            frame = null,
            onTargetFraction = null,
            result = null,
            saving = false,
            recorded = false,
        )
        active.value = loop
    }

    /** Ends the run now, at the user's request. An early stop is still a real session if anything happened. */
    fun stopRun() = finishRun()

    /** Back to the setup card without running anything — the way out of a finished run that is not a retry. */
    fun reset() {
        finishing = false
        active.value = null
        onTargetAccumulator = null
        lastSampledMillis = 0L
        local.update {
            it.copy(
                phase = GyroPhase.Setup,
                loop = null,
                frame = null,
                onTargetFraction = null,
                result = null,
                saving = false,
                recorded = false,
            )
        }
    }

    // ------------------------------------------------------------------------------ frames

    /**
     * Folds one arriving frame into the live measure, then ends the run if that frame was the last.
     *
     * The interval is taken from the frames' own elapsed clock rather than counted in ticks, so a frame
     * dropped by the flow's buffer still has its time attributed instead of quietly shrinking the total.
     */
    private fun observe(frame: TrainingFrame) {
        val target = frame.targets.firstOrNull()
        val accumulator = onTargetAccumulator
        if (target != null && accumulator != null && frame.elapsedMillis > lastSampledMillis) {
            val seconds = (frame.elapsedMillis - lastSampledMillis) / 1_000f
            accumulator.add(target.center, frame.crosshair, seconds)
            lastSampledMillis = frame.elapsedMillis
        }
        if (frame.finished) finishRun()
    }

    /** The live fraction, or null until something has actually been sampled. */
    private fun liveOnTargetFraction(): Float? =
        onTargetAccumulator?.takeIf { it.sampleCount > 0L }?.timeOnTargetFraction()

    /**
     * Takes the summary off the loop, releases it, and persists the run.
     *
     * The order matters. The summary is read first, because a cancelled loop has nothing left to report, and
     * only then is [active] nulled — which is what tears down the engine's ticker *and* unregisters the
     * gyroscope, both being children of the collection [active] drives. A null summary means the loop judged
     * the run empty; that is not saved, and the screen says so rather than showing zeroes.
     */
    private fun finishRun() {
        if (finishing) return
        finishing = true

        val summary = active.value?.stop()
        active.value = null

        local.update {
            it.copy(
                phase = GyroPhase.Result,
                loop = null,
                frame = null,
                result = summary,
                saving = summary != null,
                recorded = false,
            )
        }
        if (summary == null) return

        viewModelScope.launch {
            val id = repository.saveSession(summary)
            // Only report back if the screen is still showing this run: a quick retry must not have its
            // fresh state overwritten by the previous run's write landing late.
            local.update { if (it.result === summary) it.copy(saving = false, recorded = id > 0L) else it }
        }
    }

    /**
     * Drops a run that was still going when the screen stopped collecting.
     *
     * Not a stop: nothing is read off the loop and nothing is written, because the user did not end this
     * session — they left it, and a session nobody was present for is not a session (§30). What this does
     * is make the state agree with what already happened underneath it. By the time this runs, the
     * collection that *was* the run has been cancelled: the ticker is gone and the gyroscope is
     * unregistered. Leaving [GyroPhase.Running] in place would leave the screen claiming a live run that
     * no longer exists, so a returning user would face a frozen arena with no way back to setup.
     *
     * [finishing] is cleared so the next run can end normally, and [onTargetAccumulator] is dropped so no
     * part of the abandoned run's measurement can leak into the next one.
     */
    private fun abandonRun() {
        finishing = false
        active.value = null
        onTargetAccumulator = null
        lastSampledMillis = 0L
        local.update {
            if (it.phase != GyroPhase.Running) {
                it
            } else {
                it.copy(
                    phase = GyroPhase.Setup,
                    loop = null,
                    frame = null,
                    onTargetFraction = null,
                )
            }
        }
    }

    private companion object {
        /**
         * How long [state] — and therefore the loop and the gyroscope — survives the screen going away.
         *
         * A second covers a rotation and nothing else. Past that the run is abandoned, which is the correct
         * outcome: a training session the user walked away from is not a session.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 1_000L
    }
}
