package com.gamecore.ui.charge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.BuildConfig
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.charge.ChargeBypassController
import com.gamecore.domain.charge.ChargeControlNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The charge-bypass screen's brain: probe the device, and drive the one manual switch.
 *
 * Deliberately thin. Everything hard about running the phone from the charger lives in
 * [ChargeBypassController] — the node probe, the record-before-write, the read-back, the back-out on an
 * unconfirmed stop — and this ViewModel does not second-guess any of it. It asks [ChargeBypassController.support]
 * whether the device can do this at all, asks [ChargeBypassController.isBypassedByGameCore] whether one of
 * GameCore's bypasses is already in effect, and turns the switch into an [ChargeBypassController.enable] or
 * [ChargeBypassController.restore] call, reflecting the [Observed] result as a sentence.
 *
 * Two things it does *not* do, both on purpose. It holds no `Context` and reaches for no repository: the
 * only package it ever names is GameCore's own [BuildConfig.APPLICATION_ID], which is the honest owner of a
 * bypass the user turned on by hand from this screen rather than one a game's profile applied, and it is the
 * name that lands in the restore ledger and any report. And it does not persist the switch anywhere —
 * automatic, per-game application is a field on the game's profile, wired separately, and this screen is the
 * manual override for the here and now.
 *
 * The switch position, [ChargeBypassUiState.isBypassed], follows the last *confirmed* action. A toggle that
 * comes back anything other than [Observed.Value] leaves the switch where it was and states why, mirroring the
 * controller's own rule that an unconfirmed charge-stop is undone rather than reported as on.
 */
@HiltViewModel
class ChargeBypassViewModel @Inject constructor(
    private val charge: ChargeBypassController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /** The parts polled from the controller rather than observed: nothing pushes a probe result at us. */
    private data class LocalState(
        val support: Observed<ChargeControlNode> = Observed.awaitingSample(NOT_PROBED),
        val isBypassed: Boolean = false,
        val isBusy: Boolean = false,
        val isProbing: Boolean = true,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<ChargeBypassUiState> = combine(
        preferences.settings,
        local,
    ) { settings, own ->
        ChargeBypassUiState(
            support = own.support,
            isBypassed = own.isBypassed,
            isBusy = own.isBusy,
            isProbing = own.isProbing,
            thermalWarningsOn = settings.showThermalWarnings,
            message = own.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = ChargeBypassUiState(),
    )

    init {
        refresh()
    }

    /**
     * Re-probes the device and re-reads whether a bypass is in effect.
     *
     * Called on resume for the same reason ShizukuScreen re-asks on resume: the likeliest change while the
     * user was away is that they started Shizuku's service in the other app, and nothing tells GameCore that
     * happened — the shell simply becomes available. So a resume re-probes rather than trusting the last
     * verdict, and a device that was "needs Shizuku" a moment ago can become supported without a restart.
     */
    fun onResume() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            local.value = local.value.copy(isProbing = true)
            val support = charge.support()
            val bypassed = charge.isBypassedByGameCore()
            local.value = local.value.copy(
                support = support,
                isBypassed = bypassed,
                isProbing = false,
            )
        }
    }

    /**
     * Turns the manual bypass on or off, and reflects what actually happened.
     *
     * On is [ChargeBypassController.enable] for GameCore's own package; off is
     * [ChargeBypassController.restore] with no captured value, which the controller resolves to the node's
     * normal (charging) value. The switch moves only on a confirmed [Observed.Value]; a shell that dropped
     * out or a write that could not be read back leaves it where it was and puts the reason in the banner.
     */
    fun toggle(on: Boolean) {
        if (local.value.isBusy) return
        local.value = local.value.copy(isBusy = true, message = null)
        viewModelScope.launch {
            val result = if (on) {
                charge.enable(BuildConfig.APPLICATION_ID)
            } else {
                charge.restore(previousValue = null, packageName = null)
            }
            val confirmed = result is Observed.Value
            local.value = local.value.copy(
                isBusy = false,
                isBypassed = if (confirmed) on else local.value.isBypassed,
                message = resultMessage(on, result),
            )
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    /**
     * The one sentence a toggle leaves behind.
     *
     * A confirmed result gets GameCore's own plain wording; anything short of one carries the controller's
     * device-specific detail through unchanged, because that is where the useful reason lives — which node
     * refused, or that the shell stopped answering — falling back to the shared honesty wording only if a
     * detail ever comes blank.
     */
    private fun resultMessage(turningOn: Boolean, result: Observed<ChargeControlNode>): String =
        when (result) {
            is Observed.Value -> if (turningOn) BYPASS_ON else BYPASS_OFF
            is Observed.Restricted -> result.detail.ifBlank { result.unavailabilityText() ?: GENERIC_FAIL }
            is Observed.Failed -> result.detail.ifBlank { result.unavailabilityText() ?: GENERIC_FAIL }
        }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val NOT_PROBED = "GameCore has not checked this device for a charge-control node yet."

        const val BYPASS_ON =
            "Running the phone from the charger. The battery is not charging while this is on — turn it " +
                "off, or let a game close, and charging comes straight back."

        const val BYPASS_OFF = "Charging is back on."

        const val GENERIC_FAIL = "That could not be changed on this device."
    }
}
