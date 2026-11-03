package com.gamecore.studio.core

import com.gamecore.studio.model.ThemeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ready-made themes are examples as much as starting points: if a template cannot pass
 * the studio's own quality checks, a user has no reason to trust the checks or the template.
 */
class TemplatesTest {

    private val expectedNames = setOf(
        "Minimal",
        "AMOLED",
        "Gaming",
        "Glass",
        "Nothing-inspired",
        "Neon",
        "Material-inspired",
        "Compact",
    )

    @Test
    fun `the gallery has exactly the eight named starting points`() {
        assertEquals(8, Templates.ALL.size)
        assertEquals(expectedNames, Templates.ALL.map { it.name }.toSet())
    }

    @Test
    fun `ids are unique and byId resolves every template`() {
        assertEquals(
            "duplicate template ids: ${Templates.ALL.map { it.id }}",
            Templates.ALL.size,
            Templates.ALL.map { it.id }.toSet().size,
        )
        Templates.ALL.forEach { template ->
            assertTrue("${template.name} has a blank id", template.id.isNotBlank())
            assertEquals("byId did not return ${template.id}", template, Templates.byId(template.id))
        }
        assertNull(Templates.byId("not-a-template"))
    }

    @Test
    fun `every template is a valid theme with readable main pairs`() {
        Templates.ALL.forEach { template ->
            val theme = template.theme
            assertTrue(
                "${template.name} is not a valid ThemeDraft: ${theme.problems()}",
                theme.problems().isEmpty(),
            )
            assertTrue("${template.name} has no description", template.description.isNotBlank())
            assertTrue("${template.name} has no palette", template.palette.isNotEmpty())
            assertTrue(
                "${template.name} names an unknown font ${theme.font}",
                theme.font in ThemeDraft.KNOWN_FONTS,
            )
            assertTrue(
                "${template.name} names an unknown weight ${theme.typographyWeight}",
                theme.typographyWeight in ThemeDraft.KNOWN_WEIGHTS,
            )

            val checks = ThemeAnalysis.contrastChecks(theme)
            checks.forEach { check ->
                assertTrue(
                    "${template.name}: ${check.label} measured ${check.ratio}",
                    check.meetsAA,
                )
            }
            val bodyOnPage = checks.first { it.label == "On surface / background" }
            assertTrue(
                "${template.name}: onSurface on background measured ${bodyOnPage.ratio}",
                bodyOnPage.ratio >= 4.5,
            )
        }
    }

    @Test
    fun `every template palette is distinct and contains its key colours`() {
        assertEquals(
            "two templates share a palette",
            Templates.ALL.size,
            Templates.ALL.map { it.palette }.toSet().size,
        )
        Templates.ALL.forEach { template ->
            assertTrue(
                "${template.name}: palette does not contain the primary",
                template.theme.primary in template.palette,
            )
            assertTrue(
                "${template.name}: palette does not contain the accent",
                template.theme.accent in template.palette,
            )
        }
    }

    @Test
    fun `the glass template declares schema four and a glass block`() {
        val glass = Templates.byId("glass")
        assertNotNull("no glass template", glass)
        assertEquals(4, glass?.theme?.schema)
        assertNotNull("the glass template has no glass material", glass?.theme?.glass)
    }

    @Test
    fun `the neon template declares a glitch block on schema three or newer`() {
        val neon = Templates.byId("neon")
        assertNotNull("no neon template", neon)
        assertNotNull("the neon template has no glitch block", neon?.theme?.glitch)
        assertTrue(
            "the neon template schema ${neon?.theme?.schema} predates effects",
            (neon?.theme?.schema ?: 0) >= ThemeDraft.SCHEMA_EFFECTS,
        )
    }

    @Test
    fun `AMOLED is black and Material-inspired is a light theme`() {
        val amoled = Templates.byId("amoled")
        assertNotNull(amoled)
        assertTrue("AMOLED did not set amoled", amoled?.theme?.amoled == true)
        assertEquals(0xFF000000L, amoled?.theme?.background)

        val material = Templates.byId("material-inspired")
        assertNotNull(material)
        assertFalse("Material-inspired is dark", material?.theme?.dark == true)
    }

    @Test
    fun `compact uses a tighter scale and small corners`() {
        val compact = Templates.byId("compact")
        assertNotNull(compact)
        assertTrue("Compact uiScale is not tighter than 1", (compact?.theme?.uiScale ?: 1f) < 1f)
        assertTrue(
            "Compact corner radius is not small",
            (compact?.theme?.cornerRadiusDp ?: 48) <= 8,
        )
        assertTrue(
            "Compact type scale is not tighter than 1",
            (compact?.theme?.typographyScale ?: 1f) < 1f,
        )
    }
}
