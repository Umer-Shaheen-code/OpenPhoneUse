package com.personal.agent.core

/**
 * Tiny pattern language compiled to token matchers (not regex), so slot boundaries can be chosen
 * by scoring (e.g. which span is a real contact name) instead of regex greediness.
 *
 *   word            literal token
 *   (a|b c|d)       required choice; alternatives may be multi-word and contain slots
 *   [a|b c]         optional choice
 *   {name}          slot; type inferred from name, or {name:type}
 *   {name=a|b|c}    enum slot: one token from the list; value is the token
 *   <noun>          macro expansion (see MACROS)
 */
enum class SlotType(val minLen: Int, val maxLen: Int) {
    TEXT(1, 60), CONTACT(1, 4), DURATION(1, 8), POSITION(1, 6), TIME(1, 5), PERCENT(1, 4),
    APP(1, 1), NUMBER(1, 3), SPEED(1, 4), WORDS(1, 4), DATE(1, 5), EXPR(1, 20), ENUM(1, 1), ORDINAL(1, 3)
}

sealed class SlotValue {
    data class Text(val text: String) : SlotValue()
    data class Contact(val spoken: String, val pronoun: Boolean, val resolution: Resolution?) : SlotValue()
    data class Duration(val ms: Long) : SlotValue()
    data class Position(val ms: Long) : SlotValue()
    data class Time(val tod: Entities.TimeOfDay) : SlotValue()
    data class Percent(val value: Int) : SlotValue()
    data class App(val id: String) : SlotValue()
    data class Number(val value: Double) : SlotValue()
    data class Speed(val value: Float) : SlotValue()
    data class Words(val text: String) : SlotValue()
    data class Date(val date: Dates.DateSpec) : SlotValue()
    data class Expr(val result: Calc.Result) : SlotValue()
    data class Enum(val value: String) : SlotValue()
    data class Ordinal(val index: Int) : SlotValue()
}

/** Everything the parser needs from the outside world. Pure data so it can be built in tests. */
data class ParseContext(
    val contacts: ContactMatcher = ContactMatcher(emptyList()),
    val mediaActive: Boolean = false,
    val readingList: Boolean = false,
    val preferApp: String? = null,
    val contextContactIds: Set<String> = emptySet(),
)

internal sealed class El {
    data class Word(val w: String) : El()
    data class Choice(val options: List<List<El>>, val optional: Boolean) : El()
    data class Slot(val name: String, val type: SlotType, val enumValues: Set<String> = emptySet()) : El()
}

class Pattern(val intent: String, source: String, val weight: Double, val fixed: Map<String, SlotValue>) {
    internal val elements: List<El> = PatternCompiler.compile(source)
    val text = source
}

internal object PatternCompiler {
    val MACROS = mapOf(
        "<noun>" to "(video|song|music|track|movie|podcast|episode|audio|it|this|playback|clip)",
        "<msgs>" to "(notifications|notification|messages|message|emails|email|mails|mail|texts|text|alerts|chats|msgs)",
    )

    private fun typeFor(name: String): SlotType = when (name) {
        "contact" -> SlotType.CONTACT
        "message", "text", "note", "query", "item", "appname", "place", "city" -> SlotType.TEXT
        "duration" -> SlotType.DURATION
        "position" -> SlotType.POSITION
        "time" -> SlotType.TIME
        "level" -> SlotType.PERCENT
        "app" -> SlotType.APP
        "number" -> SlotType.NUMBER
        "speed" -> SlotType.SPEED
        "list" -> SlotType.WORDS
        "date" -> SlotType.DATE
        "expr" -> SlotType.EXPR
        "index" -> SlotType.ORDINAL
        else -> error("unknown slot $name")
    }

    fun compile(src0: String): List<El> {
        var src = src0
        MACROS.forEach { (k, v) -> src = src.replace(k, v) }
        val p = Parser(src)
        val seq = p.sequence(stopAt = setOf())
        require(p.pos == src.length) { "trailing input in pattern '$src0' at ${p.pos}" }
        return seq
    }

    private class Parser(val s: String) {
        var pos = 0
        fun ws() { while (pos < s.length && s[pos] == ' ') pos++ }

        fun sequence(stopAt: Set<Char>): List<El> {
            val out = ArrayList<El>()
            while (true) {
                ws()
                if (pos >= s.length || s[pos] in stopAt) return out
                out += element()
            }
        }

