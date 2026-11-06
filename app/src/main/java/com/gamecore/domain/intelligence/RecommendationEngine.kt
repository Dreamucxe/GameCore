package com.gamecore.domain.intelligence

import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.CpuAffinityPreset
import com.gamecore.core.model.DeviceScan
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.LearningSummary
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.RecommendationClass
import com.gamecore.core.model.RecommendationEvidence
import com.gamecore.core.model.RecommendationTarget
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.model.SmartProfileField
import com.gamecore.core.model.SmartRecommendation
import com.gamecore.core.model.ThermalClass
import kotlin.math.abs
import java.util.Locale

/**
 * Turns measured device facts into recommendations, with the evidence and the limits attached.
 *
 * Everything here is a local rule over a [DeviceScan], an optional real [BenchmarkRun], the current
 * game's profile and the outcomes already recorded on this device. There is no server, no model and
 * no per-device database of "known good settings": a recommendation exists only when a field the
 * scanner actually read supports it.
 *
 * Three rules keep the output honest:
 *
 *  1. **No fabricated effect.** Pinning a refresh rate changes the panel, not the game's render
 *     loop, and every sentence says so. Disabling animations changes window transitions, not
 *     frames inside the game. CPU affinity is a test, not a speed-up.
 *  2. **Compatibility is explicit.** A recommendation whose mechanism is not usable is never
 *     returned. A control that needs Shizuku is shown as needing Shizuku, not as available.
 *  3. **Confidence is a count, not a feeling.** It starts from the number of independent readings
 *     behind the rule, rises when a benchmark is present, and is adjusted by recorded outcomes.
 *     It never exceeds [MAX_CONFIDENCE] because a finite set of sessions on one phone cannot
 *     justify certainty.
 *
 * The engine is pure and deterministic. Sorting is stable and the same facts always produce the
 * same list, which is what lets a JVM test assert the exact recommendation for a device.
 */
object RecommendationEngine {

    /** No recommendation claims more than this. A local rule set has no 100% case. */
    const val MAX_CONFIDENCE = 95

    /** A confidence below this is reported as experimental rather than recommended. */
    const val RECOMMENDED_CONFIDENCE = 70

    /** Confidence at which an action is presented as strongly recommended. */
    const val STRONG_CONFIDENCE = 85

    /** Below this the recommendation is dropped instead of being shown as advice. */
    const val MIN_USEFUL_CONFIDENCE = 45

    /** A battery level at or below this makes battery saver urgent, not optional. */
    const val LOW_BATTERY_PERCENT = 20

    /** A measured drain at or above this is evidence that the session is expensive. */
    const val HIGH_DRAIN_PERCENT_PER_HOUR = 20f

    /** Peak memory use at or above this means the run spent time near the platform's limit. */
    const val HIGH_MEMORY_PERCENT = 92f

    /** Average temperature at or above this (tenths °C) is warm enough to act on. */
    const val WARM_TEMPERATURE_DECI = 550

    /** Jank above this makes frame pacing the problem rather than average speed. */
    const val HIGH_JANK_PERCENT = 10f

    /** Frame-window variation above this is visible as uneven pacing. */
    const val HIGH_VARIATION_PERCENT = 12f

    /** Free RAM below this fraction of total is pressure worth reporting. */
    const val LOW_AVAILABLE_RAM_FRACTION = 0.10f

    fun recommend(
        scan: DeviceScan,
        goal: OptimizationGoal,
        benchmark: BenchmarkRun? = null,
        learning: Map<String, LearningSummary> = emptyMap(),
        gamePackage: String? = null,
        gameLabel: String? = null,
        profile: GameProfile? = null,
        sessions: List<GameSession> = emptyList(),
    ): List<SmartRecommendation> {
        val facts = Facts(scan, goal, benchmark, learning, gamePackage, gameLabel, profile, sessions)
        return buildList {
            refresh(facts)?.let(::add)
            thermal(facts)?.let(::add)
            battery(facts)?.let(::add)
            fullPerformance(facts)?.let(::add)
            memory(facts)?.let(::add)
            affinity(facts)?.let(::add)
            animations(facts)?.let(::add)
        }.sortedWith(
            compareBy(
                { it.classification.rank },
                { -it.confidencePercent },
                { it.title },
            ),
        )
    }

