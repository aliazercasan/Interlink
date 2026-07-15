package com.example.interlink

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.interlink.service.IntercomService
import com.example.interlink.client.ClientManager
import com.example.interlink.ui.screens.ClientDashboard
import com.example.interlink.ui.screens.HomeScreen
import com.example.interlink.ui.screens.HostDashboard
import com.example.interlink.ui.theme.InterlinkTheme
import com.example.interlink.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var intercomService: IntercomService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as IntercomService.IntercomBinder
            intercomService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            intercomService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startAndBindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (hasRequiredPermissions()) {
            startAndBindService()
        } else {
            requestPermissions()
        }

        setContent {
            InterlinkTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                val devices by viewModel.devices.collectAsState()
                val hostIp by viewModel.hostIp.collectAsState()
                
                // Navigate to dashboard if service is already active
                LaunchedEffect(isBound) {
                    if (isBound) {
                        intercomService?.let { service ->
                            if (service.isHostActive()) {
                                navController.navigate("host") {
                                    popUpTo("home") { inclusive = false }
                                }
                            } else if (service.isClientActive()) {
                                navController.navigate("client") {
                                    popUpTo("home") { inclusive = false }
                                }
                            }
                        }
                    }
                }

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onCreateServer = {
                                intercomService?.startAsHost()
                                viewModel.refreshHostIp()
                                navController.navigate("host")
                            },
                            onJoinServer = { ip ->
                                intercomService?.startAsClient(ip)
                                navController.navigate("client")
                            },
                            onSettings = { /* Handle settings */ },
                            onRestart = {
                                intercomService?.let {
                                    val intent = Intent(this@MainActivity, it::class.java)
                                    stopService(intent)
                                }
                                val intent = Intent(this@MainActivity, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(intent)
                                Runtime.getRuntime().exit(0)
                            }
                        )
                    }
                    composable("host") {
                        val pendingRequests by intercomService?.getPendingMusicRequests()?.collectAsState(emptyList()) ?: remember { mutableStateOf(emptyList()) }
                        val isMusicStreaming by intercomService?.isMusicStreaming?.collectAsState(false) ?: remember { mutableStateOf(false) }
                        
                        val hostMusicPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent()
                        ) { uri: Uri? ->
                            uri?.let { intercomService?.startMusicSharing(it) }
                        }

                        HostDashboard(
                            devices = devices,
                            hostIp = hostIp,
                            pendingMusicRequests = pendingRequests,
                            onRefreshIp = { viewModel.refreshHostIp() },
                            onPttPressed = { intercomService?.startPtt() },
                            onPttReleased = { intercomService?.stopPtt() },
                            onApproveMusic = { intercomService?.approveMusicRequest(it) },
                            onDenyMusic = { intercomService?.denyMusicRequest(it) },
                            onPickMusic = { hostMusicPicker.launch("audio/*") },
                            onStopMusic = { intercomService?.stopMusicSharing() },
                            isMusicPlaying = isMusicStreaming,
                            onBack = { 
                                intercomService?.stopMusicSharing()
                                navController.popBackStack() 
                            }
                        )
                    }
                    composable("client") {
                        val clientStatus by intercomService?.getClientConnectionStatus()?.collectAsState(ClientManager.ConnectionStatus.DISCONNECTED) ?: remember { mutableStateOf(ClientManager.ConnectionStatus.DISCONNECTED) }
                        val musicApproved by intercomService?.isMusicSharingApproved()?.collectAsState(false) ?: remember { mutableStateOf(false) }
                        val username by viewModel.username.collectAsState()
                        
                        val musicPickerLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent()
                        ) { uri: Uri? ->
                            uri?.let { intercomService?.startMusicSharing(it) }
                        }

                        ClientDashboard(
                            status = clientStatus.name,
                            isConnected = clientStatus == ClientManager.ConnectionStatus.CONNECTED,
                            isMusicSharingApproved = musicApproved,
                            onRequestMusicShare = { intercomService?.requestMusicShare(username) },
                            onStopMusicShare = { 
                                intercomService?.stopMusicSharing()
                                intercomService?.stopMusicShare() 
                            },
                            onPickMusic = { musicPickerLauncher.launch("audio/*") },
                            onPttPressed = { intercomService?.startPtt() },
                            onPttReleased = { intercomService?.stopPtt() },
                            onBack = { 
                                intercomService?.stopMusicSharing()
                                navController.popBackStack() 
                            }
                        )
                    }
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startAndBindService() {
        val intent = Intent(this, IntercomService::class.java)
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
