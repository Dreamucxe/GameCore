package com.gamecore.data.database

import com.gamecore.core.model.DASHBOARD_SCHEMA_VERSION
import com.gamecore.core.model.DashboardItem
import com.gamecore.core.model.DashboardItemKind
import com.gamecore.core.model.DashboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard domain↔row mapping [DashboardMappers] owns, pinned both sides of the DAO's save/load
 * transaction. Room cannot run in a JVM unit test here, so the DAO's own transaction is covered elsewhere;
 * what is pure, and what protects a dashboard written by a newer build, is the mapper.
 *
 * Two habits the object inherits from [Mappers] are what these tests hold. A layout name is sanitised
 * through [TextSanitizer] and never written blank: an empty or all-whitespace name becomes "My dashboard",
 * and an over-long one is cut to [DashboardLayout.MAX_NAME_LENGTH]. An item row whose [DashboardItemKind]
 * name this build does not recognise is dropped on load rather than defaulted to the wrong kind — and
 * because that drop lives in a private helper, it is exercised the only way it runs in production, through
 * [DashboardMappers.toModel]. The remaining assertion is that [toModel] hands back a
 * [DashboardLayout.normalised] result, positions densified to a canonical 0..n-1, so the single load path
 * every dashboard screen shares never sees a sparse or out-of-order set.
 */
class DashboardMappersTest {

    @Test
    fun `a blank or all-whitespace name becomes the default dashboard name`() {
        assertEquals(
            "My dashboard",
            DashboardMappers.toEntity(DashboardLayout.empty().copy(name = ""), nowMillis = 1L).name,
        )
        assertEquals(
            "My dashboard",
            DashboardMappers.toEntity(DashboardLayout.empty().copy(name = "   "), nowMillis = 1L).name,
        )
    }

    @Test
    fun `an over-long name is sanitised to the dashboard name limit`() {
        val longName = "a".repeat(100)
        val name = DashboardMappers.toEntity(DashboardLayout.empty().copy(name = longName), nowMillis = 1L).name
        // TextSanitizer truncates to MAX_NAME_LENGTH and marks the cut with an ellipsis, so the stored
        // name is (MAX_NAME_LENGTH - 1) kept characters plus "…" — length MAX_NAME_LENGTH, never more.
        assertTrue(name.length <= DashboardLayout.MAX_NAME_LENGTH)
        assertTrue(name.endsWith("…"))
        assertTrue(name.length < longName.length)
    }

    @Test
    fun `createdAtMillis is preserved when set and stamped with now when zero`() {
        val existing = DashboardLayout(
            id = 1L,
            name = "d",
            schemaVersion = DASHBOARD_SCHEMA_VERSION,
            isSelected = false,
            items = emptyList(),
            createdAtMillis = 5_000L,
            updatedAtMillis = 6_000L,
        )
        val fromExisting = DashboardMappers.toEntity(existing, nowMillis = 9_999L)
        assertEquals(5_000L, fromExisting.createdAtMillis)
        assertEquals(9_999L, fromExisting.updatedAtMillis)

        val fromFresh = DashboardMappers.toEntity(existing.copy(createdAtMillis = 0L), nowMillis = 9_999L)
        assertEquals(9_999L, fromFresh.createdAtMillis)
        assertEquals(9_999L, fromFresh.updatedAtMillis)
    }

    @Test
    fun `toItemEntity copies every field and stamps the layout id`() {
        val item = DashboardItem(
            itemId = "plugin:aim#reticle",
            kind = DashboardItemKind.PLUGIN_WIDGET,
            pluginId = "aim",
            refId = "reticle",
            position = 7,
            visible = false,
        )
        val row = DashboardMappers.toItemEntity(item, layoutId = 42L)
        assertEquals("plugin:aim#reticle", row.itemId)
        assertEquals(42L, row.layoutId)
        assertEquals("PLUGIN_WIDGET", row.kind)
        assertEquals("aim", row.pluginId)
        assertEquals("reticle", row.refId)
        assertEquals(7, row.position)
        assertFalse(row.visible)
    }

