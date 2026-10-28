package com.aodcards

import android.content.Context
import android.widget.RemoteViews

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
    fun createViews(
        context: Context,
        cards: List<NotificationCard>,
        settings: SettingsRepository,
        music: MusicTrack?,
        scale: Float,
        width: Int,
        height: Int,
        screenInteractive: Boolean = false,
        plan: WidgetRenderModel = WidgetRenderModel.build(cards, settings, music, width, height, context)
    ): RemoteViews {
        // A distinct layout ID makes existing hosts reinflate after the order change.
        val views = RemoteViews(context.packageName, R.layout.widget_cards_grouped)
        val hideContent = screenInteractive && !settings.showWhenScreenOn
        // The provider sets explicit root bounds for LockStar. Hidden content is
        // cleared as well as hidden; visibility alone would retain secrets in the host.
        views.setViewVisibility(R.id.widget_root, android.view.View.VISIBLE)
        views.setViewVisibility(R.id.widget_content, if (hideContent) android.view.View.INVISIBLE else android.view.View.VISIBLE)
        WidgetRows.applyRootScale(context, views, scale)
        val orderedCards = if (hideContent) emptyList() else plan.cards
        WidgetRows.notificationRows.forEachIndexed { index, ids ->
            views.setViewVisibility(ids.shell, if (index < orderedCards.size) android.view.View.VISIBLE else android.view.View.GONE)
            if (index < orderedCards.size) {
                WidgetRows.apply(context, views, orderedCards[index], ids, scale, width)
            } else {
                // This badge is a sibling of the shell, so hiding the shell alone
                // leaves a stale badge and an occupied row when views are reapplied.
                WidgetRows.clear(views, ids)
            }
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
