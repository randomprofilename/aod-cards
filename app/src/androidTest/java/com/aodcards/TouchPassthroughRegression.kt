package com.aodcards

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aodcards.xposed.LockStarTouchPassthrough
import com.samsung.systemui.lockstar.presentation.view.core.widget.OverlayDimContainer

/** Exercises Android's actual gesture targeting, including LockStar's clickable parent. */
object TouchPassthroughRegression {
    fun check(context: Context) {
        val provider = AppWidgetProviderInfo().apply {
            this.provider = ComponentName("com.aodcards", "com.aodcards.AodCardsWidgetProvider")
        }
        val host = object : AppWidgetHostView(context) {
            override fun getAppWidgetInfo() = provider
        }
        var childTouches = 0
        host.addView(View(context).apply {
            isClickable = true // Simulates an existing Unlock on tap PendingIntent.
            setOnTouchListener { _, _ -> childTouches++; true }
        }, FrameLayout.LayoutParams(100, 100))
        val wrapper = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (LockStarTouchPassthrough.shouldSkipDispatch(this)) return false
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            isClickable = true // LockStar's res/1a.xml contents_container.
            addView(host, FrameLayout.LayoutParams(100, 100))
        }
        val overlay = OverlayDimContainer(context).apply {
            addView(wrapper, FrameLayout.LayoutParams(100, 100))
        }
        val parentEvents = mutableListOf<Int>()
        val root = FrameLayout(context).apply {
            addView(overlay, FrameLayout.LayoutParams(100, 100))
            setOnTouchListener { _, event -> parentEvents.add(event.actionMasked); true }
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 300, 300)
        }
        fun gesture(outside: Boolean = false) {
            val start = android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
                .forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(start, start + index * 20L, action,
                        if (outside && index > 0) 200f else 50f, 50f, 0)
                    try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                }
        }
        layout()
        check(LockStarTouchPassthrough.shouldSkipDispatch(wrapper))
        gesture(outside = true)
        check(childTouches == 0) { "AOD Cards received touch events" }
        check(parentEvents == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            "The parent did not receive the complete swipe: $parentEvents"
        }

        // Other providers inside LockStar must remain interactive.
        provider.provider = ComponentName("other.widget", "other.widget.Provider")
        parentEvents.clear()
        gesture()
        check(childTouches == 3 && parentEvents.isEmpty()) { "Another widget lost its gesture" }

        // An AOD Cards widget outside LockStar must retain normal widget behavior.
        provider.provider = ComponentName("com.aodcards", "com.aodcards.AodCardsWidgetProvider")
        overlay.removeView(wrapper)
        val ordinaryParent = FrameLayout(context).apply {
            addView(wrapper, FrameLayout.LayoutParams(100, 100))
        }
        root.removeAllViews()
        root.addView(ordinaryParent, FrameLayout.LayoutParams(100, 100))
        layout()
        check(!LockStarTouchPassthrough.shouldSkipDispatch(wrapper))
        gesture()
        check(childTouches == 6 && parentEvents.isEmpty()) { "An ordinary host lost its gesture" }

        // An unbound host must be ignored safely during initialization.
        val unbound = FrameLayout(context).apply { addView(AppWidgetHostView(context)) }
        overlay.addView(unbound)
        check(!LockStarTouchPassthrough.shouldSkipDispatch(unbound))
    }
}
