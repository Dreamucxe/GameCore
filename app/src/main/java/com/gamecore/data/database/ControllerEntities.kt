package com.gamecore.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved controller reference profile, added in schema version 21.
 *
 * Two tables, both new, both additive: no existing row is rewritten by the update. The values are the
 * curve lab's simulation and the drift tolerance a run used, stored so two runs can be compared and a
 * profile can travel between devices. They are **not applied to any game** — GameCore has no supported
 * mechanism to change another app's controller dead zone, and nothing here pretends otherwise.
 *
 * [deviceSignature] is a capability fingerprint derived from the device's vendor/product ids, declared
 * axis ids and button count. A name is stored too, but only so a person can read the list; it is never the
 * key, because two physically different pads can share a marketing name.
 */
@Entity(
    tableName = "controller_profiles",
    indices = [Index("device_signature"), Index("updated_at")],
)
data class ControllerProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "device_signature")
    val deviceSignature: String,

    @ColumnInfo(name = "device_name")
    val deviceName: String,

    @ColumnInfo(name = "deadzone")
    val deadzone: Float,

    /** A [com.gamecore.core.controller.ResponseCurveKind] name, parsed defensively by the mapper. */
    @ColumnInfo(name = "curve")
    val curve: String,

    @ColumnInfo(name = "exponent")
    val exponent: Float,

    @ColumnInfo(name = "drift_offset")
    val driftOffset: Float,

    @ColumnInfo(name = "drift_jitter")
    val driftJitter: Float,

    @ColumnInfo(name = "notes")
    val notes: String,

    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAtMillis: Long,
)

/**
 * One completed drift run, added in schema version 21.
 *
 * The run's samples stay in memory and are discarded: this row is the summary a later run is compared
 * against. [appliedOffset] and [appliedJitter] are the tolerance actually used after the device's declared
 * flat/fuzz floors were applied, so a result can be read years later without guessing which threshold was
 * in force at the time.
 */
@Entity(
    tableName = "controller_test_results",
    indices = [Index("device_signature"), Index("started_at")],
)
data class ControllerTestResultEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "device_signature")
    val deviceSignature: String,

    @ColumnInfo(name = "device_name")
    val deviceName: String,

    /** A [com.gamecore.core.controller.StickKind] name. */
    @ColumnInfo(name = "stick")
    val stick: String,

    @ColumnInfo(name = "started_at")
    val startedAtMillis: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMillis: Long,

    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,

    @ColumnInfo(name = "offset_x")
    val offsetX: Float,

    @ColumnInfo(name = "offset_y")
    val offsetY: Float,

    @ColumnInfo(name = "offset_magnitude")
    val offsetMagnitude: Float,

    @ColumnInfo(name = "jitter")
    val jitter: Float,

    @ColumnInfo(name = "stability_percent")
    val stabilityPercent: Float,

    @ColumnInfo(name = "peak_magnitude")
    val peakMagnitude: Float,

    /** A [com.gamecore.core.controller.DriftVerdict] name; an unknown name drops the row on read. */
    @ColumnInfo(name = "verdict")
    val verdict: String,

    @ColumnInfo(name = "offset_threshold")
    val offsetThreshold: Float,

    @ColumnInfo(name = "jitter_threshold")
    val jitterThreshold: Float,
)
