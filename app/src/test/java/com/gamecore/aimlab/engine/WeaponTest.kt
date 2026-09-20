package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A training weapon's numbers, and the guarantee the engine never runs on an impossible one.
 *
 * A weapon can arrive from the editor, from a shared config file, or from a row an old app version wrote,
 * so [Weapon.normalised] is the gate every one of those paths goes through — which makes its clamps
 * load-bearing rather than cosmetic. Two properties matter most: nothing survives the gate outside its
 * declared range (a 99999 RPM import must not reach the firing loop), and normalising is idempotent, so a
 * value that has already been through it is never dragged a second time. [Weapon.shotIntervalMillis] is
 * the other sharp edge: it divides by the fire rate, so a zero rate must not divide by zero.
 */
class WeaponTest {

    private val eps = 1e-6f

    @Test
    fun `the shot interval is one minute divided by the fire rate`() {
        assertEquals(100L, Weapon(name = "Rifle", fireRateRpm = 600).shotIntervalMillis)
        assertEquals(50L, Weapon(name = "Fast", fireRateRpm = 1_200).shotIntervalMillis)
        assertEquals(2_000L, Weapon(name = "Slow", fireRateRpm = 30).shotIntervalMillis)
        // Integer division, so an awkward rate truncates rather than rounding up.
        assertEquals(1_090L, Weapon(name = "Marksman", fireRateRpm = 55).shotIntervalMillis)
        // A faster weapon always waits less between shots.
        assertTrue(
            Weapon(name = "a", fireRateRpm = 900).shotIntervalMillis <
                Weapon(name = "b", fireRateRpm = 400).shotIntervalMillis,
        )
    }

    @Test
    fun `a zero or negative fire rate never divides by zero`() {
        assertEquals(Long.MAX_VALUE, Weapon(name = "Broken", fireRateRpm = 0).shotIntervalMillis)
        assertEquals(Long.MAX_VALUE, Weapon(name = "Broken", fireRateRpm = -600).shotIntervalMillis)
        // And once normalised it fires again, at the slowest legal rate.
        assertEquals(2_000L, Weapon(name = "Broken", fireRateRpm = 0).normalised().shotIntervalMillis)
    }

    @Test
    fun `the defaults already sit inside the bounds normalising enforces`() {
        val default = Weapon(name = "Service Rifle")
        assertEquals(default, default.normalised())
        assertEquals(RecoilSpec(), RecoilSpec().normalised())
        assertTrue(default.fireRateRpm in Weapon.MIN_RPM..Weapon.MAX_RPM)
        assertTrue(default.magazineSize in 1..Weapon.MAX_MAG)
        assertTrue(default.movementPenalty in 0f..1f)
        assertTrue(default.spread in 0f..0.2f)
        assertEquals(WeaponCategory.ASSAULT_RIFLE, default.category)
        assertFalse(default.isBuiltIn)
    }

    @Test
    fun `normalising pulls an over-the-top weapon down to its declared maximums`() {
        val wild = Weapon(
            name = "Impossible",
            fireRateRpm = 99_999,
            magazineSize = 9_999,
            reloadMillis = 99_999L,
            adsTimeMillis = 99_999L,
            movementPenalty = 5f,
            spread = 5f,
            recoil = RecoilSpec(
                verticalPerShot = 5f,
                horizontalPerShot = 5f,
                randomness = 5f,
                recoveryPerSecond = 99f,
            ),
        ).normalised()

        assertEquals(Weapon.MAX_RPM, wild.fireRateRpm)
        assertEquals(Weapon.MAX_MAG, wild.magazineSize)
        assertEquals(10_000L, wild.reloadMillis)
        assertEquals(2_000L, wild.adsTimeMillis)
        assertEquals(1f, wild.movementPenalty, eps)
        assertEquals(0.2f, wild.spread, eps)
        assertEquals(0.1f, wild.recoil.verticalPerShot, eps)
        assertEquals(0.1f, wild.recoil.horizontalPerShot, eps)
        assertEquals(1f, wild.recoil.randomness, eps)
        assertEquals(10f, wild.recoil.recoveryPerSecond, eps)
    }

