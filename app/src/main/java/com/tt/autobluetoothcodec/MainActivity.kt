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
import android.content.Context
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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.TreeMap

/** Whether the app has the Nearby devices permission, and whether it may still ask for it. */
internal enum class PermissionState { GRANTED, DENIED, PERMANENTLY_DENIED }

/** A connected device as the main screen shows it. */
internal data class DeviceData(
    val address: String,
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
    // Why the codec status couldn't be read, null if it was read
    val readError: String? = null,
)

/** Everything the main screen shows, provided by [MainActivity]. */
internal data class MainScreenState(
    val permissionState: PermissionState,
    val devices: List<DeviceData>,
    val canAssociate: Boolean,
    // The device a link is being requested for, null if none
    val linkingAddress: String?,
    val showDevelopmentOptions: Boolean,
)

/** What the main screen's controls do, implemented by [MainActivity]. */
internal interface MainScreenActions {
    fun requestPermission()
    fun openAppSettings()
    fun openLog()
    fun openAbout()
    fun setCodec(address: String, config: CodecConfig)
    fun savePreferred(address: String, config: CodecConfig)
    fun restorePreferred(address: String, config: CodecConfig)
    fun deletePreferred(address: String)
    fun link(address: String)
    fun unlink(address: String)
}

class MainActivity : ComponentActivity(), BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "MainActivity"
        private const val COMPANION_TAG = "CompanionAssociation"
        private const val FIRST_DEVICES_SEEN = "first_devices_seen"

        // Kept per process, so the activity being recreated doesn't log the same state again
        private var loggedPermissionState: PermissionState? = null
        private val readFailures = HashSet<String>()

        /** Forgets the states logged by this process, for tests. */
        @VisibleForTesting
        fun resetForTest() {
            loggedPermissionState = null
            readFailures.clear()
        }

        private fun showFailure(context: Context, resolution: EventType) {
            val text = when (resolution) {
                EventType.SECURITY_EXCEPTION -> "Bluetooth didn't allow changing the codec"
                EventType.ERROR -> "Changing the codec failed"
                else -> return
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // Development options are only shown in debuggable builds
    private val showDevelopmentOptions by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val log by lazy { EventLog.get(this) }
    private val applier by lazy { CodecApplier.get(this) }

    private var permissionState by mutableStateOf(PermissionState.DENIED)

    private val actions = object : MainScreenActions {
        override fun requestPermission() = requestBluetoothPermission()
        override fun openAppSettings() = this@MainActivity.openAppSettings()
        override fun openLog() = startActivity(Intent(this@MainActivity, LogActivity::class.java))
        override fun openAbout() = startActivity(Intent(this@MainActivity, AboutActivity::class.java))
        override fun setCodec(address: String, config: CodecConfig) = setCodecConfig(remoteDevice(address), config)
        override fun savePreferred(address: String, config: CodecConfig) = savePreferredConfig(remoteDevice(address), config)
        override fun restorePreferred(address: String, config: CodecConfig) = restorePreferredConfig(remoteDevice(address), config)
        override fun deletePreferred(address: String) = deletePreferredConfig(remoteDevice(address))
        override fun link(address: String) = linkDevice(remoteDevice(address))
        override fun unlink(address: String) = unlinkDevice(remoteDevice(address))
    }

    /** The device the main screen shows with [address], only shown devices have one. */
    private fun remoteDevice(address: String): BluetoothDevice =
        getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)

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
        logPermissionState()
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation the cards keep their own state
        firstDevicesSeen = savedInstanceState?.getBoolean(FIRST_DEVICES_SEEN) ?: false
        // Starts loading the log in the background, merging the events logged before the unlock.
        // The screen only logs on its own once a device is connected
        EventLog.get(this)

        handler = Handler(mainLooper)
        CompanionPresence.syncAll(this)

        updatePermissionState()
        // After a configuration change the result of a pending request is delivered to this instance
        if (permissionState != PermissionState.GRANTED && savedInstanceState == null) {
            requestBluetoothPermission()
        }

        setContent {
            AppTheme {
                MainScreen(
                    MainScreenState(
                        permissionState = permissionState,
                        devices = connectedDevices,
                        canAssociate = PermissionUtil.canAssociate(this),
                        linkingAddress = linkingAddress,
                        showDevelopmentOptions = showDevelopmentOptions
                    ),
                    actions,
                    initiallyExpanded = autoExpanded
                )
            }
        }

        lifecycleScope.launch {
            // Shows the codec as soon as an apply starts or ends, instead of on the next poll
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                applier.pending.collect { pullBluetoothState() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(FIRST_DEVICES_SEEN, firstDevicesSeen)
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
        logPermissionState()
    }

    /** Logs a change of the permission state, the first state seen by the process isn't a change. */
    private fun logPermissionState() {
        val previous = loggedPermissionState
        loggedPermissionState = permissionState
        if (previous != null && previous != permissionState)
            log.log(EventType.PERMISSION_CHANGED, message = permissionState.name.lowercase().replace('_', ' '))
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

    private var linkingAddress by mutableStateOf<String?>(null)
    private val associationInProgress get() = linkingAddress != null
    private var onAssociated: (() -> Unit)? = null
    private var onAssociationFailed: ((String?) -> Unit)? = null

    private fun requestAssociation(device: BluetoothDevice, onSuccess: () -> Unit, onFailure: (String?) -> Unit) {
        if (associationInProgress) {
            onFailure("association request already in progress")
            return
        }
        if (!PermissionUtil.canAssociate(this)) {
            onFailure("companion association not supported")
            return
        }

        val companionManager = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothDeviceFilter.Builder().setAddress(device.address).build()
        val request = AssociationRequest.Builder().setSingleDevice(true).addDeviceFilter(filter).build()
        linkingAddress = device.address
        onAssociated = {
            CompanionPresence.update(this, device)
            onSuccess()
        }
        onAssociationFailed = onFailure
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
        linkingAddress = null
        val action = onAssociated
        val failureAction = onAssociationFailed
        onAssociated = null
        onAssociationFailed = null
        if (success) {
            action?.invoke()
        } else {
            val text = "Device wasn't linked" + (message?.let { ": $it" } ?: "")
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            failureAction?.invoke(message?.toString())
        }
    }

    /** Requests an association as part of [operation], which is resolved if it fails. */
    private fun associateFor(device: BluetoothDevice, operation: EventLog.Operation, onSuccess: () -> Unit) {
        operation.log(EventType.ASSOCIATION_REQUESTED)
        requestAssociation(device, onSuccess) { operation.resolve(EventType.ASSOCIATION_FAILED, message = it) }
    }

    private fun linkDevice(device: BluetoothDevice) {
        val operation = log.begin(EventType.USER_LINK_DEVICE, LogDevice.of(device))
        requestAssociation(
            device,
            onSuccess = {
                operation.resolve(EventType.ASSOCIATION_CREATED)
                pullBluetoothState()
            },
            onFailure = { operation.resolve(EventType.ASSOCIATION_FAILED, message = it) }
        )
    }

    private fun setCodecConfig(device: BluetoothDevice, config: CodecConfig) {
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice.of(device), LogCodecConfig.of(config))
        applyCodecConfig(device, config, operation)
    }

    private fun restorePreferredConfig(device: BluetoothDevice, config: CodecConfig) {
        val operation = log.begin(EventType.USER_RESTORE_PREFERRED, LogDevice.of(device), LogCodecConfig.of(config))
        applyCodecConfig(device, null, operation)
    }

    /** Applies [config], or the preferred codec if it's null, as part of [operation]. */
    private fun applyCodecConfig(device: BluetoothDevice, config: CodecConfig?, operation: EventLog.Operation, mayAssociate: Boolean = true) {
        if (mayAssociate && PermissionUtil.isAssociationRequired(this, device)) {
            associateFor(device, operation) { applyCodecConfig(device, config, operation, mayAssociate = false) }
            return
        }
        // The applier may outlive this activity, so the result is shown with the application context
        val appContext = applicationContext
        val onSecurityException: ((EventLog.Operation, SecurityException) -> Unit)? =
            if (mayAssociate) ({ handedBack, e -> associateAfterRefusal(device, config, handedBack, e) }) else null
        applier.apply(device, operation, config, onDone = { showFailure(appContext, it) }, onSecurityException = onSecurityException)
    }

    /** Continues [operation] after Bluetooth refused the codec change with [e]. */
    private fun associateAfterRefusal(device: BluetoothDevice, config: CodecConfig?, operation: EventLog.Operation, e: SecurityException) {
        // An updated Bluetooth module may require an association on older versions as well
        if (!isDestroyed && PermissionUtil.canAssociate(this) && !PermissionUtil.hasAssociation(this, device)) {
            associateFor(device, operation) { applyCodecConfig(device, config, operation, mayAssociate = false) }
            return
        }
        Log.w(TAG, "Bluetooth refused to change the codec", e)
        operation.resolve(EventType.SECURITY_EXCEPTION, message = e.toString())
        showFailure(applicationContext, EventType.SECURITY_EXCEPTION)
    }

    private fun savePreferredConfig(device: BluetoothDevice, config: CodecConfig) {
        val operation = log.begin(EventType.USER_SAVE_PREFERRED, LogDevice.of(device), LogCodecConfig.of(config))
        storePreferredConfig(device, config, operation)
    }

    private fun storePreferredConfig(device: BluetoothDevice, config: CodecConfig, operation: EventLog.Operation, mayAssociate: Boolean = true) {
        // Without an association the preferred codec can't be applied when the device connects
        if (mayAssociate && (PermissionUtil.isAssociationRequired(this, device)
                    || PermissionUtil.isAssociationRequiredForAutoApply(this, device))) {
            associateFor(device, operation) { storePreferredConfig(device, config, operation, mayAssociate = false) }
            return
        }
        PreferredCodecStore.set(this, device, config)
        CompanionPresence.update(this, device)
        operation.resolve(EventType.PREFERRED_SAVED)
        pullBluetoothState()
    }

    private fun deletePreferredConfig(device: BluetoothDevice) {
        val config = PreferredCodecStore.get(this, device)
        PreferredCodecStore.remove(this, device)
        log.log(EventType.PREFERRED_DELETED, LogDevice.of(device), config = config?.let { LogCodecConfig.of(it) })
        CompanionPresence.update(this, device)
        pullBluetoothState()
    }

    private fun unlinkDevice(device: BluetoothDevice) {
        if (PermissionUtil.removeAssociation(this, device)) {
            log.log(EventType.DEVICE_UNLINKED, LogDevice.of(device))
            Toast.makeText(this, "Device unlinked", Toast.LENGTH_SHORT).show()
        }
        pullBluetoothState()
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private var profileRequested = false
    private var handler: Handler? = null

    private var connectedDevices by mutableStateOf(emptyList<DeviceData>())
    // A lone device on the first list with devices in it starts expanded, once per activity
    private var autoExpanded by mutableStateOf(emptySet<String>())
    private var firstDevicesSeen = false

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
        if (!firstDevicesSeen && devices.isNotEmpty()) {
            firstDevicesSeen = true
            autoExpanded = connectedDevices.singleOrNull()?.let { setOf(it.address) } ?: emptySet()
        }
        for ((device, data) in devices.zip(connectedDevices))
            logObservedCodec(device, data)
    }

    /**
     * Logs codecs that differ from the last one known for the device in this process, so the first
     * one read in a process as well, and new read errors. While the applier changes the codec of a
     * device, its operation logs the codecs instead.
     */
    private fun logObservedCodec(device: BluetoothDevice, data: DeviceData) {
        val address = device.address.uppercase()
        val error = data.readError
        if (error != null) {
            if (readFailures.add(address))
                log.log(EventType.READ_FAILED, LogDevice.of(device), message = error)
            return
        }
        readFailures.remove(address)
        val config = data.androidCodecConfig ?: return
        if (address !in applier.pending.value && config != applier.knownCodec(address)) {
            applier.noteObservedCodec(address, config)
            log.log(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, LogDevice.of(device), actual = LogCodecConfig.of(config))
        }
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
                address = device.address,
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
            return DeviceData(device.address, name, null, null, ArrayList(), statusReadable = false, associationRequired, associated, preferredConfig, readError = e.toString())
        } catch (e: Exception) {
            Log.e(TAG, "reading the codec status of $name failed", e)
            return DeviceData(device.address, name, null, null, ArrayList(), statusReadable = true, associationRequired, associated, preferredConfig, readError = e.toString())
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
}

// Not const, a constant false condition would prevent the smart casts in the guarded blocks
private val SHOW_UNCONFIGURABLE_OPTIONS = false

/**
 * A chip of the codec's values. The selected one is filled in the inverse primary color, the theme's color at tone 80
 * in light mode and 40 in dark mode: a step stronger than Save's container, without outshouting the buttons. The
 * others are faintly outlined. The collapsed card's chips look like the selected ones, but can't be chosen.
 */
@Composable
private fun ConfigChip(text: String, selected: Boolean, enabled: Boolean, clickable: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (selected) scheme.inversePrimary else Color.Transparent,
            contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
            enabled = clickable,
            onClick = onClick,
            modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.38f)
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ActiveConfigChip(text: String) {
    ConfigChip(text = text, selected = true, enabled = true, onClick = {}, clickable = false)
}

/**
 * The chip of the collapsed card that says the preferred codec is active, after the codec's chips, in Restore's green,
 * which holds its own among the filled codec chips.
 */
@Composable
private fun PreferredChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AppColors.status.restoreContainer,
        contentColor = AppColors.status.onRestoreContainer,
        modifier = Modifier.testTag("preferred_chip")
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Preferred Codec", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** What can be done with a device's preferred codec. */
private enum class PreferredAction(val label: String, val icon: ImageVector, val tag: String) {
    SAVE("Save", Icons.Default.Star, "save_preferred"),
    RESTORE("Restore", Icons.Default.Refresh, "restore_preferred"),
    CLEAR("Clear", Icons.Default.Delete, "clear_preferred"),
}

/** An action, or the "Active" label if [action] is null, taking [slots] of the three slots of the row. */
private class PreferredSlot(val action: PreferredAction?, val slots: Int)

/**
 * Only what's available, so no button is ever disabled: Save across the row while nothing is saved, the "Active"
 * label across two slots and Clear while the preferred codec is active, and otherwise Save, Restore and Clear.
 */
private fun preferredSlots(device: DeviceData): List<PreferredSlot> {
    val current = device.codecConfig
    val preferred = device.preferredConfig
    return when {
        preferred == null -> if (current != null) listOf(PreferredSlot(PreferredAction.SAVE, 3)) else emptyList()
        preferred == current -> listOf(PreferredSlot(null, 2), PreferredSlot(PreferredAction.CLEAR, 1))
        // A codec unknown to this app can't be saved
        current == null -> listOf(PreferredSlot(PreferredAction.RESTORE, 2), PreferredSlot(PreferredAction.CLEAR, 1))
        else -> PreferredAction.entries.map { PreferredSlot(it, 1) }
    }
}

/** The size of the icons in the preferred codec's row, 18 dp at the normal font size, growing with the labels. */
@Composable
private fun preferredIconSize(): Dp =
    with(LocalDensity.current) { (MaterialTheme.typography.labelLarge.fontSize * (18f / 14f)).toDp() }

/** The icon and the label of an action, or just the icon, which is then as tall as the label would be. */
@Composable
private fun PreferredContent(label: String, icon: ImageVector, showLabel: Boolean) {
    val style = MaterialTheme.typography.labelLarge
    Row(
        Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = if (showLabel) null else label, modifier = Modifier.size(preferredIconSize()))
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = style, maxLines = 1)
        } else {
            Text("", style = style)
        }
    }
}

