package com.phoneuse.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Deepgram for voice mode: Nova-3 live speech-to-text over a WebSocket ([Listener]) and Flux text-to-speech
 * ([speak]). Used instead of Android's recognizer and TTS once a Deepgram key is saved in Settings.
 */
object Deepgram {
    const val SECRET = "deepgram_api_key"

    /** The key built into the app (see app/build.gradle.kts); blank = none, so users save their own in Settings. */
    val builtIn: Boolean get() = com.phoneuse.BuildConfig.DEEPGRAM_KEY.isNotBlank()

    /** The key in use: the built-in one, else the one saved in Settings; null or blank = not set up. */
    @Volatile
    var key: String? = com.phoneuse.BuildConfig.DEEPGRAM_KEY.ifBlank { null }

    val enabled: Boolean get() = !key.isNullOrBlank()

    private const val TAG = "Deepgram"
    private const val MIC_RATE = 16_000
    /** Longer than this for a reply's audio and it is read in the system voice instead, so voice mode never stalls. */
    private const val SPEAK_TIMEOUT_S = 6L

    /**
     * The Flux voice (/v2/speak) for a BCP-47 [languageTag], or null when the app has none for it (the system voice
     * reads then). Urdu and Korean are Deepgram Early Access: numbers and dates may not be read out well.
     * shortcut: only the app's voice languages have a voice; add more from Deepgram's Flux voice list when needed.
     */
    fun ttsModel(languageTag: String): String? = when (languageTag.substringBefore('-').lowercase()) {
        "en" -> "flux-hannah-en"
        "ur" -> "flux-usman-ur"
        "hi" -> "flux-ajay-hi"
        "ko" -> "flux-eunji-ko"
        else -> null
    }

    /** Deepgram's per-request text limit is 2000 characters. */
    private const val TTS_MAX_CHARS = 1_800

    private val json = Json { ignoreUnknownKeys = true }

    /** Deepgram's language code: English keeps its region (en-US), other languages use the bare code (ur, hi, ko). */
    fun languageCode(tag: String): String = if (tag.startsWith("en")) tag else tag.substringBefore('-')

    fun listenUrl(languageTag: String): String =
        "wss://api.deepgram.com/v1/listen?model=nova-3&language=${languageCode(languageTag)}" +
            "&encoding=linear16&sample_rate=$MIC_RATE&channels=1&interim_results=true&smart_format=true" +
            // endpointing: silence (ms) that ends a phrase; utterance_end_ms: a backstop when words keep trickling in.
            "&endpointing=1500&utterance_end_ms=2000&vad_events=true"

    /** One server message, reduced to what the mic UI needs. */
    sealed interface Message {
        data class Transcript(val text: String, val isFinal: Boolean, val speechFinal: Boolean) : Message
        data object UtteranceEnd : Message
        data object SpeechStarted : Message
        data object Other : Message
    }

