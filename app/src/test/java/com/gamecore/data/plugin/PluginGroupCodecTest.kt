package com.gamecore.data.plugin

import com.gamecore.core.plugin.PluginGroup
import com.gamecore.core.plugin.PluginGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a custom group, proved on the JVM.
 *
 * The rules are few but each one rules out a specific bad screen: a hand-edited value pretending to
 * be an automatic group, a malformed entry crashing the manager, a name rendered unsanitised, or a
 * duplicate id producing two chips that filter to different sets. The store around this is a
 * preference entry and needs a device; the document is pure and is tested here.
 */
class PluginGroupCodecTest {

    private fun group(
        id: String = "custom.one",
        name: String = "Daily drivers",
        pluginIds: Set<String> = setOf("com.example.a"),
    ): PluginGroup = PluginGroup(id = id, name = name, pluginIds = pluginIds, automatic = false)

    @Test
    fun `round trips custom groups, their order and overlapping membership`() {
        val groups = listOf(
            group(
                id = "custom.first",
                name = "Daily drivers",
                pluginIds = setOf("com.example.a", "com.example.b"),
            ),
            group(
                id = "custom.second",
                name = "Night mode",
                pluginIds = setOf("com.example.b"),
            ),
        )

        val decoded = PluginGroupCodec.decode(PluginGroupCodec.encode(groups))

        assertEquals(listOf("Daily drivers", "Night mode"), decoded.map { it.name })
        assertEquals(listOf("custom.first", "custom.second"), decoded.map { it.id })
        assertEquals(setOf("com.example.a", "com.example.b"), decoded[0].pluginIds)
        assertEquals(setOf("com.example.b"), decoded[1].pluginIds)
        assertTrue(decoded.none { it.automatic })
    }

    @Test
    fun `a stored id cannot claim to be automatic`() {
        val raw = """
            {"groups":[
              {"id":"auto.section.theme","name":"Fake","pluginIds":["com.example.a"]},
              {"id":"custom.real","name":"Real","pluginIds":["com.example.a"]}
            ]}
        """.trimIndent()

        val decoded = PluginGroupCodec.decode(raw)

        assertEquals(listOf("custom.real"), decoded.map { it.id })
        assertTrue(decoded.none { it.automatic })
    }

    @Test
    fun `malformed and absent values read as no custom groups`() {
        val raws = listOf(
            null,
            "",
            "   ",
            "{not json",
            """{"groups":[{"id":"custom.a","name":"A"}""",
            "[]",
            """{"groups":"nope"}""",
        )
        raws.forEach { raw ->
            assertEquals(raw, emptyList<PluginGroup>(), PluginGroupCodec.decode(raw))
        }
    }

    @Test
    fun `a duplicated id is dropped rather than merged`() {
        val raw = """
            {"groups":[
              {"id":"custom.same","name":"First","pluginIds":["com.example.a"]},
              {"id":"custom.same","name":"Second","pluginIds":["com.example.b"]}
            ]}
        """.trimIndent()

        val decoded = PluginGroupCodec.decode(raw)

        assertEquals(1, decoded.size)
        assertEquals("First", decoded.single().name)
        assertEquals(setOf("com.example.a"), decoded.single().pluginIds)
    }

    @Test
    fun `names are sanitised and a blank name drops the group`() {
        val raw = """
            {"groups":[
              {"id":"custom.a","name":"  Night   Mode  ","pluginIds":[]},
              {"id":"custom.b","name":"   ","pluginIds":[]},
              {"id":"custom.c","name":"Night${'\u202E'}Mode","pluginIds":[]}
            ]}
        """.trimIndent()

        val decoded = PluginGroupCodec.decode(raw)

        assertEquals(listOf("Night Mode", "NightMode"), decoded.map { it.name })
    }

    @Test
    fun `members collapse to a set and absurd ids are dropped`() {
        val longId = "x".repeat(129)
        val raw = """
            {"groups":[
              {"id":"custom.a","name":"A","pluginIds":[
                "com.example.a","com.example.a","  com.example.b  ","","$longId"
              ]}
            ]}
        """.trimIndent()

        val decoded = PluginGroupCodec.decode(raw)

        assertEquals(setOf("com.example.a", "com.example.b"), decoded.single().pluginIds)
    }

    @Test
    fun `encoding keeps only custom groups and refuses an invalid one`() {
        val encoded = PluginGroupCodec.encode(
            listOf(
                group(id = "auto.section.theme", name = "Derived"),
                group(id = "custom.kept", name = "Kept"),
                group(id = "custom.blank", name = "   "),
            ),
        )

        val decoded = PluginGroupCodec.decode(encoded)

        assertEquals(listOf("custom.kept"), decoded.map { it.id })
        assertFalse(encoded.contains("auto.section.theme"))
        assertTrue(encoded.length < 1024 * 1024)
    }

    @Test
    fun `the codec preserves membership the registry has not narrowed yet`() {
        // Intersecting with installed ids is PluginGrouping.all's job, not the codec's: a backup
        // restored before the plugin folder has been read must not silently lose its memberships.
        val stored = group(
            id = "custom.one",
            name = "Kept",
            pluginIds = setOf("com.example.not-installed-yet"),
        )

        val decoded = PluginGroupCodec.decode(PluginGroupCodec.encode(listOf(stored)))

        assertEquals(setOf("com.example.not-installed-yet"), decoded.single().pluginIds)
        assertTrue(PluginGrouping.CUSTOM_PREFIX in decoded.single().id)
    }
}
