package com.personal.agent.core

/**
 * A normalized token that remembers which span of the ORIGINAL transcript it came from,
 * so free-text slots (message bodies, notes) can be copied verbatim with case and punctuation.
 */
data class Tok(val norm: String, val start: Int, val end: Int)

class Utterance(val original: String, val tokens: List<Tok>) {
    val size get() = tokens.size
    fun norm(i: Int) = tokens[i].norm
    fun normText(from: Int = 0, to: Int = tokens.size) = tokens.subList(from, to).joinToString(" ") { it.norm }

    /** Original transcript text covering tokens [from, to). */
    fun originalText(from: Int, to: Int): String {
        if (from >= to) return ""
        val s = tokens[from].start
        val e = tokens[to - 1].end
        return original.substring(s, e).trim().trimEnd('.', ',', '!', '?', ';').trim()
    }

    override fun toString() = normText()
}

object Normalizer {
    private val TOKEN = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d+)?%?|\d+(?::\d+)+|\d+(?:\.\d+)?%?|[\p{L}\p{M}\p{N}]+(?:['’][\p{L}]+)*|%""")

    private val CONTRACTIONS = mapOf(
        "what's" to listOf("what", "is"), "whats" to listOf("what", "is"),
        "i'm" to listOf("i", "am"), "it's" to listOf("it", "is"), "that's" to listOf("that", "is"),
        "where's" to listOf("where", "is"), "who's" to listOf("who", "is"), "how's" to listOf("how", "is"),
        "there's" to listOf("there", "is"), "he's" to listOf("he", "is"), "she's" to listOf("she", "is"),
        "don't" to listOf("do", "not"), "doesn't" to listOf("does", "not"), "can't" to listOf("can", "not"),
        "won't" to listOf("will", "not"), "i'll" to listOf("i", "will"), "you'll" to listOf("you", "will"),
        "let's" to listOf("let", "us"), "you're" to listOf("you", "are"), "i've" to listOf("i", "have"),
        "i'd" to listOf("i", "would"), "we're" to listOf("we", "are"), "they're" to listOf("they", "are"),
        "isn't" to listOf("is", "not"), "aren't" to listOf("are", "not"),
    )

    /** Multi-token ASR mis-hearings → canonical token. Longest first. */
    private val ALIASES: List<Pair<List<String>, String>> = listOf(
        listOf("what", "is", "app") to "whatsapp",
        listOf("whats", "app") to "whatsapp",
        listOf("watts", "app") to "whatsapp",
        listOf("whatsapp", "business") to "whatsappbusiness",
        listOf("g", "mail") to "gmail",
        listOf("gee", "mail") to "gmail",
        listOf("e", "mail") to "email",
        listOf("e", "mails") to "emails",
        listOf("you", "tube") to "youtube",
        listOf("youtube", "music") to "ytmusic",
        listOf("yt", "music") to "ytmusic",
        listOf("for", "word") to "forward",
        listOf("fast", "forward") to "forward",
        listOf("flash", "light") to "flashlight",
        listOf("do", "not", "disturb") to "dnd",
        listOf("text", "message") to "sms",
        listOf("minat") to "minute",
        listOf("mint") to "minute",
        listOf("mints") to "minutes",
        listOf("ghanta") to "hour",
        listOf("ghante") to "hours",
        listOf("ghantay") to "hours",
        listOf("sekand") to "second",
    ).sortedByDescending { it.first.size }

    private val LEADING_FILLERS = listOf(
        listOf("hey", "agent"), listOf("ok", "agent"), listOf("okay", "agent"), listOf("sunno", "agent"), listOf("suno", "agent"),
        listOf("i", "want", "you", "to"), listOf("i", "need", "you", "to"),
        listOf("can", "you", "please"), listOf("could", "you", "please"), listOf("would", "you", "please"),
        listOf("can", "you"), listOf("could", "you"), listOf("would", "you"), listOf("will", "you"),
        listOf("please"), listOf("hey"), listOf("hi"), listOf("ok"), listOf("okay"), listOf("so"),
        listOf("um"), listOf("uh"), listOf("agent"), listOf("just"), listOf("kindly"),
        listOf("zara"), listOf("yaar"), listOf("bhai"), listOf("acha"),
    )
    private val TRAILING_FILLERS = listOf(listOf("for", "me", "please"), listOf("for", "me"), listOf("please"), listOf("thanks"), listOf("thank", "you"), listOf("yaar"), listOf("bhai"))

    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(when (ch) {
                in '\u06F0'..'\u06F9' -> ('0'.code + (ch.code - 0x06F0)).toChar()
                in '\u0660'..'\u0669' -> ('0'.code + (ch.code - 0x0660)).toChar()
                else -> ch
            })
        }
        return sb.toString()
    }

    fun normalize(text: String): Utterance {
        val cleanText = normalizeDigits(text)
        val raw = TOKEN.findAll(cleanText).map { m ->
            val v = m.value.lowercase().replace('’', '\'')
            Tok(if (v.first().isDigit()) v.replace(",", "") else v, m.range.first, m.range.last + 1)
        }.toList()

        // 1. contractions
        val expanded = ArrayList<Tok>(raw.size + 4)
        for (t in raw) {
            val c = CONTRACTIONS[t.norm]
            if (c != null) c.forEach { expanded += Tok(it, t.start, t.end) }
            else expanded += t.copy(norm = t.norm.removeSuffix("'s").replace("'", ""))
        }

        // 2. aliases (multi-token)
        val aliased = mergeSequences(expanded) { toks, i ->
            for ((seq, canon) in ALIASES) {
                if (i + seq.size <= toks.size && (0 until seq.size).all { toks[i + it].norm == seq[it] }) {
                    return@mergeSequences seq.size to canon
                }
            }
            null
        }

        // 3. number words → digits
        val numbered = mergeSequences(aliased) { toks, i -> NumberWords.parseAt(toks.map { it.norm }, i)?.let { (len, v) -> len to v.toString() } }

        // 4. fillers
        var from = 0
        var to = numbered.size
        loop@ while (from < to) {
            for (f in LEADING_FILLERS) if (matchesAt(numbered, from, f) && from + f.size < to) { from += f.size; continue@loop }
            break
        }
        loop@ while (to > from) {
            for (f in TRAILING_FILLERS) if (to - f.size > from && matchesAt(numbered, to - f.size, f)) { to -= f.size; continue@loop }
            break
        }
        return Utterance(text, numbered.subList(from, to))
    }

    private fun matchesAt(toks: List<Tok>, i: Int, seq: List<String>) =
        i + seq.size <= toks.size && seq.indices.all { toks[i + it].norm == seq[it] }

    private inline fun mergeSequences(toks: List<Tok>, f: (List<Tok>, Int) -> Pair<Int, String>?): List<Tok> {
        val out = ArrayList<Tok>(toks.size)
        var i = 0
        while (i < toks.size) {
            val r = f(toks, i)
            if (r != null && r.first > 0) {
                out += Tok(r.second, toks[i].start, toks[i + r.first - 1].end)
                i += r.first
            } else {
                out += toks[i]; i++
            }
        }
        return out
    }
}