    private data class Facts(
        val scan: DeviceScan,
        val goal: OptimizationGoal,
        val benchmark: BenchmarkRun?,
        val learning: Map<String, LearningSummary>,
        val gamePackage: String?,
        val gameLabel: String?,
        val profile: GameProfile?,
        val sessions: List<GameSession>,
    )

    // --------------------------------------------------------------------------------- refresh rate

    private fun refresh(facts: Facts): SmartRecommendation? {
        val scan = facts.scan
        val rates = scan.supportedRefreshRates.filter { it > 0f }.distinct().sorted()
        if (rates.size < 2) return null

        val peak = rates.last()
        val lowest = rates.first()
        val current = scan.displayRefreshRateHz
        val mechanism = scan.capabilities.refreshRateMechanism
        // A per-window preference can affect only GameCore's own Activity. It cannot pin the game
        // or the device, so treating it as a usable recommendation would offer a button whose real
        // scope does not match its words.
        if (mechanism == RefreshRateMechanism.NONE ||
            mechanism == RefreshRateMechanism.WINDOW_PREFERENCE
        ) {
            return null
        }

        val hot = scan.thermalClass.level.severity >= ThermalClass.WARM.severity
        val wantsLower = facts.goal == OptimizationGoal.COOLER_GAMING ||
            facts.goal == OptimizationGoal.BATTERY_EFFICIENCY
        val wantsHigher = facts.goal == OptimizationGoal.MAXIMUM_PERFORMANCE ||
            facts.goal == OptimizationGoal.STABLE_FPS

        val targetRate: Float
        val action: RecommendationAction
        var confidence: Int
        if (wantsLower) {
            if (current != null && current <= lowest + 0.5f) return null
            targetRate = lowest
            action = RecommendationAction.PIN_LOWEST_REFRESH_RATE
            confidence = 68
            if (hot) confidence += 6
            val drain = facts.benchmark?.summary?.batteryDrainPerHour
            if (drain != null && drain >= HIGH_DRAIN_PERCENT_PER_HOUR) confidence += 4
            val temp = facts.benchmark?.summary?.averageTemperatureDeciCelsius
            if (temp != null && temp >= WARM_TEMPERATURE_DECI) confidence += 4
        } else if (wantsHigher && !hot) {
            if (current != null && current >= peak - 0.5f) return null
            targetRate = peak
            action = RecommendationAction.PIN_PEAK_REFRESH_RATE
            confidence = 64
            val measuredRefresh = facts.benchmark?.summary?.averageRefreshRateHz
            if (measuredRefresh != null && measuredRefresh < peak - 1f) confidence += 8
            if (facts.benchmark != null) confidence += 3
            if (facts.sessions.any { (it.averageRefreshRate ?: peak) < peak - 1f }) confidence += 4
        } else {
            return null
        }

        if (facts.profile?.targetRefreshRate?.let { abs(it - targetRate) < 0.5f } == true) return null

        val currentText = current?.let(::formatHz) ?: "Not reported by this build"
        val proposedText = formatHz(targetRate)
        val reason = if (wantsLower) {
            "The panel advertises $proposedText and the current mode is $currentText. Your goal is " +
                "${facts.goal.label.lowercase()}, and the panel is usually the largest single power " +
                "draw while a game runs. Holding the lowest rate is the measurable saving available " +
                "here."
        } else {
            val measured = facts.benchmark?.summary?.averageRefreshRateHz
            if (measured != null && measured < peak - 1f) {
                "The panel advertises up to $proposedText but the benchmark averaged " +
                    "${formatHz(measured)}, so the platform was choosing a lower mode during play. " +
                    "Pinning the peak removes that switching; it does not make the game render more " +
                    "frames."
            } else {
                "The panel advertises $proposedText and the current mode is $currentText. Pinning " +
                    "holds the panel at its ceiling so Android cannot drop it when the screen looks " +
                    "static. It does not raise the game's own frame rate."
            }
        }

        val evidence = buildList {
            add(RecommendationEvidence("Display modes", rates.joinToString(", ") { formatHz(it) }))
            add(
                RecommendationEvidence(
                    "Current mode",
                    current?.let(::formatHz) ?: "Display.getRefreshRate() was not readable.",
                ),
            )
            add(
                RecommendationEvidence(
                    "Refresh control",
                    "${mechanism.label}: ${mechanism.explanation}",
                ),
            )
            facts.benchmark?.let { run ->
                add(
                    RecommendationEvidence(
                        "Benchmark",
                        "${run.profile.label}, ${run.summary.sampleCount} samples over " +
                            "${minutes(run.summary.durationMillis)}" +
                            (run.summary.averageRefreshRateHz?.let { ", average ${formatHz(it)}" } ?: ""),
                    ),
                )
            }
            val historicalRates = facts.sessions.mapNotNull { it.averageRefreshRate }
            if (historicalRates.isNotEmpty()) {
                add(
                    RecommendationEvidence(
                        "Gaming history",
                        "${historicalRates.size} recorded session(s), average display rate " +
                            formatHz(historicalRates.average().toFloat()),
                    ),
                )
            }
            if (hot) {
                add(
                    RecommendationEvidence(
                        "Thermal state",
                        scan.thermalClass.label + temperatureSuffix(scan),
                    ),
                )
            }
        }

        return finalize(
            facts = facts,
            action = action,
            current = currentText,
            proposed = proposedText,
            reason = reason,
            tradeoff = if (wantsLower) {
                "The interface and the game's presentation update less often, which can feel less " +
                    "responsive. It saves panel power and heat, and it does not make the game's " +
                    "renderer faster or slower by itself."
            } else {
                "The higher ceiling cannot raise the game's own frame rate, and it can add heat and " +
                    "battery drain. A device that is already warm is offered a cooling action " +
                    "instead of this one."
            },
            compatibility = CapabilityStatus.AVAILABLE,
            baseConfidence = confidence,
            requiresTesting = false,
            evidence = evidence,
            targetRefreshRateHz = targetRate,
        )
    }

