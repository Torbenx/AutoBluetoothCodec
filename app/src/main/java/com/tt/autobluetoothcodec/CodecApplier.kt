package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Applies codecs to connected devices and checks that they took effect, trying again a few times
 * if they didn't. There is one applier per process, so the requests of the UI, [CodecCompanionService]
 * and [A2dpConnectReceiver] are coordinated: each device has at most one pending request. A request
 * for a different codec supersedes the pending one, a request for the same codec is dropped.
 *
 * Must be used on the main thread. Each [apply] continues an operation of the [EventLog] and
 * resolves it, unless the operation is handed back to its onSecurityException callback.
 *
 * The profile proxy is opened by the first request and kept until Bluetooth turns off, keeping it
 * open only holds a binder reference.
 */
class CodecApplier private constructor(private val context: Context) : BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "CodecApplier"
        private const val VERIFY_DELAY_MILLIS: Long = 500
        private const val MAX_ATTEMPT_COUNT = 5

        private var instance: CodecApplier? = null

        fun get(context: Context): CodecApplier {
            // A BroadcastReceiver's context isn't allowed to bind services, which the profile proxy does
            return instance ?: CodecApplier(context.applicationContext).also { instance = it }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = Runnable { processRequests() }

    private var a2dpProfile: BluetoothA2dp? = null
    private var profileRequested = false
    private val requests = ArrayList<Request>()

    private val mutablePending = MutableStateFlow<Map<String, CodecConfig>>(emptyMap())
    /** The target of each device with a pending request, by upper case address. */
    val pending: StateFlow<Map<String, CodecConfig>> = mutablePending.asStateFlow()

    /**
     * Applies [target] to [device], or its preferred codec if [target] is null. The request is
     * dropped if A2DP isn't connected to the device. [onDone] is called with the resolution of
     * [operation]. If Bluetooth refuses the change and [onSecurityException] is set, the operation
     * is handed back to it unresolved instead.
     */
    fun apply(
        device: BluetoothDevice,
        operation: EventLog.Operation,
        target: CodecConfig? = null,
        onDone: ((EventType) -> Unit)? = null,
        onSecurityException: ((EventLog.Operation, SecurityException) -> Unit)? = null
    ) {
        if (!PermissionUtil.testPermission(context)) {
            reject(operation, onDone, EventType.NO_PERMISSION)
            return
        }
        val config = target ?: PreferredCodecStore.get(context, device)
        if (config == null) {
            reject(operation, onDone, EventType.NO_PREFERRED_CODEC)
            return
        }
        // While Bluetooth is off the profile proxy wouldn't connect until it's turned on
        if (!isBluetoothEnabled()) {
            reject(operation, onDone, EventType.PROFILE_UNAVAILABLE)
            return
        }
        val superseded = requests.firstOrNull { it.device == device }
        if (superseded != null) {
            if (superseded.target == config) {
                reject(operation, onDone, EventType.ALREADY_PENDING, "pending in operation ${superseded.operation.uid}")
                return
            }
            requests.remove(superseded)
        }
        requests.add(Request(device, config, operation, onDone, onSecurityException))
        // Replaced in one step, so observers don't see the device idle while its codec is changing
        publishPending()
        if (superseded != null) {
            superseded.operation.resolve(EventType.SUPERSEDED, attempt = superseded.attemptCount,
                message = "superseded by operation ${operation.uid}")
            superseded.onDone?.invoke(EventType.SUPERSEDED)
        }
        if (a2dpProfile != null)
            processRequests()
        else
            connectProfile()
    }

    /** Resolves the pending request of [device] with [resolution]. */
    fun cancel(device: BluetoothDevice, resolution: EventType, message: String? = null) {
        requests.firstOrNull { it.device == device }?.let { finish(it, resolution, attempt = it.attemptCount, message = message) }
    }

    /** Resolves the pending request of [operation] with [resolution], returns false if there is none. */
    fun cancel(operation: EventLog.Operation, resolution: EventType, message: String? = null): Boolean {
        val request = requests.firstOrNull { it.operation == operation } ?: return false
        finish(request, resolution, attempt = request.attemptCount, message = message)
        return true
    }

    private fun reject(operation: EventLog.Operation, onDone: ((EventType) -> Unit)?, resolution: EventType, message: String? = null) {
        operation.resolve(resolution, message = message)
        onDone?.invoke(resolution)
    }

    private fun isBluetoothEnabled(): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter?.isEnabled ?: false
    }

    /** Removes [request] and resolves its operation with [resolution]. */
    private fun finish(
        request: Request,
        resolution: EventType,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        attempt: Int? = null,
        message: String? = null
    ) {
        requests.remove(request)
        // Resolved first, observers of pending compare the codec with the last one logged
        request.operation.resolve(resolution, config = config, actual = actual, attempt = attempt, message = message)
        publishPending()
        request.onDone?.invoke(resolution)
    }

    private fun publishPending() {
        mutablePending.value = requests.associate { it.device.address.uppercase() to it.target }
    }

    private fun connectProfile() {
        if (profileRequested)
            return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        profileRequested = adapter?.getProfileProxy(context, this, BluetoothProfile.A2DP) ?: false
        if (!profileRequested) {
            for (request in requests.toList())
                if (request in requests)
                    finish(request, EventType.PROFILE_UNAVAILABLE)
        }
    }

    private fun closeProfileProxy(profile: BluetoothA2dp) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.closeProfileProxy(BluetoothProfile.A2DP, profile)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        a2dpProfile = proxy as BluetoothA2dp
        processRequests()
    }

    override fun onServiceDisconnected(profile: Int) {
        // Closing the proxy reports the disconnect again
        val proxy = a2dpProfile ?: return
        a2dpProfile = null
        // Requested again by the next request, rather than relying on the framework to reconnect it
        profileRequested = false
        closeProfileProxy(proxy)
        handler.removeCallbacks(processRunnable)
        for (request in requests.toList())
            if (request in requests)
                finish(request, EventType.PROFILE_DISCONNECTED, attempt = request.attemptCount)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processRequests() {
        handler.removeCallbacks(processRunnable)
        val profile = a2dpProfile ?: return
        val now = SystemClock.uptimeMillis()
        for (request in requests.toList()) {
            // The callback of an earlier request may have changed the requests
            if (request !in requests || now - request.lastApplyAttempt < VERIFY_DELAY_MILLIS)
                continue
            try {
                process(profile, request, now)
            } catch (e: SecurityException) {
                Log.d(TAG, "Bluetooth refused codec access", e)
                val onSecurityException = request.onSecurityException
                if (onSecurityException != null) {
                    requests.remove(request)
                    publishPending()
                    onSecurityException(request.operation, e)
                } else {
                    finish(request, EventType.SECURITY_EXCEPTION, attempt = request.attemptCount, message = e.toString())
                }
            } catch (e: Throwable) {
                Log.d(TAG, "codec change ran into exception", e)
                finish(request, EventType.ERROR, attempt = request.attemptCount, message = e.toString())
            }
        }
        if (a2dpProfile != null && requests.isNotEmpty()) {
            val nextAttempt = requests.minOf { it.lastApplyAttempt } + VERIFY_DELAY_MILLIS
            handler.postAtTime(processRunnable, nextAttempt)
        }
    }

    /** Verifies the previous attempt of [request] and makes the next one, or finishes the request. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun process(profile: BluetoothA2dp, request: Request, now: Long) {
        val operation = request.operation
        val target = LogCodecConfig.of(request.target)
        if (profile.getConnectionState(request.device) != BluetoothProfile.STATE_CONNECTED) {
            finish(request, EventType.NOT_CONNECTED, attempt = request.attemptCount)
            return
        }

        if (PermissionUtil.isAssociationRequired(context, request.device)) {
            finish(request, EventType.NO_ASSOCIATION)
            return
        }

        var actual: LogCodecConfig? = null
        if (request.attemptCount > 0) {
            actual = A2dpProfileUtil.getCurrentCodecConfig(profile, request.device)?.let { LogCodecConfig.of(it) }
            operation.log(EventType.VALIDATE_CODEC_CONFIG, config = target, actual = actual, attempt = request.attemptCount)
            if (actual?.own?.let { request.target.isFulfilledBy(it) } == true) {
                finish(request, EventType.CODEC_VERIFIED, actual = actual, attempt = request.attemptCount)
                return
            }
        }

        if (request.attemptCount >= MAX_ATTEMPT_COUNT) {
            finish(request, EventType.MAX_ATTEMPTS, config = target, actual = actual, attempt = request.attemptCount)
            return
        }

        request.attemptCount += 1
        operation.log(EventType.SET_CODEC_CONFIG, config = target, attempt = request.attemptCount)
        A2dpProfileUtil.setCodecConfig(profile, request.device, request.target.toAndroid())
        request.lastApplyAttempt = now
    }

    private class Request(
        val device: BluetoothDevice,
        val target: CodecConfig,
        val operation: EventLog.Operation,
        val onDone: ((EventType) -> Unit)?,
        val onSecurityException: ((EventLog.Operation, SecurityException) -> Unit)?,
        var lastApplyAttempt: Long = 0,
        var attemptCount: Int = 0)
}
