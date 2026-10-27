package com.tt.autobluetoothcodec

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.UiTestData.ADDRESS
import com.tt.autobluetoothcodec.UiTestData.NAME
import com.tt.autobluetoothcodec.UiTestData.RecordingActions
import com.tt.autobluetoothcodec.UiTestData.codec
import com.tt.autobluetoothcodec.UiTestData.device
import com.tt.autobluetoothcodec.UiTestData.ldac
import com.tt.autobluetoothcodec.UiTestData.state
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** V2 to V6 of the test concept: what the main screen shows for a device, and what it asks for. */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()

    private fun show(state: MainScreenState) = compose.setContent { MainScreen(state, actions) }

    private fun show(device: DeviceData, showDevelopmentOptions: Boolean = false, canAssociate: Boolean = true,
                     associationInProgress: Boolean = false) =
        show(state(device, showDevelopmentOptions = showDevelopmentOptions, canAssociate = canAssociate,
            associationInProgress = associationInProgress))

    private fun text(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    /** Taps the device's name, the card's center may be covered by its status chip. */
    private fun expand() {
        text(NAME).performClick()
        compose.waitForIdle()
    }

    /** A chip of the expanded card, scrolled into view since the card may not fit on the screen. */
    private fun chip(text: String) = compose.onNodeWithText(text).performScrollTo()

    // V2

    @Test
    fun collapsedCardShowsTheCodec() {
        show(device())
        text(NAME).assertExists()
        text("LDAC").assertExists()
        text("96000 Hz").assertExists()
        text("32 bits/sample").assertExists()
        // The system default bitrate has no chip
        text("Adaptive").assertDoesNotExist()
    }

    @Test
    fun collapsedCardShowsTheLdacBitrate() {
        show(device(ldac(bitrate = LDACCodecConfig.BITRATE_ADAPTIVE)))
        text("Adaptive").assertExists()
    }

    @Test
    fun collapsedCardShowsAFixedLdacBitrateForTheSampleRate() {
        show(device(ldac(sampleRate = CodecConfig.SAMPLE_RATE_44100, bitrate = LDACCodecConfig.BITRATE_HIGH)))
        text("909 kbps").assertExists()
    }

    @Test
    fun unsupportedCodec() {
        show(device(codec = null, android = AndroidCodecConfig(7, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)))
        text("Unsupported codec").assertExists()
    }

    @Test
    fun noCodecInformation() {
        show(device(codec = null, android = null))
        text("No codec information").assertExists()
    }

    // V3

    @Test
    fun saveAsPreferredPassesTheCurrentCodec() {
        show(device(preferred = ldac(sampleRate = CodecConfig.SAMPLE_RATE_48000)))
        compose.onNodeWithTag("status_chip").assertTextEquals("Save As Preferred Codec").assertIsEnabled().performClick()
        assertEquals(listOf("savePreferred $ADDRESS LDAC, 96 kHz, 32-bit"), actions.calls)
    }

    @Test
    fun theActivePreferredCodecIsALabel() {
        show(device(preferred = ldac()))
        compose.onNodeWithTag("status_chip").assertTextEquals("Preferred Codec").assertIsNotEnabled()
    }

    @Test
    fun noStatusChipForAnUnreadableOrUnknownCodec() {
        show(state(
            device(statusReadable = false, codec = null, android = null, address = ADDRESS),
        ))
        compose.onNodeWithTag("status_chip").assertDoesNotExist()
    }

    @Test
    fun noStatusChipForAnUnknownCodec() {
        show(device(codec = null, android = AndroidCodecConfig(7)))
        compose.onNodeWithTag("status_chip").assertDoesNotExist()
    }

    // V4

    @Test
    fun noMenuWithoutPreferredCodec() {
        show(device())
        compose.onNodeWithTag("device_menu").assertDoesNotExist()
    }

    @Test
    fun menuRestoresAndDeletesTheInactivePreferredCodec() {
        val preferred = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)
        show(device(preferred = preferred))
        compose.onNodeWithTag("device_menu").performClick()
        compose.onNodeWithTag("delete_preferred").assertExists()
        compose.onNodeWithTag("unlink_device").assertDoesNotExist()
        compose.onNodeWithTag("restore_preferred").performClick()
        compose.onNodeWithTag("device_menu").performClick()
        compose.onNodeWithTag("delete_preferred").performClick()
        assertEquals(listOf("restorePreferred $ADDRESS AAC, 44.1 kHz, 16-bit", "deletePreferred $ADDRESS"), actions.calls)
    }

    @Test
    fun menuOffersNoRestoreWhileThePreferredCodecIsActive() {
        show(device(preferred = ldac()))
        compose.onNodeWithTag("device_menu").performClick()
        compose.onNodeWithTag("restore_preferred").assertDoesNotExist()
        compose.onNodeWithTag("delete_preferred").assertExists()
    }

    @Test
    fun debugBuildsCanUnlink() {
        show(device(), showDevelopmentOptions = true)
        compose.onNodeWithTag("device_menu").performClick()
        compose.onNodeWithTag("delete_preferred").assertDoesNotExist()
        compose.onNodeWithTag("unlink_device").performClick()
        assertEquals(listOf("unlink $ADDRESS"), actions.calls)
    }

    @Test
    fun debugBuildsOnlyUnlinkLinkedDevices() {
        show(device(associated = false), showDevelopmentOptions = true)
        compose.onNodeWithTag("device_menu").assertDoesNotExist()
    }

    // V5

    @Test
    fun anUnreadableCodecAsksForALink() {
        show(device(statusReadable = false, codec = null, android = null, associated = false, associationRequired = true))
        text("Link $NAME to see and change its codec.").assertExists()
        compose.onNodeWithTag("link_device").assertIsEnabled().performClick()
        assertEquals(listOf("link $ADDRESS"), actions.calls)
        text("LDAC").assertDoesNotExist()
    }

    @Test
    fun aPreferredCodecWithoutLinkAsksForOneNextToTheCodec() {
        // The API 34 and 35 state: readable, but a link is needed to apply the preferred codec
        show(device(associated = false, associationRequired = true, preferred = ldac()))
        text("Link $NAME so its preferred codec is applied when it connects.").assertExists()
        text("LDAC").assertExists()
    }

    @Test
    fun noLinkPromptWithoutPreferredCodec() {
        show(device(associated = false, associationRequired = true))
        compose.onNodeWithTag("link_device").assertDoesNotExist()
    }

    @Test
    fun phonesWithoutTheCompanionFeatureCantLink() {
        show(device(statusReadable = false, codec = null, android = null, associated = false, associationRequired = true),
            canAssociate = false)
        text("This phone can't link devices, so the codec of $NAME can't be changed.").assertExists()
        compose.onNodeWithTag("link_device").assertDoesNotExist()
    }

    @Test
    fun allowIsDisabledWhileLinking() {
        show(device(statusReadable = false, codec = null, android = null, associated = false, associationRequired = true),
            associationInProgress = true)
        compose.onNodeWithTag("link_device").assertIsNotEnabled()
    }

    // V6

    @Test
    fun expandedCardEnablesTheOfferedOptions() {
        show(device())
        expand()
        text("Codec").assertExists()
        chip("LDAC").assertIsEnabled()
        chip("aptX HD").assertIsEnabled()
        chip("Opus").assertIsNotEnabled()
        chip("44100 Hz").assertIsEnabled()
        chip("16").assertIsEnabled()
        // Collapsed chips are gone, and the bitrate row shows the system default as adaptive
        text("96000 Hz").assertExists()
        text("Bitrate").assertExists()
    }

    @Test
    fun choosingACodecKeepsTheRateAndSizeIfSupported() {
        // LDAC also supports 48 kHz and 24-bit, so they stay
        show(device(codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24)))
        expand()
        chip("LDAC").performClick()
        assertEquals(listOf("setCodec $ADDRESS LDAC, 48 kHz, 24-bit"), actions.calls)
    }

    @Test
    fun choosingACodecTakesTheFirstSupportedRateAndSize() {
        show(device())
        expand()
        chip("aptX HD").performClick()
        chip("SBC").performClick()
        assertEquals(listOf(
            "setCodec $ADDRESS aptX HD, 44.1 kHz, 24-bit",
            "setCodec $ADDRESS SBC, 44.1 kHz, 16-bit",
        ), actions.calls)
    }

    @Test
    fun choosingACodecPrefersTheCapabilityThatSupportsBoth() {
        val masks = listOf(
            A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_32),
            A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_16),
            A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_32),
        )
        show(device(masks = masks))
        expand()
        chip("AAC").performClick()
        assertEquals(listOf("setCodec $ADDRESS AAC, 96 kHz, 32-bit"), actions.calls)
    }

    @Test
    fun choosingTheCurrentCodecDoesNothing() {
        show(device())
        expand()
        chip("LDAC").performClick()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun choosingARateSizeOrBitrateChangesOnlyThat() {
        show(device())
        expand()
        chip("48000 Hz").performClick()
        chip("24").performClick()
        chip("990 kbps").performClick()
        assertEquals(listOf(
            "setCodec $ADDRESS LDAC, 48 kHz, 32-bit",
            "setCodec $ADDRESS LDAC, 96 kHz, 24-bit",
            "setCodec $ADDRESS LDAC, 96 kHz, 32-bit, 990 kbps",
        ), actions.calls)
    }

    @Test
    fun ratesAndSizesTheCodecDoesntOfferAreDisabled() {
        show(device(codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24)))
        expand()
        chip("44100 Hz").assertIsEnabled()
        chip("88200 Hz").assertIsNotEnabled()
        chip("16").assertIsNotEnabled()
        chip("32").assertIsNotEnabled()
        text("Bitrate").assertDoesNotExist()
    }
}
