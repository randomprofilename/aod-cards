package com.aodcards

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

data class CardsSnapshot(
    val cards: List<NotificationCard> = emptyList(),
    val music: MusicTrack? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val updatedAt: Long = 0,
    val sourcePackages: Set<String> = emptySet(),
    val blockedCount: Int = 0
)

/** One immutable snapshot for the widget, preview and connection indicators. */
object CardState {
    @Volatile var value = CardsSnapshot()
        private set
    private val observers = linkedSetOf<() -> Unit>()

    fun publish(snapshot: CardsSnapshot) {
        check(Looper.myLooper() == Looper.getMainLooper())
        value = snapshot
        observers.toList().forEach { it() }
    }

    fun observe(observer: () -> Unit) { observers += observer }
    fun removeObserver(observer: () -> Unit) { observers -= observer }

    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, NotificationListener::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}
