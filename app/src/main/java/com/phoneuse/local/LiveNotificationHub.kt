package com.phoneuse.local

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.personal.agent.core.Apps
import com.personal.agent.core.Conversation
import com.personal.agent.core.MessageText
import com.personal.agent.core.Msg
import com.personal.agent.core.MsgKind
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.phone.SmsParsers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Notification actions we can trigger in the background without opening the source app. */
data class LiveHandles(
    val sbnKey: String,
    val pkg: String,
    val reply: Notification.Action?,
    val markRead: Notification.Action?,
    val archive: Notification.Action?,
    val delete: Notification.Action?,
    val contentIntent: PendingIntent?,
)

/**
 * In-memory structured MessagingStyle / Gmail conversation cache + RemoteInput reply handles,
 * kept in sync with [PhoneUseNotificationListener]. OTP codes are masked via [SmsParsers.maskCodes].
 */
class LiveNotificationHub private constructor(private val appContext: Context) {
    private val convs = LinkedHashMap<String, Conversation>()
    private val handles = HashMap<String, LiveHandles>()

    /** Last reply action per conversation, kept for 12 h after dismissal so follow-up replies still work. */
    private val replyCache = object : LinkedHashMap<String, Pair<Notification.Action, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Notification.Action, Long>>) = size > 50
    }

    private val _posted = MutableSharedFlow<Conversation>(extraBufferCapacity = 32)
    val posted: SharedFlow<Conversation> = _posted

    @Volatile var listenerConnected: Boolean = false
        private set

    fun isAccessEnabled(): Boolean = PhoneUseNotificationListener.enabled(appContext)

    fun onListenerConnected(active: Array<StatusBarNotification>?, rankingMap: NotificationListenerService.RankingMap?) {
        listenerConnected = true
        if (active != null) seed(active, rankingMap)
    }

    fun onListenerDisconnected() {
        listenerConnected = false
    }

    @Synchronized fun snapshot(): List<Conversation> = convs.values.toList()

    @Synchronized fun handlesFor(key: String): LiveHandles? = handles[key]

    @Synchronized fun get(key: String): Conversation? = convs[key]

    @Synchronized fun replyActionFor(key: String): Pair<Notification.Action, Boolean>? {
        handles[key]?.reply?.let { return it to true }
        val cached = replyCache[key] ?: return null
        if (System.currentTimeMillis() - cached.second > REPLY_CACHE_TTL_MS) {
            replyCache.remove(key)
            return null
        }
        return cached.first to false
    }

    @Synchronized fun evictCachedReply(key: String) {
        replyCache.remove(key)
    }

    @Synchronized fun markSpoken(upTo: Map<String, Long>) {
        for ((k, t) in upTo) {
            convs[k]?.let { convs[k] = it.copy(spokenUpTo = maxOf(it.spokenUpTo, t)) }
        }
    }

    @Synchronized fun clear() {
        convs.clear()
        handles.clear()
        replyCache.clear()
    }

    @Synchronized fun onPosted(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?): Conversation? {
        val parsed = NotificationParser.parse(sbn, ranking, appContext.packageName) ?: return null
        val old = convs[parsed.conv.key]
        val merged = if (old == null) {
            parsed.conv
        } else {
            parsed.conv.copy(
                messages = MessageText.merge(old.messages, parsed.conv.messages),
                spokenUpTo = old.spokenUpTo,
            )
        }
        convs.remove(parsed.conv.key)
        convs[parsed.conv.key] = merged
        handles[parsed.conv.key] = parsed.handles
        parsed.handles.reply?.let { replyCache[parsed.conv.key] = it to System.currentTimeMillis() }
        prune()
        _posted.tryEmit(merged)
        return merged
    }

    @Synchronized fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        val key = handles.entries.firstOrNull { it.value.sbnKey == sbn.key }?.key ?: return
        handles.remove(key)
        convs[key]?.let { c ->
            val readElsewhere = reason == NotificationListenerService.REASON_APP_CANCEL ||
                reason == NotificationListenerService.REASON_APP_CANCEL_ALL
            val maxTime = c.messages.maxOfOrNull { it.time } ?: c.spokenUpTo
            convs[key] = c.copy(
                live = false,
                spokenUpTo = if (readElsewhere) maxOf(c.spokenUpTo, maxTime) else c.spokenUpTo,
            )
        }
    }

    @Synchronized private fun seed(active: Array<StatusBarNotification>, rankingMap: NotificationListenerService.RankingMap?) {
        for (s in active.sortedBy { it.postTime }) {
            val rank = rankingMap?.let { r -> NotificationListenerService.Ranking().takeIf { r.getRanking(s.key, it) } }
            onPosted(s, rank)
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - ARCHIVE_TTL_MS
        convs.entries.removeIf { !it.value.live && it.value.updatedAt < cutoff }
        while (convs.size > MAX_CONVS) convs.remove(convs.keys.first())
    }

    companion object {
        private const val REPLY_CACHE_TTL_MS = 12 * 3_600_000L
        private const val ARCHIVE_TTL_MS = 48 * 3_600_000L
        private const val MAX_CONVS = 300

        @Volatile private var instance: LiveNotificationHub? = null

        fun get(context: Context): LiveNotificationHub =
            instance ?: synchronized(this) {
                instance ?: LiveNotificationHub(context.applicationContext).also { instance = it }
            }
    }
}