    // ------------------------------------------------------------------------------------ thermal

    private fun thermal(facts: Facts): SmartRecommendation? {
        // A profile field only exists per game. With no game selected there is nothing to write it
        // to, so the honest output is no recommendation rather than one with no target.
        if (facts.gamePackage == null) return null
        val scan = facts.scan
        if (facts.profile?.thermalDownshiftEnabled == true) return null

        val classification = scan.thermalClass
        val benchmarkTemp = facts.benchmark?.summary?.averageTemperatureDeciCelsius
        val temperature = scan.cpuTemperatureDeciCelsius ?: scan.batteryTemperatureDeciCelsius
        val thermalEvidenceExists = temperature != null ||
            scan.capabilities.thermalStatusSupported ||
            benchmarkTemp != null
        if (!thermalEvidenceExists) return null

        val fromScan = classification.level.severity >= ThermalClass.WARM.severity
        val fromBenchmark = benchmarkTemp != null && benchmarkTemp >= WARM_TEMPERATURE_DECI
        val hotSessions = facts.sessions.filter { session ->
            (session.averageTemperatureDeciCelsius ?: Int.MIN_VALUE) >= WARM_TEMPERATURE_DECI ||
                (session.peakTemperatureDeciCelsius ?: Int.MIN_VALUE) >= WARM_TEMPERATURE_DECI
        }
        if (!fromScan && !fromBenchmark && hotSessions.isEmpty()) return null

        val confidence = when {
            classification.level.severity >= ThermalClass.CRITICAL.severity -> 90
            classification.level.severity >= ThermalClass.HOT.severity -> 84
            classification.level.severity >= ThermalClass.WARM.severity -> 66
            else -> 60
        } + (if (fromBenchmark) 4 else 0) + minOf(hotSessions.size * 2, 8)

        val proposedTemperature = when {
            fromScan -> temperature
            fromBenchmark -> benchmarkTemp
            hotSessions.isNotEmpty() -> WARM_TEMPERATURE_DECI
            else -> null
        }
        val proposed = when {
            proposedTemperature != null ->
                "Downshift at ${formatTemperature(proposedTemperature)} or when Android reports throttling"
            else -> "Downshift on Android's own thermal status"
        }

        return finalize(
            facts = facts,
            action = RecommendationAction.ENABLE_THERMAL_DOWNSHIFT,
            current = if (facts.profile == null) {
                "No game profile is selected, so no thermal behaviour is configured"
            } else {
                "Off for this game's profile"
            },
            proposed = proposed,
            reason = buildString {
                append("The device is ")
                append(scan.thermalClass.label.lowercase())
                temperature?.let { append(" at ${formatTemperature(it)}") }
                benchmarkTemp?.let {
                    append(" and the benchmark averaged ${formatTemperature(it)}")
                }
                if (hotSessions.isNotEmpty()) {
                    append("; ${hotSessions.size} recorded session(s) also reached warm temperatures")
                }
                append(". The profile's auto-cooling can lower the refresh-rate floor before Android ")
                append("decides to clamp the whole device, which is the only thermal control GameCore ")
                append("actually has.")
            },
            tradeoff = "Frames may drop earlier than the device would have throttled by itself. The " +
                "trade is a cooler session that is more likely to hold a steady rate, not a faster one.",
            compatibility = CapabilityStatus.AVAILABLE,
            baseConfidence = confidence,
            requiresTesting = false,
            evidence = buildList {
                add(
                    RecommendationEvidence(
                        "Thermal classification",
                        "${scan.thermalClass.label}: sensor and platform status combined" +
                            temperatureSuffix(scan),
                    ),
                )
                val peakTemperature = facts.benchmark?.summary?.peakTemperatureDeciCelsius
                benchmarkTemp?.let {
                    add(
                        RecommendationEvidence(
                            "Benchmark temperature",
                            "Average ${formatTemperature(it)}, peak " +
                                (peakTemperature?.let(::formatTemperature) ?: "not recorded"),
                        ),
                    )
                }
                if (hotSessions.isNotEmpty()) {
                    add(
                        RecommendationEvidence(
                            "Gaming history",
                            "${hotSessions.size} of ${facts.sessions.size} recorded session(s) " +
                                "reached at least ${formatTemperature(WARM_TEMPERATURE_DECI)}",
                        ),
                    )
                }
                add(
                    RecommendationEvidence(
                        "Profile field",
                        "thermalDownshiftEnabled, applied only while this game runs",
                    ),
                )
            },
        )
    }

