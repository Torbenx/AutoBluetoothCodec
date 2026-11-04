package com.tt.autobluetoothcodec

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.SampleData.ADDRESS
import com.tt.autobluetoothcodec.SampleData.codec
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.state
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the preferred codec banner's button goes: at the end of the title on a phone, below the codec on a small phone
 * with the largest font, where the title would otherwise wrap mid-word. Only on the JVM, a test can't change the font
 * size of a phone. It needs the native graphics to measure the labels like a phone does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreferredBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferred = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)

    private fun showExpanded() {
        compose.setContent {
            AppTheme { MainScreen(state(device(preferred = preferred)), UiTestData.RecordingActions(), setOf(ADDRESS)) }
        }
    }

    private fun restoreTop() = compose.onNodeWithTag("restore_preferred").getUnclippedBoundsInRoot().top

    private fun codecTextBounds() =
        compose.onNodeWithText(preferred.describe(), useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun onAPhoneRestoreIsAtTheEndOfTheTitle() {
        showExpanded()
        assertTrue(restoreTop() < codecTextBounds().top)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 2f)
    fun withTheLargestFontOnASmallPhoneRestoreIsBelowTheCodec() {
        showExpanded()
        compose.onNodeWithText("Preferred codec", useUnmergedTree = true).assertExists()
        assertTrue(restoreTop() >= codecTextBounds().bottom)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 2f)
    fun withTheLargestFontOnASmallPhoneSaveKeepsItsLabel() {
        showExpanded()
        compose.onNodeWithText("Save as preferred", useUnmergedTree = true).assertExists()
    }
}
