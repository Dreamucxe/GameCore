package com.gamecore.ui.color

import com.gamecore.core.common.Observed
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorField
import com.gamecore.core.model.ColorPreset
import com.gamecore.domain.color.ColorEngagement
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readout

/**
 * The colour editor: the values being adjusted, the presets they can be saved as, and what the display is
 * actually doing about any of it.
 *
 * Three facts on this screen are readings rather than settings, and all three arrive as [Observed] because
 * on a lot of devices the honest answer to them is "no": whether GameCore may write a secure setting at
 * all, what the display currently has engaged, and — per field — whether this Android version has anywhere
 * to put the value the user just dragged. §5 of the colour brief and the app's own honesty rule are the
 * same rule here: a slider that moves and changes nothing has to say so, next to itself.
 *
 * [correction] is not one of those. It is what the user asked for, held by GameCore, and it exists whether
 * or not the panel can express it — which is why the sliders always have a position to show, and why
 * reopening this screen shows where the last drag left off.
 */
data class ColorUiState(
    val isLoaded: Boolean = false,
    val correction: ColorCorrection = ColorCorrection.NEUTRAL,
    val presets: List<ColorPreset> = emptyList(),
    /** The preset whose values these are, until a slider moves. Null means "custom". */
    val activePresetId: Long? = null,
    /**
     * How GameCore is allowed to write colour keys, as the mechanism's own label.
     *
     * The label rather than the enum: §24A.2 asks that what reaches the UI hold only what the UI shows,
     * and what this screen shows is one phrase. Which permission it maps to, and what to do about it
     * being absent, is [Observed.Restricted]'s detail — already written for a person to read.
     */
    val access: Observed<String> = Observed.awaitingSample(NOT_READ_YET),
    /** What the display has engaged, read from the device — including a ROM's own night schedule. */
    val engagement: Observed<ColorEngagement> = Observed.awaitingSample(NOT_READ_YET),
    /** Whether *GameCore's* correction is the thing in force, which is a cheaper, narrower question. */
    val isEngagedByGameCore: Boolean = false,
    val isChecking: Boolean = false,
    val isApplying: Boolean = false,
    /** Per field, why this device cannot honour the value it is set to. Empty is the good case. */
    val limits: Map<ColorField, String> = emptyMap(),
    /** What an apply would change on this device, in the user's words. Empty when nothing is asked. */
    val changes: List<PlannedChange> = emptyList(),
    val message: String? = null,
) {
    val activePreset: ColorPreset? get() = presets.firstOrNull { it.id == activePresetId }

    /**
     * The preset name for the header, or what the values are when they belong to no preset.
     *
     * "Custom" rather than a blank: values that match nothing saved are the normal state of this screen
     * halfway through an adjustment, and a header that empties itself as soon as a slider moves reads as
     * something having gone wrong.
     */
    val presetName: String
        get() = activePreset?.name ?: if (correction.changesNothing) "No correction" else "Custom"

    /** Whether a write would even reach the provider. The sliders stay live either way; see the screen. */
    val canWrite: Boolean get() = access is Observed.Value

    val accessRow: Readout
        get() = access.readout(label = "Write access", tone = Tone.Good) { it }

    /**
     * The engagement row, with the count GameCore owns as its detail.
     *
     * The two halves are a deliberate pair: "the screen is warm" and "GameCore made it warm" are different
     * claims, and only the second one licenses this screen to offer to put it back.
     */
    val engagementRow: Readout
        get() = engagement.readout(
            label = "On screen now",
            tone = Tone.Accent,
            detailOf = { engaged ->
                when {
                    !engaged.isActive -> "Nothing GameCore or the system has changed is in force."
                    engaged.ownedByGameCore > 0 ->
                        "GameCore is holding ${engaged.ownedByGameCore} of these and can put them back."
                    else -> "None of this is GameCore's, so this screen will not undo it."
                }
            },
        ) { it.summary }

    /** Why this field cannot be honoured, or null when it can. Drawn under the slider that produced it. */
    fun limitFor(field: ColorField): String? = limits[field]

    /** True when every visible field has somewhere to go on this device. */
    val isFullyReachable: Boolean get() = correction.visibleFields.none { it in limits }

    /** Whether "reset everything" has anything to do — either stored values or a live correction. */
    val canReset: Boolean get() = !correction.changesNothing || isEngagedByGameCore

    private companion object {
        const val NOT_READ_YET = "Not read yet."
    }
}

/**
 * One thing an apply will change on this device, and why that expresses what was asked.
 *
 * A pair of sentences rather than the [com.gamecore.core.system.ColorWrite] behind it. The write carries a
 * settings namespace, a key and a raw provider value, none of which this screen shows and none of which
 * belongs on the UI side of §25's line — but "why does warmth move a Kelvin slider I never touched" is a
 * real question, and the answer is the half of the write that was written for a person.
 */
data class PlannedChange(val what: String, val why: String)
