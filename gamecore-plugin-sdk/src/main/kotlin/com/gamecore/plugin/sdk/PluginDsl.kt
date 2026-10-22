package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject

/** The `type` the host reads a chart node by (`ComponentType.CHART.id`). */
private const val CHART_NODE_TYPE = "chart"

/** Where an icon's optional image is written, chosen by the image's own format. */
private const val ARTWORK_PNG = "assets/icon.png"
private const val ARTWORK_WEBP = "assets/icon.webp"

/** `\x89PNG\r\n\x1a\n` — the eight bytes the host checks before it will decode artwork. */
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** `RIFF`, and the `WEBP` four bytes at offset eight that make it a WebP rather than a WAV. */
private val RIFF_MAGIC = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val WEBP_MAGIC = byteArrayOf(0x57, 0x45, 0x42, 0x50)

/** Whether [this] carries [magic] starting at [offset]. False when the array is too short to tell. */
private fun ByteArray.matchesAt(magic: ByteArray, offset: Int): Boolean {
    if (size < offset + magic.size) return false
    for (i in magic.indices) if (this[offset + i] != magic[i]) return false
    return true
}

/**
 * The Kotlin DSL for defining a plugin. A `plugin { }` block builds an in-memory description
 * that [PluginPackager] turns into a `.gcplugin` zip.
 *
 * This is a build-time authoring aid, not a runtime extension point: the DSL only ever emits
 * JSON and asset bytes, exactly the data the host loads. There is no hook, no callback, no
 * code path from this DSL into the running app — a plugin "runs" by being interpreted, never
 * by executing anything written here.
 */
@DslMarker
annotation class PluginDsl

fun plugin(id: String, name: String, block: PluginBuilder.() -> Unit): PluginDefinition =
    PluginBuilder(id, name).apply(block).build()

@PluginDsl
class PluginBuilder(private val id: String, private val name: String) {
    var version: Int = 1
    var versionName: String = "1.0"
    var api: Int = Sdk.API
    var minHostVersion: Int = Sdk.HOST_VERSION_CODE
    var developer: String = "Unknown"
    var description: String = ""
    var changelog: String = ""

    private val permissions = mutableListOf<RequestedPermission>()
    private val sections = linkedMapOf<String, ByteArray>()
    private val declared = linkedSetOf<String>()

    /** Request a permission, optionally scoped (scopes only meaningful for read_approved_data/modify_settings). */
    fun request(permission: String, scopes: Set<String> = emptySet()) {
        permissions += RequestedPermission(permission, scopes)
    }

    /** Attach a theme section built with [ThemeBuilder]. */
    fun theme(block: ThemeBuilder.() -> Unit) {
        val builder = ThemeBuilder().apply(block)
        sections["theme.json"] = builder.toJson().toString(2).toByteArray()
        declared += "theme"
        // Per-use api bump: a theme that touched a schema-2 or schema-3 feature needs a host that
        // understands it, so raise the manifest api to match. A pure v1 theme leaves api at Sdk.API (1).
        val schema = builder.schemaVersion()
        if (schema >= ThemeSchema.V2) {
            api = maxOf(api, schema)
        }
    }

    /** Attach automation rules built with [RulesBuilder]. */
    fun rules(block: RulesBuilder.() -> Unit) {
        val json = RulesBuilder().apply(block).toJson()
        sections["rules/rules.json"] = json.toString(2).toByteArray()
        declared += "rules"
    }

    /**
     * Attach a typed settings section built with [SettingsBuilder].
     *
     * Like [theme], this writes `settings.json` and declares the `settings` section in one act,
     * so the manifest's `declares` and the package contents can never disagree. Settings are an
     * API-3 feature, so declaring any raises the manifest `api` to [Sdk.API_V3]; a plugin that
     * declares none leaves `api` at [Sdk.API] and is byte-for-byte the file it was before this
     * existed.
     */
    fun settings(block: SettingsBuilder.() -> Unit) {
        val json = SettingsBuilder().apply(block).toJson()
        sections["settings.json"] = json.toString(2).toByteArray()
        declared += "settings"
        api = maxOf(api, Sdk.API_V3)
    }

