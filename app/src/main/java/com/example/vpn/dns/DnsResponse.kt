package com.example.vpn.dns

internal object DnsResponse {
    fun isResponseTo(query: ByteArray, response: ByteArray): Boolean =
        query.size >= 12 && response.size in 12..65507 &&
        query[0] == response[0] && query[1] == response[1] &&
        (response[2].toInt() and 0x80) != 0 &&
        (query[2].toInt() and 0x78) == (response[2].toInt() and 0x78)
}
