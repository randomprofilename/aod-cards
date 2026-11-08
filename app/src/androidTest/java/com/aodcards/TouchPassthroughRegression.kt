package com.aodcards

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aodcards.xposed.LockStarTouchPassthrough
import com.samsung.systemui.lockstar.presentation.view.core.widget.OverlayDimContainer

/** Real view geometry and dispatch, with the manager's before-dispatch decision simulated. */
object TouchPassthroughRegression {
    private class Fixture(val context: Context) {
        val policy = LockStarTouchPassthrough()
        val parentEvents = mutableListOf<Int>()
        var childTouches = 0
        var originalCalls = 0
        val extras = FrameLayout(context)
        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (policy.shouldBypass(this, event)) {
                    parentEvents.add(event.actionMasked)
                    return true // SystemUI selects its swipe-unlock handler.
                }
                originalCalls++
                return super.dispatchTouchEvent(event)
            }
        }.apply { addView(extras, FrameLayout.LayoutParams(400, 400)) }
        var clock = android.os.SystemClock.uptimeMillis()

        fun item(ours: Boolean = true, x: Int = 0, y: Int = 0): FrameLayout {
            val info = AppWidgetProviderInfo().apply {
                provider = if (ours) ComponentName("com.aodcards", "com.aodcards.AodCardsWidgetProvider")
                    else ComponentName("other.widget", "other.widget.Provider")
            }
            val host = object : AppWidgetHostView(context) {
                override fun getAppWidgetInfo() = info
            }.apply {
                addView(View(context).apply {
                    isClickable = true // The existing Unlock on tap action must never see DOWN.
                    setOnTouchListener { _, _ -> childTouches++; true }
                }, FrameLayout.LayoutParams(100, 100))
            }
            val wrapper = FrameLayout(context).apply {
                isClickable = true // LockStar's clickable contents_container, including gaps.
                addView(host, FrameLayout.LayoutParams(100, 100))
            }
            val overlay = OverlayDimContainer(context).apply {
                addView(wrapper, FrameLayout.LayoutParams(100, 100))
            }
            return FrameLayout(context).apply {
                addView(overlay, FrameLayout.LayoutParams(100, 100))
                extras.addView(this, FrameLayout.LayoutParams(100, 100).apply {
                    leftMargin = x
                    topMargin = y
                })
            }
        }

        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 400)
        }

        fun nextGesture(): Long { clock += 100; return clock }

        fun event(start: Long, action: Int, x: Float = 50f, y: Float = 50f,
                  queryOnly: Boolean = false): Boolean {
            val event = MotionEvent.obtain(start, start + action, action, x, y, 0)
            return try {
                if (queryOnly) policy.shouldBypass(root, event) else root.dispatchTouchEvent(event)
            } finally { event.recycle() }
        }

        fun down(x: Float = 50f, y: Float = 50f): Boolean =
            event(nextGesture(), MotionEvent.ACTION_DOWN, x, y, queryOnly = true)
    }

    fun check(context: Context) {
        Fixture(context).apply {
            val target = item()
            layout()
            val start = nextGesture()
            check(event(start, MotionEvent.ACTION_DOWN, queryOnly = true))
            target.translationX = 200f
            // Repeated DOWN queries must not reclassify after geometry changes.
            check(event(start, MotionEvent.ACTION_DOWN))
            check(event(start, MotionEvent.ACTION_POINTER_DOWN, 350f, queryOnly = true))
            check(event(start, MotionEvent.ACTION_POINTER_UP, 350f, queryOnly = true))
            check(event(start, MotionEvent.ACTION_MOVE, 350f))
            check(event(start, MotionEvent.ACTION_UP, 350f))
            check(event(start, MotionEvent.ACTION_UP, 350f, queryOnly = true))
            check(childTouches == 0 && originalCalls == 0) { "Bypassed gesture reached LockStar" }
            check(parentEvents == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP))
            check(!down()) { "New gesture reused stale widget bounds" }
            check(down(250f)) { "Moved widget bounds were not refreshed" }
            val cancelled = nextGesture()
            check(event(cancelled, MotionEvent.ACTION_DOWN, 250f, queryOnly = true))
            check(event(cancelled, MotionEvent.ACTION_CANCEL, 350f, queryOnly = true))
            check(event(cancelled, MotionEvent.ACTION_CANCEL, 350f, queryOnly = true))
            check(!down()) { "Cancelled gesture leaked into the next DOWN" }
        }
        Fixture(context).apply {
            item(x = 200)
            item(ours = false)
            layout()
            val start = nextGesture()
            event(start, MotionEvent.ACTION_DOWN)
            event(start, MotionEvent.ACTION_MOVE)
            event(start, MotionEvent.ACTION_UP)
            check(originalCalls == 3 && childTouches == 3 && parentEvents.isEmpty()) {
                "Another provider lost its normal dispatch"
            }
            val outside = nextGesture()
            check(!event(outside, MotionEvent.ACTION_DOWN, 150f, queryOnly = true))
            check(!event(outside, MotionEvent.ACTION_MOVE, 250f, queryOnly = true)) {
                "Gesture starting outside was stolen when it entered the widget"
            }
        }
        Fixture(context).apply {
            val target = item()
            val other = item(ours = false)
            layout()
            check(!down()) { "Overlapping later item lost its interaction" }
            target.elevation = 10f
            check(down()) { "Higher-Z AOD Cards item was not selected" }
            other.elevation = 20f
            check(!down()) { "Higher-Z other item lost its interaction" }
            other.visibility = View.GONE
            check(down())
            target.visibility = View.INVISIBLE
            check(!down()) { "Hidden widget captured a gesture" }
            target.visibility = View.VISIBLE
            extras.visibility = View.GONE
            check(!down()) { "Hidden ancestor captured a gesture" }
        }
        Fixture(context).apply {
            val target = item(x = 100, y = 100)
            layout()
            target.pivotX = 0f
            target.pivotY = 0f
            target.scaleX = 0.5f
            target.scaleY = 0.5f
            check(down(125f, 125f))
            check(!down(175f, 175f)) { "Unscaled bounds captured a gesture" }
            target.scaleX = 1f
            target.scaleY = 1f
            target.rotation = 45f
            check(!down(160f, 110f)) { "Rotated widget's bounding-box corner captured a gesture" }
            root.translationX = 30f
            root.translationY = 40f
            val matrix = Matrix()
            target.transformMatrixToGlobal(matrix)
            val p = floatArrayOf(50f, 50f)
            matrix.mapPoints(p)
            check(down(p[0], p[1])) { "Screen coordinates/transforms were not mapped correctly" }
            extras.scrollX = 40
            target.rotation = 0f
            check(down(100f, 190f)) { "Scrolled widget coordinates were not mapped correctly" }
            target.translationX = 500f
            check(!down(650f, 190f)) { "Widget outside ancestor bounds captured a gesture" }
        }
        Fixture(context).apply {
            val target = item()
            layout()
            val overlay = target.getChildAt(0) as FrameLayout
            val wrapper = overlay.getChildAt(0) as FrameLayout
            val start = nextGesture()
            check(!event(start, MotionEvent.ACTION_MOVE, queryOnly = true)) { "Missing DOWN initiated bypass" }
            wrapper.removeAllViews()
            wrapper.addView(AppWidgetHostView(context))
            check(!down()) { "Unbound host captured a gesture" }
            overlay.removeView(wrapper)
            val ordinary = FrameLayout(context).apply { addView(wrapper) }
            target.removeAllViews()
            target.addView(ordinary)
            layout()
            check(!down()) { "Ordinary host was treated as a LockStar widget" }
        }
        Fixture(context).apply {
            item()
            layout()
            check(down())
            extras.removeAllViews()
            check(!down()) { "Removed widget left a stale hit target" }
            item(x = 200)
            layout()
            check(down(250f)) { "Reinflated widget was not discovered" }
        }
        Fixture(context).apply {
            val target = item()
            layout()
            val overlay = target.getChildAt(0) as FrameLayout
            val wrapper = overlay.getChildAt(0) as FrameLayout
            // Blank content is still inside LockStar's clickable wrapper.
            (wrapper.getChildAt(0) as AppWidgetHostView).removeAllViews()
            check(down(90f, 90f)) { "Blank widget space did not pass through" }
        }
    }
}
