package com.bkc.core.domain.repository

import com.bkc.core.domain.DiscussionTopic


class GetDiscussions(private val repo: DiscussionRepository) { suspend operator fun invoke(): List<DiscussionTopic> = repo.getTopics() }
