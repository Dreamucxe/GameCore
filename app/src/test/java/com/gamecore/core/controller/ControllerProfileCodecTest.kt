package com.gamecore.core.controller

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfileCodecTest {

    private fun profile(name: String = "Reference") = ControllerProfile(
        name = name,
        deviceSignature = ControllerSignature.of(1, 2, listOf(0, 1, 11, 14), 15),
        deviceName = "Wireless Controller",
        deadzone = 0.12f,
        curve = ResponseCurveKind.SMOOTH,
        exponent = 2.5f,
        driftOffsetThreshold = 0.09f,
        driftJitterThreshold = 0.025f,
        notes = "Measured at rest",
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_100_000L,
    )

    /** The first encoded profile object, so hostile-input cases start from a complete, valid entry. */
    private fun encodedObject(): JSONObject = JSONObject(ControllerProfileCodec.encode(listOf(profile())))
        .getJSONArray("profiles")
        .getJSONObject(0)

    private fun envelope(vararg profiles: Any): String {
        val array = JSONArray()
        profiles.forEach { array.put(it) }
        return JSONObject()
            .put("format", ControllerProfileCodec.FORMAT)
            .put("version", ControllerProfileCodec.VERSION)
            .put("profiles", array)
            .toString()
    }

    @Test
    fun `a profile round-trips without carrying a local id`() {
        val decoded = ControllerProfileCodec.decode(ControllerProfileCodec.encode(listOf(profile())))

        assertNull(decoded.error)
        assertEquals(1, decoded.profiles.size)
        assertEquals(0, decoded.rejectedProfiles)

        val restored = decoded.profiles.single()
        assertEquals(0L, restored.id)
        assertEquals("Reference", restored.name)
        assertEquals(profile().deviceSignature, restored.deviceSignature)
        assertEquals(0.12f, restored.deadzone, 0.0001f)
        assertEquals(ResponseCurveKind.SMOOTH, restored.curve)
        assertEquals(2.5f, restored.exponent, 0.0001f)
        assertEquals(0.09f, restored.driftOffsetThreshold, 0.0001f)
        assertEquals(0.025f, restored.driftJitterThreshold, 0.0001f)
        assertEquals("Measured at rest", restored.notes)
        assertEquals(1_700_000_000_000L, restored.createdAtMillis)
    }

    @Test
    fun `a blank file is an error, not an empty import`() {
        assertNotNull(ControllerProfileCodec.decode("  ").error)
    }

    @Test
    fun `truncated json is an error`() {
        assertNotNull(ControllerProfileCodec.decode("{\"format\":").error)
    }

    @Test
    fun `an unrelated json file is refused before its profiles are read`() {
        val unrelated = JSONObject()
            .put("format", "gamecore.profile")
            .put("version", 1)
            .put("profiles", JSONArray().put(JSONObject().put("name", "x")))

        val decoded = ControllerProfileCodec.decode(unrelated.toString())

        assertNotNull(decoded.error)
        assertTrue(decoded.profiles.isEmpty())
    }

    @Test
    fun `a future version is refused with the version it found`() {
        val future = JSONObject()
            .put("format", ControllerProfileCodec.FORMAT)
            .put("version", 99)
            .put("profiles", JSONArray())

        val decoded = ControllerProfileCodec.decode(future.toString())

        assertNotNull(decoded.error)
        assertTrue(decoded.error!!.contains("99"))
    }

    @Test
    fun `a missing profiles list is an error`() {
        val noList = JSONObject().put("format", ControllerProfileCodec.FORMAT).put("version", 1)

        assertNotNull(ControllerProfileCodec.decode(noList.toString()).error)
    }

    @Test
    fun `one bad entry does not discard the valid ones`() {
        val good = encodedObject()
        val bad = JSONObject(good.toString()).put("deadzone", 0.9)

        val decoded = ControllerProfileCodec.decode(envelope(good, bad, "not a profile"))

        assertNull(decoded.error)
        assertEquals(1, decoded.profiles.size)
        assertEquals(2, decoded.rejectedProfiles)
        assertEquals(2, decoded.issues.size)
        assertFalse(decoded.isEmpty)
    }

    @Test
    fun `an unknown curve name and a missing number are rejected`() {
        val unknownCurve = JSONObject(encodedObject().toString()).put("curve", "BEZIER")
        val missingExponent = JSONObject(encodedObject().toString()).apply { remove("exponent") }

        val decoded = ControllerProfileCodec.decode(envelope(unknownCurve, missingExponent))

        assertEquals(0, decoded.profiles.size)
        assertEquals(2, decoded.rejectedProfiles)
        assertTrue(decoded.issues.any { it.contains("BEZIER") })
        assertTrue(decoded.issues.any { it.contains("exponent") })
    }

    @Test
    fun `a hostile non-numeric value is rejected rather than stored as NaN`() {
        val poisoned = JSONObject(encodedObject().toString()).put("drift_jitter", "NaN")

        val decoded = ControllerProfileCodec.decode(envelope(poisoned))

        assertEquals(0, decoded.profiles.size)
        assertEquals(1, decoded.rejectedProfiles)
        assertTrue(decoded.issues.single().contains("jitter"))
    }

    @Test
    fun `unknown keys are ignored so a later version can add fields`() {
        val future = JSONObject(encodedObject().toString())
            .put("future_field", JSONObject().put("nested", true))

        val decoded = ControllerProfileCodec.decode(envelope(future))

        assertNull(decoded.error)
        assertEquals(1, decoded.profiles.size)
    }

    @Test
    fun `an empty valid export decodes to no profiles and no error`() {
        val decoded = ControllerProfileCodec.decode(ControllerProfileCodec.encode(emptyList()))

        assertNull(decoded.error)
        assertTrue(decoded.profiles.isEmpty())
        assertEquals(0, decoded.rejectedProfiles)
        assertTrue(decoded.isEmpty)
    }
}
