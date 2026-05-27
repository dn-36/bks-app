package com.bkc

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform