package com.phoneuse.voice

import android.os.SystemClock

/**
 * Hands-free voice mode: a command spoken on the assistant's voice sheet runs, its reply is read aloud, and the
 * sheet opens again to listen for the next one. Silence or × on the sheet ends it.
 * The sheet marks a spoken command with [spoke]; the run it starts claims it with [take].
 */
object VoiceMode {
    /** A mark older than this belongs to a send that never became a run (refused, busy). */
    private const val FRESH_MS = 10_000L

    @Volatile
    private var spokenAt = 0L

    fun spoke() {
        spokenAt = SystemClock.elapsedRealtime()
    }

    fun end() {
        spokenAt = 0L
    }

    /** True once for the run a spoken command started. */
    fun take(): Boolean {
        val at = spokenAt
        spokenAt = 0L
        return isFresh(at, SystemClock.elapsedRealtime())
    }

    fun isFresh(at: Long, now: Long): Boolean = at > 0L && now - at in 0 until FRESH_MS

    /**
     * A link's site as it can be said: "https://www.youtube.com/watch" -> "Youtube", "https://m.facebook.com" ->
     * "Facebook". Null for links without a web host (tel:, intent:, settings), which get no spoken line.
     */
    fun siteName(uri: String): String? {
        val host = Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE).find(uri)?.groupValues?.get(1) ?: return null
        val labels = host.lowercase().split('.').filter { it.isNotEmpty() }
        val name = labels.getOrNull(labels.size - 2) ?: labels.firstOrNull() ?: return null
        return name.replaceFirstChar { it.uppercase() }
    }

    /** Added to a voice-mode run's prompt: the reply is spoken by an English-only voice. */
    const val NOTE = "Voice mode: the user spoke this command and your final reply will be read aloud by an English-only voice. " +
        "Do the task as usual, then reply in English whatever language they used: one to three short spoken sentences, " +
        "no Markdown, lists, links, emoji or tables."
}
