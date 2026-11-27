package com.phoneuse.local

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import com.personal.agent.core.CommandParser
import com.personal.agent.core.ContactCandidate
import com.personal.agent.core.ContactMatch
import com.personal.agent.core.ContactMatcher
import com.personal.agent.core.ContactSource
import com.personal.agent.core.ParseContext
import com.personal.agent.core.ParsedCommand
import com.personal.agent.core.SlotValue
import com.phoneuse.agent.UserGate
import com.phoneuse.app.ConfirmMode
import com.phoneuse.app.Source

internal class LocalSlotRequest(
    val name: String,
    val resume: suspend (String) -> LocalExecResult,
)

internal class LocalChoice(
    val spoken: String,
    val options: List<ContactMatch>,
    val resume: suspend (ContactMatch) -> LocalExecResult,
)

/** Short-lived multi-turn context for fast local commands (reply?, choose contact, repeat, read more). */
internal class LocalDialogState {
    var lastConvKey: String? = null
    var lastContactName: String? = null
    var lastEmailKey: String? = null
    var lastResponse: String = ""
    var offeredReply: Boolean = false
    var readQueue: List<com.personal.agent.core.Conversation> = emptyList()
    var readApp: String? = null
    var slotRequest: LocalSlotRequest? = null
    var choice: LocalChoice? = null
    private var lastTouchAt = System.currentTimeMillis()

    val hasActiveFollowUp: Boolean
        get() = offeredReply || slotRequest != null || choice != null

    fun expireIfStale(now: Long = System.currentTimeMillis()) {
        if (now - lastTouchAt > 90_000L) {
            slotRequest = null
            choice = null
            offeredReply = false
        }
        if (now - lastTouchAt > 5 * 60_000L) {
            readQueue = emptyList()
            readApp = null
            lastConvKey = null
            lastContactName = null
            lastEmailKey = null
        }
    }

    fun touch(now: Long = System.currentTimeMillis()) {
        lastTouchAt = now
    }

    fun clearPendingInteractions() {
        slotRequest = null
        choice = null
        offeredReply = false
    }
}

sealed interface LocalAgentOutcome {
    data class Handled(
        val reply: String,
        val steps: List<String> = emptyList(),
        val launchIntent: Intent? = null,
        val stayInApp: Boolean = false,
        val listenAgain: Boolean = false,
        val afterClose: (suspend () -> Unit)? = null,
        val thirdParty: Boolean = false,
    ) : LocalAgentOutcome

    data class Escalate(val reason: String) : LocalAgentOutcome
}

/**
 * Coordinates Tier-1 (zero-AI local NLU + platform APIs) before Tier-2 (OpenPhoneUse AI AgentLoop).
 */
