package com.gamecore.core.model

import kotlin.math.roundToInt

/**
 * A logical display size in pixels, as `wm size` states it.
 *
 * Two numbers, with three jobs that stop the rest of the feature improvising. [argument] is the exact token
 * the shell takes, so nothing else in the app formats a `WxH` string. [matches] compares two sizes without
 * caring which way up the device is, because the platform reports the panel in its natural orientation and
 * reports GameCore's own window in whatever orientation it is in — a verification that read 1080×1920 and
 * 1920×1080 as different sizes would call every landscape confirmation a failure. And [rejectionFor] is the
 * guard: a `wm size` override survives a reboot, so a size that leaves Android unusable is not a mistake the
 * user can tap their way out of afterwards.
 */
data class DisplaySize(val widthPixels: Int, val heightPixels: Int) {

    val longSide: Int get() = maxOf(widthPixels, heightPixels)

    val shortSide: Int get() = minOf(widthPixels, heightPixels)

    /** Spaced like [DisplayReading.resolutionLabel], because they appear on the same screens. */
    val label: String get() = "$widthPixels × $heightPixels"

    /** The one place a `WxH` shell argument is built. */
    val argument: String get() = "${widthPixels}x$heightPixels"

    /** Long side over short, so a panel and the same panel rotated read the same. */
    val aspect: Float get() = if (shortSide <= 0) 0f else longSide.toFloat() / shortSide

    /**
     * "20:9" where the reduced ratio is small enough to recognise, "2.22:1" where it is not. Reduced from
     * the pixel counts rather than matched against a table, because there is no table of screen shapes that
     * covers every panel a phone has shipped with.
     */
    val aspectLabel: String
        get() {
            if (shortSide <= 0) return "—"
            val divisor = gcd(longSide, shortSide)
            val short = shortSide / divisor
            if (short <= MAX_RECOGNISABLE_SHORT) return "${longSide / divisor}:$short"
            val hundredths = (aspect * 100).roundToInt()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}:1"
        }

    /** True when this is the same size as [other], held either way up. */
    fun matches(other: DisplaySize): Boolean = longSide == other.longSide && shortSide == other.shortSide

    /**
     * This size turned to match [reference]'s orientation.
     *
     * `wm size` reads its argument in the display's natural orientation, so `1920x1080` on a portrait phone
     * is a request for a landscape desktop rather than for a 16:9 game view. The custom fields let the user
     * type either way round; this is what makes both mean the same thing.
     */
    fun orientedLike(reference: DisplaySize): DisplaySize =
        if (isPortrait == reference.isPortrait) this else DisplaySize(heightPixels, widthPixels)

    /**
     * This size with its long side cut to [longToShort], keeping the short side and the orientation.
     *
     * Keeping the short side is what makes the result a stretch rather than a downscale: the panel still
     * lights the same pixels across, the game is handed fewer of them along the length, and the compositor
     * spreads that shorter frame over the whole screen. Rounded down to an even number, because odd
     * dimensions are a source of off-by-one scaling and encoder complaints for a half-pixel of accuracy.
     */
    fun stretchedTo(longToShort: Float): DisplaySize? {
        if (shortSide < MIN_SIDE || longToShort < 1f) return null
        val raw = (shortSide * longToShort).roundToInt()
        val long = raw - raw % 2
        if (long < shortSide) return null
        return if (isPortrait) DisplaySize(shortSide, long) else DisplaySize(long, shortSide)
    }

    /**
     * Why this size cannot be put on a panel of size [physical], or null when it can.
     *
     * The upper bound is not caution: a logical size larger than the panel is supersampling. The device
     * renders more pixels than it can show and the extra ones are thrown away in the downscale, which costs
     * frame rate — the opposite of what anyone opens this screen for — and widens nothing.
     */
    fun rejectionFor(physical: DisplaySize): String? = when {
        widthPixels <= 0 || heightPixels <= 0 -> "A display size needs two positive numbers."
        shortSide < MIN_SIDE ->
            "$label is too small to leave Android usable. The narrower side has to be at least " +
                "$MIN_SIDE pixels, and a display override outlives a reboot."
        aspect > MAX_ASPECT ->
            "$label is a $aspectLabel sliver rather than a screen shape. GameCore will not set a " +
                "display it cannot be undone from by hand."
        longSide > physical.longSide || shortSide > physical.shortSide ->
            "$label is larger than this display's ${physical.label}. GameCore stretches down to a smaller " +
                "logical size; a larger one makes the device render pixels the panel cannot show, which " +
                "costs frame rate and widens nothing."
        else -> null
    }

    private val isPortrait: Boolean get() = heightPixels >= widthPixels

    companion object {

        /** Below this the system UI stops laying out. Chosen to be survivable, not comfortable. */
        const val MIN_SIDE = 320

        /** Long:short past this is a sliver, not a screen. */
        const val MAX_ASPECT = 3f

        /** `Physical size: 1080x2400` and `Override size: 1080x1440` both parse through this. */
        fun parse(text: String): DisplaySize? {
            val parts = text.trim().split('x', 'X')
            if (parts.size != 2) return null
            val width = parts[0].trim().toIntOrNull() ?: return null
            val height = parts[1].trim().toIntOrNull() ?: return null
            if (width <= 0 || height <= 0) return null
            return DisplaySize(width, height)
        }
    }
}

/**
 * The screen shapes the Aspect Ratio tile and the profile editor offer.
 *
 * Each is a long:short ratio rather than a pair of pixel counts, because the only sizes worth offering are
 * derived from the panel in front of the user: "4:3" means 1080×1440 on a 1080×2400 phone and 1440×1920 on a
 * 1440×3200 one. [NATIVE] carries no ratio at all — it is in the list so the chips read as one row and so
 * there is always something to tap that undoes the others, and the controller turns it into `wm size reset`
 * rather than an override that happens to equal the panel, which would be a restore row recorded for nothing.
 */
