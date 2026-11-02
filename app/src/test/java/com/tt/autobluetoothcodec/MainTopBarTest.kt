package com.tt.autobluetoothcodec

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.state
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The title gets shorter, then only the arrow is left, when the bar is too narrow for the list's name. */
@RunWith(AndroidJUnit4::class)
// Measures text like a phone, Robolectric's default graphics make it a few pixels wide
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainTopBarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show() = compose.setContent { AppTheme { MainScreen(state(device()), NoMainActions) } }

    private fun title(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun aPhoneShowsTheListsName() {
        show()
        title("Connected devices").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 2f)
    fun theLargestFontOnASmallPhoneShowsTheShortName() {
        show()
        title("Connected devices").assertDoesNotExist()
        title("Connected").assertExists()
    }

    @Test
    @Config(qualifiers = "w240dp-h411dp-port", fontScale = 2f)
    fun aNarrowSplitScreenAtTheLargestFontOnlyShowsTheArrow() {
        show()
        title("Connected devices").assertDoesNotExist()
        title("Connected").assertDoesNotExist()
        // Still named for screen readers
        compose.onNodeWithTag("list_toggle").assertContentDescriptionEquals("Connected devices")
    }
}