    @Test
    fun `normalising lifts a degenerate weapon up to its declared minimums`() {
        val feeble = Weapon(
            name = "Unusable",
            fireRateRpm = 0,
            magazineSize = 0,
            reloadMillis = 0L,
            adsTimeMillis = -50L,
            movementPenalty = -2f,
            spread = -1f,
            recoil = RecoilSpec(
                verticalPerShot = -1f,
                horizontalPerShot = -1f,
                randomness = -1f,
                recoveryPerSecond = -1f,
            ),
        ).normalised()

        assertEquals(Weapon.MIN_RPM, feeble.fireRateRpm)
        // A magazine of zero rounds could never fire a shot.
        assertEquals(1, feeble.magazineSize)
        assertEquals(200L, feeble.reloadMillis)
        assertEquals(0L, feeble.adsTimeMillis)
        assertEquals(0f, feeble.movementPenalty, eps)
        assertEquals(0f, feeble.spread, eps)
        assertEquals(0f, feeble.recoil.verticalPerShot, eps)
        assertEquals(0f, feeble.recoil.horizontalPerShot, eps)
        assertEquals(0f, feeble.recoil.randomness, eps)
        assertEquals(0f, feeble.recoil.recoveryPerSecond, eps)
    }

    @Test
    fun `normalising is idempotent and leaves identity alone`() {
        val raw = Weapon(
            id = 17L,
            name = "Hand-edited",
            category = WeaponCategory.SNIPER,
            fireRateRpm = 99_999,
            magazineSize = -4,
            reloadMillis = 99_999L,
            adsTimeMillis = -1L,
            movementPenalty = 9f,
            spread = -3f,
            recoil = RecoilSpec(verticalPerShot = 9f, horizontalPerShot = -9f, randomness = 9f, recoveryPerSecond = -9f),
            isBuiltIn = true,
        )
        val once = raw.normalised()
        assertEquals(once, once.normalised())
        assertEquals(once, once.normalised().normalised())

        // The clamps touch behaviour, never who the weapon is.
        assertEquals(17L, once.id)
        assertEquals("Hand-edited", once.name)
        assertEquals(WeaponCategory.SNIPER, once.category)
        assertTrue(once.isBuiltIn)
    }

    @Test
    fun `an in-range recoil spec survives normalising untouched`() {
        val spec = RecoilSpec(verticalPerShot = 0.055f, horizontalPerShot = 0.004f, randomness = 0.15f, recoveryPerSecond = 3f)
        assertEquals(spec, spec.normalised())
        val clamped = spec.normalised()
        assertTrue(clamped.verticalPerShot in 0f..0.1f)
        assertTrue(clamped.horizontalPerShot in 0f..0.1f)
        assertTrue(clamped.randomness in 0f..1f)
        assertTrue(clamped.recoveryPerSecond in 0f..10f)
    }

    @Test
    fun `the weapon categories are stable stored keys with distinct labels`() {
        assertEquals(
            listOf("PISTOL", "SMG", "ASSAULT_RIFLE", "LMG", "SHOTGUN", "SNIPER"),
            WeaponCategory.entries.map { it.name },
        )
        val labels = WeaponCategory.entries.map { it.label }
        for (label in labels) assertTrue("blank label", label.isNotBlank())
        assertEquals(WeaponCategory.entries.size, labels.distinct().size)
        for (category in WeaponCategory.entries) {
            assertEquals(category, WeaponCategory.fromName(category.name))
        }
    }

    @Test
    fun `an unrecognised category falls back to the assault rifle rather than throwing`() {
        assertEquals(WeaponCategory.ASSAULT_RIFLE, WeaponCategory.fromName(null))
        assertEquals(WeaponCategory.ASSAULT_RIFLE, WeaponCategory.fromName(""))
        assertEquals(WeaponCategory.ASSAULT_RIFLE, WeaponCategory.fromName("RAILGUN"))
        assertEquals(WeaponCategory.ASSAULT_RIFLE, WeaponCategory.fromName("pistol"))
        assertEquals(WeaponCategory.ASSAULT_RIFLE, WeaponCategory.fromName(WeaponCategory.PISTOL.label))
    }
}
