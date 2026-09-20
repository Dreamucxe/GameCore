package com.gamecore.core.common

/**
 * The type that keeps GameCore honest.
 *
 * Almost nothing this app wants to show is guaranteed to exist. Whether a CPU
 * percentage, a battery temperature, a thermal status, a supported refresh rate or
 * a frame rate can be read at all depends on the API level, the OEM's kernel
 * configuration, SELinux policy, which special accesses the user has granted and
 * whether Shizuku happens to be running. The specification is absolute that a
 * value which could not be read must never be invented, and that the app must say
 * "Not available on this device" instead of estimating.
 *
 * So no reading leaves the system layer as a bare number. Every one travels
 * wrapped here, carrying either the value together with where it came from, or the
 * reason it is absent. A screen cannot render a plausible-looking 0% by accident,
 * because [Restricted] and [Failed] hold nothing to render — the compiler forces
 * the missing case to be handled at every call site.
 *
 * Ported from ProcessLens, where the same discipline was applied to process
 * inspection. The shape is unchanged on purpose: it is proven, and the two apps
 * mean exactly the same thing by "we could not read this".
 */
sealed interface Observed<out T> {

    /** A real reading. [source] and [precision] are surfaced in the UI. */
    data class Value<out T>(
        val value: T,
        val source: DataSource,
        val precision: Precision = Precision.EXACT,
    ) : Observed<T>

    /**
     * Android, the hardware, or the current permission state does not expose this.
     *
     * Not an error: it is documented, expected behaviour. The UI explains it and
     * offers the fix where one exists ([unlockedBy] names the access level that
     * would reveal it) rather than retrying or showing a spinner forever.
     */
    data class Restricted(
        val reason: RestrictionReason,
        /** The lowest access level that *would* expose this, or null if nothing does. */
        val unlockedBy: AccessLevel? = null,
        val detail: String = "",
    ) : Observed<Nothing>

    /**
     * A read that should have worked and did not — an I/O failure, a vendor kernel
     * missing a file it ought to have, a shell returning unparseable output.
     * Distinct from [Restricted] because it is a genuine fault, and the difference
     * is what the user needs in order to know whether to act.
     */
    data class Failed(
        val detail: String,
        val cause: String? = null,
    ) : Observed<Nothing>

    companion object {
        fun <T> of(value: T, source: DataSource, precision: Precision = Precision.EXACT): Observed<T> =
            Value(value, source, precision)

        /** Withheld by the platform on this API level or by this device's policy. */
        fun platform(detail: String, unlockedBy: AccessLevel? = null): Observed<Nothing> =
            Restricted(RestrictionReason.PLATFORM_RESTRICTED, unlockedBy, detail)

        /** A runtime or special access the user has not granted yet. */
        fun needsPermission(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.PERMISSION_REQUIRED, null, detail)

        /** The hardware or kernel does not report this at all. */
        fun notPresent(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.NOT_PRESENT_ON_DEVICE, null, detail)

        /** Readable, but only with an elevated shell. */
        fun needsElevation(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.REQUIRES_ELEVATED_ACCESS, AccessLevel.SHIZUKU, detail)

        /** Not supported until a later Android version than this device runs. */
        fun needsNewerApi(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL, null, detail)

        /**
         * A rate that needs two samples and has only had one.
         *
         * Kept separate from every other reason because it is the one absence that
         * resolves itself: CPU utilisation, network throughput and battery drain are
         * differences between readings, not instantaneous values, so the first
         * sample can only honestly say "not yet". A zero here would be
         * indistinguishable from a genuinely idle CPU.
         */
        fun awaitingSample(detail: String = "Waiting for a second sample."): Observed<Nothing> =
            Restricted(RestrictionReason.AWAITING_SECOND_SAMPLE, null, detail)

        /** The user switched this sampling off. Their choice, not a device limit. */
        fun samplingDisabled(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.SAMPLING_DISABLED, null, detail)

        /**
         * Runs [block], mapping the two failure modes apart. A [SecurityException]
         * is the sandbox refusing, which Shizuku may be able to lift; anything else
         * is a fault.
         */
        inline fun <T> catching(
            source: DataSource,
            precision: Precision = Precision.EXACT,
            block: () -> T,
        ): Observed<T> = try {
            val v = block()
            if (v == null) Failed("Read returned no data") else Value(v, source, precision)
        } catch (se: SecurityException) {
            Restricted(
                RestrictionReason.PLATFORM_RESTRICTED,
                AccessLevel.SHIZUKU,
                se.message ?: "SecurityException",
            )
        } catch (t: Throwable) {
            Failed(t.message ?: t::class.java.simpleName, t::class.java.name)
        }
    }
}

