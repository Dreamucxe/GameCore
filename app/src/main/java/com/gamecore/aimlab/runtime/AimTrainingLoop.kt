package com.gamecore.aimlab.runtime

import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.TrainingLoop
import com.gamecore.aimlab.engine.Clock
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.DifficultyParameters
import com.gamecore.aimlab.engine.FloatRingBuffer
import com.gamecore.aimlab.engine.ReactionStats
import com.gamecore.aimlab.engine.RecoilEngine
import com.gamecore.aimlab.engine.Rng
import com.gamecore.aimlab.engine.Scoring
import com.gamecore.aimlab.engine.SensitivityMath
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Target
import com.gamecore.aimlab.engine.TrackingAccumulator
import com.gamecore.aimlab.engine.TrackingPath
import com.gamecore.aimlab.engine.TrackingPattern
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Vec2
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * The training loop: one run of one mode, driven by collecting [frames].
 *
 * Lifecycle is collection and nothing else. [frames] is a cold `callbackFlow`; its ticker coroutine is the
 * loop, and `awaitClose` marks the run stopped. A screen collects through `SharingStarted.WhileSubscribed`,
 * so leaving the screen cancels the collection, which cancels the ticker and runs `awaitClose` — no timer,
 * coroutine or state survives the screen (§19/§29). There is no Service and no shared mutable state: a new
 * loop is provided per screen via `Provider<AimTrainingLoop>`.
 *
 * Two rates, like the motion sampler. Input arrives at the finger's/gyro's rate and mutates plain fields
 * with no allocation; the ticker reads those fields [TICK_MILLIS] apart and emits one immutable
 * [TrainingFrame]. So a burst of taps or gyro samples never drives a burst of recompositions (§21), and the
 * per-frame history that scoring needs lives in a [FloatRingBuffer], never a growing list and never Room.
 *
 * Every number the run produces is measured from real input against the injected [Clock]; nothing is
 * fabricated (§30). `stop()` folds the accumulators into a [SessionSummary] and returns it only if the run
 * actually produced something ([SessionSummary.isValid]).
 *
 * Time and randomness come in through the primary constructor, so the whole loop can be driven
 * deterministically: a test hands it a fake clock and a fixed-seed [Rng] and steps a complete run without
 * sleeping. Production never builds it that way — Hilt uses the no-arg secondary constructor, which takes
 * the real [TrainingClockSource] values, so every `Provider<AimTrainingLoop>` injection site is unchanged.
 */