    @Test
    fun `an item of a kind this build does not know is dropped, not defaulted`() {
        val rows = listOf(
            itemEntity(itemId = "builtin:fps", kind = "BUILTIN", refId = "fps", position = 0),
            itemEntity(itemId = "plugin:aim#reticle", kind = "PLUGIN_WIDGET", pluginId = "aim", refId = "reticle", position = 1),
            itemEntity(itemId = "future", kind = "FUTURE_KIND", refId = "mystery", position = 2),
        )
        val loaded = DashboardMappers.toModel(layoutEntity(), rows)
        assertEquals(2, loaded.items.size)
        assertTrue(loaded.items.none { it.refId == "mystery" })
        assertTrue(loaded.items.any { it.kind == DashboardItemKind.BUILTIN })
        assertTrue(loaded.items.any { it.kind == DashboardItemKind.PLUGIN_WIDGET })
    }

    @Test
    fun `a loaded layout comes back with dense canonical positions`() {
        val rows = listOf(
            itemEntity(itemId = "builtin:a", kind = "BUILTIN", refId = "a", position = 50),
            itemEntity(itemId = "builtin:b", kind = "BUILTIN", refId = "b", position = 5),
            itemEntity(itemId = "builtin:c", kind = "BUILTIN", refId = "c", position = 20),
        )
        val loaded = DashboardMappers.toModel(layoutEntity(), rows)
        assertEquals(listOf(0, 1, 2), loaded.items.map { it.position })
        // Canonical order is ascending by original position: b(5), c(20), a(50).
        assertEquals(listOf("b", "c", "a"), loaded.items.map { it.refId })
    }

    @Test
    fun `a full entity round-trip preserves identity and the surviving items`() {
        val layout = DashboardLayout(
            id = 11L,
            name = "My HUD",
            schemaVersion = DASHBOARD_SCHEMA_VERSION,
            isSelected = true,
            items = emptyList(),
            createdAtMillis = 1_234L,
            updatedAtMillis = 5_678L,
        )
        val entity = DashboardMappers.toEntity(layout, nowMillis = 9_999L)
        val rows = listOf(
            DashboardMappers.toItemEntity(DashboardItem.builtin("fps", position = 0), entity.id),
            DashboardMappers.toItemEntity(DashboardItem.pluginWidget("aim", "reticle", position = 1), entity.id),
            itemEntity(itemId = "future", kind = "FUTURE_KIND", refId = "mystery", position = 2, layoutId = entity.id),
        )
        val restored = DashboardMappers.toModel(entity, rows)

        assertEquals(11L, restored.id)
        assertEquals("My HUD", restored.name)
        assertEquals(DASHBOARD_SCHEMA_VERSION, restored.schemaVersion)
        assertTrue(restored.isSelected)
        assertEquals(listOf("builtin:fps", "plugin:aim#reticle"), restored.items.map { it.itemId })
    }

    private fun layoutEntity(
        id: Long = 1L,
        name: String = "My dashboard",
        schemaVersion: Int = DASHBOARD_SCHEMA_VERSION,
        isSelected: Boolean = true,
        createdAtMillis: Long = 1_000L,
        updatedAtMillis: Long = 2_000L,
    ) = DashboardLayoutEntity(
        id = id,
        name = name,
        schemaVersion = schemaVersion,
        isSelected = isSelected,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    private fun itemEntity(
        itemId: String,
        kind: String,
        refId: String,
        position: Int,
        layoutId: Long = 1L,
        pluginId: String = "",
        visible: Boolean = true,
    ) = DashboardItemEntity(
        itemId = itemId,
        layoutId = layoutId,
        kind = kind,
        pluginId = pluginId,
        refId = refId,
        position = position,
        visible = visible,
    )
}
