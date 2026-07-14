package com.example.interlink.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.interlink.models.Device

@Database(entities = [Device::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}
