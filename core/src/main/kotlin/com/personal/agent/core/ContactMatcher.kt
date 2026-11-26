package com.personal.agent.core

import java.text.Normalizer as JNormalizer

enum class ContactSource { LIVE_CONVERSATION, RECENT_CONVERSATION, GROUP_SENDER, STARRED, CONTACTS, ALIAS }

/**
 * Anything the user might name: a contact, a live WhatsApp conversation, a sender inside a group.
 * [id] is opaque to the core (conversation key or contact id).
 */
data class ContactCandidate(
    val id: String,
    val displayName: String,
    val source: ContactSource,
    val aliases: List<String> = emptyList(),
    val app: String? = null,          // "whatsapp", "sms", "gmail"...
    val isGroup: Boolean = false,
    val groupName: String? = null,    // for GROUP_SENDER
    val lastMessage: String? = null,  // used to disambiguate aloud
    val recencyBoost: Double = 0.0,   // 0..1, newer = higher
)

data class ContactMatch(val candidate: ContactCandidate, val score: Double, val nameSim: Double)

data class Resolution(val best: ContactMatch?, val alternatives: List<ContactMatch>, val decided: Boolean) {
    val ambiguous get() = best != null && !decided
}

class ContactMatcher(candidates: List<ContactCandidate>) {
    private val entries = candidates.map { c -> c to (listOf(c.displayName) + c.aliases).map(::keyForms) }

    /** Score every candidate against the spoken span. */
    fun rank(spoken: String, preferApp: String? = null, contextIds: Set<String> = emptySet()): List<ContactMatch> {
        val q = keyForms(spoken)
        if (q.plain.isBlank()) return emptyList()
        return entries.mapNotNull { (c, forms) ->
            val sim = forms.maxOf { similarity(q, it) }
            if (sim < 0.6) return@mapNotNull null
            val prior = when (c.source) {
                ContactSource.ALIAS -> 1.0
                ContactSource.LIVE_CONVERSATION -> 1.0
                ContactSource.RECENT_CONVERSATION -> 0.7
                ContactSource.GROUP_SENDER -> 0.6
                ContactSource.STARRED -> 0.5
                ContactSource.CONTACTS -> 0.2
            }
            var ctx = 0.0
            if (preferApp != null && c.app == preferApp) ctx += 0.6
            if (c.id in contextIds) ctx += 0.4
            ctx += 0.3 * c.recencyBoost
            val score = 0.55 * sim + 0.25 * prior + 0.20 * ctx.coerceAtMost(1.0)
            ContactMatch(c, score, sim)
        }
            .sortedByDescending { it.score }
            .distinctBy { it.candidate.id }
    }

    /**
     * Accept when the top match is strong and clearly ahead. Same person seen through several sources
     * (live conversation + contact card) is collapsed by display name before checking the margin.
     */
    fun resolve(spoken: String, preferApp: String? = null, contextIds: Set<String> = emptySet()): Resolution {
        val ranked = rank(spoken, preferApp, contextIds)
        if (ranked.isEmpty()) return Resolution(null, emptyList(), false)
        val byPerson = ranked.distinctBy { normalizeName(it.candidate.displayName) + "|" + (it.candidate.groupName ?: "") }
        val best = byPerson.first()
        val second = byPerson.getOrNull(1)
        val decided = best.nameSim >= 0.84 && (second == null || best.score - second.score >= 0.08 || second.nameSim < best.nameSim - 0.12)
        return Resolution(best, byPerson.take(4), decided)
    }

