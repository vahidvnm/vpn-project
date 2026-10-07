package com.vpnproject.app.engine

/** Opaque generation token used to ignore stale permission and prepare callbacks. */
data class EngineOperationToken internal constructor(val generation: Long)

enum class EngineOperationPhase {
    IDLE,
    AWAITING_PERMISSION,
    PREPARING,
    STARTING,
    START_REQUESTED,
    STOPPING,
    FAILED
}

enum class EngineStartHandoffState {
    WAITING,
    READY,
    FAILED,
    STALE
}

data class VpnEngineCoordinatorSnapshot(
    val generation: Long,
    val phase: EngineOperationPhase,
    val pendingProfileId: String?,
    val pendingEngineId: VpnEngineId?,
    /** Last accepted start request; this is not proof that the Android service is alive. */
    val requestedProfileId: String?,
    val requestedEngineId: VpnEngineId?,
    /** Engines still expected to report a terminal status after an explicit stop request. */
    val stoppingEngineIds: Set<VpnEngineId>
)

/**
 * Coordinates app-initiated engine requests without depending on Android UI or
 * services. A newer request or stop invalidates older asynchronous callbacks.
 * The requested engine fields describe app intent; service status remains the
 * source of truth for whether a tunnel is actually running.
 */
class VpnEngineCoordinator {
    private var generation = 0L
    private var pendingTokenGeneration: Long? = null
    private var phase = EngineOperationPhase.IDLE
    private var pendingProfileId: String? = null
    private var pendingEngineId: VpnEngineId? = null
    private var requestedProfileId: String? = null
    private var requestedEngineId: VpnEngineId? = null
    private var requestedStatusBaseline: EngineStatus? = null
    private var requestedServiceAcknowledged = false
    private data class StopTarget(
        val profileId: String?,
        val baselineStatus: EngineStatus?
    )

    private val stoppingTargets = mutableMapOf<VpnEngineId, StopTarget>()
    private var stopFailureObserved = false
    private var startHandoffTokenGeneration: Long? = null

    @Synchronized
    fun beginPending(
        profileId: String?,
        phase: EngineOperationPhase = EngineOperationPhase.PREPARING
    ): EngineOperationToken {
        require(phase == EngineOperationPhase.AWAITING_PERMISSION || phase == EngineOperationPhase.PREPARING) {
            "A new request must start in AWAITING_PERMISSION or PREPARING."
        }
        generation += 1L
        pendingTokenGeneration = generation
        pendingProfileId = profileId
        pendingEngineId = null
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = null
        this.phase = phase
        return EngineOperationToken(generation)
    }

    @Synchronized
    fun bindEngine(
        token: EngineOperationToken,
        engineId: VpnEngineId,
        profileId: String?
    ): Boolean {
        if (!isCurrentLocked(token)) return false
        if (engineId == VpnEngineId.NONE || engineId == VpnEngineId.OPENVPN_EXTERNAL) return false
        pendingEngineId = engineId
        pendingProfileId = profileId
        phase = EngineOperationPhase.PREPARING
        return true
    }

    @Synchronized
    fun setPhase(token: EngineOperationToken, next: EngineOperationPhase): Boolean {
        if (!isCurrentLocked(token)) return false
        require(next == EngineOperationPhase.PREPARING || next == EngineOperationPhase.STARTING) {
            "Pending requests can only transition to PREPARING or STARTING."
        }
        phase = next
        return true
    }

    @Synchronized
    fun isCurrent(token: EngineOperationToken): Boolean = isCurrentLocked(token)

    /**
     * Establish a barrier before starting a prepared engine. Only other
     * in-app engines with a non-terminal or failed status are explicitly stopped
     * and waited on; FAILED may still own OS resources. The selected engine may
     * restart its own existing service directly.
     * A null result means the operation token is stale or is not engine-bound.
     */
    @Synchronized
    fun beginStartHandoff(
        token: EngineOperationToken,
        serviceStatuses: List<EngineStatus>
    ): Set<VpnEngineId>? {
        if (!isCurrentLocked(token) || phase != EngineOperationPhase.STARTING) return null
        val selectedEngineId = pendingEngineId ?: return null
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = token.generation

        serviceStatuses.forEach { status ->
            val statusEngineId = engineIdFor(status)
            if (statusEngineId == selectedEngineId) {
                if (!isNonTerminalServiceState(status.state) && statusEngineId == requestedEngineId &&
                    (requestedProfileId == null || status.profileId == requestedProfileId)
                ) {
                    clearRequestedEngineLocked()
                }
                return@forEach
            }
            if (requiresExplicitStop(status)) {
                stoppingTargets[statusEngineId] = StopTarget(
                    profileId = status.profileId,
                    baselineStatus = status
                )
            } else if (statusEngineId == requestedEngineId &&
                (requestedProfileId == null || status.profileId == requestedProfileId)
            ) {
                clearRequestedEngineLocked()
            }
        }

        phase = if (stoppingTargets.isEmpty()) EngineOperationPhase.STARTING else EngineOperationPhase.STOPPING
        return stoppingTargets.keys.toSet()
    }

