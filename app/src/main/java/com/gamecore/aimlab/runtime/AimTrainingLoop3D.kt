package com.gamecore.aimlab.runtime

import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.TrainingLoop
import com.gamecore.aimlab.engine.Clock
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.FloatRingBuffer
import com.gamecore.aimlab.engine.ReactionStats
import com.gamecore.aimlab.engine.FireControl
import com.gamecore.aimlab.engine.RecoilEngine
import com.gamecore.aimlab.engine.Rng
import com.gamecore.aimlab.engine.Scoring
import com.gamecore.aimlab.engine.SensitivityMath
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Vec2
import com.gamecore.aimlab.engine3d.Camera3D
import com.gamecore.aimlab.engine3d.Difficulty3DParameters
import com.gamecore.aimlab.engine3d.GyroRemap
import com.gamecore.aimlab.engine3d.LookConversion
import com.gamecore.aimlab.engine3d.Pattern3D
import com.gamecore.aimlab.engine3d.RecoilCamera3D
import com.gamecore.aimlab.engine3d.Room
import com.gamecore.aimlab.engine3d.Target3D
import com.gamecore.aimlab.engine3d.TargetKind
import com.gamecore.aimlab.engine3d.TargetPath3D
import com.gamecore.aimlab.engine3d.TrackingAccumulator3D
import com.gamecore.aimlab.engine3d.Vec3
import com.gamecore.aimlab.engine3d.aimErrorDegrees
import com.gamecore.aimlab.engine3d.applySpreadCone
import com.gamecore.aimlab.render.RenderState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * The first-person 3D training loop (§3): one run of one mode, driven by collecting [frames], producing
 * both the HUD's scalar [TrainingFrame] and the renderer's [RenderState].
 *
 * It is the 3D sibling of [AimTrainingLoop] and keeps every one of that class's lifecycle guarantees —
 * collection is the loop, `awaitClose` abandons the run, no service, no state survives the screen (§5,
 * §8.item-5). What changes is the world it runs in: the camera has a yaw/pitch/position, targets are
 * spheres at world positions, a shot is a ray, and every aim metric is an **angle** in degrees so a
 * session means the same on any screen.
 *
 * Input is real. Touch drags and gyro deltas both go through [LookConversion] — the existing sensitivity
 * pipeline — to turn into camera rotation; `onShot` fires the crosshair ray (perturbed by the weapon's
 * spread cone) against the live spheres. Nothing is fabricated (§30): every number folds from measured
 * input against the injected [Clock], and randomness comes from the injected seedable [Rng].
 *
 * The renderer never touches this loop's mutable state; the loop publishes an immutable [RenderState]
 * into [renderState] (an [AtomicReference]) each tick, and the GL thread reads the latest (§5).
 */
