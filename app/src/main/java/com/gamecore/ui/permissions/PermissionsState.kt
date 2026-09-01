package com.gamecore.ui.permissions

import com.gamecore.core.permissions.GamePermission

/**
 * One catalogue entry, with this device's answer for it.
 *
 * The row carries the [GamePermission] rather than a copy of its wording: the same explanation appears on
 * this screen, in the Shizuku screen's prerequisites and in a handful of empty states, and a second copy is
 * a second thing to keep in step. What is added here is everything that is a property of *this device* —
 * whether it is granted, whether there is a page to send the user to, whether a dialog can be raised.
 *
 * [unavailableReason] is the honest answer for an access that does not exist on this version of Android.
 * §32 does not allow a button that cannot do anything, so those rows explain themselves instead of
 * offering one.
 */
data class PermissionRow(
    val permission: GamePermission,
    val isGranted: Boolean,
    val hasSettingsPage: Boolean,
    val canRequestDialog: Boolean,
    val unavailableReason: String? = null,
) {
    val isActionable: Boolean
        get() = !isGranted && unavailableReason == null && (hasSettingsPage || canRequestDialog)
}

/**
 * §21's permissions centre.
 *
 * [needsBatteryExemption] is §24B's Doze prompt, and it is a condition rather than a constant: it is true
 * only when the user has switched on something a throttled process breaks — session recording, background
 * monitoring, an overlay — and the exemption is not held. An install that does none of those has no reason
 * to grant it, and asking anyway is how a prompt becomes something people dismiss without reading.
 */
data class PermissionsUiState(
    val isLoaded: Boolean = false,
    val rows: List<PermissionRow> = emptyList(),
    val needsBatteryExemption: Boolean = false,
    /**
     * Set once the notification dialog has come back refused.
     *
     * Android stops showing that dialog after the second refusal and offers no way to ask whether it will
     * appear, so the row switches to the Settings page rather than leaving a button that would silently do
     * nothing on the third tap.
     */
    val notificationDialogRefused: Boolean = false,
    val message: String? = null,
) {
    /** Counted over the accesses that exist here, so a 60 Hz Android 12 phone is not shown "5 of 7". */
    val granted: Int get() = rows.count { it.isGranted && it.unavailableReason == null }

    val applicable: Int get() = rows.count { it.unavailableReason == null }

    val summary: String
        get() = if (!isLoaded) "Checking what this device has granted" else "$granted of $applicable granted"
}
