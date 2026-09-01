package com.gamecore.data.database

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ScreenOrientationLock
import com.gamecore.core.model.SessionSample
import com.gamecore.core.model.StopReason

/**
 * Entity ↔ model conversion, and the one place enum names and user text cross the boundary.
 *
 * Two rules are enforced here rather than trusted to callers:
 *
 * **Enum names are parsed defensively.** `valueOf` throws on an unrecognised name, and an
 * unrecognised name is not hypothetical — it is what a row written by a newer version of
 * GameCore looks like to an older one, and what a hand-edited database looks like always.
 * Every read falls back to a sane default instead of crashing the Games screen.
 *
 * **User and third-party text is sanitised on the way in.** Profile labels come from another
 * app's manifest, layout and preset names come from the user, and all three are drawn into
 * overlay windows and notifications. §24A.4 asks for sanitisation before storage *and* before
 * render; this is the before-storage half, and it is on the only path into the tables.
 */
internal object Mappers {

    // ------------------------------------------------------------------- profiles

    fun toEntity(profile: GameProfile, nowMillis: Long): GameProfileEntity = GameProfileEntity(
        packageName = profile.packageName,
        label = TextSanitizer.sanitizeName(profile.label).ifBlank { profile.packageName },
        isEnabled = profile.isEnabled,
        targetRefreshRate = profile.targetRefreshRate,
        brightnessPercent = profile.brightnessPercent?.coerceIn(0, 100),
        rotationLock = profile.rotationLock?.name,
        screenTimeoutMillis = profile.screenTimeoutMillis,
        mediaVolumePercent = profile.mediaVolumePercent?.coerceIn(0, 100),
        enableDoNotDisturb = profile.enableDoNotDisturb,
        showFloatingButton = profile.showFloatingButton,
        showPerformancePill = profile.showPerformancePill,
        showCrosshair = profile.showCrosshair,
        hudLayoutId = profile.hudLayoutId,
        crosshairPresetId = profile.crosshairPresetId,
        performanceMode = profile.performanceMode.name,
        useShizukuOptimizations = profile.useShizukuOptimizations,
        trackSession = profile.trackSession,
        updatedAtMillis = nowMillis,
    )

    fun toModel(entity: GameProfileEntity): GameProfile = GameProfile(
        packageName = entity.packageName,
        label = entity.label,
        isEnabled = entity.isEnabled,
        targetRefreshRate = entity.targetRefreshRate,
        brightnessPercent = entity.brightnessPercent,
        rotationLock = entity.rotationLock?.let { name ->
            ScreenOrientationLock.entries.firstOrNull { it.name == name }
        },
        screenTimeoutMillis = entity.screenTimeoutMillis,
        mediaVolumePercent = entity.mediaVolumePercent,
        enableDoNotDisturb = entity.enableDoNotDisturb,
        showFloatingButton = entity.showFloatingButton,
        showPerformancePill = entity.showPerformancePill,
        showCrosshair = entity.showCrosshair,
        hudLayoutId = entity.hudLayoutId,
        crosshairPresetId = entity.crosshairPresetId,
        performanceMode = PerformanceMode.entries
            .firstOrNull { it.name == entity.performanceMode }
            ?: PerformanceMode.BALANCED,
        useShizukuOptimizations = entity.useShizukuOptimizations,
        trackSession = entity.trackSession,
    )

    // ------------------------------------------------------------------------ hud

    fun toEntity(layout: HudLayout, nowMillis: Long): HudLayoutEntity = HudLayoutEntity(
        id = layout.id,
        name = TextSanitizer.sanitizeName(layout.name, HudLayout.MAX_NAME_LENGTH)
            .ifBlank { "Layout" },
        createdAtMillis = if (layout.createdAtMillis > 0L) layout.createdAtMillis else nowMillis,
        updatedAtMillis = nowMillis,
    )

    fun toEntity(widget: HudWidget, layoutId: Long): HudWidgetEntity =
        widget.normalised().let {
            HudWidgetEntity(
                widgetId = it.id,
                layoutId = layoutId,
                stat = it.stat.name,
                xFraction = it.xFraction,
                yFraction = it.yFraction,
                textSizeSp = it.textSizeSp,
                opacityPercent = it.opacityPercent,
                showLabel = it.showLabel,
                showBackground = it.showBackground,
                colorArgb = it.colorArgb,
            )
        }

    fun toModel(entity: HudLayoutEntity, widgets: List<HudWidgetEntity>): HudLayout = HudLayout(
        id = entity.id,
        name = entity.name,
        // A widget whose stat name is unrecognised is dropped rather than defaulted: showing
        // CPU usage where the user configured something else is worse than showing nothing.
        widgets = widgets.mapNotNull { toModelOrNull(it) },
        createdAtMillis = entity.createdAtMillis,
        updatedAtMillis = entity.updatedAtMillis,
    )

    private fun toModelOrNull(entity: HudWidgetEntity): HudWidget? {
        val stat = HudStat.entries.firstOrNull { it.name == entity.stat } ?: return null
        return HudWidget(
            id = entity.widgetId,
            stat = stat,
            xFraction = entity.xFraction,
            yFraction = entity.yFraction,
            textSizeSp = entity.textSizeSp,
            opacityPercent = entity.opacityPercent,
            showLabel = entity.showLabel,
            showBackground = entity.showBackground,
            colorArgb = entity.colorArgb,
        ).normalised()
    }

