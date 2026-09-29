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
import com.vpnproject.app.engine.WireGuardTunnelHandle
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

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: WireGuardTunnelHandle? = null

    @Volatile
    private var currentConfig: Config? = null

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
                val parsedConfig = Config.parse(configText.byteInputStream(Charsets.UTF_8))
                val tunnelHandle = WireGuardTunnelHandle(tunnelName) { state ->
                    when (state) {
                        Tunnel.State.UP -> {
                            updateStatus(EngineState.RUNNING, "WireGuard tunnel state is UP.", note)
                            startForegroundNotification("WireGuard tunnel is UP. Verification comes next.")
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
                    updateStatus(EngineState.RUNNING, "WireGuard engine started.", note)
                    startForegroundNotification("WireGuard engine started. Handshake verification is next.")
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

    private fun stopWireGuardTunnel() {
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

    private fun setCurrentUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return
        runCatching { setUnderlyingNetworks(arrayOf(activeNetwork)) }
    }

    private fun updateStatus(state: EngineState, message: String, detail: String? = null) {
        lastStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = state,
            message = message,
            detail = detail
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

        @Volatile
        var lastStatus: EngineStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.IDLE,
            message = "WireGuard engine has not started."
        )
            private set
    }
}
