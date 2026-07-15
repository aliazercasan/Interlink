package com.example.interlink.models

data class MusicRequest(
    val clientIp: String,
    val clientName: String,
    val timestamp: Long = System.currentTimeMillis()
)
