package com.gamecore.domain.gaming

import android.content.Context
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.DetectionAvailability
import com.gamecore.core.model.StopReason
import com.gamecore.core.system.ForegroundAppWatcher
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One detection decision and when it was made.
 *
 * The decision itself is pure and lives in [DetectionPolicy]; this pairs it with the clock reading the
 * Games screen needs for "last decision at 21:14". The time only moves when the answer changes, so a
 * game left in the foreground for an hour does not keep rewriting the row.
 */
data class DetectionDecisionRecord(
    val decision: DetectionDecision,
    val atMillis: Long,
)

/**
 * Watches for the games GameCore has profiles for, and says when one starts and stops.
 *
 * Polling, not callbacks, because Android offers no callback for "the foreground app changed" to a
 * normal application. `UsageStatsManager` answers "which app was in front during this window" when
 * asked, and that is the whole of the public surface; the alternatives are an accessibility service
 * (a permission GameCore has no business asking for, and one Play policy treats as a red flag) or
 * nothing. So the loop asks, at the interval the user chose, and [ForegroundAppWatcher] decides
 * per call whether usage stats or the elevated shell answers it.
 *
 * The judgement of what the answers *mean* is not here — it is [GameWatch], a value type this class
 * feeds one reading at a time. That split is what lets §31 test the interesting cases (a game
 * dropping out for eight seconds and returning, a straight switch between two games, usage access
 * being revoked while a session runs) as a list of sightings against a fake clock, with no poll loop
 * and no `UsageStatsManager` in sight.
 *
 * Three facts about the loop that the state machine cannot know:
 *
 *  - **GameCore's own package is not "another app".** It is reported as [Sighting.Inspecting], so
 *    opening the app to look at the live graph does not end the session being looked at.
 *  - **Blind polling slows down.** If the foreground is unreadable there is nothing to see and no
 *    reason to keep asking every two seconds, so the interval stretches to [BLIND_INTERVAL_MILLIS]
 *    until a reading comes back. The loop does not stop, because usage access is granted in a
 *    Settings screen outside this app: a detector that gave up would need restarting by hand at
 *    exactly the moment the user has just fixed the problem.
 *  - **A stop can arrive between polls.** [requestStop] interrupts the wait rather than queueing
 *    behind it, so the overlay's stop button acts at once instead of up to a poll interval later.
 */
