package com.gamecore.data.repository

import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CpuAffinityPreset
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ScreenOrientationLock
import com.gamecore.data.repository.ProfileTransferCodec.PresetBundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProfileTransferCodec]'s round trip, its clamping and its hostile-input handling (§5, §2, §12), asserted
 * the way [com.gamecore.core.overlay.MacroCodecTest] asserts its own: on what a real `org.json` actually
 * parses, not on a paraphrase of it. `org.json` is on the unit-test classpath — the same fact that test
 * relies on — so a value written here is parsed here.
 *
 * The codec is the *importer* half of the §36 divide: [ProfileTransferCodec.decodeBody] and
 * [ProfileTransferCodec.decodeEnvelope] are total. A blank string, an object where an array belonged, a
 * package name that is not one, a number out of range, an enum name a newer build wrote — each costs the
 * offending field or the offending record and is counted in `rejectedProfiles`, never thrown. The recreate-
 * or-match-and-remap that turns the exported preset ids into local ones is [ProfileTransfer]'s job (it needs
 * the repositories); what is tested here is that the linkage the remap depends on survives the round trip —
 * a profile keeps the preset id it was exported with, and the referenced definition comes back under it.
 */
class ProfileTransferCodecTest {

    private fun bodyWithProfile(profileJson: String): JSONObject =
        JSONObject("""{"profiles":[$profileJson],"presets":{}}""")

    // --- Round trip -------------------------------------------------------------------------------

    @Test
    fun `a fully populated profile survives encode and decode unchanged`() {
        val profile = GameProfile(
            packageName = "com.example.game",
            label = "Example Game",
            isEnabled = false,
            targetRefreshRate = 120f,
            brightnessPercent = 70,
            rotationLock = ScreenOrientationLock.LANDSCAPE,
            screenTimeoutMillis = 600_000L,
            mediaVolumePercent = 40,
            enableDoNotDisturb = true,
            hudLayoutId = 7L,
            crosshairPresetId = 9L,
            colorPresetId = 11L,
            displaySize = DisplaySize(1080, 2400),
            performanceMode = PerformanceMode.PERFORMANCE,
            cpuAffinity = CpuAffinityPreset.PERFORMANCE_ONLY,
            thermalDownshiftEnabled = true,
            thermalLimitDeciCelsius = 420,
            thermalFloorRateHz = 60f,
        )
        val decoded = ProfileTransferCodec.decodeEnvelope(
            ProfileTransferCodec.encodeEnvelope(listOf(profile), PresetBundle()),
        )
        assertEquals(1, decoded.profiles.size)
        assertEquals(0, decoded.rejectedProfiles)
        assertEquals(profile, decoded.profiles.first())
    }

    @Test
    fun `a referenced preset comes back under the id the profile still carries, ready for the remap`() {
        val profile = GameProfile(
            packageName = "com.example.aim",
            label = "Aim",
            hudLayoutId = 7L,
            crosshairPresetId = 9L,
            colorPresetId = 11L,
            showCrosshair = true,
        )
        val bundle = PresetBundle(
            hud = listOf(
                HudLayout(id = 7L, name = "My HUD", widgets = listOf(HudWidget(id = "w1", stat = HudStat.CPU_USAGE))),
            ),
            crosshair = listOf(
                CrosshairPreset(id = 9L, name = "Sniper", design = CrosshairDesign.DOT, imagePath = "/data/local/x.png"),
            ),
            color = listOf(
                ColorPreset(id = 11L, name = "Night", correction = ColorCorrection(redGain = 30, blueGain = -30)),
            ),
        )
        val decoded = ProfileTransferCodec.decodeEnvelope(
            ProfileTransferCodec.encodeEnvelope(listOf(profile), bundle),
        )

        // The profile keeps the exported ids: the codec never remaps, so the importer can look each one up.
        val out = decoded.profiles.single()
        assertEquals(7L, out.hudLayoutId)
        assertEquals(9L, out.crosshairPresetId)
        assertEquals(11L, out.colorPresetId)

        // And each definition is present, keyed by that same exported id.
        assertEquals("My HUD", decoded.hud[7L]?.name)
        assertEquals(HudStat.CPU_USAGE, decoded.hud[7L]?.widgets?.single()?.stat)
        assertEquals(CrosshairDesign.DOT, decoded.crosshair[9L]?.design)
        // The device-local image path is the one field that cannot travel — it is dropped, not carried.
        assertNull(decoded.crosshair[9L]?.imagePath)
        assertEquals(30, decoded.color[11L]?.correction?.redGain)
        assertEquals(-30, decoded.color[11L]?.correction?.blueGain)
    }

    // --- decode is total --------------------------------------------------------------------------

    @Test
    fun `decode never throws and yields empty for anything it cannot parse`() {
        assertTrue(ProfileTransferCodec.decodeEnvelope(null).profiles.isEmpty())
        assertTrue(ProfileTransferCodec.decodeEnvelope("").profiles.isEmpty())
        assertTrue(ProfileTransferCodec.decodeEnvelope("   ").profiles.isEmpty())
        assertTrue(ProfileTransferCodec.decodeEnvelope("not json at all").profiles.isEmpty())
        assertTrue(ProfileTransferCodec.decodeEnvelope("[1,2,3]").profiles.isEmpty())
        assertTrue(ProfileTransferCodec.decodeBody(null).profiles.isEmpty())
        // An object where the profiles array belonged, and an array of the wrong shape, both survive.
        assertTrue(ProfileTransferCodec.decodeBody(JSONObject("""{"profiles":{},"presets":42}""")).profiles.isEmpty())
        assertTrue(ProfileTransferCodec.decodeBody(bodyWithProfile("1")).profiles.isEmpty())
    }

