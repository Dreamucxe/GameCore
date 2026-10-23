package com.gamecore.data.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginGrantStore
import com.gamecore.core.plugin.PluginNamespace
import com.gamecore.core.plugin.icon.AppIconSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a plugin's grant, its enabled flag, the global kill switch, the selected theme and the
 * plugin's own namespaced values are kept: one encrypted file, separate from GameCore's own.
 *
 * **Why a second file rather than a corner of `gamecore-settings`.** A plugin chooses its own storage
 * keys. However carefully those keys are namespaced, putting them in the same file as the app's
 * settings puts an attacker-chosen key one prefix bug away from `theme`, `allow_elevated` or
 * `trigger_action` — and puts every plugin value on the same surface as the settings a user resets,
 * backs up and exports. `gamecore-plugins` has its own file name and its own master key alias, so a
 * plugin's keys cannot collide with a setting, cannot be read through the settings store, and a
 * Keystore entry lost on one side does not take the other down with it.
 *
 * **Fail closed when the file will not open.** On a device whose Keystore is broken — a partially
 * restored backup, a vendor build with a damaged keyset — `prefs` is null. Every read then returns the
 * *safe* answer rather than the convenient one: no grant, not enabled, and the kill switch **on**.
 * That is the opposite choice from `SecurePreferenceStore`, which serves defaults, and the difference
 * is deliberate: a lost accent colour is an annoyance, while a plugin running with a grant nobody can
 * read is the one state this platform must never reach. Writes are dropped in that state, so the user
 * cannot turn the kill switch back off either — we will not act on a consent we cannot record.
 *
 * **Suspension is this run only.** It lives in an in-memory map, never in the file, because a
 * suspension answers a failure that happened in *this* process: the supervisor stopped a plugin after
 * repeated failures a minute ago. Persisting it would strand a plugin that is perfectly fine after a
 * reboot — and leave the user with a dead row and no obvious way to revive it. The enabled flag, the
 * kill switch and the selected theme id *are* persisted, because each of those is a decision the user
 * made rather than an observation about one run.
 *
 * **Quarantine is persisted.** It is the stronger, deliberate stop: the user (or the supervisor after
 * consecutive failures) has decided these bytes must not load again until explicitly released. It is
 * stored under its own key prefix, so it cannot collide with a grant, an enabled flag or a plugin value
 * and it survives a restart. [isSuspended] and [isQuarantined] stay separate so the manager can say
 * which of the two stopped a plugin.
 *
 * All parsing and all key/value validation belong to [PluginGrantCodec]; this class holds bytes and
 * nothing else, so the rules are testable without a device.
 */
@Singleton
class SecurePluginGrantStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PluginGrantStore {

    /**
     * Null when the encrypted file cannot be opened. `by lazy` rather than eager, because opening it
     * is Keystore work plus a keyset decrypt — on a first launch, key generation in the TEE — and Hilt
     * builds singletons on whichever thread first asks for one, which for a ViewModel dependency is
     * the main thread.
     */
    private val prefs: SharedPreferences? by lazy { openPreferences() }

    /**
     * Suspended plugin id to the detail the manager shows. A concurrent map because the supervisor
     * records a suspension from whichever thread the failing call was on, while the registry reads it
     * on its IO dispatcher.
     */
    private val suspensions = ConcurrentHashMap<String, String>()

    /**
     * Opens the file off the main thread. Idempotent — `by lazy` is synchronised, so a second caller
     * waits rather than repeating the Keystore work.
     */
    suspend fun preload() = withContext(io) {
        prefs
        Unit
    }

    // ------------------------------------------------------------------------------------ grants

    override fun grantFor(pluginId: String): PluginGrant =
        PluginGrantCodec.decode(pluginId, readString(PREFIX_GRANT + pluginId))

    override fun saveGrant(grant: PluginGrant) {
        edit { putString(PREFIX_GRANT + grant.pluginId, PluginGrantCodec.encode(grant)) }
    }

    override fun clearGrant(pluginId: String) {
        edit { remove(PREFIX_GRANT + pluginId) }
    }

    // ------------------------------------------------------------------------------------ flags

    /**
     * False when there is no record.
     *
     * An absent entry means the plugin was never switched on, which is the state a bundled plugin is
     * in on first launch — it has no install step that could have written a flag. Defaulting to true
     * would have the app ship three plugins already running on a grant the user never gave.
     */
    override fun isEnabled(pluginId: String): Boolean =
        prefs?.getBoolean(PREFIX_ENABLED + pluginId, false) ?: false

    override fun setEnabled(pluginId: String, enabled: Boolean) {
        edit { putBoolean(PREFIX_ENABLED + pluginId, enabled) }
    }

