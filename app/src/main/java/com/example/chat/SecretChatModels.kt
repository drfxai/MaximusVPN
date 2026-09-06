package com.example.chat

import java.util.UUID

enum class EphemeralTimer(val seconds: Long, val label: String) {
    OFF(0L, "Keep Messages"),
    ONE_MINUTE(60L, "1 Minute Shred"),
    FIVE_MINUTES(300L, "5 Minutes Shred"),
    ONE_HOUR(3600L, "1 Hour Shred"),
    TWENTY_FOUR_HOURS(86400L, "24 Hours Shred")
}

data class SecretConversation(
    val id: String = UUID.randomUUID().toString(),
    val peerAtomicId: String,
    val peerAlias: String = "",
    val ephemeralTimer: EphemeralTimer = EphemeralTimer.FIVE_MINUTES,
    val unreadCount: Int = 0,
    val lastMessageText: String? = null,
    val lastMessageTime: Long = System.currentTimeMillis(),
    val isVerifiedE2ee: Boolean = true
)

data class SecretMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderAtomicId: String,
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val ephemeralTimer: EphemeralTimer = EphemeralTimer.FIVE_MINUTES,
    val expiresAt: Long? = if (ephemeralTimer.seconds > 0) System.currentTimeMillis() + ephemeralTimer.seconds * 1000 else null,
    val isEncrypted: Boolean = true,
    val deliveryStatus: MessageStatus = MessageStatus.SENT
)

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ
}
