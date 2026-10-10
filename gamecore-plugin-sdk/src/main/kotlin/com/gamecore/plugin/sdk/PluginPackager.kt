package com.gamecore.plugin.sdk

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [PluginDefinition] to a `.gcplugin` zip on disk.
 *
 * The layout matches exactly what the host loader expects: `manifest.json` at the root and
 * each section file at its declared path. Entries are written with forward-slash paths and no
 * absolute or `..` components, so the archive can never carry a path the host's zip-slip guard
 * would (rightly) reject — the SDK produces only archives that load.
 *
 * The packager intentionally does NOT re-implement validation. Parity with the host is the
 * `packagePlugin` Gradle task's job: it depends on `:app`'s test classpath so it can run the
 * produced bytes through the host's real `PluginPackageLoader`/codecs. If that wiring is
 * unavailable in a given build, the task still emits the package but prints that validation
 * was skipped, rather than silently pretending the package was checked.
 */
object PluginPackager {

    fun writeTo(output: File, definition: PluginDefinition) {
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            putEntry(zip, "manifest.json", definition.manifestJson)
            definition.sectionFiles.forEach { (path, bytes) ->
                require(isSafePath(path)) { "unsafe section path: $path" }
                putEntry(zip, path, bytes)
            }
        }
    }

    private fun putEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Mirrors the host's canonical-path rule: relative, forward-slash, no `..`, no leading slash. */
    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith("/") &&
            !path.contains("\\") &&
            path.split("/").none { it == ".." || it == "." || it.isEmpty() }
}
