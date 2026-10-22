package com.gamecore.core.plugin.icon

import com.gamecore.core.common.TextSanitizer
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a plugin's `icon.json` into a trusted [PluginIconSpec].
 *
 * The same contract as every other codec in the platform: untrusted JSON in, a value the rest of the
 * app may trust out, or a typed rejection. It is deliberately tiny, because the capability is
 * deliberately tiny — a plugin may name one of the icons the host ships, and may attach one image.
 * There is no field here that says *what* to draw, which is the point: a launcher icon is not a
 * canvas a package gets to paint on.
 *
 * Strictness follows [PluginThemeCodec]. An unknown key rejects the whole file rather than being
 * ignored, so a future field never loads with its meaning silently dropped. A slot that does not
 * resolve against [AppIconSlot] is a rejection, never a fallback to [AppIconSlot.DEFAULT] — a plugin
 * that asked for an icon and got the default instead should be told, not humoured.
 *
 * Pure Kotlin + org.json, no Android, so the rules are unit-tested on the JVM.
 */
object PluginIconCodec {

    enum class Reason {
        EMPTY, MALFORMED, MISSING_FIELD, UNKNOWN_KEY, BAD_SLOT, BAD_ARTWORK, BAD_LABEL,
    }

    sealed interface Result {
        data class Parsed(val spec: PluginIconSpec) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    /** The three keys `icon.json` may carry. */
    private val KNOWN_KEYS = setOf("slot", "artwork", "label")

    /**
     * Where artwork may live: directly under `assets/`, one file, a known image extension.
     *
     * A character class rather than "anything but a slash" because this string is later used to look
     * an archive entry up. The pattern is anchored and admits no `.` or `/` beyond the one separator,
     * so `..`, an absolute path and a nested directory are all simply not paths it can match.
     */
    private val ARTWORK_PATH = Regex("^assets/[A-Za-z0-9_-][A-Za-z0-9._-]*\\.(png|webp)$")

    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)

        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        root.keys().asSequence().firstOrNull { it !in KNOWN_KEYS }?.let {
            return Result.Rejected(Reason.UNKNOWN_KEY, "unknown key \"$it\"")
        }

        if (!root.has("slot")) return Result.Rejected(Reason.MISSING_FIELD, "missing \"slot\"")

        val rawSlot = root.optString("slot")
        val slot = AppIconSlot.fromId(rawSlot)
            ?: return Result.Rejected(Reason.BAD_SLOT, "\"$rawSlot\" is not an icon this build ships")

        val artwork = if (root.has("artwork")) {
            val path = root.optString("artwork").trim()
            when {
                path.length > PluginIconSpec.MAX_ARTWORK_PATH ->
                    return Result.Rejected(Reason.BAD_ARTWORK, "path is longer than ${PluginIconSpec.MAX_ARTWORK_PATH}")
                !ARTWORK_PATH.matches(path) ->
                    return Result.Rejected(Reason.BAD_ARTWORK, "\"$path\" is not assets/<name>.png|webp")
                else -> path
            }
        } else {
            null
        }

        val label = if (root.has("label")) {
            val sanitised = TextSanitizer.sanitizeName(root.optString("label"), PluginIconSpec.MAX_LABEL)
            // An explicit label that sanitises to nothing is refused rather than dropped: the author
            // wrote a caption and it cannot be shown, which is worth saying out loud at load time.
            if (sanitised.isEmpty()) return Result.Rejected(Reason.BAD_LABEL, "label is empty after sanitising")
            sanitised
        } else {
            null
        }

        return Result.Parsed(PluginIconSpec(slot = slot, artworkPath = artwork, label = label))
    }
}
