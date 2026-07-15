package com.example.interlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.interlink.MainActivity
import com.example.interlink.R
import com.example.interlink.client.ClientManager
import com.example.interlink.server.ServerManager
import com.example.interlink.audio.AudioHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@AndroidEntryPoint
class IntercomService : LifecycleService() {

    @Inject lateinit var serverManager: ServerManager
    @Inject lateinit var clientManager: ClientManager
    @Inject lateinit var audioHandler: AudioHandler

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _isMusicStreaming = MutableStateFlow(false)
    val isMusicStreaming = _isMusicStreaming.asStateFlow()

    private val binder = IntercomBinder()
    private var isHost = false

    inner class IntercomBinder : Binder() {
        fun getService(): IntercomService = this@IntercomService
    }

    fun isHostActive(): Boolean = isHost
    fun isClientActive(): Boolean = !isHost && clientManager.isConnected()
    fun getClientConnectionStatus() = clientManager.connectionStatus
    fun getPendingMusicRequests() = serverManager.pendingMusicRequests
    fun isMusicSharingApproved() = clientManager.isMusicSharingApproved

    fun approveMusicRequest(request: com.example.interlink.models.MusicRequest) = serverManager.approveMusicRequest(request)
    fun denyMusicRequest(request: com.example.interlink.models.MusicRequest) = serverManager.denyMusicRequest(request)
    
    fun requestMusicShare(username: String) = clientManager.requestMusicShare(username)
    fun stopMusicShare() = clientManager.stopMusicShare()

    fun startMusicSharing(uri: android.net.Uri) {
        _isMusicStreaming.value = true
        audioHandler.startMusicStreaming(uri) { data ->
            if (isHost) {
                serverManager.broadcastData(data)
            } else {
                clientManager.sendVoiceData(data)
            }
        }
    }

    fun stopMusicSharing() {
        audioHandler.stopMusicStreaming()
        _isMusicStreaming.value = false
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1, createNotification())
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Interlink:ServiceWakeLock").apply {
            acquire(10 * 60 * 1000L /*10 minutes*/)
        }

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Interlink:WifiLock").apply {
            acquire()
        }
        Timber.d("WakeLock and WifiLock acquired")
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        Timber.d("Locks released")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "interlink_service",
            "InterLink Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "interlink_service")
            .setContentTitle("InterLink is running")
            .setContentText("Tap to open app")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Need to make sure this exists
            .setContentIntent(pendingIntent)
            .build()
    }

    fun startAsHost() {
        if (isHost) return
        isHost = true
        serverManager.startServer()
        audioHandler.startPlayback()
    }

    fun startAsClient(ip: String? = null) {
        // If we are already connected to this IP, don't restart
        if (!isHost && clientManager.isConnected()) return

        isHost = false
        if (ip.isNullOrBlank()) {
            clientManager.discoverAndConnect()
        } else {
            clientManager.connectToManualIp(ip)
        }
        audioHandler.startPlayback()
    }

    fun startPtt() {
        audioHandler.startRecording { data ->
            if (isHost) {
                serverManager.broadcastData(data)
            } else {
                clientManager.sendVoiceData(data)
            }
        }
    }

    fun stopPtt() {
        audioHandler.stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        serverManager.stopServer()
        clientManager.disconnect()
        audioHandler.stopPlayback()
        audioHandler.release()
        Timber.d("Service destroyed")
    }
}