enum class AspectPreset(val label: String, val longToShort: Float?, val summary: String) {

    NATIVE("Native", null, "The panel's own size. Nothing is stretched."),

    TALL_18_9("18:9", 2f, "A little shorter than a modern phone panel."),

    WIDE_16_9("16:9", 16f / 9f, "The shape a TV, a monitor and most PC games are."),

    CLASSIC_4_3("4:3", 4f / 3f, "The most stretched of these, and the widest models."),
    ;

    /**
     * What this shape means in pixels on [physical], or null when it is not worth offering there.
     *
     * A preset whose long side is not shorter than the panel's is dropped rather than shown and refused: on
     * a 16:9 tablet, "16:9" is the native size under another name and "18:9" would be an enlargement.
     */
    fun sizeFor(physical: DisplaySize): DisplaySize? {
        val ratio = longToShort ?: return physical
        val stretched = physical.stretchedTo(ratio) ?: return null
        return if (stretched.longSide < physical.longSide) stretched else null
    }

    companion object {

        /** The chips to show for this panel, in enum order, native first. */
        fun optionsFor(physical: DisplaySize): List<AspectChoice> =
            entries.mapNotNull { preset -> preset.sizeFor(physical)?.let { AspectChoice(preset, it) } }

        /** The preset [size] is on this panel, or null when it is a custom size. */
        fun of(size: DisplaySize, physical: DisplaySize): AspectPreset? =
            entries.firstOrNull { it.sizeFor(physical)?.matches(size) == true }
    }
}

/** One chip: a shape, and what that shape is in pixels on this device. */
data class AspectChoice(val preset: AspectPreset, val size: DisplaySize) {
    val label: String get() = preset.label
    val detail: String get() = size.label
}

/**
 * What `wm size` says: the panel, and the override sitting on top of it if there is one.
 *
 * Both halves are kept because neither can be derived from the other while an override is active. The panel
 * size is what the presets are computed from and what "Reset to native" goes back to; the override is what
 * the user is looking at. GameCore's own window reports only the second, which is why this comes from the
 * shell rather than from `DisplayReader`.
 */
data class DisplaySizeState(val physical: DisplaySize, val override: DisplaySize?) {

    val active: DisplaySize get() = override ?: physical

    /** True when something has set a size — GameCore, another app, or the user over adb. */
    val isOverridden: Boolean get() = override != null

    /** The shape now on screen, or null when a custom size is active. */
    val activePreset: AspectPreset? get() = AspectPreset.of(active, physical)

    val isCustom: Boolean get() = isOverridden && activePreset == null

    val options: List<AspectChoice> get() = AspectPreset.optionsFor(physical)
}

/**
 * The result of asking the display to change size.
 *
 * Shaped after [RefreshRateOutcome] and for the same reason: `wm size` prints nothing on success, exits zero
 * whether or not the display moved, and on some builds is quietly declined for the built-in panel. So there
 * is no case here meaning "the command did not fail". [Applied] and [Restored] are returned only after the
 * new size has been read back, [NotHonoured] is the specific case where the shell accepted the request and
 * the display stayed where it was, and [AppliedUnverified] exists for the read-back that could not be taken
 * — and says so, rather than being counted as success.
 */
sealed interface DisplaySizeOutcome {

    /** Confirmed: the display is running at [size], read back after the change. */
    data class Applied(val size: DisplaySize, val verifiedBy: String) : DisplaySizeOutcome

    /** Confirmed: the override is gone and the panel is back to its own [size]. */
    data class Restored(val size: DisplaySize, val verifiedBy: String) : DisplaySizeOutcome

    /** The command went through and the display did not move. */
    data class NotHonoured(val requested: DisplaySize, val actual: DisplaySize?) : DisplaySizeOutcome

    /**
     * Set, and not confirmable. Shown as "requested, not confirmed", never as success.
     *
     * [requested] is null when the request was `wm size reset`, which asks for a size the caller does not
     * know: the panel's own, whatever it turns out to be. Stating it as the native size is the honest
     * phrasing there, and inventing a number to fill the field would not be.
     */
    data class AppliedUnverified(val requested: DisplaySize?, val reason: String) : DisplaySizeOutcome

    /** The size itself is refused, before anything is written. [DisplaySize.rejectionFor] says why. */
    data class SizeUnsupported(val requested: DisplaySize, val detail: String) : DisplaySizeOutcome

    /** Needs Shizuku. There is no non-elevated path to a display size and no point pretending otherwise. */
    data class RequiresAccess(val detail: String) : DisplaySizeOutcome

    /** A genuine failure: the shell errored, the size could not be read, or the platform threw. */
    data class Failed(val detail: String) : DisplaySizeOutcome

    val isSuccess: Boolean get() = this is Applied || this is Restored

    /** What the user is told. Every case has its own sentence; none of them says "done". */
    val message: String
        get() = when (this) {
            is Applied -> "Display is running at ${size.label} — ${size.aspectLabel}."
            is Restored -> "Display is back to its native ${size.label}."
            is NotHonoured -> if (actual != null) {
                "This device accepted the size change and stayed at ${actual.label}. Some builds refuse a " +
                    "size override for the built-in screen."
            } else {
                "This device accepted the size change and did not report a new size."
            }
            is AppliedUnverified ->
                "Requested ${requested?.label ?: "the display's native size"}. The change could not be " +
                    "confirmed: $reason"
            is SizeUnsupported -> detail
            is RequiresAccess -> detail
            is Failed -> detail
        }
}

/** Past this the reduced ratio stops being something anyone reads as a screen shape. */
private const val MAX_RECOGNISABLE_SHORT = 32

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
