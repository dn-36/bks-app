package com.bkc.core.domain

data class ShiftForm(
    val objectId: String,
    val questions: List<ShiftQuestion>
)

data class ShiftQuestion(
    val id: String,
    val objectId: String,
    val prompt: String,
    val type: String,
    val options: List<String>,
    val position: Int,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class ShiftReport(
    val id: String,
    val objectId: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val senderStatus: String,
    val createdAtMillis: Long,
    val answers: List<ShiftReportAnswer>
)

data class ShiftReportAnswer(
    val id: String,
    val reportId: String,
    val questionId: String,
    val questionPrompt: String,
    val questionType: String,
    val value: String,
    val position: Int
)

data class ShiftQuestionInput(
    val prompt: String,
    val type: String,
    val options: List<String> = emptyList()
)

data class ShiftAnswerInput(
    val questionId: String,
    val value: String
)

const val SHIFT_QUESTION_TEXT = "TEXT"
const val SHIFT_QUESTION_CHOICE = "CHOICE"
