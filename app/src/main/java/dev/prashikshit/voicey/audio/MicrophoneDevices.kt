package dev.prashikshit.voicey.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** The small set of connected external inputs Voicey can sensibly prefer. */
object MicrophoneDevices {
    private val externalTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun isBluetooth(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    fun connectedExternalInputs(audioManager: AudioManager): List<AudioDeviceInfo> {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type in externalTypes }
        // Communication routing accepts output endpoints; Android selects their matching mic.
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            inputs.filterNot(::isBluetooth) + audioManager.availableCommunicationDevices.filter(::isBluetooth)
        } else inputs
        return devices
            .distinctBy {
                val family = if (it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                ) "usb" else it.type.toString()
                family to it.productName?.toString()?.trim()?.lowercase().orEmpty()
            }
    }

    fun findConnectedExternalInput(audioManager: AudioManager, id: Int): AudioDeviceInfo? =
        if (id == 0) null else connectedExternalInputs(audioManager).firstOrNull { it.id == id }
            // Preserve selections saved by earlier versions, which used source endpoint IDs.
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == id && it.type in externalTypes }
}
