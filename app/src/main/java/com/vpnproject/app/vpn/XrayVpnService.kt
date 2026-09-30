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
import com.vpnproject.app.engine.EngineKind
import com.vpnproject.app.engine.EngineState
import com.vpnproject.app.engine.EngineStatus
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

class XrayVpnService : VpnService(), CoreCallbackHandler {
    private val running = AtomicBoolean(false)

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var coreController: CoreController? = null

    @Volatile
    private var verifierThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopXray("Stop requested for Xray engine.")
                stopSelf()
                Service.START_NOT_STICKY
            }
            ACTION_START -> {
                val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val profileName = intent?.getStringExtra(EXTRA_PROFILE_NAME).orEmpty().ifBlank { "v2ray-import" }
                val note = intent?.getStringExtra(EXTRA_NOTE).orEmpty()
                startForegroundNotification("Starting embedded Xray engine…")
                updateStatus(EngineState.CONNECTING, "Starting embedded Xray engine for $profileName.", note)
                Thread({ startXray(configJson, profileName, note) }, "xray-start").start()
                Service.START_STICKY
            }
            else -> Service.START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        // If startup failed, keep the FAILED status visible for the UI instead
        // of overwriting the useful error with a generic service-destroyed state.
        if (lastStatus.kind == EngineKind.XRAY_CORE && lastStatus.state == EngineState.FAILED) {
            stopCoreOnly()
            stopForegroundCompat()
        } else {
            stopXray("Xray service destroyed.")
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        stopXray("Xray VPN permission was revoked.")
        super.onRevoke()
    }

    override fun startup(): Long = 0L

    override fun shutdown(): Long = 0L

    override fun onEmitStatus(l: Long, s: String?): Long {
        if (!s.isNullOrBlank()) {
            updateStatus(lastStatus.state, lastStatus.message, "xray: $s")
        }
        return 0L
    }

    private fun startXray(configJson: String, profileName: String, note: String) {
        if (configJson.isBlank()) {
            updateStatus(EngineState.FAILED, "Xray runtime config is empty.", note)
            startForegroundNotification("Xray failed: runtime config is empty.")
            stopSelf()
            return
        }

        stopCoreOnly()
        try {
            updateStatus(
                EngineState.CONNECTING,
                "Establishing Android VPN interface for Xray profile $profileName.",
                note
            )
            startForegroundNotification("Establishing Android VPN interface for Xray…")
            val tun = establishTunOrThrow(profileName)
            updateStatus(
                EngineState.CONNECTING,
                "Android VPN interface is established; starting Xray core for $profileName.",
                note
            )
            startForegroundNotification("VPN interface established; starting Xray core…")
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, packageName)
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            controller.startLoop(configJson, tun.fd)
            running.set(true)
            updateStatus(
                EngineState.VERIFYING,
                "Xray core started for $profileName; verifying outbound delay through the proxy.",
                note
            )
            startForegroundNotification("Xray core is running; verifying proxy egress…")
            startVerification(controller, note)
        } catch (e: Exception) {
            stopCoreOnly()
            val errorText = e.message ?: e.javaClass.simpleName
            updateStatus(
                EngineState.FAILED,
                "Xray startup failed: $errorText",
                buildString {
                    append(note)
                    append("\nError class: ${e.javaClass.name}")
                    e.cause?.let { append("\nCause: ${it.message ?: it.javaClass.simpleName}") }
                },
                verified = false
            )
            startForegroundNotification("Xray failed: $errorText")
            stopSelf()
        }
    }

    private fun establishTunOrThrow(profileName: String): ParcelFileDescriptor {
        if (VpnService.prepare(this) != null) {
            throw IllegalStateException("VPN permission is missing. Tap Prepare VPN permission, allow it, then start again.")
        }

        val builder = Builder()
            .setSession("VPN Project Xray - $profileName")
            .setMtu(1500)
            .setBlocking(false)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // Ignore; the package exists, but Android can still throw on unusual profiles.
        }

        return try {
            vpnInterface?.close()
            val established = builder.establish()
                ?: throw IllegalStateException("Android Builder.establish() returned null.")
            vpnInterface = established
            established
        } catch (e: Exception) {
            throw IllegalStateException("Android VPN interface failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun startVerification(controller: CoreController, note: String) {
        verifierThread?.interrupt()
        verifierThread = Thread({
            try {
                Thread.sleep(3_000L)
                val delay = controller.measureDelay("https://www.gstatic.com/generate_204")
                val stats = runCatching { controller.queryAllOutboundTrafficStats() }.getOrNull()
                if (delay >= 0) {
                    updateStatus(
                        EngineState.VERIFIED,
                        "Xray verified: outbound HTTP check passed through the proxy in ${delay}ms.",
                        buildString {
                            append(note)
                            if (!stats.isNullOrBlank()) append("\nStats: $stats")
                        }.ifBlank { null },
                        verified = true
                    )
                    startForegroundNotification("Xray verified through proxy: ${delay}ms")
                } else {
                    updateStatus(
                        EngineState.RUNNING,
                        "Xray core is running, but proxy egress verification did not complete yet.",
                        buildString {
                            append(note)
                            if (!stats.isNullOrBlank()) append("\nStats: $stats")
                        }.ifBlank { null },
                        verified = false
                    )
                    startForegroundNotification("Xray running; egress is not verified yet.")
                }
            } catch (e: InterruptedException) {
                // Stopped.
            } catch (e: Exception) {
                updateStatus(
                    EngineState.RUNNING,
                    "Xray core is running, but verification failed: ${e.message ?: e.javaClass.simpleName}",
                    note,
                    verified = false
                )
                startForegroundNotification("Xray running; verification failed.")
            }
        }, "xray-verifier")
        verifierThread?.isDaemon = true
        verifierThread?.start()
    }

    private fun stopXray(message: String) {
        updateStatus(EngineState.STOPPING, message)
        stopCoreOnly()
        updateStatus(EngineState.STOPPED, message)
        stopForegroundCompat()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopCoreOnly() {
        running.set(false)
        verifierThread?.interrupt()
        verifierThread = null
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun updateStatus(
        state: EngineState,
        message: String,
        detail: String? = null,
        verified: Boolean = false
    ) {
        lastStatus = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = state,
            message = message,
            detail = detail,
            verified = verified
        )
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
        val stopIntent = Intent(this, XrayVpnService::class.java).apply { action = ACTION_STOP }
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
            .setContentTitle("VPN Project Xray")
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
            "Xray engine status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the embedded Xray/V2Ray engine status."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.vpnproject.app.vpn.xray.action.START"
        const val ACTION_STOP = "com.vpnproject.app.vpn.xray.action.STOP"
        const val EXTRA_CONFIG_JSON = "com.vpnproject.app.vpn.xray.extra.CONFIG_JSON"
        const val EXTRA_PROFILE_NAME = "com.vpnproject.app.vpn.xray.extra.PROFILE_NAME"
        const val EXTRA_NOTE = "com.vpnproject.app.vpn.xray.extra.NOTE"

        @Volatile
        var lastStatus: EngineStatus = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.IDLE,
            message = "Xray engine has not started."
        )
            private set

        private const val CHANNEL_ID = "xray_engine_status"
        private const val NOTIFICATION_ID = 61
        private const val STOP_REQUEST_CODE = 62
    }
}