    /**
     * Attach the overlay module built with [PillBuilder].
     *
     * Declaring the section and writing the file are one act here, which is the point: the
     * host rejects a package whose `declares` and contents disagree in *either* direction, so
     * the SDK never lets them be written separately.
     *
     * A module is drawn over a game, so the plugin must also request
     * [Permissions.CREATE_OVERLAY], and any `stat` node needs
     * [Permissions.READ_APPROVED_DATA] scoped to that stat's [OverlayStats.scopeFor] — a
     * missing grant is not malformed JSON, so no codec will catch it for you.
     */
    fun pill(block: PillBuilder.() -> Unit) {
        val json = PillBuilder().apply(block).toJson()
        sections["pill.json"] = json.toString(2).toByteArray()
        declared += "pill"
        // Per-use api bump, mirroring the schema-2 theme bump: a chart node and a motion stat are
        // each API-3 features, so a module that draws either needs a host that understands it. A
        // module with neither leaves api at Sdk.API, so its pill.json and manifest stay
        // byte-identical to a build that predates both.
        if (pillUsesChart(json) || pillUsesMotionReading(json)) {
            api = maxOf(api, Sdk.API_V3)
        }
    }

    /**
     * Attach one or more declarative screens built with [ScreensBuilder].
     *
     * Like [rules], declaring the section and writing the file are one act, so the host never sees a
     * manifest whose `declares` and screen files disagree. Declarative screens are a 3.x host feature,
     * so a package that ships one raises its manifest `api` to [Sdk.API_V3] — the same per-use bump
     * [theme] makes for a schema-2 theme. A plugin that reaches for none of the 3.x features stays at
     * [Sdk.API] and emits byte-identical output to a build that predates them.
     */
    fun screens(block: ScreensBuilder.() -> Unit) {
        val json = ScreensBuilder().apply(block).toJson()
        sections["screens/screens.json"] = json.toString(2).toByteArray()
        declared += "screens"
        api = maxOf(api, Sdk.API_V3)
    }

    /**
     * Attach one or more read-only dashboard widgets built with [WidgetsBuilder].
     *
     * Each widget is its own `widgets/<id>.json` file, and declaring the section and writing those
     * files are one act — like [screens], so the host never sees a manifest whose `declares` names
     * `widgets` with no file behind it, nor a widget file the manifest never declared. Dashboard
     * widgets are a 3.x host feature, so a package that ships any raises its manifest `api` to
     * [Sdk.API_V3] — the same per-use bump [screens] and [settings] make, and unconditional here for
     * the same reason: [WidgetsBuilder.toFiles] refuses an empty section, so reaching this bump means
     * at least one widget was declared. A plugin that calls this never leaves its `api` at [Sdk.API]
     * and emits byte-identical output to a build that predates the feature.
     *
     * A dashboard widget draws GameCore's own approved readings, so a plugin that ships one must also
     * request [Permissions.ADD_DASHBOARD_WIDGETS], and any `stat` node needs
     * [Permissions.READ_APPROVED_DATA] scoped to that stat's [OverlayStats.scopeFor] — a missing grant
     * is not malformed JSON, so no codec will catch it for you.
     */
    fun widgets(block: WidgetsBuilder.() -> Unit) {
        val files = WidgetsBuilder().apply(block).toFiles()
        files.forEach { (path, json) -> sections[path] = json.toString(2).toByteArray() }
        declared += "widgets"
        api = maxOf(api, Sdk.API_V3)
    }

    /** Attach a raw section file (escape hatch for sections without a dedicated builder yet). */
    fun section(path: String, declares: String, bytes: ByteArray) {
        sections[path] = bytes
        declared += declares
    }

