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
 * The 19 → 20 DDL, checked against the entity it has to satisfy.
 *
 * The module has no Robolectric and `room-testing` is on the instrumented classpath only, so this is
 * the same pure-JVM proxy test the dashboard migration uses: run the migration against a recorder of
 * `execSQL`, then assert the statements are additive and that the column names, affinities and
 * nullability match [SessionEventEntity]. A mismatch here is not a failed test on a device — it is a
 * Room schema exception on launch for every user who updates, which is why it is worth a test.
 */
class SessionEventMigrationTest {

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
    fun `migrates from 19 to 20`() {
        val migration = GameCoreMigrations.MIGRATION_19_20
        assertEquals(19, migration.startVersion)
        assertEquals(20, migration.endVersion)
    }

    @Test
    fun `is registered in ALL`() {
        assertTrue(
            "MIGRATION_19_20 must be appended to GameCoreMigrations.ALL or the Room builder never applies it",
            GameCoreMigrations.ALL.any { it.startVersion == 19 && it.endVersion == 20 },
        )
    }

    @Test
    fun `creates session_events with columns matching the entity`() {
        val create = recordSql(GameCoreMigrations.MIGRATION_19_20)
            .single { it.contains("CREATE TABLE") && it.contains("`session_events`") }

        assertTrue(create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(create.contains("`session_id` INTEGER NOT NULL"))
        assertTrue(create.contains("`elapsed_millis` INTEGER NOT NULL"))
        assertTrue(create.contains("`kind` TEXT NOT NULL"))
        assertTrue(create.contains("`detail` TEXT"))
        assertTrue(create.contains("`value` REAL"))
    }

    @Test
    fun `adds the session id index`() {
        val index = recordSql(GameCoreMigrations.MIGRATION_19_20)
            .single { it.contains("CREATE INDEX") }

        assertTrue(index.contains("`index_session_events_session_id`"))
        assertTrue(index.contains("ON `session_events` (`session_id`)"))
    }

    @Test
    fun `does not rewrite or drop any existing row`() {
        val statements = recordSql(GameCoreMigrations.MIGRATION_19_20)

        assertEquals(2, statements.size)
        statements.forEach { sql ->
            assertTrue("expected additive DDL, got: $sql", sql.startsWith("CREATE "))
            assertFalse(sql.contains("DROP", ignoreCase = true))
            assertFalse(sql.contains("ALTER", ignoreCase = true))
        }
    }
}
