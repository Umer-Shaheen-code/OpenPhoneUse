package com.phoneuse.ui.fold

import com.phoneuse.i18n.tr
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.phoneuse.cards.CardAction
import com.phoneuse.chat.StepLog
import com.phoneuse.ui.cards.CardButton
import com.phoneuse.ui.cards.CardStage
import com.phoneuse.ui.cards.LocalMovedCardButtons
import com.phoneuse.ui.cards.LocalCardStage
import com.phoneuse.ui.cards.ResultCard
import com.phoneuse.ui.chat.AgentDot
import com.phoneuse.ui.chat.Composer
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.voice.VoiceCaption
import com.phoneuse.ui.voice.VoiceOrb
import com.phoneuse.ui.voice.rememberDraftVoiceInput

/** The device posture from Jetpack WindowManager; FLAT where there is no hinge. */
@Composable
fun rememberPosture(): Posture {
    val activity = LocalActivity.current ?: return Posture.FLAT
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(null)
    return postureOf(info)
}

private fun postureOf(info: WindowLayoutInfo?): Posture {
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return Posture.FLAT
    return FoldLayoutPolicy.posture(
        halfOpened = fold.state == FoldingFeature.State.HALF_OPENED,
        horizontalHinge = fold.orientation == FoldingFeature.Orientation.HORIZONTAL,
    )
}

/** Whether the app shares the screen with another app (split screen, pop-up view); re-read on every configuration change. */
@Composable
fun rememberMultiWindow(): Boolean {
    val activity = LocalActivity.current ?: return false
    val config = LocalConfiguration.current
    return remember(activity, config) { activity.isInMultiWindowMode }
}

/** Board Unfolded / UnfoldedPhotos: the conversation's last card, large, on the right of the chat. */
@Composable
fun ResultPane(pane: PaneCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.background(pu.surface).semantics { paneTitle = tr("Result", "결과") }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(20.dp),
        ) {
            CompositionLocalProvider(LocalCardStage provides CardStage.PANE) {
                ResultCard(pane.card, onAction, stateKey = pane.key)
            }
        }
    }
}

/**
 * Board UnfoldedFlex (tabletop): the card on the top half, its buttons and a big voice button on the bottom half.
 * [reply]: the newest reply (or the live answer), shown above the card so a spoken request that got a text
 * answer or an error still shows it; [outdated] marks the card as an earlier result.
 * Speech fills the shared composer. It stays available while the agent works, along with its stop button.
 */
@Composable
fun FlexScreen(
    pane: PaneCard,
    reply: String?,
    outdated: Boolean,
    onAction: (CardAction) -> Unit,
    runningHere: Boolean,
    liveSteps: List<StepLog>,
    conversationId: Long?,
    draft: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    onAppendSpeech: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val card = pane.card
    val buttons = remember(card) { FoldLayoutPolicy.flexButtons(card) }
    Column(modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            reply?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    // Announced once the answer is complete, not on every streamed token.
                    Modifier.fillMaxWidth().then(
                        if (runningHere) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = c.text,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (outdated) Text(tr("Previous result", "이전 결과"), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
            CompositionLocalProvider(
                LocalCardStage provides CardStage.PANE,
                LocalMovedCardButtons provides FoldLayoutPolicy.movedButtons(card),
            ) {
                ResultCard(card, onAction, stateKey = pane.key)
            }
        }
        // The hinge.
        Box(Modifier.fillMaxWidth().height(8.dp).background(c.line))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(c.lineSoft)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (buttons.isNotEmpty()) {
                // Scrolls when an event has many conflicts, so the voice button keeps its room.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (row in buttons.chunked(FLEX_COLUMNS)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (b in row) CardButton(b.label, { onAction(b.action) }, Modifier.weight(1f), primary = b.primary, height = 56.dp)
                            repeat(FLEX_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (runningHere || draft.text.isNotEmpty()) {
                if (runningHere) Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentDot()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        liveSteps.lastOrNull()?.text ?: tr("Working", "작업 중"),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        fontSize = 15.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Composer(
                    conversationId = conversationId, value = draft, onValueChange = onInput,
                    onAppendSpeech = onAppendSpeech,
                    placeholder = tr("Message or task", "메시지 또는 할 일"),
                    running = runningHere, showMic = true, onSend = onSend, onStop = onStop,
                    focusRequester = remember { FocusRequester() },
                )
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                ) {
                    val voice = key(conversationId) {
                        rememberDraftVoiceInput(onAppendSpeech)
                    }
                    VoiceOrb(voice.state, voice::toggle)
                    VoiceCaption(
                        voice.state,
                        idleTitle = tr("Tap to speak", "눌러서 말하기"),
                        idleHint = tr("e.g. “Subway only, no buses”", "예: “버스 말고 지하철만”"),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (voice.state.active) IconBtn(PuIcons.Close, tr("Cancel voice input", "음성 입력 취소"), voice::cancel, tint = c.textSub)
                }
            }
        }
    }
}

private const val FLEX_COLUMNS = 3
