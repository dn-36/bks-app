package com.bkc.core.data.local_storage

import com.bkc.core.domain.repository.UserSessionStore
import com.russhwolf.settings.Settings


class SettingsUserSessionStore(
    private val settings: Settings
) : UserSessionStore {

    private companion object {
        const val KEY_UID = "user.uid"
        const val KEY_EMAIL = "user.email"
        const val KEY_FIRST_NAME = "user.firstName"
        const val KEY_LAST_NAME = "user.lastName"
        const val KEY_NICKNAME = "user.nickname"
        const val KEY_AVATAR_URL = "user.avatarUrl"
        const val KEY_BIO = "user.bio"
        const val KEY_PHONE = "user.phone"
        const val KEY_AUTH_TOKEN = "user.authToken"
        const val KEY_ROLE = "user.role"
        const val KEY_STATUS = "user.status"
        const val KEY_ACCOUNT_STATUS = "user.accountStatus"
        const val KEY_BLOCKED_REASON = "user.blockedReason"
        const val KEY_PRIVACY_PROFILE_VISIBLE = "user.privacyProfileVisible"
        const val KEY_NOTIFICATIONS_ENABLED = "user.notificationsEnabled"
        const val KEY_CREATED_AT = "user.createdAt"
        const val KEY_SELECTED_OBJECT_ID = "user.selectedObjectId"
        const val KEY_SELECTED_OBJECT_NAME = "user.selectedObjectName"
        const val KEY_SELECTED_OBJECT_PHOTO_URL = "user.selectedObjectPhotoUrl"
        const val KEY_MATERIAL_REQUEST_EMAIL = "user.materialRequestEmail"
    }

    override suspend fun saveUser(user: SavedUser) {
        settings.putString(KEY_UID, user.uid)
        settings.putString(KEY_EMAIL, user.email)
        settings.putString(KEY_FIRST_NAME, user.firstName)
        settings.putString(KEY_LAST_NAME, user.lastName)
        settings.putString(KEY_NICKNAME, user.nickname)
        settings.putString(KEY_AVATAR_URL, user.avatarUrl.orEmpty())
        settings.putString(KEY_BIO, user.bio)
        settings.putString(KEY_PHONE, user.phone)
        settings.putString(KEY_AUTH_TOKEN, user.authToken)
        settings.putString(KEY_ROLE, user.role)
        settings.putString(KEY_STATUS, user.status)
        settings.putString(KEY_ACCOUNT_STATUS, user.accountStatus)
        settings.putString(KEY_BLOCKED_REASON, user.blockedReason)
        settings.putBoolean(KEY_PRIVACY_PROFILE_VISIBLE, user.privacyProfileVisible)
        settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, user.notificationsEnabled)
        settings.putLong(KEY_CREATED_AT, user.createdAt)
        settings.putString(KEY_SELECTED_OBJECT_ID, user.selectedObjectId.orEmpty())
        settings.putString(KEY_SELECTED_OBJECT_NAME, user.selectedObjectName.orEmpty())
        settings.putString(KEY_SELECTED_OBJECT_PHOTO_URL, user.selectedObjectPhotoUrl.orEmpty())
        settings.putString(KEY_MATERIAL_REQUEST_EMAIL, user.materialRequestEmail)
    }

    override suspend fun getUserOrNull(): SavedUser? {
        val uid = settings.getStringOrNull(KEY_UID) ?: return null
        val email = settings.getStringOrNull(KEY_EMAIL) ?: return null

        return SavedUser(
            uid = uid,
            email = email,
            firstName = settings.getString(KEY_FIRST_NAME, ""),
            lastName = settings.getString(KEY_LAST_NAME, ""),
            nickname = settings.getString(KEY_NICKNAME, ""),
            avatarUrl = settings.getString(KEY_AVATAR_URL, "").ifBlank { null },
            bio = settings.getString(KEY_BIO, ""),
            phone = settings.getString(KEY_PHONE, ""),
            authToken = settings.getString(KEY_AUTH_TOKEN, ""),
            role = settings.getString(KEY_ROLE, "USER"),
            status = normalizeStatus(
                settings.getString(KEY_STATUS, defaultStatusForRole(settings.getString(KEY_ROLE, "USER")))
            ),
            accountStatus = settings.getString(KEY_ACCOUNT_STATUS, "ACTIVE"),
            blockedReason = settings.getString(KEY_BLOCKED_REASON, ""),
            privacyProfileVisible = settings.getBoolean(KEY_PRIVACY_PROFILE_VISIBLE, true),
            notificationsEnabled = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
            createdAt = settings.getLong(KEY_CREATED_AT, 0L),
            selectedObjectId = settings.getString(KEY_SELECTED_OBJECT_ID, "").ifBlank { null },
            selectedObjectName = settings.getString(KEY_SELECTED_OBJECT_NAME, "").ifBlank { null },
            selectedObjectPhotoUrl = settings.getString(KEY_SELECTED_OBJECT_PHOTO_URL, "").ifBlank { null },
            materialRequestEmail = settings.getString(KEY_MATERIAL_REQUEST_EMAIL, "")
        )
    }

    override suspend fun clear() {
        settings.remove(KEY_UID)
        settings.remove(KEY_EMAIL)
        settings.remove(KEY_FIRST_NAME)
        settings.remove(KEY_LAST_NAME)
        settings.remove(KEY_NICKNAME)
        settings.remove(KEY_AVATAR_URL)
        settings.remove(KEY_BIO)
        settings.remove(KEY_PHONE)
        settings.remove(KEY_AUTH_TOKEN)
        settings.remove(KEY_ROLE)
        settings.remove(KEY_STATUS)
        settings.remove(KEY_ACCOUNT_STATUS)
        settings.remove(KEY_BLOCKED_REASON)
        settings.remove(KEY_PRIVACY_PROFILE_VISIBLE)
        settings.remove(KEY_NOTIFICATIONS_ENABLED)
        settings.remove(KEY_CREATED_AT)
        settings.remove(KEY_SELECTED_OBJECT_ID)
        settings.remove(KEY_SELECTED_OBJECT_NAME)
        settings.remove(KEY_SELECTED_OBJECT_PHOTO_URL)
        settings.remove(KEY_MATERIAL_REQUEST_EMAIL)
    }

    private fun defaultStatusForRole(role: String): String =
        if (role == "ADMIN") "FOREMAN" else "ELECTRICIAN"

    private fun normalizeStatus(status: String): String =
        if (status == "ADMIN") "FOREMAN" else status
}

data class SavedUser(
    val uid: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String = "",
    val authToken: String = "",
    val role: String = "USER",
    val status: String = "ELECTRICIAN",
    val accountStatus: String = "ACTIVE",
    val blockedReason: String = "",
    val privacyProfileVisible: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val createdAt: Long = 0L,
    val selectedObjectId: String? = null,
    val selectedObjectName: String? = null,
    val selectedObjectPhotoUrl: String? = null,
    val materialRequestEmail: String = ""
)
