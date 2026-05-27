package com.bkc.screens.user_register.viewmodel.models

data class RegisterState(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val password: String = "",
    val status: RegisterStatus = RegisterStatus.Electrician,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

enum class RegisterStatus(
    val serverValue: String,
    val roleServerValue: String,
    val title: String
) {
    Administrator("ADMINISTRATOR", "ADMIN", "Администратор"),
    Foreman("FOREMAN", "ADMIN", "Прораб"),
    Electrician("ELECTRICIAN", "USER", "Электромонтажник")
}
