package com.bkc.core.data

import com.bkc.core.domain.DiscussionTopic
import com.bkc.core.domain.MaterialRequest
import com.bkc.core.domain.MaterialRequestItemInput
import com.bkc.core.domain.Project
import com.bkc.core.domain.Specification
import com.bkc.core.domain.WorkObject
import com.bkc.core.domain.repository.DiscussionRepository
import com.bkc.core.domain.repository.ObjectRepository
import com.bkc.core.domain.repository.ProjectRepository
import com.bkc.core.domain.repository.SpecificationRepository
import kotlinx.coroutines.delay

class MockProjectRepository : ProjectRepository {

    override suspend fun getProjects(): List<Project> {
        delay(250)
        return (1..12).map {
            Project(
                it.toString(),
                "Проект $it",
                "",
                "",
                "",
                "",
                "",
                0L
            )
        }
    }

}

class MockObjectRepository : ObjectRepository {
    override suspend fun getObjects(): List<WorkObject> {
        delay(250)
        return (1..10).map { WorkObject(it.toString(), "Объект $it") }
    }

    override suspend fun addObject(name: String, photoFileName: String?, photoBytes: ByteArray?) = Unit

    override suspend fun updateObject(id: String, name: String, photoFileName: String?, photoBytes: ByteArray?) = Unit

    override suspend fun deleteObject(id: String) = Unit
}

class MockDiscussionRepository : DiscussionRepository {
    override suspend fun getTopics(): List<DiscussionTopic> {
        delay(250)
        return (1..14).map { DiscussionTopic(it.toString(), "Тема $it") }
    }
}

class MockSpecificationRepository : SpecificationRepository {
    override suspend fun getSpecifications(): List<Specification> {
        delay(250)
        return (1..8).map {
            Specification(
                id = it.toString(),
                objectId = "mock-object",
                name = "Материал $it",
                unit = "шт",
                initialQuantity = 50.0,
                remainingQuantity = 50.0,
                createdBy = "mock",
                createdAtMillis = 0L,
                updatedAtMillis = 0L
            )
        }
    }

    override suspend fun addSpecification(
        name: String,
        unit: String,
        initialQuantity: Double,
        remainingQuantity: Double
    ) = Unit

    override suspend fun updateSpecification(
        id: String,
        name: String,
        unit: String,
        initialQuantity: Double,
        remainingQuantity: Double
    ) = Unit

    override suspend fun deleteSpecification(id: String) = Unit

    override suspend fun getMaterialRequests(): List<MaterialRequest> = emptyList()

    override suspend fun submitMaterialRequest(
        recipientEmail: String,
        items: List<MaterialRequestItemInput>
    ): MaterialRequest {
        error("MockSpecificationRepository does not submit requests")
    }

    override suspend fun deleteMaterialRequest(id: String) = Unit
}
