package com.tt.autobluetoothcodec

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.SampleData.ADDRESS
import com.tt.autobluetoothcodec.SampleData.codec
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.state
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V4 on a small phone with the largest font, where the labels of three buttons don't fit in a third of the row:
 * the one-slot buttons only show their icons, but keep their names for TalkBack, while Save and "Active", which
 * are wider, keep their labels. Only on the JVM, a test can't change the font size of a phone. It needs the
 * native graphics to measure the labels like a phone does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
class PreferredActionsNarrowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showExpanded(device: DeviceData) {
        compose.setContent { AppTheme { MainScreen(state(device), UiTestData.RecordingActions(), setOf(ADDRESS)) } }
    }

    private fun assertIconOnly(tag: String, name: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        compose.onNode(hasText(name), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(tag).assertContentDescriptionEquals(name)
    }

    @Test
    fun threeButtonsOnlyShowTheirIcons() {
        showExpanded(device(preferred = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)))
        assertIconOnly("save_preferred", "Save")
        assertIconOnly("restore_preferred", "Restore")
        assertIconOnly("clear_preferred", "Clear")
    }

    @Test
    fun activeKeepsItsLabelNextToTheClearIcon() {
        showExpanded(device(preferred = ldac()))
        compose.onNodeWithText("Active", useUnmergedTree = true).assertExists()
        assertIconOnly("clear_preferred", "Clear")
    }

    @Test
    fun aLoneSaveKeepsItsLabel() {
        showExpanded(device())
        compose.onNodeWithText("Save", useUnmergedTree = true).assertExists()
    }
}
