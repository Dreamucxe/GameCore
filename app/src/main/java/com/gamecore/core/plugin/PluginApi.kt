package com.gamecore.core.plugin

/**
 * The versioned contract between a plugin and the GameCore that runs it.
 *
 * A `.gcplugin` is **data, not code** — it carries no classes, no scripts and no
 * binders, only JSON and assets that this build interprets. That makes the API
 * version the single thing that decides whether a given package can be trusted to
 * mean what it says: a plugin written against API 3 may use keys this build has
 * never heard of, and a plugin written against API 1 may omit keys this build now
 * requires. Neither is a crash; both are a *compatibility* answer, computed here
 * before the loader ever opens an entry.
 *
 * The scheme is deliberately coarse. There is one integer, [CURRENT], that moves
 * forward when the format gains or changes a meaning, and a plugin declares the
 * single integer it was built against in its manifest. Everything else — which keys
 * exist, which permissions are known, which components render — is derived from that
 * one number through [compatibility]. There is no per-feature capability
 * negotiation, because a plugin cannot run code to probe for one, and a matrix of
 * optional features is exactly the kind of surface that lets a package claim support
 * it does not have.
 *
 * Pure Kotlin, no Android, so the matrix is unit-tested on the JVM.
 */
object PluginApi {

    /**
     * The API version this build of GameCore implements.
     *
     * Bumped only when the *meaning* of the format changes in a way a plugin could
     * notice — a new component, a new permission, a changed clamp. A new optional
     * key that older builds safely ignore does not need a bump; a key whose absence
     * older builds would misread does.
     *
     * 4 added the `automation_history` read scope ([DataScope.AUTOMATION_HISTORY]):
     * a plugin that declares it does not load on a build that predates the scope, so
     * it is a meaning a plugin can notice — the "new permission" case above — and the
     * number moves. Raising this did not raise [MINIMUM_SUPPORTED]: an API-3 plugin
     * still means on 4 exactly what it meant on 3, so every older plugin keeps running.
     */
    const val CURRENT = 4

    /**
     * The oldest API a plugin may declare and still load.
     *
     * A plugin built against an API older than this used meanings that have since
     * changed incompatibly, so running it would be guessing. Equal to [CURRENT]
     * until the first breaking change ships; kept separate so the day it moves is a
     * one-line, reviewed edit rather than a scatter of `>= 1` comparisons.
     */
    const val MINIMUM_SUPPORTED = 1

    /**
     * The GameCore app version a plugin is being asked to run on, expressed as the
     * build's `versionCode`. A plugin names the *lowest* code it supports; a device
     * on an older build is told the plugin is too new rather than shown a screen the
     * build cannot draw.
     *
     * This build is versionCode 37 (versionName 3.15.0). The constant is duplicated
     * here rather than read from `BuildConfig` so this object stays Android-free and
     * unit-testable; `PluginHostVersionTest` asserts the two agree, so a release that
     * bumps one and forgets the other fails the gate rather than shipping a host that
     * lies to every plugin about which build it is.
     */
    const val HOST_VERSION_CODE = 37

    /**
     * The verdict for a plugin that declares API [pluginApi] and a minimum host
     * version of [pluginMinHostVersion].
     *
     * Order matters: a plugin too new for the API is reported as such even if its
     * host-version claim would also fail, because "update GameCore" is the action in
     * both cases and the API gap is the more fundamental one.
     */
    fun compatibility(pluginApi: Int, pluginMinHostVersion: Int): Compatibility = when {
        pluginApi > CURRENT -> Compatibility.TooNew
        pluginApi < MINIMUM_SUPPORTED -> Compatibility.TooOld
        pluginMinHostVersion > HOST_VERSION_CODE -> Compatibility.HostTooOld
        else -> Compatibility.Supported
    }

    /** Convenience: is this plugin runnable at all on this build? */
    fun isSupported(pluginApi: Int, pluginMinHostVersion: Int): Boolean =
        compatibility(pluginApi, pluginMinHostVersion) == Compatibility.Supported
}

/**
 * The four answers to "can this build run this plugin". A fifth state — "runs but
 * with a feature degraded" — deliberately does not exist: a plugin either renders in
 * full or falls back per-screen to GameCore's own UI, and that fallback is a loader
 * decision, not a compatibility one.
 */
enum class Compatibility(val isRunnable: Boolean, val reason: String) {
    /** API and host version both within range. */
    Supported(true, "Compatible with this version of GameCore."),

    /** Built against a newer API than this build implements — update GameCore. */
    TooNew(false, "This plugin needs a newer version of GameCore."),

    /** Built against an API so old its meanings have changed — the plugin must be rebuilt. */
    TooOld(false, "This plugin was built for an older plugin system and must be updated."),

    /** API is fine but the plugin requires a newer app build than this one. */
    HostTooOld(false, "This plugin needs a newer version of GameCore."),
}