    /**
     * Attach the icon section: which of GameCore's launcher icons this plugin asks the app to wear,
     * and optionally an image to offer as a pinned home-screen shortcut.
     *
     * **What this actually changes.** [slot] picks one of [IconSlots], and that really does change
     * the launcher icon: the host ships each one as its own launcher component and switches between
     * them. What no plugin can do is install its own drawing as *the* launcher icon — Android takes
     * that from the installed APK's manifest, and there is no API that accepts a bitmap for it. So
     * [artwork] is not a second way to set the icon. It is an image the host offers to **pin** as an
     * extra home-screen shortcut, which the user accepts in one tap. A plugin that ships artwork
     * should still name a [slot]: that is the part that works without asking the user for anything.
     *
     * Declaring `icon` and requesting [Permissions.CHANGE_ICON] are separate on purpose, like every
     * other section. A package that declares the section but never requests the permission installs
     * cleanly and simply never gets to choose — the host's gate decides, not this DSL.
     *
     * Raises `minHostVersion` to [Sdk.HOST_VERSION_CODE_ICON] and only that section does, so a
     * plugin that asks for an icon declines to install on a host that would ignore the request
     * instead of installing with a feature that silently does nothing. Artwork is checked here
     * rather than left to the host because an unreadable image does not degrade — the host refuses
     * the whole `icon.json`, and the slot request goes with it.
     *
     * ```kotlin
     * request(Permissions.CHANGE_ICON)
     * icon(slot = IconSlots.GLITCH, artwork = iconPng, label = "Torn")
     * ```
     */
    fun icon(slot: String, artwork: ByteArray? = null, label: String? = null) {
        val slotId = slot.trim().lowercase()
        require(slotId in IconSlots.ALL) {
            "icon slot is \"$slot\"; the host accepts ${IconSlots.ALL.joinToString()} — a launcher " +
                "icon can only be one GameCore already ships, so any other name is a request the " +
                "host cannot honour"
        }
        // A second call must not leave the first call's image behind as an entry the manifest never
        // names. The host never reads it, so nothing would catch it — the package would just carry
        // dead bytes until the total-size cap noticed.
        sections.remove(ARTWORK_PNG)
        sections.remove(ARTWORK_WEBP)

        val json = JSONObject().apply {
            put("slot", slotId)
            artwork?.let { bytes ->
                val path = artworkPath(bytes)
                sections[path] = bytes
                put("artwork", path)
            }
            label?.let {
                require(it.isNotBlank()) {
                    "icon label is blank; omit the argument to keep the app's own name on the shortcut"
                }
                put("label", plainText("icon label", it.trim(), IconBounds.MAX_LABEL_LENGTH))
            }
        }
        sections["icon.json"] = json.toString(2).toByteArray()
        declared += "icon"
        minHostVersion = maxOf(minHostVersion, Sdk.HOST_VERSION_CODE_ICON)
    }

    /**
     * The package path for an image, by its magic bytes rather than by an extension the author had
     * to remember. The host sniffs the same way, so a JPEG renamed `.png` is refused here instead of
     * on the user's device.
     */
    private fun artworkPath(bytes: ByteArray): String {
        require(bytes.size <= IconBounds.MAX_ARTWORK_BYTES) {
            "icon artwork is ${bytes.size} bytes; the cap is ${IconBounds.MAX_ARTWORK_BYTES}. " +
                "A launcher-size image does not need more, and the host refuses the whole icon " +
                "section over it"
        }
        return when {
            bytes.matchesAt(PNG_MAGIC, 0) -> ARTWORK_PNG
            bytes.matchesAt(RIFF_MAGIC, 0) && bytes.matchesAt(WEBP_MAGIC, 8) -> ARTWORK_WEBP
            else -> throw IllegalArgumentException(
                "icon artwork is not a PNG or WebP image. The host reads launcher art from these " +
                    "two formats only, and an unreadable image refuses the whole icon section " +
                    "rather than just the shortcut",
            )
        }
    }

    /** True when the finished pill tree contains a chart node at any depth. */
    private fun pillUsesChart(pill: JSONObject): Boolean {
        fun walk(node: JSONObject): Boolean {
            if (node.optString("type") == CHART_NODE_TYPE) return true
            val children = node.optJSONArray("children") ?: return false
            for (i in 0 until children.length()) {
                if (walk(children.getJSONObject(i))) return true
            }
            return false
        }
        return walk(pill.getJSONObject("content"))
    }

