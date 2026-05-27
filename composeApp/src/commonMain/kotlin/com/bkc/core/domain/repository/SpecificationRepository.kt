package com.bkc.core.domain.repository

import com.bkc.core.domain.MaterialRequest
import com.bkc.core.domain.MaterialRequestItemInput
import com.bkc.core.domain.Specification


interface SpecificationRepository {
    suspend fun getSpecifications(): List<Specification>
    suspend fun addSpecification(name: String, unit: String, initialQuantity: Double, remainingQuantity: Double)
    suspend fun updateSpecification(
        id: String,
        name: String,
        unit: String,
        initialQuantity: Double,
        remainingQuantity: Double
    )
    suspend fun deleteSpecification(id: String)
    suspend fun getMaterialRequests(): List<MaterialRequest>
    suspend fun submitMaterialRequest(recipientEmail: String, items: List<MaterialRequestItemInput>): MaterialRequest
    suspend fun deleteMaterialRequest(id: String)
}