    // ------------------------------------------------------------------------------------ battery

    private fun battery(facts: Facts): SmartRecommendation? {
        val scan = facts.scan
        if (scan.batteryPowerSave || scan.batteryCharging) return null
        val status = scan.capabilities.batterySaverControl
        if (!status.isUsable) return null

        val level = scan.batteryLevelPercent
        val drain = facts.benchmark?.summary?.batteryDrainPerHour
        val historicalDrain = facts.sessions.mapNotNull { it.drain()?.percentPerHour }
        val averageHistoricalDrain = historicalDrain.takeIf { it.isNotEmpty() }
            ?.average()?.toFloat()
        val low = level in 1..LOW_BATTERY_PERCENT
        val measuredHighDrain = drain != null && drain >= HIGH_DRAIN_PERCENT_PER_HOUR
        val recurringHighDrain = averageHistoricalDrain != null &&
            averageHistoricalDrain >= HIGH_DRAIN_PERCENT_PER_HOUR
        val goalWants = facts.goal == OptimizationGoal.BATTERY_EFFICIENCY
        if (!low && !measuredHighDrain && !recurringHighDrain && !goalWants) return null

        val confidence = when {
            low && level <= 10 -> 90
            low -> 84
            measuredHighDrain -> 76
            recurringHighDrain -> 72
            else -> 60
        } + if (facts.benchmark != null) 3 else 0

        return finalize(
            facts = facts,
            action = RecommendationAction.ENABLE_BATTERY_SAVER,
            current = if (level >= 0) "Battery saver is off at $level%" else "Battery saver is off",
            proposed = "Turn on Android's own battery saver",
            reason = buildString {
                when {
                    low -> append("The battery is at $level% and no charger is connected. ")
                    measuredHighDrain -> append(
                        "The benchmark measured ${formatRate(drain!!)}, which is expensive for a " +
                            "session. ",
                    )
                    recurringHighDrain -> append(
                        "${historicalDrain.size} recorded session(s) averaged " +
                            "${formatRate(averageHistoricalDrain!!)}. ",
                    )
                    else -> append("Your goal is battery efficiency. ")
                }
                append("Android's battery saver is a real platform mode, not a GameCore invention, ")
                append("and the capability probe confirmed it can be toggled here.")
            },
            tradeoff = "Battery saver limits background activity and can cap the display refresh rate " +
                "and reduce background sync. It does not close the foreground game. Android may also " +
                "reduce vibration and location accuracy while it is on.",
            compatibility = status,
            baseConfidence = confidence,
            requiresTesting = false,
            evidence = buildList {
                add(
                    RecommendationEvidence(
                        "Battery",
                        "${if (level >= 0) "$level%" else "level not reported"}, " +
                            (if (scan.batteryCharging) "charging" else "not charging"),
                    ),
                )
                drain?.let {
                    add(RecommendationEvidence("Measured drain", "${formatRate(it)} during the benchmark"))
                }
                averageHistoricalDrain?.let {
                    add(
                        RecommendationEvidence(
                            "Gaming history",
                            "${historicalDrain.size} reliable session drain rate(s), " +
                                "average ${formatRate(it)}",
                        ),
                    )
                }
                add(RecommendationEvidence("Battery saver control", status.label))
            },
        )
    }

