package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelConfigCodecTest {

    @Test
    fun `every durable wheel setting survives a round trip`() {
        val profile = WheelProfile(
            id = "sniper",
            name = "Sniper",
            sensitivity = 2.5f,
            xSensitivity = 1.25f,
            ySensitivity = 0.75f,
            radiusPx = 275,
            deadzonePx = 7,
            diameterDp = 144,
            opacity = 0.55f,
            smoothing = 0.35f,
            invertX = true,
            invertY = false,
            curve = WheelResponseCurve.PROGRESSIVE,
            style = WheelVisualStyle.NEON,
            accent = WheelAccent.VIOLET,
            locked = true,
            xFraction = 0.22f,
            yFraction = 0.71f,
            showVector = false,
            haptics = false,
        )
        val config = WheelConfig(
            profiles = listOf(WheelProfile.DEFAULT, profile),
            activeProfileId = profile.id,
            button = WheelButtonConfig(
                visible = false,
                xFraction = 0.82f,
                yFraction = 0.31f,
                sizeDp = 64,
                opacity = 0.7f,
                style = WheelButtonStyle.RING,
                icon = WheelButtonIcon.BOLT,
                accent = WheelAccent.AMBER,
                haptics = false,
                snapToEdge = false,
                idleFade = false,
            ),
            bindings = mapOf("com.example.game" to profile.id),
            autoSwitch = true,
            riskAcknowledged = true,
            precisionMultiplier = 0.4f,
            turboMultiplier = 1.75f,
        )

        assertEquals(config.normalised(), WheelConfigCodec.decode(WheelConfigCodec.encode(config)))
    }

    @Test
    fun `invalid input repairs values and drops unsafe bindings`() {
        val decoded = WheelConfigCodec.decode(
            """
            {
              "profiles": [{
                "id": "",
                "name": "",
                "sensitivity": 99,
                "radiusPx": -4,
                "opacity": -2,
                "curve": "UNKNOWN"
              }],
              "activeProfileId": "missing",
              "button": {
                "sizeDp": 2,
                "opacity": 9,
                "accent": "UNKNOWN"
              },
              "bindings": {
                "not a package": "default",
                "com.example.game": "missing"
              },
              "precisionMultiplier": -10,
              "turboMultiplier": 20
            }
            """.trimIndent(),
        )

        assertEquals(WheelProfile.DEFAULT_ID, decoded.activeProfileId)
        assertEquals(WheelProfile.MAX_SENSITIVITY, decoded.activeProfile.sensitivity)
        assertEquals(WheelProfile.MIN_RADIUS_PX, decoded.activeProfile.radiusPx)
        assertEquals(WheelProfile.MIN_OPACITY, decoded.activeProfile.opacity)
        assertEquals(WheelResponseCurve.LINEAR, decoded.activeProfile.curve)
        assertEquals(WheelButtonConfig.MIN_SIZE_DP, decoded.button.sizeDp)
        assertEquals(WheelButtonConfig.MAX_OPACITY, decoded.button.opacity)
        assertEquals(WheelAccent.CYAN, decoded.button.accent)
        assertTrue(decoded.bindings.isEmpty())
        assertEquals(WheelConfig.MIN_PRECISION_MULTIPLIER, decoded.precisionMultiplier)
        assertEquals(WheelConfig.MAX_TURBO_MULTIPLIER, decoded.turboMultiplier)
    }

    @Test
    fun `garbage and missing documents return safe defaults`() {
        assertEquals(WheelConfig.DEFAULT, WheelConfigCodec.decode(null))
        assertEquals(WheelConfig.DEFAULT, WheelConfigCodec.decode("not-json"))
        assertFalse(WheelConfigCodec.decode("{}").profiles.isEmpty())
    }

    @Test
    fun `injection risk is not accepted by default`() {
        assertFalse(WheelConfig.DEFAULT.riskAcknowledged)
        assertFalse(WheelConfigCodec.decode("{}").riskAcknowledged)
    }
}