    internal fun build(): PluginDefinition {
        val manifest = JSONObject().apply {
            put("format", Sdk.FORMAT)
            put("id", id)
            put("name", name)
            put("version", version)
            put("versionName", versionName)
            put("api", api)
            put("minHostVersion", minHostVersion)
            put("developer", developer)
            if (description.isNotEmpty()) put("description", description)
            if (changelog.isNotEmpty()) put("changelog", changelog)
            if (permissions.isNotEmpty()) {
                put("permissions", JSONArray().apply {
                    permissions.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            if (p.scopes.isNotEmpty()) put("scopes", JSONArray(p.scopes.toList()))
                        })
                    }
                })
            }
            if (declared.isNotEmpty()) put("declares", JSONArray(declared.toList()))
        }
        return PluginDefinition(
            manifestJson = manifest.toString(2).toByteArray(),
            sectionFiles = sections.toMap(),
        )
    }

    data class RequestedPermission(val id: String, val scopes: Set<String>)
}

/** The bytes of a plugin, ready to be zipped. */
data class PluginDefinition(
    val manifestJson: ByteArray,
    val sectionFiles: Map<String, ByteArray>,
)

/**
 * Whether a built `pill.json` draws any [OverlayStats.MOTION_READINGS] stat. Walked from the finished
 * tree rather than tracked as the DSL builds, so a motion stat added any way at all is caught, and
 * iterative so a deeply nested module cannot overflow the stack while deciding its own api level.
 */
private fun pillUsesMotionReading(pill: JSONObject): Boolean {
    val root = pill.optJSONObject("content") ?: return false
    val pending = ArrayDeque<JSONObject>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.optString("type") == "stat") {
            val statId = node.optJSONObject("props")?.optString("stat")
            if (statId != null && statId in OverlayStats.MOTION_READINGS) return true
        }
        val children = node.optJSONArray("children") ?: continue
        for (i in 0 until children.length()) pending.addLast(children.getJSONObject(i))
    }
    return false
}

/**
 * Builds a plugin's `theme.json`: the six colours GameCore repaints itself with, plus a font,
 * a corner radius and a density nudge.
 *
 * Every value is checked here, and the two kinds of host failure are worth telling apart,
 * because they are why checking at all is worth the lines:
 *
 * - A malformed colour or an unknown font is a **hard rejection of the whole theme**. The
 *   plugin installs, its switch turns on, and the app stays exactly as it was. Nothing in the
 *   UI distinguishes that from a theme the user has not picked yet.
 * - A corner radius or UI scale outside the host's range is **clamped**. The theme does load,
 *   and it loads looking like something the author never chose.
 *
 * The defaults are GameCore's own dark palette, so a `theme { }` block that sets nothing but a
 * name is valid and legible. The eleven v1 values are emitted every time, including the ones left
 * at their default: the host requires the six colours and defaults the rest, and a theme file
 * that spells out what it means is one a developer can read back and diff. The schema-2 extras —
 * [amoled], [roles] and [typography] — are additive and appear only when the theme uses one, so a
 * theme that touches none of them is byte-for-byte the v1 file it was before they existed.
 */
@PluginDsl
class ThemeBuilder {
    var name: String = "Theme"
    var dark: Boolean = true
    var primary: String = "#4F8CFF"
    var onPrimary: String = "#FFFFFF"
    var background: String = "#000000"
    var surface: String = "#101216"
    var onSurface: String = "#E6E8EC"
    var accent: String = "#4F8CFF"
    var cornerRadiusDp: Int = 16

    /** One of [ThemeFonts]. A theme names a font the app already bundles; it cannot ship one. */
    var font: String = ThemeFonts.SYSTEM

    /** A density nudge in [ThemeBounds.MIN_UI_SCALE]..[ThemeBounds.MAX_UI_SCALE]. */
    var uiScale: Double = 1.0

    /** From schema 2: base the dark theme on the true-black AMOLED scheme. Absent on a v1 theme. */
    var amoled: Boolean = false

    private var rolesBuilder: ThemeRolesBuilder? = null
    private var typographyBuilder: ThemeTypographyBuilder? = null
    private var glitchBuilder: ThemeGlitchBuilder? = null

    /** From schema 2: recolour semantic roles beyond the v1 six. A role left unset is inherited. */
    fun roles(block: ThemeRolesBuilder.() -> Unit) {
        rolesBuilder = (rolesBuilder ?: ThemeRolesBuilder()).apply(block)
    }

    /** From schema 2: a type-ramp scale and/or one global weight. */
    fun typography(block: ThemeTypographyBuilder.() -> Unit) {
        typographyBuilder = (typographyBuilder ?: ThemeTypographyBuilder()).apply(block)
    }

