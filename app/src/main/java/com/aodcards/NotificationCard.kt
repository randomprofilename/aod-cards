package com.aodcards

import android.graphics.Bitmap

data class NotificationCard(
    val key: String,
    val packageName: String,
    val appName: String,
    val senderName: String?,
    val title: String,
    val text: String,
    val postTime: Long,
    val ongoing: Boolean,
    val previewImage: Bitmap?,
    val senderImage: Bitmap?,
    val groupCount: Int = 1,
    val isOngoingGroup: Boolean = false,
    val systemRank: Int = Int.MAX_VALUE,
    val systemGroupKey: String? = null,
    val isSystemGroupSummary: Boolean = false,
    val isCollapsedGroup: Boolean = false,
    val isConversation: Boolean = false,
    val displayTime: Long? = postTime,
    val lockscreenVisibility: Int = android.app.Notification.VISIBILITY_PRIVATE,
    val isCustomContent: Boolean = false,
    val lockscreenVisibilityOverride: Int = android.service.notification.NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE
)
