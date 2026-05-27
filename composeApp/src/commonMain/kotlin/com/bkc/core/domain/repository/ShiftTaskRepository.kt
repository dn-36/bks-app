package com.bkc.core.domain.repository

import com.bkc.core.domain.ShiftAnswerInput
import com.bkc.core.domain.ShiftForm
import com.bkc.core.domain.ShiftQuestionInput
import com.bkc.core.domain.ShiftReport

interface ShiftTaskRepository {
    suspend fun getForm(): ShiftForm
    suspend fun saveForm(questions: List<ShiftQuestionInput>): ShiftForm
    suspend fun submitReport(answers: List<ShiftAnswerInput>): ShiftReport
    suspend fun getReports(query: String): List<ShiftReport>
}
