package com.gamecore.ui.plugins

import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginInstallPlan
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.RequestedPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent screen's arithmetic, tested without a device.
 *
 * These four functions decide what a user has actually granted, and every way they can be wrong is
 * silent. A revoke that left a scope behind, a scope stored against a permission that is not granted, a
 * summary that counted the wrong thing, or an Enable switch that let a plugin run against a grant the
 * user had not stored — none of those throw, none of them crash, and all of them are invisible until
 * someone inspects the grant store and finds consent recorded for something the user withheld. That is
 * why the logic lives in [PluginDetailState] as plain Kotlin over plain data and is pinned here rather
 * than checked by looking at the screen.
 *
 * Pure JVM: no Compose, no Android, no Robolectric, no registry. A failure in this file is a failure in
 * the grant rules and nowhere else.
 */
class PluginDetailStateTest {

    private val id = "com.example.plugin"

    private fun manifest(
        api: Int = PluginApi.CURRENT,
        minHost: Int = 1,
        perms: List<Pair<PluginPermission, Set<String>>> = emptyList(),
    ) = PluginManifest(
        id = id,
        name = "Example",
        version = 1,
        versionName = "1.0",
        api = api,
        minHostVersion = minHost,
        developer = "Tester",
        description = "An example.",
        permissions = perms.map { RequestedPermission(it.first, it.second) },
        declares = emptySet(),
        changelog = "First release.",
    )

    private fun plan(
        api: Int = PluginApi.CURRENT,
        minHost: Int = 1,
        perms: List<Pair<PluginPermission, Set<String>>> = emptyList(),
    ) = PluginInstallPlan.from(manifest(api, minHost, perms), sha256 = "a".repeat(64))

    private fun grant(
        granted: Map<PluginPermission, Set<String>> = emptyMap(),
        advancedAccess: Boolean = false,
    ) = PluginGrant(id, granted, advancedAccess)

    // ---------------------------------------------------------------- toggling a permission

    @Test
    fun `toggling a permission on then off leaves an empty grant`() {
        val granted = togglePermission(PluginGrant.none(id), PluginPermission.CHANGE_UI, on = true)
        assertTrue(granted.has(PluginPermission.CHANGE_UI))

        val revoked = togglePermission(granted, PluginPermission.CHANGE_UI, on = false)
        assertEquals(PluginGrant.none(id), revoked)
        assertTrue(revoked.granted.isEmpty())
    }

    @Test
    fun `granting a scoped permission opens none of its scopes`() {
        val granted = togglePermission(PluginGrant.none(id), PluginPermission.READ_APPROVED_DATA, on = true)
        // Opt-in applied one level down: the permission is on, every aperture is still shut.
        assertTrue(granted.has(PluginPermission.READ_APPROVED_DATA))
        assertTrue(granted.scopes(PluginPermission.READ_APPROVED_DATA).isEmpty())
    }

    @Test
    fun `toggling a permission off drops the scopes it held`() {
        val open = grant(
            granted = mapOf(PluginPermission.READ_APPROVED_DATA to setOf("sessions", "thermals")),
        )
        val revoked = togglePermission(open, PluginPermission.READ_APPROVED_DATA, on = false)
        assertTrue(revoked.granted.isEmpty())

        // Re-granting must not resurrect the apertures the user closed by revoking.
        val again = togglePermission(revoked, PluginPermission.READ_APPROVED_DATA, on = true)
        assertTrue(again.scopes(PluginPermission.READ_APPROVED_DATA).isEmpty())
        assertFalse(again.hasScope(PluginPermission.READ_APPROVED_DATA, "thermals"))
    }

    @Test
    fun `granting an already granted permission keeps the scopes it has`() {
        val open = grant(granted = mapOf(PluginPermission.READ_APPROVED_DATA to setOf("sessions")))
        val again = togglePermission(open, PluginPermission.READ_APPROVED_DATA, on = true)
        assertEquals(open, again)
    }

    @Test
    fun `toggling a permission never moves advanced access`() {
        val opted = grant(advancedAccess = true)
        val granted = togglePermission(opted, PluginPermission.SYSTEM_ACTIONS, on = true)
        assertTrue(granted.advancedAccess)

        val revoked = togglePermission(granted, PluginPermission.SYSTEM_ACTIONS, on = false)
        assertTrue(revoked.advancedAccess)
    }

    // ---------------------------------------------------------------- toggling a scope

