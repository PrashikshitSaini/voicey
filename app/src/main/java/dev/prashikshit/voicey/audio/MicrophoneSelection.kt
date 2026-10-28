package dev.prashikshit.voicey.audio

const val MICROPHONE_AUTO = "auto"
const val MICROPHONE_PHONE = "phone"

internal enum class MicrophoneKind { PHONE, WIRED, USB, BLUETOOTH }

internal data class MicrophoneCandidate(val key: String, val kind: MicrophoneKind, val id: Int)

internal data class MicrophoneChoice(val candidate: MicrophoneCandidate?, val explicit: Boolean)

/**
 * Picks an input from plain data. Automatic prefers wired, then USB, then a Bluetooth headset,
 * then the phone default. A pinned key that is absent silently falls back to automatic selection.
 */
internal fun chooseMicrophone(
    selection: String,
    candidates: List<MicrophoneCandidate>,
    communicationDeviceId: Int?,
): MicrophoneChoice {
    if (selection == MICROPHONE_PHONE) {
        return MicrophoneChoice(candidates.firstOrNull { it.kind == MicrophoneKind.PHONE }, explicit = false)
    }
    if (selection != MICROPHONE_AUTO) {
        candidates.firstOrNull { it.key == selection }?.let {
            return MicrophoneChoice(it, explicit = true)
        }
    }
    val external = candidates.filter { it.kind != MicrophoneKind.PHONE }
    external.firstOrNull { it.kind == MicrophoneKind.WIRED }?.let {
        return MicrophoneChoice(it, explicit = false)
    }
    external.firstOrNull { it.kind == MicrophoneKind.USB }?.let {
        return MicrophoneChoice(it, explicit = false)
    }
    val bluetooth = external.filter { it.kind == MicrophoneKind.BLUETOOTH }
    return MicrophoneChoice(
        bluetooth.firstOrNull { it.id == communicationDeviceId } ?: bluetooth.firstOrNull(),
        explicit = false,
    )
}