    @Synchronized
    fun startHandoffState(token: EngineOperationToken): EngineStartHandoffState {
        if (startHandoffTokenGeneration != token.generation) return EngineStartHandoffState.STALE
        return when {
            phase == EngineOperationPhase.FAILED -> EngineStartHandoffState.FAILED
            phase == EngineOperationPhase.STOPPING -> EngineStartHandoffState.WAITING
            phase == EngineOperationPhase.STARTING && stoppingTargets.isEmpty() -> EngineStartHandoffState.READY
            else -> EngineStartHandoffState.STALE
        }
    }

    @Synchronized
    fun markStartRequested(token: EngineOperationToken, baselineStatus: EngineStatus? = null): Boolean {
        if (!isCurrentLocked(token)) return false
        if (startHandoffTokenGeneration == token.generation &&
            (phase != EngineOperationPhase.STARTING || stoppingTargets.isNotEmpty() || stopFailureObserved)
        ) return false
        val engineId = pendingEngineId ?: return false
        requestedEngineId = engineId
        requestedProfileId = pendingProfileId
        requestedStatusBaseline = baselineStatus
        requestedServiceAcknowledged = false
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = null
        clearPendingLocked()
        phase = EngineOperationPhase.START_REQUESTED
        return true
    }

    /**
     * Fails only the current request. A previously requested engine is kept if
     * preparation failed before a handoff; callers clear it after start failure.
     */
    @Synchronized
    fun fail(token: EngineOperationToken, clearRequestedEngine: Boolean = false): Boolean {
        if (!isCurrentLocked(token)) return false
        clearPendingLocked()
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = null
        if (clearRequestedEngine) {
            requestedEngineId = null
            requestedProfileId = null
            requestedStatusBaseline = null
            requestedServiceAcknowledged = false
        }
        phase = if (requestedEngineId != null) EngineOperationPhase.START_REQUESTED else EngineOperationPhase.FAILED
        return true
    }

    @Synchronized
    fun cancel(token: EngineOperationToken): Boolean {
        if (!isCurrentLocked(token)) return false
        generation += 1L
        clearPendingLocked()
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = null
        phase = if (requestedEngineId != null) EngineOperationPhase.START_REQUESTED else EngineOperationPhase.IDLE
        return true
    }

    @Synchronized
    fun cancelPending(): Boolean {
        if (pendingTokenGeneration == null) return false
        generation += 1L
        clearPendingLocked()
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = null
        phase = if (requestedEngineId != null) EngineOperationPhase.START_REQUESTED else EngineOperationPhase.IDLE
        return true
    }

    /**
     * Observe a service status without launching work. A terminal status clears
     * the last start request only after a matching engine/profile reports a
     * running/connecting state or a status change from its pre-start baseline.
     * During an explicit stop, tracked engines reconcile independently against
     * their terminal service status.
     */
    @Synchronized
    fun observeServiceStatus(status: EngineStatus): Boolean {
        val statusEngineId = engineIdFor(status)
        if (phase == EngineOperationPhase.STOPPING && stoppingTargets.containsKey(statusEngineId)) {
            val target = stoppingTargets.getValue(statusEngineId)
            if (target.profileId != null && status.profileId != target.profileId) return false
            if (target.baselineStatus == status) return false
            return when (status.state) {
                EngineState.IDLE,
                EngineState.STOPPED -> finishStopTargetLocked(statusEngineId, failed = false)
                EngineState.FAILED -> finishStopTargetLocked(statusEngineId, failed = true)
                EngineState.PREPARING_CONFIG,
                EngineState.CONNECTING,
                EngineState.RUNNING,
                EngineState.VERIFYING,
                EngineState.VERIFIED,
                EngineState.RECONNECTING,
                EngineState.STOPPING -> false
            }
        }

        if (statusEngineId != requestedEngineId) return false
        if (requestedProfileId != null && status.profileId != requestedProfileId) return false
        if (status == requestedStatusBaseline) return false

        when (status.state) {
            EngineState.CONNECTING,
            EngineState.RUNNING,
            EngineState.VERIFYING,
            EngineState.VERIFIED,
            EngineState.RECONNECTING -> {
                requestedServiceAcknowledged = true
                return false
            }
            EngineState.STOPPED,
            EngineState.FAILED -> {
                if (!requestedServiceAcknowledged && requestedStatusBaseline == null) return false
                requestedEngineId = null
                requestedProfileId = null
                requestedStatusBaseline = null
                requestedServiceAcknowledged = false
                if (pendingTokenGeneration == null) {
                    phase = if (status.state == EngineState.FAILED) EngineOperationPhase.FAILED else EngineOperationPhase.IDLE
                }
                return true
            }
            EngineState.IDLE,
            EngineState.PREPARING_CONFIG,
            EngineState.STOPPING -> return false
        }
    }

