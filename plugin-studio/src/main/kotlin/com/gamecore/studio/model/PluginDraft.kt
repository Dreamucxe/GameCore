package com.gamecore.studio.model

/**
 * One plugin, as the person making it has described it so far.
 *
 * A draft is *data*, not a package: nothing here is validated by the host's rules until
 * [PluginDraft.problems] says it is, and nothing is written to disk until the composer runs.
 * That split is deliberate — the editor has to be able to hold a half-typed id or an
 * impossible colour while the user is still typing, without the app crashing or the file
 * silently losing the field.
 *
 * The field names mirror the manifest and supported section keys, so what the user reads in
 * the editor is what they will find in the JSON inside the package.
 *
 * **Scope.** This model describes exactly the sections Studio can preserve today: a theme,
 * declarative screens, one bounded overlay, typed settings, dashboard widgets, and bounded
 * automation rules. Unsupported host sections remain unrepresentable so importing and exporting
 * cannot quietly erase them.
 */
data class PluginDraft(
    /** The package-style id, e.g. `com.example.mytheme`. Two plugins cannot share one. */
    val id: String = DEFAULT_ID,
    /** The name the install screen shows. */
    val name: String = "My Plugin",
    /** The monotonic version number GameCore compares to tell an update from a downgrade. */
    val version: Int = 1,
    /** The human version, e.g. `1.0.0`. */
    val versionName: String = "1.0.0",
    /** Who made it. */
    val developer: String = "",
    /** One or two sentences about it. */
    val description: String = "",
    /** What changed in this release. */
    val changelog: String = "1.0.0 — first release.",
    /** The plugin API the package was written against. */
    val api: Int = CURRENT_API,
    /** The oldest GameCore that can run it, as a `versionCode`. */
    val minHostVersion: Int = DEFAULT_MIN_HOST_VERSION,
    /** The permissions the package asks for. Nothing is granted by asking. */
    val permissions: Set<String> = setOf(PERMISSION_CHANGE_UI),
    /** The design. */
    val theme: ThemeDraft = ThemeDraft(),
    /** Studio-only organisation metadata; it is not emitted into a plugin manifest. */
    val creatorCategory: CreatorCategory = CreatorCategory.THEME,
    /** Scopes attached to authored permission requests. A request is never a grant. */
    val permissionScopes: Map<String, Set<String>> = emptyMap(),
    /** Every host section Studio can author in addition to the backward-compatible [theme] field. */
    val sections: CreatorSections = CreatorSections(),
) {

    /** The file name the package is written under, without the extension. */
    val fileName: String get() = "$id-$versionName"

    /**
     * Everything that would make GameCore refuse this package.
     *
     * The rules are the host's rules, restated here so the editor can say *why* a field is
     * wrong instead of letting the user export a file that is rejected on the phone. The
     * studio cannot call the host's codecs — it is a separate app — so `:app` carries a test
     * that runs a composed package through the real decoders, which is what keeps the two
     * lists honest.
     */
    fun problems(): List<DraftProblem> = buildList {
        val trimmedId = id.trim()
        if (trimmedId.isEmpty()) {
            add(DraftProblem("id", "An id is required."))
        } else if (!ID_PATTERN.matches(trimmedId)) {
            add(DraftProblem("id", "An id looks like com.example.name — letters, digits and dots."))
        } else if (trimmedId.length > MAX_ID_LENGTH) {
            add(DraftProblem("id", "An id is at most $MAX_ID_LENGTH characters."))
        }
        if (name.isBlank()) {
            add(DraftProblem("name", "A name is required."))
        } else if (name.trim().length > MAX_NAME_LENGTH) {
            add(DraftProblem("name", "A name is at most $MAX_NAME_LENGTH characters."))
        }
        if (version < 1) {
            add(DraftProblem("version", "The version number starts at 1."))
        }
        if (versionName.isBlank()) {
            add(DraftProblem("versionName", "A version name is required."))
        } else if (versionName.trim().length > MAX_VERSION_NAME_LENGTH) {
            add(DraftProblem("versionName", "A version name is at most $MAX_VERSION_NAME_LENGTH characters."))
        }
        if (developer.trim().length > MAX_DEVELOPER_LENGTH) {
            add(DraftProblem("developer", "A developer name is at most $MAX_DEVELOPER_LENGTH characters."))
        }
        if (description.trim().length > MAX_DESCRIPTION_LENGTH) {
            add(DraftProblem("description", "A description is at most $MAX_DESCRIPTION_LENGTH characters."))
        }
        if (api < MINIMUM_API || api > CURRENT_API) {
            add(DraftProblem("api", "The plugin API version is $MINIMUM_API to $CURRENT_API."))
        }
        if (minHostVersion < 1) {
            add(DraftProblem("minHostVersion", "The oldest GameCore version is a number, starting at 1."))
        }
        permissions.forEach { permission ->
            if (KNOWN_PERMISSIONS.none { it.id == permission }) {
                add(DraftProblem("permissions", "\"$permission\" is not a permission GameCore knows."))
            }
        }
        permissionScopes.forEach { (permission, scopes) ->
            if (KNOWN_PERMISSIONS.none { it.id == permission }) {
                add(
                    DraftProblem(
                        "permissionScopes.$permission",
                        "\"$permission\" is not a permission GameCore knows.",
                    ),
                )
            }
            if (scopes.size > MAX_SCOPES_PER_PERMISSION) {
                add(
                    DraftProblem(
                        "permissionScopes.$permission",
                        "A permission can request at most $MAX_SCOPES_PER_PERMISSION scopes.",
                    ),
                )
            }
            if (scopes.isNotEmpty() && permission !in SCOPED_PERMISSIONS) {
                add(
                    DraftProblem(
                        "permissionScopes.$permission",
                        "The permission \"$permission\" does not take scopes.",
                    ),
                )
            }
        }
        permissionScopes[PERMISSION_READ_APPROVED_DATA].orEmpty().forEach { scope ->
            if (scope.trim().lowercase() !in KNOWN_DATA_SCOPES) {
                add(
                    DraftProblem(
                        "permissionScopes.$PERMISSION_READ_APPROVED_DATA",
                        "\"$scope\" is not a GameCore approved-data scope.",
                    ),
                )
            }
        }

        addAll(sections.problems())
        if (sections.themeEnabled) {
            addAll(theme.problems())
            val intensitySetting = theme.glass?.intensitySetting?.trim()?.takeIf { it.isNotEmpty() }
            if (intensitySetting != null) {
                val setting = sections.settings.firstOrNull { it.id.trim() == intensitySetting }
                when {
                    sections.settings.isEmpty() -> add(
                        DraftProblem(
                            "theme.glass.intensitySetting",
                            "A glass intensity control needs the settings section to be enabled.",
                        ),
                    )
                    setting !is IntSetting && setting !is FloatSetting -> add(
                        DraftProblem(
                            "theme.glass.intensitySetting",
                            "Glass intensitySetting must name an IntSetting or FloatSetting in this plugin.",
                        ),
                    )
                }
            }
        }
    }

    /** Every permission the authored content must request. This is a request plan, never a grant. */
    fun effectivePermissions(): Set<String> = linkedSetOf<String>().apply {
        addAll(permissions)
        addAll(sections.requiredPermissions())
    }

    /** Authored scopes plus the data apertures required by live section content. */
    fun effectivePermissionScopes(): Map<String, Set<String>> {
        val result = linkedMapOf<String, Set<String>>()
        permissionScopes.toSortedMap().forEach { (permission, scopes) ->
            result[permission] = scopes.toSortedSet()
        }
        val required = sections.requiredScopes()
        if (required.isNotEmpty()) {
            result[PERMISSION_READ_APPROVED_DATA] =
                (result[PERMISSION_READ_APPROVED_DATA].orEmpty() + required).toSortedSet()
        }
        return result
    }

    /** True when GameCore would accept this draft as it stands. */
    val isValid: Boolean get() = problems().isEmpty()

    companion object {
        /** The plugin API this studio writes. */
        const val CURRENT_API = 4

        /** The oldest plugin API GameCore still runs. */
        const val MINIMUM_API = 1

        /** The `versionCode` of the GameCore release this studio was written against. */
        const val DEFAULT_MIN_HOST_VERSION = 60

        const val DEFAULT_ID = "com.example.mytheme"

        const val PERMISSION_CHANGE_UI = "change_ui"
        const val PERMISSION_READ_APPROVED_DATA = "read_approved_data"

        const val MAX_ID_LENGTH = 128
        const val MAX_NAME_LENGTH = 64
        const val MAX_DEVELOPER_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 512
        const val MAX_VERSION_NAME_LENGTH = 32
        const val MAX_SCOPES_PER_PERMISSION = 32

        val KNOWN_DATA_SCOPES: Set<String> = setOf(
            "sessions",
            "games",
            "device_stats",
            "thermals",
            "motion",
            "automation_history",
        )

        private val SCOPED_PERMISSIONS = setOf(PERMISSION_READ_APPROVED_DATA, "modify_settings")

        /** The same grammar the host validates an id against. */
        val ID_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")

        /**
         * Every permission a package may ask for, with the sentence that explains it.
         *
         * Asking for a permission grants nothing: the package arrives switched off, and each
         * permission is approved on the plugin's own screen in GameCore.
         */
        val KNOWN_PERMISSIONS: List<StudioPermission> = listOf(
            StudioPermission("change_ui", "Change the look", "Restyle GameCore's screens and components."),
            StudioPermission("read_approved_data", "Read approved data", "Show sessions, games and device stats you approve."),
            StudioPermission("store_data", "Store your data", "Keep the plugin's own settings and readouts."),
            StudioPermission("add_pill_modules", "Add Game Pill modules", "Add readouts and controls to the Game Pill."),
            StudioPermission("add_dashboard_widgets", "Add dashboard widgets", "Add widgets to the Home dashboard."),
            StudioPermission("add_aimlab_content", "Add Aim Lab content", "Ship aim-training scenarios."),
            StudioPermission("change_icon", "Change the app icon", "Pick one of the launcher icons GameCore ships."),
            StudioPermission("create_overlay", "Show an overlay", "Draw a small panel over your game."),
            StudioPermission("modify_settings", "Change GameCore settings", "Adjust GameCore's own settings in the areas you approve."),
            StudioPermission("run_automations", "Run automations", "React to events with rules you can review."),
            StudioPermission("system_actions", "Run system actions", "Run the allowlisted system actions GameCore gates."),
        )
    }
}

