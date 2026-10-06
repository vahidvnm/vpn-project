package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.ImportedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEngineApiTest {
    @Test
    fun snapshotKeepsStatsAndVerificationScopeFromOneStatus() {
        val adapter = FakeEngineAdapter(
            EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.VERIFIED,
                message = "Xray proxy egress check passed.",
                detail = "Proxy egress checked; app-to-TUN not tested.",
                rxBytes = 1_024,
                txBytes = 2_048,
                egressIp = "203.0.113.7",
                latencyMs = 143,
                verified = true,
                verificationScope = VerificationScope.XRAY_PROXY_EGRESS,
                profileId = "profile-1"
            )
        )

        val snapshot = adapter.snapshot()

        assertEquals(EngineTrafficStats(rxBytes = 1_024, txBytes = 2_048), snapshot.stats)
        assertTrue(snapshot.verification.verified)
        assertEquals(VerificationScope.XRAY_PROXY_EGRESS, snapshot.verification.scope)
        assertEquals("profile-1", snapshot.verification.profileId)
        assertNull(snapshot.failure)
    }

    @Test
    fun proxyEgressVerificationDoesNotBecomeTunnelVerification() {
        val adapter = FakeEngineAdapter(
            EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.VERIFIED,
                message = "Verified.",
                verified = true,
                verificationScope = VerificationScope.XRAY_PROXY_EGRESS
            )
        )

        val evidence = adapter.verify()

        assertTrue(evidence.verified)
        assertEquals(VerificationScope.XRAY_PROXY_EGRESS, evidence.scope)
        assertFalse(evidence.scope == VerificationScope.WIREGUARD_TUNNEL_TRAFFIC_AND_EGRESS)
    }

    @Test
    fun failureExplanationExistsOnlyForFailedEngineStatus() {
        val failed = FakeEngineAdapter(
            EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.FAILED,
                message = "WireGuard config could not be started.",
                detail = "Endpoint is not reachable."
            )
        )
        val running = FakeEngineAdapter(
            EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.RUNNING,
                message = "Tunnel is running."
            )
        )

        val explanation = failed.explainFailure()

        assertEquals(VpnEngineId.WIREGUARD_GO, explanation?.engineId)
        assertTrue(explanation?.detail.orEmpty().contains("Endpoint is not reachable."))
        assertNull(running.explainFailure())
    }

    @Test
    fun preparationRequestDoesNotPrintRawConfigOrCredentials() {
        val secretConfig = "vless://user-secret-password@secret.example:443#private-profile"
        val request = EnginePreparationRequest(
            config = ImportedConfig(
                kind = ConfigKind.V2RAY,
                originalText = secretConfig,
                endpoints = emptyList()
            ),
            profileId = "profile-1"
        )

        assertFalse(request.toString().contains("user-secret-password"))
        assertFalse(request.toString().contains("secret.example"))
        assertTrue(request.toString().contains("config=<redacted>"))

        val prepared = PreparedXrayEngineStart(
            runtime = V2RayRuntimeConfig(
                configJson = "{\"password\":\"user-secret-password\"}",
                profileName = "private-profile",
                note = "prepared"
            ),
            profileId = "profile-1",
            dnsServers = listOf("1.1.1.1"),
            bypassPackages = emptyList()
        )
        assertFalse(prepared.toString().contains("user-secret-password"))
        assertTrue(prepared.toString().contains("runtime=<redacted>"))
    }

    private class FakeEngineAdapter(
        private var currentStatus: EngineStatus
    ) : VpnEngineAdapter {
        override val engineId: VpnEngineId = when (currentStatus.kind) {
            EngineKind.XRAY_CORE -> VpnEngineId.XRAY_CORE
            EngineKind.WIREGUARD_GO -> VpnEngineId.WIREGUARD_GO
        }

        override fun prepare(request: EnginePreparationRequest): PreparedEngineStart =
            throw UnsupportedOperationException("Not required by this snapshot test.")

        override fun start(prepared: PreparedEngineStart) = Unit

        override fun stop() = Unit

        override fun status(): EngineStatus = currentStatus
    }
}
