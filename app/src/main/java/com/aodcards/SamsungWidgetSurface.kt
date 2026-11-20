package com.aodcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Samsung-only recovery for a host that stops measuring its remote child. */
internal object SamsungWidgetSurface {
    const val VIEWPORT = 4096
    private const val MAX_PIXELS = 4L * 1024 * 1024
    private val boundsAnimator by lazy {
        runCatching {
            RemoteViews::class.java.getMethod("semSetViewObjectAnimator",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    val available: Boolean get() = boundsAnimator != null

    fun create(context: Context, settings: SettingsRepository, frame: Bitmap?,
        widthDp: Int, heightDp: Int): RemoteViews? {
        val animate = boundsAnimator ?: return null
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        if (width !in 1..VIEWPORT || height !in 1..VIEWPORT || width.toLong() * height > MAX_PIXELS) return null
        val views = RemoteViews(context.packageName, if (settings.tapToUnlockEnabled)
            R.layout.widget_cards_intrinsic_tap_unlock else R.layout.widget_cards_intrinsic)
        // The editor measures a new, empty screen-on widget before supplying size
        // options. Give it real layout dimensions independently of its drawable;
        // the animation viewport below is only a recovery mechanism for drawing.
        views.setViewLayoutWidth(R.id.widget_root, width.toFloat(), android.util.TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutHeight(R.id.widget_root, height.toFloat(), android.util.TypedValue.COMPLEX_UNIT_PX)
        if (settings.tapToUnlockEnabled) {
            views.setOnClickPendingIntent(R.id.widget_root, UnlockActivity.pendingIntent(context))
        }
        if (frame == null) {
            // Remove the bitmap itself, rather than retaining hidden notification pixels.
            views.setInt(R.id.widget_root, "setButtonDrawable", 0)
        } else {
            val sized = Bitmap.createScaledBitmap(frame, width, height, true)
            sized.density = Bitmap.DENSITY_NONE
            // CompoundButton sets drawable bounds in onDraw. ImageView needs setFrame,
            // which the affected LockStar host can skip even after direct edge changes.
            views.setIcon(R.id.widget_root, "setButtonIcon", Icon.createWithBitmap(sized))
        }
        // A generous viewport avoids depending on a skipped child measurement. Its
        // parent clips it to the real widget rectangle; the icon has that exact size.
        // No layer, timer, polling, or system-setting permission is required.
        return runCatching {
            animate.invoke(views, R.id.widget_root, R.animator.widget_surface_bounds)
            views
        }.getOrNull()
    }
}
