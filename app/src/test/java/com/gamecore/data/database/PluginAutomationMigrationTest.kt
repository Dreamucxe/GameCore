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
 * A pure-JVM assertion test over the SQL [GameCoreMigrations.MIGRATION_16_17] emits.
 *
 * This module has no device-backed migration test to mirror: `room-testing` (and so
 * `MigrationTestHelper`) is on the `androidTest` classpath only, and there is no Robolectric on the
 * unit-test classpath, so a full open-and-apply of the migration against a real SQLite file is the
 * instrumented suite's job rather than this one's. What *can* be checked here, deterministically and
 * without a device, is the thing that actually breaks on an update: whether the migration issues
 * additive DDL that matches [PluginAutomationEventEntity] exactly, since a column that differs in
 * name, affinity or nullability from the exported `17.json` is what makes Room throw on open for
 * every user who updates.
 *
 * The migration only ever calls [SupportSQLiteDatabase.execSQL], so the "database" it runs against is
 * a [Proxy] that records each statement and does nothing else — no real engine, no Android. The
 * assertions then read those statements back: the right table and columns, the index on `pluginId`,
 * and — the invariant the whole no-`fallbackToDestructiveMigration` policy rests on — that every
 * statement is a `CREATE` naming only the new table, so this step cannot rewrite or drop a row of the
 * history a user cannot regenerate.
 *
 * This references [GameCoreMigrations.MIGRATION_16_17], which the version-17 schema change adds
 * alongside this file; it does not compile until that migration exists.
 */
class PluginAutomationMigrationTest {

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
    fun `migrates from 16 to 17`() {
        val migration = GameCoreMigrations.MIGRATION_16_17
        assertEquals(16, migration.startVersion)
        assertEquals(17, migration.endVersion)
    }

    @Test
    fun `is registered in ALL`() {
        assertTrue(
            "MIGRATION_16_17 must be appended to GameCoreMigrations.ALL or the Room builder never applies it",
            GameCoreMigrations.ALL.any { it.startVersion == 16 && it.endVersion == 17 },
        )
    }

    @Test
    fun `creates the automation events table with columns matching the entity`() {
        val create = recordSql(GameCoreMigrations.MIGRATION_16_17).single { it.contains("CREATE TABLE") }

        assertTrue(create.contains("`plugin_automation_events`"))
        assertTrue(create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(create.contains("`timestampMillis` INTEGER NOT NULL"))
        assertTrue(create.contains("`pluginId` TEXT NOT NULL"))
        assertTrue(create.contains("`operation` TEXT NOT NULL"))
        assertTrue(create.contains("`outcome` TEXT NOT NULL"))
        assertTrue(create.contains("`detail` TEXT NOT NULL"))
        // durationMillis is the one nullable column: present, but never NOT NULL.
        assertTrue(create.contains("`durationMillis` INTEGER"))
        assertFalse("durationMillis must stay nullable", create.contains("`durationMillis` INTEGER NOT NULL"))
    }

    @Test
    fun `indexes pluginId under Room's generated index name`() {
        val index = recordSql(GameCoreMigrations.MIGRATION_16_17).single { it.contains("CREATE INDEX") }

        assertTrue(index.contains("`index_plugin_automation_events_pluginId`"))
        assertTrue(index.contains("`plugin_automation_events`"))
        assertTrue(index.contains("`pluginId`"))
    }

    @Test
    fun `is additive and touches no existing table`() {
        val statements = recordSql(GameCoreMigrations.MIGRATION_16_17)

        assertTrue("migration must emit at least the table and its index", statements.size >= 2)
        for (stmt in statements) {
            val norm = stmt.trim().uppercase()
            assertTrue(
                "every statement must create something, never rewrite; got: $stmt",
                norm.startsWith("CREATE TABLE") || norm.startsWith("CREATE INDEX") ||
                    norm.startsWith("CREATE UNIQUE INDEX"),
            )
            assertTrue(
                "every statement must target only the new table; got: $stmt",
                stmt.contains("plugin_automation_events"),
            )
            assertFalse("must not alter an existing table; got: $stmt", norm.contains("ALTER "))
            assertFalse("must not drop anything; got: $stmt", norm.contains("DROP "))
        }
    }
}
