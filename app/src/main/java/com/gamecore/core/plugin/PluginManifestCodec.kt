package com.gamecore.core.plugin

import com.gamecore.core.common.TextSanitizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses and validates `manifest.json`, the one required entry in every `.gcplugin`.
 *
 * This is the format's trust boundary. Everything downstream treats a [PluginManifest]
 * as safe, so everything a hostile or broken file could carry is caught here: a
 * `format` tag that is not ours, an unknown top-level key, a permission that does not
 * exist or is reserved, a scope on a permission that has none, a version that is not
 * a positive integer, text long enough to break a layout. None of these throw — each
 * is a typed [Result.Rejected] with a reason the install screen and the developer
 * tools can show verbatim.
 *
 * **Unknown keys are rejected, not ignored.** A forward-compatible reader that
 * skipped unknown keys would let a package built against a newer API load with its
 * new meanings silently dropped, which is a worse failure than refusing it — the
 * plugin would appear to work while doing less than it claims. The API version is
 * the forward-compatibility mechanism; the key set is exact for a given API.
 *
 * Pure `org.json` and the plugin models — no Android — so every rejection below is a
 * JVM unit test, exactly as [com.gamecore.core.backup.BackupCodec] is.
 */
object PluginManifestCodec {

    /** The envelope tag. A manifest whose `format` is not exactly this is not ours. */
    const val FORMAT = "gamecore.plugin"

    /** Caps on text, matched to where each string is drawn. */
    private const val MAX_ID_LENGTH = 128
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_DEVELOPER_LENGTH = 64
    private const val MAX_DESCRIPTION_LENGTH = 512
    private const val MAX_VERSION_NAME_LENGTH = 32
    private const val MAX_CHANGELOG_LENGTH = 2_048

    /** A ceiling on list walks so a pathological file cannot be scanned end to end. */
    private const val MAX_PERMISSIONS = 32
    private const val MAX_SCOPES_PER_PERMISSION = 32
    private const val MAX_DECLARED_SECTIONS = PluginSectionCount

    /** The exact set of keys a manifest may carry. Any other key is a rejection. */
    private val KNOWN_KEYS = setOf(
        "format", "id", "name", "version", "versionName", "api",
        "minHostVersion", "developer", "description", "permissions",
        "declares", "changelog",
    )

    private val KNOWN_PERMISSION_KEYS = setOf("id", "scopes")

    // ------------------------------------------------------------------------------------ result

    sealed interface Result {
        data class Parsed(val manifest: PluginManifest) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    /** Why a manifest was refused. Each maps to a line the UI and SDK can show. */
    enum class Reason {
        EMPTY,
        MALFORMED,
        WRONG_FORMAT,
        UNKNOWN_KEY,
        MISSING_FIELD,
        BAD_ID,
        BAD_VERSION,
        BAD_API,
        UNKNOWN_PERMISSION,
        RESERVED_PERMISSION,
        BAD_SCOPE,
        UNKNOWN_SECTION,
        TOO_MANY_ENTRIES,
    }

    // ------------------------------------------------------------------------------------ decode

    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)

        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        if (root.optString("format") != FORMAT) {
            return Result.Rejected(Reason.WRONG_FORMAT, "format must be \"$FORMAT\"")
        }

        // Reject unknown top-level keys before reading anything, so a newer-API field
        // never loads with its meaning dropped.
        val keys = root.keys().asSequence().toList()
        keys.firstOrNull { it !in KNOWN_KEYS }?.let {
            return Result.Rejected(Reason.UNKNOWN_KEY, "unknown key \"$it\"")
        }

        for (required in listOf("id", "name", "version", "api", "minHostVersion")) {
            if (!root.has(required)) {
                return Result.Rejected(Reason.MISSING_FIELD, "missing \"$required\"")
            }
        }

        // id — validated against the package grammar, not merely sanitised. A string
        // that is not a well-formed id is not an id that needs cleaning.
        val rawId = root.optString("id").trim()
        val id = TextSanitizer.validatePackageName(rawId)
        if (id == null || id.length > MAX_ID_LENGTH) {
            return Result.Rejected(Reason.BAD_ID, "id must be a package-style name")
        }

        val version = root.optInt("version", -1)
        if (version < 1) return Result.Rejected(Reason.BAD_VERSION, "version must be a positive integer")

        val api = root.optInt("api", -1)
        if (api < 1) return Result.Rejected(Reason.BAD_API, "api must be a positive integer")

        val minHostVersion = root.optInt("minHostVersion", -1)
        if (minHostVersion < 1) {
            return Result.Rejected(Reason.BAD_VERSION, "minHostVersion must be a positive integer")
        }

        val name = TextSanitizer.sanitizeName(root.optString("name"), MAX_NAME_LENGTH)
        if (name.isEmpty()) return Result.Rejected(Reason.MISSING_FIELD, "name is empty after sanitising")

