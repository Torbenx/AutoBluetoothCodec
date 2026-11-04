@file:OptIn(ExperimentalTestApi::class)

package com.tt.autobluetoothcodec

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import android.view.View
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.state
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the preferred codec banner prototypes, like PreviewScreenshots, to build/outputs/banner. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class BannerPrototypes(private val shot: Shot) {
    class Shot(val variant: String, val style: PreferredBannerStyle?, val state: String, val data: MainScreenState,
               val config: String, val qualifiers: String, val fontScale: Float) {
        override fun toString() = "$variant - $state - $config"
    }

    companion object {
        private val STATES = listOf(
            "Nothing saved" to state(device()),
            "Active" to state(device(preferred = ldac())),
            "Differs" to state(device(ldac(CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24), preferred = ldac())),
            "Unsupported codec" to state(device(codec = null,
                android = AndroidCodecConfig(SampleData.CODEC_TYPE_LC3, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16),
                preferred = ldac())),
            "Not linked" to state(device(ldac(CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24),
                preferred = ldac(), associationRequired = true, associated = false)),
        )
        private const val PHONE = "w411dp-h1800dp-port-notnight-xhdpi"
        private val EDGES = listOf(
            Triple("Dark", "w411dp-h1800dp-port-night-xhdpi", 1f) to listOf("Differs", "Active"),
            Triple("Small phone, font 200%", "w360dp-h3000dp-port-notnight-xhdpi", 2f) to listOf("Differs", "Nothing saved"),
        )

        @JvmStatic
        @Parameters(name = "{0}")
        fun shots(): List<Shot> {
            val variants = mutableListOf<Pair<String, PreferredBannerStyle?>>("Current" to null)
            for (position in BannerPosition.entries)
                for (content in BannerContent.entries)
                    for (two in listOf(false, true))
                        variants.add("${position.name.lowercase()}-${content.name.lowercase()}-${if (two) 2 else 1}" to
                            PreferredBannerStyle(position, content, two))
            val shots = ArrayList<Shot>()
            for ((variant, style) in variants) {
                for ((name, data) in STATES)
                    shots.add(Shot(variant, style, name, data, "Phone", PHONE, 1f))
                for ((config, names) in EDGES)
                    for (name in names)
                        shots.add(Shot(variant, style, name, STATES.first { it.first == name }.second, config.first,
                            config.second, config.third))
            }
            return shots
        }
    }

    @Test
    fun render() {
        assumeTrue("Set RENDER_PREVIEWS to render the prototypes", System.getenv("RENDER_PREVIEWS") != null)
        RuntimeEnvironment.setQualifiers(shot.qualifiers)
        RuntimeEnvironment.setFontScale(shot.fontScale)
        runAndroidComposeUiTest<ComponentActivity> {
            setContent {
                CompositionLocalProvider(LocalPreferredBannerStyle provides shot.style) {
                    AppTheme { MainScreen(shot.data, NoMainActions, setOf(SampleData.ADDRESS)) }
                }
            }
            waitForIdle()
            val dir = File("build/outputs/banner")
            dir.mkdirs()
            val root = activity!!.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot")
                .getDeclaredMethod("takeScreenshot", View::class.java, Bitmap::class.java)
                .apply { isAccessible = true }
                .invoke(null, root, bitmap)
            File(dir, "$shot.png".replace(Regex("[^A-Za-z0-9 .,()%-]"), "_")).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
