package com.vpnproject.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.vpnproject.app.engine.EngineKind
import com.vpnproject.app.engine.EngineState
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.HttpsEgressIpResolver
import com.vpnproject.app.engine.NetworkRebindPolicy
import com.vpnproject.app.engine.WireGuardBackendStatsSource
import com.vpnproject.app.engine.WireGuardConnectionVerifier
import com.vpnproject.app.engine.WireGuardTunnelHandle
import com.vpnproject.app.engine.WireGuardVerificationPolicy
import com.vpnproject.app.engine.WireGuardVerificationResult
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * First real connection engine: official WireGuard userspace GoBackend.
 *
 * The service receives an already-rendered runtime config. The config may be
 * pinned to a public IP by RuntimeConfigPreparer, while WireGuard's peer public
 * key still authenticates the server. Config text is never logged or uploaded.
 */
class WireGuardVpnService : GoBackend.VpnService() {
    private val starting = AtomicBoolean(false)
    private val verificationRunning = AtomicBoolean(false)
    private val verificationGeneration = AtomicInteger(0)
    private val rebindRunning = AtomicBoolean(false)
    private val rebindPolicy = NetworkRebindPolicy(
        minIntervalMs = REBIND_MIN_INTERVAL_MS,
        debounceMs = REBIND_DEBOUNCE_MS
    )

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: WireGuardTunnelHandle? = null

    @Volatile
    private var currentConfig: Config? = null

    @Volatile
    private var verificationThread: Thread? = null

    @Volatile
    private var rebindThread: Thread? = null

    @Volatile
    private var networkCallbackRegistered: Boolean = false

