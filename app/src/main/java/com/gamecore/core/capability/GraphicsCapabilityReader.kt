package com.gamecore.core.capability

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.view.Display
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device's graphics stack actually reports, and nothing it does not.
 *
 * The OpenGL ES strings are read the only way an app can read them: by creating a real context. EGL is
 * initialised, a 1×1 pbuffer is made current, `glGetString` is called, and the whole thing is torn down
 * again — a few milliseconds, once, cached for the life of the process. There is no other route to the
 * renderer and vendor strings, and there is no route at all to the ones this class reports as restricted.
 *
 * Vulkan is the honest gap. Android exposes Vulkan to applications through the NDK; there is no Java or
 * Kotlin binding for `vkEnumerateInstanceExtensionProperties` or `vkGetPhysicalDeviceProperties`, and
 * GameCore ships no native code. What the platform *does* expose to Java is the three system features
 * below, and that is exactly what is reported: whether Vulkan is present, at what hardware level, and
 * which API version the driver claims. Device properties and the extension list are reported as
 * restricted with the reason, because inventing them would be the one thing this app must never do.
 */
@Singleton
class GraphicsCapabilityReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var cached: GraphicsReport? = null

    /**
     * The report, built once and kept.
     *
     * Graphics capability does not change while the process is alive — the driver is the driver — so a
     * screen that is reopened reads the cache rather than standing up another EGL context. [refresh]
     * exists for the explicit "rescan" action and nothing else.
     */
    suspend fun report(): GraphicsReport =
        cached ?: withContext(io) { build().also { cached = it } }

    suspend fun refresh(): GraphicsReport = withContext(io) { build().also { cached = it } }

    private fun build(): GraphicsReport {
        val gl = readGlStrings()
        return GraphicsReport(
            renderer = gl.renderer,
            vendor = gl.vendor,
            glVersion = gl.version,
            shadingLanguageVersion = gl.shadingLanguage,
            contextClientVersion = gl.contextVersion,
            extensions = gl.extensions,
            compressedTextureFormatCount = gl.compressedFormats,
            textureCompressionFamilies = gl.textureFamilies,
            limits = gl.limits,
            eglVendor = gl.eglVendor,
            eglVersion = gl.eglVersion,
            eglClientApis = gl.eglClientApis,
            eglExtensions = gl.eglExtensions,
            declaredGlesVersion = declaredGlesVersion(),
            extensionPack = feature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK, "OpenGL ES 3.1 AEP"),
            vulkan = readVulkan(),
            display = readDisplay(),
            lowRamDevice = lowRamDevice(),
        )
    }

    // ------------------------------------------------------------------------------------- OpenGL

    /**
     * Stands up an offscreen context, reads every string and limit, tears it down.
     *
     * Every step can fail on a device with no usable config — a headless build, an emulator without a
     * host GPU — and each failure is carried into the fields as an explanation rather than as a zero.
     * The teardown runs from a `finally` so a failure halfway through does not leak a display.
     */
    private fun readGlStrings(): GlReadings {
        var display: EGLDisplay? = null
        var surface: EGLSurface? = null
        var glContext: EGLContext? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return GlReadings.unavailable("No default EGL display on this device.")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return GlReadings.unavailable("eglInitialize failed (0x${eglError()}).")
            }

            // ES 3 first, because its strings name a superset of what an ES 2 context reports. A device
            // that refuses the ES 3 renderable bit falls back rather than reporting nothing.
            var clientVersion = 3
            var config = chooseConfig(display, EGLExt.EGL_OPENGL_ES3_BIT_KHR)
            if (config == null) {
                clientVersion = 2
                config = chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT)
            }
            if (config == null) {
                return GlReadings.unavailable("No EGL config supports an offscreen pbuffer here.")
            }

            glContext = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE),
                0,
            )
            if (glContext == null || glContext == EGL14.EGL_NO_CONTEXT) {
                return GlReadings.unavailable("eglCreateContext failed (0x${eglError()}).")
            }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                return GlReadings.unavailable("eglCreatePbufferSurface failed (0x${eglError()}).")
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, glContext)) {
                return GlReadings.unavailable("eglMakeCurrent failed (0x${eglError()}).")
            }

            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                .orEmpty()
                .split(' ')
                .filter { it.isNotBlank() }
                .sorted()
            return GlReadings(
                renderer = glString(GLES20.GL_RENDERER, "GL_RENDERER"),
                vendor = glString(GLES20.GL_VENDOR, "GL_VENDOR"),
                version = glString(GLES20.GL_VERSION, "GL_VERSION"),
                shadingLanguage = glString(GLES20.GL_SHADING_LANGUAGE_VERSION, "GL_SHADING_LANGUAGE_VERSION"),
                contextVersion = Observed.of("OpenGL ES $clientVersion.x context", DataSource.EGL),
                extensions = Observed.of(extensions, DataSource.EGL),
                compressedFormats = glInt(GL_NUM_COMPRESSED_TEXTURE_FORMATS),
                textureFamilies = Observed.of(textureFamilies(extensions), DataSource.EGL, Precision.EXACT),
                limits = Observed.of(readLimits(), DataSource.EGL),
                eglVendor = eglString(display, EGL14.EGL_VENDOR),
                eglVersion = eglString(display, EGL14.EGL_VERSION),
                eglClientApis = eglString(display, EGL14.EGL_CLIENT_APIS),
                eglExtensions = eglString(display, EGL14.EGL_EXTENSIONS).let { observed ->
                    when (observed) {
                        is Observed.Value -> Observed.of(
                            observed.value.split(' ').filter { it.isNotBlank() }.sorted(),
                            DataSource.EGL,
                        )

                        is Observed.Restricted -> observed
                        is Observed.Failed -> observed
                    }
                },
            )
        } catch (error: Throwable) {
            // An OEM driver that throws from eglInitialize is rare and real. The report still builds.
            return GlReadings.unavailable(error.javaClass.simpleName)
        } finally {
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface)
                }
                if (glContext != null && glContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, glContext)
                }
                EGL14.eglTerminate(display)
            }
            EGL14.eglReleaseThread()
        }
    }

    private fun chooseConfig(display: EGLDisplay, renderableType: Int): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (chosen && count[0] > 0) configs[0] else null
    }

    private fun glString(name: Int, label: String): Observed<String> {
        val value = GLES20.glGetString(name)
        return if (value.isNullOrBlank()) {
            Observed.notPresent("$label is empty on this driver.")
        } else {
            Observed.of(value, DataSource.EGL)
        }
    }

    private fun glInt(name: Int): Observed<Int> {
        val out = IntArray(1)
        GLES20.glGetIntegerv(name, out, 0)
        return if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
            Observed.of(out[0], DataSource.EGL)
        } else {
            Observed.notPresent("This driver does not report the value.")
        }
    }

    /** The limits that mean something to somebody deciding what a device can render. */
    private fun readLimits(): List<GraphicsLimit> = listOf(
        GraphicsLimit("Max texture size", GLES20.GL_MAX_TEXTURE_SIZE, "px"),
        GraphicsLimit("Max cubemap size", GLES20.GL_MAX_CUBE_MAP_TEXTURE_SIZE, "px"),
        GraphicsLimit("Max renderbuffer size", GLES20.GL_MAX_RENDERBUFFER_SIZE, "px"),
        GraphicsLimit("Max texture units", GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, ""),
        GraphicsLimit("Max vertex attributes", GLES20.GL_MAX_VERTEX_ATTRIBS, ""),
        GraphicsLimit("Max varying vectors", GLES20.GL_MAX_VARYING_VECTORS, ""),
        GraphicsLimit("Max vertex uniform vectors", GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS, ""),
        GraphicsLimit("Max fragment uniform vectors", GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS, ""),
    ).map { limit ->
        val out = IntArray(1)
        GLES20.glGetIntegerv(limit.token, out, 0)
        if (GLES20.glGetError() == GLES20.GL_NO_ERROR) limit.copy(value = out[0]) else limit
    }

    /**
     * Which compressed texture families the driver advertises.
     *
     * Read from the extension string rather than from `GL_COMPRESSED_TEXTURE_FORMATS`, whose entries are
     * bare enum values that would have to be mapped through a table this app would then have to keep
     * correct. The extension names are the driver's own words; the count beside them is the driver's own
     * count. ETC2/EAC is listed as core on an ES 3 context because the specification makes it mandatory
     * there, and that is stated rather than inferred from an extension that will not be present.
     */
    private fun textureFamilies(extensions: List<String>): List<String> {
        val found = linkedSetOf<String>()
        extensions.forEach { extension ->
            val lower = extension.lowercase()
            when {
                "etc1" in lower -> found += "ETC1"
                "astc" in lower -> found += "ASTC"
                "s3tc" in lower || "dxt" in lower -> found += "S3TC / DXT"
                "pvrtc" in lower -> found += "PVRTC"
                "atc" in lower && "compress" in lower -> found += "ATC"
                "bptc" in lower -> found += "BPTC"
                "rgtc" in lower -> found += "RGTC"
                "etc2" in lower || "eac" in lower -> found += "ETC2 / EAC"
            }
        }
        return found.toList()
    }

    private fun eglString(display: EGLDisplay, name: Int): Observed<String> {
        val value = EGL14.eglQueryString(display, name)
        return if (value.isNullOrBlank()) {
            Observed.notPresent("EGL returns nothing for this query.")
        } else {
            Observed.of(value, DataSource.EGL)
        }
    }

    private fun eglError(): String = Integer.toHexString(EGL14.eglGetError())

    // ------------------------------------------------------------------------------------- Vulkan

    /**
     * Vulkan as Java can see it: three system features, and an honest account of the rest.
     *
     * `FeatureInfo.version` for `FEATURE_VULKAN_HARDWARE_VERSION` carries the driver's
     * `VkPhysicalDeviceProperties::apiVersion`, packed the way Vulkan packs it, so the decode below is
     * the specification's own field layout rather than a guess. `FEATURE_VULKAN_HARDWARE_LEVEL` is
     * Android's own compatibility tier, 0 through 2.
     */
    private fun readVulkan(): VulkanReport {
        val features = try {
            context.packageManager.systemAvailableFeatures
        } catch (error: Throwable) {
            emptyArray<android.content.pm.FeatureInfo>()
        }
        val versionFeature = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val levelFeature = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
        val computeFeature = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE }
        val supported = versionFeature != null || levelFeature != null

        return VulkanReport(
            supported = supported,
            apiVersion = versionFeature
                ?.let { Observed.of(decodeVulkanVersion(it.version), DataSource.PACKAGE_MANAGER) }
                ?: Observed.notPresent("This device declares no Vulkan hardware version."),
            packedVersion = versionFeature
                ?.let { Observed.of(it.version, DataSource.PACKAGE_MANAGER) }
                ?: Observed.notPresent("This device declares no Vulkan hardware version."),
            hardwareLevel = levelFeature
                ?.let { Observed.of(vulkanLevelLabel(it.version), DataSource.PACKAGE_MANAGER) }
                ?: Observed.notPresent("This device declares no Vulkan hardware level."),
            computeLevel = computeFeature
                ?.let { Observed.of(vulkanComputeLabel(it.version), DataSource.PACKAGE_MANAGER) }
                ?: Observed.notPresent("This device declares no Vulkan compute level."),
            deviceProperties = Observed.platform(NO_JAVA_VULKAN),
            deviceExtensions = Observed.platform(NO_JAVA_VULKAN),
        )
    }

    /** Vulkan's own packing: 7 bits major, 10 bits minor, 12 bits patch. */
    private fun decodeVulkanVersion(packed: Int): String {
        val major = (packed shr 22) and 0x7F
        val minor = (packed shr 12) and 0x3FF
        val patch = packed and 0xFFF
        return "$major.$minor.$patch"
    }

    private fun vulkanLevelLabel(level: Int): String = when (level) {
        0 -> "Level 0 — baseline"
        1 -> "Level 1 — adds sampler and image format guarantees"
        2 -> "Level 2 — adds tessellation and geometry shading guarantees"
        else -> "Level $level"
    }

    private fun vulkanComputeLabel(level: Int): String = when (level) {
        0 -> "Level 0 — baseline compute"
        else -> "Level $level"
    }

    // ------------------------------------------------------------------------------------ platform

    private fun declaredGlesVersion(): Observed<String> = Observed.catching(DataSource.ACTIVITY_MANAGER) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.deviceConfigurationInfo.glEsVersion
    }

    private fun feature(name: String, label: String): Observed<String> = try {
        if (context.packageManager.hasSystemFeature(name)) {
            Observed.of("$label: present", DataSource.PACKAGE_MANAGER)
        } else {
            Observed.notPresent("$label is not declared by this device.")
        }
    } catch (error: Throwable) {
        Observed.Failed(error.javaClass.simpleName)
    }

    private fun lowRamDevice(): Observed<Boolean> = Observed.catching(DataSource.ACTIVITY_MANAGER) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    /**
     * What the default display supports: modes, HDR types, wide colour.
     *
     * Part of the capability report because a codec that decodes HDR10 into a panel that cannot show it
     * is a fact worth having in the same place as the codec list.
     */
    private fun readDisplay(): DisplayCapability {
        val display = try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            manager.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (error: Throwable) {
            null
        } ?: return DisplayCapability(
            modes = Observed.Failed("No default display"),
            hdrTypes = Observed.Failed("No default display"),
            wideColorGamut = Observed.Failed("No default display"),
        )

        val modes = Observed.catching(DataSource.DISPLAY_MANAGER) {
            display.supportedModes
                .sortedWith(compareByDescending<Display.Mode> { it.physicalWidth * it.physicalHeight }
                    .thenByDescending { it.refreshRate })
                .map { mode ->
                    "${mode.physicalWidth} × ${mode.physicalHeight} @ " +
                        "${String.format("%.1f", mode.refreshRate)} Hz"
                }
        }
        val hdr = Observed.catching(DataSource.DISPLAY_MANAGER) {
            @Suppress("DEPRECATION")
            val capabilities = display.hdrCapabilities
            capabilities?.supportedHdrTypes?.map { hdrTypeLabel(it) }.orEmpty()
        }
        val wide = Observed.catching(DataSource.DISPLAY_MANAGER) { display.isWideColorGamut }
        return DisplayCapability(modes = modes, hdrTypes = hdr, wideColorGamut = wide)
    }

    private fun hdrTypeLabel(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "Type $type"
    }

    private companion object {
        /** `GL_NUM_COMPRESSED_TEXTURE_FORMATS`; not exposed as a constant on `GLES20`. */
        const val GL_NUM_COMPRESSED_TEXTURE_FORMATS = 0x86A2

        const val NO_JAVA_VULKAN =
            "Android exposes Vulkan to native code only. There is no Java or Kotlin binding for " +
                "vkGetPhysicalDeviceProperties or vkEnumerateDeviceExtensionProperties, and GameCore " +
                "ships no native library, so this cannot be read rather than being unknown."
    }
}

