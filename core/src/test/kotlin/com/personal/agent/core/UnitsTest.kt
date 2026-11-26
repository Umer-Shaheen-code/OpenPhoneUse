package com.personal.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class UnitsTest {
    private fun toks(s: String) = Normalizer.normalize(s).tokens.map { it.norm }

    @Test fun durations() {
        assertEquals(180_000L, Entities.duration(toks("3 mins")))
        assertEquals(180_000L, Entities.duration(toks("three minutes")))
        assertEquals(90_000L, Entities.duration(toks("a minute and a half")))
        assertEquals(90_000L, Entities.duration(toks("1 minute 30")))
        assertEquals(90_000L, Entities.duration(toks("1:30")))
        assertEquals(30_000L, Entities.duration(toks("half a minute")))
        assertEquals(150_000L, Entities.duration(toks("2 and a half minutes")))
        assertEquals(120_000L, Entities.duration(toks("a couple of minutes")))
        assertEquals(3_900_000L, Entities.duration(toks("1 hour 5 minutes")))
        assertNull(Entities.duration(toks("minutes")))
    }

    @Test fun numberWords() {
        assertEquals(listOf("25"), toks("twenty five"))
        assertEquals(listOf("105"), toks("one hundred and five"))
        assertEquals(listOf("2450"), toks("2,450"))
    }

    @Test fun times() {
        assertEquals(Entities.TimeOfDay(19, 30, true), Entities.timeOfDay(toks("7:30 pm")))
        assertEquals(Entities.TimeOfDay(7, 0, true), Entities.timeOfDay(toks("7 in the morning")))
        assertEquals(Entities.TimeOfDay(6, 30, false), Entities.timeOfDay(toks("half past 6")))
        assertEquals(Entities.TimeOfDay(12, 0, true), Entities.timeOfDay(toks("noon")))
        assertEquals(Entities.TimeOfDay(8, 45, false), Entities.timeOfDay(toks("quarter to 9")))
    }

    @Test fun nextOccurrence() {
        val now = LocalDateTime.of(2026, 11, 25, 20, 0)
        assertEquals(LocalDateTime.of(2026, 11, 26, 7, 0), TimeSpeech.nextOccurrence(now, Entities.TimeOfDay(7, 0, false)))
        assertEquals(LocalDateTime.of(2026, 11, 25, 21, 0), TimeSpeech.nextOccurrence(now, Entities.TimeOfDay(9, 0, false)))
        assertEquals(LocalDateTime.of(2026, 11, 26, 19, 0), TimeSpeech.nextOccurrence(now, Entities.TimeOfDay(19, 0, true)))
    }

    @Test fun dates() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals(LocalDate.of(2026, 12, 25), Dates.resolve(Dates.parse(toks("25th december"))!!, today))
        assertEquals(LocalDate.of(2026, 12, 25), Dates.resolve(Dates.parse(toks("christmas"))!!, today))
        assertEquals(LocalDate.of(2027, 1, 1), Dates.resolve(Dates.parse(toks("new year"))!!, today))
        assertEquals(LocalDate.of(2026, 11, 27), Dates.resolve(Dates.parse(toks("friday"))!!, today))
    }

    @Test fun calc() {
        assertEquals(441.0, Calc.evaluate(toks("18 percent of 2,450"))!!.value, 1e-9)
        assertEquals(37.0, Calc.evaluate(toks("5 times 7 plus 2"))!!.value, 1e-9)
        assertEquals(8.05, Calc.evaluate(toks("5 miles to km"))!!.value, 0.01)
        assertNull(Calc.evaluate(toks("the weather")))
    }

    @Test fun seekMath() {
        // playing, last update 2 s ago at 1x → position advanced by 2 s
        assertEquals(12_000L, MediaMath.currentPosition(10_000, 5_000, 7_000, 1f, true))
        assertEquals(10_000L, MediaMath.currentPosition(10_000, 5_000, 7_000, 1f, false))
        val p = MediaMath.planRelative(724_000, 180_000, 3_600_000, true) as MediaMath.SeekPlan.Seek
        assertEquals(904_000L, p.target)
        val end = MediaMath.planRelative(3_590_000, 180_000, 3_600_000, true) as MediaMath.SeekPlan.Seek
        assertTrue(end.clampedToEnd)
        val start = MediaMath.planRelative(10_000, -30_000, 3_600_000, true) as MediaMath.SeekPlan.Seek
        assertEquals(0L, start.target)
        assertTrue(MediaMath.planRelative(0, 1000, null, false) is MediaMath.SeekPlan.NotSupported)
        assertEquals("3 minutes", MediaMath.spokenDuration(180_000))
        assertEquals("1 minute 30 seconds", MediaMath.spokenDuration(90_000))
    }

    @Test fun endpointer() {
        val e = Endpointer()
        val silence = ShortArray(320)
        val loud = ShortArray(320) { if (it % 2 == 0) 8000 else -8000 }
        repeat(25) { assertEquals(Endpointer.Event.NONE, e.feed(silence)) }
        var started = false
        repeat(30) { if (e.feed(loud) == Endpointer.Event.SPEECH_START) started = true }
        assertTrue(started)
        e.hint = Endpointer.Hint.CLOSED_COMMAND
        var ended = false; var frames = 0
        while (!ended && frames < 100) { if (e.feed(silence) == Endpointer.Event.SPEECH_END) ended = true; frames++ }
        assertTrue(ended)
        assertTrue("ended after ${frames * 20} ms", frames * 20 in 340..420)
    }

    @Test fun noSpeechTimeout() {
        val e = Endpointer(noSpeechTimeoutMs = 1000)
        var ev = Endpointer.Event.NONE
        repeat(60) { val r = e.feed(ShortArray(320)); if (r != Endpointer.Event.NONE) ev = r }
        assertEquals(Endpointer.Event.NO_SPEECH, ev)
    }

    @Test fun riskPolicy() {
        assertTrue(RiskPolicy.decide(Risk.R2_OUTBOUND, 0.95, 0.97) is RiskPolicy.Decision.UndoWindow)
        assertTrue(RiskPolicy.decide(Risk.R2_OUTBOUND, 0.95, 0.85) is RiskPolicy.Decision.Confirm)
        assertTrue(RiskPolicy.decide(Risk.R2_OUTBOUND, 0.95, 0.97, toGroup = true) is RiskPolicy.Decision.Confirm)
        assertTrue(RiskPolicy.decide(Risk.R2_OUTBOUND, 0.95, 0.97, fromLlm = true) is RiskPolicy.Decision.Confirm)
        assertTrue(RiskPolicy.decide(Risk.R1_REVERSIBLE, 0.7) is RiskPolicy.Decision.Execute)
    }

    @Test fun timeSpeech() {
        assertEquals("4:35 PM", TimeSpeech.time(java.time.LocalTime.of(16, 35), false))
        assertEquals("12 AM", TimeSpeech.time(java.time.LocalTime.of(0, 0), false))
        assertEquals("Wednesday, November 25th", TimeSpeech.date(LocalDate.of(2026, 11, 25)))
    }

    @Test fun llmGuards() {
        // User asked to reply to Abdullah; injected message tells Claude to text Boss -> blocked
        assertTrue(LlmGuards.isRecipientAllowed("Abdullah", "reply to Abdullah politely that I'm late"))
        assertTrue(LlmGuards.isRecipientAllowed("Abdullah Khan", "tell Abdulla I am on the way"))
        assertTrue(LlmGuards.isRecipientAllowed("him", "reply politely", setOf("Abdullah Khan")))
        assertFalse(LlmGuards.isRecipientAllowed("Boss", "summarize my whatsapp messages"))
        assertFalse(LlmGuards.isRecipientAllowed("him", "summarize my messages", emptySet()))

        val limiter = LlmGuards.OutboundTurnLimiter(1)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test fun urduTransliterationAndDigits() {
        val m = ContactMatcher(listOf(
            ContactCandidate("1", "عبداللہ", ContactSource.CONTACTS),
            ContactCandidate("2", "علی", ContactSource.CONTACTS),
        ))
        assertEquals("1", m.resolve("Abdullah").best?.candidate?.id)
        assertEquals("2", m.resolve("Ali").best?.candidate?.id)
        assertEquals("5", Normalizer.normalize("۵ minutes").tokens.first().norm)
        assertEquals("3", Normalizer.normalize("teen mint").tokens.first().norm)
    }
}
