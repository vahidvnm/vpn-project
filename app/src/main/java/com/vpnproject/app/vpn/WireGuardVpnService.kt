package com.vpnproject.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import com.vpnproject.app.engine.EngineKind
import com.vpnproject.app.engine.EngineState
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.HttpsEgressIpResolver
import com.vpnproject.app.engine.WireGuardBackendStatsSource
import com.vpnproject.app.engine.WireGuardConnectionVerifier
import com.vpnproject.app.engine.WireGuardTunnelHandle
import com.vpnproject.app.engine.WireGuardVerificationPolicy
import com.vpnproject.app.engine.WireGuardVerificationResult
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.atomic.AtomicBoolean

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

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: WireGuardTunnelHandle? = null

    @Volatile
    private var currentConfig: Config? = null

    @Volatile
    private var verificationThread: Thread? = null

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
                    if (verificationRunning.get()) Thread.sleep(millis)
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

            if (!verificationRunning.get()) return@Thread
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

    private fun stopWireGuardTunnel() {
        stopVerification()
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
        verificationThread?.interrupt()
        verificationThread = null
    }

    private fun setCurrentUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return
        runCatching { setUnderlyingNetworks(arrayOf(activeNetwork)) }
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

        @Volatile
        var lastStatus: EngineStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.IDLE,
            message = "WireGuard engine has not started."
        )
            private set
    }
}
