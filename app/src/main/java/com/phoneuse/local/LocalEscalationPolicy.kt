package com.phoneuse.local

import com.personal.agent.core.CommandParser
import com.personal.agent.core.ParseContext
import com.personal.agent.core.ParsedCommand
import com.phoneuse.app.Source

/** Decision of [LocalEscalationPolicy]: run fast locally without AI, or hand off to the AI AgentLoop. */
sealed interface LocalRouteDecision {
    data class RunLocal(val cmd: ParsedCommand?, val isFollowUp: Boolean = false) : LocalRouteDecision
    data class EscalateToAi(val reason: String) : LocalRouteDecision
}

/**
 * Pure JVM gatekeeper deciding whether a user request should run locally (zero AI) or escalate
 * to OpenPhoneUse's AI [com.phoneuse.agent.AgentLoop].
 */
object LocalEscalationPolicy {
    private val COMPLEX_AI_PATTERNS = listOf(
        Regex("\\b(summarize|summarise|summary|draft|compose|rewrite|rephrase|compare|cheapest|recommend|research|explain|itinerary|routine)\\b", RegexOption.IGNORE_CASE),
        Regex("^(?!open\\s+|launch\\s+).*\\btranslate\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(politely|professionally|formally|nicely|cleverly)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(on screen|on my screen|this screen|read the screen|what is on the screen|whats on my screen)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(tap|click|scroll|swipe|pinch|rotate|fill in|type into)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(search the web|look up online|find online)\\b", RegexOption.IGNORE_CASE),
        Regex("^google\\s+(?!maps\\b|chrome\\b|drive\\b|photos\\b|meet\\b|calendar\\b|translate\\b|keep\\b|docs\\b|sheets\\b)\\S+", RegexOption.IGNORE_CASE),
        Regex("\\b(on|in|from)\\s+wikipedia\\b|\\bwikipedia\\s+(list|article|page|for)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(under \\d+ min|rated \\d|top \\d|alternatives|pros and cons)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bif\\b.+\\b(then|otherwise|else)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(how to|how do i|why is|why does|why did)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(tic tac toe|2048|minesweeper)\\b", RegexOption.IGNORE_CASE),
    )

    private val COMPOUND_SEPARATORS = Regex("\\s+(and then|and also|and|then|after that|aur|phir)\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE)

    /** Verbs that indicate a new imperative/query clause after a separator. */
    private val CLAUSE_ACTION_START = Regex(
        "^(set|turn|switch|put|open|launch|start|run|send|reply|text|message|call|dial|play|pause|resume|skip|" +
            "forward|rewind|check|read|tell|show|find|search|compare|make|create|add|delete|remove|clear|" +
            "dim|mute|unmute|enable|disable|lock|take|navigate|remind|calculate|compute|answer|decline|" +
            "kholo|lagao|karo|batao|sunao|parho|bhejo|roko|chalao)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val PAYLOAD_PREFIXES = Regex(
        "^(note|remember|remind me|say|tell|text|message|whatsapp|send|reply|add)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun decide(
        text: String,
        source: Source,
        hasAttachments: Boolean,
        suggested: Boolean,
        hasActiveDialogFollowUp: Boolean,
        hasAiBackend: Boolean,
        parseContext: ParseContext = ParseContext(),
    ): LocalRouteDecision {
        if (hasAttachments) return LocalRouteDecision.EscalateToAi("attachments require AI")
        if (suggested) return LocalRouteDecision.EscalateToAi("screen suggestion requires AI")
        if (source == Source.ROUTINE) return LocalRouteDecision.EscalateToAi("routines run via AI")

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LocalRouteDecision.EscalateToAi("empty input")

        if (hasActiveDialogFollowUp && !hasComplexCue(trimmed) && !looksCompound(trimmed, parseContext)) {
            val followCmd = CommandParser.parse(trimmed, parseContext).firstOrNull()
            return LocalRouteDecision.RunLocal(followCmd, isFollowUp = true)
        }

        if (hasComplexCue(trimmed)) return LocalRouteDecision.EscalateToAi("complex or generative cue")
        if (looksCompound(trimmed, parseContext)) return LocalRouteDecision.EscalateToAi("multi-step compound command")

        parseNumericCall(trimmed)?.let { return LocalRouteDecision.RunLocal(it) }

        val cmd = CommandParser.parse(trimmed, parseContext).firstOrNull()
            ?: return LocalRouteDecision.EscalateToAi("no local match")

        if (cmd.intent == "llm.summarize") return LocalRouteDecision.EscalateToAi("generative summary")

        // When AI is available, require a clean grammar match (>= 0.80); when offline/signed out, also accept KeywordFallback (>= 0.60).
        val accepted = if (hasAiBackend) {
            cmd.source == "grammar" && cmd.score >= MIN_ONLINE_SCORE
        } else {
            cmd.score >= CommandParser.ESCALATE_BELOW
        }
        if (!accepted) return LocalRouteDecision.EscalateToAi("low confidence (${cmd.source}:${cmd.score})")

        if (cmd.intent == "media.play_search" && hasComplexSearchModifiers(trimmed)) {
            return LocalRouteDecision.EscalateToAi("complex media search")
        }

        return LocalRouteDecision.RunLocal(cmd)
    }

    internal fun hasComplexCue(text: String): Boolean =
        COMPLEX_AI_PATTERNS.any { it.containsMatchIn(text) }

    internal fun hasComplexSearchModifiers(text: String): Boolean =
        Regex("\\b(tutorial|official|under|over|best|top|latest|review|comparison|how to)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * Detects multi-action compound commands (e.g., "turn on dark mode and set an alarm for 6:30")
     * while preserving single-intent payloads (e.g., "remind me to buy milk and eggs").
     */
    internal fun looksCompound(text: String, ctx: ParseContext): Boolean {
        val m = COMPOUND_SEPARATORS.find(text) ?: return false
        val after = text.substring(m.range.last + 1).trim()
        if (after.isEmpty()) return false
        val firstWordStartsAction = CLAUSE_ACTION_START.containsMatchIn(after)
        if (!firstWordStartsAction) return false
        // If the prefix is a free-text payload intent (note/remind/message), only escalate if the tail itself parses cleanly as a separate command.
        if (PAYLOAD_PREFIXES.containsMatchIn(text)) {
            val tailParse = CommandParser.parse(after, ctx).firstOrNull()
            return tailParse != null && tailParse.source == "grammar" && tailParse.score >= MIN_ONLINE_SCORE
        }
        return true
    }

    internal fun parseNumericCall(text: String): ParsedCommand? {
        val m = NUMERIC_CALL_REGEX.matchEntire(text.trim()) ?: return null
        val number = m.groupValues[1].trim()
        return ParsedCommand(
            intent = "call.contact",
            slots = mapOf("contact" to com.personal.agent.core.SlotValue.Contact(number, false, null)),
            score = 0.95,
            transcript = text,
            source = "grammar",
            pattern = "call {number}",
        )
    }

    private val NUMERIC_CALL_REGEX = Regex("^(?:call|dial|phone|ring)\\s+([+\\d][\\d\\s()-]{2,})$", RegexOption.IGNORE_CASE)

    const val MIN_ONLINE_SCORE = 0.80
}
