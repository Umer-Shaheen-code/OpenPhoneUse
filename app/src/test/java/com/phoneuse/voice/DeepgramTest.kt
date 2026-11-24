package com.phoneuse.voice

import com.phoneuse.agent.AgentPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramTest {
    private fun results(text: String, isFinal: Boolean, speechFinal: Boolean = false) =
        """{"type":"Results","channel":{"alternatives":[{"transcript":"$text","confidence":0.9}]},"is_final":$isFinal,"speech_final":$speechFinal}"""

    @Test
    fun parsesResultsAndEvents() {
        assertEquals(Deepgram.Message.Transcript("open whatsapp", isFinal = true, speechFinal = true), Deepgram.parse(results("open whatsapp", true, true)))
        assertEquals(Deepgram.Message.UtteranceEnd, Deepgram.parse("""{"type":"UtteranceEnd","last_word_end":2.1}"""))
        assertEquals(Deepgram.Message.SpeechStarted, Deepgram.parse("""{"type":"SpeechStarted"}"""))
        assertEquals(Deepgram.Message.Other, Deepgram.parse("""{"type":"Metadata"}"""))
        assertEquals(Deepgram.Message.Other, Deepgram.parse("not json"))
    }

    @Test
    fun transcriptJoinsFinalSegmentsAndRevisesInterim() {
        val t = Deepgram.Transcript()
        t.add(Deepgram.Message.Transcript("call", isFinal = false, speechFinal = false))
        assertEquals("call", t.text)
        t.add(Deepgram.Message.Transcript("call ali", isFinal = true, speechFinal = false))
        t.add(Deepgram.Message.Transcript("on", isFinal = false, speechFinal = false))
        assertEquals("call ali on", t.text)
        t.add(Deepgram.Message.Transcript("on whatsapp", isFinal = true, speechFinal = true))
        assertEquals("call ali on whatsapp", t.text)
        // An empty final (silence) adds nothing.
        t.add(Deepgram.Message.Transcript("", isFinal = true, speechFinal = false))
        assertEquals("call ali on whatsapp", t.text)
    }

    @Test
    fun languageCodes() {
        assertEquals("en-US", Deepgram.languageCode("en-US"))
        assertEquals("ur", Deepgram.languageCode("ur-PK"))
        assertEquals("hi", Deepgram.languageCode("hi-IN"))
        assertEquals("ko", Deepgram.languageCode("ko-KR"))
        assertTrue(Deepgram.listenUrl("ur-PK").contains("model=nova-3&language=ur&"))
    }

    @Test
    fun chunksSplitAtSentencesUnderTheLimit() {
        val text = "First sentence here. Second one is a bit longer. Third."
        val parts = Deepgram.chunks(text, max = 30)
        assertTrue(parts.all { it.length <= 30 })
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf("short"), Deepgram.chunks("  short "))
        // No space at all: a hard cut still terminates.
        assertEquals(listOf("abcde", "fghij"), Deepgram.chunks("abcdefghij", max = 5))
    }

    @Test
    fun levelMapsSilenceAndLoudness() {
        assertEquals(-2f, Deepgram.levelDb(ByteArray(320), 320), 0.01f)
        val loud = ByteArray(320) { i -> if (i % 2 == 0) 0xff.toByte() else 0x7f } // 0x7fff samples
        assertEquals(10f, Deepgram.levelDb(loud, loud.size), 0.1f)
    }

    @Test
    fun voiceModeMarkIsFreshOnlyBriefly() {
        assertFalse(VoiceMode.isFresh(0L, 5_000L))
        assertTrue(VoiceMode.isFresh(1_000L, 3_000L))
        assertFalse(VoiceMode.isFresh(1_000L, 20_000L))
    }

    @Test
    fun siteNameForSpokenOpeningLine() {
        assertEquals("Youtube", VoiceMode.siteName("https://www.youtube.com"))
        assertEquals("Youtube", VoiceMode.siteName("https://m.youtube.com/watch?v=1"))
        assertEquals("Google", VoiceMode.siteName("http://google.com:8080/x"))
        assertEquals("Localhost", VoiceMode.siteName("http://localhost/"))
        assertNull(VoiceMode.siteName("tel:123"))
        assertNull(VoiceMode.siteName("android.settings.WIFI_SETTINGS"))
    }

    @Test
    fun voiceNoteJoinsTheMemoryNote() {
        assertNull(AgentPrompt.withVoice(null, voice = false))
        assertEquals("mem", AgentPrompt.withVoice("mem", voice = false))
        assertEquals(VoiceMode.NOTE, AgentPrompt.withVoice(null, voice = true))
        assertEquals("mem\n\n" + VoiceMode.NOTE, AgentPrompt.withVoice("mem", voice = true))
    }
}
