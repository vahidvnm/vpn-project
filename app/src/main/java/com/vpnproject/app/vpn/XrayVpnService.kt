package com.vpnproject.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import com.vpnproject.app.core.IpClassifier
import com.vpnproject.app.core.VpnRoutingInputParser
import com.vpnproject.app.engine.EngineKind
import com.vpnproject.app.engine.XrayRoutePolicy
import com.vpnproject.app.engine.EngineState
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.NetworkRebindPolicy
import com.vpnproject.app.engine.VerificationScope
import com.vpnproject.app.engine.XrayTrafficStatsParser
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class XrayVpnService : VpnService(), CoreCallbackHandler {
    private val running = AtomicBoolean(false)
    private val reverifyRunning = AtomicBoolean(false)
    private val verificationGeneration = AtomicInteger(0)
    private val lifecycleGeneration = AtomicInteger(0)
    private val lifecycleLock = Any()
    private val startOperationLock = Any()

    @Volatile
    private var runningGeneration: Int? = null

    @Volatile
    private var startThread: Thread? = null
    private val rebindPolicy = NetworkRebindPolicy(
        minIntervalMs = NETWORK_REVERIFY_MIN_INTERVAL_MS,
        debounceMs = NETWORK_REVERIFY_DEBOUNCE_MS
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleNetworkReverification("network available")
        }

        override fun onLost(network: Network) {
            scheduleNetworkReverification("network lost")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleNetworkReverification("network changed: ${networkCapabilities.summary()}")
        }
    }

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

    @Volatile
    private var reverifyThread: Thread? = null

    private val pendingReverifyReason = AtomicReference<String?>(null)

    @Volatile
    private var networkCallbackRegistered: Boolean = false

    @Volatile
    private var lastRebindRequestedAtElapsedMs: Long = 0L

    @Volatile
    private var activeNote: String = ""

    @Volatile
    private var activeLocalHttpProxyPort: Int = 0

    @Volatile
    private var activeProfileId: String? = null

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
                stopSelfResult(startId)
                Service.START_NOT_STICKY
            }
        }

        return when (intent.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopXray("Stop requested for Xray engine.")
                stopSelfResult(startId)
                Service.START_NOT_STICKY
            }
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty().ifBlank { "v2ray-import" }
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
                val dnsServers = intent.getStringArrayListExtra(EXTRA_DNS_SERVERS).orEmpty().ifEmpty { DEFAULT_DNS_SERVERS }
                val bypassPackages = intent.getStringArrayListExtra(EXTRA_BYPASS_PACKAGES).orEmpty()
                val localHttpProxyPort = intent.getIntExtra(EXTRA_LOCAL_HTTP_PROXY_PORT, 0)
                val generation = synchronized(lifecycleLock) {
                    lifecycleGeneration.incrementAndGet().also {
                        startThread?.interrupt()
                        activeProfileId = profileId
                        startThread = null
                        updateStatus(EngineState.CONNECTING, "Starting embedded Xray engine for $profileName.", note)
                    }
                }
                startForegroundNotification("Starting embedded Xray engine…")
                val worker = Thread({
                    startXray(configJson, profileName, profileId, note, dnsServers, bypassPackages, localHttpProxyPort, generation, startId)
                }, "xray-start-$generation")
                synchronized(lifecycleLock) {
                    if (generation == lifecycleGeneration.get()) startThread = worker
                }
                worker.start()
                Service.START_REDELIVER_INTENT
            }
            else -> Service.START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        // If startup failed, keep the FAILED status visible for the UI instead
        // of overwriting the useful error with a generic service-destroyed state.
        if (lastStatus.kind == EngineKind.XRAY_CORE && lastStatus.state == EngineState.FAILED) {
            invalidatePendingStart()
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

    override fun onEmitStatus(l: Long, s: String?): Long =
        emitCoreStatus(lifecycleGeneration.get(), s)

    private fun callbackHandlerFor(generation: Int): CoreCallbackHandler = object : CoreCallbackHandler {
        override fun startup(): Long = this@XrayVpnService.startup()

        override fun shutdown(): Long = this@XrayVpnService.shutdown()

        override fun onEmitStatus(l: Long, s: String?): Long = emitCoreStatus(generation, s)
    }

    private fun emitCoreStatus(generation: Int, message: String?): Long {
        if (message.isNullOrBlank()) return 0L
        synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration.get()) return 0L
            val previous = lastStatus
            updateStatus(
                state = previous.state,
                message = previous.message,
                detail = appendDetailLine(previous.detail, "xray: ${message.shortForStatus()}"),
                verified = previous.verified,
                rxBytes = previous.rxBytes,
                txBytes = previous.txBytes,
                latencyMs = previous.latencyMs,
                egressIp = previous.egressIp
            )
        }
        return 0L
    }

    private fun startXray(
        configJson: String,
        profileName: String,
        profileId: String?,
        note: String,
        dnsServers: List<String>,
        bypassPackages: List<String>,
        localHttpProxyPort: Int,
        generation: Int,
        startId: Int
    ) {
        synchronized(startOperationLock) {
            startXrayLocked(configJson, profileName, profileId, note, dnsServers, bypassPackages, localHttpProxyPort, generation, startId)
        }
    }

    private fun startXrayLocked(
        configJson: String,
        profileName: String,
        profileId: String?,
        note: String,
        dnsServers: List<String>,
        bypassPackages: List<String>,
        localHttpProxyPort: Int,
        generation: Int,
        startId: Int
    ) {
        var localTun: ParcelFileDescriptor? = null
        var localController: CoreController? = null
        if (!isCurrentLifecycle(generation)) return
        if (configJson.isBlank()) {
            if (isCurrentLifecycle(generation)) {
                stopCoreOnly()
                val failed = updateStatusIfCurrent(
                    generation,
                    EngineState.FAILED,
                    "Xray runtime config is empty.",
                    note
                )
                if (failed) {
                    startForegroundNotificationIfCurrent(generation, "Xray failed: runtime config is empty.")
                    stopSelfResult(startId)
                }
            }
            return
        }

        try {
            if (!isCurrentLifecycle(generation)) return
            stopCoreOnly()
            val began = synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration.get()) {
                    false
                } else {
                    activeProfileId = profileId
                    activeNote = note
                    activeLocalHttpProxyPort = localHttpProxyPort
                    updateStatus(
                        EngineState.CONNECTING,
                        "Establishing Android VPN interface for Xray profile $profileName.",
                        note
                    )
                    true
                }
            }
            if (!began) return
            startForegroundNotificationIfCurrent(generation, "Establishing Android VPN interface for Xray…")

            val establishedTun = establishTunOrThrow(profileName, dnsServers, bypassPackages)
            localTun = establishedTun
            if (!updateStatusIfCurrent(
                    generation,
                    EngineState.CONNECTING,
                    "Android VPN interface is established; starting Xray core for $profileName.",
                    note
                )
            ) return
            startForegroundNotificationIfCurrent(generation, "VPN interface established; starting Xray core…")
            setCurrentUnderlyingNetwork()
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, xudpBaseKey())

            val newController = Libv2ray.newCoreController(callbackHandlerFor(generation))
            localController = newController
            newController.startLoop(configJson, establishedTun.fd)

            val committed = synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration.get()) {
                    false
                } else {
                    vpnInterface = establishedTun
                    coreController = newController
                    runningGeneration = generation
                    running.set(true)
                    true
                }
            }
            if (!committed) return

            synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration.get()) return
                registerNetworkCallback()
                startForegroundHeartbeat(generation)
                startStatsPolling(newController, generation)
                updateStatus(
                    EngineState.VERIFYING,
                    "Xray core started for $profileName; verifying proxy egress.",
                    note
                )
                startForegroundNotificationIfCurrent(generation, "Xray core is running; verifying proxy egress…")
                startVerification(newController, note, localHttpProxyPort, generation)
            }
        } catch (e: Exception) {
            val errorText = e.message ?: e.javaClass.simpleName
            val failed = if (isCurrentLifecycle(generation)) {
                stopCoreOnly()
                synchronized(lifecycleLock) {
                    if (generation != lifecycleGeneration.get()) {
                        false
                    } else {
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
                        startForegroundNotificationIfCurrent(generation, "Xray failed: $errorText")
                        true
                    }
                }
            } else {
                false
            }
            if (failed) stopSelfResult(startId)
        } finally {
            if (localController != null && coreController !== localController) {
                runCatching { localController?.stopLoop() }
            }
            if (localTun != null && vpnInterface !== localTun) {
                runCatching { localTun?.close() }
            }
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
            .addAddress(XrayRoutePolicy.TUN_IPV4_ADDRESS, XrayRoutePolicy.TUN_IPV4_PREFIX)
            .addAddress(XrayRoutePolicy.TUN_IPV6_ADDRESS, XrayRoutePolicy.TUN_IPV6_PREFIX)

        // A full IPv6 route intentionally fails closed if Xray cannot handle the traffic.
        XrayRoutePolicy.FULL_TUNNEL_ROUTES.forEach { route ->
            builder.addRoute(route.address, route.prefixLength)
        }

        val safeDnsServers = VpnRoutingInputParser.parseDnsServers(dnsServers.joinToString("\n")).servers
            .ifEmpty { DEFAULT_DNS_SERVERS }
        safeDnsServers.forEach { dns -> builder.addDnsServer(dns) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val safeBypassPackages = VpnRoutingInputParser.parseBypassPackages(
            bypassPackages.joinToString("\n"),
            ownPackageName = packageName
        ).packages
        (listOf(packageName) + safeBypassPackages)
            .distinct()
            .forEach { packageToBypass ->
                try {
                    builder.addDisallowedApplication(packageToBypass)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore missing/removed packages; Settings keeps plain package names only.
                }
            }

        return try {
            val established = builder.establish()
                ?: throw IllegalStateException("Android Builder.establish() returned null.")
            established
        } catch (e: Exception) {
            throw IllegalStateException("Android VPN interface failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun startVerification(
        controller: CoreController,
        note: String,
        localHttpProxyPort: Int,
        generation: Int
    ) {
        val thread = synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration.get() || runningGeneration != generation) return
            val verificationRunId = verificationGeneration.incrementAndGet()
            verifierThread?.interrupt()
            Thread({
                try {
                    Thread.sleep(VERIFY_START_DELAY_MS)
                    if (!isCurrentVerification(generation, verificationRunId)) return@Thread
                    val attempts = mutableListOf<String>()
                    var verifiedDelayMs: Long? = null
                    var verifiedUrl: String? = null

                    for (url in VERIFY_URLS) {
                        if (!running.get() || !isCurrentVerification(generation, verificationRunId) || Thread.currentThread().isInterrupted) return@Thread
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

                    if (!running.get() || !isCurrentVerification(generation, verificationRunId) || Thread.currentThread().isInterrupted) return@Thread
                    val postConnectCheck = runPostConnectChecks(localHttpProxyPort)
                    if (!running.get() || !isCurrentVerification(generation, verificationRunId) || Thread.currentThread().isInterrupted) return@Thread
                    val stats = runCatching { controller.queryAllOutboundTrafficStats() }.getOrNull()
                    val traffic = XrayTrafficStatsParser.parse(stats)
                    val egressIp = postConnectCheck.egressIp
                    if (egressIp != null) {
                        val proofText = buildList {
                            verifiedDelayMs?.let { add("HTTP delay ${it}ms") }
                            add("public IP $egressIp")
                            if (postConnectCheck.dnsRouteOk) add("DoH reachable via Xray proxy")
                        }.joinToString("; ")
                        val updated = updateStatusIfCurrent(
                            generation,
                            EngineState.VERIFIED,
                            "Xray proxy egress verified: $proofText. Android app-to-TUN traffic was not independently tested.",
                            verificationDetail(note, attempts, stats, verifiedUrl, postConnectCheck),
                            verified = true,
                            rxBytes = traffic.rxBytes,
                            txBytes = traffic.txBytes,
                            egressIp = egressIp,
                            latencyMs = verifiedDelayMs,
                            verificationRunId = verificationRunId
                        )
                        if (updated) {
                            startForegroundNotificationIfCurrent(
                                generation,
                                "Xray proxy egress verified: IP $egressIp${verifiedDelayMs?.let { delay -> " • ${delay}ms" }.orEmpty()}. TUN app traffic was not independently tested.",
                                verificationRunId
                            )
                        }
                    } else {
                        val message = if (verifiedDelayMs != null) {
                            "Xray proxy delay responded, but public egress IP was not verified; Android app-to-TUN traffic remains unverified."
                        } else {
                            "Xray core is running, but proxy egress checks failed; Android app-to-TUN traffic remains unverified."
                        }
                        val updated = updateStatusIfCurrent(
                            generation,
                            EngineState.RUNNING,
                            message,
                            verificationDetail(note, attempts, stats, null, postConnectCheck),
                            verified = false,
                            rxBytes = traffic.rxBytes,
                            txBytes = traffic.txBytes,
                            latencyMs = verifiedDelayMs,
                            egressIp = egressIp,
                            verificationRunId = verificationRunId
                        )
                        if (updated) {
                            startForegroundNotificationIfCurrent(generation, "Xray running; egress is not verified yet.", verificationRunId)
                        }
                    }
                } catch (_: InterruptedException) {
                    // Expected when stopping or replacing this Xray session.
                } catch (e: Exception) {
                    val updated = updateStatusIfCurrent(
                        generation,
                        EngineState.RUNNING,
                        "Xray core is running, but verification failed: ${e.message ?: e.javaClass.simpleName}",
                        verificationDetail(note, listOf("${e.javaClass.simpleName}: ${e.message.orEmpty()}"), null, null, null),
                        verified = false,
                        verificationRunId = verificationRunId
                    )
                    if (updated) {
                        startForegroundNotificationIfCurrent(generation, "Xray running; verification failed.", verificationRunId)
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        if (verificationGeneration.get() == verificationRunId && verifierThread === Thread.currentThread()) {
                            verifierThread = null
                        }
                    }
                    if (isCurrentVerification(generation, verificationRunId)) {
                        drainPendingNetworkReverification(generation)
                    }
                }
            }, "xray-verifier-$generation-$verificationRunId").also { verifierThread = it }
        }
        thread.isDaemon = true
        thread.start()
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

    private fun runPostConnectChecks(localHttpProxyPort: Int): XrayPostConnectCheck {
        val port = localHttpProxyPort.takeIf { it in MIN_LOCAL_HTTP_PROXY_PORT..MAX_LOCAL_HTTP_PROXY_PORT }
            ?: return XrayPostConnectCheck(
                notes = listOf("Public IP/DNS route: local Xray check proxy was not available.")
            )
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val notes = mutableListOf<String>()
        var egressIp: String? = null

        for (endpoint in PUBLIC_IP_CHECK_URLS) {
            val result = runCatching { fetchViaProxy(endpoint, proxy, "text/plain, application/json") }
            val body = result.getOrNull()
            val ip = body?.let { extractPublicIpv4(it) }
            if (ip != null) {
                egressIp = ip
                notes += "Public egress IP: $ip via ${endpoint.hostLabel()}"
                break
            } else if (notes.size < 2) {
                notes += "Public IP ${endpoint.hostLabel()}: ${result.exceptionOrNull()?.message?.shortForStatus(90) ?: "no public IPv4 in response"}"
            }
        }

        val dnsRouteOk = runCatching {
            fetchViaProxy(DNS_ROUTE_CHECK_URL, proxy, "application/dns-json")
        }.getOrNull()?.let { body ->
            body.contains("\"Status\":0") || body.contains("\"Answer\"") || body.contains("\"AD\":")
        } == true
        notes += if (dnsRouteOk) {
            "DNS route: DoH can be reached through Xray local proxy."
        } else {
            "DNS route: inconclusive; full resolver leak service is still best-effort."
        }

        return XrayPostConnectCheck(
            egressIp = egressIp,
            dnsRouteOk = dnsRouteOk,
            notes = notes
        )
    }

    private fun fetchViaProxy(url: String, proxy: Proxy, accept: String): String {
        var currentUrl = URL(url)
        if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("Xray verification only permits HTTPS URLs.")
        }

        for (redirectCount in 0..MAX_POST_CHECK_REDIRECTS) {
            val connection = (currentUrl.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = POST_CONNECT_CHECK_TIMEOUT_MS
                readTimeout = POST_CONNECT_CHECK_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "VPNProject-Android/0.1")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount == MAX_POST_CHECK_REDIRECTS) error("Too many HTTPS redirects.")
                    val location = connection.getHeaderField("Location")
                        ?: error("HTTPS verification endpoint redirected without a Location.")
                    val nextUrl = URL(currentUrl, location)
                    if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                        error("Xray verification blocked a redirect to cleartext HTTP.")
                    }
                    currentUrl = nextUrl
                    continue
                }
                if (code !in 200..299) error("HTTP $code")
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(512)
                    while (output.length < MAX_POST_CHECK_RESPONSE_CHARS) {
                        val remaining = MAX_POST_CHECK_RESPONSE_CHARS - output.length
                        val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) break
                        if (read == 0) continue
                        output.append(buffer, 0, read)
                    }
                    output.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Could not complete Xray verification after HTTPS redirects.")
    }

    private fun extractPublicIpv4(body: String): String? {
        val cloudflareTrace = body.lineSequence()
            .firstOrNull { it.startsWith("ip=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
        if (cloudflareTrace != null && IpClassifier.isPublicIpv4(cloudflareTrace)) {
            return cloudflareTrace
        }
        return IPV4_REGEX.findAll(body)
            .map { it.value }
            .firstOrNull { IpClassifier.isPublicIpv4(it) }
    }

    private fun hasNetworkCheckInProgress(): Boolean =
        reverifyRunning.get() || lastStatus.state in setOf(EngineState.VERIFYING, EngineState.RECONNECTING)

    private fun drainPendingNetworkReverification(generation: Int) {
        if (!isCurrentLifecycle(generation)) return
        pendingReverifyReason.getAndSet(null)?.let { reason ->
            scheduleNetworkReverification(reason, coalescedNetworkChange = true)
        }
    }

    private fun scheduleNetworkReverification(reason: String, coalescedNetworkChange: Boolean = false) {
        val generation = lifecycleGeneration.get()
        if (!running.get() || runningGeneration != generation || !isCurrentLifecycle(generation)) return
        val controller = coreController ?: return
        val now = SystemClock.elapsedRealtime()
        val decision = rebindPolicy.evaluate(
            isRunning = running.get(),
            nowMonotonicMs = now,
            lastRequestedMonotonicMs = if (coalescedNetworkChange) 0L else lastRebindRequestedAtElapsedMs,
            reason = reason
        )
        if (!decision.shouldSchedule) {
            if (hasNetworkCheckInProgress()) {
                pendingReverifyReason.set(reason)
                if (!hasNetworkCheckInProgress()) drainPendingNetworkReverification(generation)
            }
            return
        }
        if (!reverifyRunning.compareAndSet(false, true)) {
            pendingReverifyReason.set(reason)
            if (!hasNetworkCheckInProgress()) drainPendingNetworkReverification(generation)
            return
        }
        lastRebindRequestedAtElapsedMs = now

        reverifyThread?.interrupt()
        reverifyThread = Thread({
            try {
                if (decision.delayMs > 0L) Thread.sleep(decision.delayMs)
                if (!running.get() || !isCurrentLifecycle(generation) || Thread.currentThread().isInterrupted) return@Thread
                val activeController = coreController ?: controller
                val current = lastStatus
                setCurrentUnderlyingNetwork()
                val updated = updateStatusIfCurrent(
                    generation,
                    EngineState.RECONNECTING,
                    "Network changed; re-checking Xray route.",
                    appendDetailLine(activeNote, "Network change: ${decision.reason}"),
                    verified = false,
                    rxBytes = current.rxBytes,
                    txBytes = current.txBytes,
                    egressIp = current.egressIp,
                    latencyMs = current.latencyMs
                )
                if (!updated || !isCurrentLifecycle(generation)) return@Thread
                startForegroundNotificationIfCurrent(generation, "Network changed. Re-checking Xray route…")
                startVerification(
                    activeController,
                    appendDetailLine(activeNote, "Network re-check: ${decision.reason}").orEmpty(),
                    activeLocalHttpProxyPort,
                    generation
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                val current = lastStatus
                updateStatusIfCurrent(
                    generation,
                    EngineState.RUNNING,
                    "Xray is running, but network re-check could not start: ${e.message ?: e.javaClass.simpleName}",
                    current.detail,
                    verified = false,
                    rxBytes = current.rxBytes,
                    txBytes = current.txBytes,
                    egressIp = current.egressIp,
                    latencyMs = current.latencyMs
                )
            } finally {
                if (generation == lifecycleGeneration.get()) {
                    if (reverifyThread === Thread.currentThread()) reverifyThread = null
                    reverifyRunning.set(false)
                    drainPendingNetworkReverification(generation)
                }
            }
        }, "xray-network-reverify-$generation")
        reverifyThread?.isDaemon = true
        reverifyThread?.start()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivityManager = connectivityManager() ?: return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            lastRebindRequestedAtElapsedMs = SystemClock.elapsedRealtime()
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
        val underlying = connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        runCatching {
            setUnderlyingNetworks(underlying?.let { arrayOf(it) } ?: emptyArray())
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

    private fun startForegroundHeartbeat(generation: Int) {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread({
            while (running.get() && isCurrentLifecycle(generation) && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(FOREGROUND_HEARTBEAT_MS)
                    if (!running.get() || !isCurrentLifecycle(generation) || Thread.currentThread().isInterrupted) break
                    val current = lastStatus
                    val message = when (current.state) {
                        EngineState.VERIFIED -> buildString {
                            append("Xray proxy egress verified; app-to-TUN path not independently tested")
                            current.egressIp?.let { append(" • IP ").append(it) }
                            current.latencyMs?.let { append(" • ").append(it).append("ms") }
                        }
                        EngineState.RECONNECTING -> "Network changed; re-checking Xray route…"
                        EngineState.VERIFYING,
                        EngineState.CONNECTING -> "Xray VPN is running; verifying route…"
                        EngineState.RUNNING -> "Xray VPN is running; egress is not verified yet."
                        else -> "Xray VPN service is active."
                    }
                    startForegroundNotificationIfCurrent(generation, message)
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

    private fun startStatsPolling(controller: CoreController, generation: Int) {
        statsThread?.interrupt()
        statsThread = Thread({
            while (running.get() && isCurrentLifecycle(generation) && !Thread.currentThread().isInterrupted) {
                try {
                    val stats = controller.queryAllOutboundTrafficStats()
                    updateTrafficStats(stats, generation)
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

    private fun updateTrafficStats(stats: String?, generation: Int) {
        val traffic = XrayTrafficStatsParser.parse(stats)
        if (traffic.rxBytes == null && traffic.txBytes == null) return
        synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration.get() || !running.get()) return
            val current = lastStatus
            if (current.kind != EngineKind.XRAY_CORE) return
            lastStatus = current.copy(
                rxBytes = traffic.rxBytes ?: current.rxBytes,
                txBytes = traffic.txBytes ?: current.txBytes,
                detail = if (stats.isNullOrBlank()) current.detail else replaceStatsLine(current.detail, stats.shortForStatus(180))
            )
        }
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
        verifiedUrl: String?,
        postConnectCheck: XrayPostConnectCheck?
    ): String? = buildString {
        append(note)
        if (verifiedUrl != null) append("\nVerified URL: $verifiedUrl")
        postConnectCheck?.egressIp?.let { append("\nPublic egress IP: $it") }
        if (postConnectCheck?.dnsRouteOk == true) append("\nDNS route check: OK via Xray proxy")
        val postCheckNotes = postConnectCheck?.notes.orEmpty()
        if (postCheckNotes.isNotEmpty()) {
            append("\nPost-connect checks: ${postCheckNotes.joinToString("; ")}")
        }
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
        val pendingStart = synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            val thread = startThread
            thread?.interrupt()
            startThread = null
            updateStatus(EngineState.STOPPING, message)
            thread
        }
        joinUninterruptibly(pendingStart)
        stopCoreOnly()
        synchronized(lifecycleLock) {
            updateStatus(EngineState.STOPPED, message)
        }
        stopForegroundCompat()
    }

    private fun invalidatePendingStart() {
        val pendingStart = synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            val thread = startThread
            thread?.interrupt()
            startThread = null
            thread
        }
        joinUninterruptibly(pendingStart)
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

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopCoreOnly() {
        val resources = synchronized(lifecycleLock) {
            running.set(false)
            runningGeneration = null
            verificationGeneration.incrementAndGet()
            reverifyRunning.set(false)
            pendingReverifyReason.set(null)
            verifierThread?.interrupt()
            verifierThread = null
            reverifyThread?.interrupt()
            reverifyThread = null
            statsThread?.interrupt()
            statsThread = null
            heartbeatThread?.interrupt()
            heartbeatThread = null
            activeNote = ""
            activeLocalHttpProxyPort = 0
            lastRebindRequestedAtElapsedMs = 0L
            val controller = coreController
            val tun = vpnInterface
            coreController = null
            vpnInterface = null
            controller to tun
        }
        unregisterNetworkCallback()
        runCatching { resources.first?.stopLoop() }
        runCatching { resources.second?.close() }
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
        latencyMs: Long? = null,
        egressIp: String? = null,
        verificationScope: VerificationScope = if (verified) {
            VerificationScope.XRAY_PROXY_EGRESS
        } else {
            VerificationScope.NONE
        }
    ) {
        lastStatus = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = state,
            message = message,
            detail = detail,
            rxBytes = rxBytes,
            txBytes = txBytes,
            latencyMs = latencyMs,
            egressIp = egressIp,
            verified = verified,
            verificationScope = verificationScope,
            profileId = activeProfileId
        )
    }

    private fun isCurrentLifecycle(generation: Int): Boolean =
        lifecycleGeneration.get() == generation

    private fun isCurrentVerification(generation: Int, verificationRunId: Int): Boolean =
        isCurrentLifecycle(generation) && verificationGeneration.get() == verificationRunId

    private fun updateStatusIfCurrent(
        generation: Int,
        state: EngineState,
        message: String,
        detail: String? = null,
        verified: Boolean = false,
        rxBytes: Long? = null,
        txBytes: Long? = null,
        latencyMs: Long? = null,
        egressIp: String? = null,
        verificationRunId: Int? = null
    ): Boolean = synchronized(lifecycleLock) {
        if (generation != lifecycleGeneration.get() ||
            (verificationRunId != null && verificationGeneration.get() != verificationRunId)
        ) {
            false
        } else {
            updateStatus(
                state = state,
                message = message,
                detail = detail,
                verified = verified,
                rxBytes = rxBytes,
                txBytes = txBytes,
                latencyMs = latencyMs,
                egressIp = egressIp
            )
            true
        }
    }

    private fun startForegroundNotificationIfCurrent(
        generation: Int,
        contentText: String,
        verificationRunId: Int? = null
    ): Boolean = synchronized(lifecycleLock) {
        if (generation != lifecycleGeneration.get() ||
            (verificationRunId != null && verificationGeneration.get() != verificationRunId)
        ) {
            false
        } else {
            startForegroundNotification(contentText)
            true
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
        const val EXTRA_PROFILE_ID = "com.vpnproject.app.vpn.xray.extra.PROFILE_ID"
        const val EXTRA_DNS_SERVERS = "com.vpnproject.app.vpn.xray.extra.DNS_SERVERS"
        const val EXTRA_BYPASS_PACKAGES = "com.vpnproject.app.vpn.xray.extra.BYPASS_PACKAGES"
        const val EXTRA_LOCAL_HTTP_PROXY_PORT = "com.vpnproject.app.vpn.xray.extra.LOCAL_HTTP_PROXY_PORT"
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
        private const val POST_CONNECT_CHECK_TIMEOUT_MS = 5_000
        private const val MAX_POST_CHECK_REDIRECTS = 3
        private const val MAX_POST_CHECK_RESPONSE_CHARS = 4_096
        private const val STATS_REFRESH_MS = 2_000L
        private const val FOREGROUND_HEARTBEAT_MS = 60_000L
        private const val NETWORK_REVERIFY_MIN_INTERVAL_MS = 15_000L
        private const val NETWORK_REVERIFY_DEBOUNCE_MS = 1_000L
        private const val MIN_LOCAL_HTTP_PROXY_PORT = 1024
        private const val MAX_LOCAL_HTTP_PROXY_PORT = 65535
        private val IPV4_REGEX = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        private const val DNS_ROUTE_CHECK_URL = "https://cloudflare-dns.com/dns-query?name=example.com&type=A"
        private val PUBLIC_IP_CHECK_URLS = listOf(
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com"
        )
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

private data class XrayPostConnectCheck(
    val egressIp: String? = null,
    val dnsRouteOk: Boolean = false,
    val notes: List<String> = emptyList()
)