    /** Invalidate pending work immediately while retaining service stop targets. */
    @Synchronized
    fun stopRequested(serviceStatuses: List<EngineStatus> = emptyList()) {
        val previousRequestedEngineId = requestedEngineId
        val previousRequestedProfileId = requestedProfileId
        generation += 1L
        clearPendingLocked()
        stoppingTargets.clear()
        stopFailureObserved = false
        startHandoffTokenGeneration = null

        if (serviceStatuses.isEmpty() && previousRequestedEngineId != null) {
            stoppingTargets[previousRequestedEngineId] = StopTarget(
                profileId = previousRequestedProfileId,
                baselineStatus = requestedStatusBaseline
            )
        } else {
            serviceStatuses.forEach { status ->
                if (requiresExplicitStop(status)) {
                    stoppingTargets[engineIdFor(status)] = StopTarget(
                        profileId = status.profileId,
                        baselineStatus = status
                    )
                }
            }
        }

        requestedEngineId = null
        requestedProfileId = null
        requestedStatusBaseline = null
        requestedServiceAcknowledged = false
        phase = if (stoppingTargets.isEmpty()) EngineOperationPhase.IDLE else EngineOperationPhase.STOPPING
    }

    @Synchronized
    fun snapshot(): VpnEngineCoordinatorSnapshot = VpnEngineCoordinatorSnapshot(
        generation = generation,
        phase = phase,
        pendingProfileId = pendingProfileId,
        pendingEngineId = pendingEngineId,
        requestedProfileId = requestedProfileId,
        requestedEngineId = requestedEngineId,
        stoppingEngineIds = stoppingTargets.keys.toSet()
    )

    private fun engineIdFor(status: EngineStatus): VpnEngineId = when (status.kind) {
        EngineKind.XRAY_CORE -> VpnEngineId.XRAY_CORE
        EngineKind.WIREGUARD_GO -> VpnEngineId.WIREGUARD_GO
    }

    private fun isNonTerminalServiceState(state: EngineState): Boolean = when (state) {
        EngineState.PREPARING_CONFIG,
        EngineState.CONNECTING,
        EngineState.RUNNING,
        EngineState.VERIFYING,
        EngineState.VERIFIED,
        EngineState.RECONNECTING,
        EngineState.STOPPING -> true
        EngineState.IDLE,
        EngineState.STOPPED,
        EngineState.FAILED -> false
    }

    /** A failed service may still own resources; issue and confirm an explicit stop. */
    private fun requiresExplicitStop(status: EngineStatus): Boolean =
        isNonTerminalServiceState(status.state) || status.state == EngineState.FAILED

    private fun finishStopTargetLocked(engineId: VpnEngineId, failed: Boolean): Boolean {
        stoppingTargets.remove(engineId)
        if (requestedEngineId == engineId) clearRequestedEngineLocked()
        if (failed) stopFailureObserved = true
        if (stoppingTargets.isEmpty()) {
            if (startHandoffTokenGeneration != null) {
                if (stopFailureObserved) {
                    phase = EngineOperationPhase.FAILED
                    clearPendingLocked()
                } else {
                    phase = EngineOperationPhase.STARTING
                }
            } else {
                phase = if (stopFailureObserved) EngineOperationPhase.FAILED else EngineOperationPhase.IDLE
            }
        }
        return true
    }

    private fun clearRequestedEngineLocked() {
        requestedEngineId = null
        requestedProfileId = null
        requestedStatusBaseline = null
        requestedServiceAcknowledged = false
    }

    private fun isCurrentLocked(token: EngineOperationToken): Boolean =
        pendingTokenGeneration == token.generation

    private fun clearPendingLocked() {
        pendingTokenGeneration = null
        pendingProfileId = null
        pendingEngineId = null
    }
}
