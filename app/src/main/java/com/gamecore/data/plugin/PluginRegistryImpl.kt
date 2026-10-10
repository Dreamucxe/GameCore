package com.gamecore.data.plugin

import android.content.Context
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.ActiveOverlayModule
import com.gamecore.core.plugin.ActiveTheme
import com.gamecore.core.plugin.InstallOutcome
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.LoadedPlugin
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginManifestCodec
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginRuntimeState
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.PluginSections
import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.overlay.PluginOverlayModule
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.theme.PluginThemeCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place plugins are read from disk, and the only implementation of [PluginRegistry].
 *
 * Everything here follows from one rule: **a plugin is data that is re-validated every time it is
 * read.** Nothing is trusted because it was trusted before.
 *
 *  1. **A sideloaded plugin is stored as the original `.gcplugin` bytes**, not as a parsed manifest
 *     row, and every [refresh] re-runs [PluginPackageLoader.load] over those bytes. A file edited on
 *     disk between two launches — by a backup tool, a root shell, a half-finished write — is therefore
 *     rejected on the next start instead of being trusted from a cached manifest that no longer
 *     describes it.
 *  2. **A bundled plugin goes through the same codecs**, parsed out of `assets/plugins/<dir>/` rather
 *     than a zip. "We shipped it" is not a security property, and a bundled example that skipped
 *     validation would be the one package in the system nobody checks.
 *  3. **The plugin id is validated before it becomes a file name.** The id *is* the file name, so an
 *     id that escapes the directory is a path traversal with the user's own storage on the other side
 *     of it. [TextSanitizer.validatePackageName] decides, and a failure is a refusal, never a cleanup.
 *  4. **No method throws.** Every risky step — a disk read, an unzip, a parse — is wrapped and
 *     degrades to a value: an absent plugin, an empty section list, a typed [InstallOutcome]. An
 *     exception escaping a plugin operation would take down the host, which is the single outcome this
 *     whole design exists to prevent. The wrapping is deliberately around the *non-suspending* calls
 *     only, so a coroutine cancellation still propagates as cancellation rather than being swallowed
 *     as a plugin failure.
 *  5. **Plugin text is sanitised where it enters [InstalledPlugin]**, not where it is drawn. The
 *     manifest codec already sanitised it once; doing it again here is the same both-boundaries rule
 *     [TextSanitizer] states for itself, and it means no composable has to remember.
 *  6. **Nothing is read in the constructor.** Hilt builds singletons on whichever thread asks first,
 *     which for a ViewModel dependency is the main thread, and opening the encrypted file is Keystore
 *     work. The flows start empty with [isLoaded] false until someone calls [refresh].
 *
 * The flows are the host's view of all of this; the bytes never leave this class. A ViewModel holding
 * the [PluginRegistry] interface can list, toggle, grant and remove — it cannot reach a package, a
 * shell or the grant store.
 */
@Singleton
class PluginRegistryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SecurePluginGrantStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PluginRegistry {

    private val installedState = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    override val installed: StateFlow<List<InstalledPlugin>> = installedState.asStateFlow()

    private val activeThemeState = MutableStateFlow<ActiveTheme?>(null)
    override val activeTheme: StateFlow<ActiveTheme?> = activeThemeState.asStateFlow()

    private val overlayModulesState = MutableStateFlow<List<ActiveOverlayModule>>(emptyList())
    override val activeOverlayModules: StateFlow<List<ActiveOverlayModule>> = overlayModulesState.asStateFlow()

    /**
     * Starts **on**, before the first read.
     *
     * Fail closed: until the store has been read we do not know whether the user revoked plugin
     * access, and the answer that cannot be wrong in a damaging direction is "revoked". [isLoaded] is
     * what a screen gates its content on, so this is not a value the user sees as a claim — it is the
     * value the gate would get if anything asked before the first [refresh].
     */
    private val killSwitchState = MutableStateFlow(true)
    override val killSwitchOn: StateFlow<Boolean> = killSwitchState.asStateFlow()

