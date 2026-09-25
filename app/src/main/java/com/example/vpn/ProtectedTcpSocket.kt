package com.example.vpn

import java.net.InetSocketAddress
import java.net.Socket

/** A new Java Socket needs a bound file descriptor before Android can protect it. */
internal fun protectTcpSocket(socket: Socket, protect: (Socket) -> Boolean): Boolean {
    if (socket.isClosed || socket.isConnected) return false
    if (!socket.isBound) socket.bind(InetSocketAddress(0))
    return protect(socket)
}
