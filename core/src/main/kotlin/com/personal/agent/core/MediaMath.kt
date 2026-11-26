package com.personal.agent.core

/** Pure seek arithmetic (H7). Android-side MediaController values are passed in as plain numbers. */
object MediaMath {
    /**
     * PlaybackState.position is a snapshot taken at lastPositionUpdateTime; while playing it must be
     * extrapolated to "now" using playback speed.
     */
    fun currentPosition(position: Long, lastUpdateElapsed: Long, nowElapsed: Long, speed: Float, playing: Boolean): Long {
        if (!playing || lastUpdateElapsed <= 0) return position.coerceAtLeast(0)
        val elapsed = (nowElapsed - lastUpdateElapsed).coerceAtLeast(0)
        return (position + elapsed * speed).toLong().coerceAtLeast(0)
    }

    sealed class SeekPlan {
        data class Seek(val target: Long, val clampedToEnd: Boolean, val clampedToStart: Boolean) : SeekPlan()
        object NotSupported : SeekPlan()
        object Live : SeekPlan()
    }

    fun planRelative(current: Long, deltaMs: Long, durationMs: Long?, canSeek: Boolean): SeekPlan {
        if (!canSeek) return SeekPlan.NotSupported
        val dur = durationMs?.takeIf { it > 0 }
        val raw = current + deltaMs
        if (dur == null && raw < 0) return SeekPlan.Seek(0, false, true)
        val maxTarget = if (dur != null) (dur - 5_000).coerceAtLeast(0) else Long.MAX_VALUE
        return when {
            raw < 0 -> SeekPlan.Seek(0, false, true)
            raw > maxTarget -> SeekPlan.Seek(maxTarget, true, false)
            else -> SeekPlan.Seek(raw, false, false)
        }
    }

    fun planAbsolute(targetMs: Long, durationMs: Long?, canSeek: Boolean): SeekPlan {
        if (!canSeek) return SeekPlan.NotSupported
        val dur = durationMs?.takeIf { it > 0 }
        if (dur != null && targetMs > dur - 1_000) return SeekPlan.Seek((dur - 5_000).coerceAtLeast(0), true, false)
        return SeekPlan.Seek(targetMs.coerceAtLeast(0), false, false)
    }

    /** Was the seek applied? Allow 2 s slack plus whatever time passed while playing. */
    fun verify(target: Long, observed: Long, toleranceMs: Long = 2_500) = kotlin.math.abs(observed - target) <= toleranceMs

    fun spokenDuration(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts += plural(h, "hour")
        if (m > 0) parts += plural(m, "minute")
        if (s > 0 || parts.isEmpty()) parts += plural(s, "second")
        return parts.joinToString(" ")
    }

    fun clock(ms: Long): String {
        val t = (ms / 1000).toInt(); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
}
