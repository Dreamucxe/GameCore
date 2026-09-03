package com.gamecore.ui.color

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Observed
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.common.map
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorField
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.GammaMode
import com.gamecore.core.system.ColorPlan
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.ColorPresetRepository
import com.gamecore.domain.color.ColorApplyResult
import com.gamecore.domain.color.ColorCorrectionController
import com.gamecore.domain.color.ColorEngagement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The colour editor's state, and the only place in the UI layer that asks for a colour to be applied.
 *
 * Two things about this screen are worth naming before the code.
 *
 * **There is no save button, and no draft that can be lost.** A correction is judged by looking at the
 * screen, so a slider writes through: the values are stored when the finger lifts, and the same lift asks
 * the controller to put them on the display. What a drag does *not* do is write once per frame — an
 * encrypted preference write and up to eight shell round trips per pointer event is the cost §26 exists to
 * forbid — so the position moves at finger speed and the device catches up on release.
 *
 * **Nothing here touches a setting.** [ColorCorrectionController] owns the projection, the writes and the
 * restore points, which is §25's line: this class hands it a [ColorCorrection] and renders what comes back.
 * [ColorCorrectionController.preview] is the exception that proves the rule — it is pure arithmetic over
 * the model and no provider is involved, which is why it can run on every frame of a drag to keep each
 * slider's "this device cannot do that" line honest while the thumb is still moving.
 */
