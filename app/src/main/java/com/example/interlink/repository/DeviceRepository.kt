package com.example.interlink.repository

import com.example.interlink.database.DeviceDao
import com.example.interlink.models.Device
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val deviceDao: DeviceDao
) {
    val allDevices: Flow<List<Device>> = deviceDao.getAllDevices()

    suspend fun insertDevice(device: Device) {
        deviceDao.insertDevice(device)
    }

    suspend fun updateDeviceStatus(ipAddress: String, isOnline: Boolean) {
        deviceDao.updateStatus(ipAddress, isOnline)
    }

    suspend fun clearDevices() {
        deviceDao.deleteAll()
    }
}
