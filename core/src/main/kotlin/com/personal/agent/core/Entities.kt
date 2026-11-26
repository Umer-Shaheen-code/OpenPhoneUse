package com.personal.agent.core

/**
 * Entity parsers. Each one takes a span of normalized tokens and either consumes ALL of it
 * (returning a value) or returns null. The grammar matcher tries spans of different lengths.
 */
object Entities {

    // ---------- numbers ----------
    fun number(t: List<String>): Double? {
        if (t.size == 1) return t[0].toDoubleOrNull()
        if (t.size == 3 && t[1] == "point") {
            val a = t[0].toIntOrNull() ?: return null
            val b = t[2].toIntOrNull() ?: return null
            return "$a.$b".toDouble()
        }
        return null
    }

    // ---------- durations ----------
    private enum class Unit(val ms: Long) { SEC(1000), MIN(60_000), HOUR(3_600_000) }

    private fun unitOf(w: String): Unit? = when (w) {
        "second", "seconds", "sec", "secs", "s" -> Unit.SEC
        "minute", "minutes", "min", "mins", "m" -> Unit.MIN
        "hour", "hours", "hr", "hrs", "h" -> Unit.HOUR
        else -> null
    }

    /** "3 minutes", "1 minute 30", "90 seconds", "a minute and a half", "1:30", "half an hour", "2 and a half minutes". */
    fun duration(t: List<String>): Long? {
        if (t.isEmpty()) return null
        // mm:ss or h:mm:ss
        if (t.size == 1 && t[0].contains(':')) return clock(t[0])
        var i = 0
        var total = 0L
        var lastUnit: Unit? = null
        var matchedAny = false
        while (i < t.size) {
            val w = t[i]
            when {
                w == "and" && matchedAny -> { i++; continue }
                // "and a half" / "a half" after a unit
                w == "a" && i + 1 < t.size && t[i + 1] == "half" && lastUnit != null -> {
                    total += lastUnit.ms / 2; i += 2; continue
                }
                // "half a minute" / "half an hour"
                w == "half" && i + 2 < t.size && (t[i + 1] == "a" || t[i + 1] == "an") && unitOf(t[i + 2]) != null -> {
                    val u = unitOf(t[i + 2])!!; total += u.ms / 2; lastUnit = u; matchedAny = true; i += 3; continue
                }
                // "a couple of minutes" / "couple minutes"
                (w == "couple" || (w == "a" && i + 1 < t.size && t[i + 1] == "couple")) -> {
                    var j = if (w == "a") i + 2 else i + 1
                    if (j < t.size && t[j] == "of") j++
                    val u = t.getOrNull(j)?.let(::unitOf) ?: return null
                    total += 2 * u.ms; lastUnit = u; matchedAny = true; i = j + 1; continue
                }
                // "a minute", "an hour"
                (w == "a" || w == "an") && i + 1 < t.size && unitOf(t[i + 1]) != null -> {
                    val u = unitOf(t[i + 1])!!; total += u.ms; lastUnit = u; matchedAny = true; i += 2; continue
                }
                w.toDoubleOrNull() != null -> {
                    val n = w.toDouble()
                    var j = i + 1
                    // "2 and a half minutes"
                    var extraHalf = false
                    if (j + 2 < t.size && t[j] == "and" && t[j + 1] == "a" && t[j + 2] == "half") { extraHalf = true; j += 3 }
                    val u = t.getOrNull(j)?.let(::unitOf)
                    if (u != null) {
                        total += ((n + if (extraHalf) 0.5 else 0.0) * u.ms).toLong(); lastUnit = u; matchedAny = true; i = j + 1; continue
                    }
                    // trailing bare number after a unit: "1 minute 30" → 30 seconds, "1 hour 20" → 20 minutes
                    if (lastUnit != null && j == t.size && !extraHalf) {
                        val sub = if (lastUnit == Unit.HOUR) Unit.MIN else Unit.SEC
                        total += (n * sub.ms).toLong(); i = j; continue
                    }
                    return null
                }
                else -> return null
            }
        }
        return if (matchedAny && total > 0) total else null
    }

