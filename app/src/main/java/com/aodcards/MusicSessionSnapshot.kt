package com.aodcards

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

data class MusicTrack(
    val packageName: String,
    val title: String,
    val artist: String,
    val artwork: Bitmap?,
    val paused: Boolean = false,
    val lockscreenAllowed: Boolean = false
)

object MusicSessionSnapshot {
    @Volatile private var lastArtwork: Bitmap? = null
    @Synchronized fun clearCache() { lastArtwork = null }
    fun get(context: Context, blockedPackages: Set<String> = emptySet(), cacheAllowed: () -> Boolean = { true }): MusicTrack? {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(context, NotificationListener::class.java)
            val controllers = manager.getActiveSessions(listener).sortedBy {
                if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 0 else 1
            }
            controllers.filterNot { it.packageName in blockedPackages }.firstNotNullOfOrNull { controller ->
                val state = controller.playbackState?.state
                if (state !in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)) return@firstNotNullOfOrNull null
                val metadata = controller.metadata ?: return@firstNotNullOfOrNull null
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                if (title.isBlank()) return@firstNotNullOfOrNull null
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim()?.takeIf { it.isNotBlank() }
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
                val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                MusicTrack(controller.packageName, title, artist, art?.let { smallArtwork(it, cacheAllowed) }, state == PlaybackState.STATE_PAUSED)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun smallArtwork(bitmap: Bitmap, cacheAllowed: () -> Boolean): Bitmap {
        val side = 128
        val small = if (bitmap.width <= side && bitmap.height <= side) bitmap else {
            val scale = minOf(side.toFloat() / bitmap.width, side.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
        // MediaController can return a new bitmap with unchanged pixels on each query.
        // Reuse one small artwork so ranking-only events do not resend it to the host.
        return synchronized(this) {
            lastArtwork?.takeIf { it.sameAs(small) } ?: small.also { if (cacheAllowed()) lastArtwork = it }
        }
    }
}
