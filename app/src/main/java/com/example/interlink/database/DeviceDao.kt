package com.example.interlink.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.interlink.models.Device
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAllDevices(): Flow<List<Device>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: Device)

    @Query("UPDATE devices SET isOnline = :isOnline WHERE ipAddress = :ipAddress")
    suspend fun updateStatus(ipAddress: String, isOnline: Boolean)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}
