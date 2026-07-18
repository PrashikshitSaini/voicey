package dev.prashikshit.voicey

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import dev.prashikshit.voicey.data.LearnedCorrections
import dev.prashikshit.voicey.data.Settings
import dev.prashikshit.voicey.databinding.ActivityDictionaryBinding

/**
 * A human-friendly editor for Voicey's two personalization layers:
 *
 *  - Custom terms are sent to Whisper as spelling hints and to cleanup as vocabulary.
 *  - Corrections map a commonly misheard spelling to the user's preferred spelling.
 *
 * Both are stored on-device in the same encrypted preferences as Settings and are sent
 * only to the provider the user configured when a dictation is processed.
 */
class DictionaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDictionaryBinding
    private lateinit var learnedStore: LearnedCorrections
    private var customTerms: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        learnedStore = LearnedCorrections(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnAddTerm.setOnClickListener { addCustomTerm() }
        binding.inputCustomTerm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCustomTerm()
                true
            } else false
        }
        binding.btnAddCorrection.setOnClickListener { addCorrection() }
        binding.btnClearLearned.setOnClickListener { confirmClearLearned() }
    }

    override fun onResume() {
        super.onResume()
        customTerms = Settings.load(this).vocabulary.toMutableList()
        renderAll()
    }

    private fun addCustomTerm() {
        val term = binding.inputCustomTerm.text?.toString()?.trim().orEmpty()
        if (term.isBlank()) return
        if (customTerms.any { it.equals(term, ignoreCase = true) }) {
            toast(getString(R.string.dictionary_term_exists))
            return
        }
        customTerms += term
        saveCustomTerms()
        binding.inputCustomTerm.text?.clear()
        renderCustomTerms()
    }

    private fun removeCustomTerm(term: String) {
        customTerms.removeAll { it.equals(term, ignoreCase = true) }
        saveCustomTerms()
        renderCustomTerms()
    }

    private fun saveCustomTerms() {
        val current = Settings.load(this)
        Settings.save(this, current.copy(vocabulary = customTerms.toList()))
    }

    private fun addCorrection() {
        val wrong = binding.inputWrongSpelling.text?.toString()?.trim().orEmpty()
        val right = binding.inputRightSpelling.text?.toString()?.trim().orEmpty()
        if (wrong.isBlank() || right.isBlank()) {
            toast(getString(R.string.dictionary_correction_needs_both))
            return
        }
        if (wrong.equals(right, ignoreCase = false)) {
            toast(getString(R.string.dictionary_correction_same))
            return
        }
        learnedStore.learn(wrong, right)
        binding.inputWrongSpelling.text?.clear()
        binding.inputRightSpelling.text?.clear()
        renderLearnedCorrections()
        toast(getString(R.string.dictionary_correction_added, right))
    }

    private fun renderAll() {
        renderCustomTerms()
        renderLearnedCorrections()
    }

    private fun renderCustomTerms() {
        binding.groupCustomTerms.removeAllViews()
        binding.textCustomEmpty.visibility =
            if (customTerms.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        customTerms.forEach { term ->
            binding.groupCustomTerms.addView(removableChip(term) { removeCustomTerm(term) })
        }
    }

    private fun renderLearnedCorrections() {
        val corrections = learnedStore.all().asReversed()
        binding.groupLearnedCorrections.removeAllViews()
        binding.textLearnedEmpty.visibility =
            if (corrections.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnClearLearned.isEnabled = corrections.isNotEmpty()
        corrections.forEach { correction ->
            binding.groupLearnedCorrections.addView(
                removableChip("${correction.wrong}  →  ${correction.right}") {
                    learnedStore.delete(correction.wrong)
                    renderLearnedCorrections()
                }
            )
        }
    }

    private fun removableChip(label: String, onRemove: () -> Unit): Chip =
        Chip(this).apply {
            text = label
            isCheckable = false
            isCloseIconVisible = true
            setOnCloseIconClickListener { onRemove() }
        }

    private fun confirmClearLearned() {
        val count = learnedStore.count()
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_clear_title)
            .setMessage(getString(R.string.dictionary_clear_message, count))
            .setPositiveButton(R.string.dictionary_clear_confirm) { _, _ ->
                learnedStore.clear()
                renderLearnedCorrections()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