/** A tonal button: Save in the theme's color, Restore in green and Clear in red. Without its label it has a tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferredActionButton(action: PreferredAction, showLabel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (action) {
        PreferredAction.SAVE -> scheme.primaryContainer to scheme.onPrimaryContainer
        PreferredAction.RESTORE -> AppColors.status.restoreContainer to AppColors.status.onRestoreContainer
        PreferredAction.CLEAR -> scheme.errorContainer to scheme.onErrorContainer
    }
    val button = @Composable {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = modifier.semantics { role = Role.Button }.testTag(action.tag)
        ) {
            PreferredContent(action.label, action.icon, showLabel)
        }
    }
    if (showLabel) {
        button()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(action.label) } },
            state = rememberTooltipState()
        ) {
            button()
        }
    }
}

/**
 * Says that the preferred codec is active, shaped like the collapsed card's chip but in the pale green, so it stays a
 * label among the buttons and doesn't invite a tap.
 */
@Composable
private fun PreferredActiveLabel(showLabel: Boolean, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AppColors.status.successContainer,
        contentColor = AppColors.status.onSuccessContainer,
        modifier = modifier.testTag("preferred_active")
    ) {
        PreferredContent("Active", Icons.Default.Check, showLabel)
    }
}

/**
 * The expanded card's preferred codec section, a row of three equal slots, at most 420 dp wide. When the labels of
 * three buttons don't fit in a third of the row, on small phones or with large fonts, the one-slot buttons only show
 * their icons; Save and "Active", which are wider, keep their labels if those fit. The row never wraps, so the card
 * keeps its height whatever is available.
 */
