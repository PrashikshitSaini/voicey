package dev.prashikshit.voicey.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** The small set of connected external inputs Voicey can sensibly prefer. */
object MicrophoneDevices {
    private val externalTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    fun connectedExternalInputs(audioManager: AudioManager): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type in externalTypes }
            .distinctBy {
                val family = if (it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                ) "usb" else it.type.toString()
                family to it.productName?.toString()?.trim()?.lowercase().orEmpty()
            }

    fun findConnectedExternalInput(audioManager: AudioManager, id: Int): AudioDeviceInfo? =
        if (id == 0) null else connectedExternalInputs(audioManager).firstOrNull { it.id == id }
}
