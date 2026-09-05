package com.gamecore.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.CardFigure
import com.gamecore.core.model.SessionCard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Draws a [SessionCard] into a PNG and hands out a `content://` URI for it.
 *
 * Everything the card *says* was decided in [SessionCard], which is why no rule about absent readings
 * appears anywhere below: a dash arrives here as a dash, and this class cannot turn one into a zero. What
 * is decided here is only visual, and one of those decisions is worth stating — the card is dark whatever
 * theme the app is in, because it is going to land in a chat window rather than in this app, and it should
 * look like one thing wherever it is opened.
 *
 * The file goes to `files/cards` and out through the same [FileProvider] the CSV export and the
 * screenshots use, so sharing a card needs no storage permission on any API level. Its name carries a
 * timestamp and nothing from the game's own label: that label is chosen by whoever wrote that app, and a
 * string from that source has no business in a path.
 */
@Singleton
class SessionCardRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun render(card: SessionCard, accentArgb: Int): CardResult = withContext(io) {
        var target: File? = null
        var bitmap: Bitmap? = null
        try {
            val directory = File(context.filesDir, DIRECTORY_NAME).apply {
                if (!exists()) mkdirs()
            }
            prune(directory)
            val file = File(directory, "gamecore-session-${Formatters.fileTimestamp(now())}$EXTENSION")
            target = file
            bitmap = draw(card, accentArgb)
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            CardResult.Written(fileName = file.name, sizeBytes = file.length(), uri = shareUri(file))
        } catch (error: Throwable) {
            // Storage full, a bitmap that would not allocate, a provider that refused the URI. A
            // half-written PNG is worse than none — it would share as a broken image — so it goes.
            target?.let { runCatching { it.delete() } }
            CardResult.Failed(error.javaClass.simpleName)
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Keeps the last few cards and no more.
     *
     * A card is a picture of data that is already in the database, so a pile of them in the app's own
     * storage is a slow leak the user never sees and cannot clear from here.
     */
    private fun prune(directory: File) {
        val existing = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        existing.drop(KEEP_CARDS - 1).forEach { runCatching { it.delete() } }
    }

    private fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    // ------------------------------------------------------------------------------------- drawing

    /**
     * The whole card, top to bottom, in one pass.
     *
     * Laid out by walking a cursor down the panel rather than from a table of fixed offsets, because the
     * caption wraps to a number of lines that depends on how many caveats the session has — an
     * interrupted session with a dash in it says more than a clean one, and everything under it has to
     * move. The footer is the exception: it is pinned to the bottom so that two cards for two sessions
     * are the same shape.
     */
    private fun draw(card: SessionCard, accentArgb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val panel = RectF(MARGIN, MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN)
        canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, fill(SURFACE))
        canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, stroke(OUTLINE))

        val left = panel.left + PADDING
        val width = panel.width() - PADDING * 2

