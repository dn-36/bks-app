package com.bkc.core.domain.repository

import com.bkc.core.domain.WorkObject


interface ObjectRepository {
    suspend fun getObjects(): List<WorkObject>
    suspend fun addObject(name: String, photoFileName: String?, photoBytes: ByteArray?)
    suspend fun updateObject(id: String, name: String, photoFileName: String?, photoBytes: ByteArray?)
    suspend fun deleteObject(id: String)
}
