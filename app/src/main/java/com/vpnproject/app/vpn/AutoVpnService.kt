package com.vpnproject.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase-3 bootstrap VpnService.
 *
 * This establishes and owns the Android TUN interface, full IPv4 route, an
 * IPv6 fail-closed route, DNS policy, foreground notification, and socket-protection boundary. It does not
 * yet forward packets to OpenVPN/WireGuard; until an engine is added, the packet
 * pump deliberately drains and drops packets so the service is safe to stop and
 * reason about during development.
 */
class AutoVpnService : VpnService() {
    private val running = AtomicBoolean(false)

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var packetPumpThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                Service.START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundNotification("Starting VPN bootstrap tunnel…")
                startTunnel(VpnTunnelPolicy.defaultFullTunnel())
                Service.START_STICKY
            }
            else -> Service.START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    fun socketProtector(): VpnServiceSocketProtector = VpnServiceSocketProtector(this)

    private fun startTunnel(policy: VpnTunnelPolicy) {
        if (running.get()) {
            startForegroundNotification("VPN bootstrap tunnel is already running.")
            return
        }

        val validationErrors = policy.validate()
        if (validationErrors.isNotEmpty()) {
            startForegroundNotification("VPN policy error: ${validationErrors.first()}")
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession(policy.sessionName)
                .setMtu(policy.mtu)
                .setBlocking(false)
                .addAddress(policy.tunAddress.address, policy.tunAddress.prefixLength)

            if (policy.blockIpv6OutsideTunnel) {
                // This bootstrap engine drops packets; capturing IPv6 here is fail-closed.
                builder.addAddress(BOOTSTRAP_IPV6_TUN_ADDRESS, 128)
                    .addRoute("::", 0)
            }

            policy.routes.forEach { route ->
                builder.addRoute(route.address, route.prefixLength)
            }
            policy.dnsServers.forEach { dns ->
                builder.addDnsServer(dns)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            applySplitTunnelPolicy(builder, policy)

            val established = builder.establish()
            if (established == null) {
                startForegroundNotification("VPN permission is missing or tunnel could not be established.")
                stopSelf()
                return
            }

            vpnInterface = established
            running.set(true)
            startPacketPump(established)
            startForegroundNotification(
                "TUN captures IPv4 and IPv6; packets are dropped until a packet engine is integrated."
            )
        } catch (e: Exception) {
            stopTunnel()
            startForegroundNotification("VPN bootstrap failed: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun applySplitTunnelPolicy(builder: Builder, policy: VpnTunnelPolicy) {
        policy.disallowedApplications.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Ignore stale package names; persistent UI validation will come later.
            }
        }
    }

    private fun startPacketPump(parcelFileDescriptor: ParcelFileDescriptor) {
        val thread = Thread({
            val buffer = ByteArray(PACKET_BUFFER_BYTES)
            try {
                FileInputStream(parcelFileDescriptor.fileDescriptor).use { input ->
                    while (running.get()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        // Phase 3 only owns the TUN. Engine integration will consume
                        // and forward these packets in the next phases.
                    }
                }
            } catch (_: IOException) {
                // Expected when the VPN interface is closed during stop/revoke.
            } finally {
                running.set(false)
            }
        }, "vpn-bootstrap-packet-pump")
        thread.isDaemon = true
        packetPumpThread = thread
        thread.start()
    }

    private fun stopTunnel() {
        running.set(false)
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        packetPumpThread?.interrupt()
        packetPumpThread = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundNotification(contentText: String) {
        createNotificationChannel()
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
        val stopIntent = Intent(this, AutoVpnService::class.java).apply { action = ACTION_STOP }
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("VPN Project")
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
            "VPN tunnel status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the active VPN bootstrap tunnel status."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.vpnproject.app.vpn.action.START"
        const val ACTION_STOP = "com.vpnproject.app.vpn.action.STOP"

        private const val CHANNEL_ID = "vpn_tunnel_status"
        private const val NOTIFICATION_ID = 41
        private const val STOP_REQUEST_CODE = 42
        private const val PACKET_BUFFER_BYTES = 32 * 1024
        private const val BOOTSTRAP_IPV6_TUN_ADDRESS = "fd00:1111:2222:3333::2"
    }
}
