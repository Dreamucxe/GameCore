package com.gamecore.domain.intelligence

import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.SmartRecommendation
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * Writes a thermal recommendation into a game profile, with the trigger it promised.
 *
 * The recommendation is only useful when the profile carries at least one trigger the thermal
 * machine can act on. [SmartFeatureRunner] builds its config from the profile's
 * `thermalLimitDeciCelsius`, `thermalStatusFloor` and `thermalFloorRateHz`; a profile with the
 * enable flag alone reacts only at platform `CRITICAL`. This object refuses that no-op instead of
 * letting the caller save it and record an `APPLIED` outcome.
 *
 * Saving is injected rather than reached through a repository so the success, failure and no-op
 * paths are testable without a database.
 */
object ThermalProfileApplier {

    /** A rate this close to another is the same rung, so it is not a step down. */
    private const val RATE_TOLERANCE = 1.5f

    /**
     * Whether [floorRateHz] can actually be stepped to from [startRateHz] on this panel.
     *
     * The machine builds its ladder from the panel's advertised rates at or above the floor. A floor
     * that is below the start rate but is not one of those rates can leave fewer than two rungs, so
     * the feature would save successfully and still do nothing.
     */
    fun isUsableFloor(
        floorRateHz: Float,
        startRateHz: Float,
        supportedRates: List<Float>,
    ): Boolean = floorRateHz < startRateHz - RATE_TOLERANCE &&
        supportedRates.any { abs(it - floorRateHz) <= RATE_TOLERANCE }

    /** The profile after applying [recommendation], or null when it has no actionable target. */
    fun updatedProfile(
        recommendation: SmartRecommendation,
        profile: GameProfile,
    ): GameProfile? {
        val limit = recommendation.targetThermalLimitDeciCelsius
        val statusFloor = recommendation.targetThermalStatusFloor
        if (limit == null && statusFloor == null) return null

        // The machine can only step down if the floor is below the rate the session starts at.
        // Keep a floor the user already reviewed; fill in the recommendation's only when absent.
        val floor = profile.thermalFloorRateHz
            ?: recommendation.targetThermalFloorRateHz
            ?: return null
        val startRate = profile.targetRefreshRate
        if (startRate != null && floor >= startRate - RATE_TOLERANCE) return null

        return profile.copy(
            thermalDownshiftEnabled = true,
            // Never overwrite a trigger the user already reviewed on the profile; only fill what is
            // missing. The engine already preserved it when it built the recommendation, so this
            // only matters when the profile was edited after the recommendation was generated.
            thermalLimitDeciCelsius = profile.thermalLimitDeciCelsius ?: limit,
            thermalStatusFloor = profile.thermalStatusFloor ?: statusFloor,
            thermalFloorRateHz = floor,
        )
    }

    /**
     * Applies the recommendation and reports exactly what happened.
     *
     * A failed save is [ThermalProfileApplyResult.Failed], never [Applied]; an identical profile is
     * [AlreadyConfigured] and the save is not attempted. The caller must not record a learning
     * `APPLIED` decision for either of those outcomes.
     */
    suspend fun apply(
        recommendation: SmartRecommendation,
        profile: GameProfile,
        save: suspend (GameProfile) -> Unit,
    ): ThermalProfileApplyResult {
        if (recommendation.action != RecommendationAction.ENABLE_THERMAL_DOWNSHIFT) {
            return ThermalProfileApplyResult.NoUsableTrigger
        }

        val updated = updatedProfile(recommendation, profile)
            ?: return ThermalProfileApplyResult.NoUsableTrigger
        if (updated == profile) return ThermalProfileApplyResult.AlreadyConfigured

        return try {
            save(updated)
            ThermalProfileApplyResult.Applied(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ThermalProfileApplyResult.Failed(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "The profile could not be saved.",
            )
        }
    }
}

/** The outcome of [ThermalProfileApplier.apply]. */
sealed interface ThermalProfileApplyResult {
    /** The profile was saved with a usable trigger. */
    data class Applied(val profile: GameProfile) : ThermalProfileApplyResult

    /** The profile already contained the recommended trigger and floor; nothing was written. */
    data object AlreadyConfigured : ThermalProfileApplyResult

    /** The recommendation carried no temperature or platform-status trigger. */
    data object NoUsableTrigger : ThermalProfileApplyResult

    /** The save was attempted and failed; [detail] is safe to show to the user. */
    data class Failed(val detail: String) : ThermalProfileApplyResult
}
