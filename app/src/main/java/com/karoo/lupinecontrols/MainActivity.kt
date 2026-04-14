package com.karoo.lupinecontrols

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.karoo.lupinecontrols.LupineBeamMode
import com.karoo.lupinecontrols.extension.LampCandidate
import com.karoo.lupinecontrols.extension.ActualLightState
import com.karoo.lupinecontrols.extension.AppUiState
import com.karoo.lupinecontrols.extension.LupineControlService
import com.karoo.lupinecontrols.extension.SharedLightState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private enum class OutputTarget {
        LOW,
        HIGH,
        OFF,
    }

    private enum class RemoteFeedback {
        NONE,
        CONNECTED,
        DISCONNECTED,
    }

    companion object {
        private const val PREFS_NAME = "lupine_prefs"
        private const val PREF_SELECTED_LAMP_ADDRESS = "selected_lamp_address"
        private const val PREF_SELECTED_LAMP_NAME = "selected_lamp_name"
        private const val PROFILE_AUTO = 25
        private const val PROFILE_AUTO_ECO = 50
        private const val PROFILE_MANUAL = 75
        private const val PROFILE_MANUAL_ECO = 100
        private const val TAP_MAX_MS = 350L
        private const val LONG_PRESS_MS = 750L
        private const val SEARCH_HOLD_MS = 3_000L
        private const val UNPAIR_PRESS_MS = 10_000L
        private const val PAIRING_TIMEOUT_MS = 30_000L
        private const val CONNECT_FEEDBACK_MS = 3_000L
        private const val DISCONNECT_FEEDBACK_MS = 4_000L
    }

    private var controlService: LupineControlService? = null
    private lateinit var chooserGate: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var chooserHintView: TextView
    private lateinit var remoteT1Button: View
    private lateinit var remoteT2Button: View
    private lateinit var remoteT1Light: View
    private lateinit var remoteT2Light: View
    private lateinit var remoteT1Icon: ImageView
    private lateinit var prefs: android.content.SharedPreferences
    private var currentConnectionStatus: String = "disconnected"
    private var currentBatteryStatus: String = "?"
    private var currentTemperatureStatus: String = "?"
    private var currentDisplayStatus: String = "idle"
    private var requestedProfilePercent: Int = PROFILE_AUTO
    private var currentSelectedLampAddress: String? = null
    private var currentSelectedLampName: String? = null
    private var discoveryRequestedFromUi: Boolean = false
    private var lastConnectionMessage: String? = null
    private var remoteT1DownAtMs: Long = 0L
    private var remoteT2DownAtMs: Long = 0L
    private var searchStartedFromHold: Boolean = false
    private var activeRemoteFeedback: RemoteFeedback = RemoteFeedback.NONE
    private var remoteFeedbackUntilMs: Long = 0L
    private var pairingTimeoutJob: Job? = null
    private var remoteSearchHoldJob: Job? = null
    private val serviceListener = object : LupineControlService.Listener {
        override fun onStatus(status: String) {
            runOnUiThread {
                currentDisplayStatus = displayStatus(status)
                refreshLampSelectionUi()
            }
        }

        override fun onConnectionStatus(status: String) {
            runOnUiThread {
                val previousStatus = currentConnectionStatus
                currentConnectionStatus = status
                if (status == "connected" && previousStatus != "connected") {
                    discoveryRequestedFromUi = false
                    cancelPairingTimeout()
                    lastConnectionMessage = getString(R.string.connection_message_success)
                    showRemoteFeedback(RemoteFeedback.CONNECTED, CONNECT_FEEDBACK_MS)
                } else if (previousStatus == "connected" && status == "disconnected") {
                    discoveryRequestedFromUi = false
                    cancelPairingTimeout()
                    lastConnectionMessage = getString(R.string.connection_message_disconnected)
                    showRemoteFeedback(RemoteFeedback.DISCONNECTED, DISCONNECT_FEEDBACK_MS)
                } else if (isConnectionInProgress(status)) {
                    lastConnectionMessage = getString(R.string.connection_message_in_progress)
                } else {
                    cancelPairingTimeout()
                    lastConnectionMessage = connectionMessageForStatus(status)
                }
                refreshLampSelectionUi()
                updateOutputControls()
            }
        }

        override fun onBatteryStatus(status: String) {
            runOnUiThread {
                currentBatteryStatus = status
                updateStatusCards()
            }
        }

        override fun onTemperatureStatus(status: String) {
            runOnUiThread {
                currentTemperatureStatus = status
                updateStatusCards()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? LupineControlService.LocalBinder)?.getService() ?: return
            controlService = service
            service.registerListener(serviceListener)
            restoreSelectedLamp()
            refreshUiFromController()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controlService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            restoreSelectedLamp()
        } else {
            currentDisplayStatus = "permissions"
            refreshLampSelectionUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        chooserGate = findViewById(R.id.lampChooserGate)
        controlPanel = findViewById(R.id.layoutControlPanel)
        chooserHintView = findViewById(R.id.txtChooserHint)
        remoteT1Button = findViewById(R.id.remoteT1Button)
        remoteT2Button = findViewById(R.id.remoteT2Button)
        remoteT1Light = findViewById(R.id.remoteT1Light)
        remoteT2Light = findViewById(R.id.remoteT2Light)
        remoteT1Icon = findViewById(R.id.remoteT1Icon)
        bindService(
            Intent(this, LupineControlService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (!hasPermissions()) {
            ensurePermissions()
        }
        lifecycleScope.launch {
            while (true) {
                refreshUiFromController()
                delay(500)
            }
        }

        remoteT1Button.setOnTouchListener { _, event -> handleRemoteT1Touch(event) }
        remoteT2Button.setOnTouchListener { _, event -> handleRemoteT2Touch(event) }
        updateOutputControls()
        updateStatusCards()
        refreshLampSelectionUi()
    }

    override fun onResume() {
        super.onResume()
        AppUiState.setActive(this, true)
        refreshRequestedProfile()
        updateOutputControls()
        updateStatusCards()
    }

    override fun onPause() {
        AppUiState.setActive(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        cancelPairingTimeout()
        cancelRemoteSearchStart()
        if (isFinishing) {
            AppUiState.setActive(this, false)
        }
        controlService?.unregisterListener(serviceListener)
        runCatching { unbindService(serviceConnection) }
        if (isFinishing) {
            controlService = null
        }
        super.onDestroy()
    }

    private fun connectIfPermitted() {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, getString(R.string.toast_grant_bluetooth_permissions), Toast.LENGTH_SHORT).show()
            return
        }
        saveSelectedLamp(null, null)
        controlService?.setPreferredAddress(null)
        discoveryRequestedFromUi = true
        currentDisplayStatus = "searching"
        lastConnectionMessage = getString(R.string.connection_message_searching)
        startPairingTimeout()
        controlService?.startDiscovery(forceRestart = true)
        refreshLampSelectionUi()
    }

    private fun sendIfPermitted(frame: String) {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, getString(R.string.toast_grant_bluetooth_permissions), Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, getString(R.string.toast_select_lamp_first), Toast.LENGTH_SHORT).show()
            return
        }
        controlService?.send(frame)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        if (!hasPermissions()) permissionLauncher.launch(requiredPermissions())
    }

    private fun refreshUiFromController() {
        refreshLampSelectionUi()
        refreshRequestedProfile()
        val service = controlService ?: return
        val connectionStatus = service.currentConnectionStatus()
        val batteryStatus = service.currentBatteryStatus()
        val temperatureStatus = service.currentTemperatureStatus()
        val status = service.currentStatus()

        if (currentConnectionStatus != connectionStatus) {
            currentConnectionStatus = connectionStatus
        }
        if (currentBatteryStatus != batteryStatus) {
            currentBatteryStatus = batteryStatus
        }
        if (currentTemperatureStatus != temperatureStatus) {
            currentTemperatureStatus = temperatureStatus
        }
        val displayStatus = displayStatus(status)
        val effectiveDisplayStatus = when {
            connectionStatus == "connected" -> "connected"
            connectionStatus == "connecting" -> "connecting"
            displayStatus == "found" -> "found"
            !discoveryRequestedFromUi && displayStatus == "searching" -> "idle"
            else -> displayStatus
        }
        if (currentDisplayStatus != effectiveDisplayStatus) {
            currentDisplayStatus = effectiveDisplayStatus
        }
        if (activeRemoteFeedback != RemoteFeedback.NONE && SystemClock.elapsedRealtime() >= remoteFeedbackUntilMs) {
            activeRemoteFeedback = RemoteFeedback.NONE
        }
        refreshLampSelectionUi()
        updateOutputControls()
        updateStatusCards()
    }

    private fun refreshRequestedProfile() {
        val snapshot = SharedLightState.get(this)
        requestedProfilePercent = snapshot.lastOnLevelPercent ?: PROFILE_AUTO
    }

    private fun restoreSelectedLamp() {
        val savedAddress = prefs.getString(PREF_SELECTED_LAMP_ADDRESS, null)
        val savedName = prefs.getString(PREF_SELECTED_LAMP_NAME, null)
        currentSelectedLampAddress = savedAddress
        currentSelectedLampName = savedName
        controlService?.setPreferredAddress(savedAddress)
    }

    private fun saveSelectedLamp(address: String?, name: String? = null) {
        currentSelectedLampAddress = address
        currentSelectedLampName = name
        prefs.edit()
            .putString(PREF_SELECTED_LAMP_ADDRESS, address)
            .putString(PREF_SELECTED_LAMP_NAME, name)
            .apply()
    }

    private fun refreshLampSelectionUi() {
        val service = controlService
        val candidates = service?.currentLampCandidates() ?: emptyList()
        maybeAutoConnectDiscoveredLamp(candidates)
        val preferredAddress = service?.currentPreferredAddress() ?: currentSelectedLampAddress
        currentSelectedLampAddress = preferredAddress
        chooserGate.visibility = android.view.View.VISIBLE
        controlPanel.visibility = android.view.View.VISIBLE

        chooserHintView.text = buildChooserHintText()
    }

    private fun handleRemoteT1Touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                remoteT1DownAtMs = SystemClock.elapsedRealtime()
                searchStartedFromHold = false
                if (currentConnectionStatus != "connected") {
                    scheduleRemoteSearchStart()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val durationMs = SystemClock.elapsedRealtime() - remoteT1DownAtMs
                cancelRemoteSearchStart()
                when {
                    currentConnectionStatus == "connected" && durationMs >= UNPAIR_PRESS_MS -> disconnectFromRemote()
                    currentConnectionStatus == "connected" && durationMs >= LONG_PRESS_MS -> switchLampOff()
                    currentConnectionStatus == "connected" && durationMs <= TAP_MAX_MS -> applyShortPress(OutputTarget.LOW)
                }
                remoteT1Button.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                remoteT1DownAtMs = 0L
                cancelRemoteSearchStart()
                return true
            }
        }
        return false
    }

    private fun handleRemoteT2Touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                remoteT2DownAtMs = SystemClock.elapsedRealtime()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val durationMs = SystemClock.elapsedRealtime() - remoteT2DownAtMs
                if (currentConnectionStatus == "connected") {
                    if (durationMs >= LONG_PRESS_MS) {
                        cycleProfile(1)
                    } else if (durationMs <= TAP_MAX_MS) {
                        applyShortPress(OutputTarget.HIGH)
                    }
                }
                remoteT2Button.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                remoteT2DownAtMs = 0L
                return true
            }
        }
        return false
    }

    private fun applyShortPress(target: OutputTarget) {
        controlService?.stopRepeatingCommand()
        controlService?.applyBeamMode(
            if (target == OutputTarget.HIGH) LupineBeamMode.HIGH_BEAM else LupineBeamMode.LOW_BEAM,
        )
    }

    private fun cycleProfile(direction: Int) {
        val profiles = listOf(PROFILE_AUTO, PROFILE_AUTO_ECO, PROFILE_MANUAL, PROFILE_MANUAL_ECO)
        val currentIndex = profiles.indexOf(requestedProfilePercent).coerceAtLeast(0)
        val nextIndex = (currentIndex + direction).floorMod(profiles.size)
        requestedProfilePercent = profiles[nextIndex]
        updateStatusCards()
    }

    private fun switchLampOff() {
        controlService?.stopRepeatingCommand()
        controlService?.applyBeamMode(LupineBeamMode.OFF)
    }

    private fun disconnectFromRemote() {
        discoveryRequestedFromUi = false
        cancelRemoteSearchStart()
        controlService?.stopRepeatingCommand()
        controlService?.disconnect()
        saveSelectedLamp(null, null)
        controlService?.setPreferredAddress(null)
        currentDisplayStatus = "idle"
        refreshLampSelectionUi()
        updateOutputControls()
    }

    private fun updateOutputControls() {
        tintRemoteButtons()
    }

    private fun updateStatusCards() = Unit

    private fun tintRemoteButtons() {
        val remoteInactive = ResourcesCompat.getColor(resources, R.color.remote_inactive_fill, theme)
        val iconDefault = ResourcesCompat.getColor(resources, android.R.color.white, theme)
        val lowColor = ResourcesCompat.getColor(resources, R.color.lupine_low_beam, theme)
        val lowEcoColor = ResourcesCompat.getColor(resources, R.color.lupine_low_beam_eco, theme)
        val highColor = ResourcesCompat.getColor(resources, R.color.lupine_high_beam, theme)
        val highEcoColor = ResourcesCompat.getColor(resources, R.color.lupine_high_beam_eco, theme)
        val pairSuccessColor = ResourcesCompat.getColor(resources, R.color.lupine_pair_success, theme)
        val disconnectColor = ResourcesCompat.getColor(resources, R.color.lupine_disconnect, theme)
        val actualSnapshot = ActualLightState.get(this)

        remoteT1Light.background?.setTint(remoteInactive)
        remoteT1Light.alpha = 1f
        remoteT2Light.background?.setTint(remoteInactive)
        remoteT2Light.alpha = 1f

        val iconTint = when {
            activeRemoteFeedback == RemoteFeedback.CONNECTED && SystemClock.elapsedRealtime() < remoteFeedbackUntilMs -> pairSuccessColor
            activeRemoteFeedback == RemoteFeedback.DISCONNECTED && SystemClock.elapsedRealtime() < remoteFeedbackUntilMs -> disconnectColor
            else -> when (actualSnapshot.outputTarget) {
                ActualLightState.OutputTarget.LOW -> if (actualSnapshot.isEco) lowEcoColor else lowColor
                ActualLightState.OutputTarget.HIGH -> if (actualSnapshot.isEco) highEcoColor else highColor
                ActualLightState.OutputTarget.OFF,
                ActualLightState.OutputTarget.UNKNOWN -> iconDefault
            }
        }
        remoteT1Icon.setColorFilter(iconTint)
    }

    private fun showRemoteFeedback(feedback: RemoteFeedback, durationMs: Long) {
        activeRemoteFeedback = feedback
        remoteFeedbackUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    private fun startPairingTimeout() {
        cancelPairingTimeout()
        pairingTimeoutJob = lifecycleScope.launch {
            delay(PAIRING_TIMEOUT_MS)
            if (!discoveryRequestedFromUi || currentConnectionStatus == "connected") return@launch
            discoveryRequestedFromUi = false
            controlService?.cancelConnectionAttempt()
            lastConnectionMessage = getString(R.string.connection_message_timeout)
            currentDisplayStatus = "idle"
            refreshLampSelectionUi()
            updateOutputControls()
            Toast.makeText(
                this@MainActivity,
                getString(R.string.toast_pairing_timeout),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
    }

    private fun buildChooserHintText(): String {
        val actionText = when {
            currentConnectionStatus == "connected" -> getString(R.string.hold_main_button_to_disconnect)
            discoveryRequestedFromUi || isConnectionInProgress(currentConnectionStatus) -> getString(R.string.connection_message_searching_action)
            else -> getString(R.string.hold_main_button_to_pair)
        }
        val message = lastConnectionMessage?.takeIf { it.isNotBlank() } ?: return actionText
        return "$message\n$actionText"
    }

    private fun maybeAutoConnectDiscoveredLamp(candidates: List<LampCandidate>) {
        if (!discoveryRequestedFromUi) return
        if (currentConnectionStatus == "connecting" || currentConnectionStatus == "connected") return
        if (candidates.isEmpty()) return

        val candidate = candidates.first()
        saveSelectedLamp(candidate.address, candidate.name)
        controlService?.setPreferredAddress(candidate.address)
        currentDisplayStatus = "connecting"
        lastConnectionMessage = getString(R.string.connection_message_auto_connecting, candidate.name)
        controlService?.connect()
    }

    private fun scheduleRemoteSearchStart() {
        cancelRemoteSearchStart()
        remoteSearchHoldJob = lifecycleScope.launch {
            delay(SEARCH_HOLD_MS)
            if (currentConnectionStatus == "connected") return@launch
            searchStartedFromHold = true
            connectIfPermitted()
        }
    }

    private fun cancelRemoteSearchStart() {
        remoteSearchHoldJob?.cancel()
        remoteSearchHoldJob = null
    }

    private fun isConnectionInProgress(status: String): Boolean =
        status == "connecting" || status == "searching"

    private fun connectionMessageForStatus(status: String): String? = when {
        status == "permissions" -> getString(R.string.connection_message_permissions)
        status == "no_device" -> getString(R.string.connection_message_no_device)
        status == "no_device_selected" -> getString(R.string.connection_message_select_device)
        status == "pairing_timeout" -> getString(R.string.connection_message_timeout)
        status.startsWith("discovery_error:") -> getString(
            R.string.connection_message_discovery_error,
            humanReadableConnectionReason(status.substringAfter(':', getString(R.string.connection_reason_unknown))),
        )
        status.startsWith("ble_error:") -> getString(
            R.string.connection_message_ble_error,
            humanReadableConnectionReason(status.substringAfter(':', getString(R.string.connection_reason_unknown))),
        )
        status == "disconnected" -> getString(R.string.connection_message_disconnected)
        else -> null
    }

    private fun humanReadableConnectionReason(reason: String): String {
        val normalized = reason.trim().lowercase()
        return when {
            normalized.contains("timeout") -> getString(R.string.connection_reason_timeout)
            normalized.contains("security") -> getString(R.string.connection_reason_permissions)
            normalized.contains("gatt") -> getString(R.string.connection_reason_gatt)
            normalized.contains("illegalstate") -> getString(R.string.connection_reason_try_again)
            normalized.contains("cancel") -> getString(R.string.connection_reason_cancelled)
            normalized.contains("io") -> getString(R.string.connection_reason_try_again)
            normalized.contains("nullpointer") -> getString(R.string.connection_reason_internal)
            else -> reason.takeIf { it.isNotBlank() } ?: getString(R.string.connection_reason_unknown)
        }
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private fun displayStatus(raw: String): String = when {
        raw.startsWith("seen[") -> "searching"
        raw.startsWith("discovery")
            || raw.startsWith("waiting for target")
            || raw.startsWith("scanning...")
            || raw.startsWith("search") -> "searching"
        raw.startsWith("target cached")
            || raw.startsWith("found") -> "found"
        raw.startsWith("connected")
            || raw.startsWith("sync telemetry")
            || raw.startsWith("writing")
            || raw.startsWith("write ok")
            || raw.startsWith("send requested")
            || raw == "connected" -> "connected"
        raw.startsWith("disconnect") || raw == "disconnected" -> "disconnected"
        raw.startsWith("missing bluetooth permissions") -> "permissions"
        raw.startsWith("ble error") || raw.startsWith("sync error") -> "error"
        raw.startsWith("no target") || raw.startsWith("no device") -> "searching"
        else -> raw
    }

}
