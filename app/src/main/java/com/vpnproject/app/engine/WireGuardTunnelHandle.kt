package com.vpnproject.app.engine

import com.wireguard.android.backend.Tunnel

class WireGuardTunnelHandle(
    private val tunnelName: String,
    private val stateCallback: (Tunnel.State) -> Unit = {}
) : Tunnel {
    @Volatile
    private var currentState: Tunnel.State = Tunnel.State.DOWN

    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        currentState = newState
        stateCallback(newState)
    }

    fun state(): Tunnel.State = currentState
}