    // ------------------------------------------------------------------ crosshair

    fun toEntity(preset: CrosshairPreset): CrosshairPresetEntity =
        preset.normalised().let {
            CrosshairPresetEntity(
                id = it.id,
                name = TextSanitizer.sanitizeName(it.name, CrosshairPreset.MAX_NAME_LENGTH)
                    .ifBlank { "Crosshair" },
                design = it.design.name,
                sizeDp = it.sizeDp,
                thicknessDp = it.thicknessDp,
                centreGapDp = it.centreGapDp,
                opacityPercent = it.opacityPercent,
                rotationDegrees = it.rotationDegrees,
                colorArgb = it.colorArgb,
                showDot = it.showDot,
                showOutline = it.showOutline,
                xFraction = it.xFraction,
                yFraction = it.yFraction,
                imagePath = it.imagePath,
            )
        }

    fun toModel(entity: CrosshairPresetEntity): CrosshairPreset = CrosshairPreset(
        id = entity.id,
        name = entity.name,
        design = CrosshairDesign.entries.firstOrNull { it.name == entity.design }
            ?: CrosshairDesign.CROSS,
        sizeDp = entity.sizeDp,
        thicknessDp = entity.thicknessDp,
        centreGapDp = entity.centreGapDp,
        opacityPercent = entity.opacityPercent,
        rotationDegrees = entity.rotationDegrees,
        colorArgb = entity.colorArgb,
        showDot = entity.showDot,
        showOutline = entity.showOutline,
        xFraction = entity.xFraction,
        yFraction = entity.yFraction,
        imagePath = entity.imagePath,
    ).normalised()

    // ------------------------------------------------------------------- sessions

    fun toEntity(session: GameSession): SessionEntity = SessionEntity(
        id = session.id,
        packageName = session.packageName,
        gameLabel = TextSanitizer.sanitizeName(session.gameLabel)
            .ifBlank { session.packageName },
        startedAtMillis = session.startedAtMillis,
        endedAtMillis = session.endedAtMillis,
        batteryStartPercent = session.batteryStartPercent,
        batteryEndPercent = session.batteryEndPercent,
        wasCharging = session.wasCharging,
        averageCpuPercent = session.averageCpuPercent,
        peakCpuPercent = session.peakCpuPercent,
        averageMemoryPercent = session.averageMemoryPercent,
        peakMemoryPercent = session.peakMemoryPercent,
        averageTemperatureDeciCelsius = session.averageTemperatureDeciCelsius,
        peakTemperatureDeciCelsius = session.peakTemperatureDeciCelsius,
        averageRefreshRate = session.averageRefreshRate,
        averageFrameRate = session.averageFrameRate,
        averageLatencyMillis = session.averageLatencyMillis,
        profileApplied = session.profileApplied,
        sampleCount = session.sampleCount,
        stopReason = session.stopReason?.name,
    )

    fun toModel(entity: SessionEntity): GameSession = GameSession(
        id = entity.id,
        packageName = entity.packageName,
        gameLabel = entity.gameLabel,
        startedAtMillis = entity.startedAtMillis,
        endedAtMillis = entity.endedAtMillis,
        batteryStartPercent = entity.batteryStartPercent,
        batteryEndPercent = entity.batteryEndPercent,
        wasCharging = entity.wasCharging,
        averageCpuPercent = entity.averageCpuPercent,
        peakCpuPercent = entity.peakCpuPercent,
        averageMemoryPercent = entity.averageMemoryPercent,
        peakMemoryPercent = entity.peakMemoryPercent,
        averageTemperatureDeciCelsius = entity.averageTemperatureDeciCelsius,
        peakTemperatureDeciCelsius = entity.peakTemperatureDeciCelsius,
        averageRefreshRate = entity.averageRefreshRate,
        averageFrameRate = entity.averageFrameRate,
        averageLatencyMillis = entity.averageLatencyMillis,
        profileApplied = entity.profileApplied,
        sampleCount = entity.sampleCount,
        stopReason = StopReason.entries.firstOrNull { it.name == entity.stopReason },
    )

    fun toEntity(sample: SessionSample): SessionSampleEntity = SessionSampleEntity(
        sessionId = sample.sessionId,
        elapsedMillis = sample.elapsedMillis,
        cpuPercent = sample.cpuPercent,
        memoryPercent = sample.memoryPercent,
        batteryPercent = sample.batteryPercent,
        temperatureDeciCelsius = sample.temperatureDeciCelsius,
        refreshRate = sample.refreshRate,
        frameRate = sample.frameRate,
        latencyMillis = sample.latencyMillis,
    )

    fun toModel(entity: SessionSampleEntity): SessionSample = SessionSample(
        sessionId = entity.sessionId,
        elapsedMillis = entity.elapsedMillis,
        cpuPercent = entity.cpuPercent,
        memoryPercent = entity.memoryPercent,
        batteryPercent = entity.batteryPercent,
        temperatureDeciCelsius = entity.temperatureDeciCelsius,
        refreshRate = entity.refreshRate,
        frameRate = entity.frameRate,
        latencyMillis = entity.latencyMillis,
    )
}
