package com.aodcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Both the live widget and preview use this exact RemoteViews tree. */
object WidgetRenderer {
    const val DEFAULT_WIDTH_DP = 398
    const val DEFAULT_HEIGHT_DP = 289
    fun sizeFor(options: android.os.Bundle): Pair<Int, Int> {
        val sizes = if (android.os.Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            options.getParcelableArrayList<android.util.SizeF>(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().filter { it.width > 0 && it.height > 0 }.distinct()
        } else emptyList()
        val size = sizes.singleOrNull()
        val width = size?.width?.toInt()?.takeIf { it > 0 }
            ?: options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 }
            ?: options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val height = size?.height?.toInt()?.takeIf { it > 0 }
            ?: options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }
            ?: options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        return width to height
    }

    internal fun createLiveViews(context: Context, settings: SettingsRepository, scale: Float,
        width: Int, height: Int, screenInteractive: Boolean, plan: WidgetRenderModel,
        useFixedSurface: Boolean): RemoteViews {
        if (useFixedSurface) {
            // LockStar can stop laying out new children after folding. Mount the image
            // with the same intrinsic size in both states and let XML fill the host.
            return RemoteViews(context.packageName, if (settings.tapToUnlockEnabled)
                R.layout.widget_cards_surface_tap_unlock else R.layout.widget_cards_surface).apply {
                if (settings.tapToUnlockEnabled) {
                    setOnClickPendingIntent(R.id.widget_root, UnlockActivity.pendingIntent(context))
                }
                val hidden = screenInteractive && !settings.showWhenScreenOn
                if (hidden || (plan.cards.isEmpty() && plan.music == null && plan.overflow == 0)) {
                    setImageViewResource(R.id.widget_surface, R.drawable.widget_surface_blank)
                } else {
                    val content = createViews(context, plan.cards, settings, plan.music, scale, width, height, plan = plan)
                    setImageViewBitmap(R.id.widget_surface, drawSurface(context, content, width, height))
                }
            }
        }
        return createViews(context, plan.cards, settings, plan.music, scale, width, height,
            screenInteractive, plan, enableUnlockTap = true).apply {
            setViewLayoutWidth(R.id.widget_root, width.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.widget_root, height.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    private fun drawSurface(context: Context, views: RemoteViews, width: Int, height: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (width * density).roundToInt().coerceAtLeast(1)
        val heightPx = (height * density).roundToInt().coerceAtLeast(1)
        val root = views.apply(context, FrameLayout(context))
        root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(context.resources.getDimensionPixelSize(R.dimen.widget_surface_width),
            context.resources.getDimensionPixelSize(R.dimen.widget_surface_height), Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width.toFloat() / widthPx, bitmap.height.toFloat() / heightPx)
        root.draw(canvas)
        return bitmap
    }

    fun createViews(
        context: Context,
        cards: List<NotificationCard>,
        settings: SettingsRepository,
        music: MusicTrack?,
        scale: Float,
        width: Int,
        height: Int,
        screenInteractive: Boolean = false,
        plan: WidgetRenderModel = WidgetRenderModel.build(cards, settings, music, width, height, context),
        enableUnlockTap: Boolean = false
    ): RemoteViews {
        val unlockOnTap = enableUnlockTap && settings.tapToUnlockEnabled
        // Distinct IDs replace the host tree when disabling taps, removing both
        // the old PendingIntent and View's clickable flag without remote reflection.
        val views = RemoteViews(context.packageName,
            if (unlockOnTap) R.layout.widget_cards_dynamic_tap_unlock else R.layout.widget_cards_dynamic_passive)
        // The entire live widget, including gaps and hidden content, is a tap target.
        // In-app previews must never request an unlock.
        if (unlockOnTap) views.setOnClickPendingIntent(R.id.widget_root, UnlockActivity.pendingIntent(context))
        val hideContent = screenInteractive && !settings.showWhenScreenOn
        // The provider sets explicit root bounds for LockStar. Hidden content is
        // cleared as well as hidden; visibility alone would retain secrets in the host.
        views.setViewVisibility(R.id.widget_root, android.view.View.VISIBLE)
        views.setViewVisibility(R.id.widget_content, if (hideContent) android.view.View.INVISIBLE else android.view.View.VISIBLE)
        WidgetRows.applyRootScale(context, views, scale)
        val orderedCards = if (hideContent) emptyList() else plan.cards
        views.removeAllViews(R.id.widget_notification_container)
        orderedCards.forEachIndexed { index, card ->
            val row = RemoteViews(context.packageName, R.layout.widget_notification_row)
            row.setViewVisibility(WidgetRows.notificationRow.shell, android.view.View.VISIBLE)
            WidgetRows.apply(context, row, card, WidgetRows.notificationRow, scale, width)
            // Reuse positions without hashing notification keys. Each row fully binds its state.
            views.addStableView(R.id.widget_notification_container, row, index)
        }
        WidgetRows.applyMusic(context, views, if (hideContent) null else plan.music, scale)
        val overflow = if (hideContent) 0 else plan.overflow
        val showOverflow = overflow > 0
        views.setViewVisibility(R.id.widget_overflow_row, when {
            !showOverflow -> android.view.View.GONE
            hideContent -> android.view.View.INVISIBLE
            else -> android.view.View.VISIBLE
        })
        overflowBadgeIds.forEachIndexed { index, badgeId ->
            views.setTextViewText(badgeId, if (overflow > 0) "+$overflow" else "")
            val visible = showOverflow && index == settings.overflowBadgePosition
            views.setViewVisibility(badgeId, if (visible) android.view.View.VISIBLE else android.view.View.GONE)
        }
        return views
    }

    private val overflowBadgeIds = listOf(R.id.widget_overflow_left, R.id.widget_overflow_center, R.id.widget_overflow_right)
}