@Composable
private fun PreferredCodecActions(device: DeviceData, actions: MainScreenActions) {
    val slots = preferredSlots(device)
    if (slots.isEmpty())
        return
    Column {
        Spacer(Modifier.height(16.dp))
        Text("Preferred Codec", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("preferred_actions")) {
            val gap = 8.dp
            val third = (maxWidth - gap * 2) / 3
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelLarge
            val density = LocalDensity.current
            // The icon, its gap and the padding on both sides
            val around = preferredIconSize() + 8.dp + 32.dp
            fun fits(label: String, width: Dp) =
                with(density) { measurer.measure(label, style, softWrap = false).size.width.toDp() } + around <= width
            // Decided for all three, so a button doesn't lose its label when another one appears
            val thirdsFit = PreferredAction.entries.all { fits(it.label, third) }
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                for (slot in slots) {
                    val width = third * slot.slots + gap * (slot.slots - 1)
                    val action = slot.action
                    val showLabel = thirdsFit || (slot.slots > 1 && fits(action?.label ?: "Active", width))
                    val modifier = Modifier.width(width)
                    if (action == null) {
                        PreferredActiveLabel(showLabel, modifier)
                    } else {
                        PreferredActionButton(action, showLabel, modifier) {
                            when (action) {
                                PreferredAction.SAVE -> device.codecConfig?.let { actions.savePreferred(device.address, it) }
                                PreferredAction.RESTORE -> device.preferredConfig?.let { actions.restorePreferred(device.address, it) }
                                PreferredAction.CLEAR -> actions.deletePreferred(device.address)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ConfigRowItem(
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

/**
 * The collapsed card's chips: the codec, then the preferred codec's chip while it's active, then the link's while
 * the device isn't linked. Last, so the codec's chips stay where they are when they come and go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodecChips(device: DeviceData, state: MainScreenState) {
    val extra = @Composable {
        if (device.associationRequired)
            LinkChip(linking = state.linkingAddress == device.address, canLink = state.canAssociate)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val config = device.codecConfig
        if (config == null) {
            ActiveConfigChip(if (device.androidCodecConfig != null) "Unsupported codec" else "No codec information")
            extra()
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
        if (device.preferredConfig == config) {
            PreferredChip()
        }
        extra()
    }
}

@Composable
private fun DeviceCard(device: DeviceData, initialExtended: Boolean, state: MainScreenState, actions: MainScreenActions) {
    // Unlike remember, survives the screen being rotated
    var extended by rememberSaveable { mutableStateOf(initialExtended) }
    val modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(deviceTag(device))
    val content = @Composable {
        Column(modifier = Modifier.padding(10.dp)) {
            // The name has the header to itself, the preferred codec is below the codec
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state.showDevelopmentOptions && device.associated) {
                    DeviceMenu(device, actions)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!device.statusReadable) {
                LinkPrompt(device, state, actions)
                return@Column
            }
            AnimatedVisibility(!extended) {
                CodecChips(device, state)
            }
            AnimatedVisibility(extended) {
                Column {
                    if (device.associationRequired) {
                        LinkBanner(device, state.canAssociate)
                        Spacer(Modifier.height(16.dp))
                    }
                    CodecConfigSelection(
                        device,
                        onConfigChange = { actions.setCodec(device.address, it) }
                    )
                    PreferredCodecActions(device, actions)
                }
            }
        }
    }
    // Material's elevated card color, the shadow alone doesn't show in dark mode
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    // Without a codec there's nothing to expand, the card always shows how to link the device
    if (device.statusReadable) {
        Surface(
            onClick = { extended = !extended },
            modifier = modifier,
            color = color,
            shadowElevation = 6.dp,
            shape = CardDefaults.elevatedShape,
            content = content
        )
    } else {
        Surface(modifier = modifier, color = color, shadowElevation = 6.dp, shape = CardDefaults.elevatedShape,
            content = content)
    }
}

/** The device's menu, only in debug builds, for unlinking a linked device. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeviceMenu(device: DeviceData, actions: MainScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("device_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        // A menu is a popup, outside the Scaffold that exposes the test tags to UiAutomator
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            DropdownMenuItem(
                text = { Text("Unlink Device (Debug)") },
                modifier = Modifier.testTag("unlink_device"),
                onClick = {
                    expanded = false
                    actions.unlink(device.address)
                }
            )
        }
    }
}

/** The link's chip after the codec's: "Not linked" or "Linking…" in amber, "Can't link" in grey. */
@Composable
private fun LinkChip(linking: Boolean, canLink: Boolean) {
    val (container, content) =
        if (canLink) AppColors.status.attentionContainer to AppColors.status.onAttentionContainer
        else MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.testTag("link_chip")
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (linking) {
                CircularProgressIndicator(Modifier.size(14.dp), color = content, strokeWidth = 2.dp)
            } else {
                Icon(ImageVector.vectorResource(R.drawable.ic_link_off), contentDescription = null,
                    modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    linking -> "Linking…"
                    canLink -> "Not linked"
                    else -> "Can't link"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * The card of a device whose codec can only be read once it's linked: why, and "Link Device" where the lone Save
 * button would be, as wide as the row up to 420 dp. While linking it spins instead, while another device links it
 * waits. Without the companion feature there's only the text.
 */
@Composable
private fun LinkPrompt(device: DeviceData, state: MainScreenState, actions: MainScreenActions) {
    Column {
        if (!state.canAssociate) {
            Text(
                "This phone can't link devices, so the codec can't be read or changed.",
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }
        Text("Link this device to see and change its codec.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val linking = state.linkingAddress == device.address
        val waiting = state.linkingAddress != null && !linking
        val scheme = MaterialTheme.colorScheme
        Surface(
            onClick = { actions.link(device.address) },
            enabled = state.linkingAddress == null,
            shape = CircleShape,
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .graphicsLayer(alpha = if (waiting) 0.38f else 1f)
                .semantics { role = Role.Button }.testTag("link_device")
        ) {
            if (linking) {
                Row(
                    Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(preferredIconSize()), color = scheme.onPrimaryContainer,
                        strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Linking…", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            } else {
                PreferredContent("Link Device", ImageVector.vectorResource(R.drawable.ic_link), showLabel = true)
            }
        }
    }
}

/**
 * Tops the expanded card of a readable device that isn't linked, in the "Not linked" chip's amber. Choosing a codec
 * or saving it asks to link first, so it has no button. In grey if the phone can't link.
 */
@Composable
private fun LinkBanner(device: DeviceData, canLink: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (canLink) AppColors.status.attentionContainer else scheme.surfaceVariant,
        contentColor = if (canLink) AppColors.status.onAttentionContainer else scheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("link_banner")
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_link_off), contentDescription = null,
                modifier = Modifier.size(preferredIconSize()))
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    !canLink -> "This phone can't link devices, so the codec can't be changed."
                    // Only if the link was removed after saving, since saving asks to link
                    device.preferredConfig != null ->
                        "The preferred codec is only applied when this device connects once it's linked. " +
                            "Changing or restoring the codec asks to link it."
                    else -> "Changing the codec or saving it as preferred asks to link this device first."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PermissionRequired(modifier: Modifier, permissionState: PermissionState, actions: MainScreenActions) {
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
            Button(onClick = { actions.openAppSettings() }) {
                Text("Open settings")
            }
        } else {
            Button(onClick = { actions.requestPermission() }) {
                Text("Grant permission")
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppMenu(onOpenLog: () -> Unit, onOpenAbout: () -> Unit, includeLog: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("app_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        // A menu is a popup, outside the Scaffold that exposes the test tags to UiAutomator
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            if (includeLog) {
                DropdownMenuItem(
                    text = { Text("Log") },
                    modifier = Modifier.testTag("open_log"),
                    onClick = {
                        expanded = false
                        onOpenLog()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("About") },
                modifier = Modifier.testTag("open_about"),
                onClick = {
                    expanded = false
                    onOpenAbout()
                }
            )
        }
    }
}

/** The test tag of a device's card. */
internal fun deviceTag(device: DeviceData) = "device_" + device.address.uppercase()

/**
 * The main screen, the connected devices with their codecs. The cards of the devices in
 * [initiallyExpanded], by address, start expanded, for the previews.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun MainScreen(state: MainScreenState, actions: MainScreenActions, initiallyExpanded: Set<String> = emptySet()) {
    val style = LocalTopBarStyle.current
    val scrollBehavior = rememberMainScrollBehavior(style)
    var view by rememberSaveable { mutableStateOf(DeviceView.CONNECTED) }
    Scaffold(
        // Lets UiAutomator find the elements by their test tags
        modifier = Modifier.semantics { testTagsAsResourceId = true }
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier),
        topBar = { MainTopBar(style, scrollBehavior, state.devices.size, view, { view = it }, actions) },
        // Also keeps the content clear of display cutouts in landscape
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        if (view == DeviceView.SAVED) {
            // Prototype: the saved devices' list isn't designed yet
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Prototype: the devices with a saved preferred codec, connected or not, would be listed here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (state.permissionState != PermissionState.GRANTED) {
            CappedContent(NARROW_CONTENT, tintSides = false) { side ->
                PermissionRequired(Modifier.padding(innerPadding).padding(horizontal = side), state.permissionState, actions)
            }
        } else {
            CappedContent(NARROW_CONTENT, tintSides = false) { side ->
                // Padding inside the scroll container, so the cards scroll behind the navigation bar
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("device_list")
                        .padding(innerPadding).padding(horizontal = side)
                ) {
                    // Keyed, so each card keeps its own saved state
                    for (device in state.devices) key(device.address) {
                        DeviceCard(device, initialExtended = device.address in initiallyExpanded, state, actions)
                    }
                }
            }
        }
    }
}
