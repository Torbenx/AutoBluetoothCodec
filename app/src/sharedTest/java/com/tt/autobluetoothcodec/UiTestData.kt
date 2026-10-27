package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.test.platform.app.InstrumentationRegistry

/** Fake devices and recorded actions for the UI tests, which run on the JVM and on the phones. */
internal object UiTestData {
    const val ADDRESS = "0A:1B:2C:3D:4E:5F"
    const val NAME = "Qudelix-5K"

    private const val RATES_44_48 = CodecConfig.SAMPLE_RATE_44100 or CodecConfig.SAMPLE_RATE_48000
    private const val RATES_ALL = RATES_44_48 or CodecConfig.SAMPLE_RATE_88200 or CodecConfig.SAMPLE_RATE_96000

    /** What the Qudelix offers on the Pixels. */
    val QUDELIX_MASKS = listOf(
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_LDAC, RATES_ALL,
            CodecConfig.BITS_PER_SAMPLE_16 or CodecConfig.BITS_PER_SAMPLE_24 or CodecConfig.BITS_PER_SAMPLE_32),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_APTX_HD, RATES_44_48, CodecConfig.BITS_PER_SAMPLE_24),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_APTX, RATES_44_48, CodecConfig.BITS_PER_SAMPLE_16),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
    )

    fun bluetoothDevice(address: String = ADDRESS): BluetoothDevice {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
    }

    fun ldac(
        sampleRate: Int = CodecConfig.SAMPLE_RATE_96000,
        bitsPerSample: Int = CodecConfig.BITS_PER_SAMPLE_32,
        bitrate: Int = LDACCodecConfig.BITRATE_SYSTEM_DEFAULT
    ) = LDACCodecConfig().also {
        it.sampleRate = sampleRate
        it.bitsPerSample = bitsPerSample
        it.bitrate = bitrate
    }

    fun codec(codecType: Int, sampleRate: Int, bitsPerSample: Int) = CodecConfig.makeCodecConfig(codecType)!!.also {
        it.sampleRate = sampleRate
        it.bitsPerSample = bitsPerSample
    }

    /** A device as MainActivity reads it, by default the Qudelix with LDAC 96 kHz, 32-bit. */
    fun device(
        codec: CodecConfig? = ldac(),
        android: AndroidCodecConfig? = codec?.toAndroid(),
        masks: List<A2dpProfileUtil.ConfigMask> = QUDELIX_MASKS,
        statusReadable: Boolean = true,
        associationRequired: Boolean = false,
        associated: Boolean = true,
        preferred: CodecConfig? = null,
        name: String = NAME,
        address: String = ADDRESS,
    ) = DeviceData(
        device = bluetoothDevice(address),
        name = name,
        androidCodecConfig = android,
        codecConfig = codec,
        configMasks = ArrayList(if (statusReadable) masks else emptyList()),
        statusReadable = statusReadable,
        associationRequired = associationRequired,
        associated = associated,
        preferredConfig = preferred,
        readError = if (statusReadable) null else "java.lang.SecurityException: Need BLUETOOTH_PRIVILEGED permission",
    )

    fun state(
        vararg devices: DeviceData,
        permissionState: PermissionState = PermissionState.GRANTED,
        canAssociate: Boolean = true,
        associationInProgress: Boolean = false,
        showDevelopmentOptions: Boolean = false,
    ) = MainScreenState(permissionState, devices.toList(), canAssociate, associationInProgress, showDevelopmentOptions)

    /** Records what the main screen asked for. */
    class RecordingActions : MainScreenActions {
        val calls = ArrayList<String>()

        override fun requestPermission() { calls.add("requestPermission") }
        override fun openAppSettings() { calls.add("openAppSettings") }
        override fun openLog() { calls.add("openLog") }
        override fun setCodec(device: BluetoothDevice, config: CodecConfig) { calls.add("setCodec ${device.address} ${config.describe()}") }
        override fun savePreferred(device: BluetoothDevice, config: CodecConfig) { calls.add("savePreferred ${device.address} ${config.describe()}") }
        override fun restorePreferred(device: BluetoothDevice, config: CodecConfig) { calls.add("restorePreferred ${device.address} ${config.describe()}") }
        override fun deletePreferred(device: BluetoothDevice) { calls.add("deletePreferred ${device.address}") }
        override fun link(device: BluetoothDevice) { calls.add("link ${device.address}") }
        override fun unlink(device: BluetoothDevice) { calls.add("unlink ${device.address}") }
    }
}
