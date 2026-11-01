package com.tt.autobluetoothcodec

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.sony
import com.tt.autobluetoothcodec.SampleData.state

private const val PHONE = "spec:width=411dp,height=891dp"
private const val SMALL_PHONE = "spec:width=360dp,height=640dp"

/** A screen on a phone, in dark mode, with large fonts, in landscape, on a small phone, a foldable and a tablet. */
@Preview(name = "Phone", device = PHONE, showSystemUi = true)
@Preview(name = "Phone, dark", device = PHONE, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Phone, font 200%", device = PHONE, showSystemUi = true, fontScale = 2f)
@Preview(name = "Phone, landscape", device = "$PHONE,orientation=landscape", showSystemUi = true)
@Preview(name = "Small phone", device = SMALL_PHONE, showSystemUi = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showSystemUi = true)
@Preview(name = "Tablet", device = Devices.TABLET, showSystemUi = true)
internal annotation class ScreenPreviews

private object NoMainActions : MainScreenActions {
    override fun requestPermission() {}
    override fun openAppSettings() {}
    override fun openLog() {}
    override fun openAbout() {}
    override fun setCodec(address: String, config: CodecConfig) {}
    override fun savePreferred(address: String, config: CodecConfig) {}
    override fun restorePreferred(address: String, config: CodecConfig) {}
    override fun deletePreferred(address: String) {}
    override fun link(address: String) {}
    override fun unlink(address: String) {}
}

private object NoLogActions : LogScreenActions {
    override fun back() {}
    override fun share(mode: LogExport.Mode) {}
    override fun clear() {}
    override fun setLoggingEnabled(enabled: Boolean) {}
    override fun openLogFile() {}
}

private object NoAboutActions : AboutScreenActions {
    override fun back() {}
    override fun openUrl(url: String) {}
    override fun openLicenses() {}
}

/** The Qudelix with its preferred codec and the Sony. */
private val twoDevices = state(device(preferred = ldac()), sony())

/** The main screen's states besides [twoDevices]. */
private class MainScreenStates : PreviewParameterProvider<MainScreenState> {
    override val values = sequenceOf(
        state(),
        state(permissionState = PermissionState.DENIED),
        state(permissionState = PermissionState.PERMANENTLY_DENIED),
        // Since API 36 the codec status can only be read once the device is linked
        state(device(statusReadable = false, associationRequired = true, associated = false)),
        state(device(associationRequired = true, associated = false), associationInProgress = true),
        state(
            device(codec = null, android = null),
            device(name = "A headphone with a much longer name than usual", address = "00:1A:7D:DA:71:13"),
            showDevelopmentOptions = true,
        ),
    )
}

/** What the log screen shows, as (events, logging enabled, file name). */
private class LogScreenStates : PreviewParameterProvider<Triple<List<LogEvent>?, Boolean, String?>> {
    override val values = sequenceOf(
        Triple(null, true, null),
        Triple(emptyList(), true, null),
        Triple(SampleData.log(), false, null),
        Triple(SampleData.log(), true, "event_log_2026-10-27.jsonl"),
    )
}

@ScreenPreviews
@Composable
private fun MainScreenPreview() = AppTheme { MainScreen(twoDevices, NoMainActions) }

@Preview(name = "Main screen states", device = PHONE, showSystemUi = true)
@Composable
private fun MainScreenStatesPreview(@PreviewParameter(MainScreenStates::class) state: MainScreenState) =
    AppTheme { MainScreen(state, NoMainActions) }

@ScreenPreviews
@Composable
private fun LogScreenPreview() = AppTheme { LogScreen(SampleData.log(), SampleData.TODAY, NoLogActions) }

@Preview(name = "Log screen states", device = PHONE, showSystemUi = true)
@Composable
private fun LogScreenStatesPreview(@PreviewParameter(LogScreenStates::class) state: Triple<List<LogEvent>?, Boolean, String?>) =
    AppTheme {
        LogScreen(state.first, SampleData.TODAY, NoLogActions, loggingEnabled = state.second, fileName = state.third)
    }

@ScreenPreviews
@Composable
private fun AboutScreenPreview() = AppTheme { AboutScreen("1.0-9057ca4", NoAboutActions) }
