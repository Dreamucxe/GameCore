package com.gamecore.domain.memory

import com.gamecore.BuildConfig
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppProcessState
import com.gamecore.core.model.InstalledApp
import com.gamecore.core.model.MemoryReclaimReport
import com.gamecore.core.model.ProtectionReason
import com.gamecore.core.model.ReclaimCandidate
import com.gamecore.core.model.ReclaimOutcome
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.core.system.ProcessControls
import com.gamecore.data.preferences.SecurePreferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closes background apps when a game starts, and reports exactly what it did.
 *
 * The order of operations is the feature, so it is written out here before the code:
 *
 *  1. Establish the protections that do not come from a process list — the home screen and the
 *     keyboard. If either cannot be determined the pass **does not run**. A protection that cannot
 *     be checked cannot be honoured, and this feature is off by default precisely so that it is
 *     allowed to decline.
 *  2. Enumerate what is running. With Shizuku that is the platform's process list and the platform's
 *     own word for what each process is doing; without it, the apps the user opened recently, which
 *     is weaker and is labelled as such for the rest of the pass.
 *  3. Ask [ReclaimFilter] about every candidate, and keep the reason for each one it spares.
 *  4. Read available memory.
 *  5. Close what is left, one app at a time.
 *  6. Wait for the platform to actually tear the processes down, then read the process list again
 *     and read available memory again.
 *  7. Report. An app is [ReclaimOutcome.Closed] only if it was in the first list and is absent from
 *     the second one. Everything else is [ReclaimOutcome.Requested], [ReclaimOutcome.Failed] or
 *     [ReclaimOutcome.Protected], and the freed figure is the difference between the two readings.
 *
 * Step 6 is what separates this from a RAM booster, and it is the expensive step: it costs a second
 * process dump and a wait. The alternative is to count the commands that exited zero, call that a
 * number of apps closed, and be wrong every time the platform declined — which is the number every
 * booster on the store reports.
 *
 * What this class does not do is decide anything. [ReclaimFilter] holds every rule about which app
 * may be closed, with no Android type in its signature, so all ten of them can be checked on a
 * machine with no device attached.
 */
