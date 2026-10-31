package com.aodcards

data class WidgetRenderModel(
    val cards: List<NotificationCard>,
    val music: MusicTrack?,
    val overflow: Int
) {
    companion object {
        fun build(cards: List<NotificationCard>, settings: SettingsRepository, music: MusicTrack?, width: Int, height: Int, context: android.content.Context): WidgetRenderModel {
            val (safeCards, safeMusic) = prepare(cards, settings, music, context)
            val scale = WidgetRows.scaleFactor(settings.widgetScale)
            val count = WidgetLayout.fitCount(context, safeCards, settings, safeMusic, width, height, scale)
            return WidgetRenderModel(safeCards.take(count), safeMusic,
                safeCards.drop(count).sumOf { it.groupCount.coerceAtLeast(1) })
        }

        internal fun prepare(cards: List<NotificationCard>, settings: SettingsRepository,
            music: MusicTrack?, context: android.content.Context): Pair<List<NotificationCard>, MusicTrack?> {
            var imageBytes = 0
            // Filter before grouping: an ongoing summary can contain multiple packages.
            val allowed = cards.filterNot { it.packageName in settings.blockedPackages }
            val prepared = CardGrouper.prepare(allowed, settings.ongoingMode)
            // App-only mode represents app presence, rather than individual chats.
            // Preserve the separate aggregate for grouped ongoing notifications.
            val visible = if (settings.contentMode == 2)
                prepared.distinctBy { it.packageName to it.isOngoingGroup } else prepared
            val safeCards = visible.map { card ->
                when (settings.contentMode) {
                    1 -> card.copy(title = "", text = "", previewImage = null,
                        isOngoingGroup = false,
                        appName = if (card.isOngoingGroup) context.getString(R.string.localized_001) else card.appName)
                    2 -> card.copy(senderName = null, senderImage = null, title = "", text = "", previewImage = null,
                        groupCount = 1, isCollapsedGroup = false,
                        isOngoingGroup = false,
                        appName = if (card.isOngoingGroup) context.getString(R.string.localized_001) else card.appName)
                    else -> card
                }.let { if (settings.compactMode) it.copy(senderImage = null, previewImage = null) else it }
                    .let { if (it.isCustomContent && !settings.customNotificationsEnabled) it.copy(previewImage = null) else it }.let { safe ->
                    val bytes = (safe.previewImage?.allocationByteCount ?: 0) + (safe.senderImage?.allocationByteCount ?: 0)
                    if (imageBytes + bytes > 2 * 1024 * 1024) safe.copy(previewImage = null, senderImage = null)
                    else safe.also { imageBytes += bytes }
                }
            }
            val safeMusic = music?.takeIf { settings.musicPillEnabled && it.packageName !in settings.blockedPackages }?.let {
                if (settings.contentMode != 0) null else if (settings.compactMode) it.copy(artwork = null) else it
            }
            return safeCards to safeMusic
        }
    }
}
