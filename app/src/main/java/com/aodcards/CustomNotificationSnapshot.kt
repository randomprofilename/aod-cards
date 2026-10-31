package com.aodcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews

/** Bounded, non-interactive fallback for notifications with custom content but no text extras. */
internal object CustomNotificationSnapshot {
    data class Stats(val attempts: Long = 0, val successes: Long = 0,
        val totalNanos: Long = 0, val maxNanos: Long = 0, val lastBitmapBytes: Int = 0)
    @Volatile var stats = Stats()
        private set

    @Suppress("DEPRECATION")
    fun render(context: Context, remote: RemoteViews, packageName: String, scale: Float = 1f): Bitmap? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        var result: Bitmap? = null
        return try {
            // RemoteViews loads its own resources. No foreign code or security bypass is needed.
            val packaged = context.createPackageContext(packageName, 0)
            val themed = ContextThemeWrapper(packaged, android.R.style.Theme_DeviceDefault)
            val view = remote.apply(themed, FrameLayout(themed))
            // Measure a narrower logical layout for larger text/icons before fitting the
            // result into the same card width. Simply resizing one bitmap cancels the scale.
            val density = context.resources.displayMetrics.density
            val width = (320f * density / scale + .5f).toInt().coerceAtLeast(1)
            val maxHeight = (256f * density + .5f).toInt().coerceAtLeast(1)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            val height = view.measuredHeight
            if (height !in 1..maxHeight || view.measuredHeightAndState and View.MEASURED_STATE_TOO_SMALL != 0) return null
            view.layout(0, 0, width, height)
            // Bound the temporary canvas, then remove padding before applying the byte budget.
            val ratio = minOf(1f, 1024f / width)
            val bitmap = Bitmap.createBitmap((width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(bitmap.width.toFloat() / width, bitmap.height.toFloat() / height)
            view.draw(canvas)
            trimPadding(bitmap)?.let(::limitSize).also { result = it }
        } catch (e: Exception) {
            Log.w("AODCards", "Unable to render custom notification from $packageName", e)
            null
        } finally {
            val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - started
            stats = stats.copy(attempts = stats.attempts + 1,
                successes = stats.successes + if (result != null) 1 else 0,
                totalNanos = stats.totalNanos + elapsed, maxNanos = maxOf(stats.maxNanos, elapsed),
                lastBitmapBytes = result?.allocationByteCount ?: 0)
        }
    }

    private fun limitSize(bitmap: Bitmap): Bitmap {
        // Keep native sharpness for short rows. Only large content needs downsampling.
        val pixels = bitmap.width.toLong() * bitmap.height
        val ratio = minOf(1.0, kotlin.math.sqrt(262144.0 / pixels))
        if (ratio == 1.0) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Remove outer vertical slack while preserving the app's horizontal layout and text scale. */
    internal fun trimPadding(bitmap: Bitmap): Bitmap? {
        val row = IntArray(bitmap.width)
        var top = bitmap.height
        var bottom = -1
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 > 24 }) {
                top = minOf(top, y)
                bottom = y
            }
        }
        if (bottom < top) {
            bitmap.recycle()
            return null
        }
        // The card owns vertical padding. Keep a pixel for antialiased glyph edges.
        top = (top - 1).coerceAtLeast(0)
        bottom = (bottom + 1).coerceAtMost(bitmap.height - 1)
        if (top == 0 && bottom == bitmap.height - 1) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top + 1)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }
}