@Singleton
class BackgroundAppReclaimer @Inject constructor(
    private val processes: ProcessControls,
    private val apps: InstalledAppLister,
    private val preferences: SecurePreferenceStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * Runs one pass for the game named by [gamePackage].
     *
     * Called only when the profile asked for it. Never throws: every path out of here is a
     * [MemoryReclaimReport], because this runs inside a game launch and a launch that fails because
     * an optional extra failed would be a worse bug than the one it was working around.
     */
    suspend fun reclaim(gamePackage: String): MemoryReclaimReport = withContext(io) {
        val home = processes.homeScreenPackages()
        if (home.isEmpty()) return@withContext MemoryReclaimReport.Skipped(HOME_UNKNOWN)
        val ime = processes.currentImePackage()
            ?: return@withContext MemoryReclaimReport.Skipped(IME_UNKNOWN)

        val installed = apps.list(includeSystemApps = true).associateBy { it.packageName }
        if (installed.isEmpty()) return@withContext MemoryReclaimReport.Skipped(APPS_UNKNOWN)

        val found = enumerate(installed) ?: return@withContext MemoryReclaimReport.Skipped(BLIND)

        val protections = ReclaimFilter.Protections(
            selfPackage = BuildConfig.APPLICATION_ID,
            gamePackage = gamePackage,
            homeScreenPackages = home,
            imePackage = ime,
            allowlist = preferences.settings.value.neverKillPackages.toSet(),
            userInstalledPackages = installed.filterValues { !it.isSystemApp }.keys,
            statesWereRead = found.statesWereRead,
        )

        val spared = ArrayList<ReclaimOutcome>()
        val closable = ArrayList<ReclaimCandidate>()
        found.candidates.forEach { candidate ->
            val reason = ReclaimFilter.reasonToSpare(candidate, protections)
            if (reason == null) {
                closable += candidate
            } else {
                spared += ReclaimOutcome.Protected(candidate.packageName, candidate.label, reason)
            }
        }
        if (closable.isEmpty()) {
            return@withContext MemoryReclaimReport.Completed(spared, NOT_MEASURED, found.via)
        }

        close(closable, spared, found)
    }

    /**
     * Closes [closable], then finds out what that did.
     *
     * The two memory readings bracket the closing as tightly as they can, and the wait between the
     * last command and the second reading is not tuning: `am kill` and `killBackgroundProcesses` both
     * return before the processes are gone, so a reading taken immediately would measure nothing and
     * a process list read immediately would still list every app as running.
     */
    private suspend fun close(
        closable: List<ReclaimCandidate>,
        spared: List<ReclaimOutcome>,
        found: Enumeration,
    ): MemoryReclaimReport {
        val before = processes.availableMemoryBytes()
        val asked = closable.map { candidate ->
            candidate to if (found.statesWereRead) {
                processes.close(candidate.packageName)
            } else {
                processes.requestClose(candidate.packageName)
            }
        }
        delay(SETTLE_MILLIS)
        val after = processes.availableMemoryBytes()

        // Null means the pass has no way to check: either it never had the shell, or the second dump
        // failed. Both leave every command in `asked` unverifiable, which is what Requested is for.
        val stillRunning = if (found.statesWereRead) {
            processes.runningApps().valueOrNull?.keys
        } else {
            null
        }

        val outcomes = spared + asked.map { (candidate, outcome) ->
            when {
                !outcome.isApplied ->
                    ReclaimOutcome.Failed(candidate.packageName, candidate.label, outcome.message)
                stillRunning == null ->
                    ReclaimOutcome.Requested(candidate.packageName, candidate.label)
                candidate.packageName in stillRunning ->
                    ReclaimOutcome.Failed(candidate.packageName, candidate.label, STILL_RUNNING)
                else -> ReclaimOutcome.Closed(candidate.packageName, candidate.label)
            }
        }
        return MemoryReclaimReport.Completed(outcomes, freedBetween(before, after), found.via)
    }

    /**
     * What is running, and how well this pass knows it.
     *
     * The elevated list is preferred whenever it parses. When it does not — no Shizuku, or a dump in
     * a shape [com.gamecore.core.system.DumpsysParsers] does not recognise — the usage-event list is
     * used instead, and [statesWereRead] carries the difference into every decision downstream: the
     * filter stops treating an unreadable state as suspicious, because on that path no state was ever
     * offered, and the closing switches to the platform call whose result cannot be checked.
     *
     * The foreground app is in both lists. On the elevated path it is spared by its own state; on the
     * other one it is spared by being the game, and by `killBackgroundProcesses` refusing to touch
     * whatever is in front regardless of what it is asked.
     */
    private data class Enumeration(
        val candidates: List<ReclaimCandidate>,
        val via: DataSource,
        val statesWereRead: Boolean,
    )

    private suspend fun enumerate(installed: Map<String, InstalledApp>): Enumeration? {
        val running = processes.runningApps()
        if (running is Observed.Value) {
            return Enumeration(
                candidates = running.value.map { (packageName, state) ->
                    ReclaimCandidate(packageName, labelFor(packageName, installed), state)
                },
                via = running.source,
                statesWereRead = true,
            )
        }
        val recent = processes.recentlyUsedPackages()
        if (recent is Observed.Value) {
            return Enumeration(
                candidates = recent.value.map { packageName ->
                    ReclaimCandidate(
                        packageName,
                        labelFor(packageName, installed),
                        AppProcessState.UNKNOWN,
                    )
                },
                via = recent.source,
                statesWereRead = false,
            )
        }
        return null
    }

    /** An app's own label, already sanitised by the lister, or its package name. */
    private fun labelFor(packageName: String, installed: Map<String, InstalledApp>): String =
        installed[packageName]?.label ?: packageName

    /**
     * The measured difference between the two readings, or the reason there is not one.
     *
     * [Precision.SAMPLED] rather than exact, and the distinction is not pedantry: both readings are
     * exact, but they are readings of the whole device taken a moment apart, so their difference
     * includes everything else that allocated or released in that moment — the game loading, most of
     * all. It is allowed to come out negative, and the report says so in words rather than hiding the
     * sign.
     *
     * If either reading is missing, that absence *is* the answer. A missing reading reported as zero
     * would be a claim that nothing was freed, which is a different statement from not knowing.
     */
    private fun freedBetween(before: Observed<Long>, after: Observed<Long>): Observed<Long> = when {
        before is Observed.Value && after is Observed.Value ->
            Observed.of(after.value - before.value, DataSource.ACTIVITY_MANAGER, Precision.SAMPLED)
        before !is Observed.Value -> before
        else -> after
    }

    companion object {

        /**
         * How long to wait between the last close and looking again.
         *
         * Both kill paths return as soon as the request is queued, so this is the platform's own
         * teardown, not a guess about the apps: process death, the `ActivityManager` bookkeeping that
         * follows it, and the memory actually going back. Short enough to finish inside a game's own
         * loading screen, long enough that the verification read is not just the first read again.
         */
        const val SETTLE_MILLIS = 700L

        private const val HOME_UNKNOWN =
            "GameCore could not work out which app draws your home screen, so it closed nothing. " +
                "Closing the launcher by mistake would leave a black screen when you leave the game."

        private const val IME_UNKNOWN =
            "GameCore could not work out which app is your keyboard, so it closed nothing."

        private const val APPS_UNKNOWN =
            "GameCore could not read the list of installed apps, so it had no way to tell an app you " +
                "opened from part of the system, and closed nothing."

        private const val BLIND =
            "GameCore could not see which apps were running, so it closed nothing. Start Shizuku, or " +
                "grant usage access, and it will have something to work from."

        private const val STILL_RUNNING = "Android kept it running."

        /** No reading was taken, because nothing was closed for a reading to be about. */
        private val NOT_MEASURED =
            Observed.Failed("No memory reading was taken, because nothing was closed")
    }
}

