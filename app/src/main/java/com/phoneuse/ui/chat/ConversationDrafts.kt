package com.phoneuse.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** A composer can still deliver speech after a new chat receives its permanent ID. */
internal class ConversationDraft(initial: TextFieldValue) {
    private var local by mutableStateOf(initial)
    private var joined by mutableStateOf<ConversationDraft?>(null)

    var value: TextFieldValue
        get() = joined?.value ?: local
        set(value) {
            val target = joined
            if (target == null) local = value else target.value = value
        }

    fun join(target: ConversationDraft) {
        val before = value
        if (before.text.isNotEmpty()) {
            val after = target.value
            target.value = if (after.text.isEmpty()) before else before.appendText(after.text)
        }
        // Both the old composer and an already visible dock may still have callbacks holding their draft.
        joined = target
        local = TextFieldValue()
    }
}

/** The action dock and the chat edit the same draft on the main thread, even across activity launches. */
internal object ConversationDrafts {
    private val drafts = mutableMapOf<Long?, ConversationDraft>()

    fun state(id: Long?, initial: TextFieldValue = TextFieldValue()): ConversationDraft =
        drafts.getOrPut(id) { ConversationDraft(initial) }

    fun move(from: Long?, to: Long) {
        if (from == to) return
        val old = drafts.remove(from) ?: return
        val target = drafts[to]
        if (target == null) drafts[to] = old else old.join(target)
    }

    fun append(id: Long, speech: String) {
        if (speech.isBlank()) return
        val draft = state(id)
        draft.value = draft.value.appendSpeech(speech)
    }

    fun forget(ids: Collection<Long>) {
        for (id in ids) drafts.remove(id)?.value = TextFieldValue()
    }

    fun clear() {
        forget(drafts.keys.filterNotNull())
        drafts[null]?.value = TextFieldValue()
    }
}

internal fun TextFieldValue.appendSpeech(speech: String): TextFieldValue = appendText(speech.trim())

private fun TextFieldValue.appendText(words: String): TextFieldValue {
    if (words.isEmpty()) return this
    val separator = if (text.isEmpty() || text.last().isWhitespace() || words.first().isWhitespace()) "" else " "
    val combined = text + separator + words
    return TextFieldValue(combined, TextRange(combined.length))
}

/** Save the visible composer with its owner. A live draft may already be newer than the activity snapshot. */
@Composable
internal fun rememberConversationDraft(id: Long?): ConversationDraft {
    val saver = listSaver<ConversationDraft, Any>(
        save = { listOf(id ?: -1L, it.value.text, it.value.selection.start, it.value.selection.end) },
        restore = {
            val owner = (it[0] as Long).takeIf { key -> key >= 0 }
            ConversationDrafts.state(owner, TextFieldValue(it[1] as String, TextRange(it[2] as Int, it[3] as Int)))
            ConversationDrafts.state(id)
        },
    )
    return rememberSaveable(id, saver = saver) { ConversationDrafts.state(id) }
}
