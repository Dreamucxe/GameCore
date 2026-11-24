package com.gamecore.core.plugin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plugin's health, derived from state the app already has.
 *
 * There is no backend, no rating and no reputation score here. The two honest sources of evidence are
 * the registry's own record of the plugin (enabled, suspended, quarantined, compatible) and the audit
 * ring of calls the host has already decided on this run. Everything on this file is a pure reading of
 * those two, so the manager and the detail screen cannot disagree about whether a plugin is healthy,
 * and a test can prove the verdict without an Android device.
 *
 * [OFF] and [PAUSED] are deliberately not problems. A plugin the user switched off is a decision, not
 * a fault, and the same is true of every plugin while the global kill switch is on.
 */
enum class PluginHealthState(val label: String, val tone: PluginHealthTone) {
    QUARANTINED("Quarantined", PluginHealthTone.DANGER),
    UNSUPPORTED("Unsupported", PluginHealthTone.WARNING),
    SUSPENDED("Stopped", PluginHealthTone.WARNING),
    ATTENTION("Needs attention", PluginHealthTone.WARNING),
    PAUSED("Paused", PluginHealthTone.MUTED),
    OFF("Off", PluginHealthTone.MUTED),
    HEALTHY("Healthy", PluginHealthTone.GOOD),
    ;

    /** True for the states a user should look at, as opposed to choices and pauses. */
    val isProblem: Boolean
        get() = this == QUARANTINED || this == UNSUPPORTED || this == SUSPENDED || this == ATTENTION
}

/** How the UI paints a health state. Kept independent of Compose so the model stays testable. */
enum class PluginHealthTone { GOOD, WARNING, DANGER, MUTED }

/**
 * One plugin's health verdict, with the counts that produced it.
 *
 * [refusedCalls] is intentionally not a problem on its own: the permission gate refusing an
 * out-of-grant call is the system doing its job. It becomes [PluginHealthState.ATTENTION] only when a
 * plugin repeatedly tries, which is the pattern a user should see. [failedCalls] and
 * [rateLimitedCalls] are problems at the first occurrence because both mean the plugin is not working
 * as declared.
 */
