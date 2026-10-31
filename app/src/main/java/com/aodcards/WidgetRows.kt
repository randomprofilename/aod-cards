package com.aodcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import java.util.Date

data class WidgetRowIds(
    val shell: Int, val contentColumn: Int, val header: Int, val groupBadge: Int, val inlineGroupBadge: Int, val avatarFrame: Int, val avatar: Int, val badgeFrame: Int, val badgeIcon: Int,
    val sender: Int, val time: Int, val title: Int, val text: Int, val image: Int,
    val groupThumbnail: Int, val groupCountRight: Int
)

object WidgetRows {
    private val icons = android.util.LruCache<String, Bitmap>(64)
    const val MAX_VISIBLE_CARDS = 12
    fun scaleFactor(size: Int): Float = when (size.coerceIn(0, 2)) { 0 -> .85f; 2 -> 1.15f; else -> 1f }
    val notificationRow = WidgetRowIds(R.id.card_shell,R.id.card_content_column,R.id.card_header,R.id.card_group_badge,R.id.card_author_count,R.id.avatar_container,R.id.avatar_main,R.id.app_icon_badge_frame,R.id.app_icon_badge,R.id.app_name,R.id.card_time,R.id.card_title,R.id.card_text,R.id.card_image,R.id.card_group_thumbnail,R.id.card_group_count_right)
    private fun musicSubtitle(context: Context, track: MusicTrack) = if (track.paused) {
        context.getString(R.string.music_paused) + if (track.artist.isNotBlank()) " · ${track.artist}" else ""
    } else track.artist.ifBlank { context.getString(R.string.music_playing) }

    private fun setImage(views: RemoteViews, id: Int, bitmap: Bitmap?) {
        if (bitmap == null) views.setImageViewResource(id, 0)
        else views.setImageViewBitmap(id, bitmap)
    }

    fun applyMusic(context: Context, views: RemoteViews, track: MusicTrack?, scale: Float = 1f) {
        val visible = track != null
        views.setViewVisibility(R.id.music_pill, if (visible) android.view.View.VISIBLE else android.view.View.GONE)
        if (track == null) {
            views.setTextViewText(R.id.music_title, "")
            views.setTextViewText(R.id.music_artist, "")
            setImage(views, R.id.music_art, null)
            return
        }
        views.setTextViewText(R.id.music_title, track.title)
        views.setTextViewText(R.id.music_artist, musicSubtitle(context, track))
        val hasArtwork = track.artwork != null
        views.setViewVisibility(R.id.music_art, if (hasArtwork) android.view.View.VISIBLE else android.view.View.GONE)
        views.setViewVisibility(R.id.music_placeholder, if (hasArtwork) android.view.View.GONE else android.view.View.VISIBLE)
        setImage(views, R.id.music_art, track.artwork)
        applyMusicScale(context = context, views = views, scale = scale)
    }

    fun apply(context: Context, views: RemoteViews, card: NotificationCard, ids: WidgetRowIds, scale: Float = 1f, widthDp: Int = 360) {
        applyRow(context, views, card, ids, scale, widthDp, SettingsRepository(context))
    }

