package com.bkc.core.presentation.utils

fun containsIgnoreCase(source: String, query: String): Boolean =
    source.lowercase().contains(query.trim().lowercase())