package dev.seamless.android

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat

/**
 * When you share a YouTube video to "Send to Mac", the YouTube app's share link has no timestamp.
 * If notification access is granted we read the playing position from the YouTube app's MediaSession,
 * pause it, and add &t=<seconds> so the Mac continues exactly where you were.
 */
object YouTube {
    private val PACKAGES = listOf("com.google.android.youtube", "com.google.android.apps.youtube.music", "app.revanced.android.youtube")

    fun videoId(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
        return when {
            host == "youtu.be" -> u.pathSegments.firstOrNull()
            host == "youtube.com" || host == "music.youtube.com" -> when {
                u.path == "/watch" -> u.getQueryParameter("v")
                u.pathSegments.size >= 2 && u.pathSegments[0] in setOf("shorts", "live") -> u.pathSegments[1]
                else -> null
            }
            else -> null
        }
    }

    fun hasMediaAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun withPlaybackPosition(ctx: Context, url: String): String {
        val id = videoId(url) ?: return url
        val host = if (url.contains("music.youtube.com")) "music.youtube.com" else "www.youtube.com"
        val src = Uri.parse(url)
        val existing = src.getQueryParameter("t")
        val seconds = if (existing != null) null else currentPositionSeconds(ctx, pause = true)
        val b = Uri.Builder().scheme("https").authority(host).path("/watch").appendQueryParameter("v", id)
        src.getQueryParameter("list")?.let { b.appendQueryParameter("list", it) }
        when {
            existing != null -> b.appendQueryParameter("t", existing)
            seconds != null && seconds > 0 -> b.appendQueryParameter("t", "${seconds}s")
        }
        return b.build().toString()
    }

    private fun currentPositionSeconds(ctx: Context, pause: Boolean): Long? {
        if (!hasMediaAccess(ctx)) return null
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val sessions = runCatching {
            msm.getActiveSessions(ComponentName(ctx, MediaAccessService::class.java))
        }.getOrNull() ?: return null
        val c: MediaController = sessions.firstOrNull { it.packageName in PACKAGES } ?: return null
        val st = c.playbackState ?: return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (pause && st.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        return pos / 1000
    }
}
