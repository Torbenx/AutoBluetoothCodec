package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothDevice
import android.os.Build

object A2dpProfileUtil {
    private val codecConfigClass = ClassLoader.getSystemClassLoader().loadClass("android.bluetooth.BluetoothCodecConfig")
    private val codecStatusClass = ClassLoader.getSystemClassLoader().loadClass("android.bluetooth.BluetoothCodecStatus")

    private fun createCodecConfig(config: AndroidCodecConfig): Any {
        if (Build.VERSION.SDK_INT >= 33) {
            return BluetoothCodecConfig.Builder()
                .setCodecType(config.codecType)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(config.sampleRate)
                .setBitsPerSample(config.bitsPerSample)
                .setCodecSpecific1(config.codecSpecific1.toLong())
                .setCodecSpecific2(config.codecSpecific2.toLong())
                .setCodecSpecific3(config.codecSpecific3.toLong())
                .setCodecSpecific4(config.codecSpecific4.toLong())
                .build()
        } else {
            val codecConstructor = codecConfigClass.getConstructor(
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
                java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE)
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/oreo-release/core/java/android/bluetooth/BluetoothCodecConfig.java
            return codecConstructor.newInstance(
                config.codecType,
                BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
                config.sampleRate,
                config.bitsPerSample,
                BluetoothCodecConfig.CHANNEL_MODE_NONE,
                config.codecSpecific1,
                config.codecSpecific2,
                config.codecSpecific3,
                config.codecSpecific4
            )
        }
    }

    private fun getCodecStatus(profile: BluetoothA2dp, device: BluetoothDevice): Any {
        val method = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java)
        return method.invoke(profile, device)!!
    }

    private fun toConfigMaskArray(configs: Any?): ArrayList<ConfigMask> {
        configs?: return ArrayList()
        val result = ArrayList<ConfigMask>()
        if (configs is ArrayList<*>) {
            for (inConfig in configs) {
                result.add(ConfigMask(
                    codecType = codecConfigClass.getMethod("getCodecType").invoke(inConfig) as Int,
                    sampleRateMask = codecConfigClass.getMethod("getSampleRate").invoke(inConfig) as Int,
                    bitsPerSampleMask = codecConfigClass.getMethod("getBitsPerSample").invoke(inConfig) as Int
                ))
            }
        } else {
            for (i in 0 ..< java.lang.reflect.Array.getLength(configs)) {
                val inConfig = java.lang.reflect.Array.get(configs, i)
                result.add(ConfigMask(
                    codecType = codecConfigClass.getMethod("getCodecType").invoke(inConfig) as Int,
                    sampleRateMask = codecConfigClass.getMethod("getSampleRate").invoke(inConfig) as Int,
                    bitsPerSampleMask = codecConfigClass.getMethod("getBitsPerSample").invoke(inConfig) as Int
                ))
            }
        }


        return result
    }

    fun setCodecConfig(profile: BluetoothA2dp, device: BluetoothDevice, config: AndroidCodecConfig) {
        val codecConfig = createCodecConfig(config)
        val method = BluetoothA2dp::class.java.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, codecConfigClass)
        method.invoke(profile, device, codecConfig)
    }

    fun getCurrentCodecConfig(profile: BluetoothA2dp, device: BluetoothDevice): AndroidCodecConfig? {
        val status = getCodecStatus(profile, device)
        val codecConfig = codecStatusClass.getMethod("getCodecConfig").invoke(status) ?: return null

        return AndroidCodecConfig(
            codecType = codecConfigClass.getMethod("getCodecType").invoke(codecConfig) as Int,
            sampleRate = codecConfigClass.getMethod("getSampleRate").invoke(codecConfig) as Int,
            bitsPerSample = codecConfigClass.getMethod("getBitsPerSample").invoke(codecConfig) as Int,
            codecSpecific1 = (codecConfigClass.getMethod("getCodecSpecific1").invoke(codecConfig) as Long).toInt(),
            codecSpecific2 = (codecConfigClass.getMethod("getCodecSpecific2").invoke(codecConfig) as Long).toInt(),
            codecSpecific3 = (codecConfigClass.getMethod("getCodecSpecific3").invoke(codecConfig) as Long).toInt(),
            codecSpecific4 = (codecConfigClass.getMethod("getCodecSpecific4").invoke(codecConfig) as Long).toInt()
        )
    }

    data class ConfigMask(
        @CodecConfig.CodecType
        val codecType: Int = CodecConfig.CODEC_TYPE_INVALID,

        val sampleRateMask: Int = CodecConfig.SAMPLE_RATE_NONE,

        val bitsPerSampleMask: Int = CodecConfig.BITS_PER_SAMPLE_NONE
    ) {
        fun isSampleRateSupported(@CodecConfig.SampleRate sampleRate: Int): Boolean {
            return sampleRate and sampleRateMask != 0
        }
        fun isSampleSizeSupported(@CodecConfig.BitsPerSample bitsPerSample: Int): Boolean {
            return bitsPerSample and bitsPerSampleMask != 0
        }
    }

    fun getSelectableCodecConfigs(profile: BluetoothA2dp, device: BluetoothDevice): ArrayList<ConfigMask> {
        val status = getCodecStatus(profile, device)
        return toConfigMaskArray(codecStatusClass.getMethod("getCodecsSelectableCapabilities").invoke(status))
    }
}