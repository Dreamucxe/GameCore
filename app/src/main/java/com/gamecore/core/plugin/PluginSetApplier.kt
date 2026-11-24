package com.gamecore.core.plugin

import android.content.Context
import com.gamecore.core.diagnostics.LogKind
import com.gamecore.core.diagnostics.LogLevel
import com.gamecore.core.diagnostics.LogStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the plugin set bound to a game when it starts, and puts back exactly the switches it moved.
 *
 * ## What a set is allowed to do
 *
 * Only enable. A set never disables a plugin the user turned on, never grants a permission, never
 * releases a quarantine and never overrides a suspension. Those limits are the difference between a
 * convenience and a way to bypass the consent screen; a set that could grant would be a permission
 * system with a second, quieter door.
 *
 * ## Restoring
 *
 * The ids this applier switched on are persisted before the game runs and cleared when it stops. A
 * process that dies mid-session therefore leaves a note for the next start: [restoreLeftovers] runs at
 * the top of [com.gamecore.domain.gaming.GamingCoordinator.run] and turns them back off before the
 * detector can announce a game. Only ids the set itself enabled are in that file; a plugin the user
 * switched on by hand is never in it and is never touched.
 *
 * ## One set per game
 *
 * Enforced by [PluginRegistry.setGroupGame] and by the codec. Here, the first bound group for the
 * package is the set; a malformed store that somehow contains two cannot make the choice depend on
 * iteration order beyond the first match.
 */
@Singleton
class PluginSetApplier @Inject constructor(
    private val registry: PluginRegistry,
    @ApplicationContext context: Context,
    private val logs: LogStore,
) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** The plan the current session applied, or null when the game had no set. */
    suspend fun applyForGame(packageName: String): PluginSetPlan? = mutex.withLock {
        // A previous process may have died between switching a set on and switching it off. Clear that
        // first, even when this game has no set, so leftovers cannot accumulate across launches.
        restoreLocked()

        val group = registry.groups.value.firstOrNull {
            it.isGameSet && it.gamePackage == packageName
        } ?: return@withLock null

        // Before the first registry read finishes, the installed list is empty and every member would
        // look missing. Refusing to act is honest; persisting an empty set over the leftovers is not.
        if (!registry.isLoaded.value) return@withLock null

        val plan = PluginSetPlanner.plan(
            group = group,
            installed = registry.installed.value,
            killSwitchOn = registry.killSwitchOn.value,
        )
        plan.toEnable.forEach { id -> registry.setEnabled(id, true) }
        persist(plan.toEnable.toSet())

        logs.append(
            logs.note(
                kind = LogKind.PLUGIN,
                level = if (plan.skipped.any { it.reason.isSafetyBlock }) LogLevel.WARNING else LogLevel.INFO,
                title = "Plugin set applied: ${group.name}",
                detail = buildString {
                    appendLine("Game: $packageName")
                    appendLine(plan.summary)
                    if (plan.alreadyOn.isNotEmpty()) {
                        appendLine("Already on: ${plan.alreadyOn.joinToString()}")
                    }
                    plan.skipped.forEach { skip ->
                        appendLine("Skipped ${skip.pluginId}: ${skip.reason.label}")
                    }
                }.trimEnd(),
                packageName = packageName,
            ),
        )
        plan
    }

    /**
     * Switches off the plugins the current or previous set enabled.
     *
     * Suspends on [Mutex] with [applyForGame] so a stop cannot interleave with a start and leave a set's
     * switches half-restored. Safe to call when nothing is bound; it is a no-op then.
     */
    suspend fun restore() = mutex.withLock {
        val ids = storedIds()
        if (ids.isEmpty()) return@withLock
        if (!registry.isLoaded.value) return@withLock

        val installed = registry.installed.value.associateBy { it.id }
        val disabled = ArrayList<String>()
        ids.forEach { id ->
            if (installed[id]?.enabled == true) {
                registry.setEnabled(id, false)
                disabled += id
            }
        }
        persist(emptySet())
        if (disabled.isNotEmpty()) {
            logs.append(
                logs.note(
                    kind = LogKind.PLUGIN,
                    level = LogLevel.INFO,
                    title = "Plugin set restored",
                    detail = "Switched back off: ${disabled.joinToString()}",
                ),
            )
        }
    }

    /** Clears a set left enabled by a process that died. Called once when detection starts. */
    suspend fun restoreLeftovers() {
        mutex.withLock { restoreLocked() }
    }

    /** The set currently bound to [packageName], if any, for the manager's summary. */
    fun setForGame(packageName: String): PluginGroup? = registry.groups.value.firstOrNull {
        it.isGameSet && it.gamePackage == packageName
    }

    private suspend fun restoreLocked() {
        val ids = storedIds()
        if (ids.isEmpty() || !registry.isLoaded.value) return
        val installed = registry.installed.value.associateBy { it.id }
        ids.forEach { id ->
            if (installed[id]?.enabled == true) registry.setEnabled(id, false)
        }
        persist(emptySet())
    }

    private fun storedIds(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet()

    private fun persist(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED, HashSet(ids)).apply()
    }

    private companion object {
        const val FILE_NAME = "plugin_sets"
        const val KEY_ENABLED = "enabled_by_set"
    }
}
