package com.gamecore.ui.plugins.render

import com.gamecore.core.model.HudStat
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.plugin.DataScope
import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.domain.monitoring.HudStatReader

/**
 * The per-id scope gate behind a plugin screen's live figures, and the only channel its node tree has
 * to real data.
 *
 * This is the second half of a plugin screen's double lock. The screen as a whole is already behind the
 * plugin's `CHANGE_UI` grant (the route never resolves one for a plugin that lacks it); this adds the
 * per-reading [DataScope] prefilter the overlay and dashboard surfaces already carry, so a `stat` or a
 * live chart `series` surfaces a real figure only when the plugin was granted the scope that reading
 * sits under. The two locks are independent on purpose: CHANGE_UI lets a plugin *draw* a screen, and a
 * data scope lets a figure on it *read the host* — a plugin may have the first without the second, and
 * then its screen draws but its figures are absent.
 *
 * Pure and Android-free by construction: it takes the granted scopes, one [PerformanceSnapshot] and one
 * [MetricHistory] as plain values, so the gate can be unit-tested without Compose, Hilt or a live
 * [com.gamecore.domain.monitoring.PerformanceMonitor] (which is a final, heavy singleton and cannot be
 * faked). The route rebuilds one of these each live frame — a fresh instance over the latest snapshot
 * and history — which is what makes the read-only tree re-read moving values rather than freeze at the
 * first frame, and it re-evaluates the scope set on every rebuild, so a grant revoked mid-view stops
 * surfacing the figure on the next frame.
 *
 * The resolution mirrors the dashboard widgets' resolver exactly, deliberately: a plugin-facing
 * [ModuleStat] id in, the host's [HudStat] in the middle, [HudStatReader]'s rendered reading out, so a
 * figure on a plugin screen reads identically to the same figure in a widget, the overlay and the
 * host's own HUD — down to the unit and the absent placeholder. No new vocabulary is invented here: an
 * id outside [ModuleStat] resolves to null, and null — whether from an ungranted scope, a reading the
 * host cannot currently take, or a motion stat this surface has no sampler for — is drawn by the
 * renderer as the absent em dash, never as a zero or a stale figure.
 */
class PluginScreenStatResolver(
    private val grantedScopes: Set<DataScope>,
    private val snapshot: PerformanceSnapshot?,
    private val history: MetricHistory,
) {

    /**
     * The display string for a `stat` node's reading, or null when it has none to show.
     *
     * Null is returned for three distinct reasons that all draw the same absent em dash: the id is not
     * a known [ModuleStat]; the stat's [DataScope] was not granted (the backstop — an ungranted scope
     * must never surface a real figure); or the host cannot currently read it, including the two motion
     * stats this surface has no sampler for.
     */
    fun stats(id: String): String? {
        val stat = ModuleStat.fromId(id) ?: return null
        if (!isScopeGranted(stat)) return null
        val hud = stat.toHudStat() ?: return null
        val reading = HudStatReader.read(hud, snapshot)
        return if (reading.isAvailable) reading.display() else null
    }

    /**
     * The recent samples behind a live chart series id, or null when this surface retains none for it.
     *
     * The scope gate is applied first, for the same reason [stats] applies it: a series the user never
     * approved the scope for must not plot. Past the gate, only the four figures the host keeps a
     * rolling window for resolve — frame rate, CPU, memory and temperature; GPU and battery are read
     * instantaneously but not retained as a plottable window, and the two motion stats are not sampled
     * here, so each is null. Null (not an empty list) is the honest answer the chart reader treats as
     * "no live feed", falling back to the author's literal values rather than claiming the host owns an
     * empty feed.
     */
    fun series(id: String): List<Float>? {
        val stat = ModuleStat.fromId(id) ?: return null
        if (!isScopeGranted(stat)) return null
        return when (stat) {
            ModuleStat.FPS -> history.frameRateSeries()
            ModuleStat.CPU -> history.cpuSeries()
            ModuleStat.RAM -> history.memorySeries()
            ModuleStat.TEMP -> history.temperatureSeries()
            else -> null
        }
    }

    /**
     * True only when the scope a stat sits under is in the granted set.
     *
     * The stat's scope is [ModuleStat.scope], a [DataScope] id as a string; it is resolved to the enum
     * via [DataScope.fromId] rather than compared as a raw string, so an id that no longer names a real
     * scope fails closed to "not granted" instead of accidentally matching.
     */
    private fun isScopeGranted(stat: ModuleStat): Boolean {
        val scope = DataScope.fromId(stat.scope) ?: return false
        return scope in grantedScopes
    }

    /**
     * The host stat behind a plugin-facing one, or null when the host does not read it on this surface.
     *
     * The same mapping the overlay and dashboard make, including answering temperature with the battery
     * sensor rather than a CPU thermal zone (battery temperature exists on every device). The two
     * motion stats map to null because resolving them needs a motion sampler this surface does not hold.
     */
    private fun ModuleStat.toHudStat(): HudStat? = when (this) {
        ModuleStat.FPS -> HudStat.FRAME_RATE
        ModuleStat.CPU -> HudStat.CPU_USAGE
        ModuleStat.GPU -> HudStat.GPU_USAGE
        ModuleStat.RAM -> HudStat.RAM_USAGE
        ModuleStat.BATTERY -> HudStat.BATTERY_LEVEL
        ModuleStat.TEMP -> HudStat.BATTERY_TEMPERATURE
        ModuleStat.ROTATION_RATE, ModuleStat.MOVEMENT -> null
    }
}
