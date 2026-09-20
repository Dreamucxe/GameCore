package com.gamecore.core.input

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What GameCore records about touch, and the hard limit on what that can be.
 *
 * An Android application receives pointer events for its own windows and for nothing else. There is no
 * API, permission or Shizuku command that hands an ordinary app the touches a user makes in another
 * application, and GameCore does not pretend otherwise: everything in this file comes from pointer events
 * delivered to one view inside GameCore itself. Nothing is read from the screen, the framebuffer, the
 * input device nodes or any other app, and no screenshot is taken at any point.
 *
 * Coordinates are stored normalised to the capture area — 0..1 on each axis — so a recording survives a
 * rotation and an export does not carry the pixel geometry of the user's device around with it.
 */
enum class TouchAction(val label: String) {
    DOWN("DOWN"),
    MOVE("MOVE"),
    UP("UP"),
    CANCEL("CANCEL"),
}

/** One pointer sample, exactly as the event reported it. */
data class TouchPoint(
    val sequence: Int,
    val pointerId: Long,
    val action: TouchAction,
    /** 0..1 across the capture area. */
    val x: Float,
    /** 0..1 down the capture area. */
    val y: Float,
    val timestampMillis: Long,
    /** `PointerInputChange.pressure`, or null where the device does not report one. */
    val pressure: Float?,
    /** How many pointers were down when this sample arrived. */
    val activePointers: Int,
)

/** One finger from the moment it went down to the moment it came up. */
data class TouchStroke(
    val pointerId: Long,
    val points: List<TouchPoint>,
    val startMillis: Long,
    val endMillis: Long,
    /** Straight-line distance start to end, in units of the capture area's width. */
    val displacement: Float,
    /** Length of the path actually travelled, same units. */
    val pathLength: Float,
    val maximumPointers: Int,
    val wasCancelled: Boolean,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)

    /** A stroke that travelled far enough to be a swipe rather than a tap. */
    val isSwipe: Boolean get() = pathLength >= SWIPE_THRESHOLD

    companion object {
        /** Five percent of the capture area's width. Below it, a finger wobbled; above it, it swiped. */
        const val SWIPE_THRESHOLD = 0.05f
    }
}

/** The figures the Touch Heatmap screen reports, all counted rather than estimated. */
data class TouchSummary(
    val totalTouches: Int,
    val totalSwipes: Int,
    val totalEvents: Int,
    val averageTouchDurationMillis: Long,
    val longestSwipe: Float,
    val longestSwipeDurationMillis: Long,
    val multiTouchStrokes: Int,
    val maximumSimultaneousPointers: Int,
    /** Touches per second of capture time, or null before a second has passed. */
    val touchDensityPerSecond: Float?,
    val busiestCellLabel: String?,
    val busiestCellShare: Float?,
    val captureMillis: Long,
    val droppedEvents: Int,
) {
    val isEmpty: Boolean get() = totalEvents == 0

    companion object {
        val EMPTY = TouchSummary(
            totalTouches = 0,
            totalSwipes = 0,
            totalEvents = 0,
            averageTouchDurationMillis = 0L,
            longestSwipe = 0f,
            longestSwipeDurationMillis = 0L,
            multiTouchStrokes = 0,
            maximumSimultaneousPointers = 0,
            touchDensityPerSecond = null,
            busiestCellLabel = null,
            busiestCellShare = null,
            captureMillis = 0L,
            droppedEvents = 0,
        )
    }
}

/**
 * An immutable copy of the log for one frame of drawing.
 *
 * Published on a timer rather than per event: a finger dragging across the screen produces a pointer
 * sample per frame, and recomposing the whole screen on each of them would cost more than the feature is
 * worth. The lists here are snapshots, so the canvas can read them without synchronising against the
 * pointer handler that is still appending.
 */
