package com.example.interlink.client

import com.example.interlink.audio.AudioHandler
import com.example.interlink.network.NsdHelper
import com.example.interlink.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientManager @Inject constructor(
    private val nsdHelper: NsdHelper,
    private val audioHandler: AudioHandler
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var hostAddress: InetAddress? = null
    private var controlJob: Job? = null

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _isMusicSharingApproved = MutableStateFlow(false)
    val isMusicSharingApproved = _isMusicSharingApproved.asStateFlow()

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED, FAILED
    }

    fun isConnected(): Boolean = _connectionStatus.value == ConnectionStatus.CONNECTED

    fun discoverAndConnect() {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        
        // Timeout for discovery
        scope.launch {
            kotlinx.coroutines.delay(10000)
            if (_connectionStatus.value == ConnectionStatus.CONNECTING) {
                _connectionStatus.value = ConnectionStatus.FAILED
                nsdHelper.stopDiscovery()
            }
        }

        nsdHelper.discoverServices { serviceInfo ->
            hostAddress = serviceInfo.host
            connectToHost(serviceInfo.host, serviceInfo.port)
        }
    }

    fun connectToManualIp(ip: String) {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        scope.launch(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(ip)
                hostAddress = address
                connectToHost(address, Constants.TCP_PORT)
            } catch (e: Exception) {
                Timber.e(e, "Invalid IP address")
                _connectionStatus.value = ConnectionStatus.FAILED
            }
        }
    }

    private fun connectToHost(address: InetAddress, port: Int) {
        scope.launch {
            try {
                val socket = Socket(address, port)
                Timber.d("Connected to Host at ${address.hostAddress}")
                socket.close()
                _connectionStatus.value = ConnectionStatus.CONNECTED
                startUdpListener()
                startControlListener()
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to Host")
                _connectionStatus.value = ConnectionStatus.FAILED
            }
        }
    }

    private fun startControlListener() {
        controlJob?.cancel()
        controlJob = scope.launch {
            try {
                val serverSocket = ServerSocket(Constants.TCP_PORT)
                while (true) {
                    val socket = serverSocket.accept()
                    val reader = socket.getInputStream().bufferedReader()
                    val message = reader.readLine()
                    if (message == Constants.CMD_MUSIC_APPROVE) {
                        _isMusicSharingApproved.value = true
                    } else if (message == Constants.CMD_MUSIC_DENY) {
                        _isMusicSharingApproved.value = false
                    } else if (message == Constants.CMD_MUSIC_STOP) {
                        _isMusicSharingApproved.value = false
                    }
                    socket.close()
                }
            } catch (e: Exception) {
                Timber.e(e, "Control listener error")
            }
        }
    }

    fun requestMusicShare(username: String) {
        val host = hostAddress ?: return
        scope.launch {
            try {
                val socket = Socket(host, Constants.TCP_PORT)
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("${Constants.CMD_MUSIC_REQUEST}:$username\n")
                writer.flush()
                socket.close()
            } catch (e: Exception) {
                Timber.e(e, "Error requesting music share")
            }
        }
    }

    fun stopMusicShare() {
        _isMusicSharingApproved.value = false
        val host = hostAddress ?: return
        scope.launch {
            try {
                val socket = Socket(host, Constants.TCP_PORT)
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("${Constants.CMD_MUSIC_STOP}\n")
                writer.flush()
                socket.close()
            } catch (e: Exception) {
                Timber.e(e, "Error stopping music share")
            }
        }
    }

    private fun startUdpListener() {
        udpJob?.cancel()
        udpJob = scope.launch {
            try {
                // We bind to Constants.UDP_PORT to receive broadcasts from Host
                udpSocket = DatagramSocket(Constants.UDP_PORT).apply {
                    reuseAddress = true
                }
                val buffer = ByteArray(Constants.BUFFER_SIZE)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    audioHandler.playEncodedData(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                Timber.e(e, "UDP Listener error")
            }
        }
    }

    fun sendVoiceData(data: ByteArray) {
        val host = hostAddress ?: return
        try {
            // Reuse the receiver socket if it exists, otherwise use a temporary one
            val socket = udpSocket ?: DatagramSocket()
            val packet = DatagramPacket(data, data.size, host, Constants.UDP_PORT)
            socket.send(packet)
            // Do not close udpSocket! Only close if it was temporary.
            if (udpSocket == null) socket.close()
        } catch (e: Exception) {
            Timber.e(e, "Error sending UDP data")
        }
    }

    fun disconnect() {
        nsdHelper.stopDiscovery()
        udpJob?.cancel()
        controlJob?.cancel()
        udpSocket?.close()
        udpSocket = null
        hostAddress = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isMusicSharingApproved.value = false
    }
}
