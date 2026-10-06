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
import com.vpnproject.app.core.WireGuardConfigParser
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
import com.vpnproject.app.engine.VerificationScope
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
    private val lifecycleGeneration = AtomicInteger(0)
    private val lifecycleLock = Any()
    private val rebindRunning = AtomicBoolean(false)

    @Volatile
    private var startThread: Thread? = null
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
    private var activeProfileId: String? = null

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
                val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
                startWireGuardTunnel(configText.orEmpty(), tunnelName, note, profileId, startId)
                Service.START_STICKY
            }
            ACTION_STOP -> {
                stopWireGuardTunnel()
                stopSelfResult(startId)
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

    private fun startWireGuardTunnel(configText: String, tunnelName: String, note: String?, profileId: String?, startId: Int) {
        if (!starting.compareAndSet(false, true)) {
            updateStatus(EngineState.CONNECTING, "WireGuard start is already in progress.", note)
            return
        }

        val generation = lifecycleGeneration.incrementAndGet()
        synchronized(lifecycleLock) {
            activeProfileId = profileId
            updateStatus(EngineState.CONNECTING, "Starting WireGuard engine…", note)
        }
        startForegroundNotification(note?.let { "Starting WireGuard. $it" } ?: "Starting WireGuard engine…")

        val worker = Thread({
            var attemptBackend: GoBackend? = null
            var attemptTunnel: WireGuardTunnelHandle? = null
            var attemptIsUp = false
            try {
                if (!isCurrentLifecycle(generation)) return@Thread
                stopVerification()
                stopRebindAndJoin()
                if (!isCurrentLifecycle(generation)) return@Thread
                stopExistingTunnelForRestart()
                if (!isCurrentLifecycle(generation)) return@Thread

                if (configText.isBlank()) {
                    val message = "WireGuard config is missing."
                    if (updateStatusIfCurrent(generation, EngineState.FAILED, message, note)) {
                        startForegroundNotificationIfCurrent(generation, message)
                        stopSelfResult(startId)
                    }
                    return@Thread
                }

                if (!WireGuardConfigParser.hasIpv6DefaultRoute(configText)) {
                    val message = "WireGuard was not started: AllowedIPs lacks ::/0, so IPv6 could bypass the tunnel. Ask the provider for an IPv6-routed config."
                    if (updateStatusIfCurrent(generation, EngineState.FAILED, message, note)) {
                        startForegroundNotificationIfCurrent(generation, message)
                        stopSelfResult(startId)
                    }
                    return@Thread
                }

                val parsedConfig = Config.parse(configText.byteInputStream(Charsets.UTF_8))
                val tunnelHandle = WireGuardTunnelHandle(tunnelName) { state ->
                    if (!isCurrentLifecycle(generation)) return@WireGuardTunnelHandle
                    when (state) {
                        Tunnel.State.UP -> {
                            val updated = updateStatusIfCurrent(
                                generation,
                                EngineState.RUNNING,
                                "WireGuard tunnel state is UP; verification is pending.",
                                note
                            )
                            if (updated) {
                                startForegroundNotificationIfCurrent(generation, "WireGuard tunnel is UP. Verifying traffic and egress…")
                            }
                        }
                        Tunnel.State.DOWN -> {
                            val updated = updateStatusIfCurrent(generation, EngineState.STOPPED, "WireGuard tunnel state is DOWN.")
                            if (updated) {
                                startForegroundNotificationIfCurrent(generation, "WireGuard tunnel is stopped.")
                            }
                        }
                        Tunnel.State.TOGGLE -> Unit
                    }
                }
                val goBackend = backend ?: GoBackend(this)
                attemptBackend = goBackend
                attemptTunnel = tunnelHandle

                setCurrentUnderlyingNetwork()
                if (!isCurrentLifecycle(generation)) return@Thread
                val state = goBackend.setState(tunnelHandle, Tunnel.State.UP, parsedConfig)
                attemptIsUp = state == Tunnel.State.UP
                if (!isCurrentLifecycle(generation)) return@Thread

                if (state != Tunnel.State.UP) {
                    val updated = updateStatusIfCurrent(
                        generation,
                        EngineState.FAILED,
                        "WireGuard did not enter UP state: $state",
                        note
                    )
                    if (updated) {
                        startForegroundNotificationIfCurrent(generation, "WireGuard did not enter UP state: $state")
                        stopSelfResult(startId)
                    }
                    return@Thread
                }

                val committed = synchronized(lifecycleLock) {
                    if (generation != lifecycleGeneration.get()) {
                        false
                    } else {
                        backend = goBackend
                        tunnel = tunnelHandle
                        currentConfig = parsedConfig
                        true
                    }
                }
                if (!committed) return@Thread

                synchronized(lifecycleLock) {
                    if (generation != lifecycleGeneration.get()) return@Thread
                    registerNetworkCallback()
                    updateStatus(
                        EngineState.RUNNING,
                        "WireGuard engine started; tunnel verification is running.",
                        note
                    )
                    startForegroundNotificationIfCurrent(generation, "WireGuard engine started. Verifying tunnel traffic and egress IP…")
                    startVerification(goBackend, tunnelHandle, note, generation)
                }
            } catch (e: Exception) {
                if (attemptIsUp) {
                    val goBackend = attemptBackend
                    val tunnelHandle = attemptTunnel
                    if (goBackend != null && tunnelHandle != null) {
                        runCatching { goBackend.setState(tunnelHandle, Tunnel.State.DOWN, null) }
                    }
                }
                val message = "WireGuard failed: ${e.message ?: e.javaClass.simpleName}"
                val updated = updateStatusIfCurrent(generation, EngineState.FAILED, message, note)
                if (updated) {
                    startForegroundNotificationIfCurrent(generation, message)
                    stopSelfResult(startId)
                }
            } finally {
                if (attemptIsUp && !isCurrentLifecycle(generation)) {
                    val goBackend = attemptBackend
                    val tunnelHandle = attemptTunnel
                    if (goBackend != null && tunnelHandle != null) {
                        runCatching { goBackend.setState(tunnelHandle, Tunnel.State.DOWN, null) }
                    }
                }
                starting.set(false)
                if (startThread === Thread.currentThread()) startThread = null
            }
        }, "wireguard-engine-start-$generation")
        startThread = worker
        worker.start()
    }

    private fun startVerification(
        goBackend: GoBackend,
        tunnelHandle: WireGuardTunnelHandle,
        note: String?,
        lifecycle: Int
    ) {
        val thread = synchronized(lifecycleLock) {
            if (lifecycle != lifecycleGeneration.get()) return
            stopVerification()
            val verificationId = verificationGeneration.incrementAndGet()
            verificationRunning.set(true)
            Thread({
                try {
                    val started = updateStatusIfCurrent(
                        lifecycle,
                        EngineState.VERIFYING,
                        "Checking WireGuard tunnel traffic and public egress IP…",
                        note,
                        verificationId = verificationId
                    )
                    if (!started || !isCurrentVerification(lifecycle, verificationId)) return@Thread
                    startForegroundNotificationIfCurrent(
                        lifecycle,
                        "Verifying WireGuard tunnel: traffic + egress IP…",
                        verificationId
                    )

                    val verifier = WireGuardConnectionVerifier(
                        statsSource = WireGuardBackendStatsSource(goBackend, tunnelHandle),
                        egressIpResolver = HttpsEgressIpResolver(),
                        policy = WireGuardVerificationPolicy(
                            maxAttempts = VERIFY_ATTEMPTS,
                            intervalMs = VERIFY_INTERVAL_MS,
                            minTrafficDeltaBytes = VERIFY_MIN_TRAFFIC_DELTA_BYTES
                        ),
                        sleeper = { millis ->
                            if (verificationRunning.get() && isCurrentVerification(lifecycle, verificationId)) {
                                Thread.sleep(millis)
                            }
                        }
                    )
                    val result = runCatching { verifier.verify() }.getOrElse { error ->
                        WireGuardVerificationResult(
                            verified = false,
                            statsMoved = false,
                            egressVerified = false,
                            reason = if (verificationRunning.get() && isCurrentVerification(lifecycle, verificationId)) {
                                "Verification failed: ${error.message ?: error.javaClass.simpleName}"
                            } else {
                                "Verification cancelled."
                            },
                            rxBytes = 0L,
                            txBytes = 0L,
                            attempts = 0
                        )
                    }

                    if (!verificationRunning.get() || !isCurrentVerification(lifecycle, verificationId)) return@Thread
                    val updated = updateStatusFromVerification(result, note, lifecycle, verificationId)
                    if (updated) {
                        startForegroundNotificationIfCurrent(
                            lifecycle,
                            notificationTextFor(result),
                            verificationId
                        )
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        if (verificationGeneration.get() == verificationId && verificationThread === Thread.currentThread()) {
                            verificationRunning.set(false)
                            verificationThread = null
                        }
                    }
                }
            }, "wireguard-engine-verify-$lifecycle-$verificationId").also { verificationThread = it }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun updateStatusFromVerification(
        result: WireGuardVerificationResult,
        note: String?,
        lifecycle: Int,
        verificationId: Int
    ): Boolean {
        return if (result.verified) {
            updateStatusIfCurrent(
                lifecycle,
                state = EngineState.VERIFIED,
                message = "WireGuard verified: tunnel traffic moved and public egress IP was ${result.egressIp}.",
                detail = note ?: result.reason,
                rxBytes = result.rxBytes,
                txBytes = result.txBytes,
                egressIp = result.egressIp,
                verified = true,
                verificationId = verificationId
            )
        } else {
            updateStatusIfCurrent(
                lifecycle,
                state = EngineState.RUNNING,
                message = "WireGuard is running but not fully verified: ${result.reason}",
                detail = note,
                rxBytes = result.rxBytes,
                txBytes = result.txBytes,
                egressIp = result.egressIp,
                verified = false,
                verificationId = verificationId
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
        val generation = lifecycleGeneration.get()
        if (!isCurrentLifecycle(generation) || starting.get()) return
        if (lastStatus.state in setOf(EngineState.STOPPING, EngineState.STOPPED, EngineState.FAILED)) return
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
            var reboundBackend: GoBackend? = null
            var reboundTunnel: WireGuardTunnelHandle? = null
            var rebindAttempted = false
            try {
                if (decision.delayMs > 0L) Thread.sleep(decision.delayMs)
                if (!rebindRunning.get() || !isCurrentLifecycle(generation)) return@Thread

                val activeBackend = backend ?: return@Thread
                val activeTunnel = tunnel ?: return@Thread
                val activeConfig = currentConfig ?: return@Thread
                val reconnecting = updateStatusIfCurrent(
                    generation,
                    EngineState.RECONNECTING,
                    "Network changed; rebinding WireGuard…",
                    decision.reason
                )
                if (!reconnecting || !isCurrentLifecycle(generation)) return@Thread
                startForegroundNotificationIfCurrent(generation, "Network changed. Rebinding WireGuard…")

                setCurrentUnderlyingNetwork()
                reboundBackend = activeBackend
                reboundTunnel = activeTunnel
                rebindAttempted = true
                val state = activeBackend.setState(activeTunnel, Tunnel.State.UP, activeConfig)
                if (!isCurrentLifecycle(generation)) return@Thread
                if (state == Tunnel.State.UP) {
                    val reverifyReason = "Rebound after network change: ${decision.reason}"
                    val updated = updateStatusIfCurrent(
                        generation,
                        EngineState.RUNNING,
                        "WireGuard rebound; verification restarted.",
                        reverifyReason
                    )
                    if (updated) {
                        startForegroundNotificationIfCurrent(generation, "WireGuard rebound after network change. Re-verifying…")
                        startVerification(activeBackend, activeTunnel, reverifyReason, generation)
                    }
                } else {
                    val message = "WireGuard rebind did not return UP state: $state"
                    val updated = updateStatusIfCurrent(generation, EngineState.FAILED, message, decision.reason)
                    if (updated) startForegroundNotificationIfCurrent(generation, "WireGuard rebind failed: $state")
                }
            } catch (_: InterruptedException) {
                // Expected when stopping or when a newer lifecycle action cancels rebind.
            } catch (e: Exception) {
                val message = "WireGuard rebind failed: ${e.message ?: e.javaClass.simpleName}"
                val updated = updateStatusIfCurrent(generation, EngineState.FAILED, message, decision.reason)
                if (updated) startForegroundNotificationIfCurrent(generation, message)
            } finally {
                if (rebindAttempted && !isCurrentLifecycle(generation)) {
                    val oldBackend = reboundBackend
                    val oldTunnel = reboundTunnel
                    if (oldBackend != null && oldTunnel != null) {
                        runCatching { oldBackend.setState(oldTunnel, Tunnel.State.DOWN, null) }
                    }
                }
                rebindRunning.set(false)
                if (rebindThread === Thread.currentThread()) rebindThread = null
            }
        }, "wireguard-network-rebind-$generation")
        thread.isDaemon = true
        rebindThread = thread
        thread.start()
    }

    private fun stopExistingTunnelForRestart() {
        unregisterNetworkCallback()
        val existing = synchronized(lifecycleLock) { backend to tunnel }
        val goBackend = existing.first
        val tunnelHandle = existing.second
        if ((goBackend == null) != (tunnelHandle == null)) {
            throw IllegalStateException("WireGuard backend and tunnel handles are inconsistent; refusing to start another tunnel.")
        }
        if (goBackend != null && tunnelHandle != null) {
            val state = try {
                goBackend.setState(tunnelHandle, Tunnel.State.DOWN, null)
            } catch (error: Exception) {
                throw IllegalStateException("Could not stop the previous WireGuard tunnel before restart.", error)
            }
            if (state != Tunnel.State.DOWN) {
                throw IllegalStateException("Previous WireGuard tunnel did not stop cleanly: $state")
            }
        }
        synchronized(lifecycleLock) {
            if (backend === goBackend && tunnel === tunnelHandle) {
                backend = null
                tunnel = null
                currentConfig = null
            }
        }
    }

    private fun stopWireGuardTunnel() {
        val pendingStart = synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            val thread = startThread
            thread?.interrupt()
            startThread = null
            updateStatus(EngineState.STOPPING, "Stopping WireGuard engine…")
            thread
        }
        joinUninterruptibly(pendingStart)
        stopVerification()
        stopRebindAndJoin()
        unregisterNetworkCallback()
        val existing = synchronized(lifecycleLock) { Triple(backend, tunnel, currentConfig) }
        var stopError: Exception? = null
        val goBackend = existing.first
        val tunnelHandle = existing.second
        if (goBackend != null && tunnelHandle != null) {
            runCatching { goBackend.setState(tunnelHandle, Tunnel.State.DOWN, null) }
                .onFailure { stopError = it as? Exception }
        }
        synchronized(lifecycleLock) {
            backend = null
            tunnel = null
            currentConfig = null
            if (stopError == null) {
                updateStatus(EngineState.STOPPED, "WireGuard engine stopped.")
            } else {
                updateStatus(EngineState.FAILED, "WireGuard stop failed: ${stopError?.message ?: stopError?.javaClass?.simpleName}")
            }
        }
    }

    private fun stopVerification() {
        verificationRunning.set(false)
        verificationGeneration.incrementAndGet()
        verificationThread?.interrupt()
        verificationThread = null
    }

    private fun stopRebindAndJoin() {
        rebindRunning.set(false)
        val thread = rebindThread
        rebindThread = null
        thread?.interrupt()
        joinUninterruptibly(thread)
    }

    private fun joinUninterruptibly(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
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
        verified: Boolean = false,
        verificationScope: VerificationScope = if (verified) {
            VerificationScope.WIREGUARD_TUNNEL_TRAFFIC_AND_EGRESS
        } else {
            VerificationScope.NONE
        }
    ) {
        lastStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = state,
            message = message,
            detail = detail,
            rxBytes = rxBytes,
            txBytes = txBytes,
            egressIp = egressIp,
            verified = verified,
            verificationScope = verificationScope,
            profileId = activeProfileId
        )
    }

    private fun isCurrentLifecycle(generation: Int): Boolean =
        lifecycleGeneration.get() == generation

    private fun isCurrentVerification(lifecycle: Int, verificationId: Int): Boolean =
        isCurrentLifecycle(lifecycle) && verificationGeneration.get() == verificationId

    private fun updateStatusIfCurrent(
        generation: Int,
        state: EngineState,
        message: String,
        detail: String? = null,
        rxBytes: Long? = null,
        txBytes: Long? = null,
        egressIp: String? = null,
        verified: Boolean = false,
        verificationScope: VerificationScope = if (verified) {
            VerificationScope.WIREGUARD_TUNNEL_TRAFFIC_AND_EGRESS
        } else {
            VerificationScope.NONE
        },
        verificationId: Int? = null
    ): Boolean = synchronized(lifecycleLock) {
        if (generation != lifecycleGeneration.get() ||
            (verificationId != null && verificationGeneration.get() != verificationId)
        ) {
            false
        } else {
            updateStatus(
                state = state,
                message = message,
                detail = detail,
                rxBytes = rxBytes,
                txBytes = txBytes,
                egressIp = egressIp,
                verified = verified,
                verificationScope = verificationScope
            )
            true
        }
    }

    private fun startForegroundNotificationIfCurrent(
        generation: Int,
        contentText: String,
        verificationId: Int? = null
    ): Boolean = synchronized(lifecycleLock) {
        if (generation != lifecycleGeneration.get() ||
            (verificationId != null && verificationGeneration.get() != verificationId)
        ) {
            false
        } else {
            startForegroundNotification(contentText)
            true
        }
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
        const val EXTRA_PROFILE_ID = "com.vpnproject.app.vpn.extra.PROFILE_ID"

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
