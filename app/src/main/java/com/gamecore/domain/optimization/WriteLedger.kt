package com.gamecore.domain.optimization

import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.settingValuesMatch
import com.gamecore.data.repository.RestorePointRepository
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One thing on the device GameCore can change, named the way the restore table names it.
 *
 * The same shape as that table's primary key — namespace and key — so a [WriteLedger] entry and a
 * [com.gamecore.data.repository.PendingRestore] row can always be lined up against each other, which
 * is the whole point of it: the restore is the other half of the write.
 */
data class DeviceKey(val namespace: String, val name: String) {

    companion object {
        fun of(setting: WritableSetting) = DeviceKey(setting.namespace.token, setting.key)

        /** For media volume and Do Not Disturb, which have no settings key an app can rely on. */
        fun nonSetting(name: String) =
            DeviceKey(RestorePointRepository.NON_SETTING_NAMESPACE, name)
    }
}

/**
 * What GameCore last left on each device key, so it can tell its own changes from the user's.
 *
 * This exists because of a bug: switch Do Not Disturb off from the notification shade mid-game, wait
 * for a popup to steal the foreground long enough to end the session, and the game coming back turned
 * Do Not Disturb straight back on. [OptimizationManager] captures the *current* value as the restore
 * point and then writes the profile's value over it, which means a value the user chose thirty seconds
 * ago and a value GameCore wrote itself are indistinguishable at the moment of the next write. The
 * same blindness runs the other way at session end, where the restore writes the recorded value back
 * over a manual change.
 *
 * The rule this encodes is one sentence: **a key whose live value is not what GameCore left there is
 * the user's, and GameCore neither re-asserts nor restores it.** So:
 *
 *  - [wrote] is called after every attempted write, with the value the device actually reports —
 *    never with the value that was requested. Devices round and normalise, and a baseline that
 *    disagreed with what a read returns would call every key the user's on the next poll.
 *  - [userChanged] is consulted before an automatic write and before every restore.
 *  - [released] drops the claim. A restore that succeeded is the main one: the device is back at the
 *    user's own value, and a stale claim would make the next session refuse the profile forever.
 *
 * A refused restore deliberately *keeps* its claim, which is what makes the refusal stick: the row is
 * cleared, but the ledger still says the user owns that key, so the next automatic apply leaves it
 * alone too.
 *
 * Immutable and pure, like [com.gamecore.domain.gaming.GameWatch] and for the same reason — the
 * interesting sequences here are three or four writes and reads deep, and none of them are reachable
 * in a JVM test through a class that holds a settings provider.
 */
data class WriteLedger(
    /**
     * The value GameCore left on each key it has written and not released.
     *
     * A null value means "the key read as unset", which is a real state a device can be in and is
     * distinct from the key being absent from the map — hence [tracks] rather than a null check.
     */
    private val claims: Map<DeviceKey, String?> = emptyMap(),
) {

    /** Records what [key] holds now that GameCore has written it. Replaces any earlier claim. */
    fun wrote(key: DeviceKey, value: String?): WriteLedger = WriteLedger(claims + (key to value))

    /**
     * Gives up the claim on [key].
     *
     * Called when the value has gone back to the user's, and when a read failed badly enough that
     * there is no baseline worth keeping. Both mean the same thing here: GameCore is no longer in a
     * position to say what it left there, so it stops claiming to know.
     */
    fun released(key: DeviceKey): WriteLedger =
        if (claims.containsKey(key)) WriteLedger(claims - key) else this

    /** Whether GameCore has written [key] and not released it. */
    fun tracks(key: DeviceKey): Boolean = claims.containsKey(key)

    /**
     * Whether [live] is evidence that the user has moved [key] since GameCore wrote it.
     *
     * False for a key this ledger does not track: a setting GameCore has never written is nobody's
     * business but the user's, and treating it as taken over would block the first write of every
     * session.
     *
     * Compared with [settingValuesMatch] rather than `==` because the settings provider normalises
     * numbers — `"120"` written to a float key reads back `"120.0"` — and a false "the user changed
     * this" is the worse failure of the two: it silently drops a setting the profile asked for, with
     * no way for the user to tell why.
     */
    fun userChanged(key: DeviceKey, live: String?): Boolean {
        if (!claims.containsKey(key)) return false
        val mine = claims[key]
        if (mine == null || live == null) return mine != live
        return !settingValuesMatch(mine, live)
    }
}

/**
 * The one [WriteLedger] for the process, shared by everything that writes a device key.
 *
 * A singleton rather than a field on [OptimizationManager] because the manager is not the only writer.
 * [com.gamecore.domain.color.ColorCorrectionController] writes the display's eight colour keys itself —
 * for the reason [com.gamecore.domain.gaming.ProfileApplier] gives, that a preset touches between one
 * and eight of them and recording all eight up front would leave rows for keys it never wrote — and
 * night light, greyscale and extra dim all have shade tiles, so they are exactly as easy for a user to
 * change mid-game as Do Not Disturb is. A per-writer ledger would fix the reported bug for the settings
 * the manager owns and leave the colour keys behaving the way they always did.
 *
 * In memory only, and deliberately. A claim is a statement about what the device holds *right now*;
 * across a process death anything could have moved it, and a claim restored from disk would be a guess
 * presented as a fact. Losing the claims costs the app one thing — the first automatic apply after a
 * restart re-asserts a profile setting the user had taken over — and [com.gamecore.domain.StartupCoordinator]
 * is already the answer to the wider version of that: what a killed session left changed is offered
 * back to the user rather than acted on.
 */
@Singleton
class DeviceWriteLog @Inject constructor() {

    private val ledger = AtomicReference(WriteLedger())

    fun current(): WriteLedger = ledger.get()

    /** Records what [key] holds now that GameCore has written it. */
    fun wrote(key: DeviceKey, value: String?) {
        ledger.updateAndGet { it.wrote(key, value) }
    }

    /** Gives up the claim on [key]: it is back at the user's value, or unreadable. */
    fun release(key: DeviceKey) {
        ledger.updateAndGet { it.released(key) }
    }

    /**
     * Folds several claims in as one step.
     *
     * For the caller writing a pair of keys — the two rotation settings, three animation scales — where
     * two separate updates would leave a window in which one key is claimed and the other is not.
     */
    fun update(transform: (WriteLedger) -> WriteLedger) {
        ledger.updateAndGet(transform)
    }
}