    companion object {
        private val HONORIFICS = setOf("bhai", "bhaijan", "sahab", "sahib", "ji", "mr", "mrs", "ms", "dr", "sir", "madam", "uncle", "aunty", "auntie", "baji")

        fun normalizeName(s: String): String {
            val latin = if (s.any { it in '\u0600'..'\u06FF' }) transliterateUrdu(s) else s
            val n = JNormalizer.normalize(latin, JNormalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return n.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() && it !in HONORIFICS }.joinToString(" ")
        }

        /** H12: basic Urdu/Arabic-script → Latin transliteration for contact matching. */
        fun transliterateUrdu(s: String): String {
            val map = mapOf(
                'ا' to "a", 'آ' to "aa", 'أ' to "a", 'إ' to "i", 'ب' to "b", 'پ' to "p",
                'ت' to "t", 'ٹ' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h",
                'خ' to "kh", 'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r",
                'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z",
                'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
                'ک' to "k", 'ك' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
                'ں' to "n", 'و' to "o", 'ہ' to "h", 'ھ' to "h", 'ة' to "t", 'ء' to "",
                'ی' to "i", 'ي' to "i", 'ے' to "e",
            )
            val sb = StringBuilder(s.length * 2)
            for (ch in s) {
                sb.append(map[ch] ?: ch)
            }
            return sb.toString()
        }

        data class Forms(val plain: String, val tokens: List<String>, val phonetic: List<String>)

        fun keyForms(s: String): Forms {
            val plain = normalizeName(s)
            val toks = plain.split(' ').filter { it.isNotBlank() }
            return Forms(plain, toks, toks.map(::phonetic))
        }

        /**
         * Similarity between what was heard and a name. Handles: full name, first-name-only,
         * ASR spelling variants (Jaro-Winkler) and sound-alikes (phonetic key).
         */
        fun similarity(q: Forms, n: Forms): Double {
            if (q.plain == n.plain) return 1.0
            val full = jaroWinkler(q.plain.replace(" ", ""), n.plain.replace(" ", ""))
            // each heard token aligned to its best name token
            val tokenAligned = if (q.tokens.isEmpty() || n.tokens.isEmpty()) 0.0 else {
                val per = q.tokens.mapIndexed { i, qt ->
                    n.tokens.indices.maxOf { j ->
                        val jw = jaroWinkler(qt, n.tokens[j])
                        val ph = if (q.phonetic[i].isNotEmpty() && q.phonetic[i] == n.phonetic[j]) 0.92 else 0.0
                        maxOf(jw, ph)
                    }
                }
                val avg = per.average()
                // first-name-only mention of a multi-word name is fine, but a slightly lower ceiling
                val coverage = q.tokens.size.toDouble() / n.tokens.size
                if (coverage < 1.0) avg * (0.94 + 0.06 * coverage) else avg
            }
            return maxOf(full * (if (q.tokens.size == n.tokens.size) 1.0 else 0.9), tokenAligned)
        }

        fun jaroWinkler(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
            val am = BooleanArray(a.length); val bm = BooleanArray(b.length)
            var matches = 0
            for (i in a.indices) {
                val lo = maxOf(0, i - window); val hi = minOf(b.length - 1, i + window)
                for (j in lo..hi) if (!bm[j] && a[i] == b[j]) { am[i] = true; bm[j] = true; matches++; break }
            }
            if (matches == 0) return 0.0
            var k = 0; var trans = 0
            for (i in a.indices) if (am[i]) { while (!bm[k]) k++; if (a[i] != b[k]) trans++; k++ }
            val m = matches.toDouble()
            val jaro = (m / a.length + m / b.length + (m - trans / 2.0) / m) / 3.0
            var prefix = 0
            while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
            return jaro + prefix * 0.1 * (1 - jaro)
        }

        /** Compact sound-alike key tuned for names transcribed by ASR (abdulla ≈ abdullah, mohammed ≈ muhammad). */
        fun phonetic(w: String): String {
            if (w.isEmpty()) return ""
            var s = w.lowercase()
                .replace("ph", "f").replace("ck", "k").replace("q", "k").replace("x", "ks")
                .replace("sh", "S").replace("ch", "C").replace("th", "t").replace("kh", "k").replace("gh", "g")
                .replace("z", "s").replace("c", "k").replace("w", "v").replace("j", "J")
            if (s.endsWith("h") && s.length > 2) s = s.dropLast(1)
            val first = s[0]
            val firstClass = if (first in "aeiouy") 'A' else first
            val rest = s.drop(1).filter { it !in "aeiouyh" }
            val sb = StringBuilder().append(firstClass)
            for (ch in rest) if (sb.last() != ch) sb.append(ch)
            return sb.toString()
        }
    }
}
