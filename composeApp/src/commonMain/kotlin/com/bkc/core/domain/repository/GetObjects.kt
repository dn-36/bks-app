package com.bkc.core.domain.repository

import com.bkc.core.domain.WorkObject


class GetObjects(private val repo: ObjectRepository) { suspend operator fun invoke(): List<WorkObject> = repo.getObjects() }
