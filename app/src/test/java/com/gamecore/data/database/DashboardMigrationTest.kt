package com.gamecore.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * A pure-JVM assertion test over the SQL [GameCoreMigrations.MIGRATION_17_18] emits.
 *
 * This module has no device-backed migration test to mirror: `room-testing` (and so
 * `MigrationTestHelper`) is on the `androidTest` classpath only, and there is no Robolectric on the
 * unit-test classpath, so a full open-and-apply of the migration against a real SQLite file is the
 * instrumented suite's job rather than this one's. What *can* be checked here, deterministically and
 * without a device, is the thing that actually breaks on an update: whether the migration issues
 * additive DDL that matches [DashboardLayoutEntity] and [DashboardItemEntity] exactly, since a column
 * that differs in name, affinity or nullability from the exported `18.json` is what makes Room throw
 * on open for every user who updates.
 *
 * The migration only ever calls [SupportSQLiteDatabase.execSQL], so the "database" it runs against is
 * a [Proxy] that records each statement and does nothing else — no real engine, no Android. The
 * assertions then read those statements back: the right tables and columns, the index on `layoutId`,
 * and — the invariant the whole no-`fallbackToDestructiveMigration` policy rests on — that every
 * statement is a `CREATE` naming only the new tables, so this step cannot rewrite or drop a row of
 * the data a user cannot regenerate.
 *
 * This references [GameCoreMigrations.MIGRATION_17_18], which the version-18 schema change adds
 * alongside this file; it does not compile until that migration exists.
 */
class DashboardMigrationTest {

    /** Runs [migration] against a recording [SupportSQLiteDatabase] proxy and returns the SQL it emitted, in order. */
    private fun recordSql(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "execSQL") {
                    if (args != null && args.isNotEmpty()) statements.add(args[0] as String)
                    null
                } else {
                    // migrate() calls nothing but execSQL; this only keeps the proxy total for any
                    // Object method (toString/hashCode/…) the runtime might reach for.
                    defaultValue(method.returnType)
                }
            },
        ) as SupportSQLiteDatabase
        migration.migrate(db)
        return statements
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0.0f
        Char::class.javaPrimitiveType -> ' '
        else -> null
    }

    @Test
    fun `migrates from 17 to 18`() {
        val migration = GameCoreMigrations.MIGRATION_17_18
        assertEquals(17, migration.startVersion)
        assertEquals(18, migration.endVersion)
    }

    @Test
    fun `is registered in ALL`() {
        assertTrue(
            "MIGRATION_17_18 must be appended to GameCoreMigrations.ALL or the Room builder never applies it",
            GameCoreMigrations.ALL.any { it.startVersion == 17 && it.endVersion == 18 },
        )
    }

    @Test
    fun `creates the dashboard_layouts table with columns matching the entity`() {
        val create = recordSql(GameCoreMigrations.MIGRATION_17_18)
            .single { it.contains("CREATE TABLE") && it.contains("`dashboard_layouts`") }

        assertTrue(create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(create.contains("`name` TEXT NOT NULL"))
        assertTrue(create.contains("`schemaVersion` INTEGER NOT NULL"))
        assertTrue(create.contains("`isSelected` INTEGER NOT NULL"))
        assertTrue(create.contains("`createdAtMillis` INTEGER NOT NULL"))
        assertTrue(create.contains("`updatedAtMillis` INTEGER NOT NULL"))
    }

    @Test
    fun `creates the dashboard_items table with columns matching the entity`() {
        val create = recordSql(GameCoreMigrations.MIGRATION_17_18)
            .single { it.contains("CREATE TABLE") && it.contains("`dashboard_items`") }

        assertTrue(create.contains("`itemId` TEXT NOT NULL"))
        assertTrue(create.contains("`layoutId` INTEGER NOT NULL"))
        assertTrue(create.contains("`kind` TEXT NOT NULL"))
        assertTrue(create.contains("`pluginId` TEXT NOT NULL"))
        assertTrue(create.contains("`refId` TEXT NOT NULL"))
        assertTrue(create.contains("`position` INTEGER NOT NULL"))
        assertTrue(create.contains("`visible` INTEGER NOT NULL"))
        assertTrue(create.contains("PRIMARY KEY(`itemId`)"))
    }

    @Test
    fun `indexes layoutId under Room's generated index name`() {
        val index = recordSql(GameCoreMigrations.MIGRATION_17_18).single { it.contains("CREATE INDEX") }

        assertTrue(index.contains("`index_dashboard_items_layoutId`"))
        assertTrue(index.contains("`dashboard_items`"))
        assertTrue(index.contains("`layoutId`"))
    }

    @Test
    fun `is additive and touches no existing table`() {
        val statements = recordSql(GameCoreMigrations.MIGRATION_17_18)

        assertTrue("migration must emit at least the two tables and the index", statements.size >= 3)
        for (stmt in statements) {
            val norm = stmt.trim().uppercase()
            assertTrue(
                "every statement must create something, never rewrite; got: $stmt",
                norm.startsWith("CREATE TABLE") || norm.startsWith("CREATE INDEX") ||
                    norm.startsWith("CREATE UNIQUE INDEX"),
            )
            assertTrue(
                "every statement must target only the new tables; got: $stmt",
                stmt.contains("dashboard_layouts") || stmt.contains("dashboard_items"),
            )
            assertFalse("must not alter an existing table; got: $stmt", norm.contains("ALTER "))
            assertFalse("must not drop anything; got: $stmt", norm.contains("DROP "))
        }
    }
}
