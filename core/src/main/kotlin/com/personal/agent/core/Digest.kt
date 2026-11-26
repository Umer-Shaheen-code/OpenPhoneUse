package com.personal.agent.core

enum class MsgKind { TEXT, PHOTO, VIDEO, VOICE, AUDIO, DOC, STICKER, GIF, CONTACT, LOCATION, DELETED, POLL }

data class Msg(
    val sender: String?,
    val text: String,
    val time: Long,
    val kind: MsgKind = MsgKind.TEXT,
    val fromMe: Boolean = false,
    val detail: String? = null,       // e.g. email snippet
) {
    val identity: String get() = "$time|${sender.orEmpty()}|${text.hashCode()}"
}

data class Conversation(
    val key: String,
    val app: String,                  // canonical app id ("whatsapp", "gmail", ...)
    val title: String,
    val isGroup: Boolean,
    val messages: List<Msg>,
    val spokenUpTo: Long = 0,
    val muted: Boolean = false,
    val live: Boolean = true,         // a notification is currently posted
    val updatedAt: Long = 0,
) {
    val unread: List<Msg> get() = if (live) messages.filter { !it.fromMe } else emptyList()
    val unspoken: List<Msg> get() = unread.filter { it.time > spokenUpTo }
}

object MessageText {
    private val PLACEHOLDERS: List<Pair<Regex, MsgKind>> = listOf(
        Regex("^(📷\\s*)?(photo|image)$", RegexOption.IGNORE_CASE) to MsgKind.PHOTO,
        Regex("^(📷|🖼️?)\\s*.*", RegexOption.IGNORE_CASE) to MsgKind.PHOTO,
        Regex("^(🎥|📹)?\\s*video( \\(.*\\))?$", RegexOption.IGNORE_CASE) to MsgKind.VIDEO,
        Regex("^🎥.*") to MsgKind.VIDEO,
        Regex("^(🎤)?\\s*voice message( \\(.*\\))?$", RegexOption.IGNORE_CASE) to MsgKind.VOICE,
        Regex("^🎤.*") to MsgKind.VOICE,
        Regex("^(🎵)?\\s*audio( \\(.*\\))?$", RegexOption.IGNORE_CASE) to MsgKind.AUDIO,
        Regex("^(📄|📎).*") to MsgKind.DOC,
        Regex("^(sticker|💟 sticker)$", RegexOption.IGNORE_CASE) to MsgKind.STICKER,
        Regex("^(gif|👾 gif)$", RegexOption.IGNORE_CASE) to MsgKind.GIF,
        Regex("^(👤)\\s*.*") to MsgKind.CONTACT,
        Regex("^(📍)\\s*.*|^(live )?location$", RegexOption.IGNORE_CASE) to MsgKind.LOCATION,
        Regex("^(🚫\\s*)?this message was deleted\\.?$", RegexOption.IGNORE_CASE) to MsgKind.DELETED,
        Regex("^(📊)\\s*.*|^poll:.*", RegexOption.IGNORE_CASE) to MsgKind.POLL,
    )

    fun classify(text: String): MsgKind {
        val t = text.trim()
        for ((re, k) in PLACEHOLDERS) if (re.matches(t)) return k
        return MsgKind.TEXT
    }

    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}\\x{1F1E6}-\\x{1F1FF}]+")
    private val EMOJI_WORDS = mapOf("❤️" to "heart", "❤" to "heart", "👍" to "thumbs up", "😂" to "laughing", "🙏" to "please", "😊" to "smile")

    /** Make text TTS-friendly: map the few emoji that carry meaning, drop the rest, collapse whitespace. */
    fun speakable(text: String): String {
        var s = text
        if (s.trim() in EMOJI_WORDS) return EMOJI_WORDS.getValue(s.trim())
        s = s.replace(Regex("https?://\\S+"), "a link")
        s = EMOJI.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun describe(m: Msg): String = when (m.kind) {
        MsgKind.TEXT -> "'${truncate(speakable(m.text))}'"
        MsgKind.PHOTO -> "a photo"; MsgKind.VIDEO -> "a video"; MsgKind.VOICE -> "a voice message"; MsgKind.AUDIO -> "an audio clip"
        MsgKind.DOC -> "a document"; MsgKind.STICKER -> "a sticker"; MsgKind.GIF -> "a GIF"; MsgKind.CONTACT -> "a contact"
        MsgKind.LOCATION -> "a location"; MsgKind.DELETED -> "a deleted message"; MsgKind.POLL -> "a poll"
    }

    fun truncate(s: String, maxWords: Int = 25): String {
        val w = s.split(' ')
        return if (w.size <= maxWords) s else w.take(maxWords).joinToString(" ") + "…"
    }

    /** Merge a re-posted notification's message list into what we have, keeping order and dropping duplicates. */
    fun merge(existing: List<Msg>, incoming: List<Msg>, keep: Int = 50): List<Msg> {
        val seen = existing.map { it.identity }.toHashSet()
        val out = existing.toMutableList()
        for (m in incoming) if (seen.add(m.identity)) out += m
        return out.sortedBy { it.time }.takeLast(keep)
    }
}

