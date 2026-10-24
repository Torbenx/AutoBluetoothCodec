package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothCodecConfig
import androidx.annotation.IntDef

abstract class CodecConfig : Cloneable {
    @IntDef(
        value = [
            CODEC_TYPE_INVALID,
            CODEC_TYPE_SBC,
            CODEC_TYPE_AAC,
            CODEC_TYPE_LDAC,
            CODEC_TYPE_APTX,
            CODEC_TYPE_APTX_HD,
            CODEC_TYPE_OPUS
        ]
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class CodecType

    @IntDef(
        value = [
            SAMPLE_RATE_NONE,
            SAMPLE_RATE_44100,
            SAMPLE_RATE_48000,
            SAMPLE_RATE_88200,
            SAMPLE_RATE_96000
        ]
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class SampleRate

    @IntDef(
        value = [
            BITS_PER_SAMPLE_NONE,
            BITS_PER_SAMPLE_16,
            BITS_PER_SAMPLE_24,
            BITS_PER_SAMPLE_32
        ]
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class BitsPerSample

    companion object {
        const val CODEC_TYPE_SBC = BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC
        const val CODEC_TYPE_AAC = BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC
        const val CODEC_TYPE_LDAC = BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
        const val CODEC_TYPE_APTX = BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX
        const val CODEC_TYPE_APTX_HD = BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD
        const val CODEC_TYPE_OPUS = BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS
        const val CODEC_TYPE_INVALID = BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID

        val CODEC_TYPES = arrayOf(
            CODEC_TYPE_SBC,
            CODEC_TYPE_AAC,
            CODEC_TYPE_LDAC,
            CODEC_TYPE_APTX,
            CODEC_TYPE_APTX_HD,
            CODEC_TYPE_OPUS
        )

        const val SAMPLE_RATE_NONE = BluetoothCodecConfig.SAMPLE_RATE_NONE
        const val SAMPLE_RATE_44100 = BluetoothCodecConfig.SAMPLE_RATE_44100
        const val SAMPLE_RATE_48000 = BluetoothCodecConfig.SAMPLE_RATE_48000
        const val SAMPLE_RATE_88200 = BluetoothCodecConfig.SAMPLE_RATE_88200
        const val SAMPLE_RATE_96000 = BluetoothCodecConfig.SAMPLE_RATE_96000

        val SAMPLE_RATES = arrayOf(
            SAMPLE_RATE_44100,
            SAMPLE_RATE_48000,
            SAMPLE_RATE_88200,
            SAMPLE_RATE_96000
        )

        const val BITS_PER_SAMPLE_NONE = BluetoothCodecConfig.BITS_PER_SAMPLE_NONE
        const val BITS_PER_SAMPLE_16 = BluetoothCodecConfig.BITS_PER_SAMPLE_16
        const val BITS_PER_SAMPLE_24 = BluetoothCodecConfig.BITS_PER_SAMPLE_24
        const val BITS_PER_SAMPLE_32 = BluetoothCodecConfig.BITS_PER_SAMPLE_32

        val SAMPLE_SIZES = arrayOf(
            BITS_PER_SAMPLE_16,
            BITS_PER_SAMPLE_24,
            BITS_PER_SAMPLE_32
        )

        fun getCodecName(@CodecType codec: Int): String? {
            return when (codec) {
                CODEC_TYPE_SBC -> return "SBC"
                CODEC_TYPE_AAC -> return "AAC"
                CODEC_TYPE_LDAC -> return "LDAC"
                CODEC_TYPE_APTX -> return "aptX"
                CODEC_TYPE_APTX_HD -> return "aptX HD"
                CODEC_TYPE_OPUS -> return "Opus"
                else -> null
            }
        }

        fun getSampleRate(@SampleRate sampleRate: Int): Int? {
            return when (sampleRate) {
                SAMPLE_RATE_44100 -> 44100
                SAMPLE_RATE_48000 -> 48000
                SAMPLE_RATE_88200 -> 88200
                SAMPLE_RATE_96000 -> 96000
                else -> null
            }
        }

        fun getBitsPerSample(@BitsPerSample bitsPerSample: Int): Int? {
            return when (bitsPerSample) {
                BITS_PER_SAMPLE_16 -> 16
                BITS_PER_SAMPLE_24 -> 24
                BITS_PER_SAMPLE_32 -> 32
                else -> null
            }
        }

        fun makeCodecConfig(@CodecType type: Int): CodecConfig? {
            return when (type) {
                CODEC_TYPE_SBC -> SBCCodecConfig()
                CODEC_TYPE_AAC -> AACCodecConfig()
                CODEC_TYPE_LDAC -> LDACCodecConfig()
                CODEC_TYPE_APTX -> AptXCodecConfig()
                CODEC_TYPE_APTX_HD -> AptXHDCodecConfig()
                CODEC_TYPE_OPUS -> OpusCodecConfig()
                else -> null
            }
        }

        fun fromAndroid(config: AndroidCodecConfig): CodecConfig? {
            val result = makeCodecConfig(config.codecType) ?: return null
            result.importFromAndroid(config)
            return result
        }
    }

    @CodecType
    abstract fun getCodecType(): Int

    public override fun clone(): Any {
        return super.clone()
    }

    open fun toAndroid(): AndroidCodecConfig {
        return AndroidCodecConfig(
            codecType = getCodecType(),
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample
        )
    }

    protected open fun importFromAndroid(config: AndroidCodecConfig) {
        sampleRate = config.sampleRate
        bitsPerSample = config.bitsPerSample
    }

    // toAndroid() maps every combination of parameters to a distinct value, so comparing it
    // compares all parameters, including the ones of subclasses
    override fun equals(other: Any?): Boolean {
        return other is CodecConfig && other.toAndroid() == toAndroid()
    }

    override fun hashCode(): Int {
        return toAndroid().hashCode()
    }

    @SampleRate
    var sampleRate: Int = SAMPLE_RATE_NONE

    @BitsPerSample
    var bitsPerSample: Int = BITS_PER_SAMPLE_NONE
}

data class AndroidCodecConfig(
    @CodecConfig.CodecType
    val codecType: Int = CodecConfig.CODEC_TYPE_INVALID,

    @CodecConfig.SampleRate
    val sampleRate: Int = CodecConfig.SAMPLE_RATE_NONE,

    @CodecConfig.BitsPerSample
    val bitsPerSample: Int = CodecConfig.BITS_PER_SAMPLE_NONE,

    val codecSpecific1: Int = 0,
    val codecSpecific2: Int = 0,
    val codecSpecific3: Int = 0,
    val codecSpecific4: Int = 0
)

class SBCCodecConfig: CodecConfig() {
    override fun getCodecType(): Int {
        return CODEC_TYPE_SBC
    }
}

class AACCodecConfig: CodecConfig() {
    companion object {
        @IntDef(
            value = [
                VARIABLE_BITRATE_SYSTEM_DEFAULT,
                VARIABLE_BITRATE_DISABLED,
                VARIABLE_BITRATE_VERY_LOW,
                VARIABLE_BITRATE_LOW,
                VARIABLE_BITRATE_MEDIUM,
                VARIABLE_BITRATE_HIGH,
                VARIABLE_BITRATE_VERY_HIGH
            ]
        )
        @Retention(AnnotationRetention.SOURCE)
        annotation class VariableBitrate

        const val VARIABLE_BITRATE_SYSTEM_DEFAULT = 1000000
        const val VARIABLE_BITRATE_DISABLED = 0
        const val VARIABLE_BITRATE_VERY_LOW = 1
        const val VARIABLE_BITRATE_LOW = 2
        const val VARIABLE_BITRATE_MEDIUM = 3
        const val VARIABLE_BITRATE_HIGH = 4
        const val VARIABLE_BITRATE_VERY_HIGH = 5
    }

    override fun getCodecType(): Int {
        return CODEC_TYPE_AAC
    }

    override fun toAndroid(): AndroidCodecConfig {
        val result = super.toAndroid()
        if (variableBitrate != VARIABLE_BITRATE_SYSTEM_DEFAULT) {
            return result.copy(codecSpecific1 = 128 + variableBitrate)
        }
        return result
    }

    override fun importFromAndroid(config: AndroidCodecConfig) {
        super.importFromAndroid(config)
        variableBitrate = VARIABLE_BITRATE_SYSTEM_DEFAULT
        if (config.codecSpecific1 >= 128 && config.codecSpecific1 <= 128 + VARIABLE_BITRATE_VERY_HIGH) {
            variableBitrate = config.codecSpecific1 - 128
        }
    }

    @VariableBitrate
    var variableBitrate = VARIABLE_BITRATE_SYSTEM_DEFAULT
}

class LDACCodecConfig: CodecConfig() {
    @IntDef(
        value = [
            BITRATE_SYSTEM_DEFAULT,
            BITRATE_ADAPTIVE,
            BITRATE_LOW,
            BITRATE_MID,
            BITRATE_HIGH
        ]
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class Bitrate

    companion object {
        const val BITRATE_SYSTEM_DEFAULT = 1000000
        const val BITRATE_ADAPTIVE = 3
        const val BITRATE_LOW = 2
        const val BITRATE_MID = 1
        const val BITRATE_HIGH = 0

        val BITRATES = arrayOf(
            BITRATE_ADAPTIVE,
            BITRATE_LOW,
            BITRATE_MID,
            BITRATE_HIGH
        )

        fun getFixedBitrate(@Bitrate bitrate: Int): Pair<Int, Int>? {
            return when (bitrate) {
                BITRATE_LOW -> Pair(303, 330)
                BITRATE_MID -> Pair(606, 660)
                BITRATE_HIGH -> Pair(909, 990)
                else -> null
            }
        }

        fun getFixedBitrate(@Bitrate bitrate: Int, @SampleRate sampleRate: Int): Int? {
            if (sampleRate == SAMPLE_RATE_44100 || sampleRate == SAMPLE_RATE_88200)
                return getFixedBitrate(bitrate)?.first
            if (sampleRate == SAMPLE_RATE_48000 || sampleRate == SAMPLE_RATE_96000)
                return getFixedBitrate(bitrate)?.second
            return null
        }
    }

    override fun getCodecType(): Int {
        return CODEC_TYPE_LDAC
    }

    override fun toAndroid(): AndroidCodecConfig {
        val result = super.toAndroid()
        if (bitrate != BITRATE_SYSTEM_DEFAULT) {
            // Backend uses codecSpecific1 % 10 as value, add 10 to avoid collision with 0
            return result.copy(codecSpecific1 = 10 + bitrate)
        }
        return result
    }

    override fun importFromAndroid(config: AndroidCodecConfig) {
        super.importFromAndroid(config)

        if (config.codecSpecific1 == 0) {
            bitrate = BITRATE_SYSTEM_DEFAULT
        } else {
            bitrate = config.codecSpecific1 % 10
            if (bitrate > BITRATE_ADAPTIVE)
                bitrate = BITRATE_SYSTEM_DEFAULT
        }
    }

    @Bitrate
    var bitrate = BITRATE_SYSTEM_DEFAULT
}

class AptXCodecConfig: CodecConfig() {
    override fun getCodecType(): Int {
        return CODEC_TYPE_APTX
    }
}
class AptXHDCodecConfig: CodecConfig() {
    override fun getCodecType(): Int {
        return CODEC_TYPE_APTX_HD
    }
}

class OpusCodecConfig: CodecConfig() {
    companion object {
        // The encode complexity is define by a number between 0 and 10 inclusive
        // (see https://datatracker.ietf.org/doc/html/rfc6716#section-2.1.5).
        // But the android implementation uses the value % 10 so the actual range is 0 to 9.
        @IntDef(value = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, ENCODE_COMPLEXITY_SYSTEM_DEFAULT])
        @Retention(AnnotationRetention.SOURCE)
        annotation class EncodeComplexity

        const val ENCODE_COMPLEXITY_SYSTEM_DEFAULT = 1000000
    }

    override fun getCodecType(): Int {
        return CODEC_TYPE_OPUS
    }

    override fun toAndroid(): AndroidCodecConfig {
        val result = super.toAndroid()
        if (encodeComplexity != ENCODE_COMPLEXITY_SYSTEM_DEFAULT) {
            // Backend uses codecSpecific3 % 10 as value, add 10 to avoid collision with 0
            return result.copy(codecSpecific3 = 10 + encodeComplexity)
        }
        return result
    }

    override fun importFromAndroid(config: AndroidCodecConfig) {
        super.importFromAndroid(config)

        if (config.codecSpecific3 == 0) {
            encodeComplexity = ENCODE_COMPLEXITY_SYSTEM_DEFAULT
        } else {
            encodeComplexity = config.codecSpecific3 % 10
        }
    }

    @EncodeComplexity
    var encodeComplexity = ENCODE_COMPLEXITY_SYSTEM_DEFAULT
}