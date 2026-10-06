package com.vpnproject.app.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.vpnproject.app.engine.EngineState
import com.vpnproject.app.engine.EngineStatus

/**
 * Exercises real Android service dispatch without establishing a VPN tunnel.
 * Start requests deliberately contain empty configs, then the test sends the
 * normal stop action and waits for the service's terminal status snapshot.
 */
class VpnEngineServiceLifecycleTest : InstrumentationTestCase() {
    private lateinit var targetContext: Context
    private var launchedActivity: Activity? = null

    override fun setUp() {
        super.setUp()
        targetContext = instrumentation.targetContext
        val launchIntent = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)
            ?: throw AssertionError("Could not launch the target app for foreground service tests.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchedActivity = instrumentation.startActivitySync(launchIntent)
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { launchedActivity?.finish() }
        launchedActivity = null
        super.tearDown()
    }

    fun testXrayStopActionPublishesStoppedStatus() {
        val profileId = "instrumentation-xray-${SystemClock.uptimeMillis()}"
        val serviceClass = XrayVpnService::class.java
        val startIntent = Intent(targetContext, serviceClass).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG_JSON, "")
            putExtra(XrayVpnService.EXTRA_PROFILE_NAME, "instrumentation-empty-config")
            putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId)
        }
        targetContext.startForegroundService(startIntent)

        awaitStatus("Xray start intent", { XrayVpnService.lastStatus }) { it.profileId == profileId }
        val beforeStop = XrayVpnService.lastStatus
        targetContext.startService(Intent(targetContext, serviceClass).apply {
            action = XrayVpnService.ACTION_STOP
        })

        awaitStatus("Xray stop intent", { XrayVpnService.lastStatus }) {
            it !== beforeStop &&
                it.state == EngineState.STOPPED &&
                it.message in setOf("Stop requested for Xray engine.", "Xray service destroyed.")
        }
    }

    fun testWireGuardStopActionPublishesStoppedStatus() {
        val profileId = "instrumentation-wireguard-${SystemClock.uptimeMillis()}"
        val serviceClass = WireGuardVpnService::class.java
        val startIntent = Intent(targetContext, serviceClass).apply {
            action = WireGuardVpnService.ACTION_START
            putExtra(WireGuardVpnService.EXTRA_CONFIG_TEXT, "")
            putExtra(WireGuardVpnService.EXTRA_TUNNEL_NAME, "instrumentation-empty-config")
            putExtra(WireGuardVpnService.EXTRA_PROFILE_ID, profileId)
        }
        targetContext.startForegroundService(startIntent)

        awaitStatus("WireGuard start intent", { WireGuardVpnService.lastStatus }) { it.profileId == profileId }
        val beforeStop = WireGuardVpnService.lastStatus
        targetContext.startService(Intent(targetContext, serviceClass).apply {
            action = WireGuardVpnService.ACTION_STOP
        })

        awaitStatus("WireGuard stop intent", { WireGuardVpnService.lastStatus }) {
            it !== beforeStop &&
                it.state == EngineState.STOPPED &&
                it.message == "WireGuard engine stopped."
        }
    }

    private fun awaitStatus(
        action: String,
        statusProvider: () -> EngineStatus,
        predicate: (EngineStatus) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + STATUS_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val status = statusProvider()
            if (predicate(status)) return
            SystemClock.sleep(STATUS_POLL_INTERVAL_MS)
        }
        fail("Timed out waiting for $action; latest status: ${statusProvider()}")
    }

    private companion object {
        const val STATUS_TIMEOUT_MS = 8_000L
        const val STATUS_POLL_INTERVAL_MS = 25L
    }
}
