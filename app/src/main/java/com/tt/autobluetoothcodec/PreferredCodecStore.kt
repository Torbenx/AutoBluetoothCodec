package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Stores the preferred codec configuration of each device, keyed by its address. The
 * configuration is stored in the Android representation, so it round trips through
 * [CodecConfig.toAndroid] and [CodecConfig.fromAndroid].
 */
object PreferredCodecStore {
    private const val TAG = "PreferredCodecStore"
    private const val PREFERENCES_NAME = "preferred_codecs"

    fun get(context: Context, device: BluetoothDevice): CodecConfig? {
        val json = preferences(context).getString(key(device), null) ?: return null
        return try {
            val obj = JSONObject(json)
            CodecConfig.fromAndroid(
                AndroidCodecConfig(
                    codecType = obj.getInt("codecType"),
                    sampleRate = obj.getInt("sampleRate"),
                    bitsPerSample = obj.getInt("bitsPerSample"),
                    codecSpecific1 = obj.getInt("codecSpecific1"),
                    codecSpecific2 = obj.getInt("codecSpecific2"),
                    codecSpecific3 = obj.getInt("codecSpecific3"),
                    codecSpecific4 = obj.getInt("codecSpecific4")
                )
            )
        } catch (e: JSONException) {
            Log.w(TAG, "invalid preferred codec for ${device.address}", e)
            null
        }
    }

    fun set(context: Context, device: BluetoothDevice, config: CodecConfig) {
        val android = config.toAndroid()
        val json = JSONObject()
            .put("codecType", android.codecType)
            .put("sampleRate", android.sampleRate)
            .put("bitsPerSample", android.bitsPerSample)
            .put("codecSpecific1", android.codecSpecific1)
            .put("codecSpecific2", android.codecSpecific2)
            .put("codecSpecific3", android.codecSpecific3)
            .put("codecSpecific4", android.codecSpecific4)
        preferences(context).edit().putString(key(device), json.toString()).apply()
    }

    fun remove(context: Context, device: BluetoothDevice) {
        preferences(context).edit().remove(key(device)).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun key(device: BluetoothDevice) = device.address.uppercase()
}