    override fun isSuspended(pluginId: String): Boolean = suspensions.containsKey(pluginId)

    override fun suspend(pluginId: String, detail: String) {
        suspensions[pluginId] = detail
    }

    override fun clearSuspension(pluginId: String) {
        suspensions.remove(pluginId)
    }

    /** What stopped the plugin, for the row that would otherwise just look dead. Null when running. */
    fun suspensionDetail(pluginId: String): String? = suspensions[pluginId]

    // ------------------------------------------------------------------------------- quarantine

    /**
     * True when the user or the supervisor has persistently stopped this plugin.
     *
     * A missing record is false. On a broken Keystore [prefs] is null, so this also reads false; that
     * is safe because the same failure makes every plugin disabled and the kill switch on, so nothing
     * can run in the state where quarantine cannot be read.
     */
    override fun isQuarantined(pluginId: String): Boolean =
        prefs?.getBoolean(PREFIX_QUARANTINE + pluginId, false) ?: false

    /**
     * Records the quarantine and its reason in one edit.
     *
     * The reason is plain host text from the supervisor or a fixed string from the manager; it is
     * sanitised by the registry before it is drawn. A null reason removes any stale detail so a later
     * user-initiated quarantine cannot inherit a previous failure's explanation.
     */
    override fun quarantine(pluginId: String, detail: String?) {
        edit {
            putBoolean(PREFIX_QUARANTINE + pluginId, true)
            if (detail == null) {
                remove(PREFIX_QUARANTINE_DETAIL + pluginId)
            } else {
                putString(PREFIX_QUARANTINE_DETAIL + pluginId, detail)
            }
        }
    }

    /** Removes the quarantine and its reason. The plugin stays switched off until the user enables it. */
    override fun releaseQuarantine(pluginId: String) {
        edit {
            remove(PREFIX_QUARANTINE + pluginId)
            remove(PREFIX_QUARANTINE_DETAIL + pluginId)
        }
    }

    /** Why it was quarantined, or null when the user quarantined it without a reason. */
    override fun quarantineDetail(pluginId: String): String? =
        readString(PREFIX_QUARANTINE_DETAIL + pluginId)

    /** On a broken Keystore this reads true, and [setSystemKillSwitch] cannot turn it off. */
    override fun isSystemKillSwitchOn(): Boolean =
        prefs?.getBoolean(KEY_KILL_SWITCH, false) ?: true

    override fun setSystemKillSwitch(on: Boolean) {
        edit { putBoolean(KEY_KILL_SWITCH, on) }
    }

    // ------------------------------------------------------------------------------------ theme

    /** The plugin id whose theme the user selected, or null for GameCore's own. */
    fun selectedThemePluginId(): String? = readString(KEY_SELECTED_THEME)

    /**
     * Records the selection, or clears it when [pluginId] is null.
     *
     * Only the id is stored, never the parsed theme: the theme has to be re-read and re-validated on
     * every start anyway, and a cached copy of a plugin's colours would outlive the plugin being
     * removed.
     */
    fun setSelectedThemePluginId(pluginId: String?) {
        edit {
            if (pluginId == null) remove(KEY_SELECTED_THEME) else putString(KEY_SELECTED_THEME, pluginId)
        }
    }

    // ------------------------------------------------------------------------------------- icon

    /**
     * The slot the user picked by hand, or [AppIconSlot.FALLBACK] when they never have.
     *
     * The icon has two layers where the theme has one. A plugin may *ask* for an icon
     * ([selectedIconPluginId]), and the user may *choose* one directly; the plugin's choice wins
     * while it is in force, and this is what the app falls back to when it is not. Keeping the
     * user's own choice rather than overwriting it means uninstalling the theme plugin restores the
     * icon they picked, not the stock one.
     */
    fun selectedIconSlot(): AppIconSlot =
        AppIconSlot.fromId(readString(KEY_SELECTED_ICON_SLOT)) ?: AppIconSlot.FALLBACK

    /**
     * Records the user's own choice.
     *
     * An unknown id is dropped rather than stored: [selectedIconSlot] would ignore it on the way
     * back out, and a value nothing can interpret is not worth persisting.
     */
    fun setSelectedIconSlot(slot: AppIconSlot) {
        edit { putString(KEY_SELECTED_ICON_SLOT, slot.id) }
    }

    /** The plugin id whose icon the user accepted, or null when no plugin is supplying one. */
    fun selectedIconPluginId(): String? = readString(KEY_SELECTED_ICON_PLUGIN)

    /** [setSelectedThemePluginId]'s shape exactly: the id only, never the parsed request. */
    fun setSelectedIconPluginId(pluginId: String?) {
        edit {
            if (pluginId == null) remove(KEY_SELECTED_ICON_PLUGIN) else putString(KEY_SELECTED_ICON_PLUGIN, pluginId)
        }
    }

