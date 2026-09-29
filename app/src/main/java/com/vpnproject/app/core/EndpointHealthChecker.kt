package com.vpnproject.app.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class EndpointHealthChecker(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val socketProtector: SocketProtector = NoOpSocketProtector
) {
    fun checkBestEffort(resolved: ResolvedEndpointCandidate): HealthResult {
        return when (resolved.protocol) {
            VpnProtocol.OPENVPN_TCP -> checkTcp(resolved)
            VpnProtocol.V2RAY_TLS -> checkTls(resolved)
            VpnProtocol.V2RAY_TCP,
            VpnProtocol.V2RAY_REALITY,
            VpnProtocol.V2RAY_UNKNOWN -> checkTcp(resolved)
            VpnProtocol.OPENVPN_UDP,
            VpnProtocol.WIREGUARD -> unsupported(
                resolved,
                "TCP probe is not meaningful for ${resolved.protocol}; engine-level UDP/WireGuard handshake comes later."
            )
            VpnProtocol.UNKNOWN -> checkTcp(resolved)
        }
    }

    fun checkTcp(resolved: ResolvedEndpointCandidate): HealthResult {
        val candidate = resolved.asPinnedEndpointCandidate()
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                if (!socketProtector.protect(socket)) {
                    throw IOException("Could not protect probe socket from VPN routing loop.")
                }
                socket.connect(InetSocketAddress(resolved.ip, resolved.port), timeoutMs)
            }
            val latency = elapsedMs(started)
            HealthResult(
                candidate = candidate,
                reachable = true,
                latencyMs = latency,
                reason = "TCP connect OK to ${resolved.ip}:${resolved.port}",
                probeKind = ProbeKind.TCP_CONNECT,
                score = HealthScorer.score(latency),
                checkedAtEpochMs = nowEpochMs()
            )
        } catch (e: Exception) {
            HealthResult(
                candidate = candidate,
                reachable = false,
                latencyMs = null,
                reason = "TCP connect failed: ${e.message ?: e.javaClass.simpleName}",
                probeKind = ProbeKind.TCP_CONNECT,
                score = null,
                checkedAtEpochMs = nowEpochMs()
            )
        }
    }

    /**
     * Generic TLS probe for HTTPS/TLS-like endpoints.
     * The TCP socket connects to the pinned IP, while SNI and hostname
     * verification use the original provider hostname. We never disable
     * certificate verification here.
     */
    fun checkTls(resolved: ResolvedEndpointCandidate, verifyHostname: String? = resolved.verifyHost): HealthResult {
        val candidate = resolved.asPinnedEndpointCandidate()
        val hostname = verifyHostname?.trim()?.takeIf { it.isNotBlank() }
        if (hostname == null || IpClassifier.isIpv4Literal(hostname)) {
            return HealthResult(
                candidate = candidate,
                reachable = false,
                reason = "TLS probe requires the original DNS hostname for SNI and certificate verification.",
                probeKind = ProbeKind.TLS_HANDSHAKE,
                checkedAtEpochMs = nowEpochMs()
            )
        }

        val started = System.nanoTime()
        var plainSocket: Socket? = null
        return try {
            plainSocket = Socket().apply {
                if (!socketProtector.protect(this)) {
                    throw IOException("Could not protect TLS probe socket from VPN routing loop.")
                }
                connect(InetSocketAddress(resolved.ip, resolved.port), timeoutMs)
            }
            val sslSocket = sslSocketFactory.createSocket(
                plainSocket,
                hostname,
                resolved.port,
                true
            ) as SSLSocket
            plainSocket = null
            sslSocket.use { socket ->
                socket.soTimeout = timeoutMs
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                socket.startHandshake()
            }
            val latency = elapsedMs(started)
            HealthResult(
                candidate = candidate,
                reachable = true,
                latencyMs = latency,
                reason = "TLS handshake OK with verified hostname $hostname",
                probeKind = ProbeKind.TLS_HANDSHAKE,
                score = HealthScorer.score(latency),
                checkedAtEpochMs = nowEpochMs()
            )
        } catch (e: Exception) {
            HealthResult(
                candidate = candidate,
                reachable = false,
                latencyMs = null,
                reason = "TLS probe failed: ${e.message ?: e.javaClass.simpleName}",
                probeKind = ProbeKind.TLS_HANDSHAKE,
                score = null,
                checkedAtEpochMs = nowEpochMs()
            )
        } finally {
            plainSocket?.close()
        }
    }

    private fun unsupported(resolved: ResolvedEndpointCandidate, reason: String): HealthResult = HealthResult(
        candidate = resolved.asPinnedEndpointCandidate(),
        reachable = false,
        reason = reason,
        probeKind = ProbeKind.UNSUPPORTED,
        checkedAtEpochMs = nowEpochMs()
    )

    private fun elapsedMs(startedNano: Long): Long =
        ((System.nanoTime() - startedNano) / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
    }
}
