package dev.prashikshit.voicey.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// getAddress() is API 28+; on 26/27 it would throw NoSuchMethodError.
private fun AudioDeviceInfo.safeAddress(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) address.orEmpty() else ""

/** Owned by one recording. All waits and audio routing changes run off the UI thread. */
internal class BluetoothMicrophoneRoute(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var previousMode: Int? = null
    private var requested = false

    @Suppress("DEPRECATION")
    fun connect(selected: AudioDeviceInfo): AudioDeviceInfo {
        try {
            if (manager.mode != AudioManager.MODE_NORMAL) {
                throw IOException("Microphone is in use by a call or another app")
            }
            previousMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            val ready = CountDownLatch(1)
            var endpoint = selected
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val candidates = manager.availableCommunicationDevices
                val targetId = selectBluetoothEndpoint(
                    BluetoothEndpoint(selected.id, selected.type, selected.safeAddress()),
                    candidates.map { BluetoothEndpoint(it.id, it.type, it.safeAddress()) },
                )
                val target = candidates.firstOrNull { it.id == targetId }
                    ?: throw IOException("Selected earbuds are unavailable; reconnect and select them again")
                endpoint = target
                val listener = AudioManager.OnCommunicationDeviceChangedListener {
                    if (it?.id == target.id) ready.countDown()
                }
                manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
                try {
                    requested = true
                    if (!manager.setCommunicationDevice(target)) throw IOException("Android rejected the earbuds microphone")
                    if (manager.communicationDevice?.id == target.id) ready.countDown()
                    if (!ready.await(5, TimeUnit.SECONDS) || manager.communicationDevice?.id != target.id) {
                        throw IOException("Earbuds microphone did not connect; check Bluetooth call audio and retry")
                    }
                } finally {
                    manager.removeOnCommunicationDeviceChangedListener(listener)
                }
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ==
                            AudioManager.SCO_AUDIO_STATE_CONNECTED) ready.countDown()
                    }
                }
                context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                try {
                    requested = true
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                    if (!ready.await(5, TimeUnit.SECONDS)) {
                        throw IOException("Earbuds microphone did not connect; enable Bluetooth call audio and retry")
                    }
                } finally {
                    context.unregisterReceiver(receiver)
                }
            }
            // Routing selects a communication *output*. Resolve its matching source after
            // connection, so speech-recognition capture can explicitly prefer the buds' mic.
            val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val inputId = selectBluetoothEndpoint(
                BluetoothEndpoint(endpoint.id, endpoint.type, endpoint.safeAddress()),
                inputs.map { BluetoothEndpoint(it.id, it.type, it.safeAddress()) },
            )
            return inputs.firstOrNull { it.id == inputId }
                ?: throw IOException("Earbuds connected but their microphone is unavailable; reconnect and retry")
        } catch (e: Exception) {
            try { close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    override fun close() {
        try {
            if (requested) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.clearCommunicationDevice()
                else {
                    manager.isBluetoothScoOn = false
                    manager.stopBluetoothSco()
                }
            }
        } finally {
            requested = false
            previousMode?.let { mode ->
                if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = mode
            }
            previousMode = null
        }
    }
}
