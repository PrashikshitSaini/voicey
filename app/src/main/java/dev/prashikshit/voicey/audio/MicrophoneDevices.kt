package dev.prashikshit.voicey.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** The small set of connected external inputs Voicey can sensibly prefer. */
object MicrophoneDevices {
    private val usbTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val externalTypes = usbTypes + setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun isBluetooth(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /** Stable across reconnects; ids are reassigned by Android. */
    fun stableKey(device: AudioDeviceInfo): String = when {
        isBluetooth(device) -> {
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.address?.trim().orEmpty()
            } else ""
            if (address.isNotEmpty()) "bt:$address" else "bt:name:${deviceName(device)}"
        }
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        device.type in usbTypes -> "usb:${deviceName(device)}"
        else -> "device:${device.type}"
    }

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
                family to deviceName(it)
            }
    }

    class Picked(val device: AudioDeviceInfo?, val explicit: Boolean, val bluetooth: Boolean)

    fun select(audioManager: AudioManager, selection: String): Picked {
        val devices = connectedExternalInputs(audioManager)
        val builtIn = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val candidates = devices.map(::candidate) +
            listOfNotNull(builtIn?.let { MicrophoneCandidate(MICROPHONE_PHONE, MicrophoneKind.PHONE, it.id) })
        val choice = chooseMicrophone(selection, candidates, communicationDeviceId(audioManager))
        val device = choice.candidate?.let { chosen ->
            devices.firstOrNull { it.id == chosen.id } ?: builtIn?.takeIf { it.id == chosen.id }
        }
        return Picked(device, choice.explicit, choice.candidate?.kind == MicrophoneKind.BLUETOOTH)
    }

    private fun candidate(device: AudioDeviceInfo) = MicrophoneCandidate(
        stableKey(device),
        when {
            isBluetooth(device) -> MicrophoneKind.BLUETOOTH
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicrophoneKind.WIRED
            else -> MicrophoneKind.USB
        },
        device.id,
    )

    private fun communicationDeviceId(audioManager: AudioManager): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.id else null

    private fun deviceName(device: AudioDeviceInfo): String =
        device.productName?.toString()?.trim()?.lowercase().orEmpty()
}
