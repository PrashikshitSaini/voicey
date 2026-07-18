package dev.prashikshit.voicey.data

/** A friendly language picker over Whisper's ISO-639-1 language parameter. */
object LanguageCatalog {

    data class Option(val label: String, val code: String)

    val options = listOf(
        Option("Auto-detect (multilingual)", ""),
        Option("English", "en"),
        Option("Hindi", "hi"),
        Option("Spanish", "es"),
        Option("French", "fr"),
        Option("German", "de"),
        Option("Italian", "it"),
        Option("Portuguese", "pt"),
        Option("Chinese", "zh"),
        Option("Japanese", "ja"),
        Option("Korean", "ko"),
        Option("Arabic", "ar"),
        Option("Russian", "ru"),
        Option("Bengali", "bn"),
        Option("Urdu", "ur"),
        Option("Tamil", "ta"),
        Option("Telugu", "te"),
        Option("Punjabi", "pa"),
        Option("Marathi", "mr"),
        Option("Gujarati", "gu"),
    )

    val labels: List<String> = options.map(Option::label)

    fun labelForCode(code: String): String =
        options.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }?.label
            ?: code.ifBlank { options.first().label }

    fun codeForLabel(labelOrCode: String): String =
        options.firstOrNull { it.label.equals(labelOrCode.trim(), ignoreCase = true) }?.code
            ?: labelOrCode.trim().lowercase()
}