    /**
     * From schema 3: ask the host to distort what it draws.
     *
     * This is a request, not a program — the theme says how hard, and the host owns every frame of
     * what that looks like. See the host's `PluginGlitch` for what each value means and
     * [GlitchBounds] for the ranges, which the DSL asserts so an out-of-range theme fails in the
     * author's build rather than arriving clamped in a user's hands.
     */
    fun glitch(block: ThemeGlitchBuilder.() -> Unit) {
        glitchBuilder = (glitchBuilder ?: ThemeGlitchBuilder()).apply(block)
    }

    /**
     * The schema this theme serialises as: V3 the moment it asks for motion, V2 for any other v2
     * feature, else V1. This gates both the keys [toJson] emits and the manifest api bump in
     * [PluginBuilder.theme].
     */
    internal fun schemaVersion(): Int = when {
        glitchBuilder != null -> ThemeSchema.V3
        amoled || rolesBuilder?.any() == true || typographyBuilder?.any() == true -> ThemeSchema.V2
        else -> ThemeSchema.V1
    }

    internal fun toJson(): JSONObject {
        require(name.isNotBlank()) { "a theme needs a name" }
        val fontId = font.trim().lowercase()
        require(fontId in ThemeFonts.ALL) {
            "theme font is \"$font\"; a theme names one of GameCore's own bundled faces " +
                "(${ThemeFonts.ALL.joinToString()}) and the host rejects the whole theme otherwise"
        }
        require(cornerRadiusDp in ThemeBounds.MIN_CORNER_DP..ThemeBounds.MAX_CORNER_DP) {
            "cornerRadiusDp is $cornerRadiusDp; the host clamps to " +
                "${ThemeBounds.MIN_CORNER_DP}..${ThemeBounds.MAX_CORNER_DP}, so a value outside " +
                "it ships as a corner the author never picked"
        }
        require(uiScale in ThemeBounds.MIN_UI_SCALE..ThemeBounds.MAX_UI_SCALE) {
            "uiScale is $uiScale; the host clamps to ${ThemeBounds.MIN_UI_SCALE}.." +
                "${ThemeBounds.MAX_UI_SCALE} — a theme may lean the UI, not rebuild it"
        }

        return JSONObject().apply {
            put("name", plainText("theme name", name, ThemeBounds.MAX_NAME_LENGTH))
            put("dark", dark)
            put("primary", hexColor("theme primary", primary))
            put("onPrimary", hexColor("theme onPrimary", onPrimary))
            put("background", hexColor("theme background", background))
            put("surface", hexColor("theme surface", surface))
            put("onSurface", hexColor("theme onSurface", onSurface))
            put("accent", hexColor("theme accent", accent))
            put("cornerRadiusDp", cornerRadiusDp)
            put("font", fontId)
            put("uiScale", uiScale)
            // Additive v2+ extras — emitted ONLY when the feature was used, so a pure v1 theme is
            // byte-for-byte the 11-key file it has always been.
            if (schemaVersion() >= ThemeSchema.V2) {
                put("schema", schemaVersion())
                if (amoled) put("amoled", true)
                rolesBuilder?.toJsonOrNull()?.let { put("roles", it) }
                typographyBuilder?.toJsonOrNull()?.let { put("typography", it) }
            }
            if (schemaVersion() >= ThemeSchema.V3) {
                glitchBuilder?.let { put("effects", JSONObject().put("glitch", it.toJson())) }
            }
        }
    }
}

/**
 * The seven optional [ThemeRoles] a v2 theme may recolour. Each is validated as a hex colour the
 * same way the v1 colours are, and only the roles the author set are emitted.
 */
@PluginDsl
class ThemeRolesBuilder {
    var secondary: String? = null
    var tertiary: String? = null
    var surfaceVariant: String? = null
    var onSurfaceVariant: String? = null
    var outline: String? = null
    var error: String? = null
    var onError: String? = null

    internal fun any(): Boolean =
        listOf(secondary, tertiary, surfaceVariant, onSurfaceVariant, outline, error, onError)
            .any { it != null }

