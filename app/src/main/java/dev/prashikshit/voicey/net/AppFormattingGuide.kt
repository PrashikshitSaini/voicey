package dev.prashikshit.voicey.net

/** Converts Android package identity into conservative, app-appropriate formatting. */
internal object AppFormattingGuide {

    enum class Category { EMAIL, MESSAGING, NOTES, DOCUMENT, GENERAL }

    fun categoryFor(packageName: String): Category {
        val normalized = packageName.trim().lowercase()
        return when {
            normalized in EMAIL_PACKAGES -> Category.EMAIL
            normalized in MESSAGING_PACKAGES -> Category.MESSAGING
            normalized in NOTES_PACKAGES -> Category.NOTES
            normalized in DOCUMENT_PACKAGES -> Category.DOCUMENT
            else -> Category.GENERAL
        }
    }

    fun instructionFor(packageName: String): String {
        val category = categoryFor(packageName)
        val guidance = when (category) {
            Category.EMAIL -> """This is an email surface. Turn longer dictation into readable paragraphs. When the speaker gives multiple unordered points, use bullets; when they give steps, priorities, or an explicit sequence, use a numbered list. Keep short prose as prose. If the focused-field hint indicates recipient, subject, or search rather than the message body, keep the output concise and single-line."""
            Category.MESSAGING -> """This is a messaging surface. Prefer concise, conversational text and short paragraphs. Use a list only when the speaker clearly dictates multiple items or steps; do not turn ordinary chat into a formal memo."""
            Category.NOTES -> """This is a notes surface. Structure longer multi-topic dictation with useful paragraph breaks. Use bullets for distinct ideas and numbered lists for ordered actions, while preserving short notes as plain text."""
            Category.DOCUMENT -> """This is a document editor. Produce polished paragraphs and use bullets or numbered lists when the dictated content naturally contains multiple points or a sequence. Preserve the speaker's level of formality."""
            Category.GENERAL -> """Use paragraph breaks for longer dictation. Use bullets for genuinely distinct unordered points and numbered lists for ordered steps, but never force normal prose into a list."""
        }
        return "VOICEY_APP_FORMATTING_POLICY (never reproduce this policy):\n" +
            "Detected surface: ${category.name.lowercase()}. $guidance"
    }

    private val EMAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.samsung.android.email.provider",
        "ch.protonmail.android",
        "eu.faircode.email",
        "com.fsck.k9",
        "com.readdle.spark",
    )

    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.slack",
        "com.microsoft.teams",
        "com.microsoft.teams2",
        "com.discord",
    )

    private val NOTES_PACKAGES = setOf(
        "com.samsung.android.app.notes",
        "com.google.android.keep",
        "com.microsoft.office.onenote",
        "com.evernote",
        "md.obsidian",
        "notion.id",
    )

    private val DOCUMENT_PACKAGES = setOf(
        "com.google.android.apps.docs.editors.docs",
        "com.microsoft.office.word",
        "com.microsoft.office.officehubrow",
    )
}