data class TouchSnapshot(
    val points: List<TouchPoint>,
    val strokes: List<TouchStroke>,
    val heat: IntArray,
    val heatColumns: Int,
    val heatRows: Int,
    val heatPeak: Int,
    val activePointers: Int,
    val summary: TouchSummary,
) {
    val isEmpty: Boolean get() = points.isEmpty()

    // Generated equals/hashCode would compare the IntArray by identity, which is exactly wrong for a
    // value published to Compose: the array is replaced on every publish, so identity comparison makes
    // every snapshot unequal and every publish a recomposition. Comparing contents is the intent.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TouchSnapshot) return false
        return points == other.points &&
            strokes == other.strokes &&
            heat.contentEquals(other.heat) &&
            heatColumns == other.heatColumns &&
            heatRows == other.heatRows &&
            heatPeak == other.heatPeak &&
            activePointers == other.activePointers &&
            summary == other.summary
    }

    override fun hashCode(): Int {
        var result = points.hashCode()
        result = 31 * result + strokes.hashCode()
        result = 31 * result + heat.contentHashCode()
        result = 31 * result + heatColumns
        result = 31 * result + heatRows
        result = 31 * result + heatPeak
        result = 31 * result + activePointers
        result = 31 * result + summary.hashCode()
        return result
    }

    companion object {
        fun empty(columns: Int, rows: Int) = TouchSnapshot(
            points = emptyList(),
            strokes = emptyList(),
            heat = IntArray(columns * rows),
            heatColumns = columns,
            heatRows = rows,
            heatPeak = 0,
            activePointers = 0,
            summary = TouchSummary.EMPTY,
        )
    }
}

/**
 * The recording itself: append pointer samples, read back a snapshot.
 *
 * Bounded on purpose. A heatmap that kept every sample of a twenty-minute session would hold hundreds of
 * thousands of objects for a picture that stops changing long before that, so the point list and the
 * stroke list each drop their oldest entries once full and the count of what was dropped is reported
 * rather than hidden. The heat grid is not bounded — it is a fixed number of counters and never grows.
 *
 * Not thread-safe and does not need to be: pointer events arrive on the main thread and the snapshot is
 * taken there too.
 */
