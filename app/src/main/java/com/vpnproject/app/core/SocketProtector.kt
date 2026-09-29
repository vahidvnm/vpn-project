package com.vpnproject.app.core

import java.net.DatagramSocket
import java.net.Socket

/**
 * Lets outbound control/probe sockets bypass the VPN tunnel when they are
 * created from a VpnService. The default implementation is safe for unit tests
 * and pre-VPN UI probes.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean
}

object NoOpSocketProtector : SocketProtector {
    override fun protect(socket: Socket): Boolean = true
    override fun protect(socket: DatagramSocket): Boolean = true
}
