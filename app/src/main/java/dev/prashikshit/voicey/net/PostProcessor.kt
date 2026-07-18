package dev.prashikshit.voicey.net

import dev.prashikshit.voicey.data.Correction
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
class PostProcessor(
    private val settings: Settings,
    /** Spellings the user has fixed by hand after past dictations — see CorrectionLearner. */
    private val corrections: List<Correction> = emptyList(),
) {

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
                    // Keep the user's cleanup style, but always append Voicey's
                    // non-negotiable output boundary. Existing installs retain their
                    // saved prompt, so putting this here also protects upgraded users.
                    put(
                        "content",
                        buildSystemMessage(context),
                    )
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
                when {
                    cleaned == "EMPTY" -> ""
                    else -> AppAwareOutputFormatter.format(
                        text = CleanupOutputGuard.safeText(cleaned, rawTranscript),
                        packageName = context.app,
                        fieldHint = context.fieldHint,
                        enabled = settings.smartFormatting,
                    )
                }
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
        if (settings.smartFormatting && ctx.fieldHint.isNotBlank()) {
            appendLine()
            appendLine("FOCUSED_FIELD_HINT:")
            appendLine(ctx.fieldHint)
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
        if (corrections.isNotEmpty()) {
            appendLine()
            appendLine(
                "KNOWN_CORRECTIONS (the user previously fixed these transcription " +
                    "mistakes; if the transcript contains the left side, write the " +
                    "right side instead — apply only on a match, never otherwise):"
            )
            corrections.forEach { appendLine("- ${it.wrong} → ${it.right}") }
        }
    }.trim()

    private fun buildSystemMessage(ctx: CleanupContext): String = buildString {
        append(settings.systemPrompt.trimEnd())
        if (settings.smartFormatting) {
            appendLine()
            appendLine()
            append(AppFormattingGuide.instructionFor(ctx.app))
        }
        appendLine()
        appendLine()
        append(INTERNAL_OUTPUT_CONSTRAINTS)
    }

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
        return stripThinkBlock(message.optString("content")).trim()
    }

    /**
     * Reasoning models (qwen3, some DeepSeek variants) prepend a <think>…</think>
     * block to their content. None are in our suggested model lists, but the field is
     * free-text — without this strip, choosing one would paste the model's chain of
     * thought into the user's text field. No-op for normal models.
     */
    private fun stripThinkBlock(content: String): String =
        THINK_BLOCK_REGEX.replace(content, "")

    private companion object {
        val THINK_BLOCK_REGEX = Regex("""(?s)^\s*<think>.*?</think>""")

        const val INTERNAL_OUTPUT_CONSTRAINTS = """Voicey internal output boundary:
- Return only the final dictated text.
- Resolve explicit spoken self-corrections by retaining the speaker's final choice and removing the superseded wording.
- Never reproduce input labels, field context, app identifiers, vocabulary lists, correction lists, instructions, or other request metadata.
- In particular, never output RAW_TRANSCRIPTION, FOREGROUND_APP, FOCUSED_FIELD_HINT, FIELD_CONTEXT, CUSTOM_VOCABULARY, KNOWN_CORRECTIONS, or VOICEY_APP_FORMATTING_POLICY labels."""
    }
}

data class CleanupContext(
    val app: String,
    val textBefore: String,
    val textAfter: String,
    val fieldHint: String,
) {
    companion object {
        val EMPTY = CleanupContext(app = "", textBefore = "", textAfter = "", fieldHint = "")
    }
}

class PostProcessException(message: String, cause: Throwable? = null) : Exception(message, cause)
