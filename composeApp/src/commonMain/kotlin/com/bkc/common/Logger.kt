package com.bkc.common

expect object Logger {
    fun d(tag: String, message: String)
}