package com.gamecore.domain.memory

import com.gamecore.core.model.AppProcessState
import com.gamecore.core.model.ProtectionReason
import com.gamecore.core.model.ReclaimCandidate
import com.gamecore.domain.memory.ReclaimFilter.Protections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one decision in "free RAM on launch" that cannot be taken back if it is wrong.
 *
 * Everything else in the pass is recoverable or visible: a dump that will not parse costs a protection,
 * a shell that is not running skips the whole thing, a freed figure that cannot be taken is reported
 * absent. Closing the wrong app is none of those — GameCore does not reopen it, and the user finds out
 * when their music stops or their keyboard is gone. So the whole table is checked here, on a host with
 * no Android on it, which is what [ReclaimFilter] holding no platform type buys.
 *
 * Two properties are asserted past the individual reasons: that [ProtectionReason.entries] order is the
 * precedence the user reads, and that every fact the device contributes only ever *adds* protection.
 */
class ReclaimFilterTest {

    @Test
    fun `a background app that nothing objects to is closable`() {
        // The case the whole feature exists for. A filter that spares everything is a quieter failure
        // than one that spares too little, so it is pinned first and on its own.
        assertNull(ReclaimFilter.reasonToSpare(candidate(OTHER), device()))
        val idle = candidate(OTHER, AppProcessState.BACKGROUND_SERVICE)
        assertNull(ReclaimFilter.reasonToSpare(idle, device()))
    }

    @Test
    fun `every reason in the enum is reachable, and each fires for the app it is about`() {
        val cases = listOf(
            ProtectionReason.SELF to candidate(SELF),
            ProtectionReason.LAUNCHING_GAME to candidate(GAME),
            ProtectionReason.DEFAULT_LAUNCHER to candidate(LAUNCHER),
            ProtectionReason.CURRENT_IME to candidate(KEYBOARD),
            ProtectionReason.FOREGROUND_SERVICE to candidate(OTHER, AppProcessState.FOREGROUND_SERVICE),
            ProtectionReason.FOREGROUND_APP to candidate(OTHER, AppProcessState.TOP),
            ProtectionReason.IN_USE to candidate(OTHER, AppProcessState.PERCEPTIBLE),
            ProtectionReason.SYSTEM_APP to candidate(SYSTEM),
            ProtectionReason.ALLOWLISTED to candidate(ALLOWED),
            ProtectionReason.FOREGROUND_STATE_UNKNOWN to candidate(OTHER, AppProcessState.UNKNOWN),
        )
        for ((expected, app) in cases) {
            assertEquals(app.packageName, expected, ReclaimFilter.reasonToSpare(app, device()))
        }
        // A tenth reason cannot be added to the enum without a case for it here, and a reason whose
        // branch can never be true cannot hide behind the nine that work.
        assertEquals(ProtectionReason.entries.toSet(), cases.map { it.first }.toSet())
    }

    @Test
    fun `an app acting as the home screen is spared even when it is not a known launcher`() {
        // The launcher list comes from a package-manager query for home-screen intents, and a device can
        // put something on the home screen that the query missed — a vendor shell, or a launcher
        // installed after the list was taken. The state the dump reported is the second, independent way
        // to find that out, and either one alone is enough.
        val home = candidate(OTHER, AppProcessState.HOME)
        val noLaunchers = device(homeScreenPackages = emptySet())
        assertEquals(ProtectionReason.DEFAULT_LAUNCHER, ReclaimFilter.reasonToSpare(home, noLaunchers))
    }

    @Test
    fun `an unreadable state protects nothing when there was no process list to read`() {
        // Without the elevated shell every candidate carries UNKNOWN, because no state was ever on
        // offer. Treating that as suspicious would spare every app on the device and turn the feature
        // off while looking like it was working, so on that path `killBackgroundProcesses` is what
        // enforces the state rule and this object says nothing about it.
        val unknown = candidate(OTHER, AppProcessState.UNKNOWN)
        assertNull(ReclaimFilter.reasonToSpare(unknown, device(statesWereRead = false)))
        assertEquals(
            ProtectionReason.FOREGROUND_STATE_UNKNOWN,
            ReclaimFilter.reasonToSpare(unknown, device(statesWereRead = true)),
        )
    }

