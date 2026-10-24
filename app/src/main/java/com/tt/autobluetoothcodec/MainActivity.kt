package com.tt.autobluetoothcodec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass.Device
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap.Config
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.Space
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import java.util.TreeMap

class MainActivity : ComponentActivity(), BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "MainActivity"
        private const val COMPANION_TAG = "CompanionAssociation"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val COMPANION_DEVICE_ASSOCIATION_REQUEST_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handler = Handler(mainLooper)

        if (!PermissionUtil.testPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), PERMISSION_REQUEST_CODE)
        } else {
            onCreateWithPermission()
        }

        setContent { Foo() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && permissions.size == 1 && grantResults.size == 1) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onCreateWithPermission()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            COMPANION_DEVICE_ASSOCIATION_REQUEST_CODE -> {
                Log.d(COMPANION_TAG, "result code for association request: " + resultCode + when (resultCode) { RESULT_OK -> " (ok)" else -> " (not ok)"})
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun onCreateWithPermission() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter.getProfileProxy(this, this, BluetoothProfile.A2DP)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        a2dpProfile = proxy as BluetoothA2dp
        pullBluetoothState()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dpProfile = null
        pullBluetoothState()
    }

    fun doWithAssociation(device: BluetoothDevice, r: Runnable) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            Log.d(COMPANION_TAG, "companion feature not supported/required")
            r.run()
            return
        }

        val companionManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        if (companionManager.associations.contains(device.address)) {
            Log.d(COMPANION_TAG, "already associated with device")
            r.run()
            return
        }

        val filter = BluetoothDeviceFilter.Builder().setAddress(device.address).build()
        val request = AssociationRequest.Builder().setSingleDevice(true).addDeviceFilter(filter).build()
        companionManager.associate(request, object: CompanionDeviceManager.Callback() {
            override fun onFailure(message: CharSequence?) {
                Log.d(COMPANION_TAG, "companion association failed: " + message.toString())
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                Log.d(COMPANION_TAG, "device found, requesting association...")
                startIntentSenderForResult(intentSender, 0, null, 0, 0, 0)
            }
            override fun onAssociationCreated(info: AssociationInfo) {
                Log.d(COMPANION_TAG, "companion association created")
                r.run()
            }
        }, null)
    }

    data class DeviceData(
        val device: BluetoothDevice,
        val codecConfig: CodecConfig?,
        val configMasks: ArrayList<A2dpProfileUtil.ConfigMask>,
    )

    private var a2dpProfile: BluetoothA2dp? = null
    private var handler: Handler? = null

    private var connectedDevices by mutableStateOf(emptyList<DeviceData>())

    private fun pullBluetoothState() {
        if (a2dpProfile != null) {
            val devices = ArrayList<DeviceData>()
            for (device in a2dpProfile!!.connectedDevices) {
                Log.d(TAG, A2dpProfileUtil.getCurrentCodecConfig(a2dpProfile!!, device).toString())
                devices.add(
                    DeviceData(
                    device = device,
                    codecConfig = A2dpProfileUtil.getCurrentCodecConfig(a2dpProfile!!, device)?.let { CodecConfig.fromAndroid(it) },
                    configMasks = A2dpProfileUtil.getSelectableCodecConfigs(a2dpProfile!!, device)
                ))
            }
            connectedDevices = devices
        } else {
            connectedDevices = emptyList()
        }
    }

    private val myTimer = Runnable {
        pullBluetoothState()
        handler!!.postDelayed(timer(), 1000)
    }
    private fun timer(): Runnable { return myTimer }

    override fun onStart() {
        super.onStart()

        timer().run()
    }

    override fun onStop() {
        super.onStop()

        handler!!.removeCallbacks(timer())
    }

    private fun shortConfigString(config: CodecConfig): String {
        val codec = CodecConfig.getCodecName(config.getCodecType())?: "unknown codec"
        val sampleRate = CodecConfig.getSampleRate(config.sampleRate)
            ?.div(1000.0)?.toString()?.plus(" kHz")
            ?:"unknown sample rate"
        val bitsPerSample = CodecConfig.getBitsPerSample(config.bitsPerSample)
            ?.toString()?.plus(" bits/sample")
            ?:"unknown sample size"
        return "$codec, $sampleRate, $bitsPerSample"
    }

    @Composable
    private fun ConfigChip(text: String, selected: Boolean, enabled: Boolean, clickable: Boolean, onClick: () -> Unit) {
        val color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onBackground
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, color = color),
                enabled = clickable,
                onClick = onClick,
                modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.38f)
            ) {
                Text(
                    text,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = color,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    @Composable
    private fun ActiveConfigChip(text: String) {
        ConfigChip(text = text, selected = true, enabled = true, onClick = {}, clickable = false)
    }

    private data class StatusChipColors(
        val labelBackgroundColor: Color,
        val labelTextColor: Color,
        val buttonBackgroundColor: Color,
        val buttonTextColor: Color
    )

    @Composable
    private fun statusChipColors(
        labelBackgroundColor: Color,
        labelTextColor: Color,
        buttonBackgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
        buttonTextColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
    ): StatusChipColors {
        return StatusChipColors(labelBackgroundColor, labelTextColor, buttonBackgroundColor, buttonTextColor)
    }

    @Composable
    private fun StatusChip(
        text: String,
        colors: StatusChipColors,
        isButton: Boolean,
        onButtonClick: () -> Unit
    ) {
        val transition = updateTransition(isButton, "buttonState")
        val duration: @Composable (Transition.Segment<Boolean>.() -> Int) = {
            when {
                false isTransitioningTo true -> 500
                else -> 500
            }
        }

        val backgroundColor by transition.animateColor(
            label = "backgroundColor",
            transitionSpec = { tween(duration()) }
        ) { state -> if (state) colors.buttonBackgroundColor else colors.labelBackgroundColor }

        val textColor by transition.animateColor(
            label = "textColor",
            transitionSpec = { tween(duration()) }
        ) { state -> if (state) colors.buttonTextColor else colors.labelTextColor }

        val cornerRadius by transition.animateDp(
            label = "cornerRadius",
            transitionSpec = { tween(duration()) }
        ) { state -> if (state) 20.dp else 8.dp }

        val horizontalPadding = if (isButton) 24.dp else 16.dp

        val verticalPadding = if (isButton) 16.dp else 6.dp

        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Surface(
                shape = RoundedCornerShape(cornerRadius),
                color = backgroundColor,
                onClick = onButtonClick,
                enabled = isButton,
                modifier = Modifier
                    .animateContentSize(
                        animationSpec = tween(500),
                        alignment = Alignment.CenterEnd
                    )
            ) {
                Text(
                    text,
                    Modifier
                        .animateContentSize(
                            animationSpec = tween(500),
                            alignment = Alignment.Center
                        )
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    color = textColor,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    data class ConfigRowItem(
        val text: String,
        val value: Int,
        val enabled: Boolean
    )

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ConfigSelectRow(title: String, items: List<ConfigRowItem>, selectedValue: Int, onSelected: (Int) -> Unit) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in items) {
                    ConfigChip(
                        text = item.text,
                        selected = item.value == selectedValue,
                        enabled = item.enabled,
                        clickable = item.enabled,
                        onClick = { onSelected(item.value) },
                    )
                }
            }
        }
    }

    private fun isCodecTypeSupported(device: DeviceData, @CodecConfig.CodecType newCodecType: Int): Boolean {
        return device.configMasks.any { it.codecType == newCodecType }
    }

    private fun findSelectableConfig(device: DeviceData, @CodecConfig.CodecType newCodecType: Int): CodecConfig {
        val validConfigs = TreeMap<Int, CodecConfig>()
        val curConfig = device.codecConfig!!
        for (configMask in device.configMasks) {
            if (configMask.codecType != newCodecType)
                continue

            val newConfig = CodecConfig.makeCodecConfig(newCodecType)!!
            var score = 0

            if (configMask.isSampleRateSupported(curConfig.sampleRate)) {
                score += 2
                newConfig.sampleRate = curConfig.sampleRate
            } else {
                for (sampleRate in CodecConfig.SAMPLE_RATES) {
                    if (configMask.isSampleRateSupported(sampleRate)) {
                        newConfig.sampleRate = sampleRate
                        break
                    }
                }
            }
            if (configMask.isSampleSizeSupported(curConfig.bitsPerSample)) {
                score += 1
                newConfig.bitsPerSample = curConfig.bitsPerSample
            } else {
                for (bitsPerSample in CodecConfig.SAMPLE_SIZES) {
                    if (configMask.isSampleSizeSupported(bitsPerSample)) {
                        newConfig.bitsPerSample = bitsPerSample
                        break
                    }
                }
            }

            validConfigs.putIfAbsent(score, newConfig)
        }
        return validConfigs.lastEntry()!!.value
    }

    private fun isConfigSelectable(
        device: DeviceData,
        @CodecConfig.CodecType codecType: Int,
        @CodecConfig.SampleRate sampleRate: Int,
        @CodecConfig.BitsPerSample bitsPerSample: Int
    ): Boolean {
        for (configMask in device.configMasks) {
            if (configMask.codecType == codecType &&
                configMask.isSampleRateSupported(sampleRate) &&
                configMask.isSampleSizeSupported(bitsPerSample)) {
                return true
            }
        }
        return false
    }

    private fun isSampleRateSelectable(device: DeviceData, @CodecConfig.SampleRate sampleRate: Int): Boolean {
        val config = device.codecConfig!!
        return isConfigSelectable(device, config.getCodecType(), sampleRate, config.bitsPerSample)
    }

    private fun isSampleSizeSelectable(device: DeviceData, @CodecConfig.BitsPerSample bitsPerSample: Int): Boolean {
        val config = device.codecConfig!!
        return isConfigSelectable(device, config.getCodecType(), config.sampleRate, bitsPerSample)
    }

    @Composable
    private fun CodecConfigSelection(device: DeviceData, onConfigChange: (CodecConfig) -> Unit) {
        val config = device.codecConfig!!
        Column {
            ConfigSelectRow(
                "Codec",
                CodecConfig.CODEC_TYPES.map {
                    ConfigRowItem(CodecConfig.getCodecName(it)!!, it, isCodecTypeSupported(device, it))
                },
                config.getCodecType(),
                onSelected = {
                    if (it != config.getCodecType()) {
                        onConfigChange(findSelectableConfig(device, it))
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            ConfigSelectRow(
                "Sample Rate",
                CodecConfig.SAMPLE_RATES.map {
                    ConfigRowItem("${CodecConfig.getSampleRate(it)} Hz", it, isSampleRateSelectable(device, it))
                },
                config.sampleRate,
                onSelected = {
                    val newConfig = config.clone() as CodecConfig
                    newConfig.sampleRate = it
                    onConfigChange(newConfig)
                }
            )

            Spacer(Modifier.height(16.dp))
            ConfigSelectRow(
                "Bits per Sample",
                CodecConfig.SAMPLE_SIZES.map { ConfigRowItem("${CodecConfig.getBitsPerSample(it)}", it, isSampleSizeSelectable(device, it)) },
                config.bitsPerSample,
                onSelected = {
                    val newConfig = config.clone() as CodecConfig
                    newConfig.bitsPerSample = it
                    onConfigChange(newConfig)
                }
            )

            if (config is LDACCodecConfig) {
                Spacer(Modifier.height(16.dp))
                ConfigSelectRow(
                    "Bitrate",
                    LDACCodecConfig.BITRATES.map {
                        ConfigRowItem(LDACCodecConfig.getFixedBitrate(it, config.sampleRate)?.toString()?.plus(" kbps") ?: "Adaptive", it, true)
                    },
                    if (config.bitrate == LDACCodecConfig.BITRATE_SYSTEM_DEFAULT) LDACCodecConfig.BITRATE_ADAPTIVE else config.bitrate,
                    onSelected = {
                        val newConfig = config.clone() as LDACCodecConfig
                        newConfig.bitrate = it
                        onConfigChange(newConfig)
                    }
                )
            }

            // This is in the AOSP bluetooth source but appears to not be configurable
            if (false && config is AACCodecConfig) {
                Spacer(Modifier.height(16.dp))
                ConfigSelectRow(
                    "Variable Bitrate",
                    arrayListOf(
                        ConfigRowItem("Disabled", AACCodecConfig.VARIABLE_BITRATE_DISABLED, true),
                        ConfigRowItem("Very Low", AACCodecConfig.VARIABLE_BITRATE_VERY_LOW, true),
                        ConfigRowItem("Low", AACCodecConfig.VARIABLE_BITRATE_LOW, true),
                        ConfigRowItem("Medium", AACCodecConfig.VARIABLE_BITRATE_MEDIUM, true),
                        ConfigRowItem("High", AACCodecConfig.VARIABLE_BITRATE_HIGH, true),
                        ConfigRowItem("Very High", AACCodecConfig.VARIABLE_BITRATE_VERY_HIGH, true),
                    ),
                    config.variableBitrate,
                    onSelected = {
                        val newConfig = config.clone() as AACCodecConfig
                        newConfig.variableBitrate = it
                        onConfigChange(newConfig)
                    }
                )
            }

            // This is in the AOSP bluetooth source but appears to not be configurable
            if (false && config is OpusCodecConfig) {
                Spacer(Modifier.height(16.dp))
                ConfigSelectRow(
                    "Encode Complexity",
                    listOf(ConfigRowItem("Default", OpusCodecConfig.ENCODE_COMPLEXITY_SYSTEM_DEFAULT, true)) +
                    (0..9).map { ConfigRowItem(it.toString(), it, true) },
                    config.encodeComplexity,
                    onSelected = {
                        val newConfig = config.clone() as OpusCodecConfig
                        newConfig.encodeComplexity = it
                        onConfigChange(newConfig)
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DeviceCard(device: DeviceData, initialExtended: Boolean) {
        var extended by remember { mutableStateOf(initialExtended) }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shadowElevation = 6.dp,
            shape = CardDefaults.elevatedShape,
            onClick = { extended = !extended },
            enabled = true
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(device.device.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f).defaultMinSize(minWidth = 8.dp))

                    var isButton by remember { mutableStateOf(true) }

                    StatusChip(
                        if (isButton) "Use Preferred Codec" else "Preferred Codec",
                        statusChipColors(
                            labelBackgroundColor = Color(0xFFE8F5E9),
                            labelTextColor = Color(0xFF388E3C)
                        ),
                        isButton = isButton,
                        onButtonClick = { isButton = false }
                    )
                }
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(!extended) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val config = device.codecConfig!!
                        CodecConfig.getCodecName(config.getCodecType())?.let {
                            ActiveConfigChip(it)
                        }
                        CodecConfig.getSampleRate(config.sampleRate)?.let {
                            ActiveConfigChip("$it Hz")
                        }
                        CodecConfig.getBitsPerSample(config.bitsPerSample)?.let {
                            ActiveConfigChip("$it bits/sample")
                        }
                        if (config is LDACCodecConfig) {
                            if (config.bitrate == LDACCodecConfig.BITRATE_ADAPTIVE) {
                                ActiveConfigChip("Adaptive")
                            } else {
                                LDACCodecConfig.getFixedBitrate(config.bitrate, config.sampleRate)
                                    ?.let { ActiveConfigChip("$it kbps") }
                                    ?: LDACCodecConfig.getFixedBitrate(config.bitrate)
                                        ?.let { ActiveConfigChip("${it.first}/${it.second} kbps") }
                            }
                        }
                    }
                }
                AnimatedVisibility(extended) {
                    CodecConfigSelection(
                        device,
                        onConfigChange = {
                            Log.d(TAG, "onConfigChange: ${it.toAndroid()}")
                            doWithAssociation(device.device) {
                                if (a2dpProfile != null) {
                                    A2dpProfileUtil.setCodecConfig(a2dpProfile!!, device.device, it.toAndroid())
                                    pullBluetoothState()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    private fun Foo() {
        Box(Modifier.fillMaxWidth().fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
            Column {
                for (device in connectedDevices) {
                    DeviceCard(device, initialExtended = false)
                }
            }
        }
    }
}