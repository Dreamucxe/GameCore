package com.gamecore.core.capability

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every codec the platform will hand an ordinary application, read once.
 *
 * `MediaCodecList.REGULAR_CODECS` is the list `MediaCodec.createDecoderByType` actually chooses from —
 * as opposed to `ALL_CODECS`, which includes entries the framework will not select — so it is the list
 * that answers "what can this device play". Everything below is read off `MediaCodecInfo` and its
 * capabilities objects; nothing is inferred from the device model, the chipset name or a lookup table.
 *
 * The hardware/software classification is the honest special case. `isHardwareAccelerated` and
 * `isSoftwareOnly` exist from API 29 and are authoritative there. Below 29 they do not exist, and the
 * common trick of guessing from the codec's name prefix is exactly the kind of fabrication this app
 * refuses, so a device on API 26–28 gets [CodecClassification.UNKNOWN] and the screen says the platform
 * does not report it.
 *
 * Profile and level names come from `MediaCodecInfo.CodecProfileLevel`'s own public constants, read
 * reflectively so the table cannot drift from the platform's. A value with no matching constant is
 * printed as a number rather than being dropped or guessed at.
 */
@Singleton
class CodecCapabilityReader @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var cached: Observed<List<CodecEntry>>? = null

    /** The codec table, built once. A second screen visit re-reads nothing. */
    suspend fun codecs(): Observed<List<CodecEntry>> =
        cached ?: withContext(io) { build().also { cached = it } }

    suspend fun refresh(): Observed<List<CodecEntry>> = withContext(io) { build().also { cached = it } }

    private fun build(): Observed<List<CodecEntry>> = Observed.catching(DataSource.MEDIA_CODEC_LIST) {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val entries = ArrayList<CodecEntry>(infos.size * 2)
        infos.forEach { info ->
            info.supportedTypes.forEach { mime ->
                entryFor(info, mime)?.let(entries::add)
            }
        }
        entries.sortedWith(
            compareBy<CodecEntry> { it.family }
                .thenBy { it.isEncoder }
                .thenBy { it.codecName },
        )
    }

    private fun entryFor(info: MediaCodecInfo, mime: String): CodecEntry? {
        val capabilities = try {
            info.getCapabilitiesForType(mime)
        } catch (error: Throwable) {
            // A codec that lists a type it then refuses to describe. Skipping it is the only honest
            // option: there is nothing to report and a row of blanks would imply there was.
            return null
        }
        val profiles = capabilities.profileLevels.orEmpty()
        val profileLabels = profiles.map { describeProfileLevel(mime, it) }.distinct().sorted()
        val supportsApi29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        return CodecEntry(
            codecName = info.name,
            canonicalName = if (supportsApi29) info.canonicalName else null,
            mimeType = mime,
            family = familyOf(mime),
            isEncoder = info.isEncoder,
            classification = when {
                !supportsApi29 -> CodecClassification.UNKNOWN
                info.isHardwareAccelerated -> CodecClassification.HARDWARE
                info.isSoftwareOnly -> CodecClassification.SOFTWARE
                else -> CodecClassification.UNKNOWN
            },
            isVendor = if (supportsApi29) info.isVendor else null,
            isAlias = if (supportsApi29) info.isAlias else null,
            maxInstances = runCatching { capabilities.maxSupportedInstances }.getOrNull(),
            profileLevels = profileLabels,
            hdrProfiles = profileLabels.filter { "HDR" in it.uppercase() },
            colorFormatCount = capabilities.colorFormats?.size ?: 0,
            video = capabilities.videoCapabilities?.let { summarise(it) },
            audio = capabilities.audioCapabilities?.let { summarise(it) },
            secureRequired = info.name.endsWith(".secure"),
        )
    }

    private fun summarise(video: MediaCodecInfo.VideoCapabilities): VideoCapabilitySummary {
        val maxWidth = runCatching { video.supportedWidths.upper }.getOrNull()
        val maxHeight = runCatching { video.supportedHeights.upper }.getOrNull()
        // The two uppers are not necessarily achievable together. Asking for the heights supported *at*
        // the widest width gives a pair the codec will actually accept, which is the figure worth showing.
        val heightAtMaxWidth = maxWidth?.let { width ->
            runCatching { video.getSupportedHeightsFor(width).upper }.getOrNull()
        }
        val frameRates = runCatching { video.supportedFrameRates }.getOrNull()
        val bitrates = runCatching { video.bitrateRange }.getOrNull()
        val performancePoints = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                video.supportedPerformancePoints
                    ?.map { it.toString() }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return VideoCapabilitySummary(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            heightAtMaxWidth = heightAtMaxWidth,
            widthAlignment = runCatching { video.widthAlignment }.getOrNull(),
            heightAlignment = runCatching { video.heightAlignment }.getOrNull(),
            minFrameRate = frameRates?.lower?.toInt(),
            maxFrameRate = frameRates?.upper?.toInt(),
            minBitrate = bitrates?.lower,
            maxBitrate = bitrates?.upper,
            performancePoints = performancePoints,
        )
    }

    private fun summarise(audio: MediaCodecInfo.AudioCapabilities): AudioCapabilitySummary {
        val bitrates = runCatching { audio.bitrateRange }.getOrNull()
        val sampleRates = runCatching { audio.supportedSampleRates?.toList() }.getOrNull().orEmpty()
        val sampleRateRanges = runCatching {
            audio.supportedSampleRateRanges?.map { "${it.lower}–${it.upper}" }?.toList()
        }.getOrNull().orEmpty()
        return AudioCapabilitySummary(
            maxChannels = runCatching { audio.maxInputChannelCount }.getOrNull(),
            sampleRates = sampleRates,
            sampleRateRanges = sampleRateRanges,
            minBitrate = bitrates?.lower,
            maxBitrate = bitrates?.upper,
        )
    }

    /**
     * "AVCProfileHigh · AVCLevel42", or the raw numbers when the platform defines no constant.
     *
     * Names are the platform's own field names rather than prettied-up versions of them, because those
     * are the strings that match what a codec's documentation, a bug report and `dumpsys media.player`
     * all use. Prettying them would make this report harder to compare against anything else.
     */
    private fun describeProfileLevel(mime: String, entry: MediaCodecInfo.CodecProfileLevel): String {
        val prefix = constantPrefix(mime)
        val profile = prefix?.let { profileConstants(it)[entry.profile] } ?: "Profile ${entry.profile}"
        val level = prefix?.let { levelConstants(it)[entry.level] }
        return if (level != null) "$profile · $level" else "$profile · Level ${entry.level}"
    }

    /** `video/avc` → `AVC`, and so on. Null means the platform defines no constants for this type. */
    private fun constantPrefix(mime: String): String? = when (mime.lowercase()) {
        "video/avc" -> "AVC"
        "video/hevc" -> "HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG4"
        "video/mpeg2" -> "MPEG2"
        "video/3gpp" -> "H263"
        "video/dolby-vision" -> "DolbyVision"
        "audio/mp4a-latm" -> "AACObject"
        else -> null
    }

    /** `video/avc` → `H.264 / AVC`: the name a person searching for a codec would type. */
    private fun familyOf(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        "video/3gpp" -> "H.263"
        "video/dolby-vision" -> "Dolby Vision"
        "video/x-vnd.on2.vp6" -> "VP6"
        "video/raw" -> "Raw video"
        else -> when {
            mime.startsWith("video/") -> mime.removePrefix("video/").uppercase()
            mime.startsWith("audio/") -> mime.removePrefix("audio/").uppercase()
            mime.startsWith("image/") -> mime.removePrefix("image/").uppercase()
            else -> mime
        }
    }

    /**
     * The platform's own profile constants for one family, read off the class.
     *
     * Reflection over a public API class's public static fields — not a hidden API, and not a private
     * one either. The alternative is a hand-copied table of roughly a hundred integers that would be
     * wrong the first time a platform release adds a profile, which for a screen whose whole purpose is
     * accuracy is the worse trade.
     */
    private fun profileConstants(prefix: String): Map<Int, String> =
        constantCache.getOrPut("profile:$prefix") {
            readConstants { name ->
                name.startsWith(prefix) && (name.contains("Profile") || prefix == "AACObject")
            }
        }

    private fun levelConstants(prefix: String): Map<Int, String> =
        constantCache.getOrPut("level:$prefix") {
            readConstants { name ->
                name.startsWith(prefix) && name.contains("Level") && !name.contains("Profile")
            }
        }

    private fun readConstants(accept: (String) -> Boolean): Map<Int, String> = try {
        MediaCodecInfo.CodecProfileLevel::class.java.fields
            .asSequence()
            .filter { it.type == Int::class.javaPrimitiveType && accept(it.name) }
            .mapNotNull { field -> runCatching { field.getInt(null) to field.name }.getOrNull() }
            // Two constants can share a value across families; first one wins, and the filter above has
            // already narrowed to a single family, so a collision here is a genuine platform alias.
            .distinctBy { it.first }
            .toMap()
    } catch (error: Throwable) {
        emptyMap()
    }

    private val constantCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, String>>()
}

