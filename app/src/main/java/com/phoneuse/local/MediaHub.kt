package com.phoneuse.local

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.personal.agent.core.Apps
import com.personal.agent.core.MediaMath
import com.phoneuse.phone.PhoneUseNotificationListener
import kotlinx.coroutines.delay

/**
 * Controls other apps' active media sessions via [MediaSessionManager], authorized by
 * [PhoneUseNotificationListener]. Supports play/pause/skip, verified seekTo, playback speed,
 * custom "like" actions, now-playing metadata, and playFromSearch.
 */
class MediaHub(private val context: Context) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, PhoneUseNotificationListener::class.java)
    private var snapshot: List<Snap> = emptyList()
    var lastTargetPkg: String? = null
        private set

    data class Snap(val pkg: String, val state: Int, val position: Long, val at: Long)
    data class NowPlaying(
        val app: String,
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long?,
    )

    fun sessions(): List<MediaController> = runCatching {
        msm?.getActiveSessions(listenerComponent).orEmpty()
    }.getOrDefault(emptyList())

    val hasActiveSession: Boolean
        get() = sessions().any {
            val s = it.playbackState?.state
            s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING
        }

    /** Record which session was playing before voice input takes audio focus. */
    fun takeSnapshot() {
        snapshot = sessions().map { c ->
            val s = c.playbackState
            Snap(c.packageName, s?.state ?: PlaybackState.STATE_NONE, s?.position ?: 0L, SystemClock.elapsedRealtime())
        }
    }

    /** Target selection: named app -> playing in snapshot -> currently playing -> last target -> first session. */
    fun target(appId: String? = null): MediaController? {
        val list = sessions()
        if (list.isEmpty()) return null
        appId?.let { id ->
            val pkgs = Apps.packages[id].orEmpty()
            list.firstOrNull { it.packageName in pkgs }?.let { return remember(it) }
        }
        snapshot.firstOrNull { it.state == PlaybackState.STATE_PLAYING }?.let { s ->
            list.firstOrNull { it.packageName == s.pkg }?.let { return remember(it) }
        }
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return remember(it) }
        lastTargetPkg?.let { p -> list.firstOrNull { it.packageName == p }?.let { return remember(it) } }
        return remember(list.first())
    }

    private fun remember(c: MediaController): MediaController {
        lastTargetPkg = c.packageName
        return c
    }

    fun appName(c: MediaController): String {
        Apps.idForPackage(c.packageName)?.let { return Apps.displayName(it) }
        return runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
    }

    private fun wasPlaying(c: MediaController): Boolean =
        snapshot.firstOrNull { it.pkg == c.packageName }?.state == PlaybackState.STATE_PLAYING ||
            c.playbackState?.state == PlaybackState.STATE_PLAYING

    fun position(c: MediaController): Long {
        val s = c.playbackState ?: return 0L
        return MediaMath.currentPosition(
            s.position,
            s.lastPositionUpdateTime,
            SystemClock.elapsedRealtime(),
            s.playbackSpeed,
            s.state == PlaybackState.STATE_PLAYING,
        )
    }

    fun duration(c: MediaController): Long? =
        c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }

    sealed class SeekResult {
        data class Done(val app: String, val target: Long, val verified: Boolean, val clampedEnd: Boolean) : SeekResult()
        data class NotSupported(val app: String) : SeekResult()
        data object NoSession : SeekResult()
    }

    suspend fun seek(appId: String?, deltaMs: Long?, absoluteMs: Long?): SeekResult {
        val c = target(appId) ?: return SeekResult.NoSession
        val s = c.playbackState
        val canSeek = s != null && (s.actions and PlaybackState.ACTION_SEEK_TO) != 0L
        val dur = duration(c)
        val plan = if (absoluteMs != null) {
            MediaMath.planAbsolute(absoluteMs, dur, canSeek)
        } else {
            MediaMath.planRelative(position(c), deltaMs ?: 0L, dur, canSeek)
        }
        return when (plan) {
            is MediaMath.SeekPlan.Seek -> {
                c.transportControls.seekTo(plan.target)
                delay(450)
                val ok = MediaMath.verify(plan.target, position(c))
                SeekResult.Done(appName(c), plan.target, ok, plan.clampedToEnd)
            }
            else -> SeekResult.NotSupported(appName(c))
        }
    }

    fun pause(appId: String?): String? = target(appId)?.let { it.transportControls.pause(); appName(it) }
    fun play(appId: String?): String? = target(appId)?.let { it.transportControls.play(); appName(it) }
    fun next(appId: String?): String? = target(appId)?.let { it.transportControls.skipToNext(); appName(it) }
    fun previous(appId: String?): String? = target(appId)?.let { it.transportControls.skipToPrevious(); appName(it) }

    fun setSpeed(appId: String?, speed: Float): Pair<String, Boolean>? {
        val c = target(appId) ?: return null
        val ok = ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SET_PLAYBACK_SPEED) != 0L
        if (ok) c.transportControls.setPlaybackSpeed(speed)
        return appName(c) to ok
    }

    fun like(appId: String?): Pair<String, String?>? {
        val c = target(appId) ?: return null
        val names = listOf("like", "thumbs up", "add to liked", "favorite", "favourite", "heart", "save", "love")
        val action = c.playbackState?.customActions?.firstOrNull { a ->
            val l = a.name.toString().lowercase()
            names.any { l.contains(it) } && !l.contains("dislike")
        }
        if (action != null) c.transportControls.sendCustomAction(action, null)
        return appName(c) to action?.name?.toString()
    }

    fun nowPlaying(appId: String? = null): NowPlaying? {
        val c = target(appId) ?: return null
        val m = c.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        return NowPlaying(appName(c), title, artist, wasPlaying(c), position(c), duration(c))
    }

    fun playFromSearch(appId: String?, query: String): String? {
        val list = sessions()
        val pkgs = appId?.let { Apps.packages[it].orEmpty() }
        val c = if (pkgs != null) list.firstOrNull { it.packageName in pkgs } else null
        if (c != null && ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L) {
            c.transportControls.playFromSearch(query, null)
            return appName(c)
        }
        return null
    }
}