    @Volatile
    private var lastRebindRequestedAtEpochMs: Long = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleNetworkRebind("network available")
        }

        override fun onLost(network: Network) {
            scheduleNetworkRebind("network lost")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleNetworkRebind("network capabilities changed: ${networkCapabilities.summary()}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundNotification("WireGuard engine is preparing…")
        updateStatus(EngineState.IDLE, "WireGuard service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val configText = intent?.getStringExtra(EXTRA_CONFIG_TEXT)
                val tunnelName = intent?.getStringExtra(EXTRA_TUNNEL_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_TUNNEL_NAME
                val note = intent?.getStringExtra(EXTRA_NOTE)
                if (configText.isNullOrBlank()) {
                    updateStatus(EngineState.FAILED, "WireGuard config is missing.")
                    startForegroundNotification("WireGuard config is missing.")
                    stopSelf()
                    Service.START_NOT_STICKY
                } else {
                    startWireGuardTunnel(configText, tunnelName, note)
                    Service.START_STICKY
                }
            }
            ACTION_STOP -> {
                stopWireGuardTunnel()
                stopSelf()
                Service.START_NOT_STICKY
            }
            else -> Service.START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopWireGuardTunnel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        stopWireGuardTunnel()
        super.onRevoke()
    }

    private fun startWireGuardTunnel(configText: String, tunnelName: String, note: String?) {
        if (!starting.compareAndSet(false, true)) {
            updateStatus(EngineState.CONNECTING, "WireGuard start is already in progress.", note)
            return
        }

        updateStatus(EngineState.CONNECTING, "Starting WireGuard engine…", note)
        startForegroundNotification(note?.let { "Starting WireGuard. $it" } ?: "Starting WireGuard engine…")

        Thread({
            try {
                stopVerification()
                stopRebind()
                stopExistingTunnelForRestart()
                val parsedConfig = Config.parse(configText.byteInputStream(Charsets.UTF_8))
                val tunnelHandle = WireGuardTunnelHandle(tunnelName) { state ->
                    when (state) {
                        Tunnel.State.UP -> {
                            updateStatus(EngineState.RUNNING, "WireGuard tunnel state is UP; verification is pending.", note)
                            startForegroundNotification("WireGuard tunnel is UP. Verifying traffic and egress…")
                        }
                        Tunnel.State.DOWN -> {
                            updateStatus(EngineState.STOPPED, "WireGuard tunnel state is DOWN.")
                            startForegroundNotification("WireGuard tunnel is stopped.")
                        }
                        Tunnel.State.TOGGLE -> Unit
                    }
                }
                val goBackend = backend ?: GoBackend(this)

                setCurrentUnderlyingNetwork()
                val state = goBackend.setState(tunnelHandle, Tunnel.State.UP, parsedConfig)

                backend = goBackend
                tunnel = tunnelHandle
                currentConfig = parsedConfig

                if (state == Tunnel.State.UP) {
                    registerNetworkCallback()
                    updateStatus(EngineState.RUNNING, "WireGuard engine started; verification is running.", note)
                    startForegroundNotification("WireGuard engine started. Verifying handshake traffic and egress IP…")
                    startVerification(goBackend, tunnelHandle, note)
                } else {
                    updateStatus(EngineState.FAILED, "WireGuard did not enter UP state: $state", note)
                    startForegroundNotification("WireGuard did not enter UP state: $state")
                }
            } catch (e: Exception) {
                updateStatus(
                    EngineState.FAILED,
                    "WireGuard failed: ${e.message ?: e.javaClass.simpleName}",
                    note
                )
                startForegroundNotification("WireGuard failed: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            } finally {
                starting.set(false)
            }
        }, "wireguard-engine-start").start()
    }

    private fun startVerification(goBackend: GoBackend, tunnelHandle: WireGuardTunnelHandle, note: String?) {
        stopVerification()
        val generation = verificationGeneration.incrementAndGet()
        verificationRunning.set(true)
        val thread = Thread({
            updateStatus(EngineState.VERIFYING, "Checking WireGuard traffic and public egress IP…", note)
            startForegroundNotification("Verifying WireGuard tunnel: traffic + egress IP…")

            val verifier = WireGuardConnectionVerifier(
                statsSource = WireGuardBackendStatsSource(goBackend, tunnelHandle),
                egressIpResolver = HttpsEgressIpResolver(),
                policy = WireGuardVerificationPolicy(
                    maxAttempts = VERIFY_ATTEMPTS,
                    intervalMs = VERIFY_INTERVAL_MS,
                    minTrafficDeltaBytes = VERIFY_MIN_TRAFFIC_DELTA_BYTES
                ),
                sleeper = { millis ->
                    if (verificationRunning.get() && verificationGeneration.get() == generation) {
                        Thread.sleep(millis)
                    }
                }
            )
            val result = runCatching { verifier.verify() }.getOrElse { error ->
                WireGuardVerificationResult(
                    verified = false,
                    statsMoved = false,
                    egressVerified = false,
                    reason = if (verificationRunning.get()) {
                        "Verification failed: ${error.message ?: error.javaClass.simpleName}"
                    } else {
                        "Verification cancelled."
                    },
                    rxBytes = 0L,
                    txBytes = 0L,
                    attempts = 0
                )
            }

            if (!verificationRunning.get() || verificationGeneration.get() != generation) return@Thread
            updateStatusFromVerification(result, note)
            startForegroundNotification(notificationTextFor(result))
        }, "wireguard-engine-verify")
        thread.isDaemon = true
        verificationThread = thread
        thread.start()
    }

    private fun updateStatusFromVerification(result: WireGuardVerificationResult, note: String?) {
        if (result.verified) {
            updateStatus(
                state = EngineState.VERIFIED,
                message = "WireGuard verified: traffic moved and public egress IP is ${result.egressIp}.",
                detail = note ?: result.reason,
                rxBytes = result.rxBytes,
                txBytes = result.txBytes,
                egressIp = result.egressIp,
                verified = true
            )
        } else {
            updateStatus(
                state = EngineState.RUNNING,
                message = "WireGuard is running but not fully verified: ${result.reason}",
                detail = note,
                rxBytes = result.rxBytes,
                txBytes = result.txBytes,
                egressIp = result.egressIp,
                verified = false
            )
        }
    }

    private fun notificationTextFor(result: WireGuardVerificationResult): String {
        return if (result.verified) {
            "WireGuard verified. Egress IP: ${result.egressIp}. RX ${result.rxBytes} B / TX ${result.txBytes} B."
        } else {
            "WireGuard running, not verified yet. ${result.reason} RX ${result.rxBytes} B / TX ${result.txBytes} B."
        }
    }

    private fun scheduleNetworkRebind(reason: String) {
        val goBackend = backend ?: return
        val tunnelHandle = tunnel ?: return
        currentConfig ?: return
        val now = System.currentTimeMillis()
        val isRunning = runCatching { goBackend.getState(tunnelHandle) == Tunnel.State.UP }
            .getOrElse { tunnelHandle.state() == Tunnel.State.UP }
        val decision = rebindPolicy.evaluate(
            isRunning = isRunning,
            nowEpochMs = now,
            lastRequestedEpochMs = lastRebindRequestedAtEpochMs,
            reason = reason
        )
        if (!decision.shouldSchedule) return
        lastRebindRequestedAtEpochMs = now
        if (!rebindRunning.compareAndSet(false, true)) return

        val thread = Thread({
            try {
                if (decision.delayMs > 0L) Thread.sleep(decision.delayMs)
                if (!rebindRunning.get()) return@Thread

                val activeBackend = backend ?: return@Thread
                val activeTunnel = tunnel ?: return@Thread
                val activeConfig = currentConfig ?: return@Thread

                updateStatus(
                    EngineState.RECONNECTING,
                    "Network changed; rebinding WireGuard…",
                    decision.reason
                )
                startForegroundNotification("Network changed. Rebinding WireGuard…")

                setCurrentUnderlyingNetwork()
                val state = activeBackend.setState(activeTunnel, Tunnel.State.UP, activeConfig)
                if (state == Tunnel.State.UP) {
                    val reverifyReason = "Rebound after network change: ${decision.reason}"
                    updateStatus(EngineState.RUNNING, "WireGuard rebound; verification restarted.", reverifyReason)
                    startForegroundNotification("WireGuard rebound after network change. Re-verifying…")
                    startVerification(activeBackend, activeTunnel, reverifyReason)
                } else {
                    updateStatus(
                        EngineState.FAILED,
                        "WireGuard rebind did not return UP state: $state",
                        decision.reason
                    )
                    startForegroundNotification("WireGuard rebind failed: $state")
                }
            } catch (_: InterruptedException) {
                // Expected when stopping or when a newer lifecycle action cancels rebind.
            } catch (e: Exception) {
                updateStatus(
                    EngineState.FAILED,
                    "WireGuard rebind failed: ${e.message ?: e.javaClass.simpleName}",
                    decision.reason
                )
                startForegroundNotification("WireGuard rebind failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                rebindRunning.set(false)
            }
        }, "wireguard-network-rebind")
        thread.isDaemon = true
        rebindThread = thread
        thread.start()
    }

    private fun stopExistingTunnelForRestart() {
        unregisterNetworkCallback()
        val goBackend = backend
        val tunnelHandle = tunnel
        if (goBackend != null && tunnelHandle != null) {
            runCatching { goBackend.setState(tunnelHandle, Tunnel.State.DOWN, null) }
        }
        backend = null
        tunnel = null
        currentConfig = null
    }

    private fun stopWireGuardTunnel() {
        stopVerification()
        stopRebind()
        unregisterNetworkCallback()
        updateStatus(EngineState.STOPPING, "Stopping WireGuard engine…")
        try {
            val goBackend = backend
            val tunnelHandle = tunnel
            if (goBackend != null && tunnelHandle != null) {
                goBackend.setState(tunnelHandle, Tunnel.State.DOWN, null)
            }
            updateStatus(EngineState.STOPPED, "WireGuard engine stopped.")
        } catch (e: Exception) {
            updateStatus(EngineState.FAILED, "WireGuard stop failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            backend = null
            tunnel = null
            currentConfig = null
            starting.set(false)
        }
    }

    private fun stopVerification() {
        verificationRunning.set(false)
        verificationGeneration.incrementAndGet()
        verificationThread?.interrupt()
        verificationThread = null
    }

    private fun stopRebind() {
        rebindRunning.set(false)
        rebindThread?.interrupt()
        rebindThread = null
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivityManager = connectivityManager() ?: return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val connectivityManager = connectivityManager() ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
    }

    private fun setCurrentUnderlyingNetwork() {
        val connectivityManager = connectivityManager() ?: return
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else {
            null
        }
        runCatching {
            setUnderlyingNetworks(activeNetwork?.let { arrayOf(it) } ?: emptyArray())
        }
    }

    private fun connectivityManager(): ConnectivityManager? =
        runCatching { getSystemService(ConnectivityManager::class.java) }.getOrNull()

    private fun NetworkCapabilities.summary(): String {
        val transports = buildList {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile")
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }.ifEmpty { listOf("unknown") }
        val validated = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not-validated"
        return transports.joinToString("+") + "/" + validated
    }

    private fun updateStatus(
        state: EngineState,
        message: String,
        detail: String? = null,
        rxBytes: Long? = null,
        txBytes: Long? = null,
        egressIp: String? = null,
        verified: Boolean = false
    ) {
        lastStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = state,
            message = message,
            detail = detail,
            rxBytes = rxBytes,
            txBytes = txBytes,
            egressIp = egressIp,
            verified = verified
        )
    }

    private fun startForegroundNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, WireGuardVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("VPN Project WireGuard")
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WireGuard engine status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the WireGuard engine connection status."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.vpnproject.app.vpn.action.START_WIREGUARD"
        const val ACTION_STOP = "com.vpnproject.app.vpn.action.STOP_WIREGUARD"
        const val EXTRA_CONFIG_TEXT = "com.vpnproject.app.vpn.extra.CONFIG_TEXT"
        const val EXTRA_TUNNEL_NAME = "com.vpnproject.app.vpn.extra.TUNNEL_NAME"
        const val EXTRA_NOTE = "com.vpnproject.app.vpn.extra.NOTE"

        private const val CHANNEL_ID = "wireguard_engine_status"
        private const val NOTIFICATION_ID = 51
        private const val STOP_REQUEST_CODE = 52
        private const val DEFAULT_TUNNEL_NAME = "vpn-project-wg"
        private const val VERIFY_ATTEMPTS = 6
        private const val VERIFY_INTERVAL_MS = 1_500L
        private const val VERIFY_MIN_TRAFFIC_DELTA_BYTES = 1L
        private const val REBIND_MIN_INTERVAL_MS = 2_000L
        private const val REBIND_DEBOUNCE_MS = 750L

        @Volatile
        var lastStatus: EngineStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.IDLE,
            message = "WireGuard engine has not started."
        )
            private set
    }
}