/** The value if this is a real reading, otherwise null. Never invents a default. */
val <T> Observed<T>.valueOrNull: T? get() = (this as? Observed.Value)?.value

val Observed<*>.isAvailable: Boolean get() = this is Observed.Value

/**
 * True while a rate is still waiting for its second sample. The UI shows a
 * placeholder for this rather than the "not available" explanation, because the
 * figure is about to arrive.
 */
val Observed<*>.isAwaitingSample: Boolean
    get() = this is Observed.Restricted && reason == RestrictionReason.AWAITING_SECOND_SAMPLE

inline fun <T, R> Observed<T>.map(transform: (T) -> R): Observed<R> = when (this) {
    is Observed.Value -> Observed.Value(transform(value), source, precision)
    is Observed.Restricted -> this
    is Observed.Failed -> this
}

/**
 * Chains a second observation onto a successful first, keeping the failure of
 * whichever step failed. Used where one reading is the input to another — a
 * refresh rate read from a display mode that itself may not be exposed.
 */
inline fun <T, R> Observed<T>.flatMap(transform: (T) -> Observed<R>): Observed<R> = when (this) {
    is Observed.Value -> transform(value)
    is Observed.Restricted -> this
    is Observed.Failed -> this
}

/**
 * The exact wording shown wherever a value is missing.
 *
 * Centralised so the explanation for a given absence is identical on the
 * dashboard, in the HUD editor, in a session report and in the capability list.
 * Returns null for a real reading, which makes `unavailabilityText() ?: render()`
 * the natural shape at a call site.
 */
fun Observed<*>.unavailabilityText(): String? = when (this) {
    is Observed.Value -> null
    is Observed.Failed -> "Could not be read on this device."
    is Observed.Restricted -> when (reason) {
        RestrictionReason.PLATFORM_RESTRICTED ->
            "Android does not expose this to ordinary apps on this device."
        RestrictionReason.PERMISSION_REQUIRED ->
            "A permission is needed before this can be read."
        RestrictionReason.NOT_PRESENT_ON_DEVICE ->
            "Not available on this device."
        RestrictionReason.REQUIRES_ELEVATED_ACCESS ->
            "Requires Shizuku."
        RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL ->
            "This Android version does not provide this."
        RestrictionReason.AWAITING_SECOND_SAMPLE ->
            "Measuring…"
        RestrictionReason.SAMPLING_DISABLED ->
            "Switched off in GameCore's settings."
    }
}

/** A short form for the pill and the HUD, where there is room for a word, not a sentence. */
fun Observed<*>.shortUnavailabilityText(): String? = when (this) {
    is Observed.Value -> null
    is Observed.Failed -> "error"
    is Observed.Restricted -> when (reason) {
        RestrictionReason.AWAITING_SECOND_SAMPLE -> "…"
        RestrictionReason.REQUIRES_ELEVATED_ACCESS -> "shizuku"
        RestrictionReason.PERMISSION_REQUIRED -> "perm"
        RestrictionReason.SAMPLING_DISABLED -> "off"
        else -> "n/a"
    }
}

enum class RestrictionReason {
    /** Deliberately withheld by the sandbox (SELinux, hidepid, API gating). */
    PLATFORM_RESTRICTED,
    PERMISSION_REQUIRED,

    /** Hardware or kernel does not report it. No permission can change this. */
    NOT_PRESENT_ON_DEVICE,
    REQUIRES_ELEVATED_ACCESS,
    NOT_SUPPORTED_ON_API_LEVEL,

    /** A rate with only one sample so far. Resolves on the next tick. */
    AWAITING_SECOND_SAMPLE,

    /**
     * GameCore could read this but was told not to. Kept apart from the reasons
     * above deliberately: those describe the device, this describes the user's own
     * choice, and conflating them would blame Android for a limit the user set and
     * can lift in one tap.
     */
    SAMPLING_DISABLED,
}

