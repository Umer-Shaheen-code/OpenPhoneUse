package com.personal.agent.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusTest {
    private val contacts = ContactMatcher(
        listOf(
            ContactCandidate("wa:abdullah", "Abdullah Khan", ContactSource.LIVE_CONVERSATION, app = "whatsapp", lastMessage = "Where are you?"),
            ContactCandidate("c:abdullah-office", "Abdullah Office", ContactSource.CONTACTS),
            ContactCandidate("c:mom", "Mom", ContactSource.CONTACTS, aliases = listOf("Ammi")),
            ContactCandidate("c:ali", "Ali Raza", ContactSource.CONTACTS),
            ContactCandidate("c:sara", "Sara Ahmed", ContactSource.STARRED),
            ContactCandidate("g:amazon", "Amazon", ContactSource.LIVE_CONVERSATION, app = "gmail"),
            ContactCandidate("wa:family", "Family", ContactSource.LIVE_CONVERSATION, app = "whatsapp", isGroup = true),
        )
    )
    private val ctx = ParseContext(contacts = contacts, mediaActive = true)

    private fun render(v: SlotValue): String = when (v) {
        is SlotValue.Text -> v.text
        is SlotValue.Contact -> v.spoken
        is SlotValue.Duration -> v.ms.toString()
        is SlotValue.Position -> v.ms.toString()
        is SlotValue.Time -> "%d:%02d".format(v.tod.hour, v.tod.minute)
        is SlotValue.Percent -> v.value.toString()
        is SlotValue.App -> v.id
        is SlotValue.Number -> v.value.toString()
        is SlotValue.Speed -> v.value.toString()
        is SlotValue.Words -> v.text
        is SlotValue.Date -> v.date.toString()
        is SlotValue.Expr -> v.result.spoken
        is SlotValue.Enum -> v.value
        is SlotValue.Ordinal -> v.index.toString()
    }

    @Test
    fun corpus() {
        val lines = javaClass.getResource("/corpus.tsv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val failures = ArrayList<String>()
        val started = System.nanoTime()
        for (line in lines) {
            val cols = line.split('\t')
            val utter = cols[0]; val intent = cols[1]
            val expected = cols.getOrNull(2)?.split(';')?.filter { it.isNotBlank() }?.associate { kv -> kv.substringBefore('=') to kv.substringAfter('=') } ?: emptyMap()
            val res = CommandParser.parse(utter, ctx)
            val top = res.firstOrNull()
            if (top == null || top.intent != intent) {
                failures += "INTENT  '$utter' → ${top?.intent}(${"%.2f".format(top?.score ?: 0.0)}) expected $intent; alts=${res.take(3).map { it.intent + ":" + "%.2f".format(it.score) }}"
                continue
            }
            for ((k, v) in expected) {
                val actual = top.slots[k]?.let(::render)
                if (!actual.equals(v, ignoreCase = true)) failures += "SLOT    '$utter' $k='$actual' expected '$v'  (all=${top.slots.mapValues { render(it.value) }})"
            }
        }
        val ms = (System.nanoTime() - started) / 1e6
        val acc = 1.0 - failures.size.toDouble() / lines.size
        println("corpus: ${lines.size} utterances, ${failures.size} failures, accuracy=${"%.3f".format(acc)}, avg ${"%.2f".format(ms / lines.size)} ms/utterance")
        failures.forEach(::println)
        assertTrue("corpus accuracy ${"%.3f".format(acc)} below 0.97", acc >= 0.97)
    }

    @Test
    fun abdullahResolvesToLiveConversation() {
        val r = contacts.resolve("Abdullah", "whatsapp")
        assertTrue(r.decided)
        assertTrue(r.best!!.candidate.id == "wa:abdullah")
    }

    @Test
    fun misheardNameStillMatches() {
        val r = contacts.resolve("Abdulla")
        assertTrue("best=${r.best}", r.best?.candidate?.id == "wa:abdullah")
    }

    @Test
    fun nbestPrefersHypothesisWithRealContact() {
        val best = CommandParser.rank(listOf("tell of the law I'm on the way", "tell Abdullah I'm on the way"), ctx)!!
        val c = best.slots["contact"] as SlotValue.Contact
        assertTrue(c.spoken, c.spoken == "Abdullah")
    }
}
