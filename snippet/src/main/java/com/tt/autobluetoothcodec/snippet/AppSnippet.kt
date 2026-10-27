package com.tt.autobluetoothcodec.snippet

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import com.tt.autobluetoothcodec.A2dpProfileUtil
import com.tt.autobluetoothcodec.AndroidCodecConfig
import com.tt.autobluetoothcodec.CodecApplier
import com.tt.autobluetoothcodec.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    @Volatile
    private var a2dp: BluetoothA2dp? = null

    /** Runs [block] on the main thread, where the app uses its singletons. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The snippet's own A2DP proxy. Must not be called on the main thread, which delivers it. */
    private fun profile(): BluetoothA2dp {
        a2dp?.let { return it }
        val connected = CountDownLatch(1)
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                connected.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
            }
        }, BluetoothProfile.A2DP)
        check(connected.await(5, TimeUnit.SECONDS)) { "the A2DP profile proxy didn't connect" }
        return a2dp!!
    }

    private fun device(address: String): BluetoothDevice = adapter.getRemoteDevice(address.uppercase())

    private fun toJson(config: AndroidCodecConfig) =
        JSONObject(EventLog.json.encodeToString(AndroidCodecConfig.serializer(), config))

    private fun fromJson(config: JSONObject) =
        EventLog.json.decodeFromString(AndroidCodecConfig.serializer(), config.toString())

    @Rpc(description = "The devices the applier is changing, and where its class was loaded from.")
    fun getPending(): JSONObject = onMain {
        val applier = CodecApplier.get(context)
        JSONObject()
            .put("pending", JSONArray(applier.pending.value.keys))
            .put("pid", Process.myPid())
            .put("sameClassLoader", CodecApplier::class.java.classLoader === context.classLoader)
            .put("classLoader", CodecApplier::class.java.classLoader?.toString())
    }

    @Rpc(description = "The device's current codec as the app reads it, or null.")
    fun getCurrentCodec(address: String): JSONObject? {
        val config = A2dpProfileUtil.getCurrentCodecConfig(profile(), device(address)) ?: return null
        return toJson(config)
    }

    @Rpc(description = "Sets a codec bypassing the CodecApplier, like another app would.")
    fun setCodecDirectly(address: String, config: JSONObject) {
        A2dpProfileUtil.setCodecConfig(profile(), device(address), fromJson(config))
    }

    override fun shutdown() {
        a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
    }
}