/** Everything read from one EGL context, or the explanation for why none of it was. */
private data class GlReadings(
    val renderer: Observed<String>,
    val vendor: Observed<String>,
    val version: Observed<String>,
    val shadingLanguage: Observed<String>,
    val contextVersion: Observed<String>,
    val extensions: Observed<List<String>>,
    val compressedFormats: Observed<Int>,
    val textureFamilies: Observed<List<String>>,
    val limits: Observed<List<GraphicsLimit>>,
    val eglVendor: Observed<String>,
    val eglVersion: Observed<String>,
    val eglClientApis: Observed<String>,
    val eglExtensions: Observed<List<String>>,
) {
    companion object {
        fun unavailable(detail: String): GlReadings {
            val reason = Observed.Failed(detail)
            return GlReadings(
                renderer = reason,
                vendor = reason,
                version = reason,
                shadingLanguage = reason,
                contextVersion = reason,
                extensions = reason,
                compressedFormats = reason,
                textureFamilies = reason,
                limits = reason,
                eglVendor = reason,
                eglVersion = reason,
                eglClientApis = reason,
                eglExtensions = reason,
            )
        }
    }
}

/** One `glGetIntegerv` limit. [value] is null when the driver refused the query. */
data class GraphicsLimit(
    val label: String,
    val token: Int,
    val unit: String,
    val value: Int? = null,
)

