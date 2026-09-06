package com.example.data.model

import java.security.MessageDigest
import java.util.Locale

object CanonicalFingerprint {

    /**
     * Generates a deterministic SHA-256 fingerprint for a proxy node configuration.
     * Nodes with identical core routing credentials across files or subscriptions will
     * produce identical fingerprints for automated deduplication.
     */
    fun compute(
        protocol: ProtocolType,
        server: String,
        port: Int,
        uuidOrPassword: String,
        transport: String = "tcp",
        security: String = "none",
        sni: String = "",
        path: String = "",
        serviceName: String = "",
        publicKey: String = "",
        shortId: String = "",
        alpn: String = ""
    ): String {
        val normalizedServer = server.trim().lowercase(Locale.US)
        val normalizedProtocol = protocol.name.lowercase(Locale.US)
        val normalizedTransport = transport.trim().lowercase(Locale.US)
        val normalizedSecurity = security.trim().lowercase(Locale.US)
        val normalizedSni = sni.trim().lowercase(Locale.US)
        val normalizedPath = path.trim()
        val normalizedServiceName = serviceName.trim()
        val normalizedPublicKey = publicKey.trim()
        val normalizedShortId = shortId.trim()
        val normalizedAlpn = alpn.trim().lowercase(Locale.US)
        val normalizedAuth = uuidOrPassword.trim()

        val rawSignature = listOf(
            normalizedProtocol,
            normalizedServer,
            port.toString(),
            normalizedAuth,
            normalizedTransport,
            normalizedSecurity,
            normalizedSni,
            normalizedPath,
            normalizedServiceName,
            normalizedPublicKey,
            normalizedShortId,
            normalizedAlpn
        ).joinToString("|")

        return sha256Hex(rawSignature)
    }

    fun computeFromProfile(profile: VlessProfile): String {
        return compute(
            protocol = profile.protocolType,
            server = profile.address,
            port = profile.port,
            uuidOrPassword = profile.uuid.ifBlank { profile.encryption },
            transport = profile.transport,
            security = profile.security,
            sni = profile.sni.ifBlank { profile.host },
            path = profile.path,
            serviceName = profile.serviceName,
            publicKey = profile.publicKey,
            shortId = profile.shortId,
            alpn = profile.alpn
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