/** One thing wrong with a draft, and which field it is wrong about. */
data class DraftProblem(val field: String, val message: String)

/** A permission the studio can offer, with the words the user reads. */
data class StudioPermission(val id: String, val title: String, val rationale: String)

/**
 * The design half of a draft.
 *
 * Colours are ARGB longs rather than strings so the editor can offer a picker and a preview
 * without parsing and re-parsing hex on every keystroke. The composer writes `#RRGGBB`, or
 * `#AARRGGBB` when the author chose an alpha, which is the pair of forms the host accepts.
 *
 * The model holds the full schema-4 vocabulary: the six v1 colours, the seven v2 roles, the v2
 * type controls, and the optional schema-4 glass and schema-3 glitch blocks. Every optional
 * block is null when it is not used, so a theme that asks for no material writes no material.
 */
data class ThemeDraft(
    /** The name of the theme, shown on the theme card. */
    val name: String = "My Plugin",
    /** The theme schema. Four is the one with roles, type, glitch and glass. */
    val schema: Int = CURRENT_SCHEMA,
    val dark: Boolean = true,
    /** Lets the host collapse true black for an OLED panel. */
    val amoled: Boolean = false,
    val primary: Long = 0xFFFF8A4CL,
    val onPrimary: Long = 0xFF24130AL,
    val background: Long = 0xFF121014L,
    val surface: Long = 0xFF211D24L,
    val onSurface: Long = 0xFFF6EFE7L,
    val accent: Long = 0xFF2BD4C0L,
    /** The softer roles: secondary, tertiary and the outlines between things. */
    val secondary: Long = 0xFFFFC46BL,
    val tertiary: Long = 0xFF8FD3FFL,
    val surfaceVariant: Long = 0xFF2C2730L,
    val onSurfaceVariant: Long = 0xFFCFC7D8L,
    val outline: Long = 0xFF4A424FL,
    val error: Long = 0xFFE5484DL,
    val onError: Long = 0xFF2A0606L,
    val cornerRadiusDp: Int = 20,
    val uiScale: Float = 1f,
    /** One of the bundled faces, by the id the host knows it under. */
    val font: String = FONT_SYSTEM,
    /** `light`, `normal`, `medium` or `bold`. */
    val typographyWeight: String = "medium",
    /** The global type-ramp multiplier, independent of [uiScale]. */
    val typographyScale: Float = 1f,
    /** Panels made of light rather than fill. Null means "no material block at all". */
    val glass: GlassDraft? = null,
    /** Controlled frame distortion. Null means "no motion block at all". */
    val glitch: GlitchDraft? = null,
) {

    fun problems(): List<DraftProblem> = buildList {
        if (name.isBlank()) {
            add(DraftProblem("theme.name", "A theme name is required."))
        }
        if (schema < 1 || schema > CURRENT_SCHEMA) {
            add(DraftProblem("theme.schema", "The theme schema is 1 to $CURRENT_SCHEMA."))
        }
        if (cornerRadiusDp !in MIN_CORNER_DP..MAX_CORNER_DP) {
            add(DraftProblem("theme.cornerRadiusDp", "Corner radius is $MIN_CORNER_DP to $MAX_CORNER_DP dp."))
        }
        if (uiScale < MIN_UI_SCALE || uiScale > MAX_UI_SCALE) {
            add(DraftProblem("theme.uiScale", "UI scale is ${MIN_UI_SCALE} to ${MAX_UI_SCALE}."))
        }
        if (typographyScale < MIN_TYPO_SCALE || typographyScale > MAX_TYPO_SCALE) {
            add(DraftProblem("theme.typographyScale", "Type scale is $MIN_TYPO_SCALE to $MAX_TYPO_SCALE."))
        }
        if (font !in KNOWN_FONTS) {
            add(DraftProblem("theme.font", "\"$font\" is not a typeface GameCore bundles."))
        }
        if (typographyWeight !in KNOWN_WEIGHTS) {
            add(DraftProblem("theme.typographyWeight", "\"$typographyWeight\" is not a known weight."))
        }
        if (glass != null && schema < SCHEMA_GLASS) {
            add(DraftProblem("theme.schema", "Glass needs schema $SCHEMA_GLASS; this theme declares $schema."))
        }
        if (glitch != null && schema < SCHEMA_EFFECTS) {
            add(DraftProblem("theme.schema", "Effects need schema $SCHEMA_EFFECTS; this theme declares $schema."))
        }
        glass?.problems()?.forEach { add(DraftProblem("theme.glass.${it.field}", it.message)) }
        glitch?.problems()?.forEach { add(DraftProblem("theme.glitch.${it.field}", it.message)) }
    }

    /** The opaque RGB of a colour, for contrast checks and previews. */
    fun opaque(color: Long): Long = 0xFF000000L or (color and 0xFFFFFFL)

    companion object {
        /** The schema this studio writes. */
        const val CURRENT_SCHEMA = 4

        /** `effects` arrived with schema 3; `glass` with schema 4. */
        const val SCHEMA_EFFECTS = 3
        const val SCHEMA_GLASS = 4

        const val MIN_CORNER_DP = 0
        const val MAX_CORNER_DP = 48
        const val MIN_UI_SCALE = 0.8f
        const val MAX_UI_SCALE = 1.4f
        const val MIN_TYPO_SCALE = 0.85f
        const val MAX_TYPO_SCALE = 1.30f

        const val FONT_SYSTEM = "system"
        const val FONT_ROUNDED = "rounded"
        const val FONT_MONO = "mono"
        const val FONT_SERIF = "serif"
        const val FONT_CURSIVE = "cursive"

        /** Every face a theme may name. The bundled open-source faces have their own ids. */
        val KNOWN_FONTS: List<String> = listOf(
            FONT_SYSTEM,
            FONT_ROUNDED,
            FONT_MONO,
            FONT_SERIF,
            FONT_CURSIVE,
            "minecraft",
            "cinzel",
            "comic-neue",
            "inter",
            "jetbrains-mono",
            "orbitron",
            "press-start-2p",
            "rubik",
            "vt323",
        )

        /** The host's own display names, kept here so the picker reads like a type specimen. */
        val FONT_NAMES: Map<String, String> = mapOf(
            FONT_SYSTEM to "System",
            FONT_ROUNDED to "Rounded",
            FONT_MONO to "Mono",
            FONT_SERIF to "Serif",
            FONT_CURSIVE to "Cursive",
            "minecraft" to "Minecraft",
            "cinzel" to "Cinzel",
            "comic-neue" to "Comic Neue",
            "inter" to "Inter",
            "jetbrains-mono" to "JetBrains Mono",
            "orbitron" to "Orbitron",
            "press-start-2p" to "Press Start 2P",
            "rubik" to "Rubik",
            "vt323" to "VT323",
        )

        val KNOWN_WEIGHTS: List<String> = listOf("light", "normal", "medium", "bold")

        /** The colour roles, in the order the editor and the quality check present them. */
        val COLOR_FIELDS: List<ColorFieldSpec> = listOf(
            ColorFieldSpec("primary", "Primary", "Main actions and selected states"),
            ColorFieldSpec("onPrimary", "On primary", "Ink drawn on primary"),
            ColorFieldSpec("background", "Background", "The page behind everything"),
            ColorFieldSpec("surface", "Surface", "Cards, sheets and bars"),
            ColorFieldSpec("onSurface", "On surface", "Body ink on cards"),
            ColorFieldSpec("accent", "Accent", "Highlights and secondary emphasis"),
            ColorFieldSpec("secondary", "Secondary", "A quieter supporting colour"),
            ColorFieldSpec("tertiary", "Tertiary", "A third supporting colour"),
            ColorFieldSpec("surfaceVariant", "Surface variant", "Nested cards and inert fills"),
            ColorFieldSpec("onSurfaceVariant", "On surface variant", "Secondary ink on cards"),
            ColorFieldSpec("outline", "Outline", "Borders and dividers"),
            ColorFieldSpec("error", "Error", "Failure and destructive states"),
            ColorFieldSpec("onError", "On error", "Ink drawn on error"),
        )
    }
}

