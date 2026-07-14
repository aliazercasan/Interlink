package com.example.interlink

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.interlink.service.IntercomService
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
                            onSettings = { /* Handle settings */ }
                        )
                    }
                    composable("host") {
                        HostDashboard(
                            devices = devices,
                            hostIp = hostIp,
                            onRefreshIp = { viewModel.refreshHostIp() },
                            onPttPressed = { intercomService?.startPtt() },
                            onPttReleased = { intercomService?.stopPtt() },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("client") {
                        ClientDashboard(
                            status = "Connected",
                            onPttPressed = { intercomService?.startPtt() },
                            onPttReleased = { intercomService?.stopPtt() },
                            onBack = { navController.popBackStack() }
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
