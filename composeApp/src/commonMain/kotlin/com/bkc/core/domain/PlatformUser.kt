package com.bkc.core.domain

data class PlatformUser(
    val uid: String,
    val email: String? = null,
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String? = null,
    val role: String = "USER",
    val status: String = "ELECTRICIAN",
    val accountStatus: String = "ACTIVE",
    val blockedReason: String? = null,
    val isOnline: Boolean = false,
    val lastSeenAt: Long = 0L,
    val createdAt: Long = 0L
)
