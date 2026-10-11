package com.gamecore.data.repository

import com.gamecore.core.model.DashboardItem
import com.gamecore.core.model.DashboardLayout
import com.gamecore.data.database.DashboardDao
import com.gamecore.data.database.DashboardItemEntity
import com.gamecore.data.database.DashboardLayoutEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DashboardRepository]'s contract, proven against an in-memory [FakeDashboardDao] that models Room's
 * two-table save/select/delete exactly — Room cannot run in a JVM unit test here.
 *
 * What is pinned, and why these are the behaviours a user can be lied to by:
 *  - A save is the one entry point to disk, so it normalises rather than trusts: an over-full,
 *    duplicated or sparsely-positioned layout is stored capped, deduped and densified, never raw.
 *  - The save transaction re-points items at the *resolved* id and replaces the prior set wholesale,
 *    so a layout's items can never be left pointing at a stale parent and an edit leaves no orphans.
 *  - [DashboardRepository.selectedLayout] is `null` on first run — the honest empty value — and must
 *    not write a row merely because it was observed.
 *  - [DashboardRepository.select] is the single place the one-selected invariant is enforced; it
 *    repairs even a store where several rows read as selected down to exactly one.
 *
 * The fake implements only the DAO's abstract primitives; its `@Transaction` default methods (save,
 * select, deleteWithItems) are inherited unoverridden, so these tests exercise the *real* transaction
 * logic against fake storage. [FakeDashboardDao.insertLayout] emulates Room's autoincrement + REPLACE,
 * and the three `observe*` flows re-emit after every mutation. If production save/select ever
 * regressed, this fake would have to regress in lockstep to stay green, which is the point.
 */
class DashboardRepositoryTest {

