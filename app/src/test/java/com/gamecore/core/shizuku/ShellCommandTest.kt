package com.gamecore.core.shizuku

import com.gamecore.BuildConfig
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.model.DisplaySize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole of what GameCore can do with ADB-level authority.
 *
 * Two things are being tested. The first is §24A.3: every argument that originates outside this
 * app's own code is validated before a command is built, and a rejected argument yields null rather
 * than a command carrying a malformed value into the settings provider. The second is §24's list of
 * absences — the commands that must not exist at any privilege level — which is asserted here by
 * enumerating every command the app can construct and checking what it is allowed to invoke.
 */
class ShellCommandTest {

    /** Every command reachable from any call site, including one of each validated form. */
    private fun everyCommand(): List<ShellCommand> = buildList {
        add(ShellCommand.Probe)
        add(ShellCommand.CpuStat)
        add(ShellCommand.MemInfo)
        add(ShellCommand.ThermalService)
        add(ShellCommand.BatteryDump)
        add(ShellCommand.DisplayDump)
        add(ShellCommand.SurfaceFlingerLatency)
        add(ShellCommand.TopActivity)
        WritableSetting.entries.forEach { add(ShellCommand.getSetting(it)) }
        WritableSetting.entries.forEach { add(ShellCommand.putSetting(it, it.restoreDefault)!!) }
        ReadableProperty.entries.forEach { add(ShellCommand.getProp(it)) }
        SelfGrantablePermission.entries.forEach {
            add(ShellCommand.grantSelfPermission(BuildConfig.APPLICATION_ID, it)!!)
        }
        SelfAppOp.entries.forEach {
            add(ShellCommand.setSelfAppOp(BuildConfig.APPLICATION_ID, it, allow = true)!!)
        }
        add(ShellCommand.frameStats("com.example.game")!!)
        add(ShellCommand.GetDisplaySize)
        add(ShellCommand.setDisplaySize(DisplaySize(1080, 1440))!!)
        add(ShellCommand.ResetDisplaySize)
    }

    @Test
    fun `the only programs GameCore can invoke are these eight`() {
        val allowed = setOf("id", "cat", "dumpsys", "settings", "getprop", "pm", "appops", "wm")
        everyCommand().forEach { command ->
            assertTrue(
                "unexpected program: ${command.argv}",
                command.argv.first() in allowed,
            )
        }
    }

    @Test
    fun `nothing here can kill an app, lie to the thermal service or rewrite a game`() {
        // §24's absences, asserted rather than trusted to review. `am`, `cmd` and `su` are not
        // reachable at all, so neither is force-stop, thermal override or a compile of another app.
        // `wm` is reachable, so its own dangerous subcommands are named here too: the only one this
        // app can reach is `size`.
        val forbidden = setOf(
            "am", "cmd", "su", "sh", "rm", "mount", "setprop", "reboot", "kill", "killall",
            "force-stop", "trim-caches", "override-status", "compile", "install", "uninstall",
            "density", "dismiss-keyguard", "overscan",
        )
        everyCommand().forEach { command ->
            command.argv.forEach { token ->
                assertFalse("forbidden token in ${command.argv}", token in forbidden)
            }
        }
    }

    @Test
    fun `a read never changes anything and a write always says that it does`() {
        val readOnly = everyCommand().filter { it.isReadOnly }
        val writes = everyCommand().filterNot { it.isReadOnly }
        readOnly.forEach { assertEquals(ShellCommand.Effect.READ_ONLY, it.effect) }
        readOnly.forEach { assertFalse(it.argv.contains("put")) }
        // Nineteen settings keys, three self-grants, two self-appops and the two halves of a display
        // size override. Nothing else writes.
        assertEquals(19, WritableSetting.entries.size)
        assertEquals(26, writes.size)
        writes.forEach { assertTrue(it.argv.first() in setOf("settings", "pm", "appops", "wm")) }
    }

    @Test
    fun `every restore default is a value its own key would accept`() {
        // If a key's fallback were rejected by its own form, a profile could be applied and never
        // unwound on a device where GameCore was killed between the write and the restore.
        WritableSetting.entries.forEach { setting ->
            assertNotNull(setting.name, ShellCommand.putSetting(setting, setting.restoreDefault))
            assertTrue(setting.name, setting.accepts(setting.restoreDefault))
        }
    }

    @Test
    fun `a settings read is built from the key's own namespace, not from a string`() {
        val read = ShellCommand.getSetting(WritableSetting.SCREEN_BRIGHTNESS)
        assertEquals(listOf("settings", "get", "system", "screen_brightness"), read.argv)
        assertEquals("Read system/screen_brightness", read.description)
        assertTrue(read.isReadOnly)

        val global = ShellCommand.getSetting(WritableSetting.LOW_POWER)
        assertEquals(listOf("settings", "get", "global", "low_power"), global.argv)
    }