    // -------------------------------------------------------------------------- Android battery saver

    private fun fullPerformance(facts: Facts): SmartRecommendation? {
        if (facts.gamePackage == null) return null
        val scan = facts.scan
        if (facts.goal != OptimizationGoal.MAXIMUM_PERFORMANCE &&
            facts.goal != OptimizationGoal.STABLE_FPS
        ) {
            return null
        }
        if (!scan.batteryPowerSave) return null
        if (facts.profile?.fullPerformanceEnabled == true) return null
        val status = scan.capabilities.batterySaverControl
        if (!status.isUsable) return null

        return finalize(
            facts = facts,
            action = RecommendationAction.KEEP_FULL_PERFORMANCE,
            current = "Android battery saver is on",
            proposed = "Keep full performance for this game's session",
            reason = "Android's battery saver is active. On most builds it caps the display refresh " +
                "rate and defers background work, which is exactly the opposite of the goal you chose. " +
                "The profile's full-performance switch turns it off for the session and restores it " +
                "when the game closes.",
            tradeoff = "Power draw and heat rise and battery life falls. The switch covers only the " +
                "time this game is running.",
            compatibility = status,
            baseConfidence = 74,
            requiresTesting = false,
            evidence = listOf(
                RecommendationEvidence("Platform state", "PowerManager battery saver is active"),
                RecommendationEvidence("Profile field", "fullPerformanceEnabled"),
            ),
        )
    }

