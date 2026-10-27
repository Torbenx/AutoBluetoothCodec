package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the preferred codec configuration of each device, keyed by its address. The
 * configuration is stored in the Android representation, so it round trips through
 * [CodecConfig.toAndroid] and [CodecConfig.fromAndroid].
 */
object PreferredCodecStore {
    private const val TAG = "PreferredCodecStore"
    private const val PREFERENCES_NAME = "preferred_codecs"

    // Writes every field, like the format before kotlinx.serialization was used
    internal val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun get(context: Context, device: BluetoothDevice): CodecConfig? {
        val text = preferences(context).getString(key(device), null) ?: return null
        return try {
            CodecConfig.fromAndroid(json.decodeFromString<AndroidCodecConfig>(text))
        } catch (e: SerializationException) {
            Log.w(TAG, "invalid preferred codec for ${device.address}", e)
            null
        }
    }

    fun set(context: Context, device: BluetoothDevice, config: CodecConfig) {
        preferences(context).edit { putString(key(device), json.encodeToString(config.toAndroid())) }
    }

    fun remove(context: Context, device: BluetoothDevice) {
        preferences(context).edit { remove(key(device)) }
    }

    /** Removes the preferred codecs of all devices, for tests. */
    @VisibleForTesting
    fun clearForTest(context: Context) {
        preferences(context).edit(commit = true) { clear() }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun key(device: BluetoothDevice) = device.address.uppercase()
}
