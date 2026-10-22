package com.gamecore.data.plugin

import android.os.Build
import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.plugin.ActiveTheme
import com.gamecore.core.plugin.ApiResult
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingDef
import com.gamecore.core.plugin.theme.PluginGlass
import com.gamecore.core.plugin.theme.fractionOf
import com.gamecore.core.plugin.theme.glassIntensityScale
import com.gamecore.core.plugin.theme.resolvedGlass
import com.gamecore.core.plugin.theme.valueFor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The live glass material, and the Control Center's handle on it.
 *
 * This is the one place where a theme's `glass` block, the plugin's own intensity setting and the
 * user's finger on a slider all meet — and it exists because no two of those three can see each
 * other without it. The theme is a value the registry parses and caches; the setting lives in the
 * plugin's own encrypted store behind the API funnel; the slider is a Compose gesture. Threading a
 * read through all three at every panel would be absurd, so it is resolved once here and published
 * as one [StateFlow] that the shell and the Control Center both read.
 *
 * ## Why it is a singleton and not a screen's ViewModel
 *
 * Because two screens need the *same* answer. The material has to reach `GameCoreTheme` so every
 * card in the app is drawn from it, and the Control Center has to read the same value to draw its
 * readouts and write the same value back — and a slider whose feedback lags its own movement by a
 * round trip through a disk-backed store is a slider that feels broken. Resolving on the
 * application scope and caching the last written value is what makes the drag immediate.
 *
 * ## What it does not do
 *
 * It does not read the setting on every emission, and it does not poll. The value comes from the
 * store once per theme selection, and afterwards from what the Control Center has written — which is
 * the only thing in the app that can change it. A second writer would be a plugin writing its own
 * setting at runtime, which no section of the format can do.
 *
 * Nothing here is a permission bypass: every read and write goes through [PluginRegistry]'s narrow
 * doors, so the gate, the rate limit, the clamp and the audit trail all still apply. This class only
 * decides *when* to ask.
 */
