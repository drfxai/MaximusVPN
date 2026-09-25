package com.example.vpn.tunnel

/** Signed modular distance is valid within TCP's less-than-2^31 receive window. */
internal object TcpSequence {
    fun add(seq: Long, count: Int): Long = (seq + count) and 0xffffffffL
    fun acceptedOffset(expected: Long, sequence: Long, length: Int): Int? {
        val delta = (sequence - expected).toInt()
        if (delta > 0) return null // Gap: ACK current expected sequence; sender retransmits.
        val alreadyReceived = -delta.toLong()
        return if (alreadyReceived >= length) null else alreadyReceived.toInt()
    }
}
