package com.personal.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {
    private val abdullah = Conversation(
        "wa:abdullah", "whatsapp", "Abdullah", false,
        listOf(Msg("Abdullah", "Where are you?", 1), Msg("Abdullah", "Call me", 2), Msg("Abdullah", "🎤 Voice message (0:12)", 3, MessageText.classify("🎤 Voice message (0:12)"))),
    )
    private val family = Conversation("wa:family", "whatsapp", "Family", true, listOf(Msg("Mom", "Dinner at 8", 4)))

    @Test fun whatsappDigest() {
        val s = DigestSpeaker.messagingDigest(listOf(abdullah, family), "whatsapp", onlyNew = false)
        println(s.text)
        assertTrue(s.text, s.text.startsWith("You have 4 unread WhatsApp messages from 2 chats."))
        assertTrue(s.text, s.text.contains("Abdullah, 3 messages: 'Where are you?', 'Call me', and a voice message."))
        assertTrue(s.text, s.text.contains("In Family: Mom said 'Dinner at 8'."))
        assertEquals(3L, s.readUpTo["wa:abdullah"])
    }

    @Test fun singleMessage() {
        val c = abdullah.copy(messages = abdullah.messages.take(1))
        assertEquals("Abdullah says 'Where are you?'.", DigestSpeaker.messagingDigest(listOf(c), "whatsapp", false).text)
    }

    @Test fun onlyNewRespectsWatermark() {
        val c = abdullah.copy(spokenUpTo = 3)
        assertEquals("Nothing new.", DigestSpeaker.messagingDigest(listOf(c), "whatsapp", onlyNew = true).text)
    }

    @Test fun mutedSkipped() {
        val s = DigestSpeaker.messagingDigest(listOf(family.copy(muted = true)), "whatsapp", false)
        assertEquals("No unread WhatsApp messages.", s.text)
    }

    @Test fun mergeDedupes() {
        val repost = abdullah.messages + Msg("Abdullah", "Hello?", 5)
        val merged = MessageText.merge(abdullah.messages, repost)
        assertEquals(4, merged.size)
    }

    @Test fun email() {
        val mail = Conversation("gm:1", "gmail", "Amazon", false, listOf(Msg("Amazon", "Your order has shipped", 10, detail = "Track your package")))
        val s = DigestSpeaker.emailDigest(listOf(mail), false)
        assertEquals("One new email. From Amazon: Your order has shipped. Track your package.", s.text)
    }

    @Test fun speakable() {
        assertEquals("see you", MessageText.speakable("see you 😀😀"))
        assertEquals("thumbs up", MessageText.speakable("👍"))
        assertEquals(MsgKind.PHOTO, MessageText.classify("📷 Photo"))
        assertEquals(MsgKind.DELETED, MessageText.classify("This message was deleted"))
    }
}
