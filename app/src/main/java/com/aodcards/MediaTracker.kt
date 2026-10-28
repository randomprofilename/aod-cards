package com.aodcards

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log

/** Keeps media changes independent of notification posting behaviour of each player. */
class MediaTracker(context: Context, private val handler: Handler, private val changed: () -> Unit) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, NotificationListener::class.java)
    private val controllers = mutableListOf<MediaController>()
    private var listening = false
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onSessionDestroyed() = changed()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        bind(sessions.orEmpty())
        changed()
    }

    fun start() {
        if (listening) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            listening = true
            bind(manager.getActiveSessions(component))
        } catch (e: Exception) { Log.w("AODCards", "Could not observe media sessions", e) }
    }

    private fun bind(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        controllers.addAll(sessions)
        controllers.forEach { it.registerCallback(callback, handler) }
    }

    fun stop() {
        if (listening) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        listening = false
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
    }
}