    internal fun toJsonOrNull(): JSONObject? {
        val json = JSONObject()
        secondary?.let { json.put(ThemeRoles.SECONDARY, hexColor("theme role secondary", it)) }
        tertiary?.let { json.put(ThemeRoles.TERTIARY, hexColor("theme role tertiary", it)) }
        surfaceVariant?.let { json.put(ThemeRoles.SURFACE_VARIANT, hexColor("theme role surfaceVariant", it)) }
        onSurfaceVariant?.let { json.put(ThemeRoles.ON_SURFACE_VARIANT, hexColor("theme role onSurfaceVariant", it)) }
        outline?.let { json.put(ThemeRoles.OUTLINE, hexColor("theme role outline", it)) }
        error?.let { json.put(ThemeRoles.ERROR, hexColor("theme role error", it)) }
        onError?.let { json.put(ThemeRoles.ON_ERROR, hexColor("theme role onError", it)) }
        return if (json.length() == 0) null else json
    }
}

/**
 * A v2 theme's type controls: a scale on the whole ramp and/or one global weight. The host clamps
 * the scale; this SDK refuses one out of range, the same bargain the corner radius and UI scale
 * strike — a value the host quietly adjusted is one the author never saw.
 */
@PluginDsl
class ThemeTypographyBuilder {
    /** Multiplies every role's size and line height together. In [ThemeBounds.MIN_TYPE_SCALE]..[ThemeBounds.MAX_TYPE_SCALE]. */
    var scale: Double = ThemeBounds.DEFAULT_TYPE_SCALE

    /** One of [ThemeWeights], or null to keep each role's own designed weight. */
    var weight: String? = null

    internal fun any(): Boolean = scale != ThemeBounds.DEFAULT_TYPE_SCALE || weight != null

    internal fun toJsonOrNull(): JSONObject? {
        val json = JSONObject()
        if (scale != ThemeBounds.DEFAULT_TYPE_SCALE) {
            require(scale in ThemeBounds.MIN_TYPE_SCALE..ThemeBounds.MAX_TYPE_SCALE) {
                "theme type scale is $scale; the host clamps to ${ThemeBounds.MIN_TYPE_SCALE}.." +
                    "${ThemeBounds.MAX_TYPE_SCALE}, so a value outside it ships as a ramp the author never chose"
            }
            json.put("scale", scale)
        }
        weight?.let {
            val id = it.trim().lowercase()
            require(id in ThemeWeights.ALL) {
                "theme type weight is \"$it\"; a theme names one of ${ThemeWeights.ALL.joinToString()}"
            }
            json.put("weight", id)
        }
        return if (json.length() == 0) null else json
    }
}

/**
 * The schema-3 motion block: how hard the host's renderer should distort the UI.
 *
 * Everything here is a *request* the host clamps and, for motion-off users, ignores entirely. It
 * exists so a theme can be designed around movement — the host owns the frames, the theme owns the
 * character. The defaults are a mild, readable tear rather than the maximum, because a theme that
 * only sets `intensity` should still be usable on the screen that lets you turn it off.
 *
 * Every field is emitted, not just the ones changed from the default: the host applies its own
 * defaults for absent keys, and shipping a value the author can read in the artifact is worth more
 * than a shorter file.
 */
@PluginDsl
class ThemeGlitchBuilder {
    /** How far every other value leans toward its maximum. In [GlitchBounds.MIN_INTENSITY]..[GlitchBounds.MAX_INTENSITY]. */
    var intensity: Double = 0.6

    /** How many horizontal bands the frame is torn into. In [GlitchBounds.MIN_SLICES]..[GlitchBounds.MAX_SLICES]. */
    var slices: Int = 3

    /** The furthest, in dp, a band may slide sideways. */
    var sliceOffsetDp: Int = 18

    /** The gap, in dp, between a band and its ghost copies. */
    var ghostOffsetDp: Int = 4

    /** Draw a one-pixel line in the theme's accent at each band boundary. */
    var fractureLines: Boolean = true

    /** Draw translucent block noise during a burst. */
    var noise: Boolean = true

    /** One-frame, one-or-two-pixel displacements between bursts. */
    var microJitter: Boolean = true

    /** Let the device's own CPU and thermal state change the rhythm. Off means no sensor reads. */
    var hardwareReactive: Boolean = true

