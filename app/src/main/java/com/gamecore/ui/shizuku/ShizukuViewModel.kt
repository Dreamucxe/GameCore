package com.gamecore.ui.shizuku

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.model.RootState
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.GrantOutcome
import com.gamecore.core.shizuku.SelfGrantablePermission
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.capabilityTone
import com.gamecore.ui.components.readoutOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Shizuku screen's brain: connection state, proof that the shell works, and what it changes here.
 *
 * The one screen where "it says connected" is not good enough. A permission granted before a reboot is
 * still recorded by Shizuku after it, while the service behind it is gone, so this ViewModel offers
 * [verify] — which runs a command and reports the uid that answered — and the screen leads with that
 * rather than with the permission state.
 *
 * §24A.9 is why [verify] stores an [Observed] of one short string. The command's output is not logged
 * and not kept; the uid is extracted in [ShizukuManager] and the rest is dropped there.
 */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizuku: ShizukuManager,
    private val permissions: PermissionChecker,
    private val capabilityChecker: DeviceCapabilityChecker,
) : ViewModel() {

    /** The parts this ViewModel polls rather than observes: nothing pushes a root check at us. */
    private data class LocalState(
        val verification: Observed<String> = Observed.awaitingSample(NOT_RUN_YET),
        val isVerifying: Boolean = false,
        val isRequesting: Boolean = false,
        val root: RootState = RootState.NOT_DETECTED,
        val grants: List<GrantOffer> = emptyList(),
        val isGranting: Boolean = false,
        val isInstalled: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<ShizukuUiState> = combine(
        shizuku.state,
        capabilityChecker.capabilities,
        local,
    ) { connection, capabilities, own ->
        ShizukuUiState(
            shizuku = connection,
            verification = own.verification,
            isVerifying = own.isVerifying,
            isRequesting = own.isRequesting,
            capabilities = capabilities,
            root = own.root,
            controlRows = controlRowsOf(capabilities),
            readingRows = readingRowsOf(capabilities),
            grants = own.grants,
            isGranting = own.isGranting,
            isInstalled = own.isInstalled || connection.isInstalled,
            message = own.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = ShizukuUiState(),
    )

    init {
        reload(full = false)
    }

    /**
     * Re-reads everything cheap on resume.
     *
     * The likeliest reason a user is coming back to this screen is that they just started Shizuku's
     * service in the other app, and nothing tells GameCore about that — the binder simply becomes
     * available. So a resume re-asks rather than trusting what it had.
     */
    fun onResume() = reload(full = false)

    /** The full probe, from the screen's own button: for after granting something in Settings. */
    fun recheck() = reload(full = true)

    private fun reload(full: Boolean) {
        viewModelScope.launch {
            shizuku.refresh()
            if (full) capabilityChecker.refresh() else capabilityChecker.current()
            local.value = local.value.copy(
                root = shizuku.rootState(),
                grants = grantOffers(),
                isInstalled = shizuku.managerLaunchIntent() != null,
            )
        }
    }

    /**
     * Asks Shizuku for permission, and then proves it.
     *
     * The verification is not optional politeness: `requestPermission` returning granted means the user
     * pressed allow, which is a different fact from the binder working. A device that has just resumed
     * from a reboot can produce the first without the second.
     */
    fun requestPermission() {
        if (local.value.isRequesting) return
        local.value = local.value.copy(isRequesting = true, message = null)
        viewModelScope.launch {
            val result = shizuku.requestPermission()
            capabilityChecker.invalidate()
            capabilityChecker.refresh()
            local.value = local.value.copy(
                isRequesting = false,
                grants = grantOffers(),
                message = if (result.isUsable) null else result.explanation,
            )
            if (result.isUsable) verify()
        }
    }

    /**
     * Runs one command through the shell and keeps the uid it answered with.
     *
     * `id` was chosen because its answer is also the proof that this is not root: uid 2000 is the shell
     * user. Nothing else from the command survives the call.
     */
    fun verify() {
        if (local.value.isVerifying) return
        local.value = local.value.copy(isVerifying = true, message = null)
        viewModelScope.launch {
            val observed = shizuku.verify()
            local.value = local.value.copy(isVerifying = false, verification = observed)
            capabilityChecker.invalidate()
            capabilityChecker.current()
        }
    }

    /**
     * Sets one of the two app-op accesses directly, then checks whether Android agrees.
     *
     * The check is the point. `appops set` exits zero on builds that then carry on reporting the op as
     * denied, and reporting that as success would send the user off to a screen that immediately fails on
     * a `SecurityException`. When the write does not land, the message says so and points at Settings.
     */
    fun grant(access: SelfGrantablePermission) {
        if (local.value.isGranting) return
        local.value = local.value.copy(isGranting = true, message = null)
        viewModelScope.launch {
            val outcome = shizuku.grantSelfAccess(access)
            capabilityChecker.invalidate()
            capabilityChecker.refresh()
            val offers = grantOffers()
            val landed = offers.firstOrNull { it.access == access }?.isGranted == true
            local.value = local.value.copy(
                isGranting = false,
                grants = offers,
                message = grantMessage(access, outcome, landed),
            )
        }
    }

    private fun grantMessage(
        access: SelfGrantablePermission,
        outcome: GrantOutcome,
        landed: Boolean,
    ): String = when (outcome) {
        GrantOutcome.Granted -> if (landed) {
            "${access.userLabel} is on."
        } else {
            "The shell accepted the command, but Android still reports ${access.userLabel} as off. " +
                "Some builds only honour it after a restart of GameCore, and some not at all — the " +
                "Permissions screen opens the Settings page that always works."
        }
        GrantOutcome.NotConnected ->
            "Shizuku is not connected, so GameCore cannot set ${access.userLabel} itself. The " +
                "Permissions screen opens the Settings page for it."
        is GrantOutcome.Failed -> "${access.userLabel} could not be set: ${outcome.detail}"
    }

    /**
     * Performs the state's own next step, and hands back an intent if starting one is the step.
     *
     * The states each have exactly one sensible action and the screen should not have to know which is a
     * dialog and which is another app, so it presses one button and starts whatever comes back. Null with
     * a message set is the case where the manager app has gone missing between the state read and the
     * press — an uninstall while this screen was open — which is rare and still not a crash.
     */
    fun onPrimaryAction(): Intent? = when (state.value.shizuku) {
        ShizukuState.NOT_INSTALLED -> shizuku.installIntent()
        ShizukuState.INSTALLED_NOT_RUNNING,
        ShizukuState.VERSION_UNSUPPORTED,
        -> shizuku.managerLaunchIntent() ?: report(MANAGER_MISSING)
        ShizukuState.RUNNING_PERMISSION_UNKNOWN,
        ShizukuState.RUNNING_PERMISSION_DENIED,
        -> {
            requestPermission()
            null
        }
        ShizukuState.RUNNING_PERMISSION_GRANTED -> null
    }

    /** For the "How Shizuku works" link, which is the same page whether it is installed or not. */
    fun openSetupGuide(): Intent = shizuku.installIntent()

    /** The screen calls this when `startActivity` threw, so the failure is stated rather than silent. */
    fun onIntentFailed() {
        report(LAUNCH_FAILED)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private fun report(message: String): Intent? {
        local.value = local.value.copy(message = message)
        return null
    }

    // ---------------------------------------------------------------------- the three direct grants

    private fun grantOffers(): List<GrantOffer> = SelfGrantablePermission.entries.map { access ->
        GrantOffer(
            access = access,
            isGranted = isGranted(access),
            detail = when (access) {
                SelfGrantablePermission.PACKAGE_USAGE_STATS -> USAGE_ACCESS_DETAIL
                SelfGrantablePermission.WRITE_SETTINGS -> WRITE_SETTINGS_DETAIL
                SelfGrantablePermission.WRITE_SECURE_SETTINGS -> WRITE_SECURE_SETTINGS_DETAIL
            },
        )
    }

    private fun isGranted(access: SelfGrantablePermission): Boolean = when (access) {
        SelfGrantablePermission.PACKAGE_USAGE_STATS -> permissions.hasUsageAccess()
        SelfGrantablePermission.WRITE_SETTINGS -> permissions.hasWriteSettings()
        SelfGrantablePermission.WRITE_SECURE_SETTINGS -> permissions.hasWriteSecureSettings()
    }

    // ------------------------------------------------------------------------ the capability report

    /**
     * The eight controls the summary counts, in the order they are counted.
     *
     * §29 asks for a report of what this device can do rather than a claim about what the app can do, so
     * every row is the capability checker's own answer. Nothing here consults `Build.MANUFACTURER`.
     */
    private fun controlRowsOf(capabilities: DeviceCapabilities): List<Readout> = listOf(
        refreshRateRow(capabilities),
        statusRow("Brightness", capabilities.brightnessControl, BRIGHTNESS_DETAIL),
        statusRow("Rotation lock", capabilities.rotationControl, ROTATION_DETAIL),
        statusRow("Screen timeout", capabilities.screenTimeoutControl, TIMEOUT_DETAIL),
        statusRow("Do not disturb", capabilities.doNotDisturbControl, DND_DETAIL),
        statusRow("Media volume", capabilities.volumeControl, VOLUME_DETAIL),
        statusRow("Battery saver", capabilities.batterySaverControl, BATTERY_SAVER_DETAIL),
        statusRow("Animation scales", capabilities.animationScaleControl, ANIMATION_DETAIL),
    )

    private fun readingRowsOf(capabilities: DeviceCapabilities): List<Readout> = listOf(
        frameTimingRow(capabilities),
        readableRow("Processor use", capabilities.cpuUsageReadable, CPU_DETAIL),
        readableRow("Per-core frequencies", capabilities.perCoreFrequenciesReadable, CORES_DETAIL),
        readableRow("Temperature", capabilities.temperatureReadable, TEMPERATURE_DETAIL),
        readableRow("Throttling status", capabilities.thermalStatusSupported, THERMAL_DETAIL),
        statusRow("Which app is in front", capabilities.gameDetection, DETECTION_DETAIL),
        statusRow("Overlay windows", capabilities.overlay, OVERLAY_DETAIL),
        statusRow("Screen capture", capabilities.screenCapture, CAPTURE_DETAIL),
        statusRow("Flashlight", capabilities.torch, TORCH_DETAIL),
    )

    private fun statusRow(label: String, status: CapabilityStatus, detail: String): Readout = readoutOf(
        label = label,
        value = status.label,
        detail = detail,
        tone = capabilityTone(status),
    )

    private fun readableRow(label: String, isReadable: Boolean, detail: String): Readout = readoutOf(
        label = label,
        value = if (isReadable) "Readable" else "Not on this device",
        detail = detail,
        tone = if (isReadable) Tone.Good else Tone.Muted,
    )

    /**
     * The refresh-rate row, which reports the mechanism rather than a yes.
     *
     * Naming it matters here more than anywhere else on the screen: [RefreshRateMechanism.WINDOW_PREFERENCE]
     * is a real API that changes nothing a game can see, and a row saying "Available" for it would be the
     * §24B lie. It is amber rather than green — the panel has rates to choose between and GameCore cannot
     * reach them — and grey on a panel that only has one, where nothing is missing.
     */
    private fun refreshRateRow(capabilities: DeviceCapabilities): Readout {
        val mechanism = capabilities.refreshRateMechanism
        val rates = capabilities.supportedRefreshRates
        val works = mechanism != RefreshRateMechanism.NONE &&
            mechanism != RefreshRateMechanism.WINDOW_PREFERENCE
        return readoutOf(
            label = "Refresh rate",
            value = mechanism.label,
            detail = if (rates.size > 1) {
                "This panel reports ${rates.joinToString(" · ") { Formatters.hertz(it) }}."
            } else {
                "This panel reports one rate, so there is nothing to switch between."
            },
            tone = when {
                works -> Tone.Good
                rates.size > 1 -> Tone.Warning
                else -> Tone.Muted
            },
        )
    }

    /** §24B's FPS row. "Not available" is the answer on most devices and it is written as such. */
    private fun frameTimingRow(capabilities: DeviceCapabilities): Readout {
        val frameRate = capabilities.frameRate
        return readoutOf(
            label = "Game frame timing",
            value = if (frameRate.canMeasureGame) "Available" else "Not available",
            detail = frameRate.explanation,
            tone = if (frameRate.canMeasureGame) Tone.Good else Tone.Muted,
        )
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val NOT_RUN_YET = "GameCore has not run a command through the shell yet."

        const val MANAGER_MISSING =
            "The Shizuku app could not be opened — it may have been uninstalled since this screen was " +
                "last checked. Press Re-check to look again."

        const val LAUNCH_FAILED =
            "Android would not open that screen on this device. It can still be reached from Settings."

        const val USAGE_ACCESS_DETAIL =
            "Lets GameCore see which app is in the foreground, which is how a profile applies itself " +
                "when its game starts and comes back off when the game closes."

        const val WRITE_SETTINGS_DETAIL =
            "Lets GameCore write brightness, screen timeout, rotation and the display's refresh-rate " +
                "bounds — the settings Android's own display page writes."

        const val WRITE_SECURE_SETTINGS_DETAIL =
            "Lets GameCore write the display's colour keys — night shift, colour mode, the colour-vision " +
                "filter and extra dimming — which is what the colour correction screen applies. Android " +
                "never grants this to an app on its own, so Shizuku or adb is the only way to hold it."

        const val BRIGHTNESS_DETAIL = "Writes the system brightness, and the automatic-brightness switch."
        const val ROTATION_DETAIL = "Holds the screen in one orientation for the length of a session."
        const val TIMEOUT_DETAIL = "Extends the screen timeout while a game is running, then puts it back."
        const val DND_DETAIL = "Silences notifications for the session. Needs Do Not Disturb access."
        const val VOLUME_DETAIL = "Sets the media stream. Never touches the alarm or call streams."
        const val BATTERY_SAVER_DETAIL =
            "Turns battery saver off for a session, or on for a battery-saver profile. The switch is not " +
                "reachable without ADB-level access on most builds."
        const val ANIMATION_DETAIL =
            "Window, transition and animator scales. Lowering them shortens the system's own animations, " +
                "which is the closest thing to a real speed-up in this list — and it changes nothing " +
                "inside a game."

        const val CPU_DETAIL =
            "Total processor use across the device, from the kernel's own counters. Never per-app: " +
                "Android does not expose another app's share."
        const val CORES_DETAIL = "Each core's current clock, from the CPU frequency driver."
        const val TEMPERATURE_DETAIL = "A thermal zone or the battery sensor, whichever this build exposes."
        const val THERMAL_DETAIL =
            "The platform's own throttling level, which is what it says about its limits rather than " +
                "what a threshold guesses."
        const val DETECTION_DETAIL =
            "Reads usage events to tell when a game comes to the front. Needs usage access."
        const val OVERLAY_DETAIL =
            "Draws the floating button, the pill and the crosshair over other apps. Needs the " +
                "draw-over-other-apps permission."
        const val CAPTURE_DETAIL =
            "Screenshots and recording, both through Android's own capture consent — GameCore never " +
                "reads the screen without it."
        const val TORCH_DETAIL = "Switches the camera flash on, where the device has one."
    }
}
