package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnHubStatusMapperTest {
    @Test
    fun prefersVerifiedXrayOverStoppedWireGuard() {
        val hub = VpnHubStatusMapper.from(
            wireGuard = EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.STOPPED,
                message = "WireGuard stopped."
            ),
            xray = EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.VERIFIED,
                message = "Xray verified in 126ms.",
                latencyMs = 126L,
                verified = true
            )
        )

        assertEquals(VpnHubConnectionState.CONNECTED, hub.state)
        assertEquals(EngineKind.XRAY_CORE, hub.activeEngine)
        assertTrue(hub.verified)
        assertEquals(126L, hub.latencyMs)
        assertTrue(hub.detail.contains("Embedded Xray"))
    }

    @Test
    fun reportsConnectingWhenAnyEngineIsVerifying() {
        val hub = VpnHubStatusMapper.from(
            wireGuard = EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.STOPPED,
                message = "WireGuard stopped."
            ),
            xray = EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.VERIFYING,
                message = "Verifying proxy egress."
            )
        )

        assertEquals(VpnHubConnectionState.CONNECTING, hub.state)
        assertEquals("Connecting", hub.title)
    }

    @Test
    fun reportsFailureWithEngineName() {
        val hub = VpnHubStatusMapper.from(
            wireGuard = EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.STOPPED,
                message = "WireGuard stopped."
            ),
            xray = EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.FAILED,
                message = "credentials rejected"
            )
        )

        assertEquals(VpnHubConnectionState.FAILED, hub.state)
        assertTrue(hub.detail.contains("Embedded Xray failed"))
    }
}
