package com.innovation313.glowedge

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

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
     * True if Notification Access is granted to us. Without it, getActiveSessions()
     * throws SecurityException and we cannot read what's playing.
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    /**
     * Ask Android to (re)bind our notification listener.
     *
     * This matters: when the user grants Notification Access while the app is already
     * running, Android does NOT bind the listener service until something triggers it —
     * so getActiveSessions() keeps failing and Music Only appears broken. Requesting a
     * rebind fixes that immediately, without a reboot.
     */
    fun requestRebind(context: Context) {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context, GlowNotificationService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * @return true if music/media is currently playing.
     *
     * Primary signal: MediaSession — the playing app itself reports PLAYING, so naat and
     * music from any player are caught, while talking and notification sounds are not.
     *
     * Fallback: if Notification Access isn't granted (so MediaSession is unreadable), we
     * fall back to whether the music audio stream is active. That's less precise, but it
     * means Music Only still does something sensible instead of silently never glowing.
     */
    fun isMusicPlaying(context: Context): Boolean {
        if (hasNotificationAccess(context)) {
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as? MediaSessionManager
                if (msm != null) {
                    val listener = ComponentName(context, GlowNotificationService::class.java)
                    for (controller in msm.getActiveSessions(listener)) {
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) return true
                    }
                    return false
                }
            } catch (_: SecurityException) {
                // Listener not bound yet — ask for a rebind and fall through this once.
                requestRebind(context)
            } catch (_: Exception) {
            }
        }
        // Fallback: is the music stream active at all?
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.isMusicActive == true
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