    /** The shortest and longest idle between bursts, in milliseconds. */
    var burstMinMs: Int = 150
    var burstMaxMs: Int = 800

    internal fun toJson(): JSONObject {
        require(intensity in GlitchBounds.MIN_INTENSITY..GlitchBounds.MAX_INTENSITY) {
            "glitch intensity is $intensity; the host clamps to " +
                "${GlitchBounds.MIN_INTENSITY}..${GlitchBounds.MAX_INTENSITY}"
        }
        require(slices in GlitchBounds.MIN_SLICES..GlitchBounds.MAX_SLICES) {
            "glitch slices is $slices; the host clamps to " +
                "${GlitchBounds.MIN_SLICES}..${GlitchBounds.MAX_SLICES} — fewer than two is not a tear"
        }
        require(sliceOffsetDp in GlitchBounds.MIN_SLICE_OFFSET_DP..GlitchBounds.MAX_SLICE_OFFSET_DP) {
            "glitch sliceOffsetDp is $sliceOffsetDp; the host clamps to " +
                "${GlitchBounds.MIN_SLICE_OFFSET_DP}..${GlitchBounds.MAX_SLICE_OFFSET_DP}"
        }
        require(ghostOffsetDp in GlitchBounds.MIN_GHOST_OFFSET_DP..GlitchBounds.MAX_GHOST_OFFSET_DP) {
            "glitch ghostOffsetDp is $ghostOffsetDp; the host clamps to " +
                "${GlitchBounds.MIN_GHOST_OFFSET_DP}..${GlitchBounds.MAX_GHOST_OFFSET_DP}"
        }
        require(burstMinMs in GlitchBounds.MIN_BURST_MS..GlitchBounds.MAX_BURST_MS) {
            "glitch burstMinMs is $burstMinMs; the host clamps to " +
                "${GlitchBounds.MIN_BURST_MS}..${GlitchBounds.MAX_BURST_MS}"
        }
        require(burstMaxMs in GlitchBounds.MIN_BURST_MS..GlitchBounds.MAX_BURST_MS) {
            "glitch burstMaxMs is $burstMaxMs; the host clamps to " +
                "${GlitchBounds.MIN_BURST_MS}..${GlitchBounds.MAX_BURST_MS}"
        }
        require(burstMinMs <= burstMaxMs) {
            "glitch burst range is $burstMinMs..$burstMaxMs; the low end must not exceed the high end"
        }
        return JSONObject().apply {
            put("intensity", intensity)
            put("slices", slices)
            put("sliceOffsetDp", sliceOffsetDp)
            put("ghostOffsetDp", ghostOffsetDp)
            put("fractureLines", fractureLines)
            put("noise", noise)
            put("microJitter", microJitter)
            put("hardwareReactive", hardwareReactive)
            put("burstMinMs", burstMinMs)
            put("burstMaxMs", burstMaxMs)
        }
    }
}

/**
 * Builds a plugin's `rules/rules.json`: a flat list of "when TRIGGER, if CONDITIONS, do
 * ACTIONS" rules.
 *
 * **A rule names an action; it never carries one.** There is no command string, no settings
 * key and no value anywhere in this grammar — an action is an id from [Actions], and what it
 * does, what it clamps to and whether it needs a second opt-in is the broker's business, not
 * the plugin's. That is the whole reason automations can exist at all without a plugin ever
 * holding a shell.
 *
 * Everything a rule names is resolved here, because each of the four names fails in a
 * different way and only one of them is loud:
 *
 * - An unknown **trigger**, **operator** or **action id** is a rejection of the entire rules
 *   file — one typo costs the plugin every rule in it, not just the one that carried it.
 * - An unknown **variable** is the quiet one, and the reason [RuleVariables] exists: the host
 *   copies the name through unresolved and the engine reads a name it cannot find as a
 *   condition that is simply not met, so the rule installs and never once fires.
 *
 * ```kotlin
 * rules {
 *     rule("boost", Triggers.GAME_LAUNCHED, listOf(Actions.SET_PEAK_REFRESH_RATE))
 *     rule(
 *         "cool_down", Triggers.THERMAL_HIGH,
 *         actions = listOf(Actions.SET_MIN_REFRESH_RATE),
 *         conditions = listOf(Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0)),
 *     )
 * }
 * ```
 */