    // ------------------------------------------------------------------------------------- memory

    private fun memory(facts: Facts): SmartRecommendation? {
        if (facts.gamePackage == null) return null
        val scan = facts.scan
        if (facts.profile?.freeRamOnLaunch == true) return null
        if (!scan.capabilities.hasElevatedAccess) return null

        val availableFraction = if (scan.totalRamBytes > 0L) {
            (scan.availableRamBytes.toFloat() / scan.totalRamBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        val lowNow = scan.lowMemory ||
            (availableFraction != null && availableFraction < LOW_AVAILABLE_RAM_FRACTION)
        val peak = facts.benchmark?.summary?.peakMemoryPercent
        val measuredPressure = peak != null && peak >= HIGH_MEMORY_PERCENT
        val pressuredSessions = facts.sessions.filter {
            (it.peakMemoryPercent ?: 0f) >= HIGH_MEMORY_PERCENT
        }
        if (!lowNow && !measuredPressure && pressuredSessions.isEmpty()) return null

        var confidence = 56
        if (scan.lowMemory) confidence += 6
        if (measuredPressure) confidence += 6
        confidence += minOf(pressuredSessions.size * 2, 6)
        if (scan.totalRamBytes in 1 until DeviceIntelligence.LOW_RAM_BYTES) confidence += 4

        return finalize(
            facts = facts,
            action = RecommendationAction.FREE_RAM_ON_LAUNCH,
            current = "Off for this game's profile",
            proposed = "Close background apps when ${facts.gameLabel ?: "the game"} starts",
            reason = buildString {
                when {
                    scan.lowMemory -> append("Android's low-memory flag was set during the scan. ")
                    measuredPressure -> append(
                        "The benchmark peaked at ${formatPercent(peak!!)} memory use. ",
                    )
                    availableFraction != null -> append(
                        "${formatPercent(availableFraction * 100f)} of RAM was free at the scan. ",
                    )
                }
                append("The profile's free-RAM pass asks the platform to close background apps before ")
                append("the game starts and reports the memory delta it actually measured. It cannot ")
                append("add memory the device does not have.")
            },
            tradeoff = "Other apps are closed, and Android decides which ones. The measured freed " +
                "memory can be small or negative because the game is loading at the same time. The " +
                "pass reports what happened rather than a promised number.",
            compatibility = CapabilityStatus.AVAILABLE,
            baseConfidence = confidence,
            requiresTesting = false,
            evidence = buildList {
                add(
                    RecommendationEvidence(
                        "Memory now",
                        if (scan.totalRamBytes > 0L) {
                            "${formatGib(scan.availableRamBytes)} GB available of " +
                                "${formatGib(scan.totalRamBytes)} GB" +
                                (availableFraction?.let { " (${formatPercent(it * 100f)})" } ?: "")
                        } else {
                            "Total RAM was not readable."
                        },
                    ),
                )
                if (scan.lowMemory) {
                    add(RecommendationEvidence("Platform flag", "ActivityManager lowMemory = true"))
                }
                peak?.let {
                    add(RecommendationEvidence("Benchmark peak", "${formatPercent(it)} memory use"))
                }
                if (pressuredSessions.isNotEmpty()) {
                    add(
                        RecommendationEvidence(
                            "Gaming history",
                            "${pressuredSessions.size} of ${facts.sessions.size} recorded session(s) " +
                                "peaked at or above ${formatPercent(HIGH_MEMORY_PERCENT)} memory use",
                        ),
                    )
                }
                add(RecommendationEvidence("Profile field", "freeRamOnLaunch"))
            },
        )
    }

    // ----------------------------------------------------------------------------------- CPU affinity

    private fun affinity(facts: Facts): SmartRecommendation? {
        if (facts.gamePackage == null) return null
        val scan = facts.scan
        if (facts.profile?.cpuAffinity != null) return null
        if (!scan.capabilities.hasElevatedAccess) return null
        if (!scan.capabilities.perCoreFrequenciesReadable) return null
        val clusters = scan.cpuClusters.filter { it.coreIndices.isNotEmpty() }
        if (clusters.size < 2) return null

        val classification = scan.thermalClass
        val hot = classification.level.severity >= ThermalClass.WARM.severity
        val summary = facts.benchmark?.summary
        val jank = summary?.jankPercent
        val variation = summary?.frameVariationPercent
        val unstablePacing = (jank != null && jank >= HIGH_JANK_PERCENT) ||
            (variation != null && variation >= HIGH_VARIATION_PERCENT)
        if (!hot && !unstablePacing) return null

        val preset = if (unstablePacing && !hot) {
            CpuAffinityPreset.PERFORMANCE_ONLY
        } else {
            CpuAffinityPreset.AVOID_ONE_EFFICIENCY_CORE
        }
        var confidence = 48
        if (hot) confidence += 6
        if (unstablePacing) confidence += 6
        if (facts.benchmark != null) confidence += 3

        return finalize(
            facts = facts,
            action = RecommendationAction.SET_CPU_AFFINITY,
            current = "The game is left to Android's scheduler",
            proposed = "Test \"${preset.label}\" for this game",
            reason = buildString {
                if (unstablePacing) {
                    append(
                        "The benchmark measured " +
                            (jank?.let { "${formatPercent(it)} jank" } ?: "uneven frame pacing") +
                            ". ",
                    )
                } else {
                    append("The device reported ${classification.label.lowercase()} during the scan. ")
                }
                append("The kernel exposes ${clusters.size} CPU clusters with different clock bounds, ")
                append("so a core restriction is expressible on this device. It is the only ")
                append("process-level control GameCore has, and it is a test rather than a fix.")
            },
            tradeoff = CpuAffinityPreset.HONESTY,
            compatibility = CapabilityStatus.AVAILABLE,
            baseConfidence = confidence,
            requiresTesting = true,
            evidence = buildList {
                add(
                    RecommendationEvidence(
                        "CPU clusters",
                        clusters.joinToString("; ") { cluster ->
                            "cores ${cluster.coreIndices.joinToString(",")} up to " +
                                formatGhz(cluster.maxKHz)
                        },
                    ),
                )
                add(
                    RecommendationEvidence(
                        "Thermal state",
                        classification.label + temperatureSuffix(scan),
                    ),
                )
                jank?.let {
                    add(RecommendationEvidence("Benchmark pacing", "${formatPercent(it)} jank"))
                }
                add(RecommendationEvidence("Profile field", "cpuAffinity"))
            },
            targetCpuAffinity = preset,
        )
    }

    // ------------------------------------------------------------------------------------ animations

    private fun animations(facts: Facts): SmartRecommendation? {
        if (facts.goal != OptimizationGoal.MAXIMUM_PERFORMANCE) return null
        if (facts.profile?.performanceMode == PerformanceMode.PERFORMANCE) return null
        val status = facts.scan.capabilities.animationScaleControl
        if (!status.isUsable) return null

        var confidence = 55
        if (facts.benchmark != null) confidence += 3

        return finalize(
            facts = facts,
            action = RecommendationAction.DISABLE_ANIMATIONS,
            current = "GameCore has not changed the animation scales",
            proposed = "Set the three window animation scales to zero",
            reason = "The capability probe confirmed the three animation scales are writable here. " +
                "Setting them to zero removes window transition frames outside the game, which is a " +
                "responsiveness change you can see immediately. It does not change anything inside " +
                "the game's rendering.",
            tradeoff = "Window transitions stop being animated across the whole device, not just in " +
                "the game. Frames drawn by the game itself are unaffected.",
            compatibility = status,
            baseConfidence = confidence,
            requiresTesting = false,
            evidence = listOf(
                RecommendationEvidence("Animation scale control", status.label),
                RecommendationEvidence(
                    "Scope",
                    "Settings.Global.ANIMATOR_DURATION_SCALE, TRANSITION_ANIMATION_SCALE, " +
                        "WINDOW_ANIMATION_SCALE",
                ),
            ),
        )
    }

    // -------------------------------------------------------------------------------------- output

    private fun finalize(
        facts: Facts,
        action: RecommendationAction,
        current: String,
        proposed: String,
        reason: String,
        tradeoff: String,
        compatibility: CapabilityStatus,
        baseConfidence: Int,
        requiresTesting: Boolean,
        evidence: List<RecommendationEvidence>,
        targetRefreshRateHz: Float? = null,
        targetCpuAffinity: CpuAffinityPreset? = null,
    ): SmartRecommendation? {
        if (!compatibility.isUsable) return null

        val learning = facts.learning[action.key]
        val confidence = (baseConfidence + (learning?.confidenceAdjustment ?: 0))
            .coerceIn(0, MAX_CONFIDENCE)
        val blocked = learning?.blocked == true
        if (!blocked && confidence < MIN_USEFUL_CONFIDENCE) return null

        val classification = when {
            blocked -> RecommendationClass.NOT_RECOMMENDED
            requiresTesting -> RecommendationClass.EXPERIMENTAL
            confidence >= STRONG_CONFIDENCE -> RecommendationClass.STRONGLY_RECOMMENDED
            confidence >= RECOMMENDED_CONFIDENCE -> RecommendationClass.RECOMMENDED
            confidence >= 55 -> RecommendationClass.OPTIONAL
            else -> RecommendationClass.EXPERIMENTAL
        }

        val target = action.optimizationAction?.let { RecommendationTarget.DeviceAction(it) }
            ?: action.profileField?.let { RecommendationTarget.GameProfile(it) }
            ?: RecommendationTarget.Information

        val history = learning?.takeIf {
            it.generatedCount + it.appliedCount + it.attemptedCount +
                it.ignoredCount + it.testedCount > 0
        }?.explanation

        return SmartRecommendation(
            key = action.key,
            action = action,
            title = action.title,
            current = current,
            proposed = proposed,
            reason = reason,
            tradeoff = tradeoff,
            compatibility = compatibility,
            classification = classification,
            confidencePercent = confidence,
            requiresTesting = requiresTesting,
            target = target,
            evidence = evidence,
            appliesToPackage = facts.gamePackage,
            profileField = action.profileField,
            history = history,
            targetRefreshRateHz = targetRefreshRateHz,
            targetCpuAffinity = targetCpuAffinity,
        )
    }

    // ------------------------------------------------------------------------------------ formatting

    private fun formatHz(hz: Float): String =
        if (abs(hz - hz.toInt()) < 0.05f) {
            "${hz.toInt()} Hz"
        } else {
            String.format(Locale.US, "%.1f Hz", hz)
        }

    private fun formatTemperature(deciCelsius: Int): String =
        String.format(Locale.US, "%.1f °C", deciCelsius / 10f)

    private fun formatGhz(kHz: Long): String =
        if (kHz <= 0L) "unknown" else String.format(Locale.US, "%.2f GHz", kHz / 1_000_000f)

    private fun formatPercent(value: Float): String =
        String.format(Locale.US, "%.1f%%", value)

    private fun formatRate(percentPerHour: Float): String =
        String.format(Locale.US, "%.1f%% per hour", percentPerHour)

    private fun formatGib(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

    private fun minutes(millis: Long): String {
        val seconds = millis / 1000
        return "${seconds / 60}m ${seconds % 60}s"
    }

    private fun temperatureSuffix(scan: DeviceScan): String {
        val temperature = scan.cpuTemperatureDeciCelsius ?: scan.batteryTemperatureDeciCelsius
        return if (temperature != null) " (${formatTemperature(temperature)})" else " (no sensor read)"
    }
}