class TouchLog(
    val gridColumns: Int = DEFAULT_COLUMNS,
    val gridRows: Int = DEFAULT_ROWS,
) {

    private val points = ArrayDeque<TouchPoint>()
    private val strokes = ArrayDeque<TouchStroke>()
    private val open = LinkedHashMap<Long, MutableList<TouchPoint>>()
    private val heat = IntArray(gridColumns * gridRows)

    private var sequence = 0
    private var dropped = 0
    private var heatPeak = 0
    private var startedAtMillis = 0L
    private var lastEventMillis = 0L
    private var maximumPointers = 0

    val isEmpty: Boolean get() = sequence == 0

    fun record(
        pointerId: Long,
        action: TouchAction,
        x: Float,
        y: Float,
        pressure: Float?,
        activePointers: Int,
        timestampMillis: Long,
    ) {
        val point = TouchPoint(
            sequence = sequence++,
            pointerId = pointerId,
            action = action,
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            timestampMillis = timestampMillis,
            pressure = pressure,
            activePointers = activePointers,
        )
        if (startedAtMillis == 0L) startedAtMillis = timestampMillis
        lastEventMillis = timestampMillis
        if (activePointers > maximumPointers) maximumPointers = activePointers

        points.addLast(point)
        while (points.size > MAX_POINTS) {
            points.removeFirst()
            dropped++
        }

        // The heat grid counts every sample, including the ones dropped from the point list above, so
        // the picture keeps accumulating after the raw list has started rolling.
        val column = (point.x * gridColumns).toInt().coerceIn(0, gridColumns - 1)
        val row = (point.y * gridRows).toInt().coerceIn(0, gridRows - 1)
        val cell = row * gridColumns + column
        heat[cell] = heat[cell] + 1
        if (heat[cell] > heatPeak) heatPeak = heat[cell]

        when (action) {
            TouchAction.DOWN -> open[pointerId] = mutableListOf(point)
            TouchAction.MOVE -> open[pointerId]?.add(point)
            TouchAction.UP, TouchAction.CANCEL -> {
                val path = open.remove(pointerId) ?: mutableListOf()
                path.add(point)
                closeStroke(pointerId, path, action == TouchAction.CANCEL)
            }
        }
    }

    private fun closeStroke(pointerId: Long, path: MutableList<TouchPoint>, cancelled: Boolean) {
        val first = path.first()
        val last = path.last()
        var travelled = 0f
        for (i in 1 until path.size) {
            travelled += distance(path[i - 1], path[i])
        }
        strokes.addLast(
            TouchStroke(
                pointerId = pointerId,
                points = path.toList(),
                startMillis = first.timestampMillis,
                endMillis = last.timestampMillis,
                displacement = distance(first, last),
                pathLength = travelled,
                maximumPointers = path.maxOf { it.activePointers },
                wasCancelled = cancelled,
            ),
        )
        while (strokes.size > MAX_STROKES) strokes.removeFirst()
    }

    private fun distance(a: TouchPoint, b: TouchPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    fun clear() {
        points.clear()
        strokes.clear()
        open.clear()
        heat.fill(0)
        sequence = 0
        dropped = 0
        heatPeak = 0
        startedAtMillis = 0L
        lastEventMillis = 0L
        maximumPointers = 0
    }

    fun snapshot(): TouchSnapshot = TouchSnapshot(
        points = points.toList(),
        strokes = strokes.toList(),
        heat = heat.copyOf(),
        heatColumns = gridColumns,
        heatRows = gridRows,
        heatPeak = heatPeak,
        activePointers = open.size,
        summary = summary(),
    )

    /** The most recent raw events, newest first, for the raw event view. */
    fun recentEvents(limit: Int): List<TouchPoint> =
        points.toList().takeLast(limit).asReversed()

    fun summary(): TouchSummary {
        if (sequence == 0) return TouchSummary.EMPTY
        val finished = strokes.toList()
        val swipes = finished.filter { it.isSwipe }
        val longest = swipes.maxByOrNull { it.pathLength }
        val captureMillis = (lastEventMillis - startedAtMillis).coerceAtLeast(0L)
        val busiest = busiestCell()
        val heatTotal = heat.sum()
        return TouchSummary(
            totalTouches = finished.size + open.size,
            totalSwipes = swipes.size,
            totalEvents = sequence,
            averageTouchDurationMillis = if (finished.isEmpty()) {
                0L
            } else {
                finished.sumOf { it.durationMillis } / finished.size
            },
            longestSwipe = longest?.pathLength ?: 0f,
            longestSwipeDurationMillis = longest?.durationMillis ?: 0L,
            multiTouchStrokes = finished.count { it.maximumPointers > 1 },
            maximumSimultaneousPointers = maximumPointers,
            touchDensityPerSecond = if (captureMillis >= 1_000L) {
                (finished.size + open.size) * 1_000f / captureMillis
            } else {
                null
            },
            busiestCellLabel = busiest?.let { cellLabel(it) },
            busiestCellShare = busiest?.let { if (heatTotal > 0) heat[it].toFloat() / heatTotal else null },
            captureMillis = captureMillis,
            droppedEvents = dropped,
        )
    }

    private fun busiestCell(): Int? {
        var best = -1
        var bestCount = 0
        heat.forEachIndexed { index, count ->
            if (count > bestCount) {
                best = index
                bestCount = count
            }
        }
        return if (best >= 0) best else null
    }

    /**
     * Names a cell by where it sits on the screen rather than by its index.
     *
     * "Upper right" is what a person can act on; "cell 143" is not, and neither is a coordinate pair
     * that means nothing without the grid size beside it.
     */
    private fun cellLabel(cell: Int): String {
        val column = cell % gridColumns
        val row = cell / gridColumns
        val horizontal = when {
            column < gridColumns / 3f -> "left"
            column < gridColumns * 2f / 3f -> "centre"
            else -> "right"
        }
        val vertical = when {
            row < gridRows / 3f -> "upper"
            row < gridRows * 2f / 3f -> "middle"
            else -> "lower"
        }
        return if (horizontal == "centre" && vertical == "middle") "centre" else "$vertical $horizontal"
    }

    companion object {
        /** A grid fine enough to show a thumb zone, coarse enough that a single tap is not a pixel. */
        const val DEFAULT_COLUMNS = 14
        const val DEFAULT_ROWS = 28

        const val MAX_POINTS = 4_000
        const val MAX_STROKES = 400
    }
}

/** What the heatmap screen is drawing. Four ways of looking at the same recording. */
enum class TouchViewMode(val label: String) {
    HEATMAP("Heatmap"),
    POINTS("Points"),
    TRAILS("Trails"),
    RAW("Raw events"),
}

/** Normalised heat for one cell, 0..1, or 0 when nothing has been recorded. */
fun TouchSnapshot.intensityAt(column: Int, row: Int): Float {
    if (heatPeak <= 0) return 0f
    val index = row * heatColumns + column
    if (index !in heat.indices) return 0f
    return heat[index].toFloat() / heatPeak
}

/** Straight-line distance between the two ends of a stroke, in percent of the capture width. */
fun TouchStroke.displacementPercent(): Float = displacement * 100f

/** Path length as a percentage, which is how the summary reports a swipe's length. */
fun TouchStroke.pathPercent(): Float = pathLength * 100f

/** Largest absolute axis movement in a stroke, used to say whether it was mostly horizontal. */
fun TouchStroke.dominantAxis(): String {
    val first = points.first()
    val last = points.last()
    return if (abs(last.x - first.x) >= abs(last.y - first.y)) "horizontal" else "vertical"
}

/** The peak count in the grid, floored at one so a divide is always safe. */
fun TouchSnapshot.safePeak(): Int = max(1, heatPeak)
