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
import android.provider.Settings
import android.util.Base64
import com.vpnproject.app.engine.EngineKind
import com.vpnproject.app.engine.EngineState
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.XrayTrafficStatsParser
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class XrayVpnService : VpnService(), CoreCallbackHandler {
    private val running = AtomicBoolean(false)

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var coreController: CoreController? = null

    @Volatile
    private var verifierThread: Thread? = null

    @Volatile
    private var statsThread: Thread? = null

    @Volatile
    private var heartbeatThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return if (running.get()) {
                startForegroundNotification("Xray VPN is still running in the background…")
                Service.START_REDELIVER_INTENT
            } else {
                updateStatus(
                    EngineState.STOPPED,
                    "Xray service was restarted without its encrypted runtime config. Tap Connect again."
                )
                stopSelf()
                Service.START_NOT_STICKY
            }
        }

        return when (intent.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopXray("Stop requested for Xray engine.")
                stopSelf()
                Service.START_NOT_STICKY
            }
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty().ifBlank { "v2ray-import" }
                val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
                val dnsServers = intent.getStringArrayListExtra(EXTRA_DNS_SERVERS).orEmpty().ifEmpty { DEFAULT_DNS_SERVERS }
                val bypassPackages = intent.getStringArrayListExtra(EXTRA_BYPASS_PACKAGES).orEmpty()
                startForegroundNotification("Starting embedded Xray engine…")
                updateStatus(EngineState.CONNECTING, "Starting embedded Xray engine for $profileName.", note)
                Thread({ startXray(configJson, profileName, note, dnsServers, bypassPackages) }, "xray-start").start()
                Service.START_REDELIVER_INTENT
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running.get()) {
            startForegroundNotification("Xray VPN continues in the background…")
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun startup(): Long = 0L

    override fun shutdown(): Long = 0L

    override fun onEmitStatus(l: Long, s: String?): Long {
        if (!s.isNullOrBlank()) {
            val previous = lastStatus
            updateStatus(
                previous.state,
                previous.message,
                appendDetailLine(previous.detail, "xray: ${s.shortForStatus()}"),
                verified = previous.verified,
                rxBytes = previous.rxBytes,
                txBytes = previous.txBytes,
                latencyMs = previous.latencyMs
            )
        }
        return 0L
    }

    private fun startXray(
        configJson: String,
        profileName: String,
        note: String,
        dnsServers: List<String>,
        bypassPackages: List<String>
    ) {
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
            val tun = establishTunOrThrow(profileName, dnsServers, bypassPackages)
            updateStatus(
                EngineState.CONNECTING,
                "Android VPN interface is established; starting Xray core for $profileName.",
                note
            )
            startForegroundNotification("VPN interface established; starting Xray core…")
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, xudpBaseKey())
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            controller.startLoop(configJson, tun.fd)
            running.set(true)
            startForegroundHeartbeat()
            startStatsPolling(controller)
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

    private fun establishTunOrThrow(
        profileName: String,
        dnsServers: List<String>,
        bypassPackages: List<String>
    ): ParcelFileDescriptor {
        if (VpnService.prepare(this) != null) {
            throw IllegalStateException("VPN permission is missing. Tap Prepare VPN permission, allow it, then start again.")
        }

        val builder = Builder()
            .setSession("VPN Project Xray - $profileName")
            .setMtu(1500)
            .setBlocking(false)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)

        dnsServers.filter { it.isNotBlank() }.distinct().forEach { dns ->
            builder.addDnsServer(dns)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        (listOf(packageName) + bypassPackages)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { packageToBypass ->
                try {
                    builder.addDisallowedApplication(packageToBypass)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore missing/removed packages; Settings keeps plain package names only.
                }
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
                Thread.sleep(VERIFY_START_DELAY_MS)
                val attempts = mutableListOf<String>()
                var verifiedDelayMs: Long? = null
                var verifiedUrl: String? = null

                for (url in VERIFY_URLS) {
                    if (!running.get() || Thread.currentThread().isInterrupted) return@Thread
                    val result = measureDelayWithTimeout(controller, url, VERIFY_URL_TIMEOUT_MS)
                    if (result.delayMs != null && result.delayMs >= 0L) {
                        verifiedDelayMs = result.delayMs
                        verifiedUrl = url
                        attempts += "${url.hostLabel()}: OK ${result.delayMs}ms"
                        break
                    } else {
                        attempts += "${url.hostLabel()}: ${result.error ?: "returned no delay"}"
                        if (result.error?.startsWith("timed out") == true) break
                    }
                }

                if (!running.get() || Thread.currentThread().isInterrupted) return@Thread
                val stats = runCatching { controller.queryAllOutboundTrafficStats() }.getOrNull()
                val traffic = XrayTrafficStatsParser.parse(stats)
                if (verifiedDelayMs != null) {
                    updateStatus(
                        EngineState.VERIFIED,
                        "Xray verified: outbound HTTP check passed through the proxy in ${verifiedDelayMs}ms.",
                        verificationDetail(note, attempts, stats, verifiedUrl),
                        verified = true,
                        rxBytes = traffic.rxBytes,
                        txBytes = traffic.txBytes,
                        latencyMs = verifiedDelayMs
                    )
                    startForegroundNotification("Xray verified through proxy: ${verifiedDelayMs}ms")
                } else {
                    updateStatus(
                        EngineState.RUNNING,
                        "Xray core is running and the Android VPN is established, but proxy egress verification failed for the test URLs.",
                        verificationDetail(note, attempts, stats, null),
                        verified = false,
                        rxBytes = traffic.rxBytes,
                        txBytes = traffic.txBytes
                    )
                    startForegroundNotification("Xray running; egress is not verified yet.")
                }
            } catch (e: InterruptedException) {
                // Stopped.
            } catch (e: Exception) {
                updateStatus(
                    EngineState.RUNNING,
                    "Xray core is running, but verification failed: ${e.message ?: e.javaClass.simpleName}",
                    verificationDetail(note, listOf("${e.javaClass.simpleName}: ${e.message.orEmpty()}"), null, null),
                    verified = false
                )
                startForegroundNotification("Xray running; verification failed.")
            }
        }, "xray-verifier")
        verifierThread?.isDaemon = true
        verifierThread?.start()
    }

    private fun measureDelayWithTimeout(controller: CoreController, url: String, timeoutMs: Long): DelayProbeResult {
        val queue = ArrayBlockingQueue<DelayProbeResult>(1)
        Thread({
            val result = try {
                val delay = controller.measureDelay(url)
                if (delay >= 0L) DelayProbeResult(delayMs = delay) else DelayProbeResult(error = "returned $delay")
            } catch (error: Exception) {
                DelayProbeResult(error = (error.message ?: error.javaClass.simpleName).shortForStatus())
            }
            queue.offer(result)
        }, "xray-delay-${url.hostLabel()}").apply {
            isDaemon = true
            start()
        }
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: DelayProbeResult(error = "timed out after ${timeoutMs / 1000}s")
    }

    private fun startForegroundHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread({
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(FOREGROUND_HEARTBEAT_MS)
                    if (!running.get() || Thread.currentThread().isInterrupted) break
                    val current = lastStatus
                    val message = when (current.state) {
                        EngineState.VERIFIED -> "Xray VPN protected${current.latencyMs?.let { " • ${it}ms" }.orEmpty()}"
                        EngineState.VERIFYING,
                        EngineState.CONNECTING -> "Xray VPN is running; verifying route…"
                        EngineState.RUNNING -> "Xray VPN is running; egress is not verified yet."
                        else -> "Xray VPN service is active."
                    }
                    startForegroundNotification(message)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    runCatching { Thread.sleep(FOREGROUND_HEARTBEAT_MS) }
                }
            }
        }, "xray-foreground-heartbeat")
        heartbeatThread?.isDaemon = true
        heartbeatThread?.start()
    }

    private fun startStatsPolling(controller: CoreController) {
        statsThread?.interrupt()
        statsThread = Thread({
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val stats = controller.queryAllOutboundTrafficStats()
                    updateTrafficStats(stats)
                    Thread.sleep(STATS_REFRESH_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    // Traffic stats are best-effort; keep the connection status visible.
                    runCatching { Thread.sleep(STATS_REFRESH_MS) }
                }
            }
        }, "xray-stats")
        statsThread?.isDaemon = true
        statsThread?.start()
    }

    private fun updateTrafficStats(stats: String?) {
        val traffic = XrayTrafficStatsParser.parse(stats)
        if (traffic.rxBytes == null && traffic.txBytes == null) return
        val current = lastStatus
        if (current.kind != EngineKind.XRAY_CORE) return
        lastStatus = current.copy(
            rxBytes = traffic.rxBytes ?: current.rxBytes,
            txBytes = traffic.txBytes ?: current.txBytes,
            detail = if (stats.isNullOrBlank()) current.detail else replaceStatsLine(current.detail, stats.shortForStatus(180))
        )
    }

    private fun replaceStatsLine(current: String?, stats: String): String {
        val withoutOldStats = current
            ?.replace(Regex("(?s)\\s*Stats:.*$"), "")
            ?.trim()
            .orEmpty()
        return listOf(withoutOldStats, "Stats: $stats")
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .shortForDetail()
    }

    private fun verificationDetail(
        note: String,
        attempts: List<String>,
        stats: String?,
        verifiedUrl: String?
    ): String? = buildString {
        append(note)
        if (verifiedUrl != null) append("\nVerified URL: $verifiedUrl")
        if (attempts.isNotEmpty()) append("\nVerification attempts: ${attempts.joinToString("; ")}")
        if (!stats.isNullOrBlank()) append("\nStats: $stats")
    }.ifBlank { null }

    private fun String.hostLabel(): String = removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .shortForStatus(48)

    private fun appendDetailLine(current: String?, next: String): String {
        val combined = listOfNotNull(current?.takeIf { it.isNotBlank() }, next.takeIf { it.isNotBlank() })
            .joinToString("\n")
        return combined.shortForDetail()
    }

    private fun String.shortForStatus(maxLength: Int = 220): String {
        val singleLine = replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength - 1) + "…"
    }

    private fun String.shortForDetail(maxLength: Int = 900): String {
        val compactLines = lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return if (compactLines.length <= maxLength) compactLines else compactLines.take(maxLength - 1) + "…"
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
        statsThread?.interrupt()
        statsThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun xudpBaseKey(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: packageName
        val rawKey = androidId.toByteArray(Charsets.UTF_8).copyOf(32)
        return Base64.encodeToString(rawKey, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun updateStatus(
        state: EngineState,
        message: String,
        detail: String? = null,
        verified: Boolean = false,
        rxBytes: Long? = null,
        txBytes: Long? = null,
        latencyMs: Long? = null
    ) {
        lastStatus = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = state,
            message = message,
            detail = detail,
            rxBytes = rxBytes,
            txBytes = txBytes,
            latencyMs = latencyMs,
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
        const val EXTRA_DNS_SERVERS = "com.vpnproject.app.vpn.xray.extra.DNS_SERVERS"
        const val EXTRA_BYPASS_PACKAGES = "com.vpnproject.app.vpn.xray.extra.BYPASS_PACKAGES"
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

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
        private const val VERIFY_START_DELAY_MS = 3_000L
        private const val VERIFY_URL_TIMEOUT_MS = 15_000L
        private const val STATS_REFRESH_MS = 2_000L
        private const val FOREGROUND_HEARTBEAT_MS = 60_000L
        private val VERIFY_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.msftconnecttest.com/connecttest.txt",
            "https://www.netlify.com/"
        )
    }
}

private data class DelayProbeResult(
    val delayMs: Long? = null,
    val error: String? = null
)
