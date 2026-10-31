package com.aodcards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews

/** Measures the actual widget rows in pixels, including fonts, avatars and margins. */
object WidgetLayout {
    private data class MeasurementKey(
        val content: Any, val images: List<Int>, val environment: WidgetEnvironment,
        val width: Int, val scale: Float, val customScale: Int, val customHideIcon: Boolean, val showText: Boolean, val lines: Int
    )
    // Keys contain text/geometry only. Never retain a View, Context or Bitmap here.
    private val heights = object : android.util.LruCache<MeasurementKey, Int>(256 * 1024) {
        override fun sizeOf(key: MeasurementKey, value: Int) = 512 + key.content.toString().length * 2
    }
    internal var measurementCount = 0L
        private set

    fun clearCache() { heights.evictAll() }

    fun fitCount(context: Context, cards: List<NotificationCard>, settings: SettingsRepository,
        music: MusicTrack?, width: Int, height: Int, scale: Float): Int {
        val limit = minOf(cards.size, WidgetRows.MAX_VISIBLE_CARDS)
        if (limit == 0 || height <= 0) return limit
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = (dp * density + .5f).toInt()
        val rootPadding = px(2f * scale)
        val contentWidth = (px(width.toFloat()) - 2 * rootPadding).coerceAtLeast(1)
        val parent by lazy { FrameLayout(context) }
        val environment = WidgetEnvironment.read(context)
        fun cached(content: Any, images: List<Int> = emptyList(), create: () -> Int): Int {
            val key = MeasurementKey(content, images, environment, width, scale, settings.effectiveCustomNotificationScale, settings.customHideAppIcon, settings.showText, settings.maxTextLines)
            return heights.get(key) ?: create().also { heights.put(key, it) }
        }
        fun measure(view: View): Int {
            measurementCount++
            view.forceLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            return view.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
        }
        // Reuse one inflated row; every card uses the same template and dimensions.
        var row: View? = null
        val rowHeights = cards.take(limit).map { card ->
            cached(card.layoutContent(),
                listOf(if (card.previewImage != null) 1 else 0, if (card.senderImage != null) 1 else 0,
                    if (card.isCustomContent) card.previewImage?.width ?: 0 else 0,
                    if (card.isCustomContent) card.previewImage?.height ?: 0 else 0)) {
                val views = RemoteViews(context.packageName, R.layout.widget_notification_row)
                views.setViewVisibility(R.id.card_shell, View.VISIBLE)
                WidgetRows.apply(context, views, card, WidgetRows.notificationRow, scale, width)
                if (row == null) row = views.apply(context, parent) else views.reapply(context, row)
                measure(row!!)
            }
        }
        val musicHeight = if (music == null) 0 else cached(music.copy(artwork = null)) {
            val views = RemoteViews(context.packageName, R.layout.widget_music_pill)
            WidgetRows.applyMusic(context, views, music, scale)
            measure(views.apply(context, parent))
        }
        val available = px(height.toFloat()) - 2 * rootPadding - musicHeight
        val needsOverflow = cards.size > limit || rowHeights.sum() > available
        val overflowHeight = if (!needsOverflow) 0 else cached("overflow:${settings.overflowBadgePosition}:${cards.sumOf { it.groupCount.coerceAtLeast(1) }}") {
            val views = RemoteViews(context.packageName, R.layout.widget_overflow)
            WidgetRows.applyOverflowScale(context, views, scale)
            val badgeId = listOf(R.id.widget_overflow_left, R.id.widget_overflow_center, R.id.widget_overflow_right)[settings.overflowBadgePosition]
            views.setViewVisibility(badgeId, View.VISIBLE)
            views.setTextViewText(badgeId, "+${cards.sumOf { it.groupCount.coerceAtLeast(1) }}")
            measure(views.apply(context, parent))
        }
        var used = 0
        return rowHeights.takeWhile { rowHeight ->
            if (used + rowHeight <= available - overflowHeight) {
                used += rowHeight
                true
            } else false
        }.size
    }
}