/** Vulkan, as far as a Java application is permitted to see it. */
data class VulkanReport(
    val supported: Boolean,
    val apiVersion: Observed<String>,
    val packedVersion: Observed<Int>,
    val hardwareLevel: Observed<String>,
    val computeLevel: Observed<String>,
    val deviceProperties: Observed<String>,
    val deviceExtensions: Observed<List<String>>,
)

/** The default display's own capability list. */
data class DisplayCapability(
    val modes: Observed<List<String>>,
    val hdrTypes: Observed<List<String>>,
    val wideColorGamut: Observed<Boolean>,
)

/** The whole graphics side of the capability report. */
data class GraphicsReport(
    val renderer: Observed<String>,
    val vendor: Observed<String>,
    val glVersion: Observed<String>,
    val shadingLanguageVersion: Observed<String>,
    val contextClientVersion: Observed<String>,
    val extensions: Observed<List<String>>,
    val compressedTextureFormatCount: Observed<Int>,
    val textureCompressionFamilies: Observed<List<String>>,
    val limits: Observed<List<GraphicsLimit>>,
    val eglVendor: Observed<String>,
    val eglVersion: Observed<String>,
    val eglClientApis: Observed<String>,
    val eglExtensions: Observed<List<String>>,
    val declaredGlesVersion: Observed<String>,
    val extensionPack: Observed<String>,
    val vulkan: VulkanReport,
    val display: DisplayCapability,
    val lowRamDevice: Observed<Boolean>,
) {
    /** Android release and API level, so an exported report says which platform produced it. */
    val androidSummary: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    val deviceSummary: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
}
