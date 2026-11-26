package com.phoneuse.local

import com.personal.agent.core.ContactCandidate
import com.personal.agent.core.ContactMatcher
import com.personal.agent.core.ContactSource
import com.personal.agent.core.ParseContext
import com.phoneuse.app.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEscalationPolicyTest {
    private val ctx = ParseContext(
        contacts = ContactMatcher(
            listOf(ContactCandidate("conv:wa:1", "Abdullah", ContactSource.LIVE_CONVERSATION, app = "whatsapp")),
        ),
        mediaActive = true,
    )

    private fun decide(
        text: String,
        source: Source = Source.APP,
        hasAttachments: Boolean = false,
        suggested: Boolean = false,
        followUp: Boolean = false,
        hasAi: Boolean = true,
    ): LocalRouteDecision = LocalEscalationPolicy.decide(
        text = text,
        source = source,
        hasAttachments = hasAttachments,
        suggested = suggested,
        hasActiveDialogFollowUp = followUp,
        hasAiBackend = hasAi,
        parseContext = ctx,
    )

    @Test fun simpleEverydayTasksStayLocalWithoutAi() {
        val cases = listOf(
            "what time is it" to "time.now",
            "aaj ki tareekh kya hai" to "date.today",
            "18% of 2450" to "calc",
            "turn on the flashlight" to "torch.set",
            "set volume to 40%" to "volume.set",
            "pause the video" to "media.pause",
            "forward 3 minutes" to "media.seek_relative",
            "set an alarm for 6:30 am" to "alarm.set",
            "remind me in 10 minutes to drink water" to "reminder.set",
            "add milk to my shopping list" to "list.add",
            "read my WhatsApp messages" to "notif.read",
            "reply I am on the way to Abdullah" to "msg.send",
            "Open Google" to "app.open",
            "Open Google Translate" to "app.open",
            "launch Wikipedia" to "app.open",
            "Open call" to "app.open",
            "call 03001234567" to "call.contact",
        )
        for ((text, expectedIntent) in cases) {
            val d = decide(text)
            assertTrue("Expected RunLocal for '$text', got $d", d is LocalRouteDecision.RunLocal)
            assertEquals("Wrong intent for '$text'", expectedIntent, (d as LocalRouteDecision.RunLocal).cmd?.intent)
        }
    }

    @Test fun compoundMultiActionTasksEscalateToAi() {
        val compounds = listOf(
            "turn on dark mode and set an alarm for 6:30 AM",
            "turn on flashlight and set volume to 50%",
            "open YouTube and search for a tie tutorial",
            "check the weather and then remind me to carry an umbrella",
            "volume 40% aur alarm lagao 7 baje ka",
        )
        for (text in compounds) {
            val d = decide(text)
            assertTrue("Expected EscalateToAi for compound '$text', got $d", d is LocalRouteDecision.EscalateToAi)
        }
    }

    @Test fun singlePayloadWithAndStaysLocal() {
        val payloads = listOf(
            "remind me to buy milk and eggs",
            "note that keys are on the table and door is locked",
            "tell Abdullah I am late and stuck in traffic",
        )
        for (text in payloads) {
            val d = decide(text)
            assertTrue("Expected RunLocal for single-payload '$text', got $d", d is LocalRouteDecision.RunLocal)
        }
    }

    @Test fun generativeAndComplexTasksEscalateToAi() {
        val complex = listOf(
            "summarize my WhatsApp messages",
            "reply to Abdullah politely that I will be 10 minutes late",
            "compare the price of AirPods Pro at Amazon and Walmart",
            "tap the checkout button on screen",
            "if tomorrow chance of rain is over 30% set a 7:00 alarm otherwise 7:30",
            "find a tie-tying tutorial under 5 minutes on YouTube",
            "play tic tac toe on the hardest level",
            "google best phones 2026",
            "translate hello to Urdu",
            "open the English Wikipedia list of tallest buildings",
        )
        for (text in complex) {
            val d = decide(text)
            assertTrue("Expected EscalateToAi for complex '$text', got $d", d is LocalRouteDecision.EscalateToAi)
        }
    }

    @Test fun attachmentsSuggestionsAndRoutinesEscalateToAi() {
        assertTrue(decide("what time is it", hasAttachments = true) is LocalRouteDecision.EscalateToAi)
        assertTrue(decide("what time is it", suggested = true) is LocalRouteDecision.EscalateToAi)
        assertTrue(decide("what time is it", source = Source.ROUTINE) is LocalRouteDecision.EscalateToAi)
    }

    @Test fun keywordFallbackWorksOfflineWhenNoAiConfigured() {
        // Low-confidence keyword phrase ("timer 5 minutes" -> KeywordFallback ~0.75)
        val offline = decide("timer 5 minutes please check", hasAi = false)
        assertTrue("Expected offline fallback RunLocal, got $offline", offline is LocalRouteDecision.RunLocal)
    }

    @Test fun activeDialogFollowUpRoutesLocally() {
        val d = decide("yes", followUp = true)
        assertTrue(d is LocalRouteDecision.RunLocal)
        assertTrue((d as LocalRouteDecision.RunLocal).isFollowUp)
    }
}