/**
 * Whether one app may be closed, with the device taken out of it.
 *
 * Split from [BackgroundAppReclaimer] because this is the whole of what makes the feature safe and it
 * is the part that can be checked without a device: given a package name, what its processes are
 * doing, and eight facts about the phone, is there a reason to leave it alone? The pass around it owns
 * the dumps, the commands and the waiting; this owns the answer.
 *
 * [reasonToSpare] walks [ProtectionReason.entries] in declaration order and returns the first reason
 * that applies, which makes the enum the single statement of precedence — there is no second ordering
 * here to drift out of step with it. The order matters for what the user reads: a persistent system
 * process whose state is unreadable is reported as [ProtectionReason.SYSTEM_APP], which is the useful
 * half of the truth, rather than as [ProtectionReason.FOREGROUND_STATE_UNKNOWN], which is also true
 * and tells them nothing.
 *
 * Every rule here only ever *adds* protection. There is no input to this object that can make an app
 * closable when another rule has spared it, which is why a corrupt allowlist or an unrecognised
 * process state costs the user nothing.
 */
internal object ReclaimFilter {

    /**
     * Everything true of the device rather than of one app.
     *
     * Flat strings and sets, no platform types, so the whole table of decisions is a JVM test.
     *
     * [statesWereRead] is the one field that is about GameCore rather than the phone: it says whether
     * [ReclaimCandidate.state] came from a process list that was actually read. When it did, a state
     * that could not be understood is a reason to leave the app alone — the dump named the process and
     * GameCore failed to read the line, and the safe reading of that is "do not touch it". When it did
     * not, every candidate carries [AppProcessState.UNKNOWN] because no state was on offer at all, and
     * treating that as suspicious would protect every app on the device and quietly turn the feature
     * off. What enforces the state rule on that path is `killBackgroundProcesses` itself, which spares
     * the foreground app and everything the platform ranks above a plain background service.
     */
    data class Protections(
        val selfPackage: String,
        val gamePackage: String,
        val homeScreenPackages: Set<String>,
        val imePackage: String?,
        val allowlist: Set<String>,
        /** Has a launcher entry and is not part of the system image. Everything else is spared. */
        val userInstalledPackages: Set<String>,
        val statesWereRead: Boolean,
    )

    /** The first reason to leave [candidate] running, or null if there is none. */
    fun reasonToSpare(candidate: ReclaimCandidate, device: Protections): ProtectionReason? =
        ProtectionReason.entries.firstOrNull { applies(it, candidate, device) }

    /**
     * Whether one reason applies to one app.
     *
     * Exhaustive with no `else`, so a tenth reason added to the enum is a compile error here rather
     * than a protection that silently never fires.
     */
    private fun applies(
        reason: ProtectionReason,
        candidate: ReclaimCandidate,
        device: Protections,
    ): Boolean = when (reason) {
        ProtectionReason.SELF -> candidate.packageName == device.selfPackage
        ProtectionReason.LAUNCHING_GAME -> candidate.packageName == device.gamePackage
        // Every launcher installed, not whichever is default: closing an app that is acting as the
        // home screen is the same black screen whichever one the user has chosen this week.
        ProtectionReason.DEFAULT_LAUNCHER ->
            candidate.packageName in device.homeScreenPackages ||
                candidate.state.protection == reason
        ProtectionReason.CURRENT_IME -> candidate.packageName == device.imePackage
        ProtectionReason.FOREGROUND_SERVICE,
        ProtectionReason.FOREGROUND_APP,
        ProtectionReason.IN_USE,
        -> candidate.state.protection == reason
        ProtectionReason.SYSTEM_APP -> candidate.packageName !in device.userInstalledPackages
        ProtectionReason.ALLOWLISTED -> candidate.packageName in device.allowlist
        ProtectionReason.FOREGROUND_STATE_UNKNOWN ->
            device.statesWereRead && candidate.state.protection == reason
    }
}