    /** "1:30" → 90 s, "1:02:03" → 1h 2m 3s. */
    fun clock(s: String): Long? {
        val parts = s.split(':').map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000
            else -> null
        }
    }

    /** Absolute media position: "1:20", "minute 5", "5 minutes", "2 minutes 10 seconds", "the beginning". */
    fun mediaPosition(t: List<String>): Long? {
        if (t == listOf("the", "beginning") || t == listOf("the", "start") || t == listOf("beginning") || t == listOf("start")) return 0
        if (t.size == 2 && t[0] in setOf("minute", "min")) return t[1].toDoubleOrNull()?.let { (it * 60_000).toLong() }
        if (t.size == 4 && t[0] == "minute" && t[2] in setOf("second", "seconds")) {
            val m = t[1].toLongOrNull() ?: return null; val s = t[3].toLongOrNull() ?: return null
            return m * 60_000 + s * 1000
        }
        return duration(t)
    }

    // ---------- time of day ----------
    data class TimeOfDay(val hour: Int, val minute: Int, val meridiemKnown: Boolean)

    fun timeOfDay(t0: List<String>): TimeOfDay? {
        if (t0.isEmpty()) return null
        var t = t0
        var pm: Boolean? = null
        // trailing modifiers
        fun dropSuffix(vararg seq: String): Boolean {
            if (t.size > seq.size && t.takeLast(seq.size) == seq.toList()) { t = t.dropLast(seq.size); return true }
            return false
        }
        when {
            dropSuffix("in", "the", "morning") -> pm = false
            dropSuffix("in", "the", "afternoon") || dropSuffix("in", "the", "evening") || dropSuffix("at", "night") || dropSuffix("tonight") -> pm = true
            dropSuffix("am") || dropSuffix("a", "m") -> pm = false
            dropSuffix("pm") || dropSuffix("p", "m") -> pm = true
        }
        dropSuffix("oclock")
        if (t.size == 1) when (t[0]) {
            "noon", "midday" -> return TimeOfDay(12, 0, true)
            "midnight" -> return TimeOfDay(0, 0, true)
        }
        var h: Int
        var m = 0
        when {
            t.size == 1 && t[0].contains(':') -> {
                val p = t[0].split(':'); if (p.size != 2) return null
                h = p[0].toIntOrNull() ?: return null; m = p[1].toIntOrNull() ?: return null
            }
            t.size == 1 -> {
                val n = t[0].toIntOrNull() ?: return null
                if (n in 100..2359 && t[0].length >= 3) { h = n / 100; m = n % 100 } else h = n
            }
            t.size == 2 && t[0].toIntOrNull() != null && t[1].toIntOrNull() != null -> {
                h = t[0].toInt(); m = t[1].toInt()
            }
            t.size == 3 && t[1] in setOf("past", "after") -> {
                val base = t[2].toIntOrNull() ?: return null
                m = when (t[0]) { "half" -> 30; "quarter" -> 15; else -> t[0].toIntOrNull() ?: return null }
                h = base
            }
            t.size == 4 && t[0] == "a" && t[1] == "quarter" && t[2] in setOf("past", "after") -> { h = t[3].toIntOrNull() ?: return null; m = 15 }
            t.size == 3 && t[1] in setOf("to", "till") -> {
                val base = t[2].toIntOrNull() ?: return null
                val before = when (t[0]) { "quarter" -> 15; else -> t[0].toIntOrNull() ?: return null }
                h = (base + 23) % 24; m = 60 - before
            }
            else -> return null
        }
        if (m !in 0..59 || h !in 0..23) return null
        if (pm != null) {
            if (h > 12) return null
            if (pm && h < 12) h += 12
            if (!pm && h == 12) h = 0
            return TimeOfDay(h, m, true)
        }
        return TimeOfDay(h, m, h > 12 || h == 0)
    }

    // ---------- percent / level ----------
    fun percent(t: List<String>): Int? {
        val s = t.joinToString(" ")
        when (s) {
            "max", "maximum", "full", "100 percent", "all the way up" -> return 100
            "half", "50 percent", "halfway" -> return 50
            "min", "minimum", "zero", "0" -> return 0
        }
        val stripped = when {
            t.size == 2 && (t[1] == "percent" || t[1] == "%") -> t[0]
            t.size == 1 -> t[0].removeSuffix("%")
            else -> return null
        }
        val n = stripped.toDoubleOrNull()?.toInt() ?: return null
        return if (n in 0..100) n else null
    }

    // ---------- playback speed ----------
    fun speed(t: List<String>): Float? {
        val s = t.joinToString(" ")
        when (s) {
            "normal", "normal speed", "1x", "regular speed" -> return 1f
            "double", "double speed", "2x" -> return 2f
            "half speed" -> return 0.5f
        }
        val core = when {
            t.size == 2 && t[1] in setOf("x", "times", "speed") -> listOf(t[0])
            t.size == 4 && t[1] == "point" && t[3] in setOf("x", "times", "speed") -> t.take(3)
            else -> t
        }
        val n = number(core)?.toFloat() ?: return null
        return if (n in 0.25f..3f) n else null
    }
}
