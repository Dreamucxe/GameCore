package com.gamecore.ui.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.ApiResult
import com.gamecore.core.plugin.DataScope
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginInstallPlan
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingValue
import com.gamecore.ui.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The consent screen's state holder: one plugin, what it asks for, and the only four things the user
 * can do about it.
 *
 * Everything this class is allowed to do to a plugin is a method on [PluginRegistry], and that is the
 * point of taking the interface rather than the implementation. A ViewModel behind the screen where
 * permissions are granted could, with a concrete registry, reach the grant store and write a grant
 * without the user's switch moving — so it is handed the narrow port instead, and the list a reviewer
 * has to check is the dozen methods on the interface rather than the whole surface of a class.
 *
 * §24's honesty rule shapes three decisions here:
 *
 *  1. **Nothing is optimistically mutated.** `enabled`, `suspended` and the stored grant are only ever
 *     written from a [PluginRegistry.installed] emission. A switch that moved because it was tapped
 *     would be reporting the tap; this screen reports the store. The one piece of local state is the
 *     *draft* grant, which is explicitly labelled as unsaved.
 *  2. **A refusal names its reason.** [useThisTheme] has three distinct ways to fail and says which
 *     one applies, because "could not apply theme" leaves the user with nothing to act on.
 *  3. **Enabling with an unsaved edit saves first and says so**, rather than starting the plugin
 *     under the grant the user had before they touched anything.
 *
 * The id arrives as a nav argument, which is to say from outside: a missing or blank one resolves to
 * the "no longer installed" state rather than throwing, and so does an id that names nothing. A
 * consent screen that crashes on a stale back-stack entry is a consent screen the user re-enters with
 * no idea what state their grants are in.
 */
