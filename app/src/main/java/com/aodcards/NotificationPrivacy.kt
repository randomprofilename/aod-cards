package com.aodcards

import android.app.KeyguardManager
import android.app.Notification
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService.Ranking

/** Read-only policy: app preferences may restrict this result, never broaden it. */
internal data class NotificationPrivacy(
    val locked: Boolean,
    val showNotifications: Boolean,
    val showPrivateContent: Boolean
) {
    fun mode(visibility: Int, override: Int): Int? {
        if (!locked) return 0
        if (!showNotifications || visibility == Notification.VISIBILITY_SECRET ||
            override == Notification.VISIBILITY_SECRET) return null
        if (override == Notification.VISIBILITY_PRIVATE) return 2
        return if (visibility == Notification.VISIBILITY_PUBLIC ||
            visibility == Notification.VISIBILITY_PRIVATE && showPrivateContent) 0 else 2
    }

    fun cards(cards: List<NotificationCard>, settings: SettingsRepository): List<NotificationCard> =
        cards.mapNotNull { card ->
            if (card.packageName in settings.blockedPackages) return@mapNotNull null
            val systemMode = mode(card.lockscreenVisibility, card.lockscreenVisibilityOverride)
                ?: return@mapNotNull null
            redact(card, maxOf(systemMode, settings.contentMode))
        }

    fun music(track: MusicTrack?, settings: SettingsRepository): MusicTrack? = track?.takeIf {
        settings.musicPillEnabled && settings.contentMode == 0 &&
            it.packageName !in settings.blockedPackages &&
            (!locked || showNotifications && showPrivateContent && it.lockscreenAllowed)
    }

    companion object {
        // AOSP readable settings; OEMs may withhold them. Missing/denied values fail closed.
        val settingNames = listOf("lock_screen_show_notifications", "lock_screen_allow_private_notifications")

        fun read(context: Context): NotificationPrivacy = try {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            val locked = keyguard.isDeviceLocked || keyguard.isKeyguardLocked || !power.isInteractive
            val disabled = context.getSystemService(DevicePolicyManager::class.java).getKeyguardDisabledFeatures(null)
            fun enabled(name: String) = Settings.Secure.getInt(context.contentResolver, name, 0) == 1
            NotificationPrivacy(locked,
                enabled(settingNames[0]) && disabled and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS == 0,
                enabled(settingNames[1]) && disabled and DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS == 0)
        } catch (_: Exception) { NotificationPrivacy(true, false, false) }

        fun redact(card: NotificationCard, mode: Int): NotificationCard = when (mode) {
            1 -> card.copy(title = "", text = "", previewImage = null)
            2 -> card.copy(senderName = null, senderImage = null, title = "", text = "", previewImage = null)
            else -> card
        }
    }
}
