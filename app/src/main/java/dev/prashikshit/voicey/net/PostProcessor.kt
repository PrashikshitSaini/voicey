package dev.prashikshit.voicey.net

import dev.prashikshit.voicey.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the raw transcript plus surrounding-field context + custom vocabulary to an
 * OpenAI-compatible /chat/completions endpoint and returns the cleaned text.
 *
 * If the model returns the sentinel "EMPTY", this returns an empty string so the
 * caller can skip insertion.
 */
class PostProcessor(private val settings: Settings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun clean(rawTranscript: String, context: CleanupContext): String = withContext(Dispatchers.IO) {
        if (rawTranscript.isBlank()) return@withContext ""
        if (!settings.isReady()) {
            throw PostProcessException("API key or base URL is missing")
        }

        val userMessage = buildUserMessage(rawTranscript, context)
        val payload = JSONObject().apply {
            put("model", settings.cleanupModel)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", settings.systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("${settings.apiBase.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw PostProcessException("HTTP ${response.code}: ${body.take(200)}")
                }
                val cleaned = parseFirstChoice(body)
                if (cleaned == "EMPTY") "" else cleaned
            }
        } catch (e: IOException) {
            throw PostProcessException("Network failure: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun buildUserMessage(transcript: String, ctx: CleanupContext): String = buildString {
        appendLine("RAW_TRANSCRIPTION:")
        appendLine(transcript)
        if (ctx.app.isNotBlank()) {
            appendLine()
            appendLine("FOREGROUND_APP: ${ctx.app}")
        }
        if (ctx.textBefore.isNotBlank() || ctx.textAfter.isNotBlank()) {
            appendLine()
            appendLine("FIELD_CONTEXT_BEFORE_CURSOR:")
            appendLine(ctx.textBefore)
            appendLine("FIELD_CONTEXT_AFTER_CURSOR:")
            appendLine(ctx.textAfter)
        }
        if (settings.vocabulary.isNotEmpty()) {
            appendLine()
            appendLine("CUSTOM_VOCABULARY (spellings to preserve if mentioned):")
            settings.vocabulary.forEach { appendLine("- $it") }
        }
    }.trim()

    private fun parseFirstChoice(body: String): String {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw PostProcessException("Malformed JSON response: ${e.message}")
        }
        val choices = root.optJSONArray("choices")
            ?: throw PostProcessException("Response missing 'choices' field")
        if (choices.length() == 0) throw PostProcessException("Response 'choices' is empty")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw PostProcessException("Response choice missing 'message'")
        return message.optString("content").trim()
    }
}

data class CleanupContext(
    val app: String,
    val textBefore: String,
    val textAfter: String,
) {
    companion object {
        val EMPTY = CleanupContext(app = "", textBefore = "", textAfter = "")
    }
}

class PostProcessException(message: String, cause: Throwable? = null) : Exception(message, cause)