        fun element(): El {
            val c = s[pos]
            return when (c) {
                '(', '[' -> {
                    val close = if (c == '(') ')' else ']'
                    pos++
                    val options = ArrayList<List<El>>()
                    while (true) {
                        options += sequence(stopAt = setOf('|', close))
                        require(pos < s.length) { "unclosed $c in '$s'" }
                        if (s[pos] == '|') { pos++; continue }
                        pos++; break
                    }
                    El.Choice(options, optional = c == '[')
                }
                '{' -> {
                    val end = s.indexOf('}', pos)
                    val body = s.substring(pos + 1, end)
                    pos = end + 1
                    when {
                        body.contains('=') -> {
                            val (n, vals) = body.split('=', limit = 2)
                            El.Slot(n, SlotType.ENUM, vals.split('|').toSet())
                        }
                        body.contains(':') -> {
                            val (n, t) = body.split(':', limit = 2)
                            El.Slot(n, SlotType.valueOf(t.uppercase()))
                        }
                        else -> El.Slot(body, typeFor(body))
                    }
                }
                else -> {
                    val start = pos
                    while (pos < s.length && s[pos] !in " ()[]{}|") pos++
                    El.Word(s.substring(start, pos))
                }
            }
        }
    }
}

data class Match(val pattern: Pattern, val slots: Map<String, SlotValue>, val score: Double)

internal class Matcher(private val u: Utterance, private val ctx: ParseContext) {
    private val norms = u.tokens.map { it.norm }
    private val slotCache = HashMap<Triple<El.Slot, Int, Int>, Pair<SlotValue, Double>?>()

    fun matchAll(p: Pattern): Match? {
        var best: Match? = null
        walk(p.elements, 0, 0, emptyMap(), 1.0) { slots, score ->
            val total = score * p.weight
            if (best == null || total > best!!.score) best = Match(p, p.fixed + slots, total)
        }
        return best
    }

    /** Depth-first over elements; [k] continues with the remaining element list after a choice. */
    private fun walk(els: List<El>, ei: Int, ti: Int, slots: Map<String, SlotValue>, score: Double, done: (Map<String, SlotValue>, Double) -> Unit) {
        if (ei == els.size) { if (ti == norms.size) done(slots, score); return }
        when (val e = els[ei]) {
            is El.Word -> if (ti < norms.size && (norms[ti] == e.w || (e.w == "one" && norms[ti] == "1"))) walk(els, ei + 1, ti + 1, slots, score, done)
            is El.Choice -> {
                val rest = els.subList(ei + 1, els.size)
                for (opt in e.options) walk(opt + rest, 0, ti, slots, score, done)
                if (e.optional) walk(els, ei + 1, ti, slots, score, done)
            }
            is El.Slot -> {
                val maxEnd = minOf(norms.size, ti + e.type.maxLen)
                for (end in (ti + e.type.minLen)..maxEnd) {
                    val r = slotCache.getOrPut(Triple(e, ti, end)) { parseSlot(e, ti, end) } ?: continue
                    walk(els, ei + 1, end, slots + (e.name to r.first), score * r.second, done)
                }
            }
        }
    }

    private fun parseSlot(e: El.Slot, from: Int, to: Int): Pair<SlotValue, Double>? {
        val t = norms.subList(from, to)
        return when (e.type) {
            SlotType.TEXT -> SlotValue.Text(u.originalText(from, to)).takeIf { it.text.isNotBlank() }?.let { it to (if (t[0] in TEXT_BAD_START) 0.88 else 1.0) }
            SlotType.WORDS -> SlotValue.Words(t.joinToString(" ")) to 1.0
            SlotType.DURATION -> Entities.duration(t)?.let { SlotValue.Duration(it) to 1.0 }
            SlotType.POSITION -> Entities.mediaPosition(t)?.let { SlotValue.Position(it) to 1.0 }
            SlotType.TIME -> Entities.timeOfDay(t)?.let { SlotValue.Time(it) to 1.0 }
            SlotType.PERCENT -> Entities.percent(t)?.let { SlotValue.Percent(it) to 1.0 }
            SlotType.NUMBER -> Entities.number(t)?.let { SlotValue.Number(it) to 1.0 }
            SlotType.SPEED -> Entities.speed(t)?.let { SlotValue.Speed(it) to 1.0 }
            SlotType.APP -> Apps.byToken[t[0]]?.let { SlotValue.App(it) to 1.0 }
            SlotType.DATE -> Dates.parse(t)?.let { SlotValue.Date(it) to 1.0 }
            SlotType.EXPR -> Calc.evaluate(t)?.let { SlotValue.Expr(it) to 1.0 }
            SlotType.ENUM -> if (t[0] in e.enumValues) SlotValue.Enum(t[0]) to 1.0 else null
            SlotType.ORDINAL -> ordinal(t)?.let { SlotValue.Ordinal(it) to 1.0 }
            SlotType.CONTACT -> contact(from, to)
        }
    }

