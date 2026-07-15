package com.example.interlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import timber.log.Timber

@AndroidEntryPoint
class IntercomService : LifecycleService() {

    @Inject lateinit var serverManager: ServerManager
    @Inject lateinit var clientManager: ClientManager
    @Inject lateinit var audioHandler: AudioHandler

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val binder = IntercomBinder()
    private var isHost = false

    inner class IntercomBinder : Binder() {
        fun getService(): IntercomService = this@IntercomService
    }

    fun isHostActive(): Boolean = isHost
    fun isClientActive(): Boolean = !isHost && clientManager.isConnected()
    fun getClientConnectionStatus() = clientManager.connectionStatus

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        startForeground(1, createNotification())
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Interlink:ServiceWakeLock").apply {
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "interlink_service",
                "InterLink Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
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
