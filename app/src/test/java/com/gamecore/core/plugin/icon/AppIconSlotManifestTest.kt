package com.gamecore.core.plugin.icon

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The launcher aliases in `AndroidManifest.xml`, read as text and checked against [AppIconSlot].
 *
 * This is the one test in the icon feature that has to read a file rather than call code, because
 * the thing being tested *is* the file: a slot is only real if a launcher component exists for it,
 * and `AppIconApplier` addresses those components by a name it builds from the enum. Nothing in the
 * build joins the two, so a slot added to the enum without an alias — or an alias that went missing
 * from the manifest — would compile, ship, and fail silently at runtime for whoever picked it.
 *
 * It also pins the invariant the applier maintains at runtime: **exactly one** alias is enabled in
 * the manifest. If two were, a fresh install would show two GameCore icons; if none were, a device
 * whose component state was cleared would show none.
 *
 * JUnit4 idiom (`:app` module): `assertEquals(message, expected, actual)` — the message is FIRST.
 */
class AppIconSlotManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    // :app unit tests run with the module dir as cwd.
    private val manifest = File("src/main/AndroidManifest.xml")
    private val res = File("src/main/res")

    private fun document() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(manifest)

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    private fun aliases(): List<Element> {
        val nodes = document().getElementsByTagName("activity-alias")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** The alias a slot addresses: the application id followed by the slot's suffix. */
    private fun AppIconSlot.aliasName(): String = componentSuffix

    @Test
    fun every_slot_has_an_alias_that_points_at_main() {
        val byName = aliases().associateBy { it.attr("name") }
        for (slot in AppIconSlot.entries) {
            val name = slot.aliasName()
            val alias = byName[name]
            assertTrue("manifest has an activity-alias for ${slot.id} at $name", alias != null)
            assertEquals(
                "the ${slot.id} alias targets MainActivity",
                ".MainActivity",
                alias!!.attr("targetActivity"),
            )
            assertEquals("the ${slot.id} alias is exported", "true", alias.attr("exported"))
            assertTrue(
                "the ${slot.id} alias has an icon, got \"${alias.attr("icon")}\"",
                alias.attr("icon").startsWith("@mipmap/"),
            )
        }
    }

    @Test
    fun there_is_exactly_one_alias_per_slot_and_no_orphans() {
        val aliasNames = aliases().map { it.attr("name") }.toSet()
        val slotNames = AppIconSlot.entries.map { it.aliasName() }.toSet()
        assertEquals("aliases and slots are the same set", slotNames, aliasNames)
    }

    @Test
    fun exactly_one_alias_is_enabled_and_it_is_the_default() {
        val enabled = aliases().filter { it.attr("enabled") == "true" }
        assertEquals("exactly one alias enabled at install", 1, enabled.size)
        assertEquals(
            "the default slot is the enabled one",
            AppIconSlot.DEFAULT.aliasName(),
            enabled.single().attr("name"),
        )
        for (slot in AppIconSlot.entries - AppIconSlot.DEFAULT) {
            val alias = aliases().single { it.attr("name") == slot.aliasName() }
            assertEquals("${slot.id} starts disabled", "false", alias.attr("enabled"))
        }
    }

    @Test
    fun every_alias_is_a_launcher_entry_and_main_activity_is_not() {
        for (alias in aliases()) {
            val actions = alias.getElementsByTagName("action")
            val categories = alias.getElementsByTagName("category")
            val actionNames = (0 until actions.length).map { (actions.item(it) as Element).attr("name") }
            val categoryNames = (0 until categories.length).map { (categories.item(it) as Element).attr("name") }
            assertTrue("${alias.attr("name")} carries MAIN", "android.intent.action.MAIN" in actionNames)
            assertTrue(
                "${alias.attr("name")} carries LAUNCHER",
                "android.intent.category.LAUNCHER" in categoryNames,
            )
        }

        // If MainActivity kept a LAUNCHER filter of its own, the phone would show the default icon
        // twice — one entry the picker cannot switch away from and one it can.
        val activities = document().getElementsByTagName("activity")
        val main = (0 until activities.length)
            .map { activities.item(it) as Element }
            .single { it.attr("name") == ".MainActivity" }
        val mainCategories = main.getElementsByTagName("category")
        val mainCategoryNames =
            (0 until mainCategories.length).map { (mainCategories.item(it) as Element).attr("name") }
        assertTrue(
            "MainActivity must not declare LAUNCHER itself",
            "android.intent.category.LAUNCHER" !in mainCategoryNames,
        )
    }

    @Test
    fun every_alias_icon_resolves_to_a_shipped_adaptive_icon() {
        for (alias in aliases()) {
            val icon = alias.attr("roundIcon").ifEmpty { alias.attr("icon") }
            assertTrue("${alias.attr("name")} names a mipmap, not \"$icon\"", icon.startsWith("@mipmap/"))
            val name = icon.removePrefix("@mipmap/")

            // minSdk is 26, so the adaptive wrapper in mipmap-anydpi-v26 is the file the launcher
            // actually loads; a resource in another density bucket alone would not be enough.
            val adaptive = File(res, "mipmap-anydpi-v26/$name.xml")
            assertTrue("mipmap-anydpi-v26/$name.xml exists", adaptive.isFile)
            val body = adaptive.readText()
            for (layer in listOf("background", "foreground", "monochrome")) {
                val layerName = "${name}_$layer"
                assertTrue("$name declares a $layer layer", "@drawable/$layerName" in body)
                assertTrue("res/drawable/$layerName.xml exists", File(res, "drawable/$layerName.xml").isFile)
            }
        }
    }

    @Test
    fun stored_ids_and_component_suffixes_are_distinct_and_well_formed() {
        val ids = AppIconSlot.entries.map { it.id }
        val suffixes = AppIconSlot.entries.map { it.componentSuffix }
        assertEquals("slot ids are unique", ids.size, ids.toSet().size)
        assertEquals("component suffixes are unique", suffixes.size, suffixes.toSet().size)
        for (id in ids) {
            assertEquals("id \"$id\" is lowercase so a plugin spelling it differently still resolves",
                id, id.lowercase())
        }
        for (suffix in suffixes) {
            assertTrue(
                "suffix \"$suffix\" addresses a nested class under .icon",
                suffix.matches(Regex("^\\.icon\\.[A-Za-z][A-Za-z0-9]*$")),
            )
        }
    }
}