@Singleton
class GlassMaterialStore @Inject constructor(
    private val registry: PluginRegistry,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** The Control Center's most recent write, so a drag is reflected without re-reading the store. */
    private val written = MutableStateFlow<Written?>(null)

    private data class Written(val pluginId: String, val fraction: Float)

    /**
     * The material in force, or null when no glass theme is active.
     *
     * Null is the common case and covers three different situations the UI does not have to tell
     * apart: no plugin theme selected, a theme that carries no `glass` block, and a block whose
     * every quantity has been faded to its floor. All three mean "GameCore is not wearing glass".
     */
    val state: StateFlow<GlassControl?> = combine(registry.activeTheme, written) { active, written ->
        active to written
    }
        .mapLatest { (active, written) -> resolve(active, written) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Moves the control to [fraction] — a 0..1 position on the slider — and reports whether the
     * plugin's store took it.
     *
     * False for a theme with no control, for a denied or rate-limited write, and for a fraction the
     * declaration cannot express; the caller draws that as the slider springing back rather than as
     * a silent no-op. The write is what is *stored*: the store's coercion is applied first, so a
     * slider dragged past an integer declaration's last step stores the last step rather than
     * failing.
     */
    suspend fun setFraction(fraction: Float): Boolean {
        val current = state.value ?: return false
        val def = current.setting ?: return false
        val id = current.settingId ?: return false
        val schema = current.schema ?: return false
        val value = valueFor(def, fraction) ?: return false
        return when (registry.setPluginSetting(current.pluginId, schema, id, value)) {
            is ApiResult.Ok -> {
                written.value = Written(current.pluginId, fraction)
                true
            }

            else -> false
        }
    }

    /** Puts the control back where the theme's author left it: the setting's own default. */
    suspend fun resetToAuthored(): Boolean {
        val current = state.value ?: return false
        val def = current.setting ?: return false
        val authored = fractionOf(def, def.default) ?: return false
        return setFraction(authored)
    }

    /**
     * The one resolution pass: the theme's block, the setting that scales it, and the value in force.
     *
     * Every failure inside is a *degradation* rather than a rejection, because the theme already
     * passed the registry's own check before it could reach here — so a plugin whose `settings.json`
     * has since become unreadable renders its material at the authored intensity, which is a whole
     * and correct material. The one case that is a real user-visible failure, a control the host
     * cannot read, was refused when the theme was loaded.
     */
    private suspend fun resolve(active: ActiveTheme?, written: Written?): GlassControl? {
        val theme = active?.theme ?: return null
        val authored = theme.resolvedGlass() ?: return null
        val pluginId = active.pluginId

        val wanted = authored.intensitySetting
        val section = if (wanted == null) null else registry.sectionsOf(pluginId)?.settings
        // The three non-null locals below are what the write path needs, and they are resolved as one
        // because they are only ever meaningful together: an id with no schema names nothing, and a
        // schema with no definition for that id describes no control. Destructured here rather than
        // carried as three nullable fields so the branch below cannot reach [readFraction] holding an
        // id that was never looked up.
        val settingId = wanted?.takeIf { section?.byId(it) != null }
        val schema = section?.takeIf { settingId != null }
        val def = settingId?.let { section?.byId(it) }
        if (settingId == null || schema == null || def == null || def.glassIntensityScale() == null) {
            // No control: the material is the theme's, whole and fixed. Not an error, and not
            // reported as one — a plugin is allowed to ship a material the user does not adjust.
            return GlassControl(
                pluginId = pluginId,
                pluginName = active.pluginName,
                authored = authored,
                material = authored,
                settingId = null,
                schema = null,
                setting = null,
                fraction = NO_CONTROL_FRACTION,
                blurAvailable = blurAvailable(),
            )
        }

        val fraction = if (written?.pluginId == pluginId) {
            written.fraction
        } else {
            readFraction(pluginId, schema, def, settingId)
        }
        return GlassControl(
            pluginId = pluginId,
            pluginName = active.pluginName,
            authored = authored,
            // `scaledBy` rather than a second code path: the material the panels are drawn from is
            // the theme's own block moved along its own axis, so a control can never invent a
            // material the format could not have described.
            material = authored.scaledBy(fraction),
            settingId = settingId,
            schema = schema,
            setting = def,
            fraction = fraction,
            blurAvailable = blurAvailable(),
        )
    }

    /** The stored value as a 0..1 fraction, or the authored intensity when it cannot be read. */
    private suspend fun readFraction(
        pluginId: String,
        schema: PluginSettingsSchema,
        def: SettingDef,
        settingId: String,
    ): Float = when (val read = registry.readPluginSetting(pluginId, schema, settingId)) {
        is ApiResult.Ok -> fractionOf(def, read.value) ?: AUTHORED_FRACTION
        // Denied, rate-limited or an unknown id: the theme's own intensity, which is always a
        // correct material. A Control Center that refused to draw would be worse than one that
        // draws the material the author designed.
        else -> AUTHORED_FRACTION
    }

    private fun blurAvailable(): Boolean = Build.VERSION.SDK_INT >= PluginGlass.FIRST_BLUR_SDK

    companion object {
        /**
         * The fraction a theme with no control is reported at.
         *
         * One, not zero: the material on screen is exactly what the theme asked for, so a slider
         * drawn at the bottom of its travel would be describing a material that is not there. The
         * Control Center hides the slider in this case rather than drawing it here.
         */
        const val NO_CONTROL_FRACTION = 1f

        /** The fraction the theme's authored block corresponds to. Scaling by one changes nothing. */
        const val AUTHORED_FRACTION = 1f
    }
}

/**
 * Everything the shell and the Control Center need about the material in force, resolved once.
 *
 * [authored] and [material] are both carried because the two questions are different and both get
 * asked: the readouts describe what the *theme* designed, and the panels are drawn from what the
 * *user* has dialled it to. [fraction] is the distance between them.
 *
 * [schema] and [setting] travel with the value rather than being looked up again at write time, so
 * the setting a slider writes is by construction the setting its own readout was drawn from — a
 * second lookup could resolve a different plugin's declaration after a theme switch and write a
 * value into the wrong namespace.
 */
data class GlassControl(
    val pluginId: String,
    val pluginName: String,
    /** The theme's block as its author wrote it. */
    val authored: PluginGlass,
    /** The block the panels are drawn from: [authored] scaled by [fraction]. */
    val material: PluginGlass,
    /** The setting the Control Center drives, or null when the theme shipped a fixed material. */
    val settingId: String?,
    val schema: PluginSettingsSchema?,
    val setting: SettingDef?,
    /** Where the control sits, 0..1. */
    val fraction: Float,
    /** Whether this device's OS can blur a backdrop at all — false below Android 12. */
    val blurAvailable: Boolean,
) {
    /** True when there is a slider to draw. */
    val adjustable: Boolean get() = settingId != null && setting != null

    /**
     * Whether the material on screen is actually blurred, as opposed to merely translucent.
     *
     * The Control Center states this rather than leaving the user to wonder why a theme advertised
     * frosted glass and delivered a tint. It is the same question the renderer asks — see
     * [PluginGlass.usesBackdropBlur] — asked once, in the one place the answer is shown, and it is
     * false on any device older than Android 12 however large a radius the theme asked for.
     */
    val backdropBlurred: Boolean get() = blurAvailable && material.blurRadiusDp > 0
}