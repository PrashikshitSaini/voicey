package dev.prashikshit.voicey.data

import android.content.Context

/**
 * User-configurable settings. Persisted to EncryptedSharedPreferences via [KeyStore].
 *
 * Defaults match Groq Cloud, since it's the free-tier provider FreeFlow uses on macOS.
 * Any OpenAI-compatible base URL works — set the API base + model strings accordingly.
 */
data class Settings(
    val apiBase: String,
    val apiKey: String,
    val transcriptionModel: String,
    val cleanupModel: String,
    val vocabulary: List<String>,
    val systemPrompt: String,
    val holdToTalk: Boolean,
) {
    fun isReady(): Boolean = apiKey.isNotBlank() && apiBase.isNotBlank()

    companion object {
        const val DEFAULT_API_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_CLEANUP_MODEL = "llama-3.3-70b-versatile"

        /**
         * Default cleanup prompt. Lifted with light edits from FreeFlow's simple post-processing prompt
         * (MIT-licensed). Keeps cleanup literal and avoids hallucinated names.
         */
        const val DEFAULT_SYSTEM_PROMPT = """You are a dictation post-processor. You receive raw speech-to-text output and return clean text ready to be typed into an application.

Your job:
- Remove filler words (um, uh, you know, like) unless they carry meaning.
- Fix spelling, grammar, and punctuation errors.
- When the transcript contains a word that is a close misspelling of a name or term from the context or custom vocabulary, correct the spelling. Never insert names or terms from context that the speaker did not say.
- Preserve the speaker's intent, tone, and meaning exactly.

Output rules:
- Return ONLY the cleaned transcript text, nothing else. Never output preambles like "Here is the cleaned transcript:".
- If the transcription is empty, return exactly: EMPTY
- Do not add words, names, or content that are not in the transcription.
- Do not change the meaning of what was said."""

        fun load(context: Context): Settings {
            val store = KeyStore(context)
            return Settings(
                apiBase = store.getString(KEY_API_BASE, DEFAULT_API_BASE),
                apiKey = store.getString(KEY_API_KEY, ""),
                transcriptionModel = store.getString(KEY_TRANSCRIPTION_MODEL, DEFAULT_TRANSCRIPTION_MODEL),
                cleanupModel = store.getString(KEY_CLEANUP_MODEL, DEFAULT_CLEANUP_MODEL),
                vocabulary = store.getString(KEY_VOCABULARY, "")
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList(),
                systemPrompt = store.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT),
                holdToTalk = store.getBoolean(KEY_HOLD_TO_TALK, true),
            )
        }

        fun save(context: Context, settings: Settings) {
            val store = KeyStore(context)
            store.putString(KEY_API_BASE, settings.apiBase.trim())
            store.putString(KEY_API_KEY, settings.apiKey.trim())
            store.putString(KEY_TRANSCRIPTION_MODEL, settings.transcriptionModel.trim())
            store.putString(KEY_CLEANUP_MODEL, settings.cleanupModel.trim())
            store.putString(KEY_VOCABULARY, settings.vocabulary.joinToString("\n"))
            store.putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            store.putBoolean(KEY_HOLD_TO_TALK, settings.holdToTalk)
        }

        private const val KEY_API_BASE = "api_base"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TRANSCRIPTION_MODEL = "transcription_model"
        private const val KEY_CLEANUP_MODEL = "cleanup_model"
        private const val KEY_VOCABULARY = "vocabulary"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_HOLD_TO_TALK = "hold_to_talk"
    }
}
