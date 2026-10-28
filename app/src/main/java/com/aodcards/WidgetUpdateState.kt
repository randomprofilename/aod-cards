package com.aodcards

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap

internal data class WidgetStyle(
    val contentMode: Int, val compact: Boolean, val scale: Int, val lines: Int,
    val ongoing: Int, val music: Boolean, val showWhenScreenOn: Boolean,
    val overflowPosition: Int, val blocked: Set<String>
) {
    companion object {
        fun read(settings: SettingsRepository) = WidgetStyle(settings.contentMode,
            settings.compactMode, settings.widgetScale, settings.maxTextLines,
            settings.ongoingMode, settings.musicPillEnabled, settings.showWhenScreenOn,
            settings.overflowBadgePosition, settings.blockedPackages)
    }
}

/** Includes time formatting and font/density changes; retains no Activity or bitmap. */
internal data class WidgetEnvironment(
    val configuration: Configuration, val density: Float,
    val timeZone: String, val clock24: Boolean
) {
    companion object {
        fun read(context: Context) = WidgetEnvironment(Configuration(context.resources.configuration),
            context.resources.displayMetrics.density,
            java.util.TimeZone.getDefault().id, android.text.format.DateFormat.is24HourFormat(context))
    }
}

internal data class ImageRevision(val generation: Int, val width: Int, val height: Int) {
    companion object {
        fun of(bitmap: Bitmap?) = bitmap?.let { ImageRevision(it.generationId, it.width, it.height) }
    }
}

internal data class CardRevision(
    val content: NotificationCard, val image: ImageRevision?, val sender: ImageRevision?
)

/** Source identity/ranking do not change pixels after grouping and ordering. */
internal fun NotificationCard.layoutContent() = copy(key = "", postTime = 0,
    systemRank = 0, systemGroupKey = null, isSystemGroupSummary = false,
    previewImage = null, senderImage = null)

internal data class WidgetUpdateKey(
    val cards: List<CardRevision>, val music: MusicTrack?, val artwork: ImageRevision?,
    val style: WidgetStyle, val environment: WidgetEnvironment,
    val width: Int, val height: Int, val hasAccess: Boolean
) {
    companion object {
        fun create(context: Context, cards: List<NotificationCard>, music: MusicTrack?,
            settings: SettingsRepository, width: Int, height: Int, hasAccess: Boolean): WidgetUpdateKey {
            val (visibleCards, visibleMusic) = WidgetRenderModel.prepare(cards, settings, music, context)
            return WidgetUpdateKey(
                visibleCards.map { CardRevision(it.layoutContent(),
                    ImageRevision.of(it.previewImage), ImageRevision.of(it.senderImage)) },
                visibleMusic?.copy(artwork = null), ImageRevision.of(visibleMusic?.artwork), WidgetStyle.read(settings),
                WidgetEnvironment.read(context), width, height, hasAccess)
        }
    }
}
