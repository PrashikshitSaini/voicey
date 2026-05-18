package dev.prashikshit.voicey.net

import dev.prashikshit.voicey.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transcribes a WAV file using an OpenAI-compatible /audio/transcriptions endpoint.
 * Tested against Groq Cloud; should also work with OpenAI, Cloudflare Workers AI,
 * and any other provider that mirrors the OpenAI Whisper API surface.
 */
class WhisperClient(private val settings: Settings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the raw transcript text, or an empty string if Whisper's response is
     * judged to be a silence hallucination. Throws [TranscriptionException] on any
     * provider/network failure.
     */
    suspend fun transcribe(audio: File): String = withContext(Dispatchers.IO) {
        if (!audio.exists() || audio.length() == 0L) {
            throw TranscriptionException("Audio file is empty")
        }
        if (!settings.isReady()) {
            throw TranscriptionException("API key or base URL is missing")
        }

        // Whisper hallucinates short phrases ("Subscribe", "Thanks for watching", and
        // search-bar-style fillers like "Search history") when given silent or noisy
        // audio. Three layers of defense:
        //
        //   1. `prompt` biases the decoder toward plain dictation style + user vocab.
        //   2. `language` pins the language so Whisper doesn't language-detect into a
        //      hallucinated direction on very short clips.
        //   3. `response_format=verbose_json` returns per-segment `no_speech_prob`
        //      scores so we can detect and drop hallucinations post-hoc. See
        //      [isHallucination] below. This is the trick FreeFlow uses on macOS.
        val promptParts = buildList {
            add("The following is verbatim spoken dictation from a user typing in an app.")
            if (settings.vocabulary.isNotEmpty()) {
                add("Names and terms that may appear: ${settings.vocabulary.joinToString(", ")}.")
            }
        }.joinToString(" ")

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", settings.transcriptionModel)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", promptParts)
            .addFormDataPart("language", settings.language.ifBlank { "en" })
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("${settings.apiBase.trimEnd('/')}/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(multipart)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TranscriptionException("HTTP ${response.code}: ${body.take(200)}")
                }
                parseAndFilter(body)
            }
        } catch (e: IOException) {
            throw TranscriptionException("Network failure: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun parseAndFilter(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            // Some providers (or older Whisper-compatible servers) may still return
            // plain text even when verbose_json is requested. Treat that as the
            // transcript and skip hallucination filtering — without per-segment
            // metadata we can't make the call safely.
            return body.trim()
        }
        val text = json.optString("text").trim()
        if (text.isEmpty()) return ""
        if (isHallucination(text, json)) return ""
        return text
    }

    /**
     * Mirrors FreeFlow's filter, with one extra rule (the universal-silence catch).
     *
     * - If the first segment's `no_speech_prob` is at or above [UNIVERSAL_NO_SPEECH_THRESHOLD],
     *   the clip is effectively silence and any output is a hallucination — drop it
     *   regardless of phrase. This catches the moving-target placeholders ("Search history",
     *   the app's hint text, etc.) that don't fit a static blocklist.
     *
     * - Otherwise, if the (normalized) text matches a known hallucination phrase and the
     *   first segment's `no_speech_prob` is at or above [KNOWN_PHRASE_THRESHOLD], drop it.
     *   These are phrases Whisper outputs even on partial speech, so the threshold here is
     *   much more conservative to avoid filtering real short utterances.
     */
    private fun isHallucination(text: String, json: JSONObject): Boolean {
        val segments = json.optJSONArray("segments") ?: return false
        if (segments.length() == 0) return false
        val firstSegment = segments.optJSONObject(0) ?: return false
        val noSpeechProb = firstSegment.optDouble("no_speech_prob", 0.0)

        if (noSpeechProb >= UNIVERSAL_NO_SPEECH_THRESHOLD) return true

        val normalized = text.lowercase().trim { !it.isLetterOrDigit() }
        if (normalized in KNOWN_HALLUCINATION_PHRASES &&
            noSpeechProb >= KNOWN_PHRASE_THRESHOLD
        ) {
            return true
        }
        return false
    }

    private companion object {
        /** Conservative phrase-match threshold lifted from FreeFlow. */
        const val KNOWN_PHRASE_THRESHOLD = 0.1

        /**
         * Universal silence threshold. Real short speech ("yes", "no", a name) sits
         * around 0.01–0.15. Hallucinations on silence sit around 0.5–0.95. 0.6 keeps
         * real short utterances safe while still catching the silent-tap case.
         */
        const val UNIVERSAL_NO_SPEECH_THRESHOLD = 0.6

        /**
         * Lifted from FreeFlow's TranscriptionService.swift. These are phrases
         * Whisper-large-v3 has been observed to hallucinate on near-silent audio.
         */
        val KNOWN_HALLUCINATION_PHRASES = setOf(
            "thank you",
            "thank you for watching",
            "thank you very much",
            "thank you so much",
            "thanks for watching",
            "please subscribe",
            "like and subscribe",
            "subtitles by",
            "subtitles by the amara org community",
            "you",
        )
    }
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