    @Test
    fun `the launching game reads as the game even though it is also the app on screen`() {
        // Both are true by the time the pass runs — the game being on screen is how it was detected.
        // The enum's order decides which of them the user is told, and "the game you just opened" is
        // the half that explains itself.
        val game = candidate(GAME, AppProcessState.TOP)
        assertEquals(ProtectionReason.LAUNCHING_GAME, ReclaimFilter.reasonToSpare(game, device()))
    }

    @Test
    fun `a system process whose state could not be read reads as system`() {
        // ReclaimFilter's own worked example. Both reasons apply; SYSTEM_APP is the one that tells the
        // user something true about the app, where FOREGROUND_STATE_UNKNOWN reads as a GameCore fault.
        val opaque = candidate(SYSTEM, AppProcessState.UNKNOWN)
        assertEquals(ProtectionReason.SYSTEM_APP, ReclaimFilter.reasonToSpare(opaque, device()))
    }

    @Test
    fun `every fact the device contributes only ever adds protection`() {
        // The safety claim in ReclaimFilter's KDoc, checked across the table rather than trusted: for
        // every process state, an app that was spared before a fact was added is still spared after it.
        // A launcher list that over-reads, an allowlist full of nonsense and a process state nobody
        // recognises can therefore each cost the user an app that stayed open, and none of them can cost
        // an app that was closed.
        val additions: List<Pair<String, (Protections) -> Protections>> = listOf(
            "self" to { base: Protections -> base.copy(selfPackage = OTHER) },
            "launching game" to { base: Protections -> base.copy(gamePackage = OTHER) },
            "home screen" to { base: Protections -> base.copy(homeScreenPackages = setOf(OTHER)) },
            "keyboard" to { base: Protections -> base.copy(imePackage = OTHER) },
            "never-close list" to { base: Protections -> base.copy(allowlist = setOf(OTHER)) },
            "not user installed" to { base: Protections -> base.copy(userInstalledPackages = emptySet()) },
            "states were read" to { base: Protections -> base.copy(statesWereRead = true) },
        )
        for (state in AppProcessState.entries) {
            val app = candidate(OTHER, state)
            for ((label, add) in additions) {
                for (read in listOf(false, true)) {
                    val before = ReclaimFilter.reasonToSpare(app, device(statesWereRead = read))
                    if (before == null) continue
                    val after = ReclaimFilter.reasonToSpare(app, add(device(statesWereRead = read)))
                    assertNotNull("$label un-protected ${state.name}, which was spared as $before", after)
                }
            }
        }
    }

    // ---- fixtures

    /**
     * A phone with one of everything the filter can be told about, and one app it is allowed to close.
     *
     * [statesWereRead] defaults to true because that is the elevated path, and the only one on which the
     * state half of this table is consulted at all.
     */
    private fun device(
        selfPackage: String = SELF,
        gamePackage: String = GAME,
        homeScreenPackages: Set<String> = setOf(LAUNCHER),
        imePackage: String? = KEYBOARD,
        allowlist: Set<String> = setOf(ALLOWED),
        userInstalledPackages: Set<String> = setOf(SELF, GAME, LAUNCHER, KEYBOARD, ALLOWED, OTHER),
        statesWereRead: Boolean = true,
    ) = Protections(
        selfPackage = selfPackage,
        gamePackage = gamePackage,
        homeScreenPackages = homeScreenPackages,
        imePackage = imePackage,
        allowlist = allowlist,
        userInstalledPackages = userInstalledPackages,
        statesWereRead = statesWereRead,
    )

    /** [AppProcessState.CACHED] by default: running, and the state that is no objection on its own. */
    private fun candidate(packageName: String, state: AppProcessState = AppProcessState.CACHED) =
        ReclaimCandidate(packageName = packageName, label = packageName, state = state)
}

private const val SELF = "com.gamecore"
private const val GAME = "com.example.game"
private const val LAUNCHER = "com.example.launcher"
private const val KEYBOARD = "com.example.keyboard"
private const val ALLOWED = "com.example.notes"
private const val OTHER = "com.example.background"

/** Absent from the user-installed set, which is the whole of what makes an app a system one here. */
private const val SYSTEM = "com.android.systemui"
