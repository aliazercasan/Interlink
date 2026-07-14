package com.example.interlink.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class UserPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private val USERNAME_KEY = stringPreferencesKey("username")
    private val DEVICE_NAME_KEY = stringPreferencesKey("device_name")
    private val HOST_IP_KEY = stringPreferencesKey("host_ip")

    val username: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[USERNAME_KEY] ?: "User"
    }

    val deviceName: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_NAME_KEY] ?: android.os.Build.MODEL
    }

    val hostIp: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[HOST_IP_KEY]
    }

    suspend fun updateUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = username
        }
    }

    suspend fun updateDeviceName(deviceName: String) {
        context.dataStore.edit { preferences ->
            preferences[DEVICE_NAME_KEY] = deviceName
        }
    }

    suspend fun updateHostIp(hostIp: String) {
        context.dataStore.edit { preferences ->
            preferences[HOST_IP_KEY] = hostIp
        }
    }
}
