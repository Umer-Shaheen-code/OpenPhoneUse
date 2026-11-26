package com.personal.agent.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** Small date-expression parser: today/tomorrow, weekdays, "25 december", "december 25th", holidays. */
object Dates {
    sealed class DateSpec {
        data class Relative(val days: Int) : DateSpec()
        data class Weekday(val day: DayOfWeek, val next: Boolean) : DateSpec()
        data class DayMonth(val day: Int, val month: Month) : DateSpec()
    }

    private val MONTHS = Month.entries.associateBy { it.name.lowercase() } +
        Month.entries.associateBy { it.name.lowercase().take(3) } + mapOf("sept" to Month.SEPTEMBER)
    private val DAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private val ORDINAL_WORDS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13,
        "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18,
        "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30,
    )

    fun parse(t0: List<String>): DateSpec? {
        val t = t0.filter { it != "the" && it != "of" && it != "st" && it != "nd" && it != "rd" && it != "th" && it != "on" }
        if (t.isEmpty()) return null
        val s = t.joinToString(" ")
        when (s) {
            "today", "tonight" -> return DateSpec.Relative(0)
            "tomorrow" -> return DateSpec.Relative(1)
            "day after tomorrow" -> return DateSpec.Relative(2)
            "christmas", "christmas day" -> return DateSpec.DayMonth(25, Month.DECEMBER)
            "new year", "new years", "new years day", "new year day" -> return DateSpec.DayMonth(1, Month.JANUARY)
            "valentines day", "valentine day" -> return DateSpec.DayMonth(14, Month.FEBRUARY)
        }
        if (t.size == 1) DAYS[t[0]]?.let { return DateSpec.Weekday(it, next = false) }
        if (t.size == 2 && (t[0] == "next" || t[0] == "this")) DAYS[t[1]]?.let { return DateSpec.Weekday(it, next = t[0] == "next") }
        if (t.size == 2 && t[1] == "days" ) t[0].toIntOrNull()?.let { return DateSpec.Relative(it) }
        fun day(w: String) = w.toIntOrNull() ?: ORDINAL_WORDS[w]
        if (t.size == 2) {
            val d1 = day(t[0]); val m1 = MONTHS[t[1]]
            if (d1 != null && m1 != null && d1 in 1..31) return DateSpec.DayMonth(d1, m1)
            val m2 = MONTHS[t[0]]; val d2 = day(t[1])
            if (d2 != null && m2 != null && d2 in 1..31) return DateSpec.DayMonth(d2, m2)
        }
        if (t.size == 3 && t[0] == "twenty") { // "twenty fifth december" (number words already merged digits, ordinals aren't)
            val d = ORDINAL_WORDS[t[1]]?.plus(20); val m = MONTHS[t[2]]
            if (d != null && m != null) return DateSpec.DayMonth(d, m)
        }
        return null
    }

    fun resolve(spec: DateSpec, today: LocalDate): LocalDate = when (spec) {
        is DateSpec.Relative -> today.plusDays(spec.days.toLong())
        is DateSpec.Weekday -> {
            val n = today.with(TemporalAdjusters.next(spec.day))
            if (spec.next && n.minusDays(7).isAfter(today)) n else n
        }
        is DateSpec.DayMonth -> {
            val y = today.withMonth(spec.month.value).withDayOfMonth(minOf(spec.day, spec.month.length(today.isLeapYear)))
            if (y.isBefore(today)) y.plusYears(1) else y
        }
    }
}