    // --- Clamping and wrong-typed numbers ---------------------------------------------------------

    @Test
    fun `numbers out of range are clamped to the model's own bounds, or dropped where there is no floor`() {
        val decoded = ProfileTransferCodec.decodeBody(
            bodyWithProfile(
                """{
                    "packageName":"com.example.g","label":"G",
                    "brightnessPercent":999,"mediaVolumePercent":-10,
                    "targetRefreshRate":9999.0,"screenTimeoutMillis":-5,
                    "thermalLimitDeciCelsius":99999,"thermalHysteresisDeciCelsius":9000,
                    "thermalSustainHotMillis":5000000
                }""",
            ),
        )
        val p = decoded.profiles.single()
        assertEquals(100, p.brightnessPercent)          // 0..100
        assertEquals(0, p.mediaVolumePercent)           // 0..100
        assertNull(p.targetRefreshRate)                 // outside 1..480 is not a rate — leave it alone
        assertNull(p.screenTimeoutMillis)               // a non-positive timeout is no timeout
        assertEquals(1_500, p.thermalLimitDeciCelsius)  // 0..1500
        assertEquals(500, p.thermalHysteresisDeciCelsius) // 0..500
        assertEquals(600_000L, p.thermalSustainHotMillis) // 0..600000
    }

    @Test
    fun `a wrong-typed number leaves the field alone rather than becoming a spurious zero`() {
        // org.json's opt* would coerce a non-number to 0 — a real device write. The codec rejects the type.
        val decoded = ProfileTransferCodec.decodeBody(
            bodyWithProfile(
                """{"packageName":"com.example.g","label":"G","brightnessPercent":"not a number","mediaVolumePercent":{}}""",
            ),
        )
        val p = decoded.profiles.single()
        assertNull(p.brightnessPercent)
        assertNull(p.mediaVolumePercent)
    }

    @Test
    fun `a number written as a string is still read, because a hand-edited file quotes its numbers`() {
        val decoded = ProfileTransferCodec.decodeBody(
            bodyWithProfile("""{"packageName":"com.example.g","label":"G","brightnessPercent":"55"}"""),
        )
        assertEquals(55, decoded.profiles.single().brightnessPercent)
    }

    // --- Unknown enums, and unrecoverable records ------------------------------------------------

    @Test
    fun `an enum name this build does not know degrades to null, and performanceMode to its default`() {
        val decoded = ProfileTransferCodec.decodeBody(
            bodyWithProfile(
                """{
                    "packageName":"com.example.g","label":"G",
                    "rotationLock":"SIDEWAYS","cpuAffinity":"OVERCLOCK",
                    "thermalStatusFloor":"MELTING","performanceMode":"TURBO"
                }""",
            ),
        )
        val p = decoded.profiles.single()
        assertNull(p.rotationLock)
        assertNull(p.cpuAffinity)
        assertNull(p.thermalStatusFloor)
        // performanceMode is non-nullable on the model, so an unknown name falls back rather than to null.
        assertEquals(PerformanceMode.BALANCED, p.performanceMode)
    }

    @Test
    fun `a record whose package name is not a package name is dropped and counted, the valid ones kept`() {
        val decoded = ProfileTransferCodec.decodeBody(
            JSONObject(
                """{"profiles":[
                    {"packageName":"not a package","label":"Bad"},
                    {"packageName":"com.example.ok","label":"Good"}
                ],"presets":{}}""",
            ),
        )
        assertEquals(1, decoded.profiles.size)
        assertEquals("com.example.ok", decoded.profiles.single().packageName)
        assertEquals(1, decoded.rejectedProfiles)
    }

    @Test
    fun `an unknown widget stat is dropped while its layout and the profile referencing it survive`() {
        val bundle = PresetBundle(
            hud = listOf(
                HudLayout(
                    id = 3L,
                    name = "Mix",
                    widgets = listOf(
                        HudWidget(id = "a", stat = HudStat.CPU_USAGE),
                        HudWidget(id = "b", stat = HudStat.REFRESH_RATE),
                    ),
                ),
            ),
        )
        val encoded = ProfileTransferCodec.encodeEnvelope(
            listOf(GameProfile(packageName = "com.example.g", label = "G", hudLayoutId = 3L)),
            bundle,
        )
        // Splice an action a newer build might have written into the widget array; it must be dropped.
        val tampered = encoded.replace(""""stat":"REFRESH_RATE"""", """"stat":"WARP_DRIVE"""")
        val decoded = ProfileTransferCodec.decodeEnvelope(tampered)
        assertEquals(1, decoded.hud[3L]?.widgets?.size)
        assertEquals(HudStat.CPU_USAGE, decoded.hud[3L]?.widgets?.single()?.stat)
        assertFalse(decoded.profiles.isEmpty())
        assertNotNull(decoded.hud[3L])
    }
}
