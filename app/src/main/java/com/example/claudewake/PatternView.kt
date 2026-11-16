package com.example.claudewake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lock-screen style 3x3 pattern canvas.
 *
 * Reports the drawing as dot indices 0..8 (row-major) in touch order, auto-filling the middle dot
 * when the stroke jumps over it (0 -> 2 also selects 1), exactly like Android's LockPatternView.
 */
class PatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called for each newly selected dot with the current length. */
    var onProgress: ((Int) -> Unit)? = null
    /** Called on finger up with the sequence (may be shorter than 4). */
    var onComplete: ((List<Int>) -> Unit)? = null

    private val centers = Array(9) { PointF() }
    private var cell = 0f
    private var dotRadius = 0f
    private val selected = ArrayList<Int>(9)
    private var finger: PointF? = null
    private var flash = 0 // 0 idle, 1 error, 2 success

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h).toFloat()
        cell = side / 3f
        dotRadius = cell * 0.125f
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        for (i in 0 until 9) {
            centers[i].set(left + cell * (i % 3 + 0.5f), top + cell * (i / 3 + 0.5f))
        }
    }

    fun reset() {
        selected.clear()
        finger = null
        flash = 0
        invalidate()
    }

    fun flashError() {
        flash = 1
        invalidate()
        postDelayed({ if (flash == 1) { flash = 0; invalidate() } }, 620)
    }

    fun flashOk() {
        flash = 2
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                finger = PointF(e.x, e.y)
                touch(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                finger = PointF(e.x, e.y)
                touch(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finger = null
                onComplete?.invoke(selected.toList())
                invalidate()
            }
        }
        return true
    }

    private fun touch(x: Float, y: Float) {
        val hit = nearest(x, y) ?: return
        if (selected.contains(hit)) return
        val prev = selected.lastOrNull()
        selected.add(hit)
        if (prev != null) {
            val mid = middle(prev, hit)
            if (mid >= 0 && !selected.contains(mid)) selected.add(selected.size - 1, mid)
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onProgress?.invoke(selected.size)
        invalidate()
    }

    private fun nearest(x: Float, y: Float): Int? {
        var best = -1
        var bestDist = cell * 0.42f
        for (i in 0 until 9) {
            val d = hypot(x - centers[i].x, y - centers[i].y)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return if (best >= 0) best else null
    }

    private fun middle(a: Int, b: Int): Int {
        val dr = b / 3 - a / 3
        val dc = b % 3 - a % 3
        if (dr == 0 && dc == 0) return -1
        if (dr % 2 != 0 || dc % 2 != 0) return -1
        return (a / 3 + dr / 2) * 3 + (a % 3 + dc / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val accent = when (flash) {
            1 -> Ui.DANGER
            2 -> Ui.OK
            else -> Ui.ACCENT
        }
        ring.strokeWidth = Ui.dp(context, 1.6f).toFloat()
        for (i in 0 until 9) {
            val on = selected.contains(i)
            ring.color = if (on) accent else 0x40F5F1ED
            canvas.drawCircle(centers[i].x, centers[i].y, dotRadius, ring)
            if (on) {
                fill.color = Ui.withAlpha(accent, 0x33)
                canvas.drawCircle(centers[i].x, centers[i].y, dotRadius * 2.1f, fill)
                fill.color = accent
                canvas.drawCircle(centers[i].x, centers[i].y, dotRadius * 0.62f, fill)
            }
        }
        line.strokeWidth = Ui.dp(context, 4.5f).toFloat()
        line.color = accent
        for (i in 1 until selected.size) {
            val a = centers[selected[i - 1]]
            val b = centers[selected[i]]
            canvas.drawLine(a.x, a.y, b.x, b.y, line)
        }
        val f = finger
        if (f != null && selected.isNotEmpty() && nearest(f.x, f.y) == null) {
            line.color = Ui.withAlpha(accent, 0x80)
            val last = centers[selected.last()]
            canvas.drawLine(last.x, last.y, f.x, f.y, line)
        }
    }
}

/**
 * A phone-shaped map of where the 3x3 dots really sit on the lock screen.
 * The three numbers are screen fractions: left/width, top/height, side/width.
 */
class GridPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Screen width / height, so the outline matches the real device. */
    var aspect = 0.45f
    /** left/width, top/height, side/width — null draws nothing but the outline. */
    var grid: Triple<Float, Float, Float>? = null

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()

    fun set(left: Float, top: Float, side: Float) {
        grid = Triple(left, top, side)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = (h * aspect).coerceAtMost(width.toFloat())
        val x0 = (width - w) / 2f
        body.set(x0, 0f, x0 + w, h)

        outline.color = 0x33F5F1ED
        outline.strokeWidth = Ui.dp(context, 1.2f).toFloat()
        val r = w * 0.14f
        canvas.drawRoundRect(body, r, r, outline)

        val g = grid ?: return
        val side = g.third * w
        val cell = side / 3f
        val left = x0 + g.first * w
        val top = g.second * h
        for (i in 0 until 9) {
            val cx = left + cell * (i % 3 + 0.5f)
            val cy = top + cell * (i / 3 + 0.5f)
            fill.color = Ui.withAlpha(Ui.ACCENT, 0x33)
            canvas.drawCircle(cx, cy, cell * 0.34f, fill)
            fill.color = Ui.ACCENT
            canvas.drawCircle(cx, cy, max(Ui.dp(context, 1.6f).toFloat(), cell * 0.11f), fill)
        }
    }
}
