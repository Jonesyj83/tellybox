package com.tellybox.tv.ui.epg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tellybox.tv.R
import com.tellybox.tv.data.db.EpgProgramEntity
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** One row of the guide. [tvgId] carries the persisted playlist channel lookup key. */
data class GuideChannel(
    val id: Long,
    val number: Int?,
    val name: String,
    val tvgId: String?,
    val catchupDays: Int,
    val favorite: Boolean = false,
)

/**
 * A program cell in the grid. When a channel has no guide data for a stretch of time,
 * the grid makes up half-hour placeholder blocks with [program] set to null so that
 * there's still something to move focus onto. A channel with no programmes at all gets a
 * single [strip] across the whole row instead (see [GuideBlocks.build]).
 */
data class GuideBlock(val start: Long, val stop: Long, val program: EpgProgramEntity?, val strip: Strip? = null) {
    enum class Strip { LOADING, NO_DATA }
}

/**
 * Time-based TV guide drawn directly on a canvas. A RecyclerView per row with shared
 * horizontal scrolling fights D-pad focus at every turn; doing the layout by hand keeps
 * movement predictable: Up/Down change channel and keep the time, Left/Right step
 * through programmes.
 */
class GuideGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Callback {
        fun programsFor(channel: GuideChannel): List<EpgProgramEntity>?
        fun onVisibleRows(first: Int, last: Int)
        fun onFocus(channel: GuideChannel, block: GuideBlock)
        fun onSelect(row: Int, block: GuideBlock)
        fun onMenu(row: Int, block: GuideBlock)
        /** Holding OK. Opens the same menu as the Menu key unless a screen wants otherwise. */
        fun onLongSelect(row: Int, block: GuideBlock) = onMenu(row, block)
    }

    var callback: Callback? = null
    var emptyRowText: ((GuideChannel) -> String)? = null

    var channels: List<GuideChannel> = emptyList()
        private set

    /**
     * Called when Left can't go any further back (or, with [exitLeftAtNow], when there's
     * nothing playable to the left). Return true if focus was moved somewhere else.
     */
    var onExitLeft: (() -> Boolean)? = null

    /**
     * Called for Up on the top row instead of letting Android's focus search pick
     * somewhere. Unset, Up there falls through to the default handling.
     */
    var onExitUp: (() -> Unit)? = null

    /** Treat Left on a current programme without catch-up as leaving the grid. */
    var exitLeftAtNow = false

    /** Channel to mark as playing (the preview), or null. */
    var playingChannelId: Long? = null
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    private val uiScale = (context as? com.tellybox.tv.ui.ScaledActivity)?.uiScale
        ?: resources.configuration.let {
            com.tellybox.tv.ui.UiScale(it.screenWidthDp, it.screenHeightDp, compact = it.smallestScreenWidthDp < 600)
        }
    private val dp = resources.displayMetrics.density * uiScale.scale
    private var rowHeight = 26 * dp
    private val headerHeight = 22 * dp
    private var channelWidth = 196 * dp
    private var pxPerMinute = 4.2f * dp

    /** Tighter layout for when the grid shares the screen with other things. */
    fun setMetrics(rowHeightDp: Float, channelWidthDp: Float, pxPerMinuteDp: Float) {
        rowHeight = rowHeightDp * dp
        channelWidth = channelWidthDp * dp
        pxPerMinute = pxPerMinuteDp * dp
        ensureRowVisible()
        lastReportedRows = -1 to -1
        invalidate()
    }
    private val gap = 2 * dp
    private val pad = 5 * dp

    /** Left edge of the visible time range, always on a half hour. */
    private var viewStart = floorSlot(System.currentTimeMillis())
    private var focusTime = System.currentTimeMillis()
    var focusedRow = 0
        private set
    private var firstRow = 0
    private var lastReportedRows = -1 to -1

    val minTime get() = floorSlot(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
    val maxTime get() = floorSlot(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7))

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val bgPaint = Paint().apply { color = color(R.color.surface) }
    private val pastPaint = Paint().apply { color = color(R.color.guide_past) }
    private val nowPaint = Paint().apply { color = color(R.color.surface_raised) }
    private val focusPaint = Paint().apply { color = color(R.color.surface_focus) }
    private val focusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.accent); style = Paint.Style.STROKE; strokeWidth = 3 * dp
    }
    private val nowLine = Paint().apply { color = color(R.color.accent); strokeWidth = 1.5f * dp; alpha = 110 }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.text_primary); textSize = uiScale.sp(10) * resources.displayMetrics.scaledDensity
    }
    private val channelPaint = TextPaint(titlePaint).apply { textSize = uiScale.sp(11) * resources.displayMetrics.scaledDensity }
    var panelOpacity = 100
        set(value) {
            field = value.coerceIn(0, 100)
            listOf(bgPaint, pastPaint, nowPaint, focusPaint).forEach { it.alpha = field * 255 / 100 }
            invalidate()
        }

    var dimPast = true
        set(value) { field = value; invalidate() }

    private val dimTitlePaint = TextPaint(titlePaint).apply { color = color(R.color.text_secondary) }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.text_secondary); textSize = uiScale.sp(9) * resources.displayMetrics.scaledDensity
    }
    private val numberPaint = TextPaint(captionPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val favoritePaint = TextPaint(captionPaint).apply { color = color(R.color.accent) }
    private val playingPaint = Paint().apply { color = color(R.color.accent) }
    private val rect = RectF()
    private val timeFormat = DateFormat.getTimeFormat(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /**
     * Replaces the rows. With [keepPosition] (the same list, updated) the focused channel,
     * its place on screen and the time all stay put; otherwise focus goes to
     * [focusChannelId] (or the first row) at the current time.
     */
    fun setChannels(list: List<GuideChannel>, focusChannelId: Long? = null, keepPosition: Boolean = false) {
        if (keepPosition) {
            val focusedId = channels.getOrNull(focusedRow)?.id
            val offset = focusedRow - firstRow
            channels = list
            focusedRow = list.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
                ?: focusedRow.coerceIn(0, max(0, list.size - 1))
            firstRow = max(0, focusedRow - offset)
        } else {
            channels = list
            focusedRow = list.indexOfFirst { it.id == focusChannelId }.takeIf { it >= 0 } ?: 0
            firstRow = 0
            focusTime = System.currentTimeMillis()
            viewStart = floorSlot(focusTime)
        }
        ensureRowVisible()
        lastReportedRows = -1 to -1
        invalidate()
        notifyFocus()
    }

    val focusedChannel: GuideChannel? get() = channels.getOrNull(focusedRow)

    /** Moves focus to a channel's row, keeping the time. Returns false if it isn't in the list. */
    fun focusChannel(channelId: Long): Boolean {
        val row = channels.indexOfFirst { it.id == channelId }
        if (row < 0) return false
        if (row != focusedRow) {
            focusedRow = row
            ensureRowVisible()
            invalidate()
            notifyFocus()
        }
        return true
    }

    /** New programme data arrived for some rows. */
    fun refresh() {
        invalidate()
        notifyFocus()
    }

    /** Programme data was thrown away; ask for the visible rows again. */
    fun reload() {
        lastReportedRows = -1 to -1
        refresh()
    }

    fun jumpToNow() {
        focusTime = System.currentTimeMillis()
        viewStart = floorSlot(focusTime)
        invalidate()
        notifyFocus()
    }

    fun jumpBy(deltaMs: Long) {
        focusTime = (focusTime + deltaMs).coerceIn(minTime, maxTime)
        viewStart = floorSlot(focusTime)
        invalidate()
        notifyFocus()
    }

    val visibleRowCapacity get() = visibleRows

    private val visibleRows get() = max(1, ((height - headerHeight - 8 * dp) / rowHeight).toInt())
    private val visibleMs get() = TimeUnit.MINUTES.toMillis(((width - channelWidth) / pxPerMinute).toLong())

    /** Programmes for a row, with placeholders filling any holes. */
    fun blocksFor(row: Int, from: Long, to: Long): List<GuideBlock> {
        val ch = channels.getOrNull(row) ?: return emptyList()
        return GuideBlocks.build(callback?.programsFor(ch), from, to)
    }

    private fun blockAt(row: Int, time: Long): GuideBlock? =
        blocksFor(row, time - TimeUnit.HOURS.toMillis(12), time + SLOT).firstOrNull { time >= it.start && time < it.stop }

    val focusedBlock: GuideBlock? get() = blockAt(focusedRow, focusTime)

// Touch, for phones, tablets and anything with a pointer. The grid is drawn by hand, so
    // dragging has to move it by hand too: a finger drag pans time, a vertical drag scrolls
    // channels, and a tap picks the programme under it.
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchStartRow = 0
    private var touchStartTime = 0L
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchStartRow = firstRow
                touchStartTime = viewStart
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (!dragging && kotlin.math.hypot(dx, dy) > 12 * dp) dragging = true
                if (dragging) {
                    val rows = (-dy / rowHeight).toInt()
                    val maxFirst = max(0, channels.size - visibleRows)
                    firstRow = (touchStartRow + rows).coerceIn(0, maxFirst)
                    val minuteMs = 60_000f
                    val shiftMs = (-dx / pxPerMinute * minuteMs).toLong()
                    viewStart = (floorSlot(touchStartTime + shiftMs)).coerceIn(minTime, maxTime)
                    lastReportedRows = -1 to -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val row = firstRow + ((event.y - headerHeight) / rowHeight).toInt()
                    if (event.y >= headerHeight && row in channels.indices) {
                        // A tap on the channel name picks that channel; only a tap in the grid
                        // chooses a time, or the name column would compute a time from x < 0.
                        val inGrid = event.x >= channelWidth
                        if (inGrid) {
                            focusTime = viewStart +
                                ((event.x - channelWidth) / pxPerMinute * 60_000f).toLong()
                        }
                        focusedRow = row
                        blockAt(row, focusTime)?.let { callback?.onSelect(row, it) }
                        notifyFocus()
                        invalidate()
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusedRow == 0) {
                    val exit = onExitUp ?: return super.onKeyDown(keyCode, event)
                    // Holding Up to scroll to the top stops there instead of running out of the grid.
                    if (event.repeatCount == 0) exit()
                    return true
                }
                moveRow(-1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveRow(-visibleRows)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveRow(visibleRows)
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val cur = focusedBlock ?: return exitLeft()
                if (cur.start <= minTime) return exitLeft()
                if (exitLeftAtNow && cur.start <= System.currentTimeMillis() &&
                    channels[focusedRow].catchupDays <= 0) return exitLeft()
                val prev = blockAt(focusedRow, cur.start - 1) ?: return exitLeft()
                // Long programmes: land on the part nearest to where we were, not hours back.
                focusTime = max(prev.start, min(cur.start - 1, focusTime - SLOT)).coerceAtLeast(minTime)
                if (focusTime < viewStart) viewStart = floorSlot(focusTime)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val cur = focusedBlock ?: return true
                if (cur.stop >= maxTime) return true
                val next = blockAt(focusedRow, cur.stop) ?: return true
                focusTime = GuideBlocks.rightFocusTime(cur, next)
                if (focusTime >= viewStart + visibleMs - SLOT) viewStart = floorSlot(focusTime) - SLOT
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                focusedBlock?.let { callback?.onMenu(focusedRow, it) }
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        notifyFocus()
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            focusedBlock?.let { callback?.onLongSelect(focusedRow, it) }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun exitLeft(): Boolean {
        onExitLeft?.invoke()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && !event.isCanceled
        ) {
            if (event.isTracking && !event.isLongPress) focusedBlock?.let { callback?.onSelect(focusedRow, it) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun moveRow(delta: Int) {
        focusedRow = (focusedRow + delta).coerceIn(0, channels.size - 1)
        ensureRowVisible()
    }

    private fun ensureRowVisible() {
        val rows = visibleRows
        if (focusedRow < firstRow) firstRow = focusedRow
        if (focusedRow >= firstRow + rows) firstRow = focusedRow - rows + 1
        firstRow = firstRow.coerceIn(0, max(0, channels.size - rows))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureRowVisible()
        lastReportedRows = -1 to -1
    }

    private fun notifyFocus() {
        val ch = channels.getOrNull(focusedRow) ?: return
        focusedBlock?.let { callback?.onFocus(ch, it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val viewEnd = viewStart + visibleMs
        val rows = visibleRows
        val last = min(channels.size - 1, firstRow + rows - 1)
        if (lastReportedRows != firstRow to last) {
            lastReportedRows = firstRow to last
            post { callback?.onVisibleRows(firstRow, last) }
        }

        drawHeader(canvas, viewEnd)
        val focusBlock = focusedBlock

        for (row in firstRow..last) {
            val top = headerHeight + (row - firstRow) * rowHeight
            val ch = channels[row]
            // Channel cell
            rect.set(0f, top, channelWidth - gap, top + rowHeight - gap)
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, if (row == focusedRow) nowPaint else bgPaint)
            if (ch.id == playingChannelId) {
                rect.set(0f, top + 8 * dp, 4 * dp, top + rowHeight - gap - 8 * dp)
                canvas.drawRoundRect(rect, 2 * dp, 2 * dp, playingPaint)
            }
            val numText = ch.number?.toString().orEmpty()
            val textY = top + rowHeight / 2 + titlePaint.textSize / 3
            canvas.drawText(numText, pad, textY, numberPaint)
            var nameX = pad + 44 * dp
            if (ch.favorite) {
                canvas.drawText("★", nameX, textY, favoritePaint)
                nameX += favoritePaint.measureText("★ ")
            }
            canvas.drawText(ellipsizeName(ch.name, channelPaint, channelWidth - nameX - pad), nameX, textY, channelPaint)

            for (block in blocksFor(row, viewStart, viewEnd)) {
                val left = channelWidth + (max(block.start, viewStart) - viewStart) / 60_000f * pxPerMinute
                val right = channelWidth + (min(block.stop, viewEnd) - viewStart) / 60_000f * pxPerMinute - gap
                if (right <= left) continue
                // Clamp to the row band: a placeholder strip can span the whole visible
                // window, and its rect must never be allowed to grow past its own row.
                val cellBottom = min(top + rowHeight - gap, headerHeight + rows * rowHeight)
                rect.set(left, top, right, cellBottom)
                val focused = row == focusedRow && focusBlock != null && block.start == focusBlock.start && hasFocus()
                val paint = when {
                    focused -> focusPaint
                    block.stop <= now -> pastPaint
                    block.start <= now -> nowPaint
                    else -> bgPaint
                }
                if (block.strip == null) canvas.drawRoundRect(rect, 4 * dp, 4 * dp, paint)
                if (focused) canvas.drawRoundRect(rect, 4 * dp, 4 * dp, focusStroke)
                val avail = right - left - 2 * pad
                if (block.strip != null) {
                    if (block.strip == GuideBlock.Strip.NO_DATA && avail > 12 * dp) {
                        canvas.drawText(ellipsize(emptyRowText?.invoke(ch) ?: context.getString(R.string.guide_no_data), dimTitlePaint, avail),
                            left + pad, textY, dimTitlePaint)
                    }
                    continue
                }
                val canReplay = block.program != null && com.tellybox.tv.data.catchup.CatchupUrls.available(
                    channels[row].catchupDays, block.start, block.stop, now)
                val title = (if (canReplay) "↶ " else "") + (block.program?.title ?: context.getString(R.string.guide_no_info))
                if (avail > 12 * dp) {
                    val tp = if ((dimPast && block.stop <= now) || block.program == null) dimTitlePaint else titlePaint
                    canvas.drawText(ellipsize(title, tp, avail), left + pad, top + rowHeight * 0.45f, tp)
                    val time = "${timeFormat.format(Date(block.start))} – ${timeFormat.format(Date(block.stop))}"
                    canvas.drawText(ellipsize(time, captionPaint, avail), left + pad, top + rowHeight * 0.78f, captionPaint)
                }
            }
        }

        if (now in viewStart..viewEnd && channels.isNotEmpty()) {
            val x = channelWidth + (now - viewStart) / 60_000f * pxPerMinute
            // Stop at the last row instead of drawing on into empty space below the list.
            val bottom = minOf(height.toFloat(), headerHeight + visibleRows * rowHeight)
            canvas.drawLine(x, headerHeight - 6 * dp, x, bottom, nowLine)
        }
    }

    private fun drawHeader(canvas: Canvas, viewEnd: Long) {
        var t = viewStart
        val y = headerHeight * 0.65f
        val dayFormat = DateFormat.getMediumDateFormat(context)
        canvas.drawText(dayFormat.format(Date(focusTime)), pad, y, captionPaint)
        while (t < viewEnd) {
            val x = channelWidth + (t - viewStart) / 60_000f * pxPerMinute
            val label = timeFormat.format(Date(t))
            // Don't let a label hang off the right edge where it gets cut in half.
            if (x + 4 * dp + captionPaint.measureText(label) <= width) {
                canvas.drawText(label, x + 4 * dp, y, captionPaint)
            }
            t += SLOT
        }
    }

    private fun ellipsizeName(text: String, paint: TextPaint, width: Float): String {
        val shortened = ellipsize(text, paint, width)
        if (shortened == text) return text
        val prefix = shortened.removeSuffix("…").trimEnd()
        if (text.getOrNull(prefix.length)?.isWhitespace() == true) return prefix + "…"
        val boundary = prefix.lastIndexOf(' ')
        return if (boundary > 0) prefix.substring(0, boundary).trimEnd() + "…" else shortened
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    companion object {
        val SLOT = GuideBlocks.SLOT
        fun floorSlot(t: Long) = GuideBlocks.floorSlot(t)
    }
}
