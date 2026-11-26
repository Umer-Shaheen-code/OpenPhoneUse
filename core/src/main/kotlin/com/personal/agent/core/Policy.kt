package com.personal.agent.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

enum class Risk { R0_READ, R1_REVERSIBLE, R2_OUTBOUND, R3_DESTRUCTIVE }

/** §5.7 risk & confirmation policy. */
object RiskPolicy {
    sealed class Decision {
        object Execute : Decision()
        data class UndoWindow(val ms: Long) : Decision()
        data class Confirm(val reason: String) : Decision()
    }

    fun decide(
        risk: Risk,
        parseScore: Double,
        contactSimilarity: Double? = null,
        toGroup: Boolean = false,
        fromLlm: Boolean = false,
        undoWindowMs: Long = 2_500,
        alwaysConfirm: Boolean = false,
    ): Decision = when (risk) {
        Risk.R0_READ, Risk.R1_REVERSIBLE -> Decision.Execute
        Risk.R3_DESTRUCTIVE -> Decision.Confirm("destructive")
        Risk.R2_OUTBOUND -> when {
            fromLlm -> Decision.Confirm("llm")
            alwaysConfirm -> Decision.Confirm("setting")
            toGroup -> Decision.Confirm("group")
            contactSimilarity != null && contactSimilarity < 0.9 -> Decision.Confirm("fuzzy-contact")
            parseScore < CommandParser.ACCEPT -> Decision.Confirm("low-confidence")
            undoWindowMs <= 0 -> Decision.Execute
            else -> Decision.UndoWindow(undoWindowMs)
        }
    }
}

/** Spoken formatting for time & date (no LLM). */
object TimeSpeech {
    fun time(t: LocalTime, use24h: Boolean): String {
        if (use24h) return "%02d:%02d".format(t.hour, t.minute)
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        val ampm = if (t.hour < 12) "AM" else "PM"
        return if (t.minute == 0) "$h $ampm" else "$h:%02d $ampm".format(t.minute)
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    fun date(d: LocalDate, locale: Locale = Locale.ENGLISH, withYear: Boolean = false): String {
        val dow = d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val month = d.month.getDisplayName(TextStyle.FULL, locale)
        return "$dow, $month ${ordinal(d.dayOfMonth)}" + if (withYear) ", ${d.year}" else ""
    }

    /** Next occurrence of a spoken time: "at 7" said at 8 PM means 7 AM tomorrow unless AM/PM was given. */
    fun nextOccurrence(now: LocalDateTime, tod: Entities.TimeOfDay): LocalDateTime {
        val candidates = if (tod.meridiemKnown) listOf(tod.hour) else listOf(tod.hour % 12, tod.hour % 12 + 12)
        return candidates.flatMap { h ->
            val today = now.toLocalDate().atTime(h, tod.minute)
            listOf(today, today.plusDays(1))
        }.filter { it.isAfter(now) }.minOrNull()!!
    }
}
