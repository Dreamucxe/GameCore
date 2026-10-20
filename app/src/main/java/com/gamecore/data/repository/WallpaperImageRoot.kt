package com.gamecore.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the user's imported wallpaper lives, and the only door a `Uri` comes through.
 *
 * Mirrors [CrosshairImageRoot] deliberately, including the division of labour it exists to enforce:
 * the UI hands this layer a `Uri` and gets a stored path or a null back, and no composable ever
 * touches a `ContentResolver`. Null covers a provider that has died, a URI the user did not actually
 * pick, and a `SecurityException` on a permission that was never granted — the caller reports all
 * three the same way, because "that image could not be read" is the whole of what it can honestly
 * say.
 *
 * The directory is app-private ([Context.filesDir]), which is the reason the import re-encodes rather
 * than copies: nothing here is reachable by another app, and no content URI is retained once the
 * import returns. A wallpaper the user picked from a cloud-backed gallery keeps working after the
 * provider revokes the URI, because GameCore is reading its own file by then.
 */
@Singleton
class WallpaperImageRoot @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun directory(): File = File(context.filesDir, DIRECTORY_NAME).apply {
        if (!exists()) mkdirs()
    }

    /**
     * Opens what the picker returned, or null if it cannot be read.
     *
     * Called twice per import, deliberately. [WallpaperImageStore] reads the image's bounds before it
     * decodes, and a `ContentResolver` stream is not reliably markable — resetting one is exactly the
     * kind of thing that works on one provider and hands back a truncated image on another, so the
     * second pass opens a fresh stream instead of rewinding the first.
     */
    fun openSource(uri: Uri): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    companion object {
        const val DIRECTORY_NAME = "wallpaper"
    }
}
