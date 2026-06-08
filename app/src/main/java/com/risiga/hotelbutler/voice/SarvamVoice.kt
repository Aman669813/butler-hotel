package com.risiga.hotelbutler.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sarvam voice for the device.
 *   speak()      -> bulbul:v2 / anushka TTS. Splits the text into sentences,
 *                   sends them all, and plays EVERY returned audio in order
 *                   (fixes long greetings getting cut off mid-sentence).
 *   transcribe() -> saarika:v2.5 STT from a WAV file.
 *   chat()       -> sarvam-m conversational reply (OpenAI-style /v1/chat/completions).
 */
class SarvamVoice(private val context: Context, private val apiKey: String, private val openAiKey: String = "") {

    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val base = "https://api.sarvam.ai"
    private val jsonType = "application/json".toMediaType()

    // ---------------- TTS ----------------
    // Voice: change `speaker` to taste. bulbul:v2 voices —
    //   female: anushka, manisha, vidya, arya   |   male: abhilash, karun, hitesh
    // If a speaker name ever returns "TTS 400", set it back to "anushka" (always valid).
    private val speaker = "manisha"   // warm, pleasant concierge voice

    // Cache synthesized audio per (language|text) so repeated lines (the wake reply,
    // confirmations, closings) play instantly instead of re-hitting the network.
    private val ttsCache = java.util.concurrent.ConcurrentHashMap<String, List<File>>()

    /** Latin words Sarvam's Hindi voice mispronounces → Devanagari for clean TTS. */
    private fun prepForTts(text: String, lang: String): String {
        if (lang != "hi-IN") return text
        return text
            .replace("Jehan Numa Palace", "\u091c\u0939\u093e\u0901 \u0928\u0941\u092e\u093e \u092a\u0948\u0932\u0947\u0938")
            .replace("Jehan Numa", "\u091c\u0939\u093e\u0901 \u0928\u0941\u092e\u093e")
    }

