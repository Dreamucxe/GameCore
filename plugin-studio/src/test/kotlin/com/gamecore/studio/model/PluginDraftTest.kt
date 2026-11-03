package com.gamecore.studio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of a draft: what the editor may hold, and what it must refuse.
 *
 * A [PluginDraft] is *data*, not a package. The editor is allowed to hold a half-typed
 * id or an impossible colour while the user is still typing, so nothing here may throw
 * and nothing may be quietly repaired. What a draft owes the user instead is a list of
 * [DraftProblem]s that names **the field** that is wrong — one specific word the editor
 * can put under the right input.
 *
 * The rules restated here are the *host's* rules, transcribed, because the studio is a
 * separate app and cannot call GameCore's own codecs. A transcription is only honest if
 * the host agrees with it, and that is checked elsewhere: `PluginStudioPackageTest` in
 * `:app` runs a package composed from a draft through the real `PluginManifestCodec` and
 * `PluginThemeCodec`, and refuses an id the draft would have refused. If the two ever
 * drift apart, that test fails — not this one.
 *
 * Plain JUnit, no Android: the draft model is pure Kotlin and has to stay that way.
 */
class PluginDraftTest {

    /**
     * The one field a single-fault draft complains about.
     *
     * Asserting the exact count as well as the exact field is the point: a draft with a
     * bad id must not *also* complain about its name, or the editor would underline two
     * inputs for one mistake and the user would not know which one to fix.
     */
    private fun onlyField(draft: PluginDraft): String {
        val problems = draft.problems()
        assertEquals("expected exactly one problem, got $problems", 1, problems.size)
        return problems.single().field
    }

    // ------------------------------------------------------------------------------ the good case

    @Test
    fun `a default draft is valid`() {
        val draft = PluginDraft()
        assertTrue("the default draft reported $draft", draft.isValid)
        assertTrue("the default draft reported ${draft.problems()}", draft.problems().isEmpty())
    }

    // ------------------------------------------------------------------------------ one fault at a time

    @Test
    fun `a blank name is reported against the name field`() {
        val draft = PluginDraft(name = "   ")
        assertFalse(draft.isValid)
        assertEquals("name", onlyField(draft))
    }

    @Test
    fun `an id without a dot is reported against the id field`() {
        val draft = PluginDraft(id = "no dots")
        assertFalse(draft.isValid)
        assertEquals("id", onlyField(draft))
    }

    @Test
    fun `a version below one is reported against the version field`() {
        val draft = PluginDraft(version = 0)
        assertFalse(draft.isValid)
        assertEquals("version", onlyField(draft))
    }

    @Test
    fun `an unknown permission is reported against the permissions field`() {
        val draft = PluginDraft(permissions = setOf(PluginDraft.PERMISSION_CHANGE_UI, "read_the_mail"))
        assertFalse(draft.isValid)
        assertEquals("permissions", onlyField(draft))
    }

    @Test
    fun `a corner radius out of range is reported against the theme corner radius field`() {
        val draft = PluginDraft(theme = ThemeDraft(cornerRadiusDp = ThemeDraft.MAX_CORNER_DP + 1))
        assertFalse(draft.isValid)
        assertEquals("theme.cornerRadiusDp", onlyField(draft))
    }

    @Test
    fun `a corner radius below zero is reported against the theme corner radius field`() {
        val draft = PluginDraft(theme = ThemeDraft(cornerRadiusDp = -1))
        assertEquals("theme.cornerRadiusDp", onlyField(draft))
    }

    // ------------------------------------------------------------------------------ the id grammar

    @Test
    fun `the id grammar accepts a package-style id`() {
        assertTrue(PluginDraft.ID_PATTERN.matches("com.example.mytheme"))
        assertTrue(PluginDraft.ID_PATTERN.matches("com.example.my_theme"))
        assertTrue(PluginDraft.ID_PATTERN.matches("com.example.myTheme2"))
    }

    @Test
    fun `the id grammar rejects an empty segment and a leading digit`() {
        assertFalse(PluginDraft.ID_PATTERN.matches("com..example"))
        assertFalse(PluginDraft.ID_PATTERN.matches("1bad.id"))
    }

    @Test
    fun `a well-formed id is not reported as a problem`() {
        val draft = PluginDraft(id = "com.example.mytheme")
        assertTrue(draft.problems().none { it.field == "id" })
    }

    // ------------------------------------------------------------------------------ the permission list

    @Test
    fun `the known permission list has no duplicates`() {
        val ids = PluginDraft.KNOWN_PERMISSIONS.map { it.id }
        assertTrue("the known permission list is empty", ids.isNotEmpty())
        assertEquals("duplicate permission ids in $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `every known permission id is lower case with underscores`() {
        val pattern = Regex("^[a-z][a-z_]*$")
        PluginDraft.KNOWN_PERMISSIONS.forEach { permission ->
            assertTrue(
                "permission id \"${permission.id}\" is not lower case with underscores",
                pattern.matches(permission.id),
            )
        }
    }

    @Test
    fun `every known permission carries the words the editor shows`() {
        PluginDraft.KNOWN_PERMISSIONS.forEach { permission ->
            assertTrue("permission \"${permission.id}\" has no title", permission.title.isNotBlank())
            assertTrue("permission \"${permission.id}\" has no rationale", permission.rationale.isNotBlank())
        }
    }

    @Test
    fun `the default draft asks for a permission the studio knows`() {
        val asked = PluginDraft().permissions
        assertTrue(asked.isNotEmpty())
        asked.forEach { id ->
            assertTrue(
                "the default draft asks for \"$id\", which the studio does not know",
                PluginDraft.KNOWN_PERMISSIONS.any { it.id == id },
            )
        }
    }
}
