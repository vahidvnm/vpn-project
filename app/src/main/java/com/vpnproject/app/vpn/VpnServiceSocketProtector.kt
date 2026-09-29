package com.vpnproject.app.vpn

import android.net.VpnService
import com.vpnproject.app.core.SocketProtector
import java.net.DatagramSocket
import java.net.Socket

class VpnServiceSocketProtector(
    private val vpnService: VpnService
) : SocketProtector {
    override fun protect(socket: Socket): Boolean = vpnService.protect(socket)
    override fun protect(socket: DatagramSocket): Boolean = vpnService.protect(socket)
}