    private fun applyRow(context: Context, views: RemoteViews, card: NotificationCard, ids: WidgetRowIds,
        widgetScale: Float, widthDp: Int, prefs: SettingsRepository) {
        val scale = if (card.isCustomContent && card.previewImage != null && prefs.customNotificationsEnabled)
            scaleFactor(prefs.effectiveCustomNotificationScale) else widgetScale
        val appIcon = icons.get(card.packageName) ?: runCatching { drawableToBitmap(runCatching { context.packageManager.getApplicationIcon(card.packageName) }.getOrElse { context.packageManager.defaultActivityIcon }) }.getOrNull()?.also { icons.put(card.packageName, it) }
        val isGrouped = card.isCollapsedGroup && !card.isOngoingGroup
        val hideAppIcon = card.isCustomContent && card.previewImage != null && prefs.customHideAppIcon
        if (card.senderImage != null && !isGrouped) {
            setImage(views, ids.avatar, card.senderImage)
            views.setViewVisibility(ids.badgeFrame, android.view.View.VISIBLE)
            setImage(views, ids.badgeIcon, appIcon)
        } else {
            setImage(views, ids.avatar, appIcon)
            views.setViewVisibility(ids.badgeFrame, android.view.View.GONE)
            setImage(views, ids.badgeIcon, null)
        }
        val thumbnail = if (isGrouped) card.senderImage else null
        setImage(views, ids.groupThumbnail, thumbnail)
        views.setViewVisibility(ids.groupThumbnail, if (thumbnail != null) android.view.View.VISIBLE else android.view.View.GONE)
        views.setTextViewText(ids.groupCountRight, if (isGrouped) card.groupCount.toString() else "")
        views.setViewVisibility(ids.groupCountRight, if (isGrouped) android.view.View.VISIBLE else android.view.View.GONE)
        views.setTextViewText(ids.sender, card.senderName?.takeIf { it.isNotBlank() } ?: card.appName)
        val time = card.displayTime?.let { android.text.format.DateFormat.getTimeFormat(context).format(Date(it)) }.orEmpty()
        views.setTextViewText(ids.time, time)
        views.setViewVisibility(ids.time, if (time.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        // Keep the count beside short names, but let long names use the entire
        // available header instead of truncating at an arbitrary 180dp.
        val metrics = context.resources.displayMetrics
        val timePaint = android.text.TextPaint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f * scale, metrics)
        }
        fun badgeWidth(minimumDp: Float, textSp: Float): Float {
            val paint = android.text.TextPaint().apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp * scale, metrics)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            return maxOf(minimumDp * scale * metrics.density,
                paint.measureText(card.groupCount.toString()) + 8f * scale * metrics.density)
        }
        val inlineBadgeWidth = badgeWidth(18f, 11f)
        val groupBadgeWidth = badgeWidth(22f, 12f)
        val countPaint = android.text.TextPaint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f * scale, metrics)
        }
        // Keep the thumbnail and counter in the same position for one to three digits.
        // Allow larger counts to expand so they remain readable.
        val widestDigit = ('0'..'9').maxOf { countPaint.measureText(it.toString()) }
        val countWidth = kotlin.math.ceil(maxOf(widestDigit * 3, countPaint.measureText(card.groupCount.toString())) + 4f * scale * metrics.density)
        val countReserve = if (isGrouped) (8f + if (thumbnail != null) 44f else 0f) * scale * metrics.density + countWidth
            else if (card.groupCount > 1 && !card.isOngoingGroup) inlineBadgeWidth + 6f * scale * metrics.density else 0f
        val compact = widthDp < 300
        val reservedDp = (4f + if (compact) 20f else 56f) * widgetScale +
            (if (hideAppIcon) 0f else 46f + if (compact) 9f else 14f) * widgetScale +
            if (time.isEmpty()) 0f else 5f * scale
        val nameWidth = (widthDp * metrics.density - reservedDp * metrics.density - countReserve - timePaint.measureText(time)).toInt() - 4
        views.setInt(ids.sender, "setMaxWidth", nameWidth.coerceAtLeast(1))
        views.setViewVisibility(ids.header, if (card.isOngoingGroup) android.view.View.GONE else android.view.View.VISIBLE)
        views.setTextViewText(ids.groupBadge, card.groupCount.toString())
        views.setViewVisibility(ids.groupBadge, if (card.isOngoingGroup) android.view.View.VISIBLE else android.view.View.GONE)
        val showInlineCount = card.groupCount > 1 && !card.isOngoingGroup && !isGrouped
        views.setTextViewText(ids.inlineGroupBadge, if (showInlineCount) card.groupCount.toString() else "")
        views.setViewVisibility(ids.inlineGroupBadge, if (showInlineCount) android.view.View.VISIBLE else android.view.View.GONE)
        views.setViewVisibility(ids.avatarFrame, if (card.isOngoingGroup || hideAppIcon) android.view.View.GONE else android.view.View.VISIBLE)
        views.setTextViewText(ids.title, card.title)
        views.setViewVisibility(ids.title, if (card.title.isNotBlank() && !card.isOngoingGroup) android.view.View.VISIBLE else android.view.View.GONE)
        val bodyVisible = (prefs.showText || card.ongoing) && card.text.isNotBlank()
        val body = if (bodyVisible && card.isConversation && card.groupCount > 1 && !isGrouped) {
            val density = metrics.density
            fun px(dp: Float) = (dp * scale * density + .5f).toInt()
            val bodyWidth = ((widthDp * density + .5f).toInt() - 2 * px(2f) - 2 * px(if (compact) 10f else 14f) - 2 * px(if (compact) 0f else 14f) - px(46f) - px(if (compact) 9f else 14f) - 2).coerceAtLeast(1)
            lastVisibleLines(card.text, prefs.maxTextLines, bodyWidth, context, scale)
        } else if (bodyVisible) card.text else ""
        views.setTextViewText(ids.text, body)
        views.setViewVisibility(ids.text, if (bodyVisible) android.view.View.VISIBLE else android.view.View.GONE)
        val badgeTextInset = if (card.isOngoingGroup) (groupBadgeWidth + 10f * scale * metrics.density + .5f).toInt() else 0
        views.setViewPadding(ids.text, 0, 0, badgeTextInset, 0)
        views.setViewVisibility(ids.image, if (card.previewImage != null) android.view.View.VISIBLE else android.view.View.GONE)
        setImage(views, ids.image, card.previewImage)
        views.setInt(ids.text, "setMaxLines", prefs.maxTextLines)
        views.setInt(ids.shell, "setBackgroundResource", surfaceForScale(widgetScale))
        applyCardScale(context, views, ids, scale, compact, widgetScale)
        // The shared widget style owns the outer geometry; custom size only affects content.
        views.setViewLayoutMargin(ids.shell, RemoteViews.MARGIN_START,
            (if (compact) 0f else 14f) * widgetScale, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(ids.shell, RemoteViews.MARGIN_END,
            (if (compact) 0f else 14f) * widgetScale, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(ids.shell, RemoteViews.MARGIN_BOTTOM, 8f * widgetScale, TypedValue.COMPLEX_UNIT_DIP)
        if (hideAppIcon) views.setViewLayoutMargin(ids.contentColumn, RemoteViews.MARGIN_START, 0f, TypedValue.COMPLEX_UNIT_PX)
        if (card.isCustomContent && card.previewImage != null && !card.isOngoingGroup && !isGrouped) {
            views.setViewVisibility(ids.avatarFrame, if (hideAppIcon) android.view.View.GONE else android.view.View.VISIBLE)
            setImage(views, ids.avatar, if (hideAppIcon) null else appIcon)
            setImage(views, ids.badgeIcon, null)
            views.setViewVisibility(ids.header, android.view.View.GONE)
            views.setViewVisibility(ids.badgeFrame, android.view.View.GONE)
            val density = metrics.density
            fun px(value: Float) = (value * scale * density + .5f).toInt()
            fun sharedPx(value: Float) = (value * widgetScale * density + .5f).toInt()
            val horizontalPad = if (hideAppIcon) px(18f) else sharedPx(if (compact) 10f else 14f)
            views.setViewPadding(ids.shell, horizontalPad, px(12f), horizontalPad, px(12f))
            // Width is already the host's final width; only insets scale with the style.
            // Match the rounded pixel arithmetic used by the actual RemoteViews tree.
            val rootPadding = (2f * widgetScale * density + .5f).toInt()
            val iconGap = if (hideAppIcon) 0 else sharedPx(if (compact) 9f else 14f)
            val iconReserve = if (hideAppIcon) 0 else sharedPx(46f) + iconGap
            val innerPx = ((widthDp * density + .5f).toInt() - 2 * rootPadding -
                2 * ((if (compact) 0f else 14f) * widgetScale * density + .5f).toInt() - 2 * horizontalPad - iconReserve).coerceAtLeast(1)
            val image = card.previewImage
            val heightPx = minOf(innerPx * image.height.toFloat() / image.width,
                120f * scale * density)
            views.setViewLayoutMargin(ids.contentColumn, RemoteViews.MARGIN_START, iconGap.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutMargin(ids.image, RemoteViews.MARGIN_TOP, 0f, TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutHeight(ids.image, kotlin.math.ceil(heightPx).toFloat(), TypedValue.COMPLEX_UNIT_PX)
        }
        views.setViewLayoutWidth(ids.groupCountRight, countWidth, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutWidth(ids.inlineGroupBadge, kotlin.math.ceil(inlineBadgeWidth).toFloat(), TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutWidth(ids.groupBadge, kotlin.math.ceil(groupBadgeWidth).toFloat(), TypedValue.COMPLEX_UNIT_PX)
    }

    /** TextView truncates multiline text at the end; conversations need the newest lines. */
    internal fun lastVisibleLines(text: String, maxLines: Int, widthPx: Int, context: Context, scale: Float): String {
        if (text.isEmpty() || maxLines <= 0) return text
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f * scale, context.resources.displayMetrics)
        }
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        if (layout.lineCount <= maxLines) return text
        return text.substring(layout.getLineStart(layout.lineCount - maxLines)).trimStart('\n', '\r')
    }

    fun applyRootScale(context: Context, views: RemoteViews, scale: Float) {
        val density = context.resources.displayMetrics.density
        fun px(value: Float) = (value * scale * density + .5f).toInt()
        views.setViewPadding(R.id.widget_root, px(2f), px(2f), px(2f), px(2f))
        applyOverflowScale(context, views, scale)
    }

    fun applyOverflowScale(context: Context, views: RemoteViews, scale: Float) {
        val density = context.resources.displayMetrics.density
        fun px(value: Float) = (value * scale * density + .5f).toInt()
        views.setTextViewTextSize(R.id.widget_overflow_left, TypedValue.COMPLEX_UNIT_SP, 11f * scale)
        views.setTextViewTextSize(R.id.widget_overflow_center, TypedValue.COMPLEX_UNIT_SP, 11f * scale)
        views.setTextViewTextSize(R.id.widget_overflow_right, TypedValue.COMPLEX_UNIT_SP, 11f * scale)
        for (id in listOf(R.id.widget_overflow_left, R.id.widget_overflow_center, R.id.widget_overflow_right)) {
            views.setViewPadding(id, px(6f), 0, px(6f), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setViewLayoutHeight(id, 22f * scale, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_START, 28f * scale, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_END, 28f * scale, TypedValue.COMPLEX_UNIT_DIP)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutHeight(R.id.widget_overflow_row, 22f * scale, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    private fun surfaceForScale(scale: Float): Int = when {
        scale < .95f -> R.drawable.card_surface_small
        scale > 1.05f -> R.drawable.card_surface_large
        else -> R.drawable.card_surface
    }

    private fun applyCardScale(context: Context, views: RemoteViews, ids: WidgetRowIds, scale: Float, compact: Boolean, widgetScale: Float) {
        val density = context.resources.displayMetrics.density
        fun px(value: Float) = (value * scale * density + .5f).toInt()
        fun size(id: Int, width: Float? = null, height: Float? = null, itemScale: Float = scale) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            width?.let { views.setViewLayoutWidth(id, it * itemScale, TypedValue.COMPLEX_UNIT_DIP) }
            height?.let { views.setViewLayoutHeight(id, it * itemScale, TypedValue.COMPLEX_UNIT_DIP) }
        }
        fun margin(id: Int, side: Int, value: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) views.setViewLayoutMargin(id, side, value * scale, TypedValue.COMPLEX_UNIT_DIP)
        }
        fun textSize(id: Int, value: Float) = views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, value * scale)

        val horizontalPad = ((if (compact) 10f else 14f) * widgetScale * density + .5f).toInt()
        views.setViewPadding(ids.shell, horizontalPad, px(8f), horizontalPad, px(8f))
        views.setInt(ids.shell, "setMinimumHeight", px(48f))
        margin(ids.shell, RemoteViews.MARGIN_BOTTOM, 8f)
        margin(ids.shell, RemoteViews.MARGIN_START, if (compact) 0f else 14f)
        margin(ids.shell, RemoteViews.MARGIN_END, if (compact) 0f else 14f)
        size(ids.avatarFrame, 46f, 46f, widgetScale); size(ids.avatar, 44f, 44f, widgetScale); size(ids.badgeFrame, 22f, 22f, widgetScale)
        size(ids.groupThumbnail, 36f, 36f); margin(ids.groupThumbnail, RemoteViews.MARGIN_START, 8f)
        textSize(ids.groupCountRight, 14f); margin(ids.groupCountRight, RemoteViews.MARGIN_START, 8f)
        margin(ids.groupCountRight, RemoteViews.MARGIN_END, 0f)
        // Clear the old white frame when a host reapplies views from an earlier build.
        views.setInt(ids.badgeFrame, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        views.setViewPadding(ids.badgeFrame, 0, 0, 0, 0)
        views.setViewLayoutMargin(ids.contentColumn, RemoteViews.MARGIN_START,
            (if (compact) 9f else 14f) * widgetScale, TypedValue.COMPLEX_UNIT_DIP)
        textSize(ids.sender, 15f); textSize(ids.time, 11f); textSize(ids.title, 13f); textSize(ids.text, 13f)
        margin(ids.time, RemoteViews.MARGIN_START, 5f)
        margin(ids.title, RemoteViews.MARGIN_TOP, 1f)
        size(ids.inlineGroupBadge, height = 18f); textSize(ids.inlineGroupBadge, 11f)
        margin(ids.inlineGroupBadge, RemoteViews.MARGIN_START, 4f)
        margin(ids.inlineGroupBadge, RemoteViews.MARGIN_END, 2f)
        size(ids.groupBadge, height = 22f); textSize(ids.groupBadge, 12f)
        margin(ids.groupBadge, RemoteViews.MARGIN_TOP, 5f)
        margin(ids.groupBadge, RemoteViews.MARGIN_END, 10f)
        size(ids.image, height = 64f); margin(ids.image, RemoteViews.MARGIN_TOP, 4f)
    }

    private fun applyMusicScale(context: Context?, views: RemoteViews, scale: Float) {
        fun text(id: Int, size: Float) = views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size * scale)
        val density = context?.resources?.displayMetrics?.density ?: 1f
        fun px(value: Float) = (value * scale * density + .5f).toInt()
        views.setViewPadding(R.id.music_pill, px(10f), px(6f), px(12f), px(6f))
        views.setInt(R.id.music_pill, "setBackgroundResource", surfaceForScale(scale))
        views.setInt(R.id.music_pill, "setMinimumHeight", px(58f))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutHeight(R.id.music_pill, android.view.ViewGroup.LayoutParams.WRAP_CONTENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutWidth(R.id.music_art_container, 44f * scale, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.music_art_container, 44f * scale, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutWidth(R.id.music_activity, 24f * scale, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.music_activity, 24f * scale, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.music_info, RemoteViews.MARGIN_START, 10f * scale, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.music_pill, RemoteViews.MARGIN_BOTTOM, 5f * scale, TypedValue.COMPLEX_UNIT_DIP)
        }
        text(R.id.music_title, 14f); text(R.id.music_artist, 12f); text(R.id.music_placeholder, 22f); text(R.id.music_activity, 13f)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(canvas)
        return bitmap
    }
}
