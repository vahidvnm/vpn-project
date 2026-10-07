package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEngineCoordinatorTest {
    @Test
    fun newerRequestMakesOlderPrepareCallbackStale() {
        val coordinator = VpnEngineCoordinator()
        val old = coordinator.beginPending("profile-old")
        assertTrue(coordinator.bindEngine(old, VpnEngineId.XRAY_CORE, "profile-old"))

        val current = coordinator.beginPending("profile-new")
        assertFalse(coordinator.isCurrent(old))
        assertFalse(coordinator.markStartRequested(old))
        assertTrue(coordinator.isCurrent(current))
        assertEquals("profile-new", coordinator.snapshot().pendingProfileId)
    }

    @Test
    fun permissionRequestCanBindToEngineAndRecordStartRequest() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending(
            profileId = "profile-wg",
            phase = EngineOperationPhase.AWAITING_PERMISSION
        )

        assertTrue(coordinator.setPhase(token, EngineOperationPhase.PREPARING))
        assertTrue(coordinator.bindEngine(token, VpnEngineId.WIREGUARD_GO, "profile-wg"))
        assertTrue(coordinator.setPhase(token, EngineOperationPhase.STARTING))
        assertTrue(coordinator.markStartRequested(token))

        val snapshot = coordinator.snapshot()
        assertEquals(EngineOperationPhase.START_REQUESTED, snapshot.phase)
        assertEquals(VpnEngineId.WIREGUARD_GO, snapshot.requestedEngineId)
        assertEquals("profile-wg", snapshot.requestedProfileId)
        assertNull(snapshot.pendingEngineId)
        assertNull(snapshot.pendingProfileId)
        assertFalse(coordinator.isCurrent(token))
    }

    @Test
    fun preparationFailurePreservesPreviousRequestButStartFailureCanClearIt() {
        val coordinator = VpnEngineCoordinator()
        val first = coordinator.beginPending("profile-1")
        assertTrue(coordinator.bindEngine(first, VpnEngineId.XRAY_CORE, "profile-1"))
        assertTrue(coordinator.markStartRequested(first))

        val prepareRetry = coordinator.beginPending("profile-2")
        assertTrue(coordinator.bindEngine(prepareRetry, VpnEngineId.WIREGUARD_GO, "profile-2"))
        assertTrue(coordinator.fail(prepareRetry, clearRequestedEngine = false))
        assertEquals(VpnEngineId.XRAY_CORE, coordinator.snapshot().requestedEngineId)
        assertEquals(EngineOperationPhase.START_REQUESTED, coordinator.snapshot().phase)

        val failedHandoff = coordinator.beginPending("profile-3")
        assertTrue(coordinator.bindEngine(failedHandoff, VpnEngineId.WIREGUARD_GO, "profile-3"))
        assertTrue(coordinator.fail(failedHandoff, clearRequestedEngine = true))
        assertNull(coordinator.snapshot().requestedEngineId)
        assertEquals(EngineOperationPhase.FAILED, coordinator.snapshot().phase)
    }

    @Test
    fun cancelAndStopInvalidatePendingPermissionOrPrepareCallbacks() {
        val coordinator = VpnEngineCoordinator()
        val permission = coordinator.beginPending(
            profileId = "profile-1",
            phase = EngineOperationPhase.AWAITING_PERMISSION
        )
        assertTrue(coordinator.cancel(permission))
        assertFalse(coordinator.isCurrent(permission))
        assertEquals(EngineOperationPhase.IDLE, coordinator.snapshot().phase)

        val preparing = coordinator.beginPending("profile-2")
        assertTrue(coordinator.bindEngine(preparing, VpnEngineId.XRAY_CORE, "profile-2"))
        coordinator.stopRequested()
        assertFalse(coordinator.isCurrent(preparing))
        assertEquals(EngineOperationPhase.IDLE, coordinator.snapshot().phase)
        assertNull(coordinator.snapshot().pendingEngineId)
    }

    @Test
    fun serviceDeathIsReconciledOnlyAfterMatchingServiceWasObservedRunning() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending("profile-xray")
        assertTrue(coordinator.bindEngine(token, VpnEngineId.XRAY_CORE, "profile-xray"))
        assertTrue(coordinator.markStartRequested(token))

        val staleStopped = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.STOPPED,
            message = "Old service stop.",
            profileId = "profile-xray"
        )
        assertFalse(coordinator.observeServiceStatus(staleStopped))
        assertEquals(VpnEngineId.XRAY_CORE, coordinator.snapshot().requestedEngineId)

        val runningOtherProfile = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.RUNNING,
            message = "Another profile is running.",
            profileId = "profile-other"
        )
        assertFalse(coordinator.observeServiceStatus(runningOtherProfile))

        val connecting = runningOtherProfile.copy(
            state = EngineState.CONNECTING,
            message = "Starting selected profile.",
            profileId = "profile-xray"
        )
        assertFalse(coordinator.observeServiceStatus(connecting))
        assertEquals(EngineOperationPhase.START_REQUESTED, coordinator.snapshot().phase)

        val destroyed = staleStopped.copy(
            state = EngineState.FAILED,
            message = "Xray service destroyed.",
            profileId = "profile-xray"
        )
        assertTrue(coordinator.observeServiceStatus(destroyed))
        assertNull(coordinator.snapshot().requestedEngineId)
        assertEquals(EngineOperationPhase.FAILED, coordinator.snapshot().phase)
    }

    @Test
    fun engineSwitchWaitsForConflictingEngineStopBeforeAllowingStart() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending("profile-wg")
        assertTrue(coordinator.bindEngine(token, VpnEngineId.WIREGUARD_GO, "profile-wg"))
        assertTrue(coordinator.setPhase(token, EngineOperationPhase.STARTING))

        val selectedStatus = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.CONNECTING,
            message = "Selected engine is not started yet.",
            profileId = "profile-wg"
        )
        val conflictingStatus = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.RUNNING,
            message = "Previous Xray engine is running.",
            profileId = "profile-xray"
        )
        val stopTargets = coordinator.beginStartHandoff(token, listOf(selectedStatus, conflictingStatus))

        assertEquals(setOf(VpnEngineId.XRAY_CORE), stopTargets)
        assertEquals(EngineStartHandoffState.WAITING, coordinator.startHandoffState(token))
        assertFalse(coordinator.markStartRequested(token))
        assertNull(coordinator.snapshot().requestedEngineId)

        val wrongProfileStopped = conflictingStatus.copy(
            state = EngineState.STOPPED,
            message = "Unrelated Xray profile stopped.",
            profileId = "profile-other"
        )
        assertFalse(coordinator.observeServiceStatus(wrongProfileStopped))
        assertEquals(EngineStartHandoffState.WAITING, coordinator.startHandoffState(token))

        val previousEngineStopped = conflictingStatus.copy(state = EngineState.STOPPED)
        assertTrue(coordinator.observeServiceStatus(previousEngineStopped))
        assertEquals(EngineOperationPhase.STARTING, coordinator.snapshot().phase)
        assertEquals(EngineStartHandoffState.READY, coordinator.startHandoffState(token))
        assertTrue(coordinator.markStartRequested(token, selectedStatus))
        assertEquals(VpnEngineId.WIREGUARD_GO, coordinator.snapshot().requestedEngineId)
    }

    @Test
    fun engineStartIsReadyImmediatelyWhenOtherEnginesAreAlreadyTerminal() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending("profile-wg")
        assertTrue(coordinator.bindEngine(token, VpnEngineId.WIREGUARD_GO, "profile-wg"))
        assertTrue(coordinator.setPhase(token, EngineOperationPhase.STARTING))
        val wireGuardConnecting = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.CONNECTING,
            message = "WireGuard is the selected engine.",
            profileId = "profile-wg"
        )
        val xrayStopped = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.STOPPED,
            message = "Xray is already stopped.",
            profileId = "profile-old"
        )

        assertTrue(coordinator.beginStartHandoff(token, listOf(wireGuardConnecting, xrayStopped))!!.isEmpty())
        assertEquals(EngineStartHandoffState.READY, coordinator.startHandoffState(token))
        assertTrue(coordinator.markStartRequested(token, wireGuardConnecting))
        assertEquals(VpnEngineId.WIREGUARD_GO, coordinator.snapshot().requestedEngineId)
    }

    @Test
    fun failedServiceStatusRequiresConfirmedStopBeforeStartingAnotherEngine() {
        val coordinator = VpnEngineCoordinator()
        val previous = coordinator.beginPending("profile-xray")
        assertTrue(coordinator.bindEngine(previous, VpnEngineId.XRAY_CORE, "profile-xray"))
        assertTrue(coordinator.markStartRequested(previous))

        val next = coordinator.beginPending("profile-wg")
        assertTrue(coordinator.bindEngine(next, VpnEngineId.WIREGUARD_GO, "profile-wg"))
        assertTrue(coordinator.setPhase(next, EngineOperationPhase.STARTING))
        val wireGuardConnecting = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.CONNECTING,
            message = "WireGuard selected.",
            profileId = "profile-wg"
        )
        val failedXray = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.FAILED,
            message = "Rebind failed; service resources may still exist.",
            profileId = "profile-xray"
        )

        assertEquals(setOf(VpnEngineId.XRAY_CORE), coordinator.beginStartHandoff(next, listOf(wireGuardConnecting, failedXray)))
        assertEquals(EngineStartHandoffState.WAITING, coordinator.startHandoffState(next))
        assertFalse(coordinator.observeServiceStatus(failedXray))
        assertEquals(EngineStartHandoffState.WAITING, coordinator.startHandoffState(next))

        val stopped = failedXray.copy(state = EngineState.STOPPED, message = "Xray explicitly stopped.")
        assertTrue(coordinator.observeServiceStatus(stopped))
        assertEquals(EngineStartHandoffState.READY, coordinator.startHandoffState(next))
        assertTrue(coordinator.markStartRequested(next, wireGuardConnecting))
        assertEquals(VpnEngineId.WIREGUARD_GO, coordinator.snapshot().requestedEngineId)
    }

    @Test
    fun failedEngineHandoffNeverStartsTheNewEngine() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending("profile-wg")
        assertTrue(coordinator.bindEngine(token, VpnEngineId.WIREGUARD_GO, "profile-wg"))
        assertTrue(coordinator.setPhase(token, EngineOperationPhase.STARTING))
        val xrayRunning = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.RUNNING,
            message = "Xray is running.",
            profileId = "profile-xray"
        )
        assertEquals(setOf(VpnEngineId.XRAY_CORE), coordinator.beginStartHandoff(token, listOf(xrayRunning)))

        val stopFailure = xrayRunning.copy(
            state = EngineState.FAILED,
            message = "Xray stop failed."
        )
        assertTrue(coordinator.observeServiceStatus(stopFailure))
        assertEquals(EngineStartHandoffState.FAILED, coordinator.startHandoffState(token))
        assertEquals(EngineOperationPhase.FAILED, coordinator.snapshot().phase)
        assertFalse(coordinator.isCurrent(token))
        assertFalse(coordinator.markStartRequested(token))
    }

    @Test
    fun changedTerminalStatusCanReconcileFailureBeforeFirstActivePoll() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending("profile-fast-fail")
        assertTrue(coordinator.bindEngine(token, VpnEngineId.XRAY_CORE, "profile-fast-fail"))
        val baseline = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.FAILED,
            message = "Previous failure.",
            profileId = "profile-fast-fail"
        )
        assertTrue(coordinator.markStartRequested(token, baseline))
        assertFalse(coordinator.observeServiceStatus(baseline))

        val newFailure = baseline.copy(message = "New startup failure.")
        assertTrue(coordinator.observeServiceStatus(newFailure))
        assertNull(coordinator.snapshot().requestedEngineId)
        assertEquals(EngineOperationPhase.FAILED, coordinator.snapshot().phase)
    }

    @Test
    fun explicitStopInvalidatesPendingCallbacksAndWaitsForEachServiceToStop() {
        val coordinator = VpnEngineCoordinator()
        val initial = coordinator.beginPending("profile-xray")
        assertTrue(coordinator.bindEngine(initial, VpnEngineId.XRAY_CORE, "profile-xray"))
        assertTrue(coordinator.markStartRequested(initial))

        val pending = coordinator.beginPending("profile-next")
        assertTrue(coordinator.bindEngine(pending, VpnEngineId.WIREGUARD_GO, "profile-next"))
        val wireGuardRunning = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.RUNNING,
            message = "WireGuard is running.",
            profileId = "profile-wg"
        )
        val xrayConnecting = EngineStatus(
            kind = EngineKind.XRAY_CORE,
            state = EngineState.CONNECTING,
            message = "Xray is connecting.",
            profileId = "profile-xray"
        )

        coordinator.stopRequested(listOf(wireGuardRunning, xrayConnecting))
        assertFalse(coordinator.isCurrent(pending))
        assertEquals(EngineOperationPhase.STOPPING, coordinator.snapshot().phase)
        assertEquals(setOf(VpnEngineId.WIREGUARD_GO, VpnEngineId.XRAY_CORE), coordinator.snapshot().stoppingEngineIds)
        assertNull(coordinator.snapshot().requestedEngineId)

        val wrongProfileStopped = xrayConnecting.copy(
            state = EngineState.STOPPED,
            message = "An unrelated Xray profile stopped.",
            profileId = "profile-other"
        )
        assertFalse(coordinator.observeServiceStatus(wrongProfileStopped))
        assertTrue(coordinator.observeServiceStatus(wireGuardRunning.copy(state = EngineState.STOPPED)))
        assertEquals(EngineOperationPhase.STOPPING, coordinator.snapshot().phase)
        assertEquals(setOf(VpnEngineId.XRAY_CORE), coordinator.snapshot().stoppingEngineIds)

        assertTrue(coordinator.observeServiceStatus(xrayConnecting.copy(state = EngineState.STOPPED)))
        assertEquals(EngineOperationPhase.IDLE, coordinator.snapshot().phase)
        assertTrue(coordinator.snapshot().stoppingEngineIds.isEmpty())
    }

    @Test
    fun failedServiceStopLeavesCoordinatorInFailedPhase() {
        val coordinator = VpnEngineCoordinator()
        val running = EngineStatus(
            kind = EngineKind.WIREGUARD_GO,
            state = EngineState.RUNNING,
            message = "WireGuard is running.",
            profileId = "profile-wg"
        )
        coordinator.stopRequested(listOf(running))

        val stopFailure = running.copy(
            state = EngineState.FAILED,
            message = "WireGuard stop failed."
        )
        assertTrue(coordinator.observeServiceStatus(stopFailure))
        assertEquals(EngineOperationPhase.FAILED, coordinator.snapshot().phase)
        assertTrue(coordinator.snapshot().stoppingEngineIds.isEmpty())
    }

    @Test
    fun externalHandoffCannotBecomeAnInAppStartRequest() {
        val coordinator = VpnEngineCoordinator()
        val token = coordinator.beginPending("profile-openvpn")

        assertFalse(coordinator.bindEngine(token, VpnEngineId.OPENVPN_EXTERNAL, "profile-openvpn"))
        assertFalse(coordinator.markStartRequested(token))
        assertNull(coordinator.snapshot().requestedEngineId)
    }
}