class AimTrainingLoop3D internal constructor(
    private val clock: Clock,
    private val rngFactory: (Long) -> Rng,
    private val room: Room = Room(),
) : TrainingLoop {

    @Inject constructor() : this(TrainingClockSource.clock(), TrainingClockSource::rng)

    // ---- run state, touched only from the ticker or from input on the UI thread ----

    @Volatile private var config: TrainingConfig? = null
    @Volatile private var rng: Rng = rngFactory(0L)
    @Volatile private var params = Difficulty3DParameters.forLevel(Difficulty.NORMAL)
    private var sensitivity = SensitivityMath(SensitivityProfile(name = "default"))
    private var look = LookConversion(sensitivity)

    private val camera = Camera3D()

    @Volatile private var startNanos = 0L
    @Volatile private var pausedAtNanos = 0L
    @Volatile private var pausedTotalNanos = 0L
    @Volatile private var running = false
    @Volatile private var finished = false
    @Volatile private var aiming = false

    // Landscape support: the display rotation gyro deltas are remapped for, and the horizontal FOV the
    // camera projects with (§2/§3). Both are set by the screen at start and on every configuration change.
    @Volatile private var displayRotation = GyroRemap.ROTATION_0
    @Volatile private var horizontalFov = Camera3D.DEFAULT_FOV

    // Shared counters.
    @Volatile private var hits = 0
    @Volatile private var shots = 0
    @Volatile private var targetsMissed = 0

    // Live targets and id source.
    private val targets = ArrayList<Target3D>(8)
    private var nextTargetId = 1L
    private var lastSpawnNanos = 0L

    // Reaction mode.
    private val reactionTimes = ArrayList<Long>(32)
    private var reactionWaitingUntilNanos = 0L
    private var reactionShownNanos = 0L

    // Flick acquisition timing (ms), angle metrics.
    private val acquireSamples = FloatRingBuffer(256)

    // Tracking / gyro.
    private var trackingPath: TargetPath3D? = null
    private var trackingAcc: TrackingAccumulator3D? = null

    // Recoil.
    private val recoilEngine = RecoilEngine(rng)
    private val recoilCamera = RecoilCamera3D(recoilEngine)
    // The fire-control gate turns a trigger hold into single/burst/auto shots at the weapon's rate, and
    // owns the magazine and reload. Recoil mode drives it every tick; the discrete tap modes still fire
    // one round per tap directly, which is what flick/reaction want.
    private val fireControl = FireControl()
    @Volatile private var ammo = 0
    @Volatile private var reloading = false
    private var nextShotAllowedNanos = 0L
    private var reloadingUntilNanos = 0L
    @Volatile private var viewmodelRecoil = 0f

    // Movement.
    @Volatile private var moving = false
    private val movementBuffer = FloatRingBuffer(512)
    @Volatile private var moveX = 0f
    @Volatile private var moveZ = 0f

    // Feedback that the renderer consumes once per frame.
    private val pendingBursts = ArrayList<Vec3>(8)
    private val pendingDecals = ArrayList<Vec3>(8)
    @Volatile private var muzzleFlashUntilNanos = 0L
    @Volatile private var hitMarkerUntilNanos = 0L
    @Volatile private var swayPhase = 0f

    /** The latest immutable render snapshot for the GL thread (§5). */
    val renderState = AtomicReference(RenderState.INITIAL)

    // ---------------------------------------------------------------------------- frames

    override val frames: Flow<TrainingFrame> = callbackFlow {
        val job = launch {
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
                publishRender()
                trySend(snapshot())
                if (finished) break
                delay(TICK_MILLIS)
            }
            publishRender()
            trySend(snapshot())
        }
        awaitClose {
            job.cancel()
            running = false
            finished = true
        }
    }

    // ------------------------------------------------------------------------------ control

    override fun start(config: TrainingConfig) {
        this.config = config
        val seed = if (config.seed != 0L) config.seed else clock.elapsedNanos()
        rng = rngFactory(seed)
        params = Difficulty3DParameters.forLevel(config.difficulty)
        sensitivity = SensitivityMath(config.sensitivity ?: SensitivityProfile(name = "default"))
        look = LookConversion(sensitivity)

        camera.reset()
        targets.clear()
        reactionTimes.clear()
        acquireSamples.clear()
        movementBuffer.clear()
        pendingBursts.clear()
        pendingDecals.clear()
        recoilCamera.reset()
        hits = 0; shots = 0; targetsMissed = 0
        nextTargetId = 1L
        aiming = false
        moving = false; moveX = 0f; moveZ = 0f
        viewmodelRecoil = 0f
        muzzleFlashUntilNanos = 0L
        hitMarkerUntilNanos = 0L

        val weapon = config.weapon
        ammo = weapon?.magazineSize ?: 0
        reloading = false

        startNanos = clock.elapsedNanos()
        lastSpawnNanos = startNanos
        nextShotAllowedNanos = startNanos
        reloadingUntilNanos = 0L
        // Arm the fire-control gate for recoil mode's fire-mode cadence. A mode without a weapon leaves it
        // idle; recoil configures it so a burst weapon actually bursts and an auto weapon holds fire.
        if (weapon != null) fireControl.configure(weapon, startNanos)
        pausedTotalNanos = 0L
        pausedAtNanos = 0L

        when (config.mode) {
            TrainingMode.TRACKING, TrainingMode.GYRO -> {
                trackingPath = TargetPath3D(
                    pattern = patternFor(config),
                    depth = (params.minDistance + params.maxDistance) / 2f,
                    halfSpan = trackingHalfSpan(),
                    eyeHeight = Camera3D.DEFAULT_EYE_HEIGHT,
                    speed = params.trackingSpeed,
                    rng = rng,
                )
                trackingAcc = TrackingAccumulator3D(params.onTargetAngleDegrees)
                spawnTrackingTarget()
            }
            TrainingMode.REACTION -> armReaction()
            else -> Unit
        }
        running = true
        finished = false
        publishRender()
    }

    override fun onShot(x: Float, y: Float) {
        // In 3D the crosshair is fixed at screen centre; x/y are ignored — a shot is the camera ray (§3).
        if (!running || finished) return
        when (config?.mode) {
            TrainingMode.FLICK, TrainingMode.MOVEMENT, TrainingMode.FREE_PRACTICE -> rayShot()
            TrainingMode.REACTION -> reactionShot()
            TrainingMode.RECOIL -> recoilShot()
            TrainingMode.TRACKING, TrainingMode.GYRO -> Unit // hold-to-track, no discrete shot
            null -> Unit
        }
    }

    override fun onAimDelta(dx: Float, dy: Float, aiming: Boolean) {
        if (!running || finished) return
        this.aiming = aiming
        // dx/dy are pixel deltas from the touch surface; the surface width is folded in by the screen
        // before calling, so here they are already the look-fraction the pipeline expects, scaled to
        // degrees by LookConversion. The screen passes raw pixel deltas via onLookPixels instead when it
        // has the surface width; onAimDelta remains for the TrainingLoop contract with unit-fraction input.
        val (yaw, pitch) = look.fromTouch(dx, dy, surfaceWidthPx = 1, aiming = aiming)
        val appliedPitch = camera.applyLook(yaw, pitch)
        if (config?.mode == TrainingMode.RECOIL) recoilCamera.recordPlayerAim(yaw, appliedPitch)
    }

    /** Touch look with the real surface width, the path the 3D screen uses (§3, resolution-independent). */
    fun onLookPixels(dxPx: Float, dyPx: Float, surfaceWidthPx: Int, aiming: Boolean) {
        if (!running || finished) return
        this.aiming = aiming
        val (yaw, pitch) = look.fromTouch(dxPx, dyPx, surfaceWidthPx, aiming)
        val appliedPitch = camera.applyLook(yaw, pitch)
        if (config?.mode == TrainingMode.RECOIL) recoilCamera.recordPlayerAim(yaw, appliedPitch)
    }

    /**
     * Gyro look, integrated radians from the sensor reader, through the same pipeline (§3).
     *
     * The raw delta is first remapped to the current display rotation via [GyroRemap], so a physical turn
     * to the player's right turns the view right in every orientation, including reverse landscape. The
     * rotation is set at session start and on every configuration change by [setDisplayRotation].
     */
    fun onGyro(yawRadians: Float, pitchRadians: Float) {
        if (!running || finished) return
        val (rx, ry) = GyroRemap.remap(yawRadians, pitchRadians, displayRotation)
        val (yaw, pitch) = look.fromGyro(rx, ry, aiming)
        val appliedPitch = camera.applyLook(yaw, pitch)
        if (config?.mode == TrainingMode.RECOIL) recoilCamera.recordPlayerAim(yaw, appliedPitch)
    }

    /** The display rotation the gyro deltas are remapped for (§3); read at start and on config change. */
    fun setDisplayRotation(rotation: Int) { displayRotation = rotation }

    /** The horizontal FOV the camera projects with (§2). Set from settings; vertical is derived per aspect. */
    fun setHorizontalFov(degrees: Float) { horizontalFov = degrees.coerceIn(60f, 120f) }

    /** ADS toggle: narrows FOV and slides the viewmodel over the run's ADS time (§3). */
    fun setAiming(value: Boolean) { aiming = value }

    /** Movement joystick, in normalised [-1,1] per axis; applied on the tick against the room bounds. */
    fun setMovement(x: Float, z: Float) {
        moveX = x.coerceIn(-1f, 1f)
        moveZ = z.coerceIn(-1f, 1f)
        moving = (moveX != 0f || moveZ != 0f)
    }

    /**
     * Compatibility with the movement screen's strafe model: it reports only "moving or not" via its two
     * strafe pads. Mapped to a forward walk so a held strafe actually moves the player through the 3D room
     * and the movement penalty has something to bite on. Standing still stops the player.
     */
    fun setMoving(value: Boolean) {
        moving = value
        moveZ = if (value) 1f else 0f
        moveX = 0f
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
        val elapsed = elapsedMillis()
        val reaction = ReactionStats.from(reactionTimes.toList())
        val timeOnTarget = trackingAcc?.timeOnTargetFraction() ?: 0f
        val trackingError = trackingAcc?.averageErrorDegrees() ?: 0f
        val gyroStability = if (cfg.mode == TrainingMode.GYRO) {
            1f / (1f + (trackingAcc?.errorStdDevDegrees() ?: 0f) * 0.1f)
        } else 0f
        val recoilComp = if (cfg.mode == TrainingMode.RECOIL) recoilCamera.compensationScore() else 0f
        val avgAcquire = acquireSamples.mean()

        val finalScore = when (cfg.mode) {
            TrainingMode.FLICK -> Scoring.flick(hits, shots, avgAcquire)
            TrainingMode.MOVEMENT -> Scoring.movement(hits, shots, avgAcquire, movementConsistency())
            TrainingMode.REACTION -> Scoring.reaction(reaction, hits, shots)
            TrainingMode.TRACKING -> Scoring.tracking(timeOnTarget, trackingError)
            TrainingMode.RECOIL -> Scoring.recoil(recoilComp)
            TrainingMode.GYRO -> Scoring.gyro(timeOnTarget, trackingError, trackingAcc?.errorStdDevDegrees() ?: 0f)
            TrainingMode.FREE_PRACTICE -> 0
        }

        val summary = SessionSummary(
            mode = cfg.mode,
            difficulty = cfg.difficulty,
            startedAtMillis = now - elapsed,
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
            scoringVersion = SessionSummary.SCORING_VERSION_3D,
        )
        return if (summary.isValid) summary else null
    }

    // ------------------------------------------------------------------------------- tick

    private fun tick() {
        val cfg = config ?: return
        val nowNanos = clock.elapsedNanos()

        if (cfg.mode != TrainingMode.FREE_PRACTICE && elapsedMillis() >= cfg.durationSeconds * 1_000L) {
            finished = true
            running = false
            return
        }

        // Movement moves the player against the room bounds; penalty is applied at shot time.
        if (cfg.mode == TrainingMode.MOVEMENT && (moveX != 0f || moveZ != 0f)) {
            val speed = MOVE_SPEED * (TICK_MILLIS / 1000f)
            // Move relative to look yaw so "forward" is where the player faces.
            val fwd = camera.forward
            val flatFwd = Vec3(fwd.x, 0f, fwd.z).normalised()
            val right = flatFwd.cross(Vec3(0f, 1f, 0f)).normalised()
            val delta = Vec3(
                (flatFwd.x * -moveZ + right.x * moveX) * speed,
                0f,
                (flatFwd.z * -moveZ + right.z * moveX) * speed,
            )
            camera.move(delta, room.halfExtent)
            swayPhase += 0.25f
        }

        when (cfg.mode) {
            TrainingMode.FLICK, TrainingMode.MOVEMENT, TrainingMode.FREE_PRACTICE -> tickSpawns(nowNanos)
            TrainingMode.TRACKING, TrainingMode.GYRO -> tickTracking()
            TrainingMode.REACTION -> tickReaction(nowNanos)
            TrainingMode.RECOIL -> tickRecoil(nowNanos)
        }
        if (cfg.mode == TrainingMode.MOVEMENT) movementBuffer.add(if (moving) 1f else 0f)

        // Viewmodel recoil kick decays back each tick.
        if (viewmodelRecoil > 0f) viewmodelRecoil = (viewmodelRecoil - VIEWMODEL_RECOIL_DECAY).coerceAtLeast(0f)
    }

    private fun tickSpawns(nowNanos: Long) {
        val lifetimeNanos = params.targetLifetimeMillis * 1_000_000L
        val it = targets.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (nowNanos - t.spawnedAtNanos > lifetimeNanos) {
                it.remove()
                targetsMissed++
            }
        }
        val intervalNanos = params.spawnIntervalMillis * 1_000_000L
        if (targets.size < params.simultaneousTargets && nowNanos - lastSpawnNanos >= intervalNanos) {
            spawnRayTarget(nowNanos)
            lastSpawnNanos = nowNanos
        }
    }

    private fun tickTracking() {
        val path = trackingPath ?: return
        val seconds = elapsedMillis() / 1000f
        val centre = path.positionAt(seconds)
        targets.clear()
        targets.add(Target3D(1L, centre, params.targetRadius, startNanos, kind = TargetKind.STANDARD))
        val frameSeconds = TICK_MILLIS / 1000f
        val error = aimErrorDegrees(camera.aimRay(), centre)
        trackingAcc?.add(error, frameSeconds)
    }

    private fun tickReaction(nowNanos: Long) {
        if (targets.isEmpty() && nowNanos >= reactionWaitingUntilNanos && reactionWaitingUntilNanos != 0L) {
            val centre = com.gamecore.aimlab.engine3d.spawnFlickTarget3D(
                rng, camera.position, room,
                params.minDistance, params.maxDistance,
                params.maxYawDegrees, params.maxPitchDegrees,
            )
            targets.add(Target3D(nextTargetId++, centre, params.targetRadius, nowNanos))
            reactionShownNanos = nowNanos
            reactionWaitingUntilNanos = 0L
        }
    }

    private fun tickRecoil(nowNanos: Long) {
        // The fire-control gate discharges whatever the weapon's fire mode + trigger state calls for this
        // tick — one round for SINGLE/AUTO-per-tap, a spaced burst for BURST, continuous for held AUTO —
        // and owns the magazine and reload. Each discharged round walks the recoil pattern once, at the
        // real fire-rate cadence, so a burst climbs the pattern rather than landing all at once.
        val rounds = fireControl.tick(nowNanos)
        repeat(rounds) { dischargeRecoilRound(nowNanos) }
        // Mirror the gate's magazine/reload into the fields the HUD reads.
        ammo = fireControl.ammoRemaining
        reloading = fireControl.isReloading
    }

    // ------------------------------------------------------------------------------ shots

    private fun rayShot() {
        shots++
        val weapon = config?.weapon
        val spreadDeg = weaponSpreadDegrees(weapon)
        // Movement penalty widens the cone while strafing.
        val moveExtra = if (config?.mode == TrainingMode.MOVEMENT && moving) {
            (weapon?.movementPenalty ?: 0f) * MOVEMENT_SPREAD_DEGREES
        } else 0f
        val ray = applySpreadCone(camera.aimRay(), spreadDeg + moveExtra, rng)

        // Nearest sphere the ray strikes.
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in targets.indices) {
            val hit = targets[i].intersect(ray)
            if (hit.hit && hit.distance < bestDist) {
                bestDist = hit.distance
                bestIdx = i
            }
        }
        muzzleFlashUntilNanos = clock.elapsedNanos() + MUZZLE_FLASH_NANOS
        viewmodelRecoil = 1f
        if (bestIdx >= 0) {
            val target = targets.removeAt(bestIdx)
            hits++
            hitMarkerUntilNanos = clock.elapsedNanos() + HIT_MARKER_NANOS
            acquireSamples.add((clock.elapsedNanos() - target.spawnedAtNanos) / 1_000_000f)
            pendingBursts.add(target.position)
        } else {
            // Miss: a bullet hole on whatever wall the ray reaches, for feedback.
            pendingDecals.add(rayWallHit(ray))
        }
    }

    private fun reactionShot() {
        val target = targets.firstOrNull()
        muzzleFlashUntilNanos = clock.elapsedNanos() + MUZZLE_FLASH_NANOS
        if (target == null) {
            shots++ // false start
            return
        }
        shots++
        val ray = camera.aimRay()
        if (target.intersect(ray).hit) {
            hits++
            hitMarkerUntilNanos = clock.elapsedNanos() + HIT_MARKER_NANOS
            reactionTimes.add((clock.elapsedNanos() - reactionShownNanos) / 1_000_000L)
            pendingBursts.add(target.position)
        }
        targets.clear()
        armReaction()
    }

    /**
     * A recoil-mode trigger pull (a tap), routed through [FireControl] so the weapon's fire mode decides
     * how many rounds it produces: SINGLE one, BURST its burst count, AUTO one per tap (a held-trigger
     * hook is [onTriggerHeld]/[onTriggerReleased] for continuous fire). The rounds themselves discharge on
     * the ticker in [tickRecoil], spaced by the fire-rate interval, so a burst walks the recoil pattern at
     * the real cadence rather than all at once.
     */
    private fun recoilShot() {
        if (config?.weapon == null) return
        fireControl.onTriggerDown()
        // A tap is a press-and-release; for SINGLE/BURST that fires the sequence, for AUTO it fires one.
        fireControl.onTriggerUp()
    }

    /** Held-trigger fire for recoil mode (AUTO keeps firing, BURST fires one burst): the screen calls this
     *  on pointer-down and [onTriggerReleased] on pointer-up. Discharge happens on the ticker. */
    fun onTriggerHeld() {
        if (!running || finished || config?.mode != TrainingMode.RECOIL) return
        fireControl.onTriggerDown()
    }

    fun onTriggerReleased() {
        if (config?.mode != TrainingMode.RECOIL) return
        fireControl.onTriggerUp()
    }

    /** Discharges one recoil round: the kick, the shot/hit counters, the muzzle flash and a wall decal. */
    private fun dischargeRecoilRound(nowNanos: Long) {
        val weapon = config?.weapon ?: return
        shots++
        hits++ // recoil scores on compensation; every shot lands, the pattern is the test
        val (kickYaw, kickPitch) = recoilCamera.fireShot(weapon.recoil)
        camera.applyRecoil(kickYaw, kickPitch)
        muzzleFlashUntilNanos = nowNanos + MUZZLE_FLASH_NANOS
        viewmodelRecoil = 1f
        pendingDecals.add(rayWallHit(camera.aimRay()))
    }

    // ------------------------------------------------------------------------------ spawning

    private fun spawnRayTarget(nowNanos: Long) {
        val centre = com.gamecore.aimlab.engine3d.spawnFlickTarget3D(
            rng, camera.position, room,
            params.minDistance, params.maxDistance,
            params.maxYawDegrees, params.maxPitchDegrees,
        )
        val kind = if (params.targetRadius < 0.3f) TargetKind.SMALL else TargetKind.STANDARD
        targets.add(Target3D(nextTargetId++, centre, params.targetRadius, nowNanos, kind = kind))
    }

    private fun spawnTrackingTarget() {
        targets.clear()
        val depth = (params.minDistance + params.maxDistance) / 2f
        targets.add(
            Target3D(1L, Vec3(0f, Camera3D.DEFAULT_EYE_HEIGHT, -depth), params.targetRadius, startNanos),
        )
    }

    private fun armReaction() {
        val waitMillis = rng.nextFloat(REACTION_MIN_WAIT_MS.toFloat(), REACTION_MAX_WAIT_MS.toFloat()).toLong()
        reactionWaitingUntilNanos = clock.elapsedNanos() + waitMillis * 1_000_000L
    }

    // ------------------------------------------------------------------------------ helpers

    private fun movementConsistency(): Float =
        if (movementBuffer.size == 0) 0f else movementBuffer.mean()

    private fun elapsedMillis(): Long {
        if (startNanos == 0L) return 0L
        val nowNanos = if (running) clock.elapsedNanos() else pausedAtNanos.takeIf { it > 0L } ?: clock.elapsedNanos()
        return (nowNanos - startNanos - pausedTotalNanos).coerceAtLeast(0L) / 1_000_000L
    }

    /** Where the ray meets the nearest wall of the room, for a miss decal. A simple bounded march. */
    private fun rayWallHit(ray: com.gamecore.aimlab.engine3d.Ray): Vec3 {
        // March a fixed distance; clamp into the room so the decal sits on a surface, not past it.
        val far = ray.origin + ray.direction * 30f
        return Vec3(
            far.x.coerceIn(-room.halfWidth + 0.02f, room.halfWidth - 0.02f),
            far.y.coerceIn(0.02f, room.height - 0.02f),
            far.z.coerceIn(-room.halfDepth + 0.02f, room.halfDepth - 0.02f),
        )
    }

    private fun patternFor(cfg: TrainingConfig): Pattern3D = when (cfg.difficulty) {
        Difficulty.EASY -> Pattern3D.HORIZONTAL
        Difficulty.NORMAL, Difficulty.CUSTOM -> Pattern3D.CIRCULAR
        Difficulty.HARD -> Pattern3D.ZIGZAG
        Difficulty.EXTREME -> Pattern3D.RANDOM
    }

    private fun trackingHalfSpan(): Float {
        // A span the difficulty's speed can traverse and that stays within the room and the spawn cone.
        val depth = (params.minDistance + params.maxDistance) / 2f
        val byAngle = depth * Math.tan(Math.toRadians(params.maxYawDegrees.toDouble())).toFloat()
        return minOf(byAngle, room.halfWidth - 0.6f).coerceAtLeast(0.5f)
    }

    private fun weaponSpreadDegrees(weapon: com.gamecore.aimlab.engine.Weapon?): Float {
        // The weapon's arena-unit spread → a cone half-angle in degrees. Zero stays exactly precise.
        val s = weapon?.spread ?: 0f
        return s * SPREAD_UNIT_DEGREES
    }

    private fun snapshot(): TrainingFrame = TrainingFrame(
        elapsedMillis = elapsedMillis(),
        running = running,
        finished = finished,
        // The HUD reads only scalars off the frame; the 3D world goes through renderState, so targets and
        // crosshair stay at their harmless 2D defaults here.
        hits = hits,
        shots = shots,
        score = liveScore(),
    )

    private fun liveScore(): Int = when (config?.mode) {
        TrainingMode.FLICK, TrainingMode.MOVEMENT, TrainingMode.FREE_PRACTICE ->
            Scoring.flick(hits, shots, acquireSamples.mean())
        else -> 0
    }

    private fun publishRender() {
        val cfg = config
        val nowNanos = clock.elapsedNanos()
        val bursts = if (pendingBursts.isEmpty()) emptyList() else ArrayList(pendingBursts)
        val decals = if (pendingDecals.isEmpty()) emptyList() else ArrayList(pendingDecals)
        pendingBursts.clear()
        // Decals persist in the renderer's ring; publish new ones once.
        pendingDecals.clear()
        renderState.set(
            RenderState(
                cameraPosition = camera.position,
                cameraYawDegrees = camera.yawDegrees,
                cameraPitchDegrees = camera.pitchDegrees,
                fovDegrees = currentFov(),
                targets = if (targets.isEmpty()) emptyList() else ArrayList(targets),
                weaponCategory = cfg?.weapon?.category,
                viewmodelRecoil = viewmodelRecoil,
                adsProgress = if (aiming) 1f else 0f,
                swayPhase = swayPhase,
                muzzleFlash = nowNanos < muzzleFlashUntilNanos,
                hitMarker = nowNanos < hitMarkerUntilNanos,
                decals = decals,
                hitBursts = bursts,
            ),
        )
    }

    private fun currentFov(): Float {
        // The horizontal FOV comes from settings; ADS narrows it. The renderer derives the vertical FOV
        // from the real aspect ratio, so aim feel holds across portrait and landscape (§2).
        return if (aiming) horizontalFov * ADS_FOV_FACTOR else horizontalFov
    }

    private companion object {
        const val TICK_MILLIS = 16L
        const val REACTION_MIN_WAIT_MS = 700L
        const val REACTION_MAX_WAIT_MS = 2_500L

        /** Arena-unit weapon spread → cone half-angle degrees. */
        const val SPREAD_UNIT_DEGREES = 60f

        /** Extra cone half-angle degrees at full movement penalty while strafing. */
        const val MOVEMENT_SPREAD_DEGREES = 4f

        /** Player walk speed, metres/second. */
        const val MOVE_SPEED = 3.5f

        /** FOV multiplier while aiming down sights. */
        const val ADS_FOV_FACTOR = 0.65f

        const val MUZZLE_FLASH_NANOS = 60_000_000L      // 60 ms
        const val HIT_MARKER_NANOS = 120_000_000L       // 120 ms
        const val VIEWMODEL_RECOIL_DECAY = 0.12f
    }
}