    // ------------------------------------------------------------------------------------ values

    /**
     * A plugin's stored value, truncated to the current cap on the way out.
     *
     * Truncating on *read* as well as on write is the same reasoning
     * [TextSanitizer][com.gamecore.core.common.TextSanitizer] gives for applying itself at both
     * boundaries: a value already in the file predates any given version of this code, so the cap in
     * force today has to be applied to what was written yesterday.
     */
    override fun readValue(qualifiedKey: String): String? {
        if (PluginGrantCodec.budgetGroupOf(qualifiedKey) == null) return null
        return readString(PREFIX_VALUE + qualifiedKey)?.take(PluginGrantCodec.MAX_VALUE_LENGTH)
    }

    /**
     * Stores a value, or removes it when [value] is null.
     *
     * Three ways a write is dropped, and all three are silent because the interface returns nothing: a
     * key that is not a well-formed namespaced key, a value past [PluginGrantCodec.MAX_VALUE_LENGTH],
     * and a *new* key in a namespace already holding [PluginGrantCodec.MAX_KEYS_PER_PLUGIN] of them.
     * A refused write is visible where a plugin's refused calls already are — the audit trail the
     * facade writes — rather than as an exception a host would have to catch.
     *
     * Overwriting an existing key is always allowed, so a plugin at its ceiling can still keep its
     * existing state current instead of being frozen by its own last write.
     */
    override fun writeValue(qualifiedKey: String, value: String?) {
        val group = PluginGrantCodec.budgetGroupOf(qualifiedKey) ?: return
        val storageKey = PREFIX_VALUE + qualifiedKey
        if (value == null) {
            edit { remove(storageKey) }
            return
        }
        if (!PluginGrantCodec.isAcceptableValue(value)) return

        val existing = allKeys()
        if (storageKey !in existing) {
            // Counting means decrypting the key set, which is why this is only done for a key the file
            // does not already hold: a plugin updating a value it owns pays nothing for the ceiling.
            val held = existing.count { it.startsWith(PREFIX_VALUE + group) }
            if (held >= PluginGrantCodec.MAX_KEYS_PER_PLUGIN) return
        }
        edit { putString(storageKey, value) }
    }

    /** Removes every value key inside [pluginId]'s namespace, in one edit. */
    override fun clearNamespace(pluginId: String) {
        val prefix = PREFIX_VALUE + PluginNamespace.of(pluginId)
        val doomed = allKeys().filter { it.startsWith(prefix) }
        if (doomed.isEmpty()) return
        edit { doomed.forEach { remove(it) } }
    }

    // ------------------------------------------------------------------------------------ internals

    private fun readString(key: String): String? = runCatching { prefs?.getString(key, null) }.getOrNull()

    /**
     * Applies [block] to an editor, or does nothing when the file is not open.
     *
     * `apply()` rather than `commit()`: every write here is a user action whose next read comes from
     * the registry's in-memory flows, so there is nothing to gain from blocking the caller on disk —
     * and a plugin's stored value is not worth a synchronous write on the thread a game is being
     * rendered on.
     */
    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs?.edit() ?: return
        editor.block()
        editor.apply()
    }

    /**
     * Every key in the file, or empty if it cannot be read.
     *
     * `all` decrypts the whole map, so it is used only on the two paths that genuinely need the key
     * set — a first write into a namespace, and clearing one.
     */
    private fun allKeys(): Set<String> = runCatching { prefs?.all?.keys.orEmpty() }.getOrNull().orEmpty()

    private fun openPreferences(): SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private companion object {
        /** Deliberately not `gamecore-settings`. See the class comment. */
        const val FILE_NAME = "gamecore-plugins"

        /** Its own alias too, so losing the plugin keyset does not lose the user's settings. */
        const val MASTER_KEY_ALIAS = "gamecore_plugins_master_key"

        // Mutually exclusive key prefixes: none is a prefix of another, so a plugin id or a
        // namespaced key can never be read as an entry of a different kind. Quarantine has two keys
        // (the flag and its reason), and both are removed together.
        const val PREFIX_GRANT = "grant."
        const val PREFIX_ENABLED = "enabled."
        const val PREFIX_VALUE = "value."
        const val PREFIX_QUARANTINE = "quarantine."
        const val PREFIX_QUARANTINE_DETAIL = "quarantine_detail."

        const val KEY_KILL_SWITCH = "kill_switch"
        const val KEY_SELECTED_THEME = "selected_theme"
        const val KEY_SELECTED_ICON_SLOT = "selected_icon_slot"
        const val KEY_SELECTED_ICON_PLUGIN = "selected_icon_plugin"
    }
}
