package com.gamecore.core.common

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the stack trace of an uncaught exception somewhere a person can read it.
 *
 * Android gives an app no way to read the system crash report for itself: the crash goes to the
 * logcat buffer, which belongs to the shell. When a release build fails on a device with no adb
 * attached, the only dependable evidence is what the app writes down before the process dies.
 *
 * Two destinations, in order of preference:
 *
 *  * `Downloads/GameCoreCrash.txt` through `MediaStore`, which needs no storage permission on
 *    Android 10+ (the app targets that storage model), so the file shows up where a user already
 *    knows to look;
 *  * the app's own external files directory as a fallback, for a device where the MediaStore insert
 *    is refused.
 *
 * The previous handler is always called afterwards, so crash reporting from the system still
 * happens — this observes a crash, it does not swallow one.
 */
object CrashLog {

    private const val FILE_NAME = "GameCoreCrash.txt"

    /** Installs the handler. Safe to call more than once; the last install wins. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, render(thread, error))
            } catch (_: Throwable) {
                // A crash reporter that crashes is worse than none; the original crash still runs.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun render(thread: Thread, error: Throwable): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("GameCore crash — $stamp")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        appendLine("thread: ${thread.name}")
        appendLine()
        appendLine(error.stackTraceToString())
        var cause = error.cause
        while (cause != null) {
            appendLine()
            appendLine("caused by:")
            appendLine(cause.stackTraceToString())
            cause = cause.cause
        }
    }

    private fun write(context: Context, text: String) {
        val bytes = text.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                return
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        File(dir, FILE_NAME).writeBytes(bytes)
    }
}