object DigestSpeaker {
    data class Spoken(val text: String, val convKeys: List<String>, val readUpTo: Map<String, Long>, val remaining: Int)

    fun messagingDigest(convs0: List<Conversation>, app: String?, onlyNew: Boolean, maxChats: Int = 5, maxPerChat: Int = 3, contactFilter: String? = null): Spoken {
        val appName = if (app == null) "" else Apps.displayName(app) + " "
        val convs = convs0
            .filter { (if (onlyNew) it.unspoken else it.unread).isNotEmpty() }
            .filter { !it.muted || contactFilter != null }
            .sortedByDescending { c -> c.messages.maxOfOrNull { it.time } ?: 0 }
        if (convs.isEmpty()) {
            val who = contactFilter?.let { " from $it" } ?: ""
            return Spoken(if (onlyNew) "Nothing new$who." else "No unread ${appName}messages$who.", emptyList(), emptyMap(), 0)
        }
        val total = convs.sumOf { (if (onlyNew) it.unspoken else it.unread).size }
        val sb = StringBuilder()
        if (convs.size > 1 || total > 3) {
            sb.append("You have ${MediaMath.plural(total, "unread ${appName}message".trim()).replace("messages", "messages")}")
            if (convs.size > 1) sb.append(" from ${convs.size} chats")
            sb.append(". ")
        }
        val shown = convs.take(maxChats)
        val readUpTo = HashMap<String, Long>()
        for (c in shown) {
            val msgs = (if (onlyNew) c.unspoken else c.unread)
            val latest = msgs.takeLast(maxPerChat)
            readUpTo[c.key] = msgs.maxOf { it.time }
            if (c.isGroup) {
                sb.append("In ${c.title}: ")
                val bySender = latest.groupBy { it.sender ?: "someone" }
                sb.append(bySender.entries.joinToString("; ") { (s, ms) -> "$s said " + joinAnd(ms.map(MessageText::describe)) })
                if (msgs.size > latest.size) sb.append(", and ${msgs.size - latest.size} more")
                sb.append(". ")
            } else {
                val name = c.title
                if (msgs.size == 1) sb.append("$name says ${MessageText.describe(latest[0])}. ")
                else {
                    sb.append("$name, ${msgs.size} messages: ")
                    sb.append(joinAnd(latest.map(MessageText::describe)))
                    if (msgs.size > latest.size) sb.append(", and ${msgs.size - latest.size} earlier")
                    sb.append(". ")
                }
            }
        }
        val remaining = convs.size - shown.size
        if (remaining > 0) sb.append("And ${MediaMath.plural(remaining, "more chat")}.")
        return Spoken(sb.toString().trim(), shown.map { it.key }, readUpTo, remaining)
    }

    fun emailDigest(convs0: List<Conversation>, onlyNew: Boolean, max: Int = 5, senderFilter: String? = null): Spoken {
        val mails = convs0.flatMap { c -> (if (onlyNew) c.unspoken else c.unread).map { c to it } }.sortedByDescending { it.second.time }
        if (mails.isEmpty()) return Spoken(if (senderFilter != null) "No new emails from $senderFilter." else "No new emails.", emptyList(), emptyMap(), 0)
        val sb = StringBuilder(if (mails.size == 1) "One new email. " else "${mails.size} new emails. ")
        val shown = mails.take(max)
        for ((_, m) in shown) {
            sb.append("From ${m.sender ?: "unknown"}: ${MessageText.truncate(MessageText.speakable(m.text), 15)}")
            m.detail?.takeIf { it.isNotBlank() && mails.size <= 2 }?.let { sb.append(". ").append(MessageText.truncate(MessageText.speakable(it), 25)) }
            sb.append(". ")
        }
        if (mails.size > max) sb.append("And ${mails.size - max} more.")
        val readUpTo = shown.groupBy { it.first.key }.mapValues { (_, v) -> v.maxOf { it.second.time } }
        return Spoken(sb.toString().trim(), shown.map { it.first.key }.distinct(), readUpTo, (mails.size - max).coerceAtLeast(0))
    }

    fun genericDigest(convs: List<Conversation>, max: Int = 6): Spoken {
        val items = convs.filter { it.unread.isNotEmpty() && !it.muted }.sortedByDescending { it.updatedAt }
        if (items.isEmpty()) return Spoken("You're all caught up. No notifications.", emptyList(), emptyMap(), 0)
        val byApp = items.groupBy { it.app }
        val sb = StringBuilder()
        sb.append("You have notifications from ${joinAnd(byApp.keys.map { Apps.names[it] ?: it })}. ")
        for (c in items.take(max)) {
            val m = c.unread.last()
            val app = Apps.names[c.app] ?: c.app
            sb.append("$app, ${c.title}: ${MessageText.describe(m).trim('\'')}. ")
        }
        if (items.size > max) sb.append("And ${items.size - max} more.")
        return Spoken(sb.toString().trim(), items.take(max).map { it.key }, items.take(max).associate { it.key to it.unread.maxOf { m -> m.time } }, (items.size - max).coerceAtLeast(0))
    }

    fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""; 1 -> items[0]; 2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }
}
