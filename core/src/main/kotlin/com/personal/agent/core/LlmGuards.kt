package com.personal.agent.core

/**
 * Code-level H11 safety guards for LLM-originated tool calls:
 * 1. An outbound recipient must appear in the user's own utterance or active dialog context
 *    (prevents prompt-injection inside <untrusted> notifications from redirecting messages).
 * 2. At most one outbound message/call per LLM turn.
 */
object LlmGuards {
    private val PRONOUNS = setOf("him", "her", "them")

    fun isRecipientAllowed(
        recipient: String,
        userUtterance: String,
        contextNames: Set<String> = emptySet(),
    ): Boolean {
        val normRecip = Normalizer.normalize(recipient).tokens
        if (normRecip.isEmpty()) return false
        if (normRecip.size == 1 && normRecip[0].norm in PRONOUNS) {
            return contextNames.isNotEmpty()
        }
        val recipForms = ContactMatcher.keyForms(recipient)

        // Check dialog context names (e.g. active conversation or lastContactName)
        for (ctxName in contextNames) {
            if (ContactMatcher.similarity(recipForms, ContactMatcher.keyForms(ctxName)) >= 0.85) return true
        }

        // Check n-grams (1..4 tokens) of the user's own utterance
        val utterTokens = Normalizer.normalize(userUtterance).tokens
        if (utterTokens.isEmpty()) return false
        val maxN = minOf(4, utterTokens.size)
        for (i in utterTokens.indices) {
            for (len in 1..maxN) {
                if (i + len > utterTokens.size) break
                val span = utterTokens.subList(i, i + len).joinToString(" ") { it.norm }
                if (ContactMatcher.similarity(recipForms, ContactMatcher.keyForms(span)) >= 0.85) return true
            }
        }
        return false
    }

    class OutboundTurnLimiter(private val maxPerTurn: Int = 1) {
        private var count = 0
        fun tryAcquire(): Boolean {
            if (count >= maxPerTurn) return false
            count++
            return true
        }
    }
}
