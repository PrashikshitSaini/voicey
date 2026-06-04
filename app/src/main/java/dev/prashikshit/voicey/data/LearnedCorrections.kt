package dev.prashikshit.voicey.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A spelling the user fixed by hand: Voicey wrote [wrong], the user changed it to [right]. */
data class Correction(val wrong: String, val right: String)

/**
 * Persistent store of corrections learned from the user's post-dictation edits.
 * Backed by the same encrypted prefs as Settings. Capped — oldest entries fall off —
 * and fully erasable from the settings screen, because a wrongly learned pair would
 * otherwise bias every future dictation with no visible cause.
 */
class LearnedCorrections(context: Context) {

    private val store = KeyStore(context)

    fun all(): List<Correction> {
        val raw = store.getString(KEY_CORRECTIONS, "[]")
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val wrong = entry.optString(FIELD_WRONG)
                val right = entry.optString(FIELD_RIGHT)
                if (wrong.isBlank() || right.isBlank()) null else Correction(wrong, right)
            }
        } catch (_: Exception) {
            // Corrupt store — drop it rather than crash dictation forever.
            emptyList()
        }
    }

    /** Adds or updates a pair (keyed by lowercase [wrong]), evicting the oldest past the cap. */
    fun learn(wrong: String, right: String) {
        if (wrong.isBlank() || right.isBlank()) return
        val updated = all()
            .filterNot { it.wrong.equals(wrong, ignoreCase = true) }
            .plus(Correction(wrong, right))
            .takeLast(MAX_ENTRIES)
        save(updated)
    }

    fun clear() {
        store.putString(KEY_CORRECTIONS, "[]")
    }

    fun count(): Int = all().size

    private fun save(corrections: List<Correction>) {
        val array = JSONArray()
        corrections.forEach { correction ->
            array.put(
                JSONObject().apply {
                    put(FIELD_WRONG, correction.wrong)
                    put(FIELD_RIGHT, correction.right)
                }
            )
        }
        store.putString(KEY_CORRECTIONS, array.toString())
    }

    private companion object {
        const val KEY_CORRECTIONS = "learned_corrections"
        const val FIELD_WRONG = "w"
        const val FIELD_RIGHT = "r"
        const val MAX_ENTRIES = 100
    }
}