/** One editable colour: the JSON key, the label and the sentence under it. */
data class ColorFieldSpec(val key: String, val label: String, val description: String)

/**
 * The schema-4 glass material, as the editor holds it before it becomes JSON.
 *
 * Every bound mirrors [PluginGlass] on the host. [intensitySetting] is the one real bridge from a
 * Studio-authored setting to runtime behaviour; [PluginDraft.problems] verifies that it resolves to
 * a numeric setting before export.
 */
data class GlassDraft(
    val intensity: Float = 0.7f,
    val transparency: Float = 0.55f,
    val gloss: Float = 0.45f,
    val blurRadiusDp: Int = 16,
    val sheen: Boolean = true,
    val sheenPeriodMs: Int = 6000,
    /** One of `none`, `gentle`, `snappy`, `bouncy`. */
    val spring: String = "snappy",
    val hardwareReactive: Boolean = false,
    /** A numeric setting id that scales the material, or null/blank for fixed intensity. */
    val intensitySetting: String? = null,
) {
    fun problems(): List<DraftProblem> = buildList {
        if (intensity !in MIN_FRACTION..MAX_FRACTION) {
            add(DraftProblem("intensity", "Glass intensity is 0 to 1."))
        }
        if (transparency !in MIN_FRACTION..MAX_FRACTION) {
            add(DraftProblem("transparency", "Glass transparency is 0 to 1."))
        }
        if (gloss !in MIN_FRACTION..MAX_FRACTION) {
            add(DraftProblem("gloss", "Glass gloss is 0 to 1."))
        }
        if (blurRadiusDp !in MIN_BLUR_DP..MAX_BLUR_DP) {
            add(DraftProblem("blurRadiusDp", "Blur radius is $MIN_BLUR_DP to $MAX_BLUR_DP dp."))
        }
        if (sheenPeriodMs !in MIN_SHEEN_MS..MAX_SHEEN_MS) {
            add(DraftProblem("sheenPeriodMs", "Sheen period is $MIN_SHEEN_MS to $MAX_SHEEN_MS ms."))
        }
        if (spring !in KNOWN_SPRINGS) {
            add(DraftProblem("spring", "\"$spring\" is not a glass spring."))
        }
        intensitySetting?.trim()?.takeIf { it.isNotEmpty() }?.let { setting ->
            if (!INTENSITY_SETTING_ID.matches(setting)) {
                add(
                    DraftProblem(
                        "intensitySetting",
                        "A glass intensity setting is a lowercase storage-key slug of at most " +
                            "$MAX_INTENSITY_SETTING_ID_LENGTH characters.",
                    ),
                )
            }
        }
    }

    companion object {
        const val MIN_FRACTION = 0f
        const val MAX_FRACTION = 1f
        const val MIN_BLUR_DP = 0
        const val MAX_BLUR_DP = 32
        const val MIN_SHEEN_MS = 1500
        const val MAX_SHEEN_MS = 20000
        const val MAX_INTENSITY_SETTING_ID_LENGTH = 64
        val KNOWN_SPRINGS = listOf("none", "gentle", "snappy", "bouncy")
        private val INTENSITY_SETTING_ID =
            Regex("^[a-z0-9][a-z0-9._-]{0,${MAX_INTENSITY_SETTING_ID_LENGTH - 1}}$")
    }
}

