package com.gamecore.ui.glass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.data.plugin.GlassControl
import com.gamecore.data.plugin.GlassMaterialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Glass Control Center's state: what the material is, and where the user has dragged it to.
 *
 * The screen this backs is deliberately thin, because almost nothing about glass is the screen's to
 * decide — the material comes from the active theme, the value comes from the plugin's own settings
 * store, and both are resolved by [GlassMaterialStore]. What is left for a ViewModel is the part that
 * is genuinely a screen's: the difference between where the finger *is* and what has been *stored*.
 *
 * ## Why the slider has two values
 *
 * A drag updates [fraction] immediately and writes nothing. The write happens once, when the finger
 * lifts — [commit] — because the store's write path is a rate-limited, audited, encrypted-preference
 * write, and sixty of those a second is a request flood that the plugin API's own limiter would
 * refuse within the first second of a drag. So the slider tracks the finger from one flow and the
 * material is stored from another, and a drag that is refused springs back rather than lying.
 */
@HiltViewModel
class GlassCentreViewModel @Inject constructor(
    private val glass: GlassMaterialStore,
) : ViewModel() {

    /** The material in force, or null when the app is not wearing glass. */
    val control: StateFlow<GlassControl?> = glass.state

    /** Where the finger is, or null when nothing is being dragged. */
    private val dragged = MutableStateFlow<Float?>(null)

    /**
     * The position the slider is drawn at: the finger's while one is down, the stored one otherwise.
     *
     * One, not zero, for the no-glass case — although the slider is not drawn then, so what this
     * really guards is the first frame after a theme becomes active, before the store has resolved
     * its value. A slider that flashed to empty for a frame would look like the theme had failed.
     */
    val fraction: StateFlow<Float> = combine(glass.state, dragged) { control, drag ->
        drag ?: control?.fraction ?: AUTHORED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AUTHORED)

    private val mutableMessage = MutableStateFlow<String?>(null)

    /** A one-line note about a write that did not land, or null. Cleared by the banner's own dismiss. */
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    /** The plugin whose material this screen is describing, so a theme switch can drop a stale drag. */
    private var describing: String? = null

    init {
        // A drag value outlives its drag only until the store reports the value it was dragged to.
        // Without this, switching themes mid-drag would leave the slider showing the last position
        // of a material that is no longer on screen — and, worse, the next commit would write that
        // position to the *new* theme's setting.
        viewModelScope.launch {
            glass.state.collect { current ->
                val id = current?.pluginId
                if (id != describing) {
                    describing = id
                    dragged.value = null
                    return@collect
                }
                val drag = dragged.value ?: return@collect
                // The write landed and the store now agrees with the finger: the drag has done its
                // job and the stored value takes over. Compared with a tolerance rather than for
                // equality because the value has been through a float → setting → float round trip,
                // and an exact match would never arrive on an integer-backed setting.
                if (current != null && abs(current.fraction - drag) < SETTLED_TOLERANCE) {
                    dragged.value = null
                }
            }
        }
    }

    /** The finger has moved. Nothing is stored until [commit]. */
    fun dragTo(value: Float) {
        dragged.value = value.coerceIn(0f, 1f)
    }

    /**
     * The finger has lifted: store where it left the material.
     *
     * A refusal is reported rather than swallowed. The three ways it happens are all real — the
     * plugin's Storage permission was withdrawn, the API limiter is refusing writes, or the theme
     * names no control at all — and in every one of them the honest thing is to pull the slider back
     * to the value that is actually in force and say so, because a slider that stays where it was
     * put while the app ignores it is the one outcome §24 does not allow.
     */
    fun commit() {
        val value = dragged.value ?: return
        val pluginName = control.value?.pluginName
        viewModelScope.launch {
            if (glass.setFraction(value)) return@launch
            dragged.value = null
            mutableMessage.value = if (pluginName == null) {
                "The intensity could not be saved."
            } else {
                "The intensity could not be saved for $pluginName. " +
                    "Check that it still holds the Storage permission in Settings → Plugins."
            }
        }
    }

    /**
     * Puts the material back where its author left it.
     *
     * Not the same as dragging to the middle: the authored position is wherever the plugin's own
     * setting *defaults* to, which may be anywhere on the track and is the only position that is
     * guaranteed to be the one its author designed the theme around.
     */
    fun resetToAuthored() {
        viewModelScope.launch {
            dragged.value = null
            if (!glass.resetToAuthored()) {
                mutableMessage.value = "This theme's intensity could not be reset."
            }
        }
    }

    fun clearMessage() {
        mutableMessage.value = null
    }

    private companion object {
        /** "As the author wrote it": scaling a block by one changes nothing. */
        const val AUTHORED = 1f

        /** Two floats that agree to this are the same number after a store round trip. */
        const val SETTLED_TOLERANCE = 0.005f
    }
}