    @Test
    fun `a settings write reaches exec as five separate arguments`() {
        val write = ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "120")!!
        assertEquals(listOf("settings", "put", "system", "peak_refresh_rate", "120"), write.argv)
        assertEquals(ShellCommand.Effect.CHANGES_SETTING, write.effect)
        assertEquals("120", write.value)
    }

    @Test
    fun `a refresh rate of a billion hertz is numeric and is still refused`() {
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "1e9"))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, 1_000_000_000f))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "19"))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "481"))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "NaN"))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "-1"))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "sixty"))
        assertNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, ""))
    }

    @Test
    fun `zero is a legitimate refresh rate because it releases the pin`() {
        assertNotNull(ShellCommand.putSetting(WritableSetting.MIN_REFRESH_RATE, "0"))
        assertNotNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "20"))
        assertNotNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "480"))
        assertNotNull(ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "144.0"))
    }

    @Test
    fun `a float is written in Locale US, so a comma device does not write 60,0`() {
        assertEquals("60.0", ShellCommand.putSetting(WritableSetting.MIN_REFRESH_RATE, 60f)?.value)
        assertEquals(
            "0.0",
            ShellCommand.putSetting(WritableSetting.WINDOW_ANIMATION_SCALE, 0f)?.value,
        )
        assertEquals("128", ShellCommand.putSetting(WritableSetting.SCREEN_BRIGHTNESS, 128)?.value)
    }

    @Test
    fun `a boolean setting takes exactly zero or one`() {
        assertNotNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, "0"))
        assertNotNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, "1"))
        assertNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, "true"))
        assertNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, "yes"))
        assertNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, "01"))
        assertNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, "2"))
        assertNull(ShellCommand.putSetting(WritableSetting.LOW_POWER, " 1"))
    }

    @Test
    fun `each remaining form is a range, not a numeric-looking check`() {
        assertTrue(ValueForm.BRIGHTNESS.accepts("0"))
        assertTrue(ValueForm.BRIGHTNESS.accepts("255"))
        assertFalse(ValueForm.BRIGHTNESS.accepts("256"))
        assertFalse(ValueForm.BRIGHTNESS.accepts("-1"))
        assertFalse(ValueForm.BRIGHTNESS.accepts("128.0"))

        assertTrue(ValueForm.ROTATION.accepts("0"))
        assertTrue(ValueForm.ROTATION.accepts("3"))
        assertFalse(ValueForm.ROTATION.accepts("4"))

        assertTrue(ValueForm.TIMEOUT_MILLIS.accepts("15000"))
        assertTrue(ValueForm.TIMEOUT_MILLIS.accepts("1800000"))
        assertFalse(ValueForm.TIMEOUT_MILLIS.accepts("14999"))
        assertFalse(ValueForm.TIMEOUT_MILLIS.accepts("1800001"))

        assertTrue(ValueForm.ANIMATION_SCALE.accepts("0.0"))
        assertTrue(ValueForm.ANIMATION_SCALE.accepts("10"))
        assertFalse(ValueForm.ANIMATION_SCALE.accepts("10.1"))
        assertFalse(ValueForm.ANIMATION_SCALE.accepts("-0.5"))
        assertFalse(ValueForm.ANIMATION_SCALE.accepts("NaN"))
    }

    @Test
    fun `a self-grant is checked against the compiled application id, not the caller's word`() {
        val granted = ShellCommand.grantSelfPermission(
            BuildConfig.APPLICATION_ID,
            SelfGrantablePermission.PACKAGE_USAGE_STATS,
        )!!
        assertEquals(
            listOf("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.PACKAGE_USAGE_STATS"),
            granted.argv,
        )
        assertEquals(ShellCommand.Effect.CHANGES_SETTING, granted.effect)
    }

    @Test
    fun `a grant cannot be aimed at another application`() {
        SelfGrantablePermission.entries.forEach { permission ->
            assertNull(ShellCommand.grantSelfPermission("com.other.app", permission))
            assertNull(ShellCommand.grantSelfPermission("${BuildConfig.APPLICATION_ID}.evil", permission))
            assertNull(ShellCommand.grantSelfPermission("${BuildConfig.APPLICATION_ID} ", permission))
            assertNull(ShellCommand.grantSelfPermission("", permission))
        }
        SelfAppOp.entries.forEach { op ->
            assertNull(ShellCommand.setSelfAppOp("com.other.app", op, allow = true))
            assertNull(ShellCommand.setSelfAppOp("android", op, allow = false))
        }
    }

    @Test
    fun `an app-op is set to allow or back to the platform default, never to ignore`() {
        val allowed = ShellCommand.setSelfAppOp(
            BuildConfig.APPLICATION_ID,
            SelfAppOp.GET_USAGE_STATS,
            allow = true,
        )!!
        assertEquals(
            listOf("appops", "set", BuildConfig.APPLICATION_ID, "android:get_usage_stats", "allow"),
            allowed.argv,
        )
        val revoked = ShellCommand.setSelfAppOp(
            BuildConfig.APPLICATION_ID,
            SelfAppOp.WRITE_SETTINGS,
            allow = false,
        )!!
        assertEquals("default", revoked.mode)
        assertEquals("android:write_settings", revoked.op)
    }

    @Test
    fun `frame timing is only read for a well-formed package name`() {
        val stats = ShellCommand.frameStats("com.example.game")!!
        assertEquals(listOf("dumpsys", "gfxinfo", "com.example.game", "framestats"), stats.argv)
        assertTrue(stats.isReadOnly)
    }

    @Test
    fun `a package name from a stored profile is validated before it becomes an argument`() {
        assertNull(ShellCommand.frameStats(""))
        assertNull(ShellCommand.frameStats("   "))
        assertNull(ShellCommand.frameStats("nodots"))
        assertNull(ShellCommand.frameStats("com.example.game; id"))
        assertNull(ShellCommand.frameStats("com.example.game && rm -rf /"))
        assertNull(ShellCommand.frameStats("../../etc/passwd"))
        assertFalse(ShellCommand.isValidPackageName("com.example.game; id"))
        assertTrue(ShellCommand.isValidPackageName("com.example.game"))
        assertFalse(ShellCommand.isValidPackageName(null))
    }

    @Test
    fun `the properties GameCore reads identify the chipset and nothing else`() {
        assertEquals(listOf("getprop", "ro.board.platform"), ShellCommand.getProp(ReadableProperty.BOARD_PLATFORM).argv)
        assertEquals(listOf("getprop", "ro.hardware"), ShellCommand.getProp(ReadableProperty.HARDWARE).argv)
        assertEquals(3, ReadableProperty.entries.size)
        ReadableProperty.entries.forEach { assertTrue(it.key.startsWith("ro.")) }
    }

    @Test
    fun `the read-only fixtures are the commands they say they are`() {
        assertEquals(listOf("id"), ShellCommand.Probe.argv)
        assertEquals(listOf("cat", "/proc/stat"), ShellCommand.CpuStat.argv)
        assertEquals(listOf("cat", "/proc/meminfo"), ShellCommand.MemInfo.argv)
        assertEquals(listOf("dumpsys", "thermalservice"), ShellCommand.ThermalService.argv)
        assertEquals(listOf("dumpsys", "battery"), ShellCommand.BatteryDump.argv)
        assertEquals(listOf("dumpsys", "display"), ShellCommand.DisplayDump.argv)
        assertEquals(
            listOf("dumpsys", "SurfaceFlinger", "--latency"),
            ShellCommand.SurfaceFlingerLatency.argv,
        )
        assertEquals(listOf("dumpsys", "activity", "activities"), ShellCommand.TopActivity.argv)
        assertEquals(listOf("wm", "size"), ShellCommand.GetDisplaySize.argv)
    }

    @Test
    fun `a display size reaches exec as one WxH token and nothing else`() {
        val set = ShellCommand.setDisplaySize(DisplaySize(1080, 1440))!!
        assertEquals(listOf("wm", "size", "1080x1440"), set.argv)
        assertEquals(ShellCommand.Effect.CHANGES_SETTING, set.effect)
        assertEquals(DisplaySize(1080, 1440), set.size)

        // The undo is its own command rather than a size that happens to equal the panel, because an
        // override equal to native is still an override.
        assertEquals(listOf("wm", "size", "reset"), ShellCommand.ResetDisplaySize.argv)
        assertEquals(ShellCommand.Effect.CHANGES_SETTING, ShellCommand.ResetDisplaySize.effect)
    }

    @Test
    fun `a display size that is not a plausible screen is refused before it is a command`() {
        // The floor is what stops a mistyped number leaving a screen too small to correct it from, and a
        // size override survives the reboot that would otherwise be the way out.
        assertNull(ShellCommand.setDisplaySize(DisplaySize(0, 0)))
        assertNull(ShellCommand.setDisplaySize(DisplaySize(-1080, -1440)))
        assertNull(ShellCommand.setDisplaySize(DisplaySize(16, 16)))
        assertNull(ShellCommand.setDisplaySize(DisplaySize(319, 1440)))
        assertNull(ShellCommand.setDisplaySize(DisplaySize(1080, 100_000)))
        assertNotNull(ShellCommand.setDisplaySize(DisplaySize(320, 320)))
        assertNotNull(ShellCommand.setDisplaySize(DisplaySize(1440, 3200)))
    }

    @Test
    fun `the keys that need Shizuku are the ones an ordinary app cannot write`() {
        // What the capability layer reads to tell the user which path a profile setting needs.
        val normal = WritableSetting.entries.filter { it.requiredAccess == AccessLevel.NORMAL }
        assertTrue(WritableSetting.SCREEN_BRIGHTNESS in normal)
        assertTrue(WritableSetting.SCREEN_OFF_TIMEOUT in normal)
        assertTrue(WritableSetting.USER_ROTATION in normal)
        assertFalse(WritableSetting.PEAK_REFRESH_RATE in normal)
        assertFalse(WritableSetting.LOW_POWER in normal)
        assertFalse(WritableSetting.WINDOW_ANIMATION_SCALE in normal)
    }
}