    /** Synthesize (or reuse cached) audio chunks for one line. */
    private suspend fun synth(text: String, lang: String): List<File> = withContext(Dispatchers.IO) {
        val key = "$lang|$text"
        ttsCache[key]?.let { fs -> if (fs.all { it.exists() }) return@withContext fs }
        val payload = JSONObject()
            .put("inputs", JSONArray().put(text))
            .put("target_language_code", lang)
            .put("speaker", speaker)
            .put("model", "bulbul:v2")
            .put("pace", 0.95)            // natural, unrushed
            .put("loudness", 1.0)
            .put("enable_preprocessing", true)
            .toString().toRequestBody(jsonType)
        val req = Request.Builder().url("$base/text-to-speech")
            .addHeader("api-subscription-key", apiKey)
            .post(payload).build()
        val files = http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("TTS ${resp.code}: $s")
            val audios = JSONObject(s).getJSONArray("audios")
            (0 until audios.length()).map { i ->
                val bytes = Base64.decode(audios.getString(i), Base64.DEFAULT)
                File(context.cacheDir, "tts_${key.hashCode()}_$i.wav").apply { writeBytes(bytes) }
            }
        }
        ttsCache[key] = files
        files
    }

    suspend fun speak(text: String, languageCode: String) {
        // Synthesize the WHOLE line in ONE pass so intonation flows naturally, then play
        // every chunk Sarvam returns, in order — that is what avoids truncation.
        val prepared = prepForTts(text, languageCode)
        val files = synth(prepared, languageCode)
        withContext(Dispatchers.Main) { for (f in files) playFileAwait(f) }
    }

    /** Pre-synthesize a line (e.g. the wake reply) so the first playback is instant. */
    suspend fun prewarm(text: String, languageCode: String) {
        runCatching { synth(prepForTts(text, languageCode), languageCode) }
    }

    /** Plays one file and suspends until it finishes, so segments play back-to-back. */
    private suspend fun playFileAwait(f: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        try {
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { it.release(); if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
            mp.setOnErrorListener { p, _, _ -> p.release(); if (cont.isActive) cont.resumeWith(Result.success(Unit)); true }
            mp.prepare(); mp.start()
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWith(Result.success(Unit))
        }
        cont.invokeOnCancellation { runCatching { mp.release() } }
    }

    // ---------------- Conversation (sarvam-m) ----------------
    /** messages: list of (role, content) where role is "system" / "user" / "assistant". */
    suspend fun chat(messages: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        messages.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val useOpenAi = openAiKey.isNotBlank() && !openAiKey.startsWith("PASTE")
        val url: String; val model: String
        val reqB = Request.Builder()
        if (useOpenAi) {
            url = "https://api.openai.com/v1/chat/completions"; model = "gpt-4o-mini"
            reqB.addHeader("Authorization", "Bearer $openAiKey")
        } else {
            url = "$base/v1/chat/completions"; model = "sarvam-m"
            reqB.addHeader("api-subscription-key", apiKey)
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.5)
            .put("max_tokens", 300)
            .toString().toRequestBody(jsonType)
        val req = reqB.url(url).post(payload).build()

        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Chat ${resp.code}: $s")
            val content = JSONObject(s).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            // strip any <think> reasoning block, keep the final reply
            content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
    }

    // ---------------- Live weather (WeatherAPI.com) ----------------
    private val weatherKey  = "e482cfb9676c41f2bd753847261605"
    private val weatherBase = "https://api.weatherapi.com/v1/current.json"
    private val weatherCity = "Bhopal"

    /** Real-time Bhopal weather via WeatherAPI.com, then a concierge follow-up about heading out.
     *  WeatherAPI returns a ready-made condition description; lang=hi returns it in Hindi. */
    suspend fun weather(lang: String): String = withContext(Dispatchers.IO) {
        val hi = lang == "hi-IN"
        try {
            val url = "$weatherBase?key=$weatherKey&q=$weatherCity&aqi=no" + if (hi) "&lang=hi" else ""
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("weather ${resp.code}: $s")
                val cur = JSONObject(s).getJSONObject("current")
                val temp = cur.getDouble("temp_c").toInt()
                val feels = cur.optDouble("feelslike_c", cur.getDouble("temp_c")).toInt()
                val cond = cur.getJSONObject("condition").getString("text").trim()
                if (hi)
                    "भोपाल में अभी लगभग ${temp}°C है, $cond (महसूस ${feels}° जैसा)। क्या आप कहीं बाहर जाने या आस-पास घूमने की सोच रहे हैं? मैं अच्छी जगहें बता सकता हूँ या कैब की व्यवस्था कर सकता हूँ।"
                else
                    "It's about ${temp}°C in Bhopal right now with ${cond.lowercase()} (feels like ${feels}°). Are you planning to step out or visit any nearby places? I'd be glad to suggest a few spots or arrange a cab for you."
            }
        } catch (e: Exception) {
            if (hi) "भोपाल का मौसम आमतौर पर सुहावना रहता है। क्या आप कहीं बाहर घूमने जाने की सोच रहे हैं? मैं अच्छी जगहें सुझा सकता हूँ या कैब बुक कर सकता हूँ।"
            else "Bhopal's weather is usually pleasant. Are you planning to step out or visit any nearby places? I'd be glad to suggest a few spots or arrange a cab."
        }
    }


    // ---------------- STT ----------------
    suspend fun transcribe(wav: File, languageCode: String): String = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "saarika:v2.5")
            .addFormDataPart("language_code", languageCode)
            .build()
        val req = Request.Builder().url("$base/speech-to-text")
            .addHeader("api-subscription-key", apiKey)
            .post(multipart).build()
        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("STT ${resp.code}: $s")
            JSONObject(s).optString("transcript", "")
        }
    }

    /** Auto-detects the spoken language so English is transcribed as English (not phonetic
     *  Devanagari) and Hindi as Hindi. Returns (transcript, detectedLanguageCode e.g. "en-IN"). */
    suspend fun transcribeAuto(wav: File): Pair<String, String> = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "saarika:v2.5")
            .addFormDataPart("language_code", "unknown")
            .build()
        val req = Request.Builder().url("$base/speech-to-text")
            .addHeader("api-subscription-key", apiKey)
            .post(multipart).build()
        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("STT ${resp.code}: $s")
            val j = JSONObject(s)
            j.optString("transcript", "") to j.optString("language_code", "")
        }
    }

    // ---------------- Recorder (16k mono PCM16 -> WAV) ----------------
    private val sampleRate = 16000
    private var recorder: AudioRecord? = null
    private var recThread: Thread? = null
    @Volatile private var recording = false
    private var pcmFile: File? = null
    private var wavFile: File? = null

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        val pcm = File(context.cacheDir, "input.pcm")
        recorder = rec; pcmFile = pcm; wavFile = File(context.cacheDir, "input.wav")
        recording = true
        rec.startRecording()
        recThread = Thread {
            pcm.outputStream().use { os ->
                val buf = ByteArray(minBuf)
                while (recording) { val n = rec.read(buf, 0, buf.size); if (n > 0) os.write(buf, 0, n) }
            }
        }.also { it.start() }
    }

    fun stopRecording(): File {
        recording = false
        recThread?.join()
        recorder?.apply { stop(); release() }
        recorder = null
        val pcm = pcmFile!!; val wav = wavFile!!
        writeWavHeader(pcm, wav, sampleRate)
        return wav
    }

    /**
     * Hands-free capture. Records from the mic and stops automatically when the
     * guest stops speaking (simple energy VAD). Returns a WAV file, or null if no
     * speech was detected within [startTimeoutMs] (i.e. the room was silent).
     *  - [silenceMs]: trailing silence that ends an utterance once speech has begun.
     *  - [maxMs]: hard cap so it never records forever.
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(
        maxMs: Long = 12000, silenceMs: Long = 1300, startTimeoutMs: Long = 6000,
        voiceThreshold: Double = 650.0
    ): File? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate))
        val pcm = File(context.cacheDir, "listen.pcm")
        val wav = File(context.cacheDir, "listen.wav")
        val frame = ShortArray(1600)              // 100 ms @ 16 kHz
        val outBytes = ByteArray(frame.size * 2)
        var started = false
        val t0 = System.currentTimeMillis(); var lastVoice = t0
        rec.startRecording()
        try {
            pcm.outputStream().use { os ->
                while (true) {
                    val n = rec.read(frame, 0, frame.size)
                    val now = System.currentTimeMillis()
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) { val s = frame[i].toDouble(); sum += s * s }
                        val rms = Math.sqrt(sum / n)
                        var bi = 0
                        for (i in 0 until n) { val s = frame[i].toInt(); outBytes[bi++] = (s and 0xff).toByte(); outBytes[bi++] = ((s shr 8) and 0xff).toByte() }
                        os.write(outBytes, 0, n * 2)
                        if (rms > voiceThreshold) { started = true; lastVoice = now }
                    }
                    if (!started && now - t0 > startTimeoutMs) break          // nobody spoke
                    if (started && now - lastVoice > silenceMs) break          // utterance ended
                    if (now - t0 > maxMs) break                               // hard cap
                }
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (!started) return@withContext null
        writeWavHeader(pcm, wav, sampleRate)
        wav
    }

    private fun writeWavHeader(pcm: File, wav: File, rate: Int) {
        val data = pcm.readBytes()
        val byteRate = rate * 2
        val h = ByteArray(44)
        fun int32(o: Int, v: Int) { h[o]=v.toByte(); h[o+1]=(v shr 8).toByte(); h[o+2]=(v shr 16).toByte(); h[o+3]=(v shr 24).toByte() }
        fun int16(o: Int, v: Int) { h[o]=v.toByte(); h[o+1]=(v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(h, 0); int32(4, 36 + data.size); "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, rate); int32(28, byteRate); int16(32, 2); int16(34, 16)
        "data".toByteArray().copyInto(h, 36); int32(40, data.size)
        wav.outputStream().use { it.write(h); it.write(data) }
    }
}