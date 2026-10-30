package dev.prashikshit.voicey.net

/** What kind of text the focused field accepts, so formatting can respect its shape. */
enum class FieldKind { SEARCH, URL, EMAIL_ADDRESS, SHORT, SUBJECT, SINGLE_LINE, MULTI_LINE, UNKNOWN }

// Literal android.text.InputType values so this stays a pure-JVM decision.
private const val TYPE_MASK_CLASS = 0x0000000f
private const val TYPE_MASK_VARIATION = 0x00000ff0
private const val TYPE_CLASS_TEXT = 1
private const val TYPE_CLASS_NUMBER = 2
private const val TYPE_CLASS_PHONE = 3
private const val TYPE_TEXT_VARIATION_URI = 0x10
private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
private const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x30
private const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x60
private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
private const val TYPE_TEXT_FLAG_MULTI_LINE = 0x20000
// Literal android.view.inputmethod.EditorInfo values, also pure JVM.
private const val IME_MASK_ACTION = 0x000000ff
private const val IME_ACTION_GO = 2
private const val IME_ACTION_SEARCH = 3

internal fun classifyField(
    inputType: Int,
    isMultiLine: Boolean,
    hint: String,
    imeAction: Int = 0,
): FieldKind {
    val normalized = hint.trim().lowercase()
    val action = imeAction and IME_MASK_ACTION
    if (action == IME_ACTION_SEARCH) return FieldKind.SEARCH
    if (normalized.contains("search")) return FieldKind.SEARCH

    val variation = inputType and TYPE_MASK_VARIATION
    val looksLikeUrl = variation == TYPE_TEXT_VARIATION_URI || normalized.contains("url")
    if (looksLikeUrl ||
        normalized.contains("web address") ||
        (action == IME_ACTION_GO && looksLikeUrl)
    ) return FieldKind.URL

    if (variation == TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
        variation == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
        normalized == "email address" ||
        normalized == "recipient" ||
        normalized == "to"
    ) return FieldKind.EMAIL_ADDRESS

    if (variation == TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
        normalized.contains("subject") ||
        normalized.contains("title")
    ) return FieldKind.SUBJECT

    val textClass = inputType and TYPE_MASK_CLASS
    if (variation == TYPE_TEXT_VARIATION_PERSON_NAME ||
        textClass == TYPE_CLASS_NUMBER ||
        textClass == TYPE_CLASS_PHONE
    ) return FieldKind.SHORT

    if (isMultiLine || (inputType and TYPE_TEXT_FLAG_MULTI_LINE) != 0) return FieldKind.MULTI_LINE

    return if (textClass == TYPE_CLASS_TEXT) FieldKind.SINGLE_LINE else FieldKind.UNKNOWN
}
