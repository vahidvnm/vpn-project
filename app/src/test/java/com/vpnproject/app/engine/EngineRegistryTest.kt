package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.profile.VpnProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRegistryTest {
    @Test
    fun mapsXrayProfilesToEmbeddedXrayEngine() {
        val engine = EngineRegistry.engineFor(VpnProfileKind.XRAY)

        assertEquals(VpnEngineId.XRAY_CORE, engine.id)
        assertEquals(EngineKind.XRAY_CORE, engine.engineKind)
        assertTrue(engine.embedded)
        assertTrue(engine.startableInApp)
    }

    @Test
    fun mapsSingBoxToExperimentalImportOnlyEngine() {
        val engine = EngineRegistry.engineFor(ConfigKind.SING_BOX)

        assertEquals(VpnEngineId.SING_BOX_EXPERIMENTAL, engine.id)
        assertEquals(EngineKind.SING_BOX_EXPERIMENTAL, engine.engineKind)
        assertFalse(engine.embedded)
        assertFalse(engine.startableInApp)
    }

    @Test
    fun mapsClashToExperimentalImportOnlyEngine() {
        val engine = EngineRegistry.engineFor(ConfigKind.CLASH)

        assertEquals(VpnEngineId.CLASH_IMPORT, engine.id)
        assertEquals(EngineKind.CLASH_IMPORT, engine.engineKind)
        assertFalse(engine.embedded)
        assertFalse(engine.startableInApp)
    }

    @Test
    fun mapsOpenVpnToExternalHandoffUntilEmbeddedLicenseDecision() {
        val engine = EngineRegistry.engineFor(ConfigKind.OPENVPN)

        assertEquals(VpnEngineId.OPENVPN_EXTERNAL, engine.id)
        assertEquals(EngineKind.OPENVPN_UNAVAILABLE, engine.engineKind)
        assertFalse(engine.embedded)
        assertFalse(engine.startableInApp)
    }
}
