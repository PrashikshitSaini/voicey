package dev.prashikshit.voicey.audio

internal data class BluetoothEndpoint(val id: Int, val type: Int, val address: String)

/** Input/output port IDs differ. Prefer identity, and never guess between multiple headsets. */
internal fun selectBluetoothEndpoint(
    selected: BluetoothEndpoint,
    available: List<BluetoothEndpoint>,
): Int? {
    val candidates = available.filter { it.type == selected.type }
    candidates.firstOrNull { it.id == selected.id }?.let { return it.id }
    if (selected.address.isNotBlank()) {
        candidates.firstOrNull { it.address == selected.address }?.let { return it.id }
    }
    return candidates.singleOrNull()?.takeIf {
        selected.address.isBlank() || it.address.isBlank()
    }?.id
}