/**
 * The schema-3 glitch motion block.
 *
 * The host treats these as a request, not a program: it owns every frame, and a user's
 * animations-off setting overrides the whole block. The studio keeps the same bounds so a
 * slider can never write a value the codec would clamp without telling the author.
 */
data class GlitchDraft(
    val intensity: Float = 0.6f,
    val slices: Int = 3,
    val sliceOffsetDp: Int = 18,
    val ghostOffsetDp: Int = 4,
    val fractureLines: Boolean = true,
    val noise: Boolean = true,
    val microJitter: Boolean = true,
    val hardwareReactive: Boolean = true,
    val burstMinMs: Int = 150,
    val burstMaxMs: Int = 800,
) {
    fun problems(): List<DraftProblem> = buildList {
        if (intensity !in MIN_INTENSITY..MAX_INTENSITY) {
            add(DraftProblem("intensity", "Glitch intensity is 0 to 1."))
        }
        if (slices !in MIN_SLICES..MAX_SLICES) {
            add(DraftProblem("slices", "Glitch slices are $MIN_SLICES to $MAX_SLICES."))
        }
        if (sliceOffsetDp !in MIN_SLICE_OFFSET_DP..MAX_SLICE_OFFSET_DP) {
            add(DraftProblem("sliceOffsetDp", "Slice offset is $MIN_SLICE_OFFSET_DP to $MAX_SLICE_OFFSET_DP dp."))
        }
        if (ghostOffsetDp !in MIN_GHOST_OFFSET_DP..MAX_GHOST_OFFSET_DP) {
            add(DraftProblem("ghostOffsetDp", "Ghost offset is $MIN_GHOST_OFFSET_DP to $MAX_GHOST_OFFSET_DP dp."))
        }
        if (burstMinMs !in MIN_BURST_MS..MAX_BURST_MS) {
            add(DraftProblem("burstMinMs", "The shortest burst gap is $MIN_BURST_MS to $MAX_BURST_MS ms."))
        }
        if (burstMaxMs !in MIN_BURST_MS..MAX_BURST_MS) {
            add(DraftProblem("burstMaxMs", "The longest burst gap is $MIN_BURST_MS to $MAX_BURST_MS ms."))
        }
        if (burstMinMs > burstMaxMs) {
            add(DraftProblem("burstMinMs", "The shortest burst gap must not exceed the longest."))
        }
    }

    companion object {
        const val MIN_INTENSITY = 0f
        const val MAX_INTENSITY = 1f
        const val MIN_SLICES = 2
        const val MAX_SLICES = 6
        const val MIN_SLICE_OFFSET_DP = 0
        const val MAX_SLICE_OFFSET_DP = 48
        const val MIN_GHOST_OFFSET_DP = 0
        const val MAX_GHOST_OFFSET_DP = 12
        const val MIN_BURST_MS = 120
        const val MAX_BURST_MS = 8000
    }
}
