package com.gamecore.ui.sessions

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.Readout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The data for one session receipt, built from the report's already-formatted state so the content
 * is unit-testable and the renderer stays thin.
 *
 * [gameTitle] and [subtitle] come from [SessionReportUiState]. [headline] and [figures] are the
 * same [Readout]s the screen shows. [topEvents] is the event timeline capped at [MAX_EVENTS].
 * [provenance] is the sentence the receipt prints at the bottom so a forwarded image cannot be
 * read as a full report.
 *
 * Everything here is a string the user already saw on the report screen — this is a shareable
 * snapshot, not a second computation, so the numbers cannot disagree.
 */
data class SessionReceipt(
    val gameTitle: String,
    val subtitle: String,
    val headline: List<Readout>,
    val figures: List<Readout>,
    val topEvents: List<SessionEvent>,
    val provenance: String = "Recorded on this device by GameCore. This is a summary; the full " +
        "report with graphs and details is in the app.",
) {
    companion object {
        /** A chat window can read about eight lines before it scrolls; we cap it there. */
        const val MAX_EVENTS = 8
    }
}

/**
 * Builds a [SessionReceipt] from the screen state.
 *
 * Pure and testable — no Android types. The event formatting happens here so the renderer only
 * paints what it is given.
 */
fun buildReceipt(
    state: SessionReportUiState,
    detail: SessionReportDetail,
): SessionReceipt {
    val events = detail.events
        // Same reason as the timeline: probe readings are evidence for the network card, not receipts
        // lines. Without this the first ten rows of a long session would all be probes.
        .filter { it.kind != SessionEventKind.NETWORK_PROBE }
        .sortedBy { it.elapsedMillis }
        .take(SessionReceipt.MAX_EVENTS)
    return SessionReceipt(
        gameTitle = state.title,
        subtitle = state.subtitle,
        headline = state.headline,
        figures = state.figures,
        topEvents = events,
    )
}

/**
 * Draws the receipt to a PNG and hands it to the share sheet in one tap.
 *
 * The PNG is written to the app's cache/exports directory and shared through the existing
 * FileProvider (`${applicationId}.fileprovider`). If no app can receive an image, the caller
 * sees [shared = false] and can show a message. The write and share are both on the IO dispatcher;
 * the bitmap drawing is fast enough to stay there.
 */
suspend fun shareReceipt(
    context: Context,
    state: SessionReportUiState,
    detail: SessionReportDetail,
): Boolean = withContext(Dispatchers.IO) {
    // A receipt is a convenience, and a failed one must never close the screen that offered it.
    // drawing can OOM, writes can run out of space, and FileProvider can reject a path: all three
    // become `false` here, exactly as an unresolvable share intent already does.
    try {
        val receipt = buildReceipt(state, detail)
        val bitmap = drawReceipt(receipt)
        val file = writePng(context, bitmap) ?: return@withContext false

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val shared = intent.resolveActivity(context.packageManager) != null &&
            context.startActivitySafely(intent)
        return@withContext shared
    } catch (_: Exception) {
        return@withContext false
    }
}

/**
 * Draws the receipt to a bitmap at 1080 px wide, height auto-scaled to content.
 *
 * Layout is deterministic: heading, subtitle, headline strip, figures strip, a ruled line, then
 * the event list. No graphs, no heavy styling — it must be legible in a chat window at 50% scale.
 */
fun drawReceipt(receipt: SessionReceipt): Bitmap {
    val widthPx = 1080
    val marginPx = 48
    val lineHeight = 36
    val headingSize = 28
    val subSize = 18
    val bodySize = 20
    val smallSize = 16
    val accent = 0xFF6200EE.toInt()

    // First pass: measure
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.textSize = bodySize.toFloat()

    val lines = mutableListOf<String>()
    lines += receipt.gameTitle
    lines += receipt.subtitle
    lines += "---"
    receipt.headline.forEach { lines += "${it.label}: ${it.value}" }
    lines += "---"
    receipt.figures.forEach {
        if (it.value != ABSENT) lines += "${it.label}: ${it.value}"
    }
    if (receipt.topEvents.isNotEmpty()) {
        lines += "---"
        lines += "What happened"
        receipt.topEvents.forEach { ev ->
            val time = elapsed(ev.elapsedMillis)
            val detail = ev.detail?.let { " — $it" } ?: ""
            lines += "[${ev.kind.label}] $time$detail"
        }
    }
    lines += "---"
    lines += receipt.provenance

    var heightPx = 0
    lines.forEach { text ->
        paint.textSize = if (text == receipt.gameTitle) headingSize.toFloat() else
            if (text == receipt.subtitle) subSize.toFloat() else
                if (text == "---" || text == "What happened") smallSize.toFloat() else
                    bodySize.toFloat()
        heightPx += (paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent + 16).toInt()
    }
    heightPx += marginPx * 2

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFFFFFFFF.toInt())

    var y = marginPx
    lines.forEach { text ->
        paint.textSize = if (text == receipt.gameTitle) headingSize.toFloat() else
            if (text == receipt.subtitle) subSize.toFloat() else
                if (text == "---" || text == "What happened") smallSize.toFloat() else
                    bodySize.toFloat()
        paint.color = if (text == receipt.gameTitle) accent else
            if (text == receipt.subtitle) 0xFF757575.toInt() else
                if (text == "---") 0xFFBDBDBD.toInt() else
                    if (text == "What happened") 0xFF333333.toInt() else
                        0xFF212121.toInt()
        if (text == receipt.gameTitle) paint.typeface = Typeface.DEFAULT_BOLD else paint.typeface = Typeface.DEFAULT
        canvas.drawText(text, marginPx.toFloat(), (y - paint.ascent()).toFloat(), paint)
        y += (paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent + 16).toInt()
    }
    return bitmap
}

private fun elapsed(millis: Long): String {
    val total = millis.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun writePng(context: Context, bitmap: Bitmap): File? {
    val dir = File(context.cacheDir, "exports")
    if (!dir.exists() && !dir.mkdirs()) return null
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val file = File(dir, "GameCore_receipt_$stamp.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file
}

/**
 * A tiny helper to avoid a nullability dance in the share call site.
 */
private fun Context.startActivitySafely(intent: Intent): Boolean {
    return try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}