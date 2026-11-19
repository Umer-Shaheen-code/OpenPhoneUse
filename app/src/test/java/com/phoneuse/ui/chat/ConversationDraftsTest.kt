package com.phoneuse.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.phoneuse.assist.VoiceBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationDraftsTest {
    @After fun clear() {
        VoiceBridge.activeSession.value?.let { VoiceBridge.deliver(it, null) }
        ConversationDrafts.clear()
    }

    @Test fun returnedChatKeepsTypedTextAndTheLatestDictationFromItsDock() {
        val dock = ConversationDrafts.state(11)
        dock.value = TextFieldValue("Change the date to")
        val id = VoiceBridge.request { words -> if (words != null) ConversationDrafts.append(11, words) }
        VoiceBridge.update(id, "next")
        VoiceBridge.update(id, "next Friday")
        VoiceBridge.interrupt(id)
        VoiceBridge.deliver(id, "duplicate late result")

        val chat = ConversationDrafts.state(11, TextFieldValue("older activity snapshot"))
        assertSame(dock, chat)
        assertEquals("Change the date to next Friday", chat.value.text)
        assertEquals(TextRange(chat.value.text.length), chat.value.selection)
        assertEquals("", ConversationDrafts.state(12).value.text)
    }

    @Test fun successivePhrasesAppendOnceAndCancellingTheNextOneKeepsTheDraft() {
        fun listen() = VoiceBridge.request { words -> if (words != null) ConversationDrafts.append(12, words) }
        VoiceBridge.deliver(listen(), "Show directions")
        ConversationDrafts.state(12).value = TextFieldValue("Show walking directions\n")
        VoiceBridge.deliver(listen(), "to the station")
        val id = listen()
        VoiceBridge.update(id, "discard this")
        VoiceBridge.deliver(id, null)
        assertEquals("Show walking directions\nto the station", ConversationDrafts.state(12).value.text)
    }

    @Test fun newChatAssignmentKeepsLateSpeechInTheConversationWhenItsDockAlreadyExists() {
        val homeComposer = ConversationDrafts.state(null)
        homeComposer.value = TextFieldValue("Use")
        val actionDock = ConversationDrafts.state(11)
        actionDock.value = TextFieldValue("walking directions")
        ConversationDrafts.move(null, 11)
        homeComposer.value = homeComposer.value.appendSpeech("to the station")
        assertEquals("Use walking directions to the station", actionDock.value.text)
        assertEquals("", ConversationDrafts.state(null).value.text)
    }
}
