package com.personal.agent.core

data class ParsedCommand(
    val intent: String,
    val slots: Map<String, SlotValue>,
    val score: Double,
    val transcript: String,
    val source: String,          // "grammar" | "keyword"
    val pattern: String? = null,
) {
    inline fun <reified T : SlotValue> slot(name: String): T? = slots[name] as? T
    fun text(name: String): String? = when (val v = slots[name]) {
        is SlotValue.Text -> v.text; is SlotValue.Words -> v.text; is SlotValue.Enum -> v.value; else -> null
    }
}

object CommandParser {
    const val ACCEPT = 0.85
    const val ESCALATE_BELOW = 0.6

    private val intentOrder: Map<String, Int> = CommandGrammar.patterns.map { it.intent }.distinct().withIndex().associate { it.value to it.index }

    /** All interpretations of one transcript, best first. */
    fun parse(text: String, ctx: ParseContext = ParseContext()): List<ParsedCommand> {
        val u = Normalizer.normalize(text)
        if (u.size == 0) return emptyList()
        val m = Matcher(u, ctx)
        val results = CommandGrammar.patterns.mapNotNull { p ->
            m.matchAll(p)?.let { ParsedCommand(p.intent, it.slots, 0.97 * it.score, text, "grammar", p.text) }
        }
            .map { contextFit(it, ctx) }
            .groupBy { it.intent }.map { (_, v) -> v.maxBy { it.score } }
            .sortedWith(compareByDescending<ParsedCommand> { it.score }.thenBy { intentOrder[it.intent] ?: 999 })
        if (results.isNotEmpty() && results[0].score >= ESCALATE_BELOW) return results
        val kw = KeywordFallback.guess(u, ctx, text)
        return (listOfNotNull(kw) + results).sortedByDescending { it.score }
    }

    /**
     * N-best rescoring: ASR rank matters, but a hypothesis that parses cleanly (and whose
     * contact actually exists) beats a higher-ranked one that doesn't.
     */
    fun rank(hypotheses: List<String>, ctx: ParseContext = ParseContext()): ParsedCommand? {
        var best: ParsedCommand? = null
        var bestScore = -1.0
        hypotheses.take(5).forEachIndexed { i, h ->
            val p = parse(h, ctx).firstOrNull() ?: return@forEachIndexed
            val asr = 1.0 - 0.08 * i
            val combined = 0.3 * asr + 0.7 * p.score
            if (combined > bestScore) { bestScore = combined; best = p }
        }
        return best
    }

    private val EMAIL_WORDS = setOf("email", "emails", "mail", "mails", "inbox")

    private fun contextFit(c: ParsedCommand, ctx: ParseContext): ParsedCommand {
        var s = c.score
        if (c.intent.startsWith("media.") && c.intent != "media.play_search" && !ctx.mediaActive) s *= 0.93
        if (c.intent == "list.more" && ctx.readingList) s = minOf(1.0, s * 1.03)
        var slots = c.slots
        // "emails"/"mail" with no app named means Gmail
        if ((c.intent == "notif.read" || c.intent == "notif.archive") && "app" !in slots) {
            val words = Normalizer.normalize(c.transcript).tokens.map { it.norm }
            if (words.any { it in EMAIL_WORDS }) slots = slots + ("app" to SlotValue.App("gmail"))
        }
        return if (s == c.score && slots === c.slots) c else c.copy(score = s, slots = slots)
    }
}

/** Last-chance scorer for paraphrases the grammar doesn't cover. Never returns more than 0.78. */
internal object KeywordFallback {
    fun guess(u: Utterance, ctx: ParseContext, original: String): ParsedCommand? {
        val w = u.tokens.map { it.norm }
        val set = w.toSet()
        val duration = findSpan(w) { Entities.duration(it) }
        val app = w.firstNotNullOfOrNull { Apps.byToken[it] }
        fun cmd(intent: String, score: Double, slots: Map<String, SlotValue> = emptyMap()) = ParsedCommand(intent, slots, score, original, "keyword")

        if (duration != null && set.any { it in setOf("forward", "ahead", "forwards") })
            return cmd("media.seek_relative", 0.75, mapOf("duration" to SlotValue.Duration(duration), "direction" to SlotValue.Enum("forward")))
        if (duration != null && set.any { it in setOf("back", "rewind", "backward", "backwards", "behind") })
            return cmd("media.seek_relative", 0.75, mapOf("duration" to SlotValue.Duration(duration), "direction" to SlotValue.Enum("back")))
        if (duration != null && "timer" in set) return cmd("timer.set", 0.75, mapOf("duration" to SlotValue.Duration(duration)))
        val msgWords = setOf("messages", "message", "notifications", "notification", "emails", "email", "unread", "texts", "chats")
        if (app != null && set.any { it in msgWords } && set.none { it in setOf("send", "tell", "reply", "text") })
            return cmd("notif.read", 0.72, mapOf("app" to SlotValue.App(app)))
        if ("time" in set && set.any { it in setOf("what", "tell", "current", "now") } && "timer" !in set) return cmd("time.now", 0.72)
        if ("date" in set && set.any { it in setOf("what", "tell", "today") }) return cmd("date.today", 0.72)
        if ("battery" in set) return cmd("battery.status", 0.74)
        if (set.any { it in setOf("weather", "rain", "temperature", "forecast", "umbrella") }) return cmd("weather", 0.72)
        if ("volume" in set && set.any { it in setOf("up", "increase", "raise", "louder", "higher") }) return cmd("volume.up", 0.74)
        if ("volume" in set && set.any { it in setOf("down", "decrease", "lower", "reduce", "quieter") }) return cmd("volume.down", 0.74)
        if (set.any { it in setOf("flashlight", "torch") }) return cmd("torch.set", 0.7, mapOf("state" to SlotValue.Enum(if ("off" in set) "off" else if ("on" in set) "on" else "toggle")))
        return null
    }

    private fun <T> findSpan(w: List<String>, f: (List<String>) -> T?): T? {
        for (len in minOf(6, w.size) downTo 1) for (i in 0..w.size - len) f(w.subList(i, i + len))?.let { return it }
        return null
    }
}
