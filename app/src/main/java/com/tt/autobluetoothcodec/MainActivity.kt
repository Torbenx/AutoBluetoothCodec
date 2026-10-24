package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.TreeMap

class MainActivity : ComponentActivity(), BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "MainActivity"
        private const val COMPANION_TAG = "CompanionAssociation"

        // Not const, a constant false condition would prevent the smart casts in the guarded blocks
        private val SHOW_UNCONFIGURABLE_OPTIONS = false
    }

    private enum class PermissionState { GRANTED, DENIED, PERMANENTLY_DENIED }

    // Development options are only shown in debuggable builds
    private val showDevelopmentOptions by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private var permissionState by mutableStateOf(PermissionState.DENIED)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            updatePermissionState()
        } else if (Build.VERSION.SDK_INT >= 31
            && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            // Denied without a rationale means the system won't show the permission dialog again
            permissionState = PermissionState.PERMANENTLY_DENIED
        } else {
            permissionState = PermissionState.DENIED
        }
    }

    private val associationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        Log.d(COMPANION_TAG, "result code for association request: " + result.resultCode + when (result.resultCode) { RESULT_OK -> " (ok)" else -> " (not ok)"})
        // Since API 33 onAssociationCreated finishes a successful request, before that only the result is reported
        if (result.resultCode == RESULT_OK && Build.VERSION.SDK_INT < 33) {
            finishAssociation(success = true, message = null)
        } else if (result.resultCode != RESULT_OK) {
            finishAssociation(success = false, message = null)
        }
        pullBluetoothState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Since API 35 the window is always edge-to-edge. There is no dark theme yet, so the system
        // bars have to stay light even if the system is in dark mode. The navigation bar keeps the
        // translucent white scrim enableEdgeToEdge uses by default for three-button navigation.
        val navigationBarScrim = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(navigationBarScrim, navigationBarScrim)
        )
        super.onCreate(savedInstanceState)

        handler = Handler(mainLooper)
        CompanionPresence.syncAll(this)

        updatePermissionState()
        // After a configuration change the result of a pending request is delivered to this instance
        if (permissionState != PermissionState.GRANTED && savedInstanceState == null) {
            requestBluetoothPermission()
        }

        setContent { Foo() }
    }

    override fun onDestroy() {
        super.onDestroy()
        a2dpProfile?.let { closeProfileProxy(it) }
        a2dpProfile = null
    }

    private fun updatePermissionState() {
        if (PermissionUtil.testPermission(this)) {
            permissionState = PermissionState.GRANTED
            connectProfile()
        } else if (permissionState == PermissionState.GRANTED) {
            permissionState = PermissionState.DENIED
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun connectProfile() {
        if (profileRequested)
            return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Log.w(TAG, "device has no Bluetooth adapter")
            return
        }
        profileRequested = adapter.getProfileProxy(this, this, BluetoothProfile.A2DP)
    }

    private fun closeProfileProxy(profile: BluetoothA2dp) {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.closeProfileProxy(BluetoothProfile.A2DP, profile)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        if (isDestroyed) {
            (proxy as? BluetoothA2dp)?.let { closeProfileProxy(it) }
            return
        }
        a2dpProfile = proxy as BluetoothA2dp
        pullBluetoothState()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dpProfile = null
        pullBluetoothState()
    }

    private var associationInProgress by mutableStateOf(false)
    private var onAssociated: (() -> Unit)? = null

    private fun requestAssociation(device: BluetoothDevice, onSuccess: () -> Unit) {
        if (associationInProgress) {
            Log.d(COMPANION_TAG, "association request already in progress")
            return
        }
        if (!PermissionUtil.canAssociate(this)) {
            Log.d(COMPANION_TAG, "companion association not supported")
            return
        }

        val companionManager = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothDeviceFilter.Builder().setAddress(device.address).build()
        val request = AssociationRequest.Builder().setSingleDevice(true).addDeviceFilter(filter).build()
        associationInProgress = true
        onAssociated = {
            CompanionPresence.update(this, device)
            onSuccess()
        }
        if (Build.VERSION.SDK_INT < 33) {
            // AssociationInfo and the callbacks using it only exist since API 33
            companionManager.associate(request, object: CompanionDeviceManager.Callback() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(COMPANION_TAG, "device found, requesting association...")
                    associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onFailure(error: CharSequence?) {
                    Log.d(COMPANION_TAG, "companion association failed: $error")
                    finishAssociation(success = false, message = error)
                }
            }, handler)
            return
        }
        companionManager.associate(request, mainExecutor, object: CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                Log.d(COMPANION_TAG, "device found, requesting association...")
                associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(info: AssociationInfo) {
                Log.d(COMPANION_TAG, "companion association created")
                finishAssociation(success = true, message = null)
            }

            override fun onFailure(error: CharSequence?) {
                Log.d(COMPANION_TAG, "companion association failed: $error")
                finishAssociation(success = false, message = error)
            }
        })
    }

    private fun finishAssociation(success: Boolean, message: CharSequence?) {
        if (!associationInProgress)
            return
        associationInProgress = false
        val action = onAssociated
        onAssociated = null
        if (success) {
            action?.invoke()
        } else {
            val text = "Device wasn't linked" + (message?.let { ": $it" } ?: "")
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyCodecConfig(device: BluetoothDevice, config: CodecConfig, mayAssociate: Boolean = true) {
        Log.d(TAG, "applyCodecConfig: ${config.toAndroid()}")
        val profile = a2dpProfile ?: return
        if (mayAssociate && PermissionUtil.isAssociationRequired(this, device)) {
            requestAssociation(device) { applyCodecConfig(device, config, mayAssociate = false) }
            return
        }
        try {
            A2dpProfileUtil.setCodecConfig(profile, device, config.toAndroid())
        } catch (e: SecurityException) {
            // An updated Bluetooth module may require an association on older versions as well
            if (mayAssociate && PermissionUtil.canAssociate(this) && !PermissionUtil.hasAssociation(this, device)) {
                requestAssociation(device) { applyCodecConfig(device, config, mayAssociate = false) }
            } else {
                Log.w(TAG, "Bluetooth refused to change the codec", e)
                Toast.makeText(this, "Bluetooth didn't allow changing the codec", Toast.LENGTH_SHORT).show()
            }
            return
        } catch (e: Exception) {
            Log.e(TAG, "changing the codec failed", e)
            Toast.makeText(this, "Changing the codec failed", Toast.LENGTH_SHORT).show()
            return
        }
        pullBluetoothState()
    }

    private fun savePreferredConfig(device: BluetoothDevice, config: CodecConfig, mayAssociate: Boolean = true) {
        // Without an association the preferred codec can't be applied when the device connects
        if (mayAssociate && (PermissionUtil.isAssociationRequired(this, device)
                    || PermissionUtil.isAssociationRequiredForAutoApply(this, device))) {
            requestAssociation(device) { savePreferredConfig(device, config, mayAssociate = false) }
            return
        }
        PreferredCodecStore.set(this, device, config)
        CompanionPresence.update(this, device)
        pullBluetoothState()
    }

    private fun deletePreferredConfig(device: BluetoothDevice) {
        PreferredCodecStore.remove(this, device)
        CompanionPresence.update(this, device)
        pullBluetoothState()
    }

    private fun unlinkDevice(device: BluetoothDevice) {
        if (PermissionUtil.removeAssociation(this, device)) {
            Log.d(COMPANION_TAG, "companion association removed")
            Toast.makeText(this, "Device unlinked", Toast.LENGTH_SHORT).show()
        }
        pullBluetoothState()
    }

    data class DeviceData(
        val device: BluetoothDevice,
        val name: String,
        val androidCodecConfig: AndroidCodecConfig?,
        // null if the codec is unknown to this app or no codec status is available
        val codecConfig: CodecConfig?,
        val configMasks: ArrayList<A2dpProfileUtil.ConfigMask>,
        // false if the Bluetooth stack refused to report the codec status
        val statusReadable: Boolean,
        val associationRequired: Boolean,
        val associated: Boolean,
        val preferredConfig: CodecConfig?,
    )

    private var a2dpProfile: BluetoothA2dp? = null
    private var profileRequested = false
    private var handler: Handler? = null

    private var connectedDevices by mutableStateOf(emptyList<DeviceData>())

    private fun pullBluetoothState() {
        val profile = a2dpProfile
        if (profile == null) {
            connectedDevices = emptyList()
            return
        }
        val devices = try {
            profile.connectedDevices
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to list connected devices", e)
            emptyList()
        }
        connectedDevices = devices.map { readDeviceData(profile, it) }
    }

    private fun readDeviceData(profile: BluetoothA2dp, device: BluetoothDevice): DeviceData {
        val name = device.name ?: device.address
        val associationRequired = PermissionUtil.isAssociationRequired(this, device)
                || PermissionUtil.isAssociationRequiredForAutoApply(this, device)
        val associated = PermissionUtil.hasAssociation(this, device)
        val preferredConfig = PreferredCodecStore.get(this, device)
        try {
            val androidConfig = A2dpProfileUtil.getCurrentCodecConfig(profile, device)
            Log.d(TAG, androidConfig.toString())
            return DeviceData(
                device = device,
                name = name,
                androidCodecConfig = androidConfig,
                codecConfig = androidConfig?.let { CodecConfig.fromAndroid(it) },
                configMasks = A2dpProfileUtil.getSelectableCodecConfigs(profile, device),
                statusReadable = true,
                associationRequired = associationRequired,
                associated = associated,
                preferredConfig = preferredConfig
            )
        } catch (e: SecurityException) {
            // Since API 36 reading the codec status requires an association as well
            Log.d(TAG, "Bluetooth refused to report the codec status of $name")
            return DeviceData(device, name, null, null, ArrayList(), statusReadable = false, associationRequired, associated, preferredConfig)
        } catch (e: Exception) {
            Log.e(TAG, "reading the codec status of $name failed", e)
            return DeviceData(device, name, null, null, ArrayList(), statusReadable = true, associationRequired, associated, preferredConfig)
        }
    }

    private val myTimer = Runnable {
        pullBluetoothState()
        handler!!.postDelayed(timer(), 1000)
    }
    private fun timer(): Runnable { return myTimer }

    override fun onStart() {
        super.onStart()

        // The permission may have been granted in the app settings meanwhile
        updatePermissionState()
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
        val curSampleRate = device.androidCodecConfig?.sampleRate ?: CodecConfig.SAMPLE_RATE_NONE
        val curBitsPerSample = device.androidCodecConfig?.bitsPerSample ?: CodecConfig.BITS_PER_SAMPLE_NONE
        for (configMask in device.configMasks) {
            if (configMask.codecType != newCodecType)
                continue

            val newConfig = CodecConfig.makeCodecConfig(newCodecType)!!
            var score = 0

            if (configMask.isSampleRateSupported(curSampleRate)) {
                score += 2
                newConfig.sampleRate = curSampleRate
            } else {
                for (sampleRate in CodecConfig.SAMPLE_RATES) {
                    if (configMask.isSampleRateSupported(sampleRate)) {
                        newConfig.sampleRate = sampleRate
                        break
                    }
                }
            }
            if (configMask.isSampleSizeSupported(curBitsPerSample)) {
                score += 1
                newConfig.bitsPerSample = curBitsPerSample
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

    private fun isSampleRateSelectable(device: DeviceData, config: CodecConfig, @CodecConfig.SampleRate sampleRate: Int): Boolean {
        return isConfigSelectable(device, config.getCodecType(), sampleRate, config.bitsPerSample)
    }

    private fun isSampleSizeSelectable(device: DeviceData, config: CodecConfig, @CodecConfig.BitsPerSample bitsPerSample: Int): Boolean {
        return isConfigSelectable(device, config.getCodecType(), config.sampleRate, bitsPerSample)
    }

    @Composable
    private fun CodecConfigSelection(device: DeviceData, onConfigChange: (CodecConfig) -> Unit) {
        Column {
            val currentCodecType = device.codecConfig?.getCodecType() ?: CodecConfig.CODEC_TYPE_INVALID
            ConfigSelectRow(
                "Codec",
                CodecConfig.CODEC_TYPES.map {
                    ConfigRowItem(CodecConfig.getCodecName(it)!!, it, isCodecTypeSupported(device, it))
                },
                currentCodecType,
                onSelected = {
                    if (it != currentCodecType) {
                        onConfigChange(findSelectableConfig(device, it))
                    }
                }
            )

            // The current codec is unknown to this app, so only offer switching to a known one
            val config = device.codecConfig ?: return@Column

            Spacer(Modifier.height(16.dp))
            ConfigSelectRow(
                "Sample Rate",
                CodecConfig.SAMPLE_RATES.map {
                    ConfigRowItem("${CodecConfig.getSampleRate(it)} Hz", it, isSampleRateSelectable(device, config, it))
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
                CodecConfig.SAMPLE_SIZES.map { ConfigRowItem("${CodecConfig.getBitsPerSample(it)}", it, isSampleSizeSelectable(device, config, it)) },
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
            if (SHOW_UNCONFIGURABLE_OPTIONS && config is AACCodecConfig) {
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
            if (SHOW_UNCONFIGURABLE_OPTIONS && config is OpusCodecConfig) {
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
                    Text(device.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f).defaultMinSize(minWidth = 8.dp))

                    val preferredConfig = device.preferredConfig
                    val currentConfig = device.codecConfig
                    val isPreferredActive = preferredConfig != null && preferredConfig == currentConfig

                    // A codec unknown to this app can't be saved as preference
                    if (device.statusReadable && currentConfig != null) {
                        StatusChip(
                            if (isPreferredActive) "Preferred Codec" else "Save As Preferred Codec",
                            statusChipColors(
                                labelBackgroundColor = Color(0xFFE8F5E9),
                                labelTextColor = Color(0xFF388E3C)
                            ),
                            isButton = !isPreferredActive,
                            onButtonClick = { savePreferredConfig(device.device, currentConfig) }
                        )
                    }
                    if (preferredConfig != null || (showDevelopmentOptions && device.associated)) {
                        DeviceMenu(device, isPreferredActive)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Choosing a codec requests the association anyway, so only ask up front if the
                // codec can't be shown or the preferred codec can't be applied automatically
                if (!device.statusReadable || (device.associationRequired && device.preferredConfig != null)) {
                    AssociationRow(device)
                    Spacer(Modifier.height(8.dp))
                }
                AnimatedVisibility(!extended && device.statusReadable) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val config = device.codecConfig
                        if (config == null) {
                            ActiveConfigChip(if (device.androidCodecConfig != null) "Unsupported codec" else "No codec information")
                            return@FlowRow
                        }
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
                AnimatedVisibility(extended && device.statusReadable) {
                    CodecConfigSelection(
                        device,
                        onConfigChange = {
                            Log.d(TAG, "onConfigChange: ${it.toAndroid()}")
                            applyCodecConfig(device.device, it)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun DeviceMenu(device: DeviceData, isPreferredActive: Boolean) {
        var expanded by remember { mutableStateOf(false) }
        val preferredConfig = device.preferredConfig
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (preferredConfig != null && !isPreferredActive) {
                    DropdownMenuItem(
                        text = { Text("Restore Preferred Codec") },
                        onClick = {
                            expanded = false
                            applyCodecConfig(device.device, preferredConfig)
                        }
                    )
                }
                if (preferredConfig != null) {
                    DropdownMenuItem(
                        text = { Text("Delete Preferred Codec") },
                        onClick = {
                            expanded = false
                            deletePreferredConfig(device.device)
                        }
                    )
                }
                if (showDevelopmentOptions && device.associated) {
                    DropdownMenuItem(
                        text = { Text("Unlink Device (Debug)") },
                        onClick = {
                            expanded = false
                            unlinkDevice(device.device)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun AssociationRow(device: DeviceData) {
        Column {
            if (!PermissionUtil.canAssociate(this@MainActivity)) {
                Text(
                    "This phone can't link devices, so the codec of ${device.name} can't be changed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }
            Text(
                if (device.statusReadable) "Link ${device.name} so its preferred codec is applied when it connects."
                else "Link ${device.name} to see and change its codec.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { requestAssociation(device.device) { pullBluetoothState() } },
                enabled = !associationInProgress
            ) {
                Text("Allow")
            }
        }
    }

    @Composable
    private fun PermissionRequired(modifier: Modifier) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Auto Bluetooth Codec needs the Nearby devices permission to access the codec of your Bluetooth devices.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            if (permissionState == PermissionState.PERMANENTLY_DENIED) {
                Text(
                    "Allow it in the app settings under Permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { openAppSettings() }) {
                    Text("Open settings")
                }
            } else {
                Button(onClick = { requestBluetoothPermission() }) {
                    Text("Grant permission")
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Preview(showBackground = true)
    @Composable
    private fun Foo() {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
            // Also keeps the content clear of display cutouts in landscape
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            if (permissionState != PermissionState.GRANTED) {
                PermissionRequired(Modifier.padding(innerPadding))
            } else {
                // Padding inside the scroll container, so the cards scroll behind the navigation bar
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding)) {
                    for (device in connectedDevices) {
                        DeviceCard(device, initialExtended = false)
                    }
                }
            }
        }
    }
}