/** English number words → integers ("twenty five" → 25, "a hundred" → 100). */
object NumberWords {
    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
        // Roman-Urdu number words (H12)
        "ek" to 1, "aik" to 1, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "chhay" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "gyarah" to 11, "barah" to 12, "pandrah" to 15, "bees" to 20, "pachees" to 25,
        "tees" to 30, "chalis" to 40, "pachas" to 50, "saath" to 60, "sau" to 100,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /** Returns (tokensConsumed, value) for a number phrase starting at i, or null. "oh" alone is not a number. */
    fun parseAt(t: List<String>, i: Int): Pair<Int, Int>? {
        var j = i
        var total = 0
        var current = 0
        var any = false
        while (j < t.size) {
            val w = t[j]
            when {
                w == "oh" && !any -> return null
                UNITS.containsKey(w) -> {
                    if (any && current % 10 != 0 && current < 100) break // "five six" is not 56
                    current += UNITS.getValue(w); any = true
                }
                TENS.containsKey(w) -> {
                    if (any && current % 100 != 0) break
                    current += TENS.getValue(w); any = true
                }
                w == "hundred" && any -> current *= 100
                w == "thousand" && any -> { total += current * 1000; current = 0 }
                w == "and" && any && j + 1 < t.size && (UNITS.containsKey(t[j + 1]) || TENS.containsKey(t[j + 1])) && current >= 100 -> {}
                else -> break
            }
            j++
        }
        if (!any) return null
        return (j - i) to (total + current)
    }
}
