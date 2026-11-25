package com.phoneuse.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.phoneuse.voice.Deepgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * "소리 내어 읽기": replies read one at a time (a new one replaces the last), in Deepgram's Flux voice for the language
 * when a key is set and it has one, else (or when Deepgram fails) with the system text-to-speech. The system engine
 * starts on first use; text asked for before it is ready is spoken once it is.
 */
object ReadAloud {
    private const val TAG = "ReadAloud"
    private const val UTTERANCE = "phoneuse-reply"

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Locale>? = null

    /** Each reading's chunks share a number; only the last chunk's end clears [speaking]. */
    private var reading = 0

    @Volatile
    private var lastChunk: String? = null

    /** Voice mode's next step, run once the reading ends by itself (never after [stop]). */
    private var after: (() -> Unit)? = null
    // Lazy: JVM unit tests load this object without an Android main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val _speaking = MutableStateFlow(false)

    /** True while a reply is being read; the chat's menu offers "읽기 멈추기" then. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Reads [text] (Markdown marks dropped) in [language] (a BCP-47 tag; null = the device language). */
    @Synchronized
    fun speak(context: Context, text: String, language: String? = null) {
        if (!viaDeepgram(context, text, language, {}, null)) system(context, text, language)
    }

    /**
     * Starts [text] in Deepgram's voice for [language]; false (nothing started) without a key, a voice for that
     * language or any text. A Deepgram failure falls back to the system voice, then [onDone] as after it.
     */
    private fun viaDeepgram(context: Context, text: String, language: String?, onPlaying: () -> Unit, onDone: (() -> Unit)?): Boolean {
        val key = Deepgram.key
        val plain = plain(text)
        val model = Deepgram.ttsModel(language ?: Locale.getDefault().toLanguageTag())
        if (key.isNullOrBlank() || model == null || plain.isBlank()) return false
        // Any system-voice reading in progress gives way.
        after = null
        pending = null
        tts?.stop()
        _speaking.value = true
        Deepgram.speak(AppGraph.http, key, plain, model, onPlaying) { ok ->
            if (ok) {
                _speaking.value = false
                onDone?.invoke()
            } else {
                onPlaying()
                synchronized(this) {
                    system(context, text, language)
                    after = onDone
                }
            }
        }
        return true
    }

    /** [text] with the system text-to-speech. */
    private fun system(context: Context, text: String, language: String?) {
        after = null
        Deepgram.stopSpeaking()
        val plain = plain(text)
        if (plain.isBlank()) return
        val locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val engine = tts
        if (engine == null) {
            pending = plain to locale
            tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
            return
        }
        if (!ready) {
            pending = plain to locale
            return
        }
        say(engine, plain, locale)
    }

    /**
     * Voice mode: reads [text] in [language] like [speak], then calls [onDone] on the main thread. [onPlaying] fires
     * once sound starts or the system voice takes over. A [stop] meanwhile drops [onDone].
     */
    @Synchronized
    fun speakThen(context: Context, text: String, language: String?, onPlaying: () -> Unit = {}, onDone: () -> Unit) {
        if (viaDeepgram(context, text, language, onPlaying, onDone)) return
        onPlaying()
        system(context, text, language)
        if (plain(text).isBlank()) main.post { onDone() } else after = onDone
    }

    /** Voice mode's "Opening X" is being said; [afterAck] runs once it ends. */
    private var ackPending = false
    private var afterAck: (() -> Unit)? = null

    /** Voice mode: a short line said while an app opens, before the run's reply exists. */
    @Synchronized
    fun speakAck(context: Context, text: String) {
        ackPending = true
        afterAck = null
        // The line itself is English ("Opening X."), so an English voice says it.
        speakThen(context, text, "en-US") { ackFinished() }
    }

    /** Runs [next] on the main thread once the last [speakAck] has been said (at once when it has, or was stopped). */
    @Synchronized
    fun whenAckDone(next: () -> Unit) {
        if (ackPending) afterAck = next else main.post { next() }
    }

    @Synchronized
    private fun ackFinished() {
        ackPending = false
        afterAck?.let { next -> main.post { next() } }
        afterAck = null
    }

    @Synchronized
    fun stop() {
        ackPending = false
        afterAck = null
        after = null
        Deepgram.stopSpeaking()
        pending = null
        lastChunk = null
        tts?.stop()
        _speaking.value = false
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "text-to-speech unavailable ($status)")
            engine.shutdown()
            tts = null
            pending = null
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastChunk) ended()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == lastChunk) ended()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == lastChunk) _speaking.value = false
            }
        })
        pending?.let { (text, locale) -> say(engine, text, locale) }
        pending = null
    }

    @Synchronized
    private fun ended() {
        _speaking.value = false
        after?.let { next -> main.post { next() } }
        after = null
    }

    private fun say(engine: TextToSpeech, text: String, locale: Locale) {
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        // Long answers go in pieces the engine accepts; the first flushes whatever was being read.
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val parts = text.chunked(max)
        val id = ++reading
        lastChunk = "$UTTERANCE-$id-${parts.lastIndex}"
        parts.forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$UTTERANCE-$id-$i")
        }
    }

    /** The reply as it should sound: no Markdown marks, links read by their text. */
    fun plain(markdown: String): String =
        markdown
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""!?\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)"""), "$1")
            .replace(Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s?|[-*+]\s+|\d+\.\s+)"""), "")
            .replace(Regex("""[*_`~|]"""), "")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
}
