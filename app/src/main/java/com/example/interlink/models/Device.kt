package com.example.interlink.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val ipAddress: String,
    val name: String,
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)
