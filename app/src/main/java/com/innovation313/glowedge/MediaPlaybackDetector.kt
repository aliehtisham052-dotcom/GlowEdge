package com.innovation313.glowedge

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Detects whether real music/media is actively playing, using Android's
 * MediaSessionManager. This is an exact signal reported by the playing app itself
 * (YouTube, JioSaavn, Spotify, any naat/music player) — NOT a microphone-based guess.
 *
 * When any active media session is in the PLAYING state, media is playing. Plain
 * conversation, WhatsApp voice notes, notification sounds and system beeps do not
 * create a playing media session, so they are naturally excluded.
 *
 * Requires Notification Access (the same permission the notification listener uses),
 * because getActiveSessions() needs the NotificationListenerService component.
 */
object MediaPlaybackDetector {

    /**
     * @return true if at least one media session is currently PLAYING.
     * Never throws; returns false if permission is missing or anything goes wrong.
     */
    fun isMusicPlaying(context: Context): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return false
            val listener = ComponentName(context, GlowNotificationService::class.java)
            val sessions = msm.getActiveSessions(listener)
            for (controller in sessions) {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) return true
            }
            false
        } catch (_: SecurityException) {
            // Notification Access not granted yet.
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The title/artist of the currently playing media, if any — for optionally showing
     * "now playing" later. Returns null when nothing is playing or metadata is absent.
     */
    fun nowPlaying(context: Context): String? {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return null
            val listener = ComponentName(context, GlowNotificationService::class.java)
            for (controller in msm.getActiveSessions(listener)) {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    val md = controller.metadata ?: continue
                    val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    return when {
                        !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$title — $artist"
                        !title.isNullOrBlank() -> title
                        else -> null
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
