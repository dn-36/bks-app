package com.bkc.core.domain.repository

import com.bkc.core.domain.DiscussionTopic


interface DiscussionRepository { suspend fun getTopics(): List<DiscussionTopic> }