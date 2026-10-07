package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFailureClassifierTest {
    @Test
    fun identifiesIpv6RoutePolicyWithoutCallingItNetworkBlocking() {
        val status = failed("WireGuard was not started: AllowedIPs lacks ::/0, so IPv6 could bypass the tunnel.")

        val category = EngineFailureClassifier.classify(status)

        assertEquals(EngineFailureCategory.IPV6_ROUTE_POLICY, category)
        assertTrue(category?.suggestedAction.orEmpty().contains("not evidence of blocking"))
    }

    @Test
    fun separatesNetworkRebindAndStopFailures() {
        assertEquals(
            EngineFailureCategory.NETWORK_RECOVERY,
            EngineFailureClassifier.classify(failed("WireGuard rebind failed: network changed."))
        )
        assertEquals(
            EngineFailureCategory.STOP_OPERATION,
            EngineFailureClassifier.classify(failed("WireGuard stop failed: backend error."))
        )
    }

    @Test
    fun identifiesMissingConfigurationAndPermissionFailures() {
        assertEquals(
            EngineFailureCategory.CONFIGURATION,
            EngineFailureClassifier.classify(failed("WireGuard config is missing."))
        )
        assertEquals(
            EngineFailureCategory.CONFIGURATION,
            EngineFailureClassifier.classifyFailure("Embedded Xray runtime config failed: unsupported transport.")
        )
        assertEquals(
            EngineFailureCategory.ENGINE_START,
            EngineFailureClassifier.classifyFailure("WireGuard GoBackend start failed: backend unavailable.")
        )
        assertEquals(
            EngineFailureCategory.VPN_PERMISSION,
            EngineFailureClassifier.classify(failed("Android VPN permission was not granted."))
        )
    }

    @Test
    fun unknownFailureDoesNotClaimARootCause() {
        val category = EngineFailureClassifier.classify(failed("The remote peer did not respond."))

        assertEquals(EngineFailureCategory.UNKNOWN, category)
        assertTrue(category?.suggestedAction.orEmpty().contains("does not establish a root cause"))
    }

    @Test
    fun onlyClassifiesFailedStatuses() {
        assertNull(
            EngineFailureClassifier.classify(
                EngineStatus(
                    kind = EngineKind.XRAY_CORE,
                    state = EngineState.RUNNING,
                    message = "Xray is running."
                )
            )
        )
    }

    private fun failed(message: String) = EngineStatus(
        kind = EngineKind.WIREGUARD_GO,
        state = EngineState.FAILED,
        message = message
    )
}