@HiltViewModel
class ColorViewModel @Inject constructor(
    private val presets: ColorPresetRepository,
    private val colour: ColorCorrectionController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The parts of the state this class owns rather than observes.
     *
     * [correction] is held here rather than read from [SecurePreferenceStore.colorCorrection] for the
     * duration of a drag: the store is written on release, and a store that pushed back mid-gesture would
     * fight the thumb. [refresh] is what re-syncs the two, on resume, because the overlay panel edits the
     * same three values from over a game.
     */
    private data class LocalState(
        val isLoaded: Boolean = false,
        val correction: ColorCorrection = ColorCorrection.NEUTRAL,
        val activePresetId: Long? = null,
        val access: Observed<String> = Observed.awaitingSample(NOT_READ_YET),
        val engagement: Observed<ColorEngagement> = Observed.awaitingSample(NOT_READ_YET),
        val isEngagedByGameCore: Boolean = false,
        val isChecking: Boolean = false,
        val isApplying: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<ColorUiState> = combine(presets.presets, local) { saved, own ->
        val plan = colour.preview(own.correction)
        ColorUiState(
            isLoaded = own.isLoaded,
            correction = own.correction,
            presets = saved,
            activePresetId = own.activePresetId,
            access = own.access,
            engagement = own.engagement,
            isEngagedByGameCore = own.isEngagedByGameCore,
            isChecking = own.isChecking,
            isApplying = own.isApplying,
            limits = plan.limitMessages(),
            changes = plan.plannedChanges(),
            message = own.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        ColorUiState(),
    )

    init {
        viewModelScope.launch { openFirst() }
    }

    /**
     * Seeds the shipped presets on first ever open, then shows the values GameCore is holding.
     *
     * The seven built-ins are ordinary rows from the moment they exist, exactly as the crosshair defaults
     * are, so "Night" is a starting point the user can drag, rename, save over or delete rather than a menu
     * item. The full engagement read is done once, here, because it costs shell round trips and this is the
     * one moment the user is waiting for the screen anyway.
     */
    private suspend fun openFirst() {
        presets.seedDefaultsIfEmpty()
        local.value = local.value.copy(
            isLoaded = true,
            correction = preferences.colorCorrection.value,
            activePresetId = preferences.activeColorPresetId,
        )
        refreshAccess()
        refreshOwnership()
        checkEngagement()
    }

    /**
     * Re-reads everything cheap. Called when the screen comes back to the front.
     *
     * The overlay panel edits saturation, contrast and hue from over a game, and a profile activating can
     * apply a whole preset, so the values this screen last drew can be stale by the time it is looked at
     * again. The correction is re-read from the store rather than kept, which is also how a user who
     * adjusted the quick sliders mid-game finds those positions here.
     */
    fun refresh() {
        local.value = local.value.copy(
            correction = preferences.colorCorrection.value,
            activePresetId = preferences.activeColorPresetId,
        )
        viewModelScope.launch {
            refreshAccess()
            refreshOwnership()
        }
    }

    /**
     * Reads what the display currently has engaged, from the device.
     *
     * Offered as a button rather than run on a timer: it is up to eight shell round trips, it answers a
     * question that only changes when something changes it, and a screen that re-read it every few seconds
     * would be the background cost §26 rules out on a screen the user is sitting on.
     */
    fun checkEngagement() {
        if (local.value.isChecking) return
        local.value = local.value.copy(isChecking = true)
        viewModelScope.launch {
            val engagement = colour.engagement()
            local.value = local.value.copy(engagement = engagement, isChecking = false)
            refreshOwnership()
        }
    }

    private suspend fun refreshAccess() {
        local.value = local.value.copy(access = colour.access().map { it.label })
    }

    /**
     * Whether GameCore's own correction is still in force — one indexed query, not eight shell reads.
     *
     * Kept separate from [checkEngagement] because it answers the narrower question this screen acts on: a
     * "put it back" offer is only honest for the settings GameCore changed, and a ROM's own night schedule
     * is not one of them.
     */
    private suspend fun refreshOwnership() {
        local.value = local.value.copy(isEngagedByGameCore = colour.isEngagedByGameCore())
    }

    // ------------------------------------------------------------------------------ the values

    /**
     * Moves one field. The screen's slider path, so nothing is stored and nothing is written.
     *
     * The active preset is dropped as soon as a value moves, because from that moment these are not that
     * preset's values. Saying so immediately is the point: the header reads "Custom", the preset row loses
     * its mark, and "Save as new preset" becomes the obvious next thing rather than a surprise.
     */
    fun setField(field: ColorField, value: Int) {
        val current = local.value
        val updated = current.correction.with(field, value)
        if (updated == current.correction && current.activePresetId == null) return
        local.value = current.copy(correction = updated, activePresetId = null)
    }

    /** Stores the values and puts them on the display. Called when a slider is released. */
    fun commitField() {
        store()
        apply()
    }

    /**
     * Switches between one gamma slider and three.
     *
     * Both sets of values are stored at all times, so this is a view change that happens to alter what gets
     * projected — switching to per-channel and back returns the combined slider to where it was left. Stored
     * and applied immediately, like every other discrete control here.
     */
    fun setGammaMode(mode: GammaMode) = editDiscrete { it.copy(gammaMode = mode) }

    fun setVisionFilter(filter: ColorVisionFilter) = editDiscrete { it.copy(visionFilter = filter) }

    fun setInvert(invert: Boolean) = editDiscrete { it.copy(invertColors = invert) }

    /** "Reset this channel": one field back to neutral, stored and applied. */
    fun resetField(field: ColorField) = editDiscrete { it.reset(field) }

    /** The same, for the fields of one card — the three gains, or the gamma set the user can see. */
    fun resetFields(fields: List<ColorField>) =
        editDiscrete { correction -> fields.fold(correction) { acc, field -> acc.reset(field) } }

    /**
     * "Reset to default": every value back to neutral, and the display handed back.
     *
     * [ColorCorrectionController.clear] rather than an apply of a neutral correction, and the difference is
     * the whole point of a restore point: neutral *writes* would mean deciding what this device's colour
     * mode was before GameCore touched it, and guessing wrong leaves a user's own night display switched
     * off. Clearing puts back the readings taken before each write.
     */
    fun resetAll() {
        local.value = local.value.copy(correction = ColorCorrection.NEUTRAL, activePresetId = null)
        store()
        viewModelScope.launch {
            local.value = local.value.copy(isApplying = true)
            val result = colour.clear()
            local.value = local.value.copy(
                isApplying = false,
                access = result.access.map { it.label },
                message = if (result.isBlocked) {
                    result.message
                } else {
                    "Every value is back to neutral and the display has been put back the way GameCore " +
                        "found it."
                },
            )
            refreshOwnership()
            checkEngagement()
        }
    }

    // ----------------------------------------------------------------------------- the presets

    /**
     * Loads a preset's values into the sliders and applies them.
     *
     * The id is remembered — in the store as well as here — so the overlay panel's chips, the header and
     * this list all agree about which preset is on screen. It is dropped again by the first slider that
     * moves, which is [setField]'s business.
     */
    fun loadPreset(id: Long) {
        viewModelScope.launch {
            val preset = presets.preset(id)
            if (preset == null) {
                local.value = local.value.copy(message = "That preset is no longer saved.")
                return@launch
            }
            local.value = local.value.copy(correction = preset.correction, activePresetId = preset.id)
            store()
            apply()
        }
    }

    /**
     * "Save as preset": the values on screen, under a name, as a new row.
     *
     * Sanitised on the way in rather than on the way out (§24A.4): a preset name is user-generated content
     * that will be rendered in this list, in a chip over a game, in a profile row and in a session report,
     * and cleaning it once at the storage boundary is what keeps all four honest. A blank name after
     * sanitising becomes the model's own default rather than an empty row.
     *
     * There is no cap on how many can be saved. The seven that ship are the starting points.
     */
    fun saveAsNew(name: String) {
        viewModelScope.launch {
            val clean = TextSanitizer
                .sanitizeName(name, ColorPreset.MAX_NAME_LENGTH)
                .ifBlank { ColorPreset.DEFAULT_NAME }
            val id = presets.saveAsNew(clean, ColorPreset(name = clean, correction = local.value.correction))
            preferences.activeColorPresetId = id
            local.value = local.value.copy(activePresetId = id, message = "Saved as “$clean”.")
        }
    }

    /** Writes the values on screen over an existing preset, keeping its name and its id. */
    fun overwrite(id: Long) {
        viewModelScope.launch {
            val existing = presets.preset(id) ?: return@launch
            presets.save(existing.copy(correction = local.value.correction))
            preferences.activeColorPresetId = id
            local.value = local.value.copy(
                activePresetId = id,
                message = "“${existing.name}” now holds these values.",
            )
        }
    }

    /** Renames a preset. Sanitised for the same reason [saveAsNew] sanitises. */
    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            val existing = presets.preset(id) ?: return@launch
            val clean = TextSanitizer
                .sanitizeName(name, ColorPreset.MAX_NAME_LENGTH)
                .ifBlank { ColorPreset.DEFAULT_NAME }
            presets.save(existing.copy(name = clean))
        }
    }

    /**
     * Deletes a preset and every profile's reference to it.
     *
     * The values stay on the sliders, unlike the crosshair screen's delete. A crosshair preset *is* the
     * thing on screen, so deleting it has to take it down; a colour correction is already on the display,
     * and switching the user's screen back to neutral because they tidied their preset list is a change
     * they did not ask for. The message says what the delete did reach.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val existing = presets.preset(id)
            presets.delete(id)
            if (preferences.activeColorPresetId == id) preferences.activeColorPresetId = null
            local.value = local.value.copy(
                activePresetId = local.value.activePresetId?.takeIf { it != id },
                message = "“${existing?.name ?: "Preset"}” deleted. Any game profile that used it now " +
                    "applies no colour correction. The values are still on the sliders.",
            )
        }
    }

    /**
     * Puts back any of the seven shipped presets that are missing, and says how many that was.
     *
     * Always offered rather than shown only when something is missing, because working out whether a
     * built-in is "missing" means matching by name, and that rule belongs to the repository that does the
     * seeding — a second copy of it here would be the one that drifts. A run that finds nothing missing
     * says so, which is a button that did something.
     */
    fun restoreBuiltIns() {
        viewModelScope.launch {
            val restored = presets.restoreMissingDefaults()
            local.value = local.value.copy(
                message = if (restored == 0) {
                    "All seven built-in presets are already in the list."
                } else {
                    "Put back $restored built-in preset${if (restored == 1) "" else "s"}."
                },
            )
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    // --------------------------------------------------------------------------- internals

    /** The values, and which preset they belong to, into the store. One write per gesture, not per frame. */
    private fun store() {
        val current = local.value
        preferences.setColorCorrection(current.correction)
        preferences.activeColorPresetId = current.activePresetId
    }

    /**
     * Asks the controller to put the current values on the display.
     *
     * No game is named, unlike the overlay panel's apply: a change made on this screen was made by someone
     * looking at this screen, and attributing it to whatever happened to be running would put a game's name
     * on a correction it had nothing to do with.
     *
     * A message is set only for a write that reached the provider and was refused, which is the case nothing
     * else on the screen would explain. A blocked apply needs no banner — [refreshAccess]'s row is already
     * saying why, permanently, at the top — and a correction this device cannot express is described field
     * by field under the sliders that produced it.
     */
    private fun apply() {
        val correction = local.value.correction
        local.value = local.value.copy(isApplying = true)
        viewModelScope.launch {
            val result = colour.apply(correction)
            local.value = local.value.copy(
                isApplying = false,
                access = result.access.map { it.label },
                message = result.failureMessage() ?: local.value.message,
            )
            refreshOwnership()
        }
    }

    /**
     * A change that is not a drag: stored and applied as it is made.
     *
     * Everything except a slider comes through here. The preset link goes, for [setField]'s reason — these
     * are no longer that preset's values the moment one of them is different.
     */
    private inline fun editDiscrete(transform: (ColorCorrection) -> ColorCorrection) {
        local.value = local.value.copy(
            correction = transform(local.value.correction).normalised(),
            activePresetId = null,
        )
        store()
        apply()
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val NOT_READ_YET = "Not read yet."
    }
}

/** The device's refusals, keyed by the field that caused them, for the line under each slider. */
private fun ColorPlan.limitMessages(): Map<ColorField, String> =
    limits.associate { it.field to it.message }

/**
 * The plan as a pair of sentences per change, dropping the namespace, key and raw value.
 *
 * §24A.2 in one function: the screen shows what will change and why, and a settings key is neither.
 */
private fun ColorPlan.plannedChanges(): List<PlannedChange> =
    writes.map { PlannedChange(what = it.setting.userDescription, why = it.because) }

/** The one sentence worth interrupting the user with: a write that got through and was refused anyway. */
private fun ColorApplyResult.failureMessage(): String? =
    message.takeIf { !isBlocked && failures.isNotEmpty() }


