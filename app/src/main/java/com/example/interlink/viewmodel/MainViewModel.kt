package com.example.interlink.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.interlink.repository.DeviceRepository
import com.example.interlink.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import com.example.interlink.utils.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _hostIp = MutableStateFlow<String?>(null)
    val hostIp = _hostIp.asStateFlow()

    fun refreshHostIp() {
        _hostIp.value = NetworkUtils.getLocalIpAddress()
    }

    val devices = deviceRepository.allDevices.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val username = userPreferences.username.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "User"
    )

    fun updateUsername(name: String) {
        viewModelScope.launch {
            userPreferences.updateUsername(name)
        }
    }
}
