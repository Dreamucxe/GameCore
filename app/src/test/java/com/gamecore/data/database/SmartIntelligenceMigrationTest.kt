package com.gamecore.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Pins the additive v22 → v23 DDL to the two Room entities and generated index names. */
class SmartIntelligenceMigrationTest {

    @Test
    fun `migration is registered from 22 to 23`() {
        val migration = GameCoreMigrations.MIGRATION_22_23
        assertEquals(22, migration.startVersion)
        assertEquals(23, migration.endVersion)
        assertTrue(GameCoreMigrations.ALL.any { it.startVersion == 22 && it.endVersion == 23 })
    }

    @Test
    fun `benchmark table matches nullable measurements and persisted frame windows`() {
        val create = recordSql(GameCoreMigrations.MIGRATION_22_23)
            .single { it.contains("CREATE TABLE") && it.contains("`smart_benchmarks`") }

        assertTrue(create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(create.contains("`profile` TEXT NOT NULL"))
        assertTrue(create.contains("`game_package` TEXT"))
        assertFalse(create.contains("`game_package` TEXT NOT NULL"))
        assertTrue(create.contains("`sample_count` INTEGER NOT NULL"))
        assertTrue(create.contains("`duration_ms` INTEGER NOT NULL"))
        assertTrue(create.contains("`avg_fps` REAL"))
        assertFalse(create.contains("`avg_fps` REAL NOT NULL"))
        assertTrue(create.contains("`fps_windows` TEXT NOT NULL"))
        assertTrue(create.contains("`avg_gpu` REAL"))
        assertTrue(create.contains("`peak_gpu` REAL"))
        assertTrue(create.contains("`cpu_source` TEXT"))
        assertTrue(create.contains("`gpu_source` TEXT"))
        assertTrue(create.contains("`memory_source` TEXT"))
        assertTrue(create.contains("`temperature_source` TEXT"))
        assertTrue(create.contains("`battery_source` TEXT"))
        assertTrue(create.contains("`refresh_rate_source` TEXT"))
        assertTrue(create.contains("`unavailable` TEXT NOT NULL"))
        assertTrue(create.contains("`baseline_run_id` INTEGER"))
    }

    @Test
    fun `learning table stores decisions outcomes measurements and local context`() {
        val create = recordSql(GameCoreMigrations.MIGRATION_22_23)
            .single { it.contains("CREATE TABLE") && it.contains("`smart_learning_records`") }

        assertTrue(create.contains("`action_key` TEXT NOT NULL"))
        assertTrue(create.contains("`game_package` TEXT"))
        assertTrue(create.contains("`goal` TEXT NOT NULL"))
        assertTrue(create.contains("`decision` TEXT NOT NULL"))
        assertTrue(create.contains("`outcome` TEXT NOT NULL"))
        assertTrue(create.contains("`metric_name` TEXT"))
        assertTrue(create.contains("`before_value` REAL"))
        assertTrue(create.contains("`after_value` REAL"))
        assertTrue(create.contains("`note` TEXT"))
        assertTrue(create.contains("`source_run_id` INTEGER"))
        assertTrue(create.contains("`created_at` INTEGER NOT NULL"))
    }

    @Test
    fun `migration uses Room index names and never changes an existing table`() {
        val statements = recordSql(GameCoreMigrations.MIGRATION_22_23)
        val joined = statements.joinToString("\n")
        listOf(
            "index_smart_benchmarks_started_at",
            "index_smart_benchmarks_game_package",
            "index_smart_learning_records_action_key",
            "index_smart_learning_records_game_package",
            "index_smart_learning_records_created_at",
        ).forEach { assertTrue("missing $it", joined.contains("`$it`")) }

        statements.forEach { sql ->
            val normalized = sql.trim().uppercase()
            assertTrue(normalized.startsWith("CREATE TABLE") || normalized.startsWith("CREATE INDEX"))
            assertFalse(normalized.contains("ALTER "))
            assertFalse(normalized.contains("DROP "))
            assertTrue(sql.contains("smart_benchmarks") || sql.contains("smart_learning_records"))
        }
    }

    private fun recordSql(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "execSQL") {
                    if (!args.isNullOrEmpty()) statements += args[0] as String
                    null
                } else {
                    defaultValue(method.returnType)
                }
            },
        ) as SupportSQLiteDatabase
        migration.migrate(database)
        return statements
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        Char::class.javaPrimitiveType -> ' '
        else -> null
    }
}
