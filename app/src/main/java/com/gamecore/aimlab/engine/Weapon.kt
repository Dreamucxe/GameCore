package com.gamecore.aimlab.engine

/**
 * A fictional training weapon.
 *
 * These parameters are training behaviour, not real-world weapon data (§7 is explicit about that). A
 * weapon is a bundle of the numbers the modes run on: how fast it fires, how much it kicks, how it
 * recovers. Recoil behaviour is expressed as a [RecoilSpec] so the recoil engine can generate a
 * reproducible pattern from it.
 *
 * @param fireRateRpm rounds per minute; the engine derives the inter-shot interval from it.
 * @param magazineSize rounds before a reload is required.
 * @param reloadMillis reload duration.
 * @param adsTimeMillis time to fully aim down sights.
 * @param movementPenalty fraction (0..1) that movement scales aim error by; higher punishes moving more.
 * @param spread base bullet spread in arena units at rest.
 * @param fireMode how a trigger pull discharges: one round, a fixed burst, or fully automatic.
 * @param burstCount rounds per burst when [fireMode] is [FireMode.BURST]; ignored otherwise.
 */
data class Weapon(
    val id: Long = 0L,
    val name: String,
    val category: WeaponCategory = WeaponCategory.ASSAULT_RIFLE,
    val fireRateRpm: Int = 600,
    val magazineSize: Int = 30,
    val reloadMillis: Long = 2_000L,
    val adsTimeMillis: Long = 250L,
    val movementPenalty: Float = 0.4f,
    val spread: Float = 0.010f,
    val recoil: RecoilSpec = RecoilSpec(),
    val fireMode: FireMode = FireMode.AUTO,
    val burstCount: Int = 3,
    val isBuiltIn: Boolean = false,
) {
    /** Milliseconds between shots at the weapon's fire rate. Guards against a zero/negative rate. */
    val shotIntervalMillis: Long get() = if (fireRateRpm <= 0) Long.MAX_VALUE else 60_000L / fireRateRpm

    fun normalised(): Weapon = copy(
        fireRateRpm = fireRateRpm.coerceIn(MIN_RPM, MAX_RPM),
        magazineSize = magazineSize.coerceIn(1, MAX_MAG),
        reloadMillis = reloadMillis.coerceIn(200L, 10_000L),
        adsTimeMillis = adsTimeMillis.coerceIn(0L, 2_000L),
        movementPenalty = movementPenalty.clampFinite(0f, 1f),
        spread = spread.clampFinite(0f, 0.2f),
        recoil = recoil.normalised(),
        burstCount = burstCount.coerceIn(MIN_BURST, MAX_BURST),
    )

    companion object {
        const val MIN_RPM = 30
        const val MAX_RPM = 1_200
        const val MAX_MAG = 200
        const val MIN_BURST = 2
        const val MAX_BURST = 5
    }
}

/**
 * How a weapon's trigger discharges rounds (spec: single / burst / auto).
 *
 * This is a *training* firing behaviour, not a claim about any real weapon: it changes how a tap or a
 * held trigger turns into shots, which is what the recoil and flick drills actually train against.
 *
 * - [SINGLE]: one round per trigger pull; a held trigger fires once. The tap *is* the shot.
 * - [BURST]: a fixed number of rounds ([Weapon.burstCount]) per pull, spaced by the fire-rate interval,
 *   then the trigger must be released and pulled again.
 * - [AUTO]: rounds keep firing at the fire rate for as long as the trigger is held.
 *
 * Name is the stable stored key, parsed back defensively.
 */
enum class FireMode(val label: String) {
    SINGLE("Single"),
    BURST("Burst"),
    AUTO("Auto"),
    ;

    companion object {
        fun fromName(name: String?): FireMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Weapon families §7 lists. Name is the stable stored key. */
enum class WeaponCategory(val label: String) {
    PISTOL("Pistol"),
    SMG("SMG"),
    ASSAULT_RIFLE("Assault rifle"),
    LMG("LMG"),
    SHOTGUN("Shotgun"),
    SNIPER("Sniper"),
    ;

    companion object {
        fun fromName(name: String?): WeaponCategory = entries.firstOrNull { it.name == name } ?: ASSAULT_RIFLE
    }
}

/**
 * How a weapon kicks, as the parameters [RecoilEngine] turns into a per-shot pattern.
 *
 * @param verticalPerShot upward kick per shot in arena units.
 * @param horizontalPerShot signed horizontal drift magnitude per shot; the engine alternates/jitters it.
 * @param randomness 0..1 fraction of each kick that is randomised (via the injected [Rng]); 0 is a fixed,
 *   learnable pattern, 1 is fully random.
 * @param recoveryPerSecond fraction of accumulated offset that decays back toward zero each second when
 *   not firing; the recovery math is in [RecoilEngine].
 */
data class RecoilSpec(
    val verticalPerShot: Float = 0.020f,
    val horizontalPerShot: Float = 0.008f,
    val randomness: Float = 0.3f,
    val recoveryPerSecond: Float = 2.5f,
) {
    fun normalised(): RecoilSpec = copy(
        verticalPerShot = verticalPerShot.coerceIn(0f, 0.1f),
        horizontalPerShot = horizontalPerShot.coerceIn(0f, 0.1f),
        randomness = randomness.coerceIn(0f, 1f),
        recoveryPerSecond = recoveryPerSecond.coerceIn(0f, 10f),
    )
}
