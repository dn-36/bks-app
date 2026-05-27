package com.bkc.core.domain

import kotlin.time.Instant

data class Project(
    val id: String,
    val title: String,
    val objectId: String = "",
    val fileName: String = "",
    val fileUrl: String,
    val storagePath: String, // ✅ добавь это поле
    val createdBy: String,
    val createdAtMillis: Long
)
data class WorkObject(
    val id: String,
    val name: String,
    val photoUrl: String? = null
)
data class DiscussionTopic(val id: String, val title: String)
data class Specification(
    val id: String,
    val objectId: String,
    val name: String,
    val unit: String = "",
    val initialQuantity: Double,
    val remainingQuantity: Double,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class MaterialRequest(
    val id: String,
    val objectId: String,
    val recipientEmail: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val emailStatus: String,
    val createdAtMillis: Long,
    val items: List<MaterialRequestItem>
)

data class MaterialRequestItem(
    val id: String,
    val requestId: String,
    val specificationId: String,
    val specificationName: String,
    val unit: String,
    val quantity: Double
)

data class MaterialRequestItemInput(
    val specificationId: String,
    val quantity: Double
)