/**
 * Where a reading physically came from, so a user can judge how much to trust it
 * and a saved session report stays interpretable later.
 */
enum class DataSource(val label: String, val access: AccessLevel) {
    PROC_FS("/proc", AccessLevel.NORMAL),
    SYS_FS("/sys", AccessLevel.NORMAL),
    ACTIVITY_MANAGER("ActivityManager", AccessLevel.NORMAL),
    BATTERY_MANAGER("BatteryManager", AccessLevel.NORMAL),
    BATTERY_BROADCAST("ACTION_BATTERY_CHANGED", AccessLevel.NORMAL),
    POWER_MANAGER("PowerManager", AccessLevel.NORMAL),
    HARDWARE_PROPERTIES("HardwarePropertiesManager", AccessLevel.NORMAL),
    DISPLAY_MANAGER("DisplayManager", AccessLevel.NORMAL),
    WINDOW_MANAGER("WindowManager", AccessLevel.NORMAL),
    FRAME_METRICS("Choreographer", AccessLevel.NORMAL),
    CONNECTIVITY("ConnectivityManager", AccessLevel.NORMAL),
    TRAFFIC_STATS("TrafficStats", AccessLevel.NORMAL),
    SOCKET_PROBE("TCP handshake", AccessLevel.NORMAL),
    STORAGE_MANAGER("StatFs", AccessLevel.NORMAL),
    STORAGE_STATS("StorageStatsManager", AccessLevel.NORMAL),
    USAGE_STATS("UsageStatsManager", AccessLevel.NORMAL),
    PACKAGE_MANAGER("PackageManager", AccessLevel.NORMAL),
    SETTINGS_PROVIDER("Settings provider", AccessLevel.NORMAL),
    SENSOR_MANAGER("SensorManager", AccessLevel.NORMAL),
    INPUT_MANAGER("InputManager", AccessLevel.NORMAL),
    INPUT_EVENT("InputDevice event", AccessLevel.NORMAL),
    MEDIA_CODEC_LIST("MediaCodecList", AccessLevel.NORMAL),
    EGL("EGL/OpenGL ES", AccessLevel.NORMAL),
    AUDIO_MANAGER("AudioManager", AccessLevel.NORMAL),
    NOTIFICATION_MANAGER("NotificationManager", AccessLevel.NORMAL),
    RUNTIME("Java Runtime", AccessLevel.NORMAL),
    SHELL_SHIZUKU("Shizuku shell", AccessLevel.SHIZUKU),
    DUMPSYS_SHIZUKU("dumpsys via Shizuku", AccessLevel.SHIZUKU),
    RECORDED("recorded session", AccessLevel.NORMAL),
    ;

    /** True when the platform handed the figure over rather than it being parsed from text. */
    val isFirstParty: Boolean get() = access == AccessLevel.NORMAL
}

/**
 * How exact a figure is.
 *
 * A CPU percentage derived from two `/proc/stat` readings is not the same kind of
 * fact as a byte count the platform returned, and a frame rate inferred from
 * Choreographer callbacks in *our* window is not the same kind of fact as either.
 * The distinction is shown wherever a value is, because it is the difference
 * between a measurement and an inference.
 */
enum class Precision(val label: String) {
    /** Returned directly by the platform. No inference. */
    EXACT("Exact"),

    /** A delta between two samples: accurate, but interval-dependent. */
    SAMPLED("Sampled"),

    /**
     * Inferred from an indirect signal. Always shown with a qualifier, and never
     * used to satisfy a figure the user would reasonably read as measured — the
     * frame-rate probe reports [Observed.Restricted] rather than an estimate.
     */
    ESTIMATED("Estimated"),
}

/**
 * The privilege tier an operation needs, or that a reading came from. Ordered so
 * `satisfies` works: root satisfies a Shizuku requirement.
 *
 * GameCore never requires root and never asks for it. ROOT exists here because the
 * app must be able to *detect* a rooted device — a capability check that assumes an
 * intact sandbox is not sound on one — and because a reading obtained through a
 * root shell, if the user has Shizuku running as root, should be labelled honestly.
 */
enum class AccessLevel(val label: String, val rank: Int) {
    NORMAL("Normal", 0),
    SHIZUKU("Shizuku", 1),
    ROOT("Root", 2),
    ;

    infix fun satisfies(required: AccessLevel): Boolean = rank >= required.rank
}