data class PluginHealth(
    val pluginId: String,
    val name: String,
    val state: PluginHealthState,
    val summary: String,
    val detail: String,
    val allowedCalls: Int = 0,
    val refusedCalls: Int = 0,
    val rateLimitedCalls: Int = 0,
    val failedCalls: Int = 0,
    val lastActivityMillis: Long = 0L,
    val lastActivityLabel: String? = null,
) {
    val hasActivity: Boolean
        get() = allowedCalls + refusedCalls + rateLimitedCalls + failedCalls > 0

    companion object {

        /**
         * The verdict for one plugin.
         *
         * The order is the order of authority: quarantine and incompatibility are facts about whether
         * the plugin may run at all; suspension is the supervisor's decision; the user's own switch is
         * next; the global kill switch outranks audit noise because with it on no call can succeed.
         * Only then does the audit ring decide between attention and healthy.
         */
        fun assess(
            plugin: InstalledPlugin,
            audit: List<AuditEntry> = emptyList(),
            killSwitchOn: Boolean = false,
        ): PluginHealth {
            val name = plugin.name.ifBlank { plugin.id }
            val allowed = audit.count { it.outcome == AuditEntry.Outcome.ALLOWED }
            val refused = audit.count { it.outcome == AuditEntry.Outcome.DENIED }
            val rateLimited = audit.count { it.outcome == AuditEntry.Outcome.RATE_LIMITED }
            val failed = audit.count { it.outcome == AuditEntry.Outcome.ERROR }
            val last = audit.maxByOrNull { it.timestamp }

            val state: PluginHealthState
            val summary: String
            val detail: String
            when {
                plugin.quarantined -> {
                    state = PluginHealthState.QUARANTINED
                    summary = "Held back until you release it"
                    detail = plugin.quarantineDetail?.takeIf { it.isNotBlank() }
                        ?: "GameCore quarantined this plugin. Its permissions and files are still " +
                        "here; nothing it declares is loaded while it is held."
                }

                !plugin.manifest.compatibility.isRunnable -> {
                    state = PluginHealthState.UNSUPPORTED
                    summary = "This build cannot run it"
                    detail = plugin.manifest.compatibility.reason
                }

                plugin.suspended -> {
                    state = PluginHealthState.SUSPENDED
                    summary = "Stopped for this run"
                    detail = plugin.suspensionDetail?.takeIf { it.isNotBlank() }
                        ?: "The supervisor stopped this plugin after repeated failures or rate-limit " +
                        "abuse. Re-enabling it clears the suspension."
                }

                !plugin.enabled -> {
                    state = PluginHealthState.OFF
                    summary = "Off"
                    detail = "You switched this plugin off. Its permissions and data are kept."
                }

                killSwitchOn -> {
                    state = PluginHealthState.PAUSED
                    summary = "Paused by the plugin off switch"
                    detail = "The global switch is on, so no plugin can run. This one is still enabled " +
                        "and starts again when you turn the switch off."
                }

                rateLimited > 0 -> {
                    state = PluginHealthState.ATTENTION
                    summary = "Hit its call limit"
                    detail = callDetail(
                        heading = "This plugin was rate-limited $rateLimited " +
                            (if (rateLimited == 1) "time" else "times") + " this run.",
                        allowed = allowed,
                        refused = refused,
                        failed = failed,
                        rateLimited = rateLimited,
                    )
                }

                failed > 0 -> {
                    state = PluginHealthState.ATTENTION
                    summary = "Recent calls failed"
                    detail = callDetail(
                        heading = "The host recorded $failed failed " +
                            (if (failed == 1) "call" else "calls") + " from this plugin this run.",
                        allowed = allowed,
                        refused = refused,
                        failed = failed,
                        rateLimited = rateLimited,
                    )
                }

                refused >= REPEATED_REFUSAL_THRESHOLD -> {
                    state = PluginHealthState.ATTENTION
                    summary = "Repeated calls outside its grant"
                    detail = callDetail(
                        heading = "This plugin tried $refused calls the permission gate refused this " +
                            "run. One refusal is the gate working; a pattern is worth reviewing.",
                        allowed = allowed,
                        refused = refused,
                        failed = failed,
                        rateLimited = rateLimited,
                    )
                }

                else -> {
                    state = PluginHealthState.HEALTHY
                    summary = if (audit.isEmpty()) "No calls recorded this run" else "Running normally"
                    detail = if (audit.isEmpty()) {
                        "Nothing has called the plugin API through this plugin since GameCore started. " +
                            "That is normal for a theme or a screen a game has not reached yet."
                    } else {
                        callDetail(
                            heading = "No failed, rate-limited or repeated out-of-grant calls this run.",
                            allowed = allowed,
                            refused = refused,
                            failed = failed,
                            rateLimited = rateLimited,
                        )
                    }
                }
            }

            return PluginHealth(
                pluginId = plugin.id,
                name = name,
                state = state,
                summary = summary,
                detail = detail,
                allowedCalls = allowed,
                refusedCalls = refused,
                rateLimitedCalls = rateLimited,
                failedCalls = failed,
                lastActivityMillis = last?.timestamp ?: 0L,
                lastActivityLabel = last?.let(::activityLabel),
            )
        }

        /** Counts over a set of verdicts, for the manager's summary card. */
        fun summarize(health: Collection<PluginHealth>): PluginHealthSummary =
            PluginHealthSummary(
                total = health.size,
                healthy = health.count { it.state == PluginHealthState.HEALTHY },
                attention = health.count { it.state == PluginHealthState.ATTENTION },
                stopped = health.count { it.state == PluginHealthState.SUSPENDED },
                quarantined = health.count { it.state == PluginHealthState.QUARANTINED },
                unsupported = health.count { it.state == PluginHealthState.UNSUPPORTED },
                paused = health.count { it.state == PluginHealthState.PAUSED },
                off = health.count { it.state == PluginHealthState.OFF },
            )

        private fun callDetail(
            heading: String,
            allowed: Int,
            refused: Int,
            failed: Int,
            rateLimited: Int,
        ): String = buildString {
            appendLine(heading)
            appendLine()
            appendLine("This run:")
            appendLine("· ${allowed} allowed")
            appendLine("· ${refused} refused by the permission gate")
            appendLine("· ${failed} failed")
            append("· ${rateLimited} rate-limited")
        }

        private fun activityLabel(entry: AuditEntry): String {
            val at = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(entry.timestamp))
            val outcome = when (entry.outcome) {
                AuditEntry.Outcome.ALLOWED -> "allowed"
                AuditEntry.Outcome.DENIED -> "refused"
                AuditEntry.Outcome.CLAMPED -> "clamped"
                AuditEntry.Outcome.RATE_LIMITED -> "rate-limited"
                AuditEntry.Outcome.ERROR -> "failed"
            }
            return "$at · ${entry.operation.take(60)} · $outcome"
        }

        /** Two refusals is a mistake; three is a pattern worth putting in front of the user. */
        private const val REPEATED_REFUSAL_THRESHOLD = 3
    }
}

/**
 * The manager's whole-list reading.
 *
 * [problemCount] is the number a person needs: it excludes plugins they switched off and excludes the
 * global pause, because neither is a fault. The headline says which of those situations applies rather
 * than reporting "0 healthy" beside a list of plugins the user deliberately turned off.
 */
data class PluginHealthSummary(
    val total: Int = 0,
    val healthy: Int = 0,
    val attention: Int = 0,
    val stopped: Int = 0,
    val quarantined: Int = 0,
    val unsupported: Int = 0,
    val paused: Int = 0,
    val off: Int = 0,
) {
    val problemCount: Int get() = attention + stopped + quarantined + unsupported

    val headline: String
        get() = when {
            total == 0 -> "No plugins installed"
            problemCount == 1 -> "1 of $total plugins needs attention"
            problemCount > 1 -> "$problemCount of $total plugins need attention"
            paused > 0 && healthy == 0 -> "Plugins are paused by the global off switch"
            off == total -> "Every plugin is switched off"
            healthy == 0 -> "No plugin is running right now"
            else -> "$healthy of $total plugins running normally"
        }

    val isEmpty: Boolean get() = total == 0
}