    fun parse(text: String): Message {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Message.Other
        return when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "Results" -> {
                val alt = o["channel"]?.jsonObject?.get("alternatives")?.jsonArray?.firstOrNull() as? JsonObject
                Message.Transcript(
                    alt?.get("transcript")?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                    isFinal = o["is_final"]?.jsonPrimitive?.booleanOrNull == true,
                    speechFinal = o["speech_final"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            "UtteranceEnd" -> Message.UtteranceEnd
            "SpeechStarted" -> Message.SpeechStarted
            else -> Message.Other
        }
    }

    /** Joins Deepgram's finalized segments and the segment still being revised into the text shown so far. */
    class Transcript {
        private val done = StringBuilder()
        private var interim = ""

        val text: String get() = listOf(done.toString(), interim).filter { it.isNotBlank() }.joinToString(" ").trim()

        fun add(m: Message.Transcript) {
            if (m.isFinal) {
                if (m.text.isNotBlank()) {
                    if (done.isNotEmpty()) done.append(' ')
                    done.append(m.text)
                }
                interim = ""
            } else {
                interim = m.text
            }
        }
    }

    interface Callbacks {
        fun onReady()
        /** Loudness on SpeechRecognizer's scale (about -2..10 dB), so [rmsLevel] maps it the same way. */
        fun onLevel(db: Float)
        fun onPartial(text: String)
        /** The user stopped talking; the full text follows in [onFinal]. */
        fun onFinal(text: String)
        fun onError(error: VoiceError)
    }

    /**
     * One listening session: records 16 kHz mono PCM and streams it to Deepgram; every callback arrives on the
     * main thread. [stop] keeps what was said, [cancel] drops it.
     */
    class Listener(
        private val http: OkHttpClient,
        private val apiKey: String,
        private val languageTag: String,
        private val cb: Callbacks,
    ) {
        private val main = Handler(Looper.getMainLooper())
        private val transcript = Transcript()
        @Volatile private var socket: WebSocket? = null
        @Volatile private var recording = false
        @Volatile private var over = false
        private var mic: Thread? = null

        @SuppressLint("MissingPermission") // VoiceInput asks for RECORD_AUDIO before any start.
        fun start() {
            val request = Request.Builder().url(listenUrl(languageTag)).header("Authorization", "Token $apiKey").build()
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    main.post { if (!over) { cb.onReady(); startMic(webSocket) } }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = parse(text)
                    main.post { if (!over) handle(m) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    main.post { if (!over) finishWith(transcript.text) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Our own cancel ends the socket with "Socket closed"; only a failure while listening is news.
                    if (!over) Log.w(TAG, "listen failed (${response?.code})", t)
                    val error = when (response?.code) {
                        401, 402, 403 -> VoiceError.KEY
                        400 -> VoiceError.LANGUAGE
                        null -> VoiceError.NETWORK
                        else -> VoiceError.UNAVAILABLE
                    }
                    main.post {
                        if (over) return@post
                        // A dropped connection after speech still has the words heard so far.
                        if (transcript.text.isNotBlank()) finishWith(transcript.text) else fail(error)
                    }
                }
            })
        }

        private fun handle(m: Message) {
            when (m) {
                is Message.Transcript -> {
                    transcript.add(m)
                    val text = transcript.text
                    if (text.isNotBlank()) cb.onPartial(text)
                    if (m.speechFinal && text.isNotBlank()) finishWith(text)
                }
                Message.UtteranceEnd -> transcript.text.takeIf { it.isNotBlank() }?.let(::finishWith)
                Message.SpeechStarted, Message.Other -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        private fun startMic(ws: WebSocket) {
            val min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = MIC_RATE / 10 * 2 // 100 ms of 16-bit samples
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
            } catch (e: RuntimeException) {
                Log.w(TAG, "microphone unavailable", e)
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                fail(VoiceError.AUDIO)
                return
            }
            recording = true
            mic = Thread({
                val buf = ByteArray(chunk)
                try {
                    record.startRecording()
                    while (recording) {
                        val n = record.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        ws.send(buf.toByteString(0, n))
                        val db = levelDb(buf, n)
                        main.post { if (!over) cb.onLevel(db) }
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "recording failed", e)
                    main.post { if (!over) fail(VoiceError.AUDIO) }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            }, "deepgram-mic").apply { start() }
        }

        /** Done talking: stop the mic and let Deepgram send what it still holds, then close. */
        fun stop() {
            recording = false
            socket?.send("""{"type":"CloseStream"}""")
        }

        fun cancel() {
            over = true
            recording = false
            socket?.cancel()
            socket = null
        }

        private fun finishWith(text: String) {
            if (over) return
            cancel()
            if (text.isBlank()) cb.onError(VoiceError.NO_MATCH) else cb.onFinal(text)
        }

        private fun fail(error: VoiceError) {
            if (over) return
            cancel()
            cb.onError(error)
        }
    }

    /** RMS of 16-bit little-endian PCM in dBFS (-60..0), moved onto SpeechRecognizer's -2..10 range. */
    fun levelDb(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            count++
            i += 2
        }
        if (count == 0) return -2f
        val rms = sqrt(sum / count)
        val dbfs = if (rms < 1) -60.0 else (20 * log10(rms / 32768.0)).coerceAtLeast(-60.0)
        return ((dbfs + 60) / 5 - 2).toFloat()
    }

    /** Reading pieces, split at sentence ends (or spaces) so each request stays under Deepgram's limit. */
    fun chunks(text: String, max: Int = TTS_MAX_CHARS): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "), window.lastIndexOf('\n'))
                .takeIf { it > max / 2 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Speaking now: its call, its player and the wait for its end, so [stopSpeaking] can end it at once. */
    private class Playback {
        @Volatile var stopped = false
        @Volatile var call: Call? = null
        @Volatile var player: MediaPlayer? = null
        @Volatile var ended: CountDownLatch? = null
    }

    @Volatile
    private var playback: Playback? = null

    /**
     * Reads [text] in the Flux voice [model] (see [ttsModel]) on a background thread. [onPlaying] runs on the main thread when sound starts;
     * [onDone] with true when it was read to the end, false when it failed (the caller can fall back); neither after
     * [stopSpeaking].
     */
    fun speak(http: OkHttpClient, apiKey: String, text: String, model: String, onPlaying: () -> Unit, onDone: (Boolean) -> Unit) {
        stopSpeaking()
        val p = Playback()
        playback = p
        val main = Handler(Looper.getMainLooper())
        Thread({
            val ok = try {
                play(http, apiKey, text, model, p) { main.post { if (!p.stopped) onPlaying() } }
                true
            } catch (e: Exception) {
                if (!p.stopped) Log.w(TAG, "speak failed", e)
                false
            }
            main.post { if (!p.stopped) onDone(ok) }
        }, "deepgram-tts").start()
    }

    fun stopSpeaking() {
        val p = playback ?: return
        playback = null
        p.stopped = true
        p.call?.cancel()
        runCatching { p.player?.stop() }
        p.ended?.countDown()
    }

    private fun play(http: OkHttpClient, apiKey: String, text: String, model: String, p: Playback, onPlaying: () -> Unit) {
        val client = http.newBuilder().callTimeout(SPEAK_TIMEOUT_S, TimeUnit.SECONDS).build()
        var first = true
        for (part in chunks(text)) {
            if (p.stopped) return
            val mp3 = fetch(client, apiKey, part, model, p)
            if (p.stopped) return
            playMp3(mp3, p) {
                if (first) onPlaying()
                first = false
            }
        }
    }

    /**
     * One piece as MP3. Compressed (48 kbps) rather than raw PCM (384 kbps): on a busy connection (a video app
     * loading next to us) raw audio arrived slower than it played and stuttered, while this arrives whole in a moment.
     */
    private fun fetch(client: OkHttpClient, apiKey: String, part: String, model: String, p: Playback): ByteArray {
        val url = "https://api.deepgram.com/v2/speak?model=$model&encoding=mp3&bit_rate=48000"
        val body = buildJsonObject { put("text", part) }.toString().toRequestBody("application/json".toMediaType())
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Token $apiKey").post(body).build())
        p.call = call
        val asked = SystemClock.elapsedRealtime()
        val bytes = call.execute().use { response ->
            if (!response.isSuccessful) error("speak HTTP ${response.code}")
            response.body.bytes()
        }
        Log.i(TAG, "speak: ${part.length} chars -> ${bytes.size / 1024} KB in ${SystemClock.elapsedRealtime() - asked} ms")
        return bytes
    }

    private fun playMp3(bytes: ByteArray, p: Playback, onStart: () -> Unit) {
        val ended = CountDownLatch(1)
        var failed = false
        val player = MediaPlayer()
        p.player = player
        p.ended = ended
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(BytesSource(bytes))
            player.setOnCompletionListener { ended.countDown() }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "player error $what/$extra")
                failed = true
                ended.countDown()
                true
            }
            player.prepare()
            if (p.stopped) return
            player.start()
            onStart()
            // Capped in case a device never reports the end.
            ended.await(player.duration.coerceAtLeast(0) + 3_000L, TimeUnit.MILLISECONDS)
            if (failed && !p.stopped) error("player failed")
        } finally {
            p.player = null
            player.release()
        }
    }

    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }
}
