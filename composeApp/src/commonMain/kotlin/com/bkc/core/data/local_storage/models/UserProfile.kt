package com.bkc.core.data.local_storage.models

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val uid: String,
    val email: String? = null,
    val firstName: String,
    val lastName: String,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String = "",
    val role: String = "USER",
    val status: String = "ELECTRICIAN",
    val accountStatus: String = "ACTIVE",
    val blockedReason: String = "",
    val privacyProfileVisible: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val lastSeenAt: Long = 0L,
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L
)
