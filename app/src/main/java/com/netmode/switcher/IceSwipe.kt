package com.netmode.switcher

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A horizontal swipe on a freezer row. The row's parts each take their own touches, so the listener
 * sits on all of them ([parts]) and stays silent until the finger clearly moves sideways: until
 * then taps, long presses and the list's scrolling see every event as usual.
 */
internal class IceSwipe(
    private val row: View,
    parts: List<View>,
    private val allowed: () -> Boolean,
    private val dragChanged: (Boolean) -> Unit,
    private val commit: (String) -> Unit,
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(row.context).scaledTouchSlop.toFloat()
    private val minThreshold = IceSwipeRules.MIN_THRESHOLD_DP * row.resources.displayMetrics.density
    private var key: String? = null
    /** The part following this gesture; null when there is nothing to follow. */
    private var owner: View? = null
    private var pointer = 0
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var anchorX = 0f
    private var dragging = false

    init {
        parts.forEach { it.setOnTouchListener(this) }
    }

    /** Called on every bind. A rebind of the same app (the status tick) leaves a moving row alone. */
    fun bind(newKey: String) {
        if (newKey == key) return
        key = newKey
        owner = null
        setDragging(false)
        if (row.translationX != 0f) {
            row.animate().cancel()
            row.translationX = 0f
        }
    }

    // Only ever consumes a sideways drag; every other event reaches the view's own click handling.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Another finger on another part of the row: the first one keeps the drag.
                if (dragging && v !== owner) return false
                if (dragging) endDrag()
                owner = null
                if (allowed()) {
                    owner = v
                    pointer = e.getPointerId(0)
                    downX = e.rawX
                    downY = e.rawY
                    downAt = e.eventTime
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (v !== owner) return false
                val index = e.findPointerIndex(pointer)
                if (index < 0) return dragging
                // The row moves, so only raw coordinates stay comparable.
                val x = e.getRawX(index)
                if (!dragging && !decide(v, e, x, e.getRawY(index))) return false
                val limit = row.width.toFloat()
                row.translationX = (x - anchorX).coerceIn(-limit, limit)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (v !== owner) return false
                owner = null
                if (!dragging) return false
                val done = IceSwipeRules.commits(row.translationX, IceSwipeRules.threshold(row.width.toFloat(), minThreshold))
                endDrag()
                if (done) key?.let(commit)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return drop(v)
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pointer) return drop(v)
            }
        }
        return dragging && v === owner
    }

    /** True once this gesture became a swipe. */
    private fun decide(v: View, e: MotionEvent, x: Float, y: Float): Boolean {
        // The long press has already fired: this finger belongs to its menu.
        if (e.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()) {
            owner = null
            return false
        }
        when (IceSwipeRules.decide(x - downX, y - downY, slop)) {
            IceSwipeRules.Decision.PENDING -> return false
            IceSwipeRules.Decision.REJECT -> {
                owner = null
                return false
            }
            IceSwipeRules.Decision.CLAIM -> Unit
        }
        setDragging(true)
        v.parent?.requestDisallowInterceptTouchEvent(true)
        row.animate().cancel()
        anchorX = x - row.translationX
        // The view will not see this gesture again: clear its press and its long-press timer.
        val cancel = MotionEvent.obtain(e)
        cancel.action = MotionEvent.ACTION_CANCEL
        v.onTouchEvent(cancel)
        cancel.recycle()
        return true
    }

    private fun drop(v: View): Boolean {
        if (v !== owner) return false
        owner = null
        if (!dragging) return false
        endDrag()
        return true
    }

    private fun endDrag() {
        setDragging(false)
        row.animate().translationX(0f)
            .setDuration(row.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
    }

    private fun setDragging(value: Boolean) {
        if (dragging == value) return
        dragging = value
        dragChanged(value)
    }
}

/** The swipe's decisions, apart from the views so they can be tested. */
internal object IceSwipeRules {
    enum class Decision { PENDING, CLAIM, REJECT }

    const val MIN_THRESHOLD_DP = 72f
    private const val THRESHOLD_FRACTION = 0.33f

    /** Sideways must beat vertical by this much: anything less might be the start of a scroll. */
    private const val DOMINANCE = 2f

    /** Vertical movement past the slop is the list's; it would take the gesture at that point anyway. */
    fun decide(dx: Float, dy: Float, slop: Float): Decision {
        val x = abs(dx)
        val y = abs(dy)
        return when {
            y >= slop -> Decision.REJECT
            x >= slop && x >= y * DOMINANCE -> Decision.CLAIM
            else -> Decision.PENDING
        }
    }

    /** A third of the row, but never so little that a short flick counts, nor beyond half of it. */
    fun threshold(rowWidth: Float, minimum: Float): Float =
        minOf(rowWidth / 2, maxOf(rowWidth * THRESHOLD_FRACTION, minimum))

    fun commits(offset: Float, threshold: Float): Boolean = threshold > 0f && abs(offset) >= threshold
}