    @Test
    fun `save of a fresh layout returns a positive id and persists as the selected layout`() = runTest {
        val dao = FakeDashboardDao()
        val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))
        val fresh = DashboardLayout.empty(name = "mine").copy(
            items = listOf(
                DashboardItem.builtin("clock", position = 0),
                DashboardItem.builtin("battery", position = 1),
            ),
        )

        val id = repo.save(fresh)

        assertTrue("a fresh save must resolve to a real rowid", id > 0L)
        val selected = repo.selectedLayout.first()
        assertNotNull("empty() is isSelected=true, so the saved layout is the selected one", selected)
        assertEquals(id, selected!!.id)
        assertEquals(2, selected.items.size)
        assertEquals(
            listOf("clock", "battery"),
            selected.items.sortedBy { it.position }.map { it.refId },
        )
    }

    @Test
    fun `save normalises at the repository boundary — over-cap items are capped and densified`() = runTest {
        val dao = FakeDashboardDao()
        val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))
        // More plugin widgets than the ceiling, deliberately given sparse (non-dense) positions.
        val overfull = DashboardLayout.empty(name = "big").copy(
            items = (0 until 40).map { i -> DashboardItem.pluginWidget("p", "w$i", position = i * 3) },
        )

        val id = repo.save(overfull)

        val stored = dao.items(id)
        assertEquals(
            "plugin-widget items are capped at the registry ceiling",
            DashboardLayout.MAX_PLUGIN_WIDGET_ITEMS,
            stored.size,
        )
        assertEquals(
            "positions are densified to 0..n, not left sparse",
            (0 until DashboardLayout.MAX_PLUGIN_WIDGET_ITEMS).toList(),
            stored.map { it.position },
        )
    }

    @Test
    fun `selectedLayout is null when nothing is selected and observing writes no row`() = runTest {
        val dao = FakeDashboardDao()
        val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))

        assertNull("the honest first-run value is null", repo.selectedLayout.first())
        assertEquals("observing must not write a row to disk", 0, repo.count())
    }

    @Test
    fun `saving the same layout again re-points items at the resolved id and replaces the prior set`() =
        runTest {
            val dao = FakeDashboardDao()
            val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))
            val first = DashboardLayout.empty(name = "two-then-one").copy(
                items = listOf(
                    DashboardItem.builtin("a", position = 0),
                    DashboardItem.builtin("b", position = 1),
                ),
            )

            val id = repo.save(first)
            assertEquals(2, dao.items(id).size)

            repo.save(first.copy(id = id, items = listOf(DashboardItem.builtin("c", position = 0))))

            val after = dao.items(id)
            assertEquals("the prior item set is replaced wholesale", 1, after.size)
            assertEquals("the surviving item points at the resolved layout id", id, after.single().layoutId)
            assertEquals("c", after.single().refId)
            assertEquals("the layout row was replaced, not duplicated", 1, repo.count())
        }

    @Test
    fun `layout maps an existing id and returns null for a missing one`() = runTest {
        val dao = FakeDashboardDao()
        val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))
        val id = repo.save(
            DashboardLayout.empty(name = "present").copy(
                items = listOf(DashboardItem.builtin("x", position = 0)),
            ),
        )

        val present = repo.layout(id)
        assertNotNull(present)
        assertEquals(id, present!!.id)
        assertEquals("present", present.name)
        assertEquals(1, present.items.size)

        assertNull("a missing id maps to null, never a blank layout", repo.layout(id + 999L))
    }

    @Test
    fun `delete removes the layout and its items`() = runTest {
        val dao = FakeDashboardDao()
        val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))
        val id = repo.save(
            DashboardLayout.empty(name = "doomed").copy(
                items = listOf(
                    DashboardItem.builtin("a", position = 0),
                    DashboardItem.builtin("b", position = 1),
                ),
            ),
        )
        assertEquals(1, repo.count())
        assertEquals(2, dao.items(id).size)

        repo.delete(id)

        assertEquals("the layout row is gone", 0, repo.count())
        assertNull("the layout no longer maps", repo.layout(id))
        assertTrue("its items are deleted with it", dao.observeItems(id).first().isEmpty())
    }

    @Test
    fun `select enforces the single-selected invariant — exactly one row stays selected`() = runTest {
        val dao = FakeDashboardDao()
        val repo = DashboardRepository(dao, UnconfinedTestDispatcher(testScheduler))
        // Both come from empty() (isSelected=true), so the store starts with two rows reading as
        // selected — the mess select() must repair down to one.
        val firstId = repo.save(DashboardLayout.empty(name = "first"))
        val secondId = repo.save(DashboardLayout.empty(name = "second"))
        assertTrue("two distinct layouts were stored", firstId != secondId)

        repo.select(secondId)

        val selectedRows = dao.observeLayouts().first().filter { it.isSelected }
        assertEquals("exactly one layout may read as selected", 1, selectedRows.size)
        assertEquals("and it is the one just selected", secondId, selectedRows.single().id)
    }

    // ------------------------------------------------------------------ fake DAO

    /**
     * An in-memory [DashboardDao]: layouts in an insertion-ordered map, item rows in a flat list keyed by
     * [DashboardItemEntity.layoutId] the way the real table is, and three [MutableStateFlow]s re-published
     * after every mutation so [observeSelectedLayout]/[observeItems] reflect writes.
     *
     * Only the abstract primitives are implemented; `save`, `select` and `deleteWithItems` are inherited
     * from the interface so the real transaction logic runs here. [insertLayout] emulates Room's
     * autoincrement + REPLACE: id 0 is "unset" and gets the next rowid, a non-zero id replaces that row.
     */
    private class FakeDashboardDao : DashboardDao {
        private var nextId = 1L
        private val layouts = LinkedHashMap<Long, DashboardLayoutEntity>()
        private val itemRows = ArrayList<DashboardItemEntity>()

        private val layoutsFlow = MutableStateFlow<List<DashboardLayoutEntity>>(emptyList())
        private val selectedFlow = MutableStateFlow<DashboardLayoutEntity?>(null)
        private val itemsFlow = MutableStateFlow<List<DashboardItemEntity>>(emptyList())

        /** Re-derive every observable from the backing stores — the fake's stand-in for Room's triggers. */
        private fun republish() {
            layoutsFlow.value = layouts.values.sortedByDescending { it.updatedAtMillis }
            selectedFlow.value = layouts.values.firstOrNull { it.isSelected }
            itemsFlow.value = itemRows.toList()
        }

        override fun observeLayouts(): Flow<List<DashboardLayoutEntity>> = layoutsFlow

        override fun observeSelectedLayout(): Flow<DashboardLayoutEntity?> = selectedFlow

        override fun observeItems(layoutId: Long): Flow<List<DashboardItemEntity>> =
            itemsFlow.map { rows -> rows.filter { it.layoutId == layoutId }.sortedBy { it.position } }

        override suspend fun layout(id: Long): DashboardLayoutEntity? = layouts[id]

        override suspend fun items(layoutId: Long): List<DashboardItemEntity> =
            itemRows.filter { it.layoutId == layoutId }.sortedBy { it.position }

        override suspend fun layoutCount(): Int = layouts.size

        override suspend fun insertLayout(layout: DashboardLayoutEntity): Long {
            val id = if (layout.id == 0L) nextId++ else layout.id
            layouts[id] = layout.copy(id = id)
            republish()
            return id
        }

        override suspend fun insertItems(items: List<DashboardItemEntity>) {
            // REPLACE on the itemId primary key: a repeated itemId overwrites rather than duplicates.
            for (item in items) {
                itemRows.removeAll { it.itemId == item.itemId }
                itemRows.add(item)
            }
            republish()
        }

        override suspend fun deleteItems(layoutId: Long) {
            itemRows.removeAll { it.layoutId == layoutId }
            republish()
        }

        override suspend fun deleteLayout(id: Long) {
            layouts.remove(id)
            republish()
        }

        override suspend fun clearSelection() {
            for (key in layouts.keys.toList()) {
                layouts[key] = layouts.getValue(key).copy(isSelected = false)
            }
            republish()
        }

        override suspend fun markSelected(id: Long, nowMillis: Long) {
            layouts[id]?.let { layouts[id] = it.copy(isSelected = true, updatedAtMillis = nowMillis) }
            republish()
        }
    }
}
