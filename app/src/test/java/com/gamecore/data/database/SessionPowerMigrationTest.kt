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
 * The 24 → 25 DDL, checked against the entity it has to satisfy.
 *
 * Same pure-JVM proxy test as [SessionEventMigrationTest]: the module has no Robolectric and
 * `room-testing` is on the instrumented classpath only, so the migration runs against a recorder of
 * `execSQL` and the statements are asserted directly. A mismatch here is not a failed test on a
 * device — it is a Room schema exception on launch for every user who updates.
 *
 * The two columns are the FPS-per-watt report's raw material. What matters is that they are additive,
 * nullable, and have no default: an older sample has no such reading, and a default of zero would
 * read as a measured no-draw.
 */
class SessionPowerMigrationTest {

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
    fun `migrates from 24 to 25`() {
        val migration = GameCoreMigrations.MIGRATION_24_25
        assertEquals(24, migration.startVersion)
        assertEquals(25, migration.endVersion)
    }

    @Test
    fun `is registered in ALL`() {
        assertTrue(
            "MIGRATION_24_25 must be appended to GameCoreMigrations.ALL or the Room builder never applies it",
            GameCoreMigrations.ALL.any { it.startVersion == 24 && it.endVersion == 25 },
        )
    }

    @Test
    fun `adds the current and voltage columns as nullable integers with no default`() {
        val statements = recordSql(GameCoreMigrations.MIGRATION_24_25)

        assertEquals(2, statements.size)
        assertEquals(
            "ALTER TABLE `session_samples` ADD COLUMN `current_microamps` INTEGER",
            statements[0],
        )
        assertEquals(
            "ALTER TABLE `session_samples` ADD COLUMN `voltage_millivolts` INTEGER",
            statements[1],
        )
        statements.forEach { sql ->
            assertFalse("a default would invent a reading for older samples: $sql", sql.contains("DEFAULT"))
            assertFalse("not null would make the old rows unmigratable: $sql", sql.contains("NOT NULL"))
        }
    }

    @Test
    fun `does not rewrite or drop any existing row`() {
        recordSql(GameCoreMigrations.MIGRATION_24_25).forEach { sql ->
            assertTrue("expected additive DDL, got: $sql", sql.startsWith("ALTER TABLE `session_samples` ADD COLUMN"))
            assertFalse(sql.contains("DROP", ignoreCase = true))
            assertFalse(sql.contains("UPDATE", ignoreCase = true))
        }
    }
}
