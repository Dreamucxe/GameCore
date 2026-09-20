package com.gamecore.aimlab

import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Weapon
import kotlinx.coroutines.flow.Flow

/**
 * FROZEN CONTRACTS for the Aim Lab feature — the seams the UI, data and runtime agents build against.
 *
 * These are signatures only. The `data-layer` agent implements [AimLabRepository]; the `training-runtime`
 * agent implements [TrainingLoop] and defines per-mode engines. Every screen depends only on the engine
 * data classes plus these interfaces, so the agents can be written in parallel without seeing each other's
 * finished code. Do not add logic here; if a signature must change, the change is agreed at the orchestrator
 * level and made here first.
 */

/**
 * The one repository the Aim Lab UI talks to for persistence.
 *
 * Everything durable — sessions, records, weapons, sensitivity profiles, control layouts — goes through
 * here. Implemented over Room by the data-layer agent, following the existing `SessionRepository` pattern
 * (`@Singleton`, `@IoDispatcher`, Flows for lists, `suspend` for one-shots). Session writes are summary-only;
 * no per-frame data reaches this interface.
 */
interface AimLabRepository {

    // --- sessions & records (observed lists for the stats/records/history screens) ---
    val sessions: Flow<List<SessionSummary>>
    val records: Flow<List<PersonalRecord>>
    val sessionCount: Flow<Int>

    /** Persists a finished session (rejected if [SessionSummary.isValid] is false) and updates any records
     *  it beats via PersonalRecordBook; returns the stored session id, or 0 if it was rejected. */
    suspend fun saveSession(summary: SessionSummary): Long

    suspend fun recentSessions(limit: Int): List<SessionSummary>
    suspend fun sessionsFor(mode: TrainingMode): List<SessionSummary>

    /** Deletes one stored session; any record it set stands, as a record's value cannot be recomputed. */
    suspend fun deleteSession(id: Long)

    suspend fun clearHistory()

    // --- weapons ---
    val weapons: Flow<List<Weapon>>
    suspend fun weapon(id: Long): Weapon?
    suspend fun saveWeapon(weapon: Weapon): Long
    suspend fun deleteWeapon(id: Long)

    // --- sensitivity profiles ---
    val sensitivities: Flow<List<SensitivityProfile>>
    suspend fun sensitivity(id: Long): SensitivityProfile?
    suspend fun saveSensitivity(profile: SensitivityProfile): Long
    suspend fun deleteSensitivity(id: Long)

    // --- control layouts ---
    val layouts: Flow<List<ControlLayout>>
    suspend fun layout(id: Long): ControlLayout?
    suspend fun saveLayout(layout: ControlLayout): Long
    suspend fun deleteLayout(id: Long)

    /** Ensures the built-in weapons, default sensitivity presets and a default layout exist. Called once
     *  the first time Aim Lab is opened, never at process start. */
    suspend fun seedDefaultsIfEmpty()
}

/**
 * The record of one training configuration a mode screen hands to the loop when a run starts.
 *
 * A plain value object so the runtime and the screens agree on what a run consists of without either
 * depending on the other's classes.
 */
data class TrainingConfig(
    val mode: TrainingMode,
    val difficulty: Difficulty,
    val weapon: Weapon? = null,
    val sensitivity: SensitivityProfile? = null,
    val layoutId: Long? = null,
    val durationSeconds: Int = 60,
    val seed: Long = 0L,
    /**
     * The movement pattern for tracking/gyro runs. Null lets the loop pick one from the difficulty, so a
     * mode that does not care about it (flick, reaction, recoil) can leave it unset.
     */
    val pattern: com.gamecore.aimlab.engine.TrackingPattern? = null,
)

/**
 * One immutable frame the training loop publishes for the UI to render.
 *
 * The loop mutates its own internal, allocation-free state at tick rate and emits one of these per UI
 * frame (~60/s cap), so gyro/touch events never drive whole-screen recomposition (§21). Fields a mode does
 * not use stay empty. Positions are engine-normalised [0,1].
 */
data class TrainingFrame(
    val elapsedMillis: Long,
    val running: Boolean,
    val finished: Boolean,
    val targets: List<com.gamecore.aimlab.engine.Target> = emptyList(),
    val crosshair: com.gamecore.aimlab.engine.Vec2 = com.gamecore.aimlab.engine.Vec2.CENTER,
    val recoilOffset: com.gamecore.aimlab.engine.Vec2 = com.gamecore.aimlab.engine.Vec2(0f, 0f),
    val hits: Int = 0,
    val shots: Int = 0,
    val score: Int = 0,
)

/**
 * The training loop: starts a run, accepts input, ticks, and produces the final [SessionSummary].
 *
 * Implemented by the training-runtime agent. The implementation owns a coroutine-driven update loop tied to
 * the screen's lifecycle — it starts on run start and is fully cancelled on stop/leave/background, with no
 * service and no listener outliving the run (§19/§29). Sensor registration for gyro modes lives behind the
 * runtime too and unregisters on stop.
 */
interface TrainingLoop {
    /** The frames to collect on the training screen. Collection drives the loop; leaving cancels it. */
    val frames: Flow<TrainingFrame>

    /** Begins a run with [config]. Safe to call once per screen entry. */
    fun start(config: TrainingConfig)

    /** A pointer-down "shot"/tap at normalised [x],[y]. Returns immediately; scoring is internal. */
    fun onShot(x: Float, y: Float)

    /** A look/aim delta in full-scale units (from the touch surface or gyro), and whether aiming. */
    fun onAimDelta(dx: Float, dy: Float, aiming: Boolean)

    /** Pauses without discarding progress (e.g. app backgrounded). */
    fun pause()

    /** Resumes a paused run. */
    fun resume()

    /** Ends the run and returns the summary to persist, or null if the run produced nothing valid. */
    fun stop(): SessionSummary?
}
