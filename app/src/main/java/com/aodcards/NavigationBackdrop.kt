package com.aodcards

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/** Captures only the page behind the bar; the navigation controls stay sharp. */
class NavigationBackdrop(context: Context, private val source: View) : View(context) {
    private val surface = context.getColor(R.color.ui_surface)
    private val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val tint = Color.argb(if (night) 170 else 195, Color.red(surface), Color.green(surface), Color.blue(surface))
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private val sourceLocation = IntArray(2)
    private val backdropLocation = IntArray(2)
    private var snapshot: Bitmap? = null
    private var needsCapture = true
    private var observer: ViewTreeObserver? = null
    private val scrolled = ViewTreeObserver.OnScrollChangedListener { needsCapture = true }
    private val laidOut = ViewTreeObserver.OnGlobalLayoutListener { needsCapture = true }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (needsCapture || hasDirtyContent(source)) capture()
        true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val radius = 18f * resources.displayMetrics.density
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        needsCapture = true
        observer = source.viewTreeObserver.also {
            it.addOnPreDrawListener(preDraw)
            it.addOnScrollChangedListener(scrolled)
            it.addOnGlobalLayoutListener(laidOut)
        }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(preDraw)
            it.removeOnScrollChangedListener(scrolled)
            it.removeOnGlobalLayoutListener(laidOut)
        }
        observer = null
        snapshot = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        destination.set(0, 0, w, h)
        needsCapture = true
    }

    private fun hasDirtyContent(view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        if (view.isDirty) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasDirtyContent(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun capture() {
        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return
        // A quarter-resolution strip bounds memory and work to the bar's area.
        val w = (width / 4).coerceAtLeast(1)
        val h = (height / 4).coerceAtLeast(1)
        val bitmap = snapshot?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { snapshot = it }
        bitmap.eraseColor(context.getColor(R.color.ui_background))
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(backdropLocation)
        Canvas(bitmap).apply {
            scale(w.toFloat() / this@NavigationBackdrop.width,
                h.toFloat() / this@NavigationBackdrop.height)
            translate((sourceLocation[0] - backdropLocation[0]).toFloat(),
                (sourceLocation[1] - backdropLocation[1]).toFloat())
            source.draw(this)
        }
        needsCapture = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = snapshot
        if (bitmap == null) {
            canvas.drawColor(surface)
        } else {
            canvas.drawBitmap(bitmap, null, destination, paint)
            canvas.drawColor(tint)
        }
    }
}
