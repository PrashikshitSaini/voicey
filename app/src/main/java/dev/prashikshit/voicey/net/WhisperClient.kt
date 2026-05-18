package dev.prashikshit.voicey.net

import dev.prashikshit.voicey.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** Returns the raw transcript text. Throws [TranscriptionException] on any failure. */
    suspend fun transcribe(audio: File): String = withContext(Dispatchers.IO) {
        if (!audio.exists() || audio.length() == 0L) {
            throw TranscriptionException("Audio file is empty")
        }
        if (!settings.isReady()) {
            throw TranscriptionException("API key or base URL is missing")
        }

        // Whisper hallucinates YouTube-style phrases ("Subscribe", "Thanks for watching",
        // "search history", etc.) when it gets short or silence-padded audio. Two defenses:
        //   1. `prompt` biases the decoder toward plain dictation style + the user's vocab.
        //      The model treats prompt text as "what came right before this audio," so
        //      anchoring it in dictation tone makes YouTube-trained continuations less likely.
        //   2. `language` pins the language so Whisper doesn't language-detect into a
        //      hallucinated direction on very short clips.
        val promptParts = buildList {
            add("The following is verbatim spoken dictation from a user typing in an app.")
            if (settings.vocabulary.isNotEmpty()) {
                add("Names and terms that may appear: ${settings.vocabulary.joinToString(", ")}.")
            }
        }.joinToString(" ")

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", settings.transcriptionModel)
            .addFormDataPart("response_format", "text")
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
                // response_format=text returns the transcript directly, no JSON wrapper.
                body.trim()
            }
        } catch (e: IOException) {
            throw TranscriptionException("Network failure: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