@PluginDsl
class RulesBuilder {
    private val rules = JSONArray()
    private val ids = linkedSetOf<String>()

    /**
     * One rule. [trigger] is from [Triggers], each action from [Actions], each condition built
     * with [Condition].
     *
     * [actions] must be non-empty: the host's validator refuses an action-less rule rather than
     * treating it as a no-op, so an empty list is a rules file that does not load.
     */
    fun rule(
        id: String,
        trigger: String,
        actions: List<String>,
        conditions: List<Condition> = emptyList(),
    ) {
        require(id.isNotBlank()) { "a rule needs an id" }
        val ruleId = plainText("rule id", id, OverlayBounds.MAX_ID_LENGTH)
        require(ids.add(ruleId)) {
            "two rules share the id \"$ruleId\"; the host names a rule by its id when it reports " +
                "a problem with one, so a duplicate makes that message ambiguous"
        }

        val triggerName = trigger.trim().uppercase()
        require(triggerName in Triggers.ALL) {
            "rule \"$ruleId\" has trigger \"$trigger\"; expected one of " +
                "${Triggers.ALL.joinToString()} — the host rejects the whole rules file on an " +
                "unknown trigger, not just this rule"
        }

        require(actions.isNotEmpty()) {
            "rule \"$ruleId\" has no actions; the host's validator refuses an action-less rule"
        }
        require(actions.size <= RuleBounds.MAX_ACTIONS_PER_RULE) {
            "rule \"$ruleId\" has ${actions.size} actions; the cap is " +
                "${RuleBounds.MAX_ACTIONS_PER_RULE}, and a rule over it is refused on install or " +
                "truncated on evaluation"
        }
        val actionIds = actions.map { raw ->
            val actionId = raw.trim().lowercase()
            require(actionId in Actions.ALL) {
                "rule \"$ruleId\" names action \"$raw\"; a rule may only name a broker action " +
                    "(${Actions.ALL.joinToString()})"
            }
            actionId
        }

        require(conditions.size <= RuleBounds.MAX_CONDITIONS_PER_RULE) {
            "rule \"$ruleId\" has ${conditions.size} conditions; the cap is " +
                "${RuleBounds.MAX_CONDITIONS_PER_RULE}"
        }
        val encoded = conditions.map { c -> c.toJson(ruleId) }

        require(rules.length() < RuleBounds.MAX_RULES) {
            "a rules file holds at most ${RuleBounds.MAX_RULES} rules"
        }

        rules.put(
            JSONObject().apply {
                put("id", ruleId)
                put("trigger", triggerName)
                put("actions", JSONArray(actionIds))
                if (encoded.isNotEmpty()) put("conditions", JSONArray(encoded))
            },
        )
    }

    /**
     * A single comparison: `variable op value`. No boolean operators and no nesting — a rule's
     * conditions are a flat conjunction, which is what keeps a rules file a decision table
     * rather than a program.
     *
     * [variable] is from [RuleVariables] and [op] from [RuleOperators]; see this class's note
     * for why the variable is the one worth getting right.
     */
    data class Condition(val variable: String, val op: String, val value: Double) {

        internal fun toJson(ruleId: String): JSONObject {
            val variableId = variable.trim().lowercase()
            require(variableId in RuleVariables.ALL) {
                "rule \"$ruleId\" compares against \"$variable\"; the stat feed publishes " +
                    "${RuleVariables.ALL.joinToString()}, and a name outside that set is not an " +
                    "error on the host — it is a condition that is never met, so the rule " +
                    "installs and silently never fires"
            }
            val opName = op.trim().uppercase()
            require(opName in RuleOperators.ALL) {
                "rule \"$ruleId\" uses operator \"$op\"; expected one of " +
                    RuleOperators.ALL.joinToString()
            }
            require(value.isFinite()) { "rule \"$ruleId\" compares against $value" }
            return JSONObject().apply {
                put("variable", variableId)
                put("op", opName)
                put("value", value)
            }
        }
    }

    internal fun toJson(): JSONObject {
        require(rules.length() > 0) { "a rules section needs at least one rule" }
        return JSONObject().put("rules", rules)
    }
}