    private val loadedState = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = loadedState.asStateFlow()

    /**
     * Pure policy, no state, no Android — so it is constructed here rather than injected.
     *
     * The theme path asks this the same question every broker call asks, instead of reading the grant
     * map itself, because a second way of deciding "may this plugin act" is a second way of getting it
     * wrong.
     */
    private val gate = PluginPermissionGate()

    /** Serialises the read-modify-publish sequences; without it two toggles can publish out of order. */
    private val lock = Mutex()

    /** Plugin id to its record. Held under [lock]; holds no bytes and no parsed sections. */
    private var records: Map<String, PluginRecord> = emptyMap()

    /**
     * One parsed theme, keyed by plugin id and the digest it came from.
     *
     * [publish] re-resolves the active theme on every state change, and resolving it means opening the
     * plugin's package and parsing its theme — so without this, flipping an unrelated switch would
     * cost an unzip. Keying on the digest is what keeps it honest: a plugin whose bytes changed is
     * re-parsed rather than served from memory.
     */
    private var themeCache: Pair<String, PluginTheme>? = null

    /**
     * Parsed overlay modules, keyed by plugin id and the digest they came from.
     *
     * [themeCache]'s reasoning, as a map because modules compose where themes arbitrate — there can be
     * three in force at once, and [publish] re-resolves all of them on every state change, so without
     * this a user flipping an unrelated switch would pay three unzips. Keyed on the digest for the same
     * reason: a plugin whose bytes changed is re-parsed rather than served from memory.
     */
    private var moduleCache: Map<String, PluginOverlayModule> = emptyMap()

    // ------------------------------------------------------------------------------------ reading

    override suspend fun refresh() {
        withContext(io) {
            lock.withLock {
                // Pay the Keystore cost here, on the IO dispatcher, so the first grant a UI thread
                // reads back is a memory read rather than a keyset decrypt.
                store.preload()
                reload()
            }
        }
    }

    /**
     * Rescans both sources and republishes.
     *
     * Bundled is scanned first and wins a duplicate id, so a sideloaded package cannot shadow a
     * bundled plugin by naming itself after one — [install] refuses that id outright, and this is the
     * second line for a file that arrived some other way.
     */
    private fun reload() {
        val scanned = LinkedHashMap<String, PluginRecord>()
        for (record in readBundled() + readSideloaded()) {
            val id = record.manifest.id
            if (!scanned.containsKey(id)) scanned[id] = record
        }
        records = scanned
        publish()
        loadedState.value = true
    }

    /** Recomputes every flow from [records] plus the store. No package is opened except for a theme. */
    private fun publish() {
        val killSwitch = store.isSystemKillSwitchOn()
        val plugins = records.values.map { it.toInstalledPlugin() }.sortedWith(DISPLAY_ORDER)
        killSwitchState.value = killSwitch
        installedState.value = plugins
        activeThemeState.value = resolveActiveTheme(plugins, killSwitch)
        overlayModulesState.value = resolveOverlayModules(plugins, killSwitch)
    }

    private fun PluginRecord.toInstalledPlugin(): InstalledPlugin {
        val id = manifest.id
        return InstalledPlugin(
            manifest = sanitised(manifest),
            sha256 = sha256,
            origin = origin,
            enabled = store.isEnabled(id),
            suspended = store.isSuspended(id),
            // The supervisor builds this string from a failure detail that can contain plugin text,
            // and it is drawn in a list row like any other untrusted name.
            suspensionDetail = store.suspensionDetail(id)?.let { TextSanitizer.sanitizeName(it, MAX_DETAIL_LENGTH) },
            grant = store.grantFor(id),
        )
    }