    @Test
    fun `a scope cannot be added to a permission that is not granted`() {
        val empty = PluginGrant.none(id)
        val attempted = toggleScope(empty, PluginPermission.READ_APPROVED_DATA, "sessions", on = true)
        assertEquals(empty, attempted)
        assertFalse(attempted.hasScope(PluginPermission.READ_APPROVED_DATA, "sessions"))
    }

    @Test
    fun `a scope opens inside a granted permission and closes again`() {
        val granted = togglePermission(PluginGrant.none(id), PluginPermission.READ_APPROVED_DATA, on = true)
        val opened = toggleScope(granted, PluginPermission.READ_APPROVED_DATA, "sessions", on = true)
        assertTrue(opened.hasScope(PluginPermission.READ_APPROVED_DATA, "sessions"))

        val closed = toggleScope(opened, PluginPermission.READ_APPROVED_DATA, "sessions", on = false)
        assertFalse(closed.hasScope(PluginPermission.READ_APPROVED_DATA, "sessions"))
        // Granted with no aperture open is a real state, not the same thing as ungranted.
        assertTrue(closed.has(PluginPermission.READ_APPROVED_DATA))
    }

    @Test
    fun `a scope is stored in the case the gate looks it up in`() {
        val granted = togglePermission(PluginGrant.none(id), PluginPermission.READ_APPROVED_DATA, on = true)
        val opened = toggleScope(granted, PluginPermission.READ_APPROVED_DATA, "Sessions", on = true)
        assertEquals(setOf("sessions"), opened.scopes(PluginPermission.READ_APPROVED_DATA))
        assertTrue(opened.hasScope(PluginPermission.READ_APPROVED_DATA, "sessions"))
    }

    @Test
    fun `a blank scope is refused rather than stored`() {
        val granted = togglePermission(PluginGrant.none(id), PluginPermission.READ_APPROVED_DATA, on = true)
        val attempted = toggleScope(granted, PluginPermission.READ_APPROVED_DATA, "   ", on = true)
        assertEquals(granted, attempted)
    }

    // ---------------------------------------------------------------- the advanced opt-in

    @Test
    fun `advanced access off leaves a granted HIGH permission inert`() {
        val requested = plan(perms = listOf(PluginPermission.SYSTEM_ACTIONS to emptySet()))
        val draft = grant(
            granted = mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = false,
        )
        assertTrue(hasInertHighTierGrant(requested, draft))

        val state = PluginDetailUiState(plan = requested, draft = draft, saved = draft)
        assertTrue(state.requiresAdvancedAccess)
    }

    @Test
    fun `advanced access on makes a granted HIGH permission live`() {
        val requested = plan(perms = listOf(PluginPermission.SYSTEM_ACTIONS to emptySet()))
        val draft = grant(
            granted = mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = true,
        )
        assertFalse(hasInertHighTierGrant(requested, draft))
        assertFalse(PluginDetailUiState(plan = requested, draft = draft, saved = draft).requiresAdvancedAccess)
    }

    @Test
    fun `advanced access on its own is not an inert grant`() {
        val requested = plan(perms = listOf(PluginPermission.SYSTEM_ACTIONS to emptySet()))
        // The opt-in without the permission authorises nothing, so there is nothing to warn about.
        val draft = grant(advancedAccess = false)
        assertFalse(hasInertHighTierGrant(requested, draft))
    }

