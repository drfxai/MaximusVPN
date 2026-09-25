package com.example.vpn.tunnel

import com.example.vpn.routing.RoutingEngine
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Strict, bounded validation for upstream HTTP CONNECT and SOCKS5 handshakes. */
internal object UpstreamProtocol {
    private const val MAX_HTTP_HEADER_BYTES = 16 * 1024

    fun validateHttpConnectResponse(input: InputStream) {
        val headerBytes = readUntilHeaderEnd(input)
        val headerText = headerBytes.toString(Charsets.ISO_8859_1)
        val statusLine = headerText.substringBefore("\r\n")
        val match = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(statusLine)
            ?: throw IllegalStateException("HTTP CONNECT proxy returned an invalid status line")
        val status = match.groupValues[1].toInt()
        check(status in 200..299) { "HTTP CONNECT proxy failed with status $status" }
    }

    fun performSocks5Connect(
        input: InputStream,
        output: OutputStream,
        destination: String,
        port: Int
    ) {
        require(port in 1..65535) { "SOCKS5 destination port is out of range" }

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val negotiation = ByteArray(2)
        readExact(input, negotiation)
        check(negotiation[0] == 0x05.toByte()) { "SOCKS5 server returned an invalid version" }
        check(negotiation[1] == 0x00.toByte()) { "SOCKS5 server requires unsupported authentication" }

        val request = java.io.ByteArrayOutputStream()
        request.write(byteArrayOf(0x05, 0x01, 0x00))
        val ipBytes = RoutingEngine.parseIpv4(destination)
        if (ipBytes != null) {
            request.write(0x01)
            request.write(ipBytes)
        } else {
            val domainBytes = destination.toByteArray(Charsets.UTF_8)
            require(domainBytes.isNotEmpty() && domainBytes.size <= 255) {
                "SOCKS5 destination name must contain 1..255 bytes"
            }
            request.write(0x03)
            request.write(domainBytes.size)
            request.write(domainBytes)
        }
        request.write((port ushr 8) and 0xff)
        request.write(port and 0xff)
        output.write(request.toByteArray())
        output.flush()

        val reply = ByteArray(4)
        readExact(input, reply)
        check(reply[0] == 0x05.toByte()) { "SOCKS5 connect reply used an invalid version" }
        check(reply[2] == 0x00.toByte()) { "SOCKS5 connect reply used a non-zero reserved byte" }
        check(reply[1] == 0x00.toByte()) { "SOCKS5 connect error code: ${reply[1].toInt() and 0xff}" }

        val addressLength = when (reply[3].toInt() and 0xff) {
            0x01 -> 4
            0x03 -> readRequiredByte(input).also {
                check(it > 0) { "SOCKS5 reply contained an empty domain" }
            }
            0x04 -> 16
            else -> throw IllegalStateException("SOCKS5 reply used an invalid address type")
        }
        readExact(input, ByteArray(addressLength + 2))
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var state = 0
        while (output.size() < MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) throw EOFException("HTTP CONNECT proxy closed before completing its response headers")
            output.write(value)
            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> return output.toByteArray()
                value == '\r'.code -> 1
                else -> 0
            }
        }
        throw IllegalStateException("HTTP CONNECT response headers exceed 16 KiB")
    }

    private fun readRequiredByte(input: InputStream): Int =
        input.read().also { if (it == -1) throw EOFException("Unexpected EOF from upstream proxy") }

    private fun readExact(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw EOFException("Unexpected EOF from upstream proxy")
            if (count == 0) {
                buffer[offset++] = readRequiredByte(input).toByte()
            } else {
                offset += count
            }
        }
    }
}
