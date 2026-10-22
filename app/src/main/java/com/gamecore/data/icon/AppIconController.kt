package com.gamecore.data.icon

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.plugin.ActiveIcon
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.icon.AppIconSlot
import com.gamecore.data.plugin.SecurePluginGrantStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The one thing that answers "which icon should the launcher be showing?" and makes it so.
 *
 * Three stores disagree by design and this reconciles them:
 *
 *  * the **system** — which `activity-alias` is enabled, read from `PackageManager` every time, and
 *    never mirrored in storage, because the launcher can change it without asking (a theme, a
 *    restore, an OEM cleanup) and a cached answer would then be a lie;
 *  * the **user's own choice** — a slot kept in the encrypted plugin store, which is what the app
 *    returns to when no plugin is supplying an icon;
 *  * a **plugin's request** — an [ActiveIcon] the user accepted, which outranks the user's slot while
 *    it remains valid.
 *
 * The precedence is deliberately one-directional: a plugin's request wins only while the registry
 * still resolves it. Disable the plugin, revoke `change_icon`, uninstall it, break its `icon.json` —
 * the request drops to null and the icon falls back to the user's own slot, without the app needing
 * to be told which of those happened.
 *
 * Every write is idempotent: [AppIconApplier.apply] returns early when the requested slot is already
 * the effective one, so [reconcile] can run on every start without touching the package state.
 */
@Singleton
class AppIconController @Inject constructor(
    private val registry: PluginRegistry,
    private val store: SecurePluginGrantStore,
    private val applier: AppIconApplier,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * What the picker draws from.
     *
     * [current] is the truth from the system; [userChoice] is what "reset" returns to; [offers] is
     * every plugin allowed to ask; [inForce] is the accepted one, or null.
     */
    data class State(
        val current: AppIconSlot = AppIconSlot.FALLBACK,
        val userChoice: AppIconSlot = AppIconSlot.FALLBACK,
        val offers: List<ActiveIcon> = emptyList(),
        val inForce: ActiveIcon? = null,
        val loaded: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Re-reads all three sources. Cheap: one binder call and two in-memory flows. */
    suspend fun refresh(): State = withContext(io) {
        val next = State(
            current = applier.currentSlot(),
            userChoice = store.selectedIconSlot(),
            offers = registry.iconOffers.first(),
            inForce = registry.activeIcon.first(),
            loaded = true,
        )
        mutableState.value = next
        next
    }

    /**
     * The app-start pass.
     *
     * Two jobs. If the accepted plugin icon no longer resolves, drop the request rather than leave a
     * dead selection in storage. And if nothing is in force, make sure the launcher is wearing the
     * user's own slot — the system and our storage can disagree after a restore, and the icon the
     * user sees is the one the system has, not the one we wrote down.
     */
    suspend fun reconcile(): State = withContext(io) {
        val accepted = registry.activeIcon.first()
        // A request that no longer resolves is dropped rather than left in storage looking like the
        // user's choice.
        if (store.selectedIconPluginId() != null && accepted == null) registry.resetToPluginIcon()
        val target = accepted?.spec?.slot ?: store.selectedIconSlot()
        applier.apply(target)
        refresh()
    }

    /** The user picked a slot by hand, which is also a decision to stop using a plugin's icon. */
    suspend fun chooseSlot(slot: AppIconSlot): State = withContext(io) {
        store.setSelectedIconSlot(slot)
        if (store.selectedIconPluginId() != null) registry.resetToPluginIcon()
        applier.apply(slot)
        refresh()
    }

    /**
     * Accepts a plugin's request.
     *
     * Returns the state after the attempt: if the registry refused the selection (the plugin stopped
     * being runnable between the tap and the write), [State.inForce] is null and nothing was applied.
     */
    suspend fun acceptPluginIcon(pluginId: String): State = withContext(io) {
        registry.setActiveIcon(pluginId)
        val accepted = registry.activeIcon.first()
        if (accepted != null) applier.apply(accepted.spec.slot)
        refresh()
    }

    /** Drops a plugin's request and returns to the user's own slot. */
    suspend fun resetToUserChoice(): State = withContext(io) {
        registry.resetToPluginIcon()
        applier.apply(store.selectedIconSlot())
        refresh()
    }

    /**
     * Asks the launcher to pin a shortcut wearing the plugin's artwork.
     *
     * A separate act from every other method here, and not part of [acceptPluginIcon]: this creates a
     * new home-screen icon rather than changing the app's own, so it never happens as a side effect.
     */
    suspend fun pinArtwork(pluginId: String): AppIconApplier.PinResult = withContext(io) {
        val offer = registry.iconOffers.first().firstOrNull { it.pluginId == pluginId }
            ?: return@withContext AppIconApplier.PinResult.Unreadable
        if (!offer.spec.hasArtwork) return@withContext AppIconApplier.PinResult.Unreadable
        val bytes = registry.iconArtwork(pluginId) ?: return@withContext AppIconApplier.PinResult.Unreadable
        val label = offer.spec.label ?: offer.pluginName
        applier.pinArtwork(bytes, label)
    }
}
