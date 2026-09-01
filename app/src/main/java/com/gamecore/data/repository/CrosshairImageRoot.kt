package com.gamecore.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The directory imported crosshair images are kept in, and the one place a picked `Uri` is opened.
 *
 * A one-method class rather than a `Context` in [CrosshairImageDirectory], so that class has no
 * Android dependency beyond `Bitmap` and its decode-and-re-encode logic is testable on the JVM with
 * a temporary directory.
 *
 * `filesDir`, not `cacheDir`: the platform reclaims cache directories under storage pressure without
 * asking, and an image referenced by a stored database row disappearing would leave a preset whose
 * overlay renders nothing.
 */
@Singleton
class CrosshairImageRoot @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun directory(): File = File(context.filesDir, DIRECTORY_NAME).apply {
        if (!exists()) mkdirs()
    }

    /**
     * Opens what the document picker returned, or null if it cannot be read.
     *
     * Here rather than in the crosshair screen so §25's rule holds — the UI hands the repository a
     * `Uri` and gets a stored path or a null back, and no composable touches a `ContentResolver`.
     * Null covers a provider that has died, a permission that was not granted for the URI, and a
     * `SecurityException` from a URI the user did not actually pick; the caller reports all three the
     * same way, because "that file could not be read" is the whole of what it can tell the user.
     */
    fun openSource(uri: Uri): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    private companion object {
        const val DIRECTORY_NAME = "crosshairs"
    }
}
