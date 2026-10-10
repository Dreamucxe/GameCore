package com.gamecore.core.plugin

import com.gamecore.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one number the plugin system cannot be allowed to get wrong about itself.
 *
 * [PluginApi.HOST_VERSION_CODE] is a compiled-in literal rather than a `BuildConfig` read, so that the
 * whole object stays Android-free and every compatibility decision can be tested on the JVM. The cost
 * of that choice is a duplicated fact, and a duplicated fact drifts: a release bumps `versionCode` in
 * `app/build.gradle.kts`, nobody touches `PluginApi`, and from then on the host tells every plugin it
 * is an older build than it is. A plugin that legitimately requires the new build is then refused as
 * "needs a newer GameCore" on the very version that has it — a bug that looks like the plugin's fault
 * and would survive any amount of review of the plugin side.
 *
 * So the duplication is allowed, and this is the test that makes it safe. It is the reason the KDoc on
 * [PluginApi.HOST_VERSION_CODE] can promise the two agree.
 */
class PluginHostVersionTest {

    @Test
    fun `the host version the plugin API reports is this build's version code`() {
        assertEquals(BuildConfig.VERSION_CODE, PluginApi.HOST_VERSION_CODE)
    }

    /**
     * The API version is independent of the app version and must stay so. If a release ever bumps
     * [PluginApi.CURRENT] alongside the app, every plugin built against API 1 becomes `TooNew`'s
     * mirror image — `TooOld` — on upgrade, and a user's installed plugins all stop at once. Raising
     * `CURRENT` is a deliberate format change that also raises `MINIMUM_SUPPORTED` only when support is
     * genuinely dropped; this asserts the invariant that makes old plugins keep running.
     */
    @Test
    fun `the supported API floor is at or below the current API`() {
        assertTrue(
            "MINIMUM_SUPPORTED (${PluginApi.MINIMUM_SUPPORTED}) must not exceed CURRENT (${PluginApi.CURRENT})",
            PluginApi.MINIMUM_SUPPORTED <= PluginApi.CURRENT,
        )
    }

    /**
     * A plugin built against this host's own constants is runnable on this host. Trivial to state and
     * the single most load-bearing case there is: it is what every plugin the SDK produces declares by
     * default, so if this fails nothing installs at all.
     */
    @Test
    fun `a plugin declaring this host's own API and version is supported`() {
        assertEquals(
            Compatibility.Supported,
            PluginApi.compatibility(PluginApi.CURRENT, PluginApi.HOST_VERSION_CODE),
        )
    }
}