@Singleton
class GameDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foreground: ForegroundAppWatcher,
    private val profiles: GameProfileRepository,
    private val preferences: SecurePreferenceStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Manual stop requests, waiting for the poll loop to pick them up.
     *
     * [Channel.CONFLATED] so [requestStop] never suspends and never queues a second stop behind the
     * first. The loop waits on this *instead of* sleeping, so a request is acted on immediately
     * rather than at the next tick.
     */
    private val stopRequests = Channel<StopReason>(Channel.CONFLATED)

    /**
     * The last package the poll loop judged, for the Games screen's diagnostics.
     *
     * Deliberately separate from [events]: the screen reads this state, while the detection service is
     * the one collector of the cold event flow. The value survives the service being stopped, so the
     * screen can still say what happened on the last run rather than falling back to "no decision".
     */
    private val lastDecisionState = MutableStateFlow<DetectionDecisionRecord?>(null)

    /** The last detection decision, or null until the foreground has been read at least once. */
    val lastDecision: StateFlow<DetectionDecisionRecord?> = lastDecisionState.asStateFlow()

    /** Whether the foreground can be read at all, and what would fix it if not. */
    suspend fun availability(): DetectionAvailability = foreground.availability()

    /**
     * Ends the current session from outside the loop — the overlay's stop button, or a service being
     * taken down deliberately.
     *
     * Routed through the detector rather than straight to the coordinator so that a manual stop is
     * one of [GameWatch]'s own transitions: it produces the same [GameEvent.Stopped] as a detected
     * one, and it leaves the state machine knowing the game is still in front and is not to be
     * started again until the user has been somewhere else. A coordinator that closed the session
     * itself would have the detector re-announce the game two seconds later.
     *
     * Conflated and non-suspending: two stop requests a millisecond apart are one stop, and this is
     * called from a click handler.
     */
    fun requestStop(reason: StopReason) {
        stopRequests.trySend(reason)
    }

    /**
     * A cold flow of starts and stops, one collector at a time.
     *
     * Cold and per-collector on purpose: the only intended collector is the detection service, and a
     * shared hot flow would keep polling `UsageStatsManager` after that service stopped — the exact
     * background drain §26 says to avoid. Collection ends the polling.
     *
     * The enabled-profile set is observed rather than re-queried each tick, so enabling a profile
     * for a game that is already running takes effect on the next poll instead of on the next
     * session. It is primed with a direct read first, because a Room flow's first emission is
     * asynchronous and a game already in the foreground when collection starts should be noticed on
     * the first tick rather than the second.
     */
    fun events(
        graceMillis: Long = GameWatch.DEFAULT_GRACE_MILLIS,
        blindMillis: Long = GameWatch.DEFAULT_BLIND_MILLIS,
    ): Flow<GameEvent> = channelFlow {
        // Primed with a direct read before the flow has emitted: a game already in the foreground when
        // collection starts should be noticed on the first tick, not the second.
        val enabled = MutableStateFlow(profilePackages())
        launch {
            // The enabled set is observed rather than re-queried each tick. The exclusions layered on top
            // of it are read fresh in [sight], so adding one while a game is already running takes effect
            // on the next poll instead of waiting for the next profile edit.
            profiles.profiles.collect { list ->
                enabled.value = list.filter { it.isEnabled }.map { it.packageName }.toSet()
            }
        }

        var watch = GameWatch.INITIAL
        var stop: StopReason? = null
        while (isActive) {
            val requested = stop
            stop = null
            val nowMillis = System.currentTimeMillis()
            watch = if (requested != null) {
                watch.stopped(requested, nowMillis)
            } else {
                watch.saw(
                    sight(
                        enabled = enabled.value,
                        settings = preferences.settings.value,
                        atMillis = nowMillis,
                    ),
                    nowMillis,
                    graceMillis,
                    blindMillis,
                )
            }
            for (event in watch.events) send(event)
            watch = watch.consumed()

            // Sleeping and listening for a stop are the same wait: whichever happens first decides
            // what the next iteration does, and neither can be missed while the other is pending.
            stop = withTimeoutOrNull(intervalFor(watch)) { stopRequests.receive() }
        }
    }.flowOn(io)

    // ------------------------------------------------------------------------- one reading

    /**
     * Turns one foreground reading into a [Sighting], and records the decision behind it.
     *
     * The decision is published even when it is [Sighting.Other]: "no enabled profile" is the answer a
     * user looking at a game they forgot to configure needs to see. GameCore's own package is skipped
     * before that, because opening GameCore to read this screen is not a statement about any game.
     *
     * The tracked set is derived here from the same [DetectionPolicy] and the same settings snapshot as
     * the decision, rather than from a set cached when settings last changed. That makes an exclusion
     * effective on the very next poll, and a pin on a profile that has since been disabled a badge
     * problem rather than a session: the tracked set starts from enabled profiles only.
     */
    private suspend fun sight(
        enabled: Set<String>,
        settings: AppSettings,
        atMillis: Long,
    ): Sighting {
        val reading = foreground.current()
        val app = (reading as? Observed.Value)?.value
            ?: return Sighting.Unreadable(detailOf(reading))
        if (app.packageName == context.packageName) return Sighting.Inspecting

        val decision = DetectionPolicy.decide(
            packageName = app.packageName,
            enabledProfilePackages = enabled,
            excluded = settings.detectionExcludedPackages,
            pinned = settings.pinnedPackage,
        )
        publish(decision, atMillis)

        return if (app.packageName in trackedPackages(enabled, settings)) {
            Sighting.Tracked(app.packageName)
        } else {
            Sighting.Other
        }
    }

    /** Publishes a changed answer; an unchanged one keeps the time it was first reached. */
    private fun publish(decision: DetectionDecision, atMillis: Long) {
        if (lastDecisionState.value?.decision == decision) return
        lastDecisionState.value = DetectionDecisionRecord(decision, atMillis)
    }

    private fun detailOf(reading: Observed<*>): String = when (reading) {
        is Observed.Restricted -> reading.detail
        is Observed.Failed -> reading.detail
        else -> ""
    }.ifBlank { reading.unavailabilityText() ?: UNKNOWN_REASON }

    /** Every enabled profile package, before the user's exclusion list is applied. */
    private suspend fun profilePackages(): Set<String> =
        profiles.enabledProfiles().map { it.packageName }.toSet()

    /**
     * The packages the detector may act on.
     *
     * The same [DetectionPolicy] the diagnostics report is used here, so the reason on the Games screen
     * and the decision to track cannot disagree. Pins contribute no new packages: this list starts from
     * enabled profiles, and a pin only changes the reason for one that is already in it.
     */
    private fun trackedPackages(enabled: Set<String>, settings: AppSettings): Set<String> = enabled
        .filter {
            DetectionPolicy.decide(
                packageName = it,
                enabledProfilePackages = enabled,
                excluded = settings.detectionExcludedPackages,
                pinned = settings.pinnedPackage,
            ).shouldTrack
        }
        .toSet()

    /**
     * How long to wait before the next reading.
     *
     * The user's interval normally, stretched while blind. Clamped even though
     * [SecurePreferenceStore] normalises on read: this loop runs for the length of a gaming session
     * and a zero here would be a busy loop reporting its own CPU usage.
     */
    private fun intervalFor(watch: GameWatch): Long {
        val chosen = preferences.settings.value.detectionIntervalMillis
            .coerceAtLeast(AppSettings.MIN_DETECTION_INTERVAL)
        return if (watch.isBlind) maxOf(chosen, BLIND_INTERVAL_MILLIS) else chosen
    }

    private companion object {
        /** Polling interval while the foreground cannot be read at all. */
        const val BLIND_INTERVAL_MILLIS = 6_000L

        const val UNKNOWN_REASON = "The foreground app could not be read."
    }
}
