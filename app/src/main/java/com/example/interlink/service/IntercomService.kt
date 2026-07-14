package com.example.interlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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

    private val binder = IntercomBinder()
    private var isHost = false

    inner class IntercomBinder : Binder() {
        fun getService(): IntercomService = this@IntercomService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
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
        isHost = true
        serverManager.startServer()
        audioHandler.startPlayback()
    }

    fun startAsClient(ip: String? = null) {
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
        serverManager.stopServer()
        clientManager.disconnect()
        audioHandler.stopPlayback()
        audioHandler.release()
        Timber.d("Service destroyed")
    }
}
