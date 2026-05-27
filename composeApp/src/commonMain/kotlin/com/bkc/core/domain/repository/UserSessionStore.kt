package com.bkc.core.domain.repository

import com.bkc.core.data.local_storage.SavedUser

interface UserSessionStore {
    suspend fun saveUser(user: SavedUser)
    suspend fun getUserOrNull(): SavedUser?
    suspend fun clear()
}