class LocalAgentEngine(
    private val context: Context,
    val store: LocalAgentStore,
    val notifications: LiveNotificationHub,
    val media: MediaHub,
) {
    private val dialog = LocalDialogState()
    private val executors = LocalExecutors(context, store, notifications, media)

    private fun buildParseContext(): ParseContext {
        val convs = notifications.snapshot()
        val aliases = store.aliases()
        val candidates = convs.map { c ->
            ContactCandidate(
                id = "conv:${c.key}",
                displayName = c.title,
                source = if (c.live && c.unread.isNotEmpty()) ContactSource.LIVE_CONVERSATION else ContactSource.RECENT_CONVERSATION,
                aliases = aliases.filterValues { it == c.title }.keys.toList(),
                app = c.app,
                isGroup = c.isGroup,
            )
        }
        return ParseContext(
            contacts = ContactMatcher(candidates),
            mediaActive = media.hasActiveSession,
            readingList = dialog.readQueue.isNotEmpty(),
            contextContactIds = setOfNotNull(dialog.lastConvKey?.let { "conv:$it" }),
        )
    }

    suspend fun tryHandle(
        text: String,
        source: Source,
        hasAttachments: Boolean,
        suggested: Boolean,
        hasAiBackend: Boolean,
        confirmMode: ConfirmMode,
        gate: UserGate,
    ): LocalAgentOutcome {
        val started = System.currentTimeMillis()
        dialog.expireIfStale()
        val parseCtx = buildParseContext()

        val decision = LocalEscalationPolicy.decide(
            text = text,
            source = source,
            hasAttachments = hasAttachments,
            suggested = suggested,
            hasActiveDialogFollowUp = dialog.hasActiveFollowUp,
            hasAiBackend = hasAiBackend,
            parseContext = parseCtx,
        )

        if (decision is LocalRouteDecision.EscalateToAi) {
            return LocalAgentOutcome.Escalate(decision.reason)
        }

        val route = decision as LocalRouteDecision.RunLocal
        val intentId = route.cmd?.intent
        if (source == Source.ASSIST && intentId in LOCK_SENSITIVE &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        ) {
            return LocalAgentOutcome.Handled(
                reply = "Unlock your phone first, then ask me again.",
            )
        }
        val tx = LocalTurnContext(
            dialog = dialog,
            gate = gate,
            confirmMode = confirmMode,
            hasAiBackend = hasAiBackend,
            use24h = DateFormat.is24HourFormat(context),
        )

        val execResult = runLocalTurn(text, route.cmd, tx)
        return when (execResult) {
            is LocalExecResult.FallbackToAi -> LocalAgentOutcome.Escalate(execResult.reason)
            is LocalExecResult.Done -> {
                dialog.touch()
                if (execResult.reply.isNotBlank()) dialog.lastResponse = execResult.reply
                val ms = System.currentTimeMillis() - started
                val intentTag = intentId ?: "dialog"
                Log.i("LocalAgent", "handled=$intentTag ms=$ms src=$source")
                LocalAgentOutcome.Handled(
                    reply = execResult.reply,
                    steps = execResult.steps,
                    launchIntent = execResult.launchIntent,
                    stayInApp = execResult.stayInApp,
                    listenAgain = execResult.listenAgain,
                    afterClose = execResult.afterClose,
                    thirdParty = execResult.thirdParty,
                )
            }
        }
    }

    private suspend fun runLocalTurn(
        heard: String,
        cmd0: ParsedCommand?,
        tx: LocalTurnContext,
    ): LocalExecResult {
        var cmd = cmd0
        val intent = cmd?.intent

        if (intent == "meta.cancel" && dialog.hasActiveFollowUp) {
            dialog.clearPendingInteractions()
            return LocalExecResult.Done("Okay, cancelled.")
        }

        dialog.choice?.let { ch ->
            dialog.choice = null
            val idx = (cmd?.slots?.get("index") as? SlotValue.Ordinal)?.index?.takeIf { intent == "meta.choose" }
            val pick = when {
                idx != null -> ch.options.getOrNull(if (idx < 0) ch.options.size - 1 else idx)
                else -> ch.options.maxByOrNull {
                    ContactMatcher.similarity(ContactMatcher.keyForms(heard), ContactMatcher.keyForms(it.candidate.displayName))
                }?.takeIf {
                    ContactMatcher.similarity(ContactMatcher.keyForms(heard), ContactMatcher.keyForms(it.candidate.displayName)) >= 0.8
                }
            }
            if (pick != null) return ch.resume(pick)
            if (intent == "meta.no") return LocalExecResult.Done("Okay.")
        }

        dialog.slotRequest?.let { req ->
            dialog.slotRequest = null
            val strong = cmd != null && cmd.score >= 0.9 &&
                cmd.intent !in setOf("meta.yes", "meta.choose", "calc", "media.play_search", "app.open")
            if (!strong || req.name == "message") {
                val value = if (req.name == "message") stripLeadIn(heard) else heard.trim()
                if (value.isNotBlank()) return req.resume(value)
            }
        }

        if (dialog.offeredReply) {
            dialog.offeredReply = false
            when (intent) {
                "meta.yes" -> {
                    val name = dialog.lastContactName ?: "them"
                    dialog.slotRequest = LocalSlotRequest("message") { said ->
                        executors.execute(
                            ParsedCommand(
                                "msg.send",
                                mapOf("contact" to SlotValue.Contact("them", true, null), "message" to SlotValue.Text(said)),
                                0.95,
                                said,
                                "dialog",
                            ),
                            tx,
                        )
                    }
                    return LocalExecResult.Done("What should I say to $name?", listenAgain = true)
                }
                "meta.no" -> return LocalExecResult.Done("Okay.")
                else -> if ((cmd == null || cmd.score < CommandParser.ACCEPT) &&
                    Regex("^(?i)(say|tell (him|her|them))\\b").containsMatchIn(heard.trim())
                ) {
                    val msg = stripLeadIn(heard)
                    return executors.execute(
                        ParsedCommand(
                            "msg.send",
                            mapOf("contact" to SlotValue.Contact("them", true, null), "message" to SlotValue.Text(msg)),
                            0.9,
                            heard,
                            "dialog",
                        ),
                        tx,
                    )
                }
            }
        }

        val resolved = cmd ?: return LocalExecResult.FallbackToAi("no local command")

        // Context remaps
        val finalCmd = when {
            resolved.intent == "media.next" && dialog.readQueue.isNotEmpty() &&
                heard.trim().lowercase().removeSuffix(".") in setOf("next", "next one") ->
                resolved.copy(intent = "list.more")
            resolved.intent == "meta.cancel" && media.hasActiveSession && media.nowPlaying()?.playing == true ->
                resolved.copy(intent = "media.pause")
            else -> resolved
        }
        when (finalCmd.intent) {
            "meta.cancel" -> return LocalExecResult.Done("Okay.")
            "meta.repeat" -> return LocalExecResult.Done(dialog.lastResponse.ifBlank { "I haven't said anything yet." })
            "meta.yes", "meta.no", "meta.choose" -> return LocalExecResult.FallbackToAi("ambiguous meta response")
        }

        return executors.execute(finalCmd, tx)
    }

    private fun stripLeadIn(s: String): String {
        val t = s.trim()
        val m = Regex("^(?i)(say|tell (him|her|them)|reply|reply with|saying|that)\\s+").find(t)
        return (if (m != null) t.substring(m.range.last + 1) else t).trim().trim('"')
    }

    companion object {
        private val LOCK_SENSITIVE = setOf(
            "notif.read", "msg.send", "list.more", "notif.mark_read",
            "notif.clear", "notif.archive", "calendar.agenda", "note.read",
        )
    }
}