@HiltViewModel
class PluginDetailViewModel @Inject constructor(
    private val registry: PluginRegistry,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * The plugin this screen is about, as a plain string.
     *
     * Read as a `String` and trimmed, never coerced: the route declares a string argument and a plugin
     * id is a package-shaped name, so there is no numeric fallback to attempt. Blank means the screen
     * was opened without one, which is the same outcome as an id nothing matches.
     */
    private val pluginId: String = savedStateHandle.get<String>(Destination.ARG_PLUGIN_ID)?.trim().orEmpty()

    private val editing = MutableStateFlow(PluginDetailUiState())
    val state: StateFlow<PluginDetailUiState> = editing.asStateFlow()

    /**
     * The package digest whose sections have already been read.
     *
     * [PluginRegistry.sectionsOf] re-reads and re-parses a package from disk, and the installed flow
     * emits on every enable, suspension and grant write — so without this the theme and the rules
     * would be parsed again every time the user moved a switch. Keyed on the digest rather than a
     * boolean so an *update* to the plugin, which changes the bytes, does re-read.
     */
    private var sectionsFor: String? = null

    init {
        if (pluginId.isEmpty()) {
            editing.value = PluginDetailUiState(isLoading = false, notFound = true)
        } else {
            observe()
        }
    }

    /**
     * Follows the registry for as long as the screen is alive.
     *
     * Three flows at once rather than three collectors, because all three contribute to one frame:
     * which plugin (and its stored grant), whether the first disk read has finished, and whether this
     * plugin is the one currently supplying the app's theme. Collected separately they would land as
     * three recompositions showing two intermediate states that were never true together.
     */
    private fun observe() {
        viewModelScope.launch {
            combine(
                registry.installed,
                registry.isLoaded,
                registry.activeTheme,
            ) { plugins, loaded, theme ->
                Triple(plugins.firstOrNull { it.id == pluginId }, loaded, theme?.pluginId == pluginId)
            }.collect { (plugin, loaded, isThemeSource) ->
                onRegistryChanged(plugin, loaded, isThemeSource)
            }
        }
    }

    /**
     * Rebuilds the state from one registry emission.
     *
     * The absent case is two different facts and they are not interchangeable: before the first disk
     * read has finished, "no match" means "not read yet" and the screen keeps its spinner; afterwards
     * it means the plugin is genuinely not installed — removed from the manager in another tab, or an
     * id that never existed — and the screen says so and offers only Back. Holding the last known copy
     * instead would leave the user granting permissions to something that is no longer there.
     */
    private fun onRegistryChanged(plugin: InstalledPlugin?, loaded: Boolean, isThemeSource: Boolean) {
        if (plugin == null) {
            sectionsFor = null
            editing.value = if (loaded) {
                PluginDetailUiState(isLoading = false, notFound = true)
            } else {
                editing.value.copy(isLoading = true)
            }
            return
        }
        val previous = editing.value
        editing.value = previous.copy(
            isLoading = false,
            notFound = false,
            plugin = plugin,
            plan = PluginInstallPlan.from(plugin.manifest, plugin.sha256),
            draft = draftAgainst(previous, plugin.grant),
            saved = plugin.grant,
            enabled = plugin.enabled,
            suspended = plugin.suspended,
            suspensionDetail = plugin.suspensionDetail,
            isThemeSource = isThemeSource,
            hasAutomationHistoryAccess = DataScope.AUTOMATION_HISTORY in registry.grantedScopesFor(pluginId),
            message = noteForStoredGrant(previous, plugin.grant) ?: previous.message,
        )
        if (sectionsFor != plugin.sha256) {
            sectionsFor = plugin.sha256
            // Off the collector: parsing a package is disk work, and holding the collect lambda in it
            // would stall every later emission behind one read.
            viewModelScope.launch { loadSections() }
        }
    }

    /**
     * Which grant the switches should show now that [stored] has come back from the registry.
     *
     * An unsaved draft survives an emission that did not change the stored grant — the installed flow
     * also fires for an enable or a suspension, and losing the user's half-finished edit to one of
     * those would be maddening. It does **not** survive the stored grant actually moving: a draft is a
     * set of changes to a grant, and once that grant is gone the draft describes a state the user
     * never chose. That case is announced rather than absorbed, by [noteForStoredGrant].
     */
    private fun draftAgainst(previous: PluginDetailUiState, stored: PluginGrant): PluginGrant {
        val previousStored = previous.saved ?: return stored
        if (previousStored != stored) return stored
        return previous.draft ?: stored
    }

    /**
     * The note for a stored grant that changed under the user, or null when nothing of theirs was lost.
     *
     * Three outcomes, told apart without a "we are saving" flag: there was no pending edit, so nothing
     * was dropped; the pending edit *is* the new stored grant, which is this screen's own save landing;
     * or the two differ, which means something else wrote the grant and the draft has been replaced.
     */
    private fun noteForStoredGrant(previous: PluginDetailUiState, stored: PluginGrant): String? {
        val previousStored = previous.saved ?: return null
        if (previousStored == stored) return null
        val pending = previous.draft
        return when (pending) {
            null, previousStored, stored -> null
            else -> GRANT_REPLACED
        }
    }

    /** Reads the declared sections for the theme preview, the rule count and the settings schema. Absent is zero, not an error. */
    private suspend fun loadSections() {
        val sections = registry.sectionsOf(pluginId)
        editing.value = editing.value.copy(
            themePreview = sections?.theme,
            ruleCount = sections?.rules?.size ?: 0,
            screens = sections?.screens.orEmpty(),
            settingsSchema = sections?.settings,
        )
        sections?.settings?.let { loadSettingValues(it) }
    }

    /**
     * Reads the current value of every declared setting, defaulting any the gate refuses.
     *
     * Each read goes through [PluginRegistry.readPluginSetting], the one narrow door the registry opens
     * for settings, which funnels it past the same Storage gate every settings call uses. An Ok carries
     * the stored-or-default value; a denial (no Storage grant), a rate-limit or an error all fall back
     * to the definition's own default, so a control renders an honest default the user cannot change
     * rather than a blank. The ids come from the schema itself, so an unknown id never arises here.
     */
    private suspend fun loadSettingValues(schema: PluginSettingsSchema) {
        val values = LinkedHashMap<String, SettingValue>(schema.definitions.size)
        for (def in schema.definitions) {
            val result = registry.readPluginSetting(pluginId, schema, def.id)
            values[def.id] = (result as? ApiResult.Ok)?.value ?: def.default
        }
        editing.value = editing.value.copy(settingValues = values)
    }

    /**
     * Writes one setting and reflects what the store actually kept.
     *
     * The value a control sends is coerced behind the registry — a number clamped to the setting's
     * bounds, a string truncated to its length — and the [ApiResult.Ok] carries that coerced value,
     * which is what lands back in [PluginDetailUiState.settingValues] so a control that asked for 9999
     * settles on the 240 that was stored. A denied, rate-limited or errored write changes nothing: the
     * map keeps the value it had, so a control never shows a figure the store rejected. A call made
     * before the schema has loaded is a no-op, the guard every settings path shares.
     */
    fun onSetSetting(id: String, value: SettingValue) {
        val schema = editing.value.settingsSchema ?: return
        viewModelScope.launch {
            val result = registry.setPluginSetting(pluginId, schema, id, value)
            if (result is ApiResult.Ok) {
                editing.value = editing.value.copy(
                    settingValues = editing.value.settingValues + (id to result.value),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------- the grant

    /**
     * Grants or withholds one permission in the draft.
     *
     * A permission the manifest never requested cannot be granted here. The gate would deny it as
     * `NOT_REQUESTED` anyway, so storing one would be writing a grant entry that authorises nothing
     * while reading, to anyone who dumps the store, like approval the user gave.
     */
    fun togglePermission(permission: PluginPermission, on: Boolean) {
        val current = editing.value
        val draft = current.draft ?: return
        if (on && current.plugin?.manifest?.requests(permission) != true) return
        // Qualified deliberately: this class exposes an action with the same name as the pure rule in
        // PluginDetailState, and spelling the rule out in full keeps the two unmistakable here.
        editing.value = current.copy(
            draft = com.gamecore.ui.plugins.togglePermission(draft, permission, on),
        )
    }

    /** Opens or closes one scope inside a granted permission. See [toggleScope] for why order matters. */
    fun toggleScope(permission: PluginPermission, scope: String, on: Boolean) {
        val current = editing.value
        val draft = current.draft ?: return
        editing.value = current.copy(
            draft = com.gamecore.ui.plugins.toggleScope(draft, permission, scope, on),
        )
    }

    /**
     * The separate opt-in that HIGH-tier permissions need on top of their grant.
     *
     * Held in the draft like any other change, so turning it on is something the user saves rather than
     * something that takes effect under their hand while they are still reading what it does.
     */
    fun setAdvancedAccess(on: Boolean) {
        val current = editing.value
        val draft = current.draft ?: return
        editing.value = current.copy(draft = draft.copy(advancedAccess = on))
    }

    /**
     * Stores the draft.
     *
     * Nothing about the saved grant is written here: the registry stores it and the installed flow
     * brings it back, which is what makes the Save button's disappearance evidence that the write
     * landed rather than evidence that it was attempted.
     */
    fun saveGrant() {
        val current = editing.value
        val draft = current.draft ?: return
        if (!current.hasUnsavedChanges) return
        viewModelScope.launch {
            registry.grant(pluginId, draft)
            editing.value = editing.value.copy(message = GRANT_SAVED)
        }
    }

    /** Throws the draft away and shows the stored grant again. */
    fun revertGrant() {
        val current = editing.value
        val stored = current.saved ?: return
        editing.value = current.copy(draft = stored, message = null)
    }

    // ------------------------------------------------------------------------------- running it

    /**
     * Turns the plugin on or off.
     *
     * Enabling with an unsaved edit stores the grant first and says so, rather than starting the plugin
     * under the grant the user had before they touched anything — the screen would then be showing one
     * set of permissions while the plugin ran on another. The two refusals are stated rather than
     * silent: an incompatible plugin cannot run at all, and a HIGH-tier grant with the advanced opt-in
     * off is a grant the gate refuses on every call, so starting it would look like success and do
     * nothing.
     *
     * [PluginDetailUiState.enabled] is not touched here. It arrives from the registry.
     */
    fun setEnabled(enabled: Boolean) {
        val current = editing.value
        val plan = current.plan ?: return
        if (!enabled) {
            viewModelScope.launch { registry.setEnabled(pluginId, false) }
            return
        }
        if (!plan.canInstall) {
            editing.value = current.copy(message = plan.compatibility.reason)
            return
        }
        if (current.requiresAdvancedAccess) {
            editing.value = current.copy(message = INERT_HIGH_TIER)
            return
        }
        val pending = current.draft.takeIf { current.hasUnsavedChanges }
        viewModelScope.launch {
            if (pending != null) registry.grant(pluginId, pending)
            registry.setEnabled(pluginId, true)
            if (pending != null) {
                editing.value = editing.value.copy(message = SAVED_BEFORE_ENABLE)
            }
        }
    }

    /** Clears a this-run suspension so the supervisor's stop can be retried. The flow reports the result. */
    fun clearSuspension() {
        viewModelScope.launch { registry.clearSuspension(pluginId) }
    }

    // ------------------------------------------------------------------------------- the theme

    /**
     * Hands the app's appearance to this plugin, or says which of three reasons it will not.
     *
     * Checked worst-first, because the ordering decides which sentence a user with two problems reads.
     * Incompatible comes first: nothing on this screen clears it, so offering them a permission to
     * grant instead would send them down a path that ends in the same refusal. A theme the package
     * does not declare is the next most terminal. The grant is last because it is the one of the three
     * the user can fix here, and it is the one that must be read off the **saved** grant rather than
     * the draft — a theme applied from an unsaved switch would be the app restyling itself on the
     * strength of a decision that was never stored.
     */
    fun useThisTheme() {
        val current = editing.value
        val plugin = current.plugin ?: return
        val stored = current.saved ?: return
        val refusal = when {
            !plugin.manifest.compatibility.isRunnable -> plugin.manifest.compatibility.reason
            PluginSection.THEME !in plugin.manifest.declares -> NO_THEME_DECLARED
            current.themePreview == null -> THEME_NOT_READ
            !stored.has(PluginPermission.CHANGE_UI) -> CHANGE_UI_NOT_GRANTED
            else -> null
        }
        if (refusal != null) {
            editing.value = current.copy(message = refusal)
            return
        }
        viewModelScope.launch { registry.setActiveTheme(pluginId) }
    }

    /**
     * Returns the app to GameCore's own theme. Never refuses, and that is the whole reason it exists
     * as its own action: it is the way back out of a theme a plugin has made unreadable, so it writes
     * nothing it could fail to write and asks nothing of the plugin it is overriding.
     */
    fun useDefaultTheme() {
        viewModelScope.launch {
            registry.resetToDefaultTheme()
            editing.value = editing.value.copy(message = DEFAULT_THEME_RESTORED)
        }
    }

    // ------------------------------------------------------------------------------- removal

    /**
     * Asks before deleting, and explains the one case where there is nothing to delete.
     *
     * A bundled plugin's bytes are inside the APK and covered by the app's own signature, so there is
     * no file to remove — only the switch. Said in those words rather than shown as a greyed button,
     * because a disabled Remove with no explanation reads as a bug.
     */
    fun askRemove() {
        val plugin = editing.value.plugin ?: return
        if (!plugin.canBeRemoved) {
            editing.value = editing.value.copy(message = BUNDLED_CANNOT_BE_REMOVED)
            return
        }
        editing.value = editing.value.copy(pendingRemoval = true)
    }

    fun cancelRemove() {
        editing.value = editing.value.copy(pendingRemoval = false)
    }

    /**
     * Deletes the plugin, its grant, its stored values and its theme selection.
     *
     * No navigation happens here. The plugin leaves [PluginRegistry.installed], the collector flips the
     * screen to its "no longer installed" state, and the screen itself offers Back — a ViewModel that
     * popped a back stack would have to know what is underneath it.
     */
    fun confirmRemove() {
        viewModelScope.launch {
            val removed = registry.remove(pluginId)
            editing.value = editing.value.copy(
                pendingRemoval = false,
                message = if (removed) null else REMOVE_FAILED,
            )
        }
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    private companion object {
        const val GRANT_SAVED = "Saved. These are the permissions the plugin is checked against from now on."

        const val GRANT_REPLACED =
            "This plugin's stored permissions were changed somewhere else, so the switches now show " +
                "what is actually stored. Your unsaved changes were not kept."

        const val SAVED_BEFORE_ENABLE =
            "Your permission changes were saved first, so the plugin starts with exactly what is shown " +
                "above rather than what was stored before."

        const val INERT_HIGH_TIER =
            "An advanced permission is granted but advanced access is off, so every request it makes " +
                "would be refused. Turn advanced access on, or withhold that permission."

        const val NO_THEME_DECLARED =
            "This plugin does not carry a theme, so there is nothing to apply."

        const val THEME_NOT_READ =
            "This plugin declares a theme, but it could not be read back from its package. Nothing was " +
                "changed."

        const val CHANGE_UI_NOT_GRANTED =
            "\"Change the look\" is not granted. Grant it and save, then this theme can be applied."

        const val DEFAULT_THEME_RESTORED = "GameCore's own theme is in force."

        const val BUNDLED_CANNOT_BE_REMOVED =
            "This plugin ships inside GameCore, so there is no file to delete. Turning it off stops it " +
                "completely."

        const val REMOVE_FAILED =
            "This plugin could not be removed. Its files may already be gone — reopen the manager to see."
    }
}
