package com.karoo.lupinecontrols.extension

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.karoo.lupinecontrols.LupineLampOutputTarget
import com.karoo.lupinecontrols.LupineBeamMode
import com.karoo.lupinecontrols.LupineBleProfile
import com.karoo.lupinecontrols.LupineProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LampCandidate(
    val address: String,
    val name: String,
)

@OptIn(ExperimentalUuidApi::class)
class LupineBleController(
    context: Context,
    private var onStatus: (String) -> Unit = {},
    private var onConnectionStatus: (String) -> Unit = {},
    private var onBatteryStatus: (String) -> Unit = {},
    private var onTemperatureStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "LupineBle"
        private const val AWAIT_TARGET_TIMEOUT_MS = 12_000L
        private const val AWAIT_TARGET_STEP_MS = 100L
        private const val AWAIT_TARGET_RESTART_AFTER_MS = 4_000L
        private const val SEARCH_STATUS_PULSE_MS = 1_000L
        val SUPPORTED_NAME_PREFIXES = setOf(
            "Lupine",
            "SL",
            "ONSemiconduc",
        )
        private const val PREFS_NAME = "lupine_prefs"
        private const val PREF_SELECTED_LAMP_ADDRESS = "selected_lamp_address"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }

    private val targetService = Uuid.parse(LupineBleProfile.PRIMARY_SERVICE_UUID)
    private val commandChar = Uuid.parse(LupineBleProfile.COMMAND_CHARACTERISTIC_UUID)
    private val notifyChar = Uuid.parse(LupineBleProfile.NOTIFY_CHARACTERISTIC_UUID)

    private val connectionOptions by lazy {
        CentralManager.ConnectionOptions.Direct(timeout = 8.seconds, retry = 0, retryDelay = 1.seconds)
    }

    @Volatile private var discoveryJob: Job? = null
    @Volatile private var lastPeripheral: Peripheral? = null
    @Volatile private var lastTargetSeenAtMs: Long = 0L
    @Volatile private var seenCount: Int = 0
    @Volatile private var lastSeenTag: String = "none"
    @Volatile private var lastPublishedStatus: String? = null
    @Volatile private var lastPublishedConnectionStatus: String? = null
    @Volatile private var lastPublishedBatteryStatus: String? = null
    @Volatile private var lastPublishedTemperatureStatus: String? = null
    @Volatile private var lastBatteryStatusAtMs: Long = 0L
    @Volatile private var lastTemperatureStatusAtMs: Long = 0L
    @Volatile private var preferredAddress: String? = prefs.getString(PREF_SELECTED_LAMP_ADDRESS, null)
    @Volatile private var notificationJob: Job? = null
    @Volatile private var repeatingCommandJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var telemetryBootstrapJob: Job? = null
    @Volatile private var observingAddress: String? = null
    private val operationMutex = Mutex()
    private val candidateLock = Any()
    private val knownCandidates = LinkedHashMap<String, LampCandidate>()
    private val knownPeripherals = LinkedHashMap<String, Peripheral>()

    private fun Peripheral.debugLabel(): String = "${name ?: "<unnamed>"}/${address}"

    private fun logDebug(message: String) {
        BleDiagnosticLog.debug(TAG, message)
        Log.d(TAG, message)
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        BleDiagnosticLog.warn(TAG, message, throwable)
        if (throwable == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
    }

    fun startDiscovery(forceRestart: Boolean = false) {
        if (!forceRestart && lastPeripheral?.state?.value is ConnectionState.Connected) {
            logDebug("startDiscovery skipped: already connected to ${lastPeripheral?.debugLabel()}")
            return
        }
        if (forceRestart) {
            logDebug("startDiscovery forceRestart preferred=$preferredAddress")
            discoveryJob?.cancel()
            discoveryJob = null
            lastPeripheral = null
            lastTargetSeenAtMs = 0L
            seenCount = 0
            lastSeenTag = "none"
            synchronized(candidateLock) {
                knownCandidates.clear()
                knownPeripherals.clear()
            }
        } else if (discoveryJob?.isActive == true) {
            logDebug("startDiscovery skipped: discovery already active preferred=$preferredAddress")
            return
        }
        if (!hasBlePermissions()) {
            logWarn("startDiscovery aborted: missing BLE permissions")
            publishStatus("missing bluetooth permissions")
            publishConnectionStatus("permissions")
            return
        }

        discoveryJob = scope.launch {
            seenCount = 0
            lastSeenTag = "none"
            logDebug("discovery started preferred=$preferredAddress")
            publishStatus("searching")
            try {
                centralManager
                    .scan { Any { } }
                    .collect { result ->
                        val p = result.peripheral
                        val name = p.name ?: "<unnamed>"
                        val tag = "$name/${p.address}"
                        seenCount += 1
                        lastSeenTag = tag

                        if (matchesSupportedFamily(name)) {
                            val candidate = LampCandidate(address = p.address, name = name)
                            val preferred = preferredAddress
                            val knownBefore = synchronized(candidateLock) { knownCandidates.containsKey(p.address) }
                            synchronized(candidateLock) {
                                knownCandidates[p.address] = candidate
                                knownPeripherals[p.address] = p
                            }
                            if (!knownBefore) {
                                logDebug("supported device seen ${p.debugLabel()} preferred=$preferred")
                            }
                            val isPreferredTarget = preferred != null && preferred == p.address
                            val shouldTrackAsTarget = isPreferredTarget || preferred == null || lastPeripheral == null
                            if (shouldTrackAsTarget) {
                                val isNewTarget = lastPeripheral?.address != p.address
                                lastPeripheral = p
                                lastTargetSeenAtMs = System.currentTimeMillis()
                                if (isNewTarget) {
                                    logDebug("tracking target ${p.debugLabel()} preferredMatch=$isPreferredTarget")
                                }
                                val shouldPublishFound =
                                    isNewTarget ||
                                        lastPublishedStatus == null ||
                                        lastPublishedStatus in setOf("searching", "disconnected", "idle")
                                if (shouldPublishFound) {
                                    publishStatus("found")
                                }
                            }
                        }
                    }
            } catch (t: Throwable) {
                logWarn("discovery failed preferred=$preferredAddress seenCount=$seenCount lastSeen=$lastSeenTag", t)
                publishStatus("discovery error: ${t::class.java.simpleName}")
                publishConnectionStatus("discovery_error:${t::class.java.simpleName}")
            }
        }
    }

    fun stopDiscovery() {
        logDebug("discovery stopped")
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun setPreferredAddress(address: String?) {
        logDebug("setPreferredAddress old=$preferredAddress new=$address")
        preferredAddress = address
        prefs.edit().putString(PREF_SELECTED_LAMP_ADDRESS, address).apply()
        if (address == null) {
            clearActiveConnectionState(clearCachedPeripheral = true)
            publishConnectionStatus("disconnected")
            resetTelemetryStatus()
            publishStatus("idle")
            return
        }
        synchronized(candidateLock) {
            knownPeripherals[address]?.let { peripheral ->
                lastPeripheral = peripheral
                lastTargetSeenAtMs = System.currentTimeMillis()
            }
        }
    }

    fun currentPreferredAddress(): String? = preferredAddress

    fun currentLampCandidates(): List<LampCandidate> = synchronized(candidateLock) {
        knownCandidates.values.toList()
    }

    fun currentSelectedLamp(): LampCandidate? {
        val selected = preferredAddress ?: return null
        return synchronized(candidateLock) { knownCandidates[selected] }
            ?: lastPeripheral?.let { LampCandidate(it.address, it.name ?: "Lupine SL Grano F") }
    }

    private fun firstKnownPeripheral(): Peripheral? = synchronized(candidateLock) {
        knownPeripherals.values.firstOrNull()
    }

    private fun currentTargetPeripheral(): Peripheral? {
        val preferred = preferredPeripheral()
        if (preferred != null) {
            lastPeripheral = preferred
            return preferred
        }
        if (preferredAddress == null) {
            val discovered = firstKnownPeripheral()
            if (discovered != null) {
                lastPeripheral = discovered
                return discovered
            }
        }
        return lastPeripheral
    }

    private fun preferredPeripheral(): Peripheral? {
        val selected = preferredAddress ?: return null
        return synchronized(candidateLock) { knownPeripherals[selected] } ?: lastPeripheral?.takeIf { it.address == selected }
    }

    fun connect() {
        if (connectJob?.isActive == true) {
            logDebug("connect skipped: job already active preferred=$preferredAddress")
            return
        }
        connectJob = scope.launch {
            operationMutex.withLock {
                logDebug(
                    "connect begin preferred=$preferredAddress lastPeripheral=${lastPeripheral?.debugLabel()} seenCount=$seenCount",
                )
                publishConnectionStatus("connecting")
                val cached = currentTargetPeripheral()
                val isConnected = cached?.state?.value is ConnectionState.Connected
                if (!isConnected && cached == null) {
                    logDebug("connect has no cached target; starting discovery")
                    startDiscovery()
                }

                val target = awaitTarget() ?: run {
                    logWarn(
                        "awaitTarget timeout preferred=$preferredAddress seenCount=$seenCount lastSeenTag=$lastSeenTag",
                    )
                    publishConnectionStatus("pairing_timeout")
                    return@withLock
                }

                logDebug("connect resolved target ${target.debugLabel()} state=${target.state.value::class.simpleName}")

                if (preferredAddress == null) {
                    setPreferredAddress(target.address)
                }

                try {
                    publishStatus("found")
                    ensureConnected(target)
                    publishStatus("connected")
                    scheduleTelemetryBootstrap(target)
                    logDebug("connect completed ${target.debugLabel()}")
                } catch (t: Throwable) {
                    cleanupAfterConnectionFailure(target)
                    logWarn("connect failed for ${target.debugLabel()}", t)
                    publishStatus("ble error: ${t::class.java.simpleName}")
                    publishConnectionStatus("ble_error:${t::class.java.simpleName}")
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (connectJob === job) connectJob = null
                logDebug("connect job completed preferred=$preferredAddress")
            }
        }
    }

    fun send(frameHex: String) {
        scope.launch {
            operationMutex.withLock {
                sendInternal(frameHex)
            }
        }
    }

    fun startRepeatingCommand(frameHex: String, intervalMs: Long = 1500L) {
        stopRepeatingCommand()
        repeatingCommandJob = scope.launch {
            while (true) {
                operationMutex.withLock {
                    sendInternal(frameHex)
                }
                delay(intervalMs)
            }
        }
    }

    fun stopRepeatingCommand() {
        repeatingCommandJob?.cancel()
        repeatingCommandJob = null
    }

    fun refreshTelemetry() {
        scope.launch {
            operationMutex.withLock {
                val target = preferredPeripheral() ?: lastPeripheral ?: return@withLock
                if (target.state.value !is ConnectionState.Connected) return@withLock
                requestTelemetry(target)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            operationMutex.withLock {
                logDebug("disconnect requested lastPeripheral=${lastPeripheral?.debugLabel()}")
                stopRepeatingCommand()
                val target = lastPeripheral
                if (target == null) {
                    publishConnectionStatus("disconnected")
                    clearActiveConnectionState(clearCachedPeripheral = true)
                    resetTelemetryStatus()
                    return@withLock
                }

                if (target.state.value !is ConnectionState.Connected) {
                    publishConnectionStatus("disconnected")
                    clearActiveConnectionState(clearCachedPeripheral = true)
                    resetTelemetryStatus()
                    return@withLock
                }

                try {
                    publishStatus("disconnecting")
                    target.disconnect()
                    cancelActiveJobs()
                    clearActiveConnectionState(clearCachedPeripheral = true)
                    publishStatus("disconnected")
                    publishConnectionStatus("disconnected")
                    resetTelemetryStatus()
                } catch (t: Throwable) {
                    logWarn("disconnect failed for ${target.debugLabel()}", t)
                    publishStatus("disconnect error: ${t::class.java.simpleName}")
                }
            }
        }
    }

    fun cancelConnectionAttempt() {
        scope.launch {
            operationMutex.withLock {
                logDebug("cancelConnectionAttempt preferred=$preferredAddress lastPeripheral=${lastPeripheral?.debugLabel()}")
                connectJob?.cancel()
                connectJob = null
                stopDiscovery()
                stopRepeatingCommand()
                telemetryBootstrapJob?.cancel()
                telemetryBootstrapJob = null
                notificationJob?.cancel()
                notificationJob = null
                observingAddress = null
                runCatching {
                    val peripheral = preferredPeripheral() ?: lastPeripheral
                    if (peripheral?.state?.value is ConnectionState.Connected) {
                        peripheral.disconnect()
                    }
                }
                clearActiveConnectionState(clearCachedPeripheral = false)
                publishStatus("idle")
                publishConnectionStatus("disconnected")
                resetTelemetryStatus()
            }
        }
    }

    private suspend fun ensureConnected(peripheral: Peripheral) {
        logDebug("ensureConnected start ${peripheral.debugLabel()} state=${peripheral.state.value::class.simpleName}")
        if (peripheral.state.value !is ConnectionState.Connected) {
            publishStatus("connecting: ${peripheral.name ?: peripheral.address}")
            publishConnectionStatus("connecting")
            logDebug("centralManager.connect ${peripheral.debugLabel()}")
            centralManager.connect(peripheral, connectionOptions)
            discoveryJob?.cancel()
            discoveryJob = null
            publishStatus("connected")
            publishConnectionStatus("connected")
        } else {
            discoveryJob?.cancel()
            discoveryJob = null
            publishStatus("connected")
            publishConnectionStatus("connected")
        }
        val commandCharacteristic = waitForCommandCharacteristic(peripheral)
        logDebug("command characteristic ${if (commandCharacteristic != null) "ready" else "missing"} for ${peripheral.debugLabel()}")
        ensureNotificationObservation(peripheral)
        val ready = waitUntil(timeoutMs = 40, stepMs = 8) {
            notificationJob?.isActive == true && findCommandCharacteristic(peripheral) != null
        }
        logDebug(
            "ensureConnected ready=$ready notifyActive=${notificationJob?.isActive == true} for ${peripheral.debugLabel()}",
        )
    }

    private suspend fun cleanupAfterConnectionFailure(peripheral: Peripheral) {
        try {
            logWarn("cleanupAfterConnectionFailure ${peripheral.debugLabel()}")
            cancelActiveJobs()
            peripheral.disconnect()
        } catch (t: Throwable) {
            logWarn("cleanup disconnect failed for ${peripheral.debugLabel()}", t)
        } finally {
            if (lastPeripheral?.address == peripheral.address) {
                lastPeripheral = null
            }
            clearActiveConnectionState(clearCachedPeripheral = false)
            stopDiscovery()
            publishConnectionStatus("disconnected")
        }
    }

    private suspend fun requestTelemetry(peripheral: Peripheral) {
        val telemetryStartedAtMs = System.currentTimeMillis()
        logDebug("requestTelemetry start ${peripheral.debugLabel()}")
        publishStatus("sync telemetry")
        LupineProtocol.buildInitializationFrames().forEachIndexed { index, frameHex ->
            logDebug("requestTelemetry frame[$index]=$frameHex ${peripheral.debugLabel()}")
            if (!writeFrameWithRetry(peripheral, frameHex)) {
                logWarn("requestTelemetry frame[$index] failed ${peripheral.debugLabel()}")
                return
            }
            if (index < LupineProtocol.buildInitializationFrames().lastIndex) {
                delay(70)
            }
        }
        val telemetryReceived = waitUntil(timeoutMs = 140, stepMs = 10) {
            lastTemperatureStatusAtMs > telemetryStartedAtMs
        }
        logDebug("requestTelemetry completed receivedNotify=$telemetryReceived ${peripheral.debugLabel()}")
    }

    fun applyBeamMode(mode: LupineBeamMode): Boolean {
        val peripheral = preferredPeripheral() ?: lastPeripheral ?: return false
        if (peripheral.state.value !is ConnectionState.Connected) return false
        val modeCommand = LupineProtocol.buildBeamCommand(mode)
        if (modeCommand == null) {
            publishStatus("command unavailable")
            return false
        }

        send(modeCommand)
        return true
    }

    private fun scheduleTelemetryBootstrap(peripheral: Peripheral) {
        telemetryBootstrapJob?.cancel()
        telemetryBootstrapJob = scope.launch {
            delay(180)
            operationMutex.withLock {
                if (peripheral.state.value !is ConnectionState.Connected) return@withLock
                if (lastPeripheral?.address != peripheral.address) return@withLock
                logDebug("telemetry bootstrap firing ${peripheral.debugLabel()}")
                runCatching { requestTelemetry(peripheral) }
                    .onFailure { logWarn("telemetry bootstrap failed for ${peripheral.debugLabel()}", it) }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (telemetryBootstrapJob === job) telemetryBootstrapJob = null
            }
        }
    }

    private suspend fun awaitTarget(): Peripheral? {
        var p = currentTargetPeripheral()
        if (p?.state?.value is ConnectionState.Connected) return p
        if (p != null) {
            logDebug("awaitTarget using cached ${p.debugLabel()}")
            return p
        }

        logDebug("awaitTarget start preferred=$preferredAddress seenCount=$seenCount")
        publishStatus("searching")
        if (discoveryJob?.isActive != true) {
            startDiscovery()
        }

        val startedAtMs = System.currentTimeMillis()
        var lastPulseAtMs = startedAtMs
        var restartedDiscovery = false
        while (System.currentTimeMillis() - startedAtMs < AWAIT_TARGET_TIMEOUT_MS) {
            delay(AWAIT_TARGET_STEP_MS)
            p = currentTargetPeripheral()
            if (p?.state?.value is ConnectionState.Connected || p != null) {
                logDebug("awaitTarget resolved ${p.debugLabel()} after ${System.currentTimeMillis() - startedAtMs}ms")
                return p
            }
            val nowMs = System.currentTimeMillis()
            if (!restartedDiscovery && nowMs - startedAtMs >= AWAIT_TARGET_RESTART_AFTER_MS && seenCount == 0) {
                logDebug("awaitTarget restarting discovery after ${nowMs - startedAtMs}ms without matches")
                startDiscovery(forceRestart = true)
                restartedDiscovery = true
            }
            if (nowMs - lastPulseAtMs >= SEARCH_STATUS_PULSE_MS) {
                publishStatus("searching")
                lastPulseAtMs = nowMs
            }
        }
        logWarn("awaitTarget exhausted timeout preferred=$preferredAddress seenCount=$seenCount lastSeen=$lastSeenTag")
        return currentTargetPeripheral()
    }

    private suspend fun writeFrame(peripheral: Peripheral, frameHex: String) {
        val characteristic = findCommandCharacteristic(peripheral)
        if (characteristic == null) {
            logWarn("writeFrame missing command characteristic ${peripheral.debugLabel()} frame=$frameHex")
            return
        }

        logDebug("writeFrame ${peripheral.debugLabel()} frame=$frameHex")
        characteristic.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE)
    }

    private suspend fun writeFrameWithRetry(
        peripheral: Peripheral,
        frameHex: String,
        attempts: Int = 8,
        delayMs: Long = 60,
    ): Boolean {
        repeat(attempts) { attempt ->
            val characteristic = findCommandCharacteristic(peripheral)
            if (characteristic != null) {
                logDebug("writeFrameWithRetry success attempt=${attempt + 1} ${peripheral.debugLabel()} frame=$frameHex")
                characteristic.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE)
                return true
            }
            if (attempt < attempts - 1) {
                if (attempt == 0 || attempt == attempts - 2) {
                    logDebug(
                        "writeFrameWithRetry waiting attempt=${attempt + 1}/${attempts} ${peripheral.debugLabel()} frame=$frameHex",
                    )
                }
                delay(delayMs)
            }
        }
        logWarn("writeFrameWithRetry exhausted ${peripheral.debugLabel()} frame=$frameHex")
        return false
    }

    private suspend fun ensureNotificationObservation(peripheral: Peripheral) {
        if (observingAddress == peripheral.address && notificationJob?.isActive == true) {
            logDebug("ensureNotificationObservation already active ${peripheral.debugLabel()}")
            return
        }

        notificationJob?.cancel()
        val characteristic = findNotifyCharacteristic(peripheral) ?: return
        observingAddress = peripheral.address
        logDebug("subscribing notify characteristic ${peripheral.debugLabel()}")
        notificationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                characteristic.subscribe().collect { data ->
                    val hex = data.toHexString()
                    logDebug("notify ${peripheral.debugLabel()} hex=$hex")
                    parseNotifyFrame(hex)
                }
            } catch (t: Throwable) {
                logWarn("notify observation failed for ${peripheral.debugLabel()}", t)
            }
        }
    }

    private suspend fun findCharacteristic(peripheral: Peripheral, uuid: Uuid): RemoteCharacteristic? {
        logDebug("findCharacteristic ${peripheral.debugLabel()} uuid=$uuid")
        val servicesFlow = peripheral.services(listOf(targetService))
        var service = servicesFlow.value.firstOrNull()
        if (service == null) {
            repeat(6) {
                delay(60)
                service = servicesFlow.value.firstOrNull()
                if (service != null) return@repeat
            }
        }

        if (service == null) {
            logWarn("service missing ${peripheral.debugLabel()} service=$targetService")
            return null
        }

        val characteristic = service!!.characteristics.firstOrNull { it.uuid == uuid }
        if (characteristic == null) {
            logWarn("characteristic missing ${peripheral.debugLabel()} uuid=$uuid")
            return null
        }
        logDebug("characteristic resolved ${peripheral.debugLabel()} uuid=$uuid")
        return characteristic
    }

    private suspend fun findCommandCharacteristic(peripheral: Peripheral): RemoteCharacteristic? =
        findCharacteristic(peripheral, commandChar)

    private suspend fun findNotifyCharacteristic(peripheral: Peripheral): RemoteCharacteristic? =
        findCharacteristic(peripheral, notifyChar)

    private suspend fun waitForCommandCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        repeat(8) {
            val characteristic = findCommandCharacteristic(peripheral)
            if (characteristic != null) return characteristic
            delay(40)
        }
        logWarn("waitForCommandCharacteristic timed out ${peripheral.debugLabel()}")
        return null
    }

    private fun parseNotifyFrame(frameHex: String) {
        LupineProtocol.parseStatusSnapshot(frameHex)?.let { snapshot ->
            logDebug("parseNotifyFrame parsed output=${snapshot.outputTarget} eco=${snapshot.isEco} raw=${snapshot.rawHex}")
            ActualLightState.set(
                appContext,
                when (snapshot.outputTarget) {
                    LupineLampOutputTarget.LOW -> ActualLightState.OutputTarget.LOW
                    LupineLampOutputTarget.HIGH -> ActualLightState.OutputTarget.HIGH
                    LupineLampOutputTarget.OFF -> ActualLightState.OutputTarget.OFF
                    LupineLampOutputTarget.UNKNOWN -> ActualLightState.OutputTarget.UNKNOWN
                },
                snapshot.isEco,
                snapshot.rawHex,
            )
            publishTemperatureStatus("SYNC")
            publishStatus("connected")
            return
        }
        logDebug("parseNotifyFrame ignored raw=$frameHex")
    }

    private fun matchesSupportedFamily(name: String): Boolean =
        SUPPORTED_NAME_PREFIXES.any { prefix -> name.startsWith(prefix, ignoreCase = true) }

    private suspend fun waitUntil(
        timeoutMs: Long,
        stepMs: Long,
        predicate: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(stepMs)
        }
        return predicate()
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = uppercase().replace(" ", "")
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { eachByte -> "%02X".format(eachByte) }

    private fun hasBlePermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun publishStatus(message: String) {
        val normalized = normalizeStatus(message) ?: return
        if (lastPublishedStatus == "connected" && normalized in setOf("searching", "found", "connecting")) {
            return
        }
        if (lastPublishedConnectionStatus == "connected" && normalized in setOf("searching", "found", "connecting")) {
            return
        }
        if (lastPublishedStatus == normalized) return
        lastPublishedStatus = normalized
        onStatus(normalized)
    }

    private fun normalizeStatus(message: String): String? = when {
        message.startsWith("seen[") -> null
        message.startsWith("service missing") -> null
        message.startsWith("characteristic missing") -> null
        message.startsWith("target cached") -> "found"
        message == "found" -> "found"
        message.startsWith("discovery")
            || message.startsWith("waiting for target")
            || message.startsWith("scanning...")
            || message == "searching" -> "searching"
        message.startsWith("no target")
            || message.startsWith("no device") -> "disconnected"
        message.startsWith("connecting") -> "connecting"
        message.startsWith("connected")
            || message.startsWith("sync telemetry") -> "connected"
        message.startsWith("disconnect")
            || message.startsWith("already disconnected") -> "disconnected"
        message.startsWith("missing bluetooth permissions") -> "permissions"
        message.startsWith("ble error")
            || message.startsWith("sync error")
            || message.startsWith("discovery error") -> "error"
        else -> message
    }

    private fun publishConnectionStatus(message: String) {
        if (lastPublishedConnectionStatus == message) return
        lastPublishedConnectionStatus = message
        onConnectionStatus(message)
    }

    private fun publishBatteryStatus(message: String) {
        if (lastPublishedBatteryStatus == message) return
        lastPublishedBatteryStatus = message
        lastBatteryStatusAtMs = System.currentTimeMillis()
        onBatteryStatus(message)
    }

    private fun publishTemperatureStatus(message: String) {
        if (lastPublishedTemperatureStatus == message) return
        lastPublishedTemperatureStatus = message
        lastTemperatureStatusAtMs = System.currentTimeMillis()
        onTemperatureStatus(message)
    }

    fun currentStatus(): String = lastPublishedStatus ?: "idle"

    fun currentConnectionStatus(): String = lastPublishedConnectionStatus ?: "disconnected"

    fun currentBatteryStatus(): String = lastPublishedBatteryStatus ?: "?"

    fun currentTemperatureStatus(): String = lastPublishedTemperatureStatus ?: "?"

    fun hasLiveConnection(): Boolean {
        val peripheral = preferredPeripheral() ?: lastPeripheral
        return peripheral?.state?.value is ConnectionState.Connected
    }

    fun hasConnectInFlight(): Boolean = connectJob?.isActive == true

    fun clearStalePublishedConnectionState() {
        if (!hasConnectInFlight() && !hasLiveConnection() && lastPublishedConnectionStatus != "disconnected") {
            publishConnectionStatus("disconnected")
        }
    }

    private fun cancelActiveJobs() {
        notificationJob?.cancel()
        notificationJob = null
        repeatingCommandJob?.cancel()
        repeatingCommandJob = null
        telemetryBootstrapJob?.cancel()
        telemetryBootstrapJob = null
        connectJob = null
    }

    private fun clearActiveConnectionState(clearCachedPeripheral: Boolean) {
        observingAddress = null
        lastTargetSeenAtMs = 0L
        lastSeenTag = "none"
        if (clearCachedPeripheral) {
            lastPeripheral = null
        }
    }

    private fun resetTelemetryStatus() {
        publishBatteryStatus("?")
        publishTemperatureStatus("?")
        ActualLightState.clear(appContext)
    }

    private suspend fun sendInternal(frameHex: String) {
        if (preferredAddress == null) {
            logWarn("sendInternal aborted: no preferred device frame=$frameHex")
            publishConnectionStatus("no_device_selected")
            publishStatus("searching")
            return
        }
        val cached = currentTargetPeripheral()
        val isConnected = cached?.state?.value is ConnectionState.Connected
        if (!isConnected && cached == null) {
            logDebug("sendInternal needs discovery preferred=$preferredAddress frame=$frameHex")
            startDiscovery()
        }

        val target = awaitTarget()
        if (target == null) {
            logWarn("sendInternal target missing preferred=$preferredAddress frame=$frameHex")
            publishStatus("searching")
            publishConnectionStatus("no_device")
            return
        }

        try {
            logDebug("sendInternal target=${target.debugLabel()} frame=$frameHex")
            ensureConnected(target)
            writeFrame(target, frameHex)
        } catch (t: Throwable) {
            cleanupAfterConnectionFailure(target)
            logWarn("sendInternal failed target=${target.debugLabel()} frame=$frameHex", t)
            publishStatus("ble error: ${t::class.java.simpleName}")
        }
    }
}
