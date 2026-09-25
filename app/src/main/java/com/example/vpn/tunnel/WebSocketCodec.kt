package com.example.vpn.tunnel

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.SecureRandom

object WebSocketCodec {
    private val random = SecureRandom()
    fun encodeFrame(payload: ByteArray, opcode: Int = 2): ByteArray {
        require(opcode in setOf(2, 8, 9, 10))
        require(opcode < 8 || payload.size <= 125)
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        val n = payload.size
        when {
            n <= 125 -> out.write(0x80 or n)
            n <= 65535 -> { out.write(0xfe); out.write(n ushr 8); out.write(n) }
            else -> { out.write(0xff); for (i in 7 downTo 0) out.write((n.toLong() ushr (i * 8)).toInt()) }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        out.write(mask)
        out.write(ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        return out.toByteArray()
    }

    fun readFrame(input: InputStream, onPing: (ByteArray) -> Unit = {}): ByteArray? {
        val first = input.read()
        if (first == -1) return null
        fun byte(): Int = input.read().also { if (it < 0) throw EOFException("Truncated WebSocket frame") }
        require(first and 0x70 == 0) { "Unexpected WebSocket extension" }
        val opcode = first and 15
        require(opcode in setOf(0, 2, 8, 9, 10)) { "Unsupported WebSocket opcode" }
        val second = byte()
        require(second and 0x80 == 0) { "Server WebSocket frames must not be masked" }
        var size = (second and 127).toLong()
        if (size == 126L) size = ((byte() shl 8) or byte()).toLong()
        else if (size == 127L) {
            size = 0
            repeat(8) { size = (size shl 8) or byte().toLong(); require(size in 0..1048576) { "WebSocket frame too large" } }
        }
        require(size in 0..1048576) { "WebSocket frame too large" }
        require(opcode < 8 || (size <= 125 && first and 0x80 != 0)) { "Invalid WebSocket control frame" }
        val payload = ByteArray(size.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n < 0) throw EOFException("Truncated WebSocket payload")
            read += n
        }
        return when (opcode) {
            8 -> null
            9 -> { onPing(payload); ByteArray(0) }
            10 -> ByteArray(0)
            else -> payload
        }
    }
}

/** A WebSocket tunnel carries a byte stream, not one UDP packet per frame. */
internal class WebSocketInputStream(private val input: InputStream, private val onPing: (ByteArray) -> Unit) : InputStream() {
    private var pending = ByteArray(0)
    private var offset = 0
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        while (offset == pending.size) {
            pending = WebSocketCodec.readFrame(input, onPing) ?: return -1
            offset = 0
        }
        val n = minOf(length, pending.size - offset)
        pending.copyInto(buffer, start, offset, offset + n)
        offset += n
        return n
    }
}