    /**
     * Re-sanitises the four strings the UI draws, with the same caps the manifest codec applied.
     *
     * A name that sanitises away to nothing falls back to the id, because §24's honest-UI rule cuts
     * both ways: a row the user cannot identify is no better than a row that lies.
     */
    private fun sanitised(manifest: PluginManifest): PluginManifest = manifest.copy(
        name = TextSanitizer.sanitizeName(manifest.name, MAX_NAME_LENGTH).ifEmpty { manifest.id },
        developer = TextSanitizer.sanitizeName(manifest.developer, MAX_DEVELOPER_LENGTH),
        description = TextSanitizer.sanitizeMultiline(manifest.description, MAX_DESCRIPTION_LENGTH),
        changelog = TextSanitizer.sanitizeMultiline(manifest.changelog, MAX_CHANGELOG_LENGTH),
    )

    // ------------------------------------------------------------------------------------ bundled

    private fun readBundled(): List<PluginRecord> {
        val directories = runCatching { context.assets.list(ASSET_ROOT) }.getOrNull() ?: emptyArray()
        return directories.mapNotNull { runCatching { readBundledPlugin(it) }.getOrNull() }
    }

    /**
     * Reads one `assets/plugins/<directory>/` as a plugin, or null.
     *
     * Null for every failure, including a declared section whose file is missing — the same structural
     * disagreement [PluginPackageLoader] rejects a zip for. A broken bundled plugin simply does not
     * appear, because there is nothing the user could do about one, and `BundledPluginsTest` is the
     * gate that stops a broken one shipping in the first place.
     */
    private fun readBundledPlugin(directory: String): PluginRecord? {
        val base = "$ASSET_ROOT/$directory"
        val manifestBytes = readAsset("$base/${PluginPackageLoader.MANIFEST_ENTRY}") ?: return null
        val manifest = when (val decoded = PluginManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))) {
            is PluginManifestCodec.Result.Parsed -> decoded.manifest
            is PluginManifestCodec.Result.Rejected -> return null
        }
        if (TextSanitizer.validatePackageName(manifest.id) == null) return null

        val entries = mutableListOf(PluginPackageLoader.MANIFEST_ENTRY to manifestBytes)
        for (section in manifest.declares) {
            val name = bundledEntryName(section)
            entries += name to (readAsset("$base/$name") ?: return null)
        }
        return PluginRecord(manifest = manifest, sha256 = digestOf(entries), source = PluginSource.Asset(base))
    }

    /**
     * Where a bundled plugin's section file lives.
     *
     * A bundled plugin is loose files inside the APK rather than a zip, so a section whose package
     * prefix is a directory (`rules/`) is carried as one file named for the section (`rules.json`).
     * The alternative — shipping real zips in `assets/` — would mean the plugins GameCore itself
     * ships are the only ones a developer cannot read with a text editor.
     */
    private fun bundledEntryName(section: PluginSection): String {
        val prefix = section.entryPrefix
        return if (prefix.endsWith("/")) prefix.trimEnd('/') + ".json" else prefix
    }

    /**
     * SHA-256 over the manifest bytes and each section's bytes, in sorted path order.
     *
     * A bundled plugin has no package to hash, and a plugin may never state its own digest, so this is
     * computed over exactly what was read. Sorted by path so the value does not depend on the order
     * the asset manager happened to list files in — a digest that changes between two runs of the same
     * build would be worse than none, because the install screen and the marketplace both treat it as
     * an identity. Lowercase hex, as [PluginPackageLoader] writes it.
     */
    private fun digestOf(entries: List<Pair<String, ByteArray>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.first }.forEach { digest.update(it.second) }
        val bytes = digest.digest()
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    /** One asset, under the loader's own per-entry ceiling. Null if absent or over the cap. */
    private fun readAsset(path: String): ByteArray? = runCatching {
        context.assets.open(path).use { readCapped(it, PluginPackageLoader.MAX_ENTRY_BYTES) }
    }.getOrNull()

    /**
     * Reads at most [maxBytes], returning null the moment that is exceeded.
     *
     * The declared length of anything outside the APK's own files is not trusted, so the cap is
     * applied to what is actually read rather than to a size field.
     */
    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------------------------ sideloaded

    private fun readSideloaded(): List<PluginRecord> {
        val directory = pluginDirectory() ?: return emptyList()
        val files = runCatching { directory.listFiles() }.getOrNull() ?: emptyArray()
        return files
            .filter { it.isFile && it.name.endsWith(PACKAGE_EXTENSION) }
            .mapNotNull { runCatching { readPackage(it) }.getOrNull() }
    }

    /**
     * Re-validates one stored package.
     *
     * The file name has to match the id inside the manifest, because the name is the identity
     * [remove] deletes by: a file whose manifest says something else would leave `remove` deleting the
     * wrong plugin, or nothing at all. A package that fails the loader is dropped from the list
     * rather than kept as a broken row — the bytes stay on disk, so nothing is destroyed by a bad read.
     */
    private fun readPackage(file: File): PluginRecord? {
        val loaded = loadPackage(file) ?: return null
        if (file.name != packageFileName(loaded.manifest.id)) return null
        return PluginRecord(
            manifest = loaded.manifest,
            sha256 = loaded.sha256,
            source = PluginSource.Package(file),
        )
    }

    private fun loadPackage(file: File): LoadedPlugin? {
        // Checked before the read, not after: the point of the cap is not holding the bytes at all.
        if (file.length() > PluginPackageLoader.MAX_PACKAGE_BYTES) return null
        return when (val result = PluginPackageLoader.load(file.readBytes())) {
            is PluginPackageLoader.Result.Loaded -> result.plugin
            is PluginPackageLoader.Result.Rejected -> null
        }
    }

    private fun pluginDirectory(): File? {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (directory.exists()) return directory
        return if (runCatching { directory.mkdirs() }.getOrDefault(false)) directory else null
    }

    /** The id is the file name, which is why [install] validates it as a package name first. */
    private fun packageFileName(pluginId: String): String = "$pluginId$PACKAGE_EXTENSION"

    // ------------------------------------------------------------------------------------ install

    override suspend fun install(bytes: ByteArray, origin: PluginOrigin): InstallOutcome =
        withContext(io) { lock.withLock { installLocked(bytes, origin) } }

    private fun installLocked(bytes: ByteArray, origin: PluginOrigin): InstallOutcome {
        if (origin == PluginOrigin.BUNDLED) {
            // A bundled plugin's bytes are covered by the app's own signature because they are inside
            // the APK. Writing a file and labelling it bundled would claim a property it cannot have,
            // and would hand the user a row they are told cannot be removed.
            return InstallOutcome.StorageFailed("a bundled plugin ships inside the app and is not installed")
        }

        val loaded = when (val result = PluginPackageLoader.load(bytes)) {
            is PluginPackageLoader.Result.Rejected ->
                return InstallOutcome.Rejected(result.reason, result.detail.takeIf { it.isNotEmpty() })
            is PluginPackageLoader.Result.Loaded -> result.plugin
        }

        val manifest = loaded.manifest
        if (!manifest.compatibility.isRunnable) return InstallOutcome.Incompatible(manifest.compatibility)

        // The manifest codec already validated the id; this is the check that matters for *storage*,
        // stated where the id turns into a path.
        val id = TextSanitizer.validatePackageName(manifest.id)
            ?: return InstallOutcome.Rejected(
                PluginPackageLoader.Reason.UNSAFE_PATH,
                "the plugin id cannot be used as a file name",
            )

        if (records[id]?.origin == PluginOrigin.BUNDLED) {
            return InstallOutcome.Rejected(
                PluginPackageLoader.Reason.DUPLICATE_ENTRY,
                "a bundled plugin already uses this id",
            )
        }

        val replaced = installedState.value.firstOrNull { it.id == id }
        val directory = pluginDirectory()
            ?: return InstallOutcome.StorageFailed("the plugin directory could not be created")

        when (val written = writePackage(File(directory, packageFileName(id)), bytes)) {
            is WriteResult.Written -> Unit
            is WriteResult.Failed -> return InstallOutcome.StorageFailed(written.detail)
        }

        // Installing is deliberately not consenting: no grant, switched off, and any suspension left
        // over from a previous version of the same id cleared so the new bytes start from a clean
        // state the user is asked about on the detail screen.
        store.clearGrant(id)
        store.setEnabled(id, false)
        store.clearSuspension(id)

        // Rescan rather than append the manifest we just parsed, so what the caller is handed is what
        // the loader reads back off disk. A half-written file fails here instead of appearing
        // installed.
        reload()

        val stored = installedState.value.firstOrNull { it.id == id }
            ?: return InstallOutcome.StorageFailed("the stored package could not be read back")
        return InstallOutcome.Installed(stored, replaced)
    }

    private fun writePackage(file: File, bytes: ByteArray): WriteResult = try {
        file.writeBytes(bytes)
        WriteResult.Written
    } catch (error: IOException) {
        // Storage full, the directory removed under us, or a filesystem that refused the write.
        WriteResult.Failed(error.javaClass.simpleName)
    } catch (error: SecurityException) {
        // A device whose app-private directory is not writable at all.
        WriteResult.Failed(error.javaClass.simpleName)
    }

    override suspend fun remove(pluginId: String): Boolean = withContext(io) {
        lock.withLock {
            val record = records[pluginId] ?: return@withLock false
            val file = when (val source = record.source) {
                // Nothing on disk to delete: a bundled plugin can only be switched off.
                is PluginSource.Asset -> return@withLock false
                is PluginSource.Package -> source.file
            }
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (!deleted && file.exists()) return@withLock false

            // The grant, the namespace and the flags go with the file. Leaving a grant behind would
            // mean reinstalling the same id silently inherits consent the user gave to other bytes.
            store.clearGrant(pluginId)
            store.clearNamespace(pluginId)
            store.setEnabled(pluginId, false)
            store.clearSuspension(pluginId)
            if (store.selectedThemePluginId() == pluginId) store.setSelectedThemePluginId(null)

            reload()
            true
        }
    }

    // ------------------------------------------------------------------------------------ state

    override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        withContext(io) {
            lock.withLock {
                if (!records.containsKey(pluginId)) return@withLock
                store.setEnabled(pluginId, enabled)
                // Switching a plugin back on is the user's "try again": the supervisor's suspension is
                // a this-run observation, and keeping it would leave them toggling a switch that does
                // nothing. §32 — no dead taps.
                if (enabled) store.clearSuspension(pluginId)
                publish()
            }
        }
    }

    override suspend fun grant(pluginId: String, grant: PluginGrant) {
        withContext(io) {
            lock.withLock {
                val record = records[pluginId] ?: return@withLock
                // Stored under the record's id rather than the one the grant object carries, so a
                // grant built for one plugin cannot be written into another's slot.
                store.saveGrant(grant.copy(pluginId = record.manifest.id))
                publish()
            }
        }
    }

    override suspend fun setActiveTheme(pluginId: String?) {
        withContext(io) {
            lock.withLock {
                store.setSelectedThemePluginId(pluginId)
                publish()
                // A selection that cannot produce a theme is not kept: it would sit in the file
                // looking like the user's choice while the app draws its own colours.
                if (pluginId != null && activeThemeState.value == null) {
                    store.setSelectedThemePluginId(null)
                }
            }
        }
    }

    override suspend fun resetToDefaultTheme() {
        withContext(io) {
            lock.withLock {
                store.setSelectedThemePluginId(null)
                // Set directly rather than re-derived through publish(): null needs nothing from the
                // plugin — not its file, not its parse, not even its presence — which is exactly what
                // makes this the one theme operation that cannot fail. It still works when the
                // encrypted file will not open, because the flow is in memory.
                activeThemeState.value = null
            }
        }
    }

    override suspend fun setKillSwitch(on: Boolean) {
        withContext(io) {
            lock.withLock {
                store.setSystemKillSwitch(on)
                // Re-read rather than mirrored: on a device where the store cannot persist, the switch
                // stays on and the UI shows the truth instead of the request.
                publish()
            }
        }
    }

    override suspend fun clearSuspension(pluginId: String) {
        withContext(io) {
            lock.withLock {
                store.clearSuspension(pluginId)
                publish()
            }
        }
    }

    // ------------------------------------------------------------------------------------ sections

    /**
     * Parses one plugin's sections on demand.
     *
     * The record is taken under the lock and the parsing happens outside it, because a parse is a disk
     * read and an unzip while the lock also serialises every switch flip — holding it across a parse
     * would make a plugin's own content the thing that delays the user turning it off.
     */
    override suspend fun sectionsOf(pluginId: String): PluginSections? = withContext(io) {
        val record = lock.withLock { records[pluginId] } ?: return@withContext null
        PluginSections(
            theme = if (PluginSection.THEME in record.manifest.declares) readTheme(record) else null,
            rules = if (PluginSection.RULES in record.manifest.declares) readRules(record) else emptyList(),
            overlayModule =
                if (PluginSection.PILL in record.manifest.declares) readOverlayModule(record) else null,
        )
    }

    /**
     * The theme in force, or null.
     *
     * Null is returned for a plugin that is missing, not runnable on this build, disabled, suspended,
     * without a declared theme, without `change_ui` in its *grant*, or whose `theme.json` no longer
     * parses — and also while the kill switch is on, which the interface does not list but which
     * follows from what the switch promises: a plugin's colours are the most visible involvement it
     * has in the app, and a kill switch that leaves them in place has not stopped plugin involvement.
     *
     * That every one of those degrades to null is what makes "reset to default" unfailing: null is
     * always reachable, needs nothing from the plugin, and is the app's own theme.
     */
    private fun resolveActiveTheme(plugins: List<InstalledPlugin>, killSwitch: Boolean): ActiveTheme? {
        val selectedId = store.selectedThemePluginId() ?: return null
        val record = records[selectedId] ?: return null
        val plugin = plugins.firstOrNull { it.id == selectedId } ?: return null
        if (!plugin.isActive) return null
        if (!gate.isAllowed(plugin.runtimeState(killSwitch), PluginPermission.CHANGE_UI)) return null
        if (PluginSection.THEME !in record.manifest.declares) return null
        val theme = cachedTheme(record) ?: return null
        return ActiveTheme(pluginId = plugin.id, pluginName = plugin.name, theme = theme)
    }

    private fun cachedTheme(record: PluginRecord): PluginTheme? {
        val key = "${record.manifest.id}@${record.sha256}"
        themeCache?.let { if (it.first == key) return it.second }
        val theme = readTheme(record) ?: return null
        themeCache = key to theme
        return theme
    }

    private fun readTheme(record: PluginRecord): PluginTheme? {
        val raw = sectionEntries(record, PluginSection.THEME).firstOrNull() ?: return null
        return when (val decoded = PluginThemeCodec.decode(raw.toString(Charsets.UTF_8))) {
            is PluginThemeCodec.Result.Parsed -> decoded.theme
            is PluginThemeCodec.Result.Rejected -> null
        }
    }

    /**
     * Every overlay module allowed to draw right now, in registry order, capped at what the overlay
     * layer can physically show.
     *
     * [resolveActiveTheme]'s filter chain, with two differences that both follow from a module being a
     * readout rather than a repaint. It runs over *every* plugin instead of one stored selection,
     * because modules compose where themes arbitrate. And it carries one check past the permission: a
     * module that displays a figure also needs the data scope that figure comes from, since the right
     * to draw a box over a game is not the right to put a temperature in it.
     *
     * The cap is applied by stopping the loop rather than taking a prefix, so a module past the limit
     * costs nothing — it is never unzipped and never parsed. Which modules were dropped is not reported
     * from here; the manager derives that by comparing this list against the plugins that declare a
     * `pill` section, which keeps this function a filter and nothing else.
     *
     * With the kill switch on the cache is cleared rather than kept warm. A user reaching for that
     * switch is telling the app something is wrong, and the honest response is to drop the parsed bytes
     * and re-read them if they turn it back on, rather than hold a plugin's content in memory because
     * doing so would make re-enabling fractionally faster.
     */
    private fun resolveOverlayModules(
        plugins: List<InstalledPlugin>,
        killSwitch: Boolean,
    ): List<ActiveOverlayModule> {
        if (killSwitch) {
            moduleCache = emptyMap()
            return emptyList()
        }
        val out = ArrayList<ActiveOverlayModule>(PluginOverlayModule.MAX_MODULES_ON_SCREEN)
        val cache = HashMap<String, PluginOverlayModule>(PluginOverlayModule.MAX_MODULES_ON_SCREEN)
        for (plugin in plugins) {
            if (out.size >= PluginOverlayModule.MAX_MODULES_ON_SCREEN) break
            if (!plugin.isActive) continue
            val record = records[plugin.id] ?: continue
            if (PluginSection.PILL !in record.manifest.declares) continue
            val state = plugin.runtimeState(killSwitch)
            if (!gate.isAllowed(state, PluginPermission.CREATE_OVERLAY)) continue
            val key = "${plugin.id}@${record.sha256}"
            val module = moduleCache[key] ?: readOverlayModule(record) ?: continue
            cache[key] = module
            if (!scopesGranted(state, module)) continue
            out += ActiveOverlayModule(pluginId = plugin.id, pluginName = plugin.name, module = module)
        }
        moduleCache = cache
        return out
    }

    /**
     * True when the user has approved every data scope the module's figures come from.
     *
     * [PluginOverlayModule.requiredScopes] is empty for a module that shows no figures, so a decorative
     * one passes without the loop running — a plugin that only wants to label a corner of the screen
     * never has to ask for device readings. The question is put to [gate] per scope rather than read off
     * the grant directly, so a module's figures travel the same code path the broker uses and cannot be
     * read through a route the rest of the system would refuse.
     *
     * One missing scope drops the whole module rather than blanking the one figure inside it. A readout
     * showing an em dash where a temperature should be is indistinguishable from a device with no
     * thermal sensor, and a user who declined thermals is owed the clearer answer: the module is not on
     * screen, and the plugin's own row says which permission would put it there.
     */
    private fun scopesGranted(state: PluginRuntimeState, module: PluginOverlayModule): Boolean =
        module.requiredScopes.all { scope ->
            gate.isAllowed(state, PluginPermission.READ_APPROVED_DATA, scope)
        }

    /**
     * The module a plugin declares, or null when it has none or `pill.json` no longer parses.
     *
     * [readTheme]'s shape, and null for the same reason: an unparseable section degrades to the plugin
     * simply not contributing, never to an error the overlay has to render. The codec normalises what it
     * returns, so a module reaching the service already has its anchor and opacity in range.
     */
    private fun readOverlayModule(record: PluginRecord): PluginOverlayModule? {
        val raw = sectionEntries(record, PluginSection.PILL).firstOrNull() ?: return null
        return when (val decoded = PluginOverlayCodec.decode(raw.toString(Charsets.UTF_8))) {
            is PluginOverlayCodec.Result.Parsed -> decoded.module
            is PluginOverlayCodec.Result.Rejected -> null
        }
    }

    /**
     * Every rule the plugin carries, capped in aggregate.
     *
     * [RulesCodec] caps the rules in one file, but a package may carry many files under `rules/`, so
     * without a cap across the section a plugin could ship two hundred files to get around a
     * thirty-two rule limit. A file that fails to parse is skipped rather than failing the section:
     * one bad rules file must not silence the ones that are fine.
     */
    private fun readRules(record: PluginRecord): List<AutomationRule> {
        val out = ArrayList<AutomationRule>()
        for (raw in sectionEntries(record, PluginSection.RULES)) {
            when (val decoded = RulesCodec.decode(raw.toString(Charsets.UTF_8))) {
                is RulesCodec.Result.Parsed -> out += decoded.rules
                is RulesCodec.Result.Rejected -> Unit
            }
            if (out.size >= MAX_RULES_PER_PLUGIN) break
        }
        return out.take(MAX_RULES_PER_PLUGIN)
    }

    /** The raw bytes of a section, from the APK or from the stored package. Empty on any failure. */
    private fun sectionEntries(record: PluginRecord, section: PluginSection): List<ByteArray> =
        runCatching {
            when (val source = record.source) {
                is PluginSource.Asset ->
                    listOfNotNull(readAsset("${source.base}/${bundledEntryName(section)}"))
                is PluginSource.Package -> {
                    val loaded = loadPackage(source.file)
                    if (loaded == null) emptyList() else loaded.pathsUnder(section).mapNotNull { loaded.entry(it) }
                }
            }
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------------------------ models

    /**
     * What is kept about one plugin between refreshes: enough to list it and to find it again.
     *
     * Deliberately not its bytes and not its parsed sections. A list row needs a name, a version and a
     * switch; only the detail screen and the runtime need content, and holding every plugin's parsed
     * sections for the sake of a row is how a plugin system starts costing the host its own memory.
     */
    private data class PluginRecord(
        val manifest: PluginManifest,
        val sha256: String,
        val source: PluginSource,
    ) {
        /** Derived, so a record can never claim an origin its source contradicts. */
        val origin: PluginOrigin
            get() = when (source) {
                is PluginSource.Asset -> PluginOrigin.BUNDLED
                is PluginSource.Package -> PluginOrigin.SIDELOADED
            }
    }

    private sealed interface PluginSource {
        /** Loose files in the APK under `assets/plugins/<name>/`. */
        data class Asset(val base: String) : PluginSource

        /** The original `.gcplugin` bytes in the app's own files directory. */
        data class Package(val file: File) : PluginSource
    }

    private sealed interface WriteResult {
        data object Written : WriteResult
        data class Failed(val detail: String) : WriteResult
    }

    private companion object {
        /** `assets/plugins/` — the bundled plugins' root. */
        const val ASSET_ROOT = "plugins"

        /** `filesDir/plugins/` — one `.gcplugin` file per sideloaded plugin, named from its id. */
        const val DIRECTORY_NAME = "plugins"

        const val PACKAGE_EXTENSION = ".gcplugin"

        const val READ_CHUNK_BYTES = 64 * 1024

        // The manifest codec's own caps, re-applied where the strings enter InstalledPlugin.
        const val MAX_NAME_LENGTH = 64
        const val MAX_DEVELOPER_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 512
        const val MAX_CHANGELOG_LENGTH = 2_048

        /** A suspension detail is one line under a list row, not a paragraph. */
        const val MAX_DETAIL_LENGTH = 120

        /** Across the whole `rules/` section, not per file. */
        const val MAX_RULES_PER_PLUGIN = 32

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Bundled first, then sideloaded, each alphabetical by name with the id as the tie-break.
         *
         * Total and independent of the store, so the list does not reshuffle when a switch is flipped
         * — a row moving under the finger that just touched it is how a user turns off the wrong
         * plugin. The origin is mapped explicitly rather than taken from the enum's ordinal, so
         * reordering [PluginOrigin] cannot silently reorder the manager.
         */
        private val DISPLAY_ORDER = compareBy<InstalledPlugin>(
            { if (it.origin == PluginOrigin.BUNDLED) 0 else 1 },
            { it.name.lowercase() },
            { it.id },
        )
    }
}
