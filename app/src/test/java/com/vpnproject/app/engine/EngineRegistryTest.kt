package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.profile.VpnProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRegistryTest {
    @Test
    fun runtimeRegistryContainsOnlyRealInAppCoresAndOpenVpnHandoff() {
        assertEquals(
            setOf(VpnEngineId.XRAY_CORE, VpnEngineId.WIREGUARD_GO, VpnEngineId.OPENVPN_EXTERNAL),
            EngineRegistry.engines.map { it.id }.toSet()
        )
        assertTrue(EngineRegistry.engines.first { it.id == VpnEngineId.XRAY_CORE }.startableInApp)
        assertTrue(EngineRegistry.engines.first { it.id == VpnEngineId.WIREGUARD_GO }.startableInApp)
        assertFalse(EngineRegistry.engines.first { it.id == VpnEngineId.OPENVPN_EXTERNAL }.startableInApp)
    }

    @Test
    fun runtimeCanBeLookedUpByStableEngineId() {
        assertEquals(VpnEngineId.XRAY_CORE, EngineRegistry.engine(VpnEngineId.XRAY_CORE).id)
        assertEquals(VpnEngineId.WIREGUARD_GO, EngineRegistry.engine(VpnEngineId.WIREGUARD_GO).id)
    }

    @Test
    fun mapsXrayProfilesToTheEmbeddedXrayRuntime() {
        val route = EngineRegistry.routeFor(VpnProfileKind.XRAY)
        val runtime = EngineRegistry.engineFor(VpnProfileKind.XRAY)

        assertEquals(ProfileAdapterId.V2RAY_XRAY_LINKS, route.adapterId)
        assertEquals(EngineExecutionMode.EMBEDDED_ENGINE, route.executionMode)
        assertEquals(VpnEngineId.XRAY_CORE, route.runtimeEngineId)
        assertEquals(EngineKind.XRAY_CORE, runtime.engineKind)
        assertTrue(route.supportsInAppConnection)
        assertEquals("Embedded Xray", route.pathLabel(runtime))
    }

    @Test
    fun mapsSingBoxProfilesToXrayWithoutRegisteringNativeSingBoxEngine() {
        val route = EngineRegistry.routeFor(ConfigKind.SING_BOX)
        val runtime = EngineRegistry.engineFor(ConfigKind.SING_BOX)

        assertEquals(ProfileAdapterId.SING_BOX_JSON_MAPPER, route.adapterId)
        assertEquals(EngineExecutionMode.MAPPED_TO_XRAY, route.executionMode)
        assertEquals(VpnEngineId.XRAY_CORE, route.runtimeEngineId)
        assertEquals(EngineKind.XRAY_CORE, runtime.engineKind)
        assertTrue(route.supportsInAppConnection)
        assertEquals(
            "sing-box JSON mapper → Embedded Xray (supported subset)",
            route.pathLabel(runtime)
        )
        assertFalse(EngineRegistry.engines.any { it.displayName.contains("sing-box", ignoreCase = true) })
    }

    @Test
    fun mapsClashProfilesToXrayWithoutRegisteringNativeClashEngine() {
        val route = EngineRegistry.routeFor(ConfigKind.CLASH)
        val runtime = EngineRegistry.engineFor(ConfigKind.CLASH)

        assertEquals(ProfileAdapterId.CLASH_YAML_MAPPER, route.adapterId)
        assertEquals(EngineExecutionMode.MAPPED_TO_XRAY, route.executionMode)
        assertEquals(VpnEngineId.XRAY_CORE, route.runtimeEngineId)
        assertEquals(EngineKind.XRAY_CORE, runtime.engineKind)
        assertTrue(route.supportsInAppConnection)
        assertEquals(
            "Clash/Clash.Meta YAML mapper → Embedded Xray (supported subset)",
            route.pathLabel(runtime)
        )
        assertFalse(EngineRegistry.engines.any { it.displayName.contains("Clash", ignoreCase = true) })
    }

    @Test
    fun mapsWireGuardToTheEmbeddedGoBackend() {
        val route = EngineRegistry.routeFor(ConfigKind.WIREGUARD)
        val runtime = EngineRegistry.engineFor(ConfigKind.WIREGUARD)

        assertEquals(ProfileAdapterId.WIREGUARD_CONFIG, route.adapterId)
        assertEquals(EngineExecutionMode.EMBEDDED_ENGINE, route.executionMode)
        assertEquals(VpnEngineId.WIREGUARD_GO, route.runtimeEngineId)
        assertEquals(EngineKind.WIREGUARD_GO, runtime.engineKind)
        assertEquals("WireGuard GoBackend", route.pathLabel(runtime))
    }

    @Test
    fun mapsOpenVpnToExternalHandoffAndUnknownToNoRuntime() {
        val openVpnRoute = EngineRegistry.routeFor(ConfigKind.OPENVPN)
        val openVpnRuntime = EngineRegistry.engineFor(ConfigKind.OPENVPN)
        val unknownRoute = EngineRegistry.routeFor(ConfigKind.UNKNOWN)
        val unknownRuntime = EngineRegistry.engineFor(ConfigKind.UNKNOWN)

        assertEquals(EngineExecutionMode.EXTERNAL_HANDOFF, openVpnRoute.executionMode)
        assertEquals(VpnEngineId.OPENVPN_EXTERNAL, openVpnRoute.runtimeEngineId)
        assertFalse(openVpnRoute.supportsInAppConnection)
        assertEquals(null, openVpnRuntime.engineKind)
        assertEquals(
            "OpenVPN profile parser → external OpenVPN-compatible client",
            openVpnRoute.pathLabel(openVpnRuntime)
        )
        assertEquals(EngineExecutionMode.UNAVAILABLE, unknownRoute.executionMode)
        assertEquals(VpnEngineId.NONE, unknownRuntime.id)
        assertFalse(unknownRoute.supportsInAppConnection)
    }
}
