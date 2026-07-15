package com.example.interlink.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.interlink.models.Device
import com.example.interlink.ui.components.IntercomButton
import com.example.interlink.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDashboard(
    devices: List<Device>,
    hostIp: String?,
    onRefreshIp: () -> Unit,
    onPttPressed: () -> Unit,
    onPttReleased: () -> Unit,
    onBack: () -> Unit,
    onPickMusic: () -> Unit = {},
    onStopMusic: () -> Unit = {},
    isMusicPlaying: Boolean = false,
    pendingMusicRequests: List<com.example.interlink.models.MusicRequest> = emptyList(),
    onApproveMusic: (com.example.interlink.models.MusicRequest) -> Unit = {},
    onDenyMusic: (com.example.interlink.models.MusicRequest) -> Unit = {}
) {
    var isTalking by remember { mutableStateOf(false) }
    val isHotspot = hostIp == "192.168.43.1" || (hostIp != null && NetworkUtils.isHotspotActive())
    val networkType = NetworkUtils.getInterfaceType(hostIp)

    DisposableEffect(Unit) {
        onDispose {
            if (isTalking) onPttReleased()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshIp) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh IP")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ServerStatusCard(hostIp, isHotspot, networkType)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = if (isMusicPlaying) onStopMusic else onPickMusic,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = if (isMusicPlaying) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                }
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isMusicPlaying) "Stop Sharing Music" else "Share Music with Everyone")
            }
            
            if (pendingMusicRequests.isNotEmpty()) {
                MusicRequestsSection(pendingMusicRequests, onApproveMusic, onDenyMusic)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Connected Devices (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (devices.isEmpty()) {
                EmptyDevicesState()
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices) { device ->
                        DeviceItem(device)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            IntercomButton(
                isTalking = isTalking,
                onClick = {
                    isTalking = !isTalking
                    if (isTalking) onPttPressed() else onPttReleased()
                }
            )
        }
    }
}

@Composable
fun MusicRequestsSection(
    requests: List<com.example.interlink.models.MusicRequest>,
    onApprove: (com.example.interlink.models.MusicRequest) -> Unit,
    onDeny: (com.example.interlink.models.MusicRequest) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Music Sharing Requests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        requests.forEach { request ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(request.clientName, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDeny(request) }) { Text("Deny", color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { onApprove(request) }) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
fun ServerStatusCard(hostIp: String?, isHotspot: Boolean, networkType: String) {
    val isMobileData = networkType.contains("Mobile")
    
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = when {
                isHotspot -> Color(0xFF006064)
                isMobileData -> Color(0xFF1B5E20)
                else -> MaterialTheme.colorScheme.primary
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isHotspot || isMobileData) Color(0xFF4CAF50) else Color(0xFF4CAF50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "$networkType Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                hostIp ?: "Obtaining IP...",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    isHotspot -> "Others can join your Hotspot (Offline)"
                    isMobileData -> "Hosting via Mobile Data / Public IP"
                    else -> "Connect via Local Wi-Fi"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun DeviceItem(device: Device) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        ListItem(
            headlineContent = { Text(device.name, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(device.ipAddress) },
            trailingContent = {
                Badge(
                    containerColor = if (device.isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                ) {
                    Text(
                        if (device.isOnline) "Online" else "Offline",
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun ColumnScope.EmptyDevicesState() {
    Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Devices,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Waiting for devices to join...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
