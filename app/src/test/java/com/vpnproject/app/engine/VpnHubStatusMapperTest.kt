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
                message = "Xray proxy egress verified.",
                latencyMs = 126L,
                verified = true,
                verificationScope = VerificationScope.XRAY_PROXY_EGRESS
            )
        )

        assertEquals(VpnHubConnectionState.CONNECTED, hub.state)
        assertEquals(EngineKind.XRAY_CORE, hub.activeEngine)
        assertTrue(hub.verified)
        assertEquals(126L, hub.latencyMs)
        assertTrue(hub.detail.contains("Embedded Xray"))
        assertTrue(hub.detail.contains("Android app-to-TUN traffic path was not independently tested"))
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
    fun reportsConnectingDuringNetworkReverification() {
        val hub = VpnHubStatusMapper.from(
            wireGuard = EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.STOPPED,
                message = "WireGuard stopped."
            ),
            xray = EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.RECONNECTING,
                message = "Network changed; re-checking Xray route."
            )
        )

        assertEquals(VpnHubConnectionState.CONNECTING, hub.state)
        assertEquals("Connecting", hub.title)
        assertEquals(EngineKind.XRAY_CORE, hub.activeEngine)
    }

    @Test
    fun ignoresStatusesBelongingToADifferentProfile() {
        val hub = VpnHubStatusMapper.from(
            wireGuard = EngineStatus(
                kind = EngineKind.WIREGUARD_GO,
                state = EngineState.VERIFIED,
                message = "Old profile connected.",
                verified = true,
                profileId = "old-profile"
            ),
            xray = EngineStatus(
                kind = EngineKind.XRAY_CORE,
                state = EngineState.STOPPED,
                message = "Stopped.",
                profileId = "old-profile"
            ),
            activeProfileId = "selected-profile"
        )

        assertEquals(VpnHubConnectionState.IDLE, hub.state)
        assertTrue(!hub.verified)
        assertEquals(null, hub.activeEngine)
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
        assertEquals(EngineFailureCategory.UNKNOWN, hub.failureCategory)
    }
}