internal object NotificationParser {
    data class Parsed(val conv: Conversation, val handles: LiveHandles)

    private val NOISE = listOf(
        Regex("checking for new messages", RegexOption.IGNORE_CASE),
        Regex("whatsapp web is currently active", RegexOption.IGNORE_CASE),
        Regex("backup in progress|backing up|restoring media|preparing backup", RegexOption.IGNORE_CASE),
        Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE),
        Regex("^\\d+ messages? from \\d+ chats?$", RegexOption.IGNORE_CASE),
        Regex("syncing mail|checking mail", RegexOption.IGNORE_CASE),
    )
    private val IGNORED_PKGS = setOf("android", "com.android.systemui", "com.android.providers.downloads")

    fun parse(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?, selfPkg: String): Parsed? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == selfPkg || sbn.packageName in IGNORED_PKGS) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing || n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (n.visibility == Notification.VISIBILITY_SECRET) return null
        if (n.category in setOf(Notification.CATEGORY_SERVICE, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SYSTEM)) return null

        val ex = n.extras ?: return null
        val rawTitle = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (rawTitle.isEmpty() && rawText.isEmpty()) return null
        if (NOISE.any { it.containsMatchIn(rawTitle) || it.containsMatchIn(rawText) }) return null

        val both = "$rawTitle\n$rawText"
        val title = SmsParsers.maskCodes(rawTitle, both)
        val text = SmsParsers.maskCodes(rawText, both)

        val app = Apps.idForPackage(sbn.packageName) ?: sbn.packageName
        val muted = ranking?.let { it.importance <= NotificationManager.IMPORTANCE_LOW || it.isAmbient } ?: false
        val handles = actions(sbn)

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val conv: Conversation = when {
            app == "gmail" -> gmail(sbn, title, text)
            style != null && style.messages.isNotEmpty() -> {
                val convTitle = SmsParsers.maskCodes(style.conversationTitle?.toString()?.trim()?.ifEmpty { null } ?: title, both)
                val isGroup = style.isGroupConversation
                val userName = style.user.name?.toString()
                val msgs = style.messages.map { m ->
                    val sender = m.person?.name?.toString()
                    val fromMe = m.person == null || (userName != null && sender == userName && sender != convTitle)
                    val rawBody = m.text?.toString().orEmpty()
                    val body = SmsParsers.maskCodes(rawBody, "$title\n$rawBody")
                    Msg(if (fromMe) "You" else sender ?: convTitle, body, m.timestamp, MessageText.classify(body), fromMe)
                }
                val key = sbn.packageName + ":" + (n.shortcutId?.takeIf { it.isNotBlank() } ?: convTitle)
                Conversation(key, app, cleanTitle(convTitle), isGroup, msgs, muted = muted, live = true, updatedAt = sbn.postTime)
            }
            else -> {
                val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
                val rawBody = lines?.lastOrNull() ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
                val body = SmsParsers.maskCodes(rawBody, "$title\n$rawBody")
                val groupSplit = Regex("^(.+?)(?: \\(\\d+ messages\\))?: (.+)$").find(title)
                val (convTitle, sender, isGroup) = if (groupSplit != null && Apps.idForPackage(sbn.packageName) != null) {
                    Triple(groupSplit.groupValues[1], groupSplit.groupValues[2], true)
                } else {
                    Triple(title, title, false)
                }
                val key = sbn.packageName + ":" + (n.shortcutId?.takeIf { it.isNotBlank() } ?: if (Apps.idForPackage(sbn.packageName) != null) convTitle else "${sbn.tag}:${sbn.id}")
                Conversation(key, app, cleanTitle(convTitle), isGroup, listOf(Msg(sender, body, sbn.postTime, MessageText.classify(body))), muted = muted, live = true, updatedAt = sbn.postTime)
            }
        }
        return Parsed(conv, handles.copy(sbnKey = sbn.key))
    }

    private fun gmail(sbn: StatusBarNotification, sender: String, subject: String): Conversation {
        val ex = sbn.notification.extras
        val rawBig = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val big = SmsParsers.maskCodes(rawBig, "$sender\n$subject\n$rawBig")
        val snippet = big.removePrefix(subject).trim().takeIf { it.isNotBlank() && it != subject }
        return Conversation(
            key = "gmail:${sbn.key}",
            app = "gmail",
            title = sender,
            isGroup = false,
            messages = listOf(Msg(sender, subject.ifBlank { "(no subject)" }, sbn.postTime, MsgKind.TEXT, detail = snippet)),
            live = true,
            updatedAt = sbn.postTime,
        )
    }

    private fun cleanTitle(t: String) = t.replace(Regex(" \\(\\d+ messages?\\)$"), "").trim()

    private fun actions(sbn: StatusBarNotification): LiveHandles {
        val n = sbn.notification
        val all = n.actions.orEmpty().toList() + runCatching { Notification.WearableExtender(n).actions }.getOrDefault(emptyList())
        fun label(a: Notification.Action) = a.title?.toString()?.lowercase().orEmpty()
        val reply = all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
            ?: all.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val markRead = all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
            ?: all.firstOrNull { label(it).contains("mark as read") || label(it) == "mark read" || label(it) == "read" }
        val archive = all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_ARCHIVE }
            ?: all.firstOrNull { label(it) == "archive" }
        val delete = all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_DELETE }
            ?: all.firstOrNull { label(it) == "delete" }
        return LiveHandles(sbn.key, sbn.packageName, reply, markRead, archive, delete, n.contentIntent)
    }
}

/** Fires notification actions (RemoteInput reply, mark read, archive) in the background without opening the app. */
object ActionInvoker {
    sealed class Outcome {
        data object Sent : Outcome()
        data object Dead : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    fun reply(context: Context, action: Notification.Action, text: String): Outcome {
        val inputs = action.remoteInputs ?: return Outcome.Failed("no remote input")
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return send(context, action.actionIntent, fillIn)
    }

    fun fire(context: Context, action: Notification.Action): Outcome = send(context, action.actionIntent, null)

    fun send(context: Context, pi: PendingIntent?, fillIn: Intent?): Outcome {
        pi ?: return Outcome.Failed("no intent")
        return try {
            val opts = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            pi.send(context, 0, fillIn, null, null, null, opts.toBundle())
            Outcome.Sent
        } catch (e: PendingIntent.CanceledException) {
            Outcome.Dead
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }
}
