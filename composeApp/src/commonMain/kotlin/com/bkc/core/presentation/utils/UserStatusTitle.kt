package com.bkc.core.presentation.utils

fun String.toUserStatusTitle(): String =
    when (uppercase()) {
        "ADMINISTRATOR" -> "Администратор"
        "FOREMAN" -> "Прораб"
        "ELECTRICIAN" -> "Электромонтажник"
        "DELETED" -> "Пользователь удален"
        else -> this
    }
