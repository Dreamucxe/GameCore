package com.gamecore.data.database

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.ColorCodec
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.CpuAffinityPreset
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.GammaMode
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.LatencyLog
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
        colorPresetId = profile.colorPresetId,
        displaySize = profile.displaySize?.argument,
        performanceMode = profile.performanceMode.name,
        useShizukuOptimizations = profile.useShizukuOptimizations,
        trackSession = profile.trackSession,
        freeRamOnLaunch = profile.freeRamOnLaunch,
        cpuAffinity = profile.cpuAffinity?.name,
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
        colorPresetId = entity.colorPresetId,
        // Parsed rather than trusted, for the reason the enum names above are: an unparseable
        // token becomes null, which every caller already reads as "leave the display alone".
        // Better than a profile that cannot be opened because one row holds "1080x" — and
        // better than stretching the display to a size nobody asked for.
        displaySize = entity.displaySize?.let { DisplaySize.parse(it) },
        performanceMode = PerformanceMode.entries
            .firstOrNull { it.name == entity.performanceMode }
            ?: PerformanceMode.BALANCED,
        useShizukuOptimizations = entity.useShizukuOptimizations,
        trackSession = entity.trackSession,
        freeRamOnLaunch = entity.freeRamOnLaunch,
        // Same defensive parse, and here the fallback is the field's own default rather than a
        // substitute: an unrecognised preset name means this profile stops choosing cores, which is
        // what null means everywhere else and is the safe direction to fail in.
        cpuAffinity = entity.cpuAffinity?.let { name ->
            CpuAffinityPreset.entries.firstOrNull { it.name == name }
        },
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

    // --------------------------------------------------------------------- colour

    fun toEntity(preset: ColorPreset): ColorPresetEntity =
        preset.normalised().let { normalised ->
            val c = normalised.correction
            ColorPresetEntity(
                id = normalised.id,
                name = TextSanitizer.sanitizeName(normalised.name, ColorPreset.MAX_NAME_LENGTH)
                    .ifBlank { ColorPreset.DEFAULT_NAME },
                redGain = c.redGain,
                greenGain = c.greenGain,
                blueGain = c.blueGain,
                gammaMode = c.gammaMode.name,
                gamma = c.gamma,
                redGamma = c.redGamma,
                greenGamma = c.greenGamma,
                blueGamma = c.blueGamma,
                saturation = c.saturation,
                contrast = c.contrast,
                hueDegrees = c.hueDegrees,
                brightnessOffset = c.brightnessOffset,
                visionFilter = c.visionFilter.name,
                invertColors = c.invertColors,
            )
        }

    /**
     * A stored preset as a model.
     *
     * Both enum columns fall back to the neutral value rather than throwing, for the reason this
     * object exists: an unrecognised name is what a row written by a newer build looks like to an
     * older one, and a colour picker that crashes on one bad row is worse than one that shows that
     * preset with its filter off.
     */
    fun toModel(entity: ColorPresetEntity): ColorPreset = ColorPreset(
        id = entity.id,
        name = entity.name,
        correction = ColorCorrection(
            redGain = entity.redGain,
            greenGain = entity.greenGain,
            blueGain = entity.blueGain,
            gammaMode = GammaMode.entries.firstOrNull { it.name == entity.gammaMode }
                ?: GammaMode.COMBINED,
            gamma = entity.gamma,
            redGamma = entity.redGamma,
            greenGamma = entity.greenGamma,
            blueGamma = entity.blueGamma,
            saturation = entity.saturation,
            contrast = entity.contrast,
            hueDegrees = entity.hueDegrees,
            brightnessOffset = entity.brightnessOffset,
            visionFilter = ColorVisionFilter.entries.firstOrNull { it.name == entity.visionFilter }
                ?: ColorVisionFilter.NONE,
            invertColors = entity.invertColors,
        ),
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
        // Sanitised like every other user string on the way in, and encoded rather than
        // spread over fourteen columns because a session's colour reading is written once
        // and read once.
        colorPreset = session.colorPresetName
            ?.let { TextSanitizer.sanitizeName(it, ColorPreset.MAX_NAME_LENGTH) }
            ?.takeIf { it.isNotBlank() },
        colorValues = session.colorCorrection?.let(ColorCodec::encode),
        // All six or none. `latencyProbes` is the presence flag on the way back in, so writing a
        // failure count beside a NULL probe count would produce a row that reads as "no log kept"
        // and quietly discards the rest of it.
        latencyProbes = session.latencyLog?.completedProbes,
        latencyFailed = session.latencyLog?.failedProbes,
        latencySpikes = session.latencyLog?.spikes,
        latencyWorst = session.latencyLog?.worstMillis,
        latencyJitter = session.latencyLog?.jitterMillis,
        latencyFailedRun = session.latencyLog?.longestFailureRun,
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
        colorPresetName = entity.colorPreset,
        // Null for a row the codec cannot parse, which is the same as "no reading" to every
        // caller. A report that renders one session without its colour line is a small loss;
        // one that throws on the way to the list is not.
        colorCorrection = ColorCodec.decode(entity.colorValues),
        // A log exists iff the probe count does. The three counts fall back to zero rather than
        // failing the read: a row hand-edited into a half-log should lose the counts it does not
        // have, not the session. `worst` and `jitter` stay nullable inside a present log, because
        // "no probe completed" and "fewer than two completed" are both real states of a real log.
        latencyLog = entity.latencyProbes?.let { probes ->
            LatencyLog(
                completedProbes = probes,
                failedProbes = entity.latencyFailed ?: 0,
                spikes = entity.latencySpikes ?: 0,
                worstMillis = entity.latencyWorst,
                jitterMillis = entity.latencyJitter,
                longestFailureRun = entity.latencyFailedRun ?: 0,
            )
        },
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
