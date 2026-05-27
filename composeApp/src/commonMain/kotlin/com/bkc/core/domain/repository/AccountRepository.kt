package com.bkc.core.domain.repository

import com.bkc.core.data.local_storage.models.UserProfile
import com.bkc.core.domain.PlatformUser

interface AccountRepository {
    suspend fun loadMe(): UserProfile
    suspend fun updateMe(
        email: String,
        firstName: String,
        lastName: String,
        nickname: String,
        bio: String,
        phone: String,
        avatarFileName: String?,
        avatarBytes: ByteArray?,
        privacyProfileVisible: Boolean,
        notificationsEnabled: Boolean
    ): UserProfile
    suspend fun listUsers(query: String, accountStatus: String? = null, adminMode: Boolean = false): List<PlatformUser>
    suspend fun updateUserAccess(uid: String, accountStatus: String, blockedReason: String)
    suspend fun deleteUser(uid: String, deleteData: Boolean = false)
}
