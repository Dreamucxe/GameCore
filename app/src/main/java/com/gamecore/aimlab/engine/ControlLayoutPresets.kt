package com.gamecore.aimlab.engine

/**
 * The default control placements for each finger-count preset.
 *
 * Right-handed thumb-zone layout: movement stick bottom-left, shoot bottom-right, ADS above it, and the
 * extra controls fanned along the lower corners as the finger count rises. Every placement is already
 * inside a conservative safe area, and callers clamp to the device's real safe area on top. These are
 * starting points the user then drags — §12 asks the presets to exist, not to be immovable.
 */
internal object ControlLayoutPresets {

    fun build(preset: LayoutPreset): ControlLayout {
        val controls = controlsFor(preset)
        return ControlLayout(
            name = preset.label,
            controls = controls.map { it.normalised() },
            landscapeControls = landscape(preset).map { it.normalised() },
        )
    }

    /** The portrait control set for a preset, exposed so self-heal can rebuild a layout from its count. */
    fun portrait(preset: LayoutPreset): List<ControlWidget> = controlsFor(preset).map { it.normalised() }

    /** The landscape control set for a preset (§4 landscape defaults). */
    fun landscape(preset: LayoutPreset): List<ControlWidget> = when (preset) {
        LayoutPreset.TWO_FINGER -> twoFingerLandscape()
        LayoutPreset.THREE_FINGER -> threeFingerLandscape()
        LayoutPreset.FOUR_FINGER -> fourFingerLandscape()
        LayoutPreset.FIVE_FINGER -> fiveFingerLandscape()
        LayoutPreset.CUSTOM -> threeFingerLandscape()
    }.map { it.normalised() }

    private fun controlsFor(preset: LayoutPreset): List<ControlWidget> = when (preset) {
        LayoutPreset.TWO_FINGER -> twoFinger()
        LayoutPreset.THREE_FINGER -> threeFinger()
        LayoutPreset.FOUR_FINGER -> fourFinger()
        LayoutPreset.FIVE_FINGER -> fiveFinger()
        LayoutPreset.CUSTOM -> threeFinger() // a sensible base to customise from
    }

    // Left thumb moves, right thumb shoots. The minimum viable layout.
    private fun twoFinger(): List<ControlWidget> = listOf(
        ControlWidget(ControlRole.MOVE_STICK, x = 0.16f, y = 0.78f, w = 0.22f, h = 0.22f),
        ControlWidget(ControlRole.SHOOT, x = 0.86f, y = 0.80f, w = 0.16f, h = 0.16f),
        ControlWidget(ControlRole.ADS, x = 0.86f, y = 0.58f, w = 0.12f, h = 0.12f, enabled = false),
    )

    // Adds an always-available ADS under the shooting thumb's reach.
    private fun threeFinger(): List<ControlWidget> = listOf(
        ControlWidget(ControlRole.MOVE_STICK, x = 0.16f, y = 0.78f, w = 0.22f, h = 0.22f),
        ControlWidget(ControlRole.SHOOT, x = 0.88f, y = 0.80f, w = 0.15f, h = 0.15f),
        ControlWidget(ControlRole.ADS, x = 0.70f, y = 0.82f, w = 0.13f, h = 0.13f),
        ControlWidget(ControlRole.RELOAD, x = 0.88f, y = 0.55f, w = 0.11f, h = 0.11f),
        ControlWidget(ControlRole.JUMP, x = 0.74f, y = 0.58f, w = 0.10f, h = 0.10f),
    )

    // Adds crouch and weapon switch for two index fingers up top.
    private fun fourFinger(): List<ControlWidget> = threeFinger() + listOf(
        ControlWidget(ControlRole.CROUCH, x = 0.78f, y = 0.36f, w = 0.10f, h = 0.10f),
        ControlWidget(ControlRole.WEAPON_SWITCH, x = 0.90f, y = 0.34f, w = 0.10f, h = 0.10f),
    )

    // Full claw: adds sprint and melee for two more index positions.
    private fun fiveFinger(): List<ControlWidget> = fourFinger() + listOf(
        ControlWidget(ControlRole.SPRINT, x = 0.12f, y = 0.42f, w = 0.10f, h = 0.10f),
        ControlWidget(ControlRole.MELEE, x = 0.24f, y = 0.34f, w = 0.10f, h = 0.10f),
    )

    // ---- landscape defaults (§4): wider screen, so controls spread to the far corners and edges.
    // Fractions are of the landscape safe area; y is smaller because a landscape screen is short.

    private fun twoFingerLandscape(): List<ControlWidget> = listOf(
        ControlWidget(ControlRole.MOVE_STICK, x = 0.12f, y = 0.72f, w = 0.16f, h = 0.28f),
        ControlWidget(ControlRole.SHOOT, x = 0.90f, y = 0.72f, w = 0.12f, h = 0.20f),
        ControlWidget(ControlRole.ADS, x = 0.90f, y = 0.42f, w = 0.09f, h = 0.16f, enabled = false),
    )

    private fun threeFingerLandscape(): List<ControlWidget> = listOf(
        ControlWidget(ControlRole.MOVE_STICK, x = 0.12f, y = 0.72f, w = 0.16f, h = 0.28f),
        ControlWidget(ControlRole.SHOOT, x = 0.91f, y = 0.74f, w = 0.11f, h = 0.19f),
        ControlWidget(ControlRole.ADS, x = 0.78f, y = 0.78f, w = 0.09f, h = 0.16f),
        ControlWidget(ControlRole.RELOAD, x = 0.92f, y = 0.44f, w = 0.08f, h = 0.14f),
        ControlWidget(ControlRole.JUMP, x = 0.80f, y = 0.46f, w = 0.07f, h = 0.13f),
    )

    private fun fourFingerLandscape(): List<ControlWidget> = threeFingerLandscape() + listOf(
        ControlWidget(ControlRole.CROUCH, x = 0.68f, y = 0.24f, w = 0.07f, h = 0.13f),
        ControlWidget(ControlRole.WEAPON_SWITCH, x = 0.93f, y = 0.20f, w = 0.07f, h = 0.13f),
    )

    private fun fiveFingerLandscape(): List<ControlWidget> = fourFingerLandscape() + listOf(
        ControlWidget(ControlRole.SPRINT, x = 0.08f, y = 0.30f, w = 0.07f, h = 0.13f),
        ControlWidget(ControlRole.MELEE, x = 0.20f, y = 0.22f, w = 0.07f, h = 0.13f),
    )

    // Local convenience constructor so the tables above read as coordinates, not named args.
    private fun ControlWidget(
        role: ControlRole,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        enabled: Boolean = true,
    ) = com.gamecore.aimlab.engine.ControlWidget(
        role = role,
        xFraction = x,
        yFraction = y,
        widthFraction = w,
        heightFraction = h,
        enabled = enabled,
    )
}
