package com.tt.autobluetoothcodec

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodecConfigSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun preferredCodecStore_readsFormatWrittenWithOrgJson() {
        // What PreferredCodecStore wrote before it used kotlinx.serialization
        val stored = """{"codecType":4,"sampleRate":8,"bitsPerSample":2,"codecSpecific1":13,""" +
                """"codecSpecific2":0,"codecSpecific3":0,"codecSpecific4":0}"""
        val config = PreferredCodecStore.json.decodeFromString<AndroidCodecConfig>(stored)
        assertEquals(AndroidCodecConfig(4, 8, 2, 13, 0, 0, 0), config)
        assertEquals(stored, PreferredCodecStore.json.encodeToString(config))
    }

    @Test
    fun codecConfig_roundTripsEveryCodec() {
        val configs = listOf(
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16, codecSpecific1 = 128 + AACCodecConfig.VARIABLE_BITRATE_HIGH),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24, codecSpecific1 = 10 + LDACCodecConfig.BITRATE_ADAPTIVE),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_88200, CodecConfig.BITS_PER_SAMPLE_32),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_APTX, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_OPUS, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16, codecSpecific3 = 10 + 7),
        )
        for (android in configs) {
            val config = CodecConfig.fromAndroid(android)!!
            val text = json.encodeToString<CodecConfig>(config)
            val decoded = json.decodeFromString<CodecConfig>(text)
            assertEquals(text, config, decoded)
            assertEquals(text, android, decoded.toAndroid())
        }
    }

    @Test
    fun codecConfig_isWrittenReadably() {
        val config = CodecConfig.fromAndroid(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24,
            codecSpecific1 = 10 + LDACCodecConfig.BITRATE_ADAPTIVE))!!
        val obj = json.parseToJsonElement(json.encodeToString<CodecConfig>(config)).jsonObject
        assertEquals("LDAC", obj["type"]!!.jsonPrimitive.content)
        assertEquals(96000, obj["sampleRate"]!!.jsonPrimitive.int)
        assertEquals(24, obj["bitsPerSample"]!!.jsonPrimitive.int)
        assertEquals("ADAPTIVE", obj["bitrate"]!!.jsonPrimitive.content)
    }

    @Test
    fun codecConfig_omitsSystemDefaults() {
        val config = CodecConfig.fromAndroid(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24))!!
        val obj: JsonObject = json.parseToJsonElement(json.encodeToString<CodecConfig>(config)).jsonObject
        assertFalse(obj.containsKey("bitrate"))
    }
}
