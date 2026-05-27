package com.bkc.core.domain.repository

import com.bkc.core.domain.Specification


class GetSpecifications(private val repo: SpecificationRepository) { suspend operator fun invoke(): List<Specification> = repo.getSpecifications() }