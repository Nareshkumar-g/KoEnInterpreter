package com.nars.kointerpreter.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicKind { USB, WIRED, BLUETOOTH }

/** An external microphone the user can pick. [key] survives unplug/replug; system ids don't. */
data class MicDevice(val key: String, val name: String, val kind: MicKind)

/**
 * Lists external microphones (USB-C receivers like the DJI Mic, wired headsets,
 * Bluetooth mics) as they come and go, and routes audio to the chosen one.
 */
class MicManager(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)

    private val _external = MutableStateFlow<List<MicDevice>>(emptyList())
    val external: StateFlow<List<MicDevice>> = _external.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        refresh()
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun inputs(): Array<AudioDeviceInfo> = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

    private fun kindOf(type: Int): MicKind? = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET -> MicKind.BLUETOOTH
        else -> null
    }

    private fun keyOf(d: AudioDeviceInfo): String = "${kindOf(d.type)}:${d.productName}"

    private fun nameOf(d: AudioDeviceInfo, kind: MicKind): String {
        val product = d.productName?.toString()?.trim().orEmpty()
        val fallback = when (kind) {
            MicKind.USB -> "USB mic"
            MicKind.WIRED -> "Wired mic"
            MicKind.BLUETOOTH -> "Bluetooth mic"
        }
        return product.ifBlank { fallback }
    }

    fun refresh() {
        _external.value = inputs().mapNotNull { d ->
            kindOf(d.type)?.let { kind -> MicDevice(keyOf(d), nameOf(d, kind), kind) }
        }.distinctBy { it.key }
    }

    /** The connected input for [key], or null if it's unplugged / out of range. */
    fun findInput(key: String): AudioDeviceInfo? =
        inputs().firstOrNull { kindOf(it.type) != null && keyOf(it) == key }

    fun builtInMic(): AudioDeviceInfo? =
        inputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    // ---------------------------------------------------------------- routing

    private var routedKey: String? = null

    /**
     * Sends the phone's voice audio path to [device]. Needed for Bluetooth mics
     * (which only deliver audio once the voice link is open) and helps the built-in
     * recognizer, which can't be told which mic to use directly. The route is kept
     * until [clearRoute], so push-to-talk doesn't wait on every press.
     */
    suspend fun ensureRoute(device: AudioDeviceInfo?) {
        val kind = device?.let { kindOf(it.type) }
        val key = device?.let { keyOf(it) }
        if (key != null && key == routedKey) return
        clearRoute()
        if (device == null || (kind != MicKind.BLUETOOTH && kind != MicKind.USB)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = am.availableCommunicationDevices.firstOrNull { matches(it, device) } ?: return
            if (runCatching { am.setCommunicationDevice(target) }.getOrDefault(false)) {
                routedKey = key
                if (kind == MicKind.BLUETOOTH) waitUntil { am.communicationDevice?.id == target.id }
            }
        } else if (kind == MicKind.BLUETOOTH) {
            @Suppress("DEPRECATION")
            runCatching {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                routedKey = key
            }
            delay(1000) // the Bluetooth voice link takes a moment to open
        }
    }

    fun clearRoute() {
        if (routedKey == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
            }
        }
        routedKey = null
    }

    fun release() {
        clearRoute()
        am.unregisterAudioDeviceCallback(callback)
    }

    /** Input devices and their matching communication (output) device share type and address. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun matches(out: AudioDeviceInfo, input: AudioDeviceInfo): Boolean {
        val sameFamily = kindOf(out.type) == kindOf(input.type)
        if (!sameFamily) return false
        val a = out.address.orEmpty()
        val b = input.address.orEmpty()
        return if (a.isNotEmpty() && b.isNotEmpty()) a == b else out.productName == input.productName
    }

    private suspend fun waitUntil(timeoutMs: Long = 1500, check: () -> Boolean) {
        var waited = 0L
        while (!check() && waited < timeoutMs) {
            delay(100)
            waited += 100
        }
    }

    companion object {
        /** AudioDeviceInfo.TYPE_BLE_HEADSET (API 31), inlined so it also compiles cleanly for older targets. */
        private const val TYPE_BLE_HEADSET = 26
    }
}
