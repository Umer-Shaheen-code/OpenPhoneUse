package com.personal.agent.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy VAD with an adaptive noise floor + intent-aware endpointing (H2).
 * Feed 16 kHz PCM16 frames (any size; processed in 20 ms blocks). Pure JVM → unit-tested.
 */
class Endpointer(
    private val sampleRate: Int = 16_000,
    private val noSpeechTimeoutMs: Long = 5_000,
    private val maxUtteranceMs: Long = 15_000,
) {
    enum class Hint { CLOSED_COMMAND, OPEN_TEXT, DEFAULT }
    enum class Event { NONE, SPEECH_START, SPEECH_END, NO_SPEECH, MAX_LENGTH }

    private val block = sampleRate / 50 // 20 ms
    private var carry = ShortArray(0)
    private var noiseDb = -60.0
    private var elapsedMs = 0L
    private var speechMs = 0L
    private var silenceMs = 0L
    private var speechFrames = 0
    var inSpeech = false; private set
    var speechStartMs = -1L; private set
    var speechEndMs = -1L; private set
    var hint = Hint.DEFAULT
    var dictation = false
    private var ended = false

    val trailingSilenceMs: Long get() = when (hint) {
        Hint.CLOSED_COMMAND -> 350
        Hint.OPEN_TEXT -> 1_000
        Hint.DEFAULT -> 650
    }

    fun reset() {
        carry = ShortArray(0); noiseDb = -60.0; elapsedMs = 0; speechMs = 0; silenceMs = 0; speechFrames = 0
        inSpeech = false; speechStartMs = -1; speechEndMs = -1; hint = Hint.DEFAULT; dictation = false; ended = false
    }

    fun feed(pcm: ShortArray, len: Int = pcm.size): Event {
        if (ended) return Event.NONE
        val data = if (carry.isEmpty()) pcm.copyOf(len) else carry + pcm.copyOf(len)
        var off = 0
        var result = Event.NONE
        while (off + block <= data.size) {
            val e = processBlock(data, off)
            if (e != Event.NONE && result == Event.NONE) result = e
            off += block
            if (ended) break
        }
        carry = data.copyOfRange(off, data.size)
        return result
    }

    private fun processBlock(d: ShortArray, off: Int): Event {
        var sum = 0.0
        var zc = 0
        var diffSum = 0.0
        for (i in off until off + block) {
            val v = d[i].toDouble()
            sum += v * v
            if (i > off) {
                if ((d[i] >= 0) != (d[i - 1] >= 0)) zc++
                val dv = v - d[i - 1].toDouble()
                diffSum += dv * dv
            }
        }
        val rms = sqrt(sum / block)
        val db = 20 * log10(max(rms, 1.0) / 32768.0)
        // Speech-band activity check: reject pure DC/ultra-low rumble while keeping voiced vowels & fricatives.
        val zcr = zc.toDouble() / block
        val spectralRatio = if (sum > 1.0) diffSum / sum else 0.0
        val speechLike = zcr in 0.015..0.65 || spectralRatio > 0.01
        elapsedMs += 20
        val threshold = max(noiseDb + 12.0, -48.0)
        val voiced = db > threshold && speechLike
        if (!inSpeech) {
            // track noise floor quickly downward, slowly upward
            noiseDb = if (db < noiseDb) noiseDb * 0.7 + db * 0.3 else noiseDb * 0.98 + db * 0.02
            if (voiced) speechFrames++ else speechFrames = 0
            if (speechFrames >= 4) {
                inSpeech = true; speechStartMs = elapsedMs - 80; silenceMs = 0
                return Event.SPEECH_START
            }
            if (speechStartMs < 0 && elapsedMs >= noSpeechTimeoutMs) { ended = true; return Event.NO_SPEECH }
            return Event.NONE
        }
        speechMs += 20
        if (voiced) silenceMs = 0 else silenceMs += 20
        val cap = if (dictation) 30_000 else maxUtteranceMs
        if (elapsedMs - speechStartMs >= cap) { ended = true; speechEndMs = elapsedMs; return Event.MAX_LENGTH }
        if (silenceMs >= trailingSilenceMs) { ended = true; speechEndMs = elapsedMs - silenceMs; return Event.SPEECH_END }
        return Event.NONE
    }

    /** Choose the trailing-silence hint from the current partial transcript. */
    fun updateHintFromPartial(partial: String, ctx: ParseContext) {
        val best = CommandParser.parse(partial, ctx).firstOrNull()
        hint = when {
            best == null -> Hint.DEFAULT
            best.intent in OPEN_TEXT_INTENTS && best.slots.values.none { it is SlotValue.Text } -> Hint.OPEN_TEXT
            best.intent in OPEN_TEXT_INTENTS -> Hint.OPEN_TEXT
            best.score >= 0.9 -> Hint.CLOSED_COMMAND
            else -> Hint.DEFAULT
        }
        dictation = best?.intent in OPEN_TEXT_INTENTS
    }

    companion object {
        val OPEN_TEXT_INTENTS = setOf("msg.send", "note.add", "reminder.set", "list.add", "media.play_search", "nav.go")
    }
}