class AimTrainingLoop internal constructor(
    private val clock: Clock,
    /** Makes the run's RNG from its seed. Called once per [start], so one seed replays one run exactly. */
    private val rngFactory: (Long) -> Rng,
) : TrainingLoop {

    /** The production loop: the real monotonic clock, and a freshly seeded RNG per run. */
    @Inject constructor() : this(TrainingClockSource.clock(), TrainingClockSource::rng)

    // ---- run state, all guarded by being touched only from the ticker or from input on the UI thread ----

    @Volatile private var config: TrainingConfig? = null
    @Volatile private var rng: Rng = rngFactory(0L)
    @Volatile private var params: DifficultyParameters = DifficultyParameters.forLevel(Difficulty.NORMAL)
    @Volatile private var sensitivity = SensitivityMath(SensitivityProfile(name = "default"))

    @Volatile private var startNanos = 0L
    @Volatile private var pausedAtNanos = 0L
    @Volatile private var pausedTotalNanos = 0L
    @Volatile private var running = false
    @Volatile private var finished = false

    @Volatile private var crosshair = Vec2.CENTER

    // Shared counters.
    @Volatile private var hits = 0
    @Volatile private var shots = 0
    @Volatile private var targetsMissed = 0
    @Volatile private var score = 0

    // Live targets, id source, and last-spawn time for spawn-driven modes.
    private val targets = ArrayList<Target>(8)
    private var nextTargetId = 1L
    private var lastSpawnNanos = 0L

    // Reaction mode.
    private val reactionTimes = ArrayList<Long>(32)
    private var reactionArmedAtNanos = 0L
    private var reactionWaitingUntilNanos = 0L
    private var reactionTargetShownNanos = 0L

    // Flick/movement acquisition timing.
    private val acquireSamples = FloatRingBuffer(256)

    // Tracking / gyro.
    private var trackingPath: TrackingPath? = null
    private var trackingAcc: TrackingAccumulator? = null
    private val correctionBuffer = FloatRingBuffer(1024)

    // Recoil.
    private val recoilEngine = RecoilEngine(rng)
    @Volatile private var recoilOffset = Vec2(0f, 0f)
    private var recoilShotsFired = 0
    private val recoilResidual = FloatRingBuffer(512)

    // Movement consistency (movement mode): how steadily the player kept moving.
    private val movementBuffer = FloatRingBuffer(512)
    @Volatile private var moving = false

    // Weapon handling: the fire-rate gate, the magazine, and the reload emptying it triggers. All three
    // are zero/idle when the run has no weapon configured, which is the case for every mode that does not
    // take one — the gate below is skipped entirely rather than fed defaults.
    @Volatile private var ammo = 0
    @Volatile private var reloading = false
    private var nextShotAllowedNanos = 0L
    private var reloadingUntilNanos = 0L

    /**
     * The list handed out in the last frame, reused while the live targets are unchanged.
     *
     * [snapshot] used to copy `targets` into a fresh `ArrayList` sixty times a second whatever was in it.
     * Most of those copies are of a list that did not change between ticks — an empty arena during a
     * reaction-mode wait, the same one or two discs sitting still through a flick run — and each one is
     * both an allocation and a new identity, which is what makes Compose recompose everything reading the
     * frame. Comparing first costs an element-wise `equals` over at most a handful of targets and skips
     * the copy when it would have produced an equal list, so an unchanged arena hands back the very same
     * instance. Tracking and gyro move their target every tick and so still copy every tick, which is
     * correct: there the list genuinely is different each time.
     */
    private var lastTargets: List<Target> = emptyList()

    // ---------------------------------------------------------------------------- frames

    override val frames: Flow<TrainingFrame> = callbackFlow {
        // The ticker IS the loop. It advances state and emits a frame every TICK_MILLIS until cancelled.
        val job = launch {
            // Whether the current idle stretch has already been reported. A paused — or not-yet-started —
            // loop advances nothing: `elapsedMillis()` freezes at `pausedAtNanos`, no target moves, no
            // counter changes, so every snapshot taken during it is equal to the one before it. The state
            // is still emitted once, because the screen has to see that the run is paused and because a
            // subscriber that attaches before `start()` would otherwise wait on a flow that never emits.
            // After that the ticker idles instead of pushing sixty identical frames a second through a
            // recomposition each.
            var idleReported = false
            while (isActive) {
                if (running && !finished) {
                    tick()
                    idleReported = false
                } else if (!finished) {
                    if (idleReported) {
                        delay(TICK_MILLIS)
                        continue
                    }
                    idleReported = true
                }
                trySend(snapshot())
                if (finished) break
                delay(TICK_MILLIS)
            }
            // Emit a final finished frame so the screen can transition to results.
            trySend(snapshot())
        }
        awaitClose {
            job.cancel()
            // Leaving mid-run without an explicit stop() means the run is abandoned; nothing is persisted.
            running = false
            // A loop that has been torn down stays torn down. Without this the instance is left
            // `running == false, finished == false`, and a screen that resubscribes to a loop it kept a
            // reference to starts a *second* ticker in that state: `tick()` is skipped, so nothing can ever
            // set `finished`, so the `break` above is unreachable and the ticker spins at 60 Hz forever
            // emitting a frozen snapshot. Setting it here makes the next collection emit one final frame
            // and complete, so no caller can resurrect a dead loop however it mismanages its reference.
            finished = true
        }
    }

    // ------------------------------------------------------------------------------ control

    override fun start(config: TrainingConfig) {
        this.config = config
        val seed = if (config.seed != 0L) config.seed else clock.elapsedNanos()
        rng = rngFactory(seed)
        params = DifficultyParameters.forLevel(config.difficulty)
        sensitivity = SensitivityMath(config.sensitivity ?: SensitivityProfile(name = "default"))

        // Reset every accumulator so a restart is a clean run.
        targets.clear()
        reactionTimes.clear()
        acquireSamples.clear()
        correctionBuffer.clear()
        recoilResidual.clear()
        movementBuffer.clear()
        hits = 0; shots = 0; targetsMissed = 0; score = 0
        nextTargetId = 1L
        recoilShotsFired = 0
        recoilOffset = Vec2(0f, 0f)
        crosshair = Vec2.CENTER

        startNanos = clock.elapsedNanos()
        lastSpawnNanos = startNanos
        pausedTotalNanos = 0L
        pausedAtNanos = 0L

        // Load the weapon. A mode without one (every mode but recoil) leaves the magazine empty and the
        // gate open, which the recoil path is the only reader of anyway; the fields simply do not apply.
        val weapon = config.weapon
        ammo = weapon?.magazineSize ?: 0
        reloading = false
        nextShotAllowedNanos = startNanos
        reloadingUntilNanos = 0L
        lastTargets = emptyList()

        when (config.mode) {
            TrainingMode.TRACKING, TrainingMode.GYRO -> {
                val pattern = config.pattern ?: patternForDifficulty(config.difficulty)
                trackingPath = TrackingPath(pattern, params.targetRadius, params.targetSpeed, rng)
                trackingAcc = TrackingAccumulator(params.targetRadius)
                spawnTrackingTarget()
            }
            TrainingMode.REACTION -> armReaction()
            else -> Unit
        }
        running = true
        finished = false
    }

    override fun onShot(x: Float, y: Float) {
        if (!running || finished) return
        val point = Vec2(x, y).clampToArena()
        crosshair = point
        val mode = config?.mode ?: return
        when (mode) {
            TrainingMode.FLICK, TrainingMode.MOVEMENT -> flickShot(point)
            TrainingMode.REACTION -> reactionShot(point)
            TrainingMode.RECOIL -> recoilShot()
            TrainingMode.TRACKING, TrainingMode.GYRO -> Unit // hold-to-track, no discrete shot
            TrainingMode.FREE_PRACTICE -> {
                shots++
                if (targets.any { it.contains(point) }) hits++
            }
        }
    }

    override fun onAimDelta(dx: Float, dy: Float, aiming: Boolean) {
        if (!running || finished) return
        val moved = sensitivity.apply(dx, dy, aiming, gyro = config?.mode == TrainingMode.GYRO)
        crosshair = Vec2(crosshair.x + moved.x, crosshair.y + moved.y).clampToArena()
        if (config?.mode == TrainingMode.RECOIL) {
            // The player's counter-aim reduces the standing recoil offset.
            recoilOffset = Vec2(recoilOffset.x + moved.x, recoilOffset.y + moved.y)
        }
    }

    override fun pause() {
        if (!running || finished) return
        running = false
        pausedAtNanos = clock.elapsedNanos()
    }

    override fun resume() {
        if (running || finished) return
        if (pausedAtNanos > 0L) {
            pausedTotalNanos += clock.elapsedNanos() - pausedAtNanos
            pausedAtNanos = 0L
        }
        running = true
    }

    override fun stop(): SessionSummary? {
        running = false
        finished = true
        val cfg = config ?: return null
        val now = clock.nowMillis()
        val elapsedMillis = elapsedMillis()
        val reaction = ReactionStats.from(reactionTimes.toList())
        val timeOnTarget = trackingAcc?.timeOnTargetFraction() ?: 0f
        val trackingError = trackingAcc?.averageError() ?: 0f
        val gyroStability = if (cfg.mode == TrainingMode.GYRO) {
            // Lower correction jitter = steadier aim; report as a 0..1 stability where 1 is perfectly smooth.
            1f / (1f + correctionBuffer.standardDeviation() * 100f)
        } else {
            0f
        }
        val recoilComp = if (cfg.mode == TrainingMode.RECOIL && recoilResidual.size > 0) {
            (1f - recoilResidual.mean().coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val avgAcquire = acquireSamples.mean()

        val finalScore = when (cfg.mode) {
            TrainingMode.FLICK -> Scoring.flick(hits, shots, avgAcquire)
            TrainingMode.MOVEMENT -> Scoring.movement(hits, shots, avgAcquire, movementConsistency())
            TrainingMode.REACTION -> Scoring.reaction(reaction, hits, shots)
            TrainingMode.TRACKING -> Scoring.tracking(timeOnTarget, trackingError)
            TrainingMode.RECOIL -> Scoring.recoil(recoilComp)
            TrainingMode.GYRO -> Scoring.gyro(timeOnTarget, trackingError, correctionBuffer.standardDeviation())
            TrainingMode.FREE_PRACTICE -> 0
        }

        val summary = SessionSummary(
            mode = cfg.mode,
            difficulty = cfg.difficulty,
            startedAtMillis = now - elapsedMillis,
            endedAtMillis = now,
            weaponName = cfg.weapon?.name,
            sensitivityName = cfg.sensitivity?.name,
            score = finalScore,
            hits = hits,
            shots = shots,
            targetsMissed = targetsMissed,
            reactionStats = reaction,
            averageAcquireMillis = avgAcquire,
            trackingErrorAverage = trackingError,
            timeOnTargetFraction = timeOnTarget,
            recoilCompensation = recoilComp,
            gyroStability = gyroStability,
            // This is the legacy flat-arena loop; anything it still produces is stamped 2D so it can
            // never be ranked against a 3D result (§6). Production runs the 3D loop.
            scoringVersion = SessionSummary.SCORING_VERSION_2D,
        )
        return if (summary.isValid) summary else null
    }

    // ------------------------------------------------------------------------------- tick

    /** One step of the run: expire/spawn targets, advance moving targets, mark elapsed-end, accumulate. */
    private fun tick() {
        val cfg = config ?: return
        val nowNanos = clock.elapsedNanos()

        // Time-limited modes end when the configured duration elapses. Free practice runs until stopped.
        if (cfg.mode != TrainingMode.FREE_PRACTICE && elapsedMillis() >= cfg.durationSeconds * 1_000L) {
            finished = true
            running = false
            return
        }

        when (cfg.mode) {
            TrainingMode.FLICK, TrainingMode.MOVEMENT -> tickFlick(nowNanos)
            TrainingMode.TRACKING, TrainingMode.GYRO -> tickTracking()
            TrainingMode.REACTION -> tickReaction(nowNanos)
            TrainingMode.RECOIL -> tickRecoil()
            TrainingMode.FREE_PRACTICE -> tickFlick(nowNanos) // sandbox spawns like flick
        }
        if (cfg.mode == TrainingMode.MOVEMENT) movementBuffer.add(if (moving) 1f else 0f)
    }

    private fun tickFlick(nowNanos: Long) {
        // Expire targets past their lifetime as misses.
        val lifetimeNanos = params.targetLifetimeMillis * 1_000_000L
        val iterator = targets.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            if (nowNanos - t.spawnedAtNanos > lifetimeNanos) {
                iterator.remove()
                targetsMissed++
            }
        }
        // Spawn up to the simultaneous cap at the configured interval.
        val intervalNanos = params.spawnIntervalMillis * 1_000_000L
        if (targets.size < params.simultaneousTargets && nowNanos - lastSpawnNanos >= intervalNanos) {
            spawnFlickTarget(nowNanos)
            lastSpawnNanos = nowNanos
        }
    }

    private fun tickTracking() {
        val path = trackingPath ?: return
        val seconds = elapsedMillis() / 1_000f
        val center = path.positionAt(seconds)
        targets.clear()
        targets.add(Target(id = 1L, center = center, radius = params.targetRadius, spawnedAtNanos = startNanos))
        val frameSeconds = TICK_MILLIS / 1_000f
        trackingAcc?.add(center, crosshair, frameSeconds)
        // Correction magnitude this frame = how far the crosshair is from the target; its variance is jitter.
        correctionBuffer.add(center.distanceTo(crosshair))
    }

    private fun tickReaction(nowNanos: Long) {
        // While waiting, no target is shown; when the randomized wait elapses, show one and start the clock.
        if (targets.isEmpty() && nowNanos >= reactionWaitingUntilNanos && reactionWaitingUntilNanos != 0L) {
            val center = Vec2(
                rng.nextFloat(0.15f, 0.85f),
                rng.nextFloat(0.2f, 0.8f),
            )
            targets.add(Target(nextTargetId++, center, params.targetRadius, nowNanos))
            reactionTargetShownNanos = nowNanos
            reactionWaitingUntilNanos = 0L
        }
    }

    private fun tickRecoil() {
        // The weapon adds a kick each shot (applied in recoilShot); recovery pulls the offset back when idle.
        val spec = config?.weapon?.recoil ?: return
        // A reload that started in recoilShot completes here, on the tick its duration elapses, refilling the
        // magazine. Doing it on the ticker rather than at the next shot means the "reloading" state the HUD
        // reads is honest about when the weapon is usable again, not merely cleared the instant it is next used.
        if (reloading && clock.elapsedNanos() >= reloadingUntilNanos) {
            ammo = config?.weapon?.magazineSize ?: ammo
            reloading = false
        }
        val frameSeconds = TICK_MILLIS / 1_000f
        recoilOffset = recoilEngine.recover(recoilOffset, frameSeconds, spec.recoveryPerSecond)
        // Residual = how far off centre the aim currently sits; lower mean = better compensation.
        recoilResidual.add(recoilOffset.length)
    }

    // ------------------------------------------------------------------------------ per-mode shots

    private fun flickShot(point: Vec2) {
        shots++
        // In movement mode, firing while strafing scatters the shot: the weapon's movementPenalty scales a
        // random offset drawn from the injected RNG (so a seed still replays exactly), which is what §7's
        // "movement penalty" trains against — you learn to stop, or to accept the spread. Standing still, or
        // any weapon with a zero penalty, leaves the shot exactly where it was tapped. Flick mode never
        // applies it, so the flick tests that assert an exact hit are untouched.
        val tested = if (config?.mode == TrainingMode.MOVEMENT && moving) {
            val penalty = config?.weapon?.movementPenalty ?: 0f
            if (penalty > 0f) {
                Vec2(
                    point.x + rng.nextGaussian() * penalty * MOVEMENT_SCATTER,
                    point.y + rng.nextGaussian() * penalty * MOVEMENT_SCATTER,
                ).clampToArena()
            } else {
                point
            }
        } else {
            point
        }
        val hitIndex = targets.indexOfFirst { it.contains(tested) }
        if (hitIndex >= 0) {
            val target = targets.removeAt(hitIndex)
            hits++
            // Acquisition time = how long the target was alive before it was hit.
            acquireSamples.add((clock.elapsedNanos() - target.spawnedAtNanos) / 1_000_000f)
        }
    }

    private fun reactionShot(point: Vec2) {
        val target = targets.firstOrNull()
        if (target == null) {
            // Tapped during the wait — a false start. Counts as a shot, no reaction recorded.
            shots++
            return
        }
        shots++
        if (target.contains(point)) {
            hits++
            reactionTimes.add((clock.elapsedNanos() - reactionTargetShownNanos) / 1_000_000L)
        }
        targets.clear()
        armReaction()
    }

    private fun recoilShot() {
        val weapon = config?.weapon ?: return
        val spec = weapon.recoil
        val nowNanos = clock.elapsedNanos()

        // The weapon's own three limits, each a reason a tap produces no shot:
        //  - reloading: the magazine is being refilled and cannot fire until tickRecoil finishes it.
        //  - fire rate: taps faster than the weapon's shot interval are dropped, so a machine-gun finger
        //    cannot outrun the cadence the weapon defines. The gate is armed off the last shot that fired.
        //  - empty magazine: firing the last round starts a reload rather than continuing.
        // A dropped tap is not counted as a shot: the trigger was pulled, but the weapon did not discharge,
        // and counting it would score the player down for the weapon's cadence rather than their aim.
        if (reloading || nowNanos < nextShotAllowedNanos || ammo <= 0) return

        nextShotAllowedNanos = nowNanos + weapon.shotIntervalMillis * 1_000_000L
        ammo--
        if (ammo <= 0) {
            reloading = true
            reloadingUntilNanos = nowNanos + weapon.reloadMillis * 1_000_000L
        }

        shots++
        hits++ // recoil mode scores on compensation, not hit/miss; every shot lands, the pattern is the test
        recoilShotsFired++
        val kick = recoilEngine.pattern(spec, recoilShotsFired).lastOrNull() ?: Vec2(0f, 0f)
        // Add just this shot's incremental kick to the standing offset.
        val previous = if (recoilShotsFired > 1) {
            recoilEngine.pattern(spec, recoilShotsFired - 1).lastOrNull() ?: Vec2(0f, 0f)
        } else {
            Vec2(0f, 0f)
        }
        // The weapon's spread is a per-shot random dispersion the player cannot pre-learn, drawn from the
        // injected RNG so a seed still replays a run exactly. Zero spread reduces to the fixed pattern.
        val dispersion = if (weapon.spread > 0f) {
            Vec2(rng.nextGaussian() * weapon.spread, rng.nextGaussian() * weapon.spread)
        } else {
            Vec2(0f, 0f)
        }
        recoilOffset = Vec2(
            recoilOffset.x + (kick.x - previous.x) + dispersion.x,
            recoilOffset.y + (kick.y - previous.y) + dispersion.y,
        )
    }

    // ------------------------------------------------------------------------------ spawning

    private fun spawnFlickTarget(nowNanos: Long) {
        val margin = params.targetRadius
        val center = Vec2(
            rng.nextFloat(margin, 1f - margin),
            rng.nextFloat(margin, 1f - margin),
        )
        targets.add(Target(nextTargetId++, center, params.targetRadius, nowNanos))
    }

    private fun spawnTrackingTarget() {
        targets.clear()
        targets.add(Target(1L, Vec2.CENTER, params.targetRadius, startNanos))
    }

    private fun armReaction() {
        // A randomized wait before the next target, so anticipation cannot be timed.
        reactionArmedAtNanos = clock.elapsedNanos()
        val waitMillis = rng.nextFloat(REACTION_MIN_WAIT_MS.toFloat(), REACTION_MAX_WAIT_MS.toFloat()).toLong()
        reactionWaitingUntilNanos = reactionArmedAtNanos + waitMillis * 1_000_000L
    }

    // ------------------------------------------------------------------------------ helpers

    /** Movement mode marks whether the player is strafing; the UI toggles this via the joystick control. */
    fun setMoving(value: Boolean) {
        moving = value
    }

    private fun movementConsistency(): Float =
        if (movementBuffer.size == 0) 0f else movementBuffer.mean()

    private fun elapsedMillis(): Long {
        if (startNanos == 0L) return 0L
        val nowNanos = if (running) clock.elapsedNanos() else pausedAtNanos.takeIf { it > 0L } ?: clock.elapsedNanos()
        return (nowNanos - startNanos - pausedTotalNanos).coerceAtLeast(0L) / 1_000_000L
    }

    private fun snapshot(): TrainingFrame = TrainingFrame(
        elapsedMillis = elapsedMillis(),
        running = running,
        finished = finished,
        targets = targetsSnapshot(),
        crosshair = crosshair,
        recoilOffset = recoilOffset,
        hits = hits,
        shots = shots,
        score = liveScore(),
    )

    /**
     * The immutable target list for a frame, reusing [lastTargets] whenever the live set is unchanged.
     *
     * The mutable `targets` cannot leak into an emitted frame — the ticker keeps mutating it — so a copy
     * is unavoidable *when it differs*. The win is skipping the copy when it does not: an empty arena
     * hands back the shared `emptyList()`, and a still one hands back the exact instance from last frame.
     * `List.equals` is element-wise over the handful of targets a mode ever shows, far cheaper than the
     * allocation and the recomposition a new identity would cost every 16 ms. Tracking and gyro move
     * their target each tick, so there the lists differ and a fresh copy is made — which is correct.
     */
    private fun targetsSnapshot(): List<Target> {
        if (targets.isEmpty()) {
            lastTargets = emptyList()
            return lastTargets
        }
        if (targets != lastTargets) {
            lastTargets = ArrayList(targets)
        }
        return lastTargets
    }

    /**
     * A cheap running score for the live readout, computed from the counters available mid-run.
     *
     * The authoritative score is [stop]'s, which has the full accumulators; this is only what the HUD shows
     * while playing. Flick/movement/free-practice score on hits; reaction and the tracking family have no
     * meaningful partial score before the run ends, so they read 0 live.
     */
    private fun liveScore(): Int = when (config?.mode) {
        TrainingMode.FLICK, TrainingMode.MOVEMENT, TrainingMode.FREE_PRACTICE ->
            Scoring.flick(hits, shots, acquireSamples.mean())
        else -> 0
    }

    private companion object {
        /** ~60 fps. Frames are emitted at this cadence regardless of input rate. */
        const val TICK_MILLIS = 16L

        const val REACTION_MIN_WAIT_MS = 700L
        const val REACTION_MAX_WAIT_MS = 2_500L

        /**
         * How far, in arena units, a full movement penalty scatters a shot fired while strafing.
         *
         * A weapon's `movementPenalty` is 0..1; this turns it into a distance. At 0.02 a full-penalty
         * weapon fired on the move lands roughly a target-radius off on average, enough that stopping to
         * shoot is the better play without making a moving shot hopeless — which is the skill the mode
         * trains. The draw is Gaussian, so most shots scatter less and the occasional one more.
         */
        const val MOVEMENT_SCATTER = 0.02f

        fun patternForDifficulty(difficulty: Difficulty): TrackingPattern = when (difficulty) {
            Difficulty.EASY -> TrackingPattern.HORIZONTAL
            Difficulty.NORMAL, Difficulty.CUSTOM -> TrackingPattern.CIRCULAR
            Difficulty.HARD -> TrackingPattern.ZIGZAG
            Difficulty.EXTREME -> TrackingPattern.RANDOM
        }
    }
}