/** Whether the platform told us a codec runs on hardware. Never guessed from its name. */
enum class CodecClassification(val label: String) {
    HARDWARE("Hardware"),
    SOFTWARE("Software"),
    UNKNOWN("Not reported"),
}

/** One codec's support for one MIME type — the row the scanner lists and filters. */
data class CodecEntry(
    val codecName: String,
    val canonicalName: String?,
    val mimeType: String,
    val family: String,
    val isEncoder: Boolean,
    val classification: CodecClassification,
    val isVendor: Boolean?,
    val isAlias: Boolean?,
    val maxInstances: Int?,
    val profileLevels: List<String>,
    val hdrProfiles: List<String>,
    val colorFormatCount: Int,
    val video: VideoCapabilitySummary?,
    val audio: AudioCapabilitySummary?,
    val secureRequired: Boolean,
) {
    val direction: String get() = if (isEncoder) "Encoder" else "Decoder"

    val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)

    val isAudio: Boolean get() = mimeType.startsWith("audio/", ignoreCase = true)

    val supportsHdr: Boolean get() = hdrProfiles.isNotEmpty()

    /** Everything a text filter should look at: name, MIME, family and the friendly aliases. */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return needle in codecName.lowercase() ||
            needle in mimeType.lowercase() ||
            needle in family.lowercase() ||
            canonicalName?.lowercase()?.contains(needle) == true ||
            profileLevels.any { needle in it.lowercase() }
    }
}

/** `VideoCapabilities`, with every field nullable because any of them can be refused. */
data class VideoCapabilitySummary(
    val maxWidth: Int?,
    val maxHeight: Int?,
    val heightAtMaxWidth: Int?,
    val widthAlignment: Int?,
    val heightAlignment: Int?,
    val minFrameRate: Int?,
    val maxFrameRate: Int?,
    val minBitrate: Int?,
    val maxBitrate: Int?,
    val performancePoints: List<String>,
) {
    /** "3840 × 2160", using the height the codec actually supports at its widest width. */
    val achievableResolution: String?
        get() {
            val width = maxWidth ?: return null
            val height = heightAtMaxWidth ?: maxHeight ?: return null
            return "$width × $height"
        }
}

/** `AudioCapabilities`, same rule. */
data class AudioCapabilitySummary(
    val maxChannels: Int?,
    val sampleRates: List<Int>,
    val sampleRateRanges: List<String>,
    val minBitrate: Int?,
    val maxBitrate: Int?,
)
