package com.gamecore.aimlab.runtime

/**
 * What this device can actually give the motion aim modes to read.
 *
 * The gyro challenge was built around a gyroscope, and a gyroscope is still the right answer when one is
 * present: it measures rotation directly. Some phones have no gyroscope at all, and the honest alternatives
 * are an accelerometer-derived tilt fallback or nothing. There is no third option, and nothing here pretends
 * a source exists when the platform did not report it.
 *
 * [choose] is the whole policy in one function, so the view model, the screen and the tests can never
 * disagree about which sensor wins.
 */
enum class AimInputSource(val label: String) {
    /** A real gyroscope. Rotation is measured, not inferred. */
    GYRO("Gyroscope"),

    /**
     * No gyroscope, but an accelerometer. Aim is read from the phone's lean against gravity.
     *
     * This cannot sense turning on the spot — gravity is unchanged by rotation about its own axis — so a
     * tilt run is filed separately from a gyro run rather than ranked against one.
     */
    TILT("Tilt"),

    /** Neither sensor. The screen explains the limitation instead of offering a run that cannot move. */
    NONE("No motion sensor"),
    ;

    companion object {
        /**
         * Picks the source the device can support, preferring the gyroscope.
         *
         * The order matters and is not cosmetic: tilt is a fallback for phones that cannot do the real
         * thing, not an alternative input method for phones that can.
         */
        fun choose(hasGyroscope: Boolean, hasAccelerometer: Boolean): AimInputSource = when {
            hasGyroscope -> GYRO
            hasAccelerometer -> TILT
            else -> NONE
        }
    }
}