        var y = drawMasthead(canvas, left, panel.top + PADDING, accentArgb) + TITLE_GAP
        y = drawLine(canvas, card.gameLabel, left, y, titlePaint(card.gameLabel, width)) + SUBTITLE_GAP
        y = drawLine(canvas, card.subtitle, left, y, textPaint(SUBTITLE_SIZE, ON_SURFACE_VARIANT))
        y = drawGrid(canvas, card.figures, left, y + GRID_GAP, width, accentArgb)
        drawParagraph(canvas, card.caption, left, y + CAPTION_GAP, width, CAPTION_SIZE, CAPTION_LINES)
        drawFooter(canvas, card.footer, left, panel.bottom - PADDING, width)
        return bitmap
    }

    /**
     * An accent rule and a small wordmark.
     *
     * The only branding on the card, and it is deliberately quieter than the game's name. It exists for
     * the thumbnail: at the size a chat app shows an image before it is tapped, the footer's type is
     * unreadable and this is the only thing that says which app produced the figures.
     */
    private fun drawMasthead(canvas: Canvas, left: Float, top: Float, accentArgb: Int): Float {
        val mark = textPaint(MARK_SIZE, accentArgb, bold = true).apply { letterSpacing = MARK_TRACKING }
        val height = lineHeight(mark)
        canvas.drawRoundRect(
            RectF(left, top, left + RULE_WIDTH, top + height),
            RULE_WIDTH / 2f,
            RULE_WIDTH / 2f,
            fill(accentArgb),
        )
        return drawLine(canvas, WORDMARK, left + RULE_WIDTH + MARK_GAP, top, mark)
    }

    /** Two rows of two, in [SessionCard.figures]' own order so every card reads the same way. */
    private fun drawGrid(
        canvas: Canvas,
        figures: List<CardFigure>,
        left: Float,
        top: Float,
        width: Float,
        accentArgb: Int,
    ): Float {
        val tileWidth = (width - TILE_GAP) / 2f
        var bottom = top
        figures.forEachIndexed { index, figure ->
            val x = left + (index % 2) * (tileWidth + TILE_GAP)
            val y = top + (index / 2) * (TILE_HEIGHT + TILE_GAP)
            drawTile(canvas, figure, RectF(x, y, x + tileWidth, y + TILE_HEIGHT), accentArgb)
            bottom = y + TILE_HEIGHT
        }
        return bottom
    }

    /**
     * One tile: a quiet label at the top, the figure itself at the bottom.
     *
     * [CardFigure.isReading] chooses the colour of the value, and that is the whole of what dimming
     * means here. A value that is not a measurement of the label above it — a dash, or "Charging" where a
     * drain rate would go — is drawn in the muted neutral rather than the accent, because on an image
     * with no app around it colour is the only thing separating a figure from an explanation of why
     * there isn't one.
     */
    private fun drawTile(canvas: Canvas, figure: CardFigure, bounds: RectF, accentArgb: Int) {
        canvas.drawRoundRect(bounds, TILE_RADIUS, TILE_RADIUS, fill(SURFACE_VARIANT))
        canvas.drawRoundRect(bounds, TILE_RADIUS, TILE_RADIUS, stroke(OUTLINE))
        val label = textPaint(LABEL_SIZE, ON_SURFACE_VARIANT).apply { letterSpacing = LABEL_TRACKING }
        drawLine(
            canvas,
            figure.label.uppercase(Locale.US),
            bounds.left + TILE_PADDING,
            bounds.top + TILE_PADDING,
            label,
        )
        val value = textPaint(
            size = VALUE_SIZE,
            color = if (figure.isReading) accentArgb else ON_SURFACE_VARIANT,
            bold = true,
        )
        drawLine(
            canvas,
            figure.value,
            bounds.left + TILE_PADDING,
            bounds.bottom - TILE_PADDING - lineHeight(value),
            value,
        )
    }

    /**
     * The provenance line, held at the bottom of the panel.
     *
     * Drawn fainter than the caption because it is the same sentence on every card, and drawn at all
     * because a figure shared out of context invites exactly two questions: where it came from, and
     * whether it went anywhere.
     */
    private fun drawFooter(canvas: Canvas, text: String, left: Float, bottom: Float, width: Float) {
        val paint = textPaint(FOOTER_SIZE, ON_SURFACE_VARIANT).apply { alpha = FOOTER_ALPHA }
        val layout = paragraph(text, paint, width, FOOTER_LINES)
        canvas.save()
        canvas.translate(left, bottom - layout.height)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawParagraph(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        width: Float,
        size: Float,
        maxLines: Int,
    ): Float {
        val layout = paragraph(text, textPaint(size, ON_SURFACE_VARIANT), width, maxLines)
        canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()
        return top + layout.height
    }

    /**
     * A wrapped block of text, bounded.
     *
     * [maxLines] with an ellipsis rather than an unbounded layout: the captions are written in code and
     * pinned by a test, but a paragraph that grows by one line in a later change would otherwise draw
     * itself over the footer of an image somebody has already sent.
     */
    private fun paragraph(text: String, paint: TextPaint, width: Float, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(LINE_SPACING, 1f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /**
     * Title type, reduced until the name fits.
     *
     * [SessionCard] has already clamped the label to a character count, which is not the same thing as a
     * width — thirty-four narrow characters and thirty-four wide ones are different pictures. Shrinking
     * is better than clipping a second time here: the game's name is the first thing anyone looks at on
     * this card.
     */
    private fun titlePaint(label: String, maxWidth: Float): TextPaint {
        val paint = textPaint(TITLE_SIZE, ON_SURFACE, bold = true)
        while (paint.textSize > TITLE_MIN_SIZE && paint.measureText(label) > maxWidth) {
            paint.textSize -= 2f
        }
        return paint
    }

    /** Draws one line with [top] as the top of its line box, and returns the bottom of it. */
    private fun drawLine(canvas: Canvas, text: String, x: Float, top: Float, paint: TextPaint): Float {
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
        return top + lineHeight(paint)
    }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = HAIRLINE
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) BOLD else Typeface.DEFAULT
        }

    private companion object {
        const val DIRECTORY_NAME = "cards"
        const val EXTENSION = ".png"
        const val KEEP_CARDS = 4

        const val WORDMARK = "GAMECORE"

        /**
         * A portrait card at the size a chat app will not re-compress into mush, and tall enough that
         * the longest caption the model can produce still clears the footer.
         */
        const val WIDTH = 1080
        const val HEIGHT = 1260

        /**
         * The theme's dark neutrals, repeated here as literals.
         *
         * `ui.theme.Color` keeps them private, and this is a `data` class that has no business importing
         * from the presentation layer — nor should the theme widen its public surface for one PNG. The
         * card is dark in every theme, so these do not track a setting and cannot drift out of sync with
         * one. The accent is the only colour that comes from the user's choice.
         */
        val BACKGROUND = 0xFF07090E.toInt()
        val SURFACE = 0xFF0B0F16.toInt()
        val SURFACE_VARIANT = 0xFF1A2130.toInt()
        val OUTLINE = 0xFF2A3342.toInt()
        val ON_SURFACE = 0xFFE8ECF4.toInt()
        val ON_SURFACE_VARIANT = 0xFFA5AEC0.toInt()

        val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        const val MARGIN = 36f
        const val PADDING = 56f
        const val PANEL_RADIUS = 48f
        const val HAIRLINE = 2f

        const val RULE_WIDTH = 8f
        const val MARK_SIZE = 30f
        const val MARK_GAP = 20f
        const val MARK_TRACKING = 0.14f

        const val TITLE_SIZE = 64f
        const val TITLE_MIN_SIZE = 40f
        const val TITLE_GAP = 40f
        const val SUBTITLE_SIZE = 34f
        const val SUBTITLE_GAP = 10f

        const val GRID_GAP = 46f
        const val TILE_GAP = 24f
        const val TILE_HEIGHT = 190f
        const val TILE_RADIUS = 28f
        const val TILE_PADDING = 32f
        const val LABEL_SIZE = 30f
        const val LABEL_TRACKING = 0.08f
        const val VALUE_SIZE = 58f

        const val CAPTION_GAP = 44f
        const val CAPTION_SIZE = 32f
        const val CAPTION_LINES = 5
        const val FOOTER_SIZE = 27f
        const val FOOTER_LINES = 3
        const val FOOTER_ALPHA = 170
        const val LINE_SPACING = 8f
    }
}

/**
 * What a render attempt did.
 *
 * [Written.uri] is nullable for the same reason [ExportResult.Written]'s is: the PNG exists either way,
 * and a provider that refuses a URI is not a reason to call a successful render a failure. The report
 * screen offers the share sheet only when there is a URI to put in one.
 */
sealed interface CardResult {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val uri: Uri?,
    ) : CardResult

    /** [detail] is an exception class name — never a path or a message, which can carry user data. */
    data class Failed(val detail: String) : CardResult
}