    private fun contact(from: Int, to: Int): Pair<SlotValue, Double>? {
        val t = norms.subList(from, to)
        if (t.size == 1 && t[0] in PRONOUNS) return SlotValue.Contact(t[0], true, null) to 0.95
        if (t.any { it in NOT_IN_NAMES }) return null
        if (t.any { it.toDoubleOrNull() != null }) return null
        val spoken = u.originalText(from, to).removeSuffix("'s").removeSuffix("’s")
        val res = ctx.contacts.resolve(spoken, ctx.preferApp, ctx.contextContactIds)
        val sim = res.best?.nameSim ?: 0.0
        val score = when {
            sim >= 0.9 -> 0.75 + 0.25 * sim + 0.02 * (t.size - 1)
            sim >= 0.84 -> 0.7 + 0.25 * sim
            sim >= 0.6 -> 0.6 * sim
            // unknown name: allowed (we can still ask / look up later) but weak, shorter is likelier
            else -> when (t.size) { 1 -> 0.55; 2 -> 0.45; else -> 0.3 }
        }
        return SlotValue.Contact(spoken, false, res.takeIf { it.best != null }) to score
    }

    private fun ordinal(t: List<String>): Int? {
        var w = t.filter { it != "the" && it != "number" && it != "option" }
        if (w.size == 2 && (w[1] == "one" || w[1] == "1")) w = w.take(1)
        if (w.size != 1) return null
        return when (w[0]) {
            "first", "1", "1st" -> 0; "second", "2", "2nd" -> 1; "third", "3", "3rd" -> 2; "fourth", "4", "4th" -> 3
            "last" -> -1
            else -> null
        }
    }

    companion object {
        val TEXT_BAD_START = setOf("pm", "am", "p", "to", "that", "about", "saying", "and", "with")
        val PRONOUNS = setOf("him", "her", "them", "he", "she", "they")
        val NOT_IN_NAMES = setOf(
            "that", "saying", "i", "am", "is", "are", "to", "the", "on", "at", "in", "with", "will", "be",
            "you", "we", "it", "this", "and", "please", "me", "my", "a", "an", "of", "for", "via", "over",
            "can", "not", "do", "what", "where", "when", "how", "why", "call", "back", "minutes", "seconds",
            "whatsapp", "gmail", "sms", "message", "messages", "text", "telegram", "signal", "email",
        )
    }
}

object Apps {
    /** Canonical app ids used across the agent. */
    val byToken: Map<String, String> = mapOf(
        "whatsapp" to "whatsapp", "whatsappbusiness" to "whatsapp_business",
        "gmail" to "gmail", "email" to "gmail", "emails" to "gmail", "mail" to "gmail", "mails" to "gmail", "inbox" to "gmail",
        "youtube" to "youtube", "ytmusic" to "ytmusic", "spotify" to "spotify",
        "sms" to "sms", "messages" to "sms", "texts" to "sms",
        "telegram" to "telegram", "signal" to "signal", "slack" to "slack", "instagram" to "instagram",
        "messenger" to "messenger", "facebook" to "facebook", "teams" to "teams", "outlook" to "outlook",
        "linkedin" to "linkedin", "discord" to "discord", "netflix" to "netflix",
    )

    val packages: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("com.whatsapp"), "whatsapp_business" to listOf("com.whatsapp.w4b"),
        "gmail" to listOf("com.google.android.gm"),
        "youtube" to listOf("com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube"),
        "ytmusic" to listOf("com.google.android.apps.youtube.music"),
        "spotify" to listOf("com.spotify.music"),
        "sms" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.plus"),
        "signal" to listOf("org.thoughtcrime.securesms"), "slack" to listOf("com.Slack"),
        "instagram" to listOf("com.instagram.android"), "messenger" to listOf("com.facebook.orca"),
        "facebook" to listOf("com.facebook.katana"), "teams" to listOf("com.microsoft.teams"),
        "outlook" to listOf("com.microsoft.office.outlook"), "linkedin" to listOf("com.linkedin.android"),
        "discord" to listOf("com.discord"), "netflix" to listOf("com.netflix.mediaclient"),
    )

    val names: Map<String, String> = mapOf(
        "whatsapp" to "WhatsApp", "whatsapp_business" to "WhatsApp Business", "gmail" to "Gmail", "youtube" to "YouTube",
        "ytmusic" to "YouTube Music", "spotify" to "Spotify", "sms" to "Messages", "telegram" to "Telegram",
        "signal" to "Signal", "slack" to "Slack", "instagram" to "Instagram", "messenger" to "Messenger",
        "facebook" to "Facebook", "teams" to "Teams", "outlook" to "Outlook", "linkedin" to "LinkedIn",
        "discord" to "Discord", "netflix" to "Netflix",
    )

    fun idForPackage(pkg: String): String? = packages.entries.firstOrNull { pkg in it.value }?.key
    fun displayName(id: String?): String = id?.let { names[it] } ?: "your apps"
}