    @Test
    fun `a HIGH permission the manifest never requested cannot block the screen`() {
        val requested = plan(perms = listOf(PluginPermission.CHANGE_UI to emptySet()))
        // An over-reaching grant map is already inert at the gate as NOT_REQUESTED; treating it as a
        // warning here would be a dead end, because this screen offers no switch that could clear it.
        val draft = grant(granted = mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()))
        assertFalse(hasInertHighTierGrant(requested, draft))
    }

    // ---------------------------------------------------------------- the summary

    @Test
    fun `grantSummary counts granted permissions out of requested`() {
        val requested = plan(
            perms = listOf(
                PluginPermission.CHANGE_UI to emptySet(),
                PluginPermission.ADD_PILL_MODULES to emptySet(),
                PluginPermission.CREATE_OVERLAY to emptySet(),
                PluginPermission.RUN_AUTOMATIONS to emptySet(),
                PluginPermission.SYSTEM_ACTIONS to emptySet(),
            ),
        )
        val draft = grant(
            granted = mapOf(
                PluginPermission.CHANGE_UI to emptySet(),
                PluginPermission.CREATE_OVERLAY to emptySet(),
                PluginPermission.SYSTEM_ACTIONS to emptySet(),
            ),
        )
        assertEquals("3 of 5 requested permissions granted", grantSummary(requested, draft))
    }

    @Test
    fun `grantSummary does not count a permission that was never requested`() {
        val requested = plan(perms = listOf(PluginPermission.CHANGE_UI to emptySet()))
        val draft = grant(
            granted = mapOf(
                PluginPermission.CHANGE_UI to emptySet(),
                PluginPermission.SYSTEM_ACTIONS to emptySet(),
            ),
        )
        assertEquals("1 of 1 requested permission granted", grantSummary(requested, draft))
    }

    @Test
    fun `grantSummary says plainly when nothing is requested`() {
        assertEquals("This plugin requests no permissions.", grantSummary(plan(), PluginGrant.none(id)))
    }

    @Test
    fun `grantSummary counts nothing granted as nothing granted`() {
        val requested = plan(
            perms = listOf(
                PluginPermission.CHANGE_UI to emptySet(),
                PluginPermission.CREATE_OVERLAY to emptySet(),
            ),
        )
        assertEquals("0 of 2 requested permissions granted", grantSummary(requested, PluginGrant.none(id)))
    }

    // ---------------------------------------------------------------- requestedPermissions

    @Test
    fun `requestedPermissions lists every request with the advanced one first`() {
        val requested = plan(
            perms = listOf(
                PluginPermission.CHANGE_UI to emptySet(),
                PluginPermission.CREATE_OVERLAY to emptySet(),
                PluginPermission.SYSTEM_ACTIONS to emptySet(),
            ),
        )
        val order = requestedPermissions(requested)
        assertEquals(3, order.size)
        // HIGH-first is what stops a plugin burying the one permission that matters under harmless ones.
        assertEquals(PluginPermission.SYSTEM_ACTIONS, order.first())
    }

    // ---------------------------------------------------------------- canEnable

    @Test
    fun `canEnable is false while a change is unsaved`() {
        val requested = plan(perms = listOf(PluginPermission.CHANGE_UI to emptySet()))
        val saved = PluginGrant.none(id)
        val draft = togglePermission(saved, PluginPermission.CHANGE_UI, on = true)
        assertFalse(canEnable(requested, draft, saved))

        val state = PluginDetailUiState(plan = requested, draft = draft, saved = saved)
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.canEnable)
    }

    @Test
    fun `canEnable is true once the draft matches the stored grant`() {
        val requested = plan(perms = listOf(PluginPermission.CHANGE_UI to emptySet()))
        val stored = grant(granted = mapOf(PluginPermission.CHANGE_UI to emptySet()))
        assertTrue(canEnable(requested, stored, stored))

        val state = PluginDetailUiState(plan = requested, draft = stored, saved = stored)
        assertFalse(state.hasUnsavedChanges)
        assertTrue(state.canEnable)
    }

    @Test
    fun `an edit undone leaves nothing unsaved`() {
        val requested = plan(perms = listOf(PluginPermission.CHANGE_UI to emptySet()))
        val saved = PluginGrant.none(id)
        val on = togglePermission(saved, PluginPermission.CHANGE_UI, on = true)
        val off = togglePermission(on, PluginPermission.CHANGE_UI, on = false)
        assertFalse(PluginDetailUiState(plan = requested, draft = off, saved = saved).hasUnsavedChanges)
        assertTrue(canEnable(requested, off, saved))
    }

    @Test
    fun `canEnable is false for a plugin this build cannot run`() {
        val requested = plan(minHost = 9999, perms = listOf(PluginPermission.CHANGE_UI to emptySet()))
        val stored = grant(granted = mapOf(PluginPermission.CHANGE_UI to emptySet()))
        assertFalse(requested.canInstall)
        assertFalse(canEnable(requested, stored, stored))
    }

    @Test
    fun `canEnable is false while a granted HIGH permission has no advanced access`() {
        val requested = plan(perms = listOf(PluginPermission.SYSTEM_ACTIONS to emptySet()))
        val stored = grant(
            granted = mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = false,
        )
        // Saved, compatible, and still refused: the grant is real and the gate would deny every call.
        assertFalse(canEnable(requested, stored, stored))

        val opted = stored.copy(advancedAccess = true)
        assertTrue(canEnable(requested, opted, opted))
    }

    @Test
    fun `canEnable is true for a plugin that asks for nothing`() {
        val stored = PluginGrant.none(id)
        assertTrue(canEnable(plan(), stored, stored))
    }

    @Test
    fun `a screen that has not loaded has nothing unsaved and cannot be enabled`() {
        val state = PluginDetailUiState()
        assertFalse(state.hasUnsavedChanges)
        assertFalse(state.canEnable)
        assertFalse(state.canTurnOn)
        assertFalse(state.requiresAdvancedAccess)
    }
}