        val versionName = TextSanitizer.sanitizeName(
            root.optString("versionName", version.toString()), MAX_VERSION_NAME_LENGTH,
        )
        val developer = TextSanitizer.sanitizeName(root.optString("developer", "Unknown"), MAX_DEVELOPER_LENGTH)
        val description = TextSanitizer.sanitizeMultiline(root.optString("description"), MAX_DESCRIPTION_LENGTH)
        val changelog = TextSanitizer.sanitizeMultiline(root.optString("changelog"), MAX_CHANGELOG_LENGTH)

        val permissions = when (val p = parsePermissions(root.optJSONArray("permissions"))) {
            is PermParse.Ok -> p.list
            is PermParse.Err -> return p.rejected
        }

        val declares = when (val d = parseDeclares(root.optJSONArray("declares"))) {
            is SectionParse.Ok -> d.set
            is SectionParse.Err -> return d.rejected
        }

        return Result.Parsed(
            PluginManifest(
                id = id,
                name = name,
                version = version,
                versionName = versionName,
                api = api,
                minHostVersion = minHostVersion,
                developer = developer,
                description = description,
                permissions = permissions,
                declares = declares,
                changelog = changelog,
            ),
        )
    }

    // ------------------------------------------------------------------------------------ permissions

    private sealed interface PermParse {
        data class Ok(val list: List<RequestedPermission>) : PermParse
        data class Err(val rejected: Result.Rejected) : PermParse
    }

    private fun parsePermissions(array: JSONArray?): PermParse {
        if (array == null) return PermParse.Ok(emptyList())
        if (array.length() > MAX_PERMISSIONS) {
            return PermParse.Err(Result.Rejected(Reason.TOO_MANY_ENTRIES, "too many permissions"))
        }
        val seen = LinkedHashMap<PluginPermission, RequestedPermission>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i)
                ?: // Also accept a bare string permission with no scopes.
                array.optString(i).takeIf { it.isNotBlank() }?.let { JSONObject().put("id", it) }
                ?: return PermParse.Err(Result.Rejected(Reason.UNKNOWN_PERMISSION, "permission $i is not an object or name"))

            entry.keys().asSequence().firstOrNull { it !in KNOWN_PERMISSION_KEYS }?.let {
                return PermParse.Err(Result.Rejected(Reason.UNKNOWN_KEY, "unknown permission key \"$it\""))
            }

            val permId = entry.optString("id").trim()
            if (PluginPermission.isReserved(permId)) {
                return PermParse.Err(
                    Result.Rejected(Reason.RESERVED_PERMISSION, "\"$permId\" is reserved and cannot be granted"),
                )
            }
            val permission = PluginPermission.fromId(permId)
                ?: return PermParse.Err(Result.Rejected(Reason.UNKNOWN_PERMISSION, "unknown permission \"$permId\""))

            val scopes = parseScopes(entry.optJSONArray("scopes"))
                ?: return PermParse.Err(Result.Rejected(Reason.TOO_MANY_ENTRIES, "too many scopes"))

            // A scope list on a permission that has no scopes is a mistake, not a
            // no-op: refuse it so the developer learns the permission is unscoped.
            if (scopes.isNotEmpty() && !permissionIsScoped(permission)) {
                return PermParse.Err(
                    Result.Rejected(Reason.BAD_SCOPE, "\"$permId\" does not take scopes"),
                )
            }
            // READ_APPROVED_DATA scopes must each be a known DataScope.
            if (permission == PluginPermission.READ_APPROVED_DATA) {
                scopes.firstOrNull { DataScope.fromId(it) == null }?.let {
                    return PermParse.Err(Result.Rejected(Reason.BAD_SCOPE, "unknown data scope \"$it\""))
                }
            }

            // Last declaration of a permission wins, but is deduplicated so the grant
            // list has one row per permission.
            seen[permission] = RequestedPermission(permission, scopes)
        }
        return PermParse.Ok(seen.values.toList())
    }

    private fun parseScopes(array: JSONArray?): Set<String>? {
        if (array == null) return emptySet()
        if (array.length() > MAX_SCOPES_PER_PERMISSION) return null
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val s = array.optString(i).trim().lowercase()
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private fun permissionIsScoped(p: PluginPermission): Boolean =
        p == PluginPermission.READ_APPROVED_DATA || p == PluginPermission.MODIFY_SETTINGS

    // ------------------------------------------------------------------------------------ declares

    private sealed interface SectionParse {
        data class Ok(val set: Set<PluginSection>) : SectionParse
        data class Err(val rejected: Result.Rejected) : SectionParse
    }

    private fun parseDeclares(array: JSONArray?): SectionParse {
        if (array == null) return SectionParse.Ok(emptySet())
        if (array.length() > MAX_DECLARED_SECTIONS) {
            return SectionParse.Err(Result.Rejected(Reason.TOO_MANY_ENTRIES, "too many declared sections"))
        }
        val out = LinkedHashSet<PluginSection>()
        for (i in 0 until array.length()) {
            val s = array.optString(i).trim()
            val section = PluginSection.fromId(s)
                ?: return SectionParse.Err(Result.Rejected(Reason.UNKNOWN_SECTION, "unknown section \"$s\""))
            out += section
        }
        return SectionParse.Ok(out)
    }
}

/** The number of declarable sections, as a compile-time constant for the cap above. */
private const val PluginSectionCount = 9
