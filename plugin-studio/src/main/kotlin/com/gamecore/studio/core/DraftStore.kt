package com.gamecore.studio.core

import android.content.Context
import com.gamecore.studio.model.PluginDraft
import org.json.JSONObject
import java.util.UUID

/**
 * A draft as the project list sees it: a stable storage id, the name shown in the list,
 * the draft itself, and when it was last saved.
 *
 * [id] is the key the project is stored under, not necessarily [PluginDraft.id]. Keeping
 * them separate lets a project survive the author changing the package id, and lets
 * [DraftStore.duplicate] make a copy without renaming the source.
 */
data class StudioProject(
    val id: String,
    val name: String,
    val draft: PluginDraft,
    val updatedAt: Long,
)

/**
 * The studio's project list, backed by one `SharedPreferences` string per project.
 *
 * The storage is intentionally thin. [DraftCodec] owns the draft's shape; this class only
 * wraps it with the project id, display name and save time, and keeps a second key for the
 * project that was open last. Every mutation is synchronised because the editor may save
 * from a background thread while the list is being read.
 *
 * A record that cannot be parsed is skipped rather than thrown: a corrupted preference
 * must never make the project list or the app unusable. The next save of that project
 * overwrites the damaged value.
 */
class DraftStore(context: Context) {

    private val preferences =
        context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Every readable project, most recently saved first. */
    fun list(): List<StudioProject> = synchronized(lock) {
        preferences.all.asSequence()
            .filter { (key, value) -> key.startsWith(PROJECT_PREFIX) && value is String }
            .mapNotNull { (_, value) -> decodeProject(value as String) }
            .sortedWith(compareByDescending<StudioProject> { it.updatedAt }.thenBy { it.id })
            .toList()
    }

    /** One project, or `null` when the id is unknown or the stored record is corrupt. */
    fun load(id: String): StudioProject? = synchronized(lock) {
        read(id)
    }

    /**
     * Store [draft] and return the project that now holds it.
     *
     * With no [id], the draft's package id is the project id, so saving the same plugin
     * again updates its project instead of accumulating copies. A blank package id falls
     * back to a random id. [name] defaults to the existing project name, then the draft's
     * name, so renaming the theme does not rename the project behind the author's back.
     */
    fun save(draft: PluginDraft, id: String? = null, name: String? = null): StudioProject =
        synchronized(lock) {
            val projectId = id?.trim()?.takeIf { it.isNotEmpty() }
                ?: draft.id.trim().takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString()
            val existing = read(projectId)
            val projectName = name?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing?.name?.takeIf { it.isNotEmpty() }
                ?: draft.name.trim().takeIf { it.isNotEmpty() }
                ?: projectId
            val project = StudioProject(
                id = projectId,
                name = projectName,
                draft = draft,
                updatedAt = System.currentTimeMillis(),
            )
            preferences.edit()
                .putString(projectKey(projectId), encodeProject(project))
                .apply()
            project
        }

    /** Remove a project, and forget it as the last-opened one if it was. */
    fun delete(id: String) {
        synchronized(lock) {
            val editor = preferences.edit().remove(projectKey(id))
            if (preferences.getString(LAST_OPENED_KEY, null) == id) {
                editor.remove(LAST_OPENED_KEY)
            }
            editor.apply()
        }
    }

    /** Copy a project under a free `-copy` id, keeping its draft and display name. */
    fun duplicate(id: String): StudioProject? = synchronized(lock) {
        val source = read(id) ?: return@synchronized null
        var candidate = "$id-copy"
        var suffix = 2
        while (preferences.contains(projectKey(candidate))) {
            candidate = "$id-copy-${suffix++}"
        }
        save(source.draft, id = candidate, name = "${source.name} copy")
    }

    /** The project that was open last, or `null` if there is none or it was deleted. */
    fun lastOpened(): StudioProject? = synchronized(lock) {
        val id = preferences.getString(LAST_OPENED_KEY, null)
            ?: return@synchronized null
        val project = read(id)
        if (project == null) {
            // The remembered project was deleted or corrupted; do not keep pointing at it.
            preferences.edit().remove(LAST_OPENED_KEY).apply()
        }
        project
    }

    /** Remember which project the author had open. */
    fun rememberLastOpened(id: String) {
        synchronized(lock) {
            preferences.edit().putString(LAST_OPENED_KEY, id).apply()
        }
    }

    // ------------------------------------------------------------------------------------ storage

    private fun read(id: String): StudioProject? =
        preferences.getString(projectKey(id), null)?.let(::decodeProject)

    private fun encodeProject(project: StudioProject): String = JSONObject()
        .put("id", project.id)
        .put("name", project.name)
        .put("updatedAt", project.updatedAt)
        .put("draft", DraftCodec.encode(project.draft))
        .toString()

    private fun decodeProject(raw: String): StudioProject? {
        return try {
            val root = JSONObject(raw)
            val id = root.optString("id", "").trim()
            if (id.isEmpty()) return null
            val draftText = root.opt("draft")
            if (draftText !is String) return null
            val draft = DraftCodec.decode(draftText) ?: return null
            StudioProject(
                id = id,
                name = root.optString("name", id).takeIf { it.isNotBlank() } ?: id,
                draft = draft,
                updatedAt = root.optLong("updatedAt", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun projectKey(id: String): String = PROJECT_PREFIX + id

    private companion object {
        const val PREFERENCE_FILE = "plugin_studio_projects"
        const val PROJECT_PREFIX = "project:"
        const val LAST_OPENED_KEY = "last_opened"
    }
}
