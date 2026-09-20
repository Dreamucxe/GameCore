package com.gamecore.aimlab.engine

/**
 * Turns a trigger — a press, a hold, a release — into discrete shots according to a weapon's [FireMode],
 * gated by its fire-rate interval, magazine and reload. Pure Kotlin so the cadence is unit-tested without
 * a device: the loop feeds it press/release/tick events with the injected clock's nanos and it answers
 * "does a round discharge now".
 *
 * The three modes differ only in what a held trigger does after the first round:
 *  - [FireMode.SINGLE]: one round per press; holding fires once, releasing arms the next press.
 *  - [FireMode.BURST]: up to [Weapon.burstCount] rounds per press, one every fire-rate interval, then the
 *    trigger must be released before another burst; a mid-burst release stops the remaining rounds.
 *  - [FireMode.AUTO]: rounds every fire-rate interval for as long as the trigger is held.
 *
 * The magazine and reload live here too, because they gate every mode identically: a round only
 * discharges when ammo remains and no reload is in progress, and firing the last round starts a reload.
 * State is plain fields mutated on the caller's thread; nothing allocates per shot.
 */
class FireControl {

    private var mode: FireMode = FireMode.AUTO
    private var burstSize: Int = 3
    private var intervalNanos: Long = 100_000_000L
    private var reloadNanos: Long = 2_000_000_000L
    private var magazine: Int = 30

    private var ammo: Int = 0
    private var reloading: Boolean = false
    private var reloadDoneNanos: Long = 0L

    private var triggerDown: Boolean = false
    private var nextShotAllowedNanos: Long = 0L
    private var roundsLeftInBurst: Int = 0
    // SINGLE/BURST: a press only fires once its trigger has been released since the last shot sequence.
    private var armed: Boolean = true

    /** Loads a weapon and resets to a full magazine. Call once per run start. */
    fun configure(weapon: Weapon, nowNanos: Long) {
        mode = weapon.fireMode
        burstSize = weapon.burstCount.coerceAtLeast(1)
        intervalNanos = weapon.shotIntervalMillis * 1_000_000L
        reloadNanos = weapon.reloadMillis * 1_000_000L
        magazine = weapon.magazineSize.coerceAtLeast(1)
        ammo = magazine
        reloading = false
        reloadDoneNanos = 0L
        triggerDown = false
        nextShotAllowedNanos = nowNanos
        roundsLeftInBurst = 0
        armed = true
    }

    val ammoRemaining: Int get() = ammo
    val isReloading: Boolean get() = reloading

    /** The trigger went down. In SINGLE/BURST this starts at most one sequence; AUTO fires while held. */
    fun onTriggerDown() {
        triggerDown = true
        if (mode == FireMode.BURST && armed) roundsLeftInBurst = burstSize
    }

    /** The trigger came up. Re-arms SINGLE/BURST for the next press and ends AUTO fire. */
    fun onTriggerUp() {
        triggerDown = false
        armed = true
        roundsLeftInBurst = 0
    }

    /**
     * Advances to [nowNanos] and returns how many rounds discharged since the last call (0 or more).
     *
     * Called every tick. A reload that has elapsed refills the magazine first. Then, while the trigger's
     * mode still wants to fire and the fire-rate gate has opened and ammo remains, a round is emitted, the
     * gate is re-armed one interval ahead, and the magazine decrements — starting a reload when it empties.
     * The count is usually 0 or 1 per tick; it can be more only if a tick spanned several intervals, which
     * keeps a low frame rate from silently swallowing rounds.
     */
    fun tick(nowNanos: Long): Int {
        if (reloading && nowNanos >= reloadDoneNanos) {
            ammo = magazine
            reloading = false
        }
        var fired = 0
        while (wantsToFire() && !reloading && ammo > 0 && nowNanos >= nextShotAllowedNanos) {
            fired++
            ammo--
            nextShotAllowedNanos += intervalNanos
            when (mode) {
                FireMode.SINGLE -> armed = false // one per press; a release re-arms
                FireMode.BURST -> {
                    roundsLeftInBurst--
                    if (roundsLeftInBurst <= 0) armed = false // burst spent; a release re-arms
                }
                FireMode.AUTO -> Unit // keeps firing while held
            }
            if (ammo <= 0) {
                reloading = true
                reloadDoneNanos = nowNanos + reloadNanos
                break
            }
            if (intervalNanos <= 0L) break
            // SINGLE and BURST fire at most one round per tick: a burst is a *cadence*, so a long frame
            // (a lag spike) must not dump the whole burst in one instant — the remaining rounds wait for
            // their intervals on later ticks. AUTO keeps catching up, so continuous fire never silently
            // swallows rounds when a frame runs long. This is the physically-correct behaviour and it is
            // what makes a burst walk the recoil pattern rather than land all at once.
            if (mode != FireMode.AUTO) break
        }
        return fired
    }

    /** Whether the current mode+trigger+arm state wants another round right now (before the rate gate). */
    private fun wantsToFire(): Boolean = when (mode) {
        FireMode.SINGLE -> triggerDown && armed
        FireMode.BURST -> armed && roundsLeftInBurst > 0
        FireMode.AUTO -> triggerDown
    }
}
