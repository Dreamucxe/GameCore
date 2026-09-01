package com.gamecore.core.model

import com.gamecore.core.common.AccessLevel

/**
 * Shizuku's state, from GameCore's point of view.
 *
 * All six cases are distinguished because they need six different things said to
 * the user, and collapsing them into "Shizuku: no" is how an app ends up telling
 * someone to install something they already have. Only [RUNNING_PERMISSION_GRANTED]
 * is usable; the rest each have a specific next step, which is what
 * [actionLabel] carries.
 */
enum class ShizukuState(
    val label: String,
    val isUsable: Boolean,
    val explanation: String,
    val actionLabel: String?,
) {
    NOT_INSTALLED(
        label = "Not installed",
        isUsable = false,
        explanation = "Shizuku is a separate app. GameCore works without it — everything " +
            "Android allows an ordinary app to do is already available. Installing it adds " +
            "refresh-rate pinning on devices that ignore the standard API, animation-scale " +
            "control, and detailed thermal sensor names.",
        actionLabel = "How to install Shizuku",
    ),

    INSTALLED_NOT_RUNNING(
        label = "Installed, not running",
        isUsable = false,
        explanation = "Shizuku is installed but its service is not running. It has to be " +
            "started after every reboot, either through wireless debugging or by connecting " +
            "to a computer once.",
        actionLabel = "Open Shizuku",
    ),

    RUNNING_PERMISSION_UNKNOWN(
        label = "Running, permission not requested",
        isUsable = false,
        explanation = "Shizuku is running. GameCore has not asked it for permission yet.",
        actionLabel = "Request permission",
    ),

    RUNNING_PERMISSION_DENIED(
        label = "Running, permission denied",
        isUsable = false,
        explanation = "Permission was declined. GameCore will keep using the standard " +
            "Android paths. You can grant it later from Shizuku's own app list.",
        actionLabel = "Request again",
    ),

    RUNNING_PERMISSION_GRANTED(
        label = "Connected",
        isUsable = true,
        explanation = "GameCore can use ADB-level access. This is not root: it is the same " +
            "authority `adb shell` has, and it ends when Shizuku stops.",
        actionLabel = null,
    ),

    /** A Shizuku older than v11, whose permission model was removed. */
    VERSION_UNSUPPORTED(
        label = "Version too old",
        isUsable = false,
        explanation = "This version of Shizuku predates the permission API GameCore uses. " +
            "Updating Shizuku will fix it.",
        actionLabel = "Open Shizuku",
    ),
    ;

    /** True when the manager app exists, whatever state its service is in. */
    val isInstalled: Boolean get() = this != NOT_INSTALLED
}

/**
 * Whether the device is rooted, which GameCore *detects* and never uses.
 *
 * The security requirements ask for root-awareness rather than root support, and
 * the reason is specific: a capability check assumes the sandbox is intact. On a
 * rooted device an app cannot know whether a value it read was produced by the
 * platform or by a module that rewrote it, and a "0 modes supported" answer might
 * be a kernel the user themselves patched. So root is reported, and used to add a
 * caveat to readings — nothing more. GameCore never runs `su`, never asks for it,
 * and holds no code path that would spawn it.
 */
enum class RootState(val label: String, val detected: Boolean) {
    NOT_DETECTED("Not detected", false),

    /** An `su` binary is present. Never executed, so this is as far as detection goes. */
    SU_BINARY_PRESENT("Root binary present", true),

    /** A root-manager package is installed. */
    MANAGER_INSTALLED("Root manager installed", true),

    /**
     * The build itself is a userdebug/eng image, or `ro.debuggable` is set. Not root
     * as such, but the same caveat applies: the sandbox is not the one a stock
     * device has.
     */
    DEBUGGABLE_BUILD("Debuggable build", true),
    ;

    val warning: String?
        get() = if (!detected) {
            null
        } else {
            "This device appears to be rooted or running a debuggable build. GameCore does " +
                "not use root and never will, but readings from a device whose system " +
                "partitions can be modified cannot be guaranteed to be what the platform " +
                "itself would report."
        }
}

/**
 * The single access summary the whole app reasons about.
 *
 * Gathered once by the capability layer and passed down, so that a screen deciding
 * whether to offer refresh-rate pinning and a service deciding whether to verify a
 * mode change against `dumpsys` are looking at the same facts.
 */
data class AccessSummary(
    val shizuku: ShizukuState,
    val root: RootState,
    val hasUsageAccess: Boolean,
    val hasWriteSettings: Boolean,
    val hasNotificationPolicy: Boolean,
    val hasOverlayPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val isIgnoringBatteryOptimisations: Boolean,
) {
    /** The highest authority actually available right now. Never ROOT: root is not used. */
    val effective: AccessLevel
        get() = if (shizuku.isUsable) AccessLevel.SHIZUKU else AccessLevel.NORMAL

    fun satisfies(required: AccessLevel): Boolean = effective satisfies required

    companion object {
        /** Initial UI state, before anything has been evaluated. Claims nothing. */
        val UNKNOWN = AccessSummary(
            shizuku = ShizukuState.NOT_INSTALLED,
            root = RootState.NOT_DETECTED,
            hasUsageAccess = false,
            hasWriteSettings = false,
            hasNotificationPolicy = false,
            hasOverlayPermission = false,
            hasNotificationPermission = false,
            isIgnoringBatteryOptimisations = false,
        )
    }
}
