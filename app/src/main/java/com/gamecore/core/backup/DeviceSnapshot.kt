package com.gamecore.core.backup

/**
 * What device a backup was written on.
 *
 * The point of the section is narrow: it lets the restore screen say *where this came from* and,
 * more usefully, name the settings that may not mean the same thing on the device being restored
 * onto. It is a snapshot for comparison, not an identity — there is no serial, no account, no
 * advertising id, and nothing here is ever used to recognise a user. A file without one is still a
 * valid backup of everything else, which is why every field but [manufacturer] and [model] is
 * nullable and why the migration report treats an absent section as "unknown origin" rather than
 * "same device".
 *
 * The panel numbers are physical, not logical. A backup taken on a device whose display was already
 * overridden by `wm size` would otherwise record the override as the panel's own size and every
 * aspect preset would be recomputed from the wrong number on the way back.
 */
data class DeviceSnapshot(
    /** e.g. "Google". Sanitised on the way in, like every other third-party string in this app. */
    val manufacturer: String,
    /** e.g. "Pixel 7". The retail model, not the internal board name. */
    val model: String,
    /** The SDK level the backup was written on, or null when it could not be read. */
    val sdkInt: Int? = null,
    /** The panel's physical width in pixels, or null when it could not be read. */
    val panelWidthPixels: Int? = null,
    /** The panel's physical height in pixels, or null when it could not be read. */
    val panelHeightPixels: Int? = null,
    /** The highest refresh rate the panel advertises in Hz, or null when it could not be read. */
    val peakRefreshRateHz: Float? = null,
) {
    /** "Google Pixel 7", for the restore screen's "Coming from" line. */
    val label: String get() = "$manufacturer $model".trim().ifBlank { "an unnamed device" }
}
