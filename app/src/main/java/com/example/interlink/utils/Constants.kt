package com.example.interlink.utils

object Constants {
    const val TCP_PORT = 8080
    const val UDP_PORT = 8081
    const val NSD_SERVICE_TYPE = "_interlink._tcp"
    const val NSD_SERVICE_NAME = "InterLinkHost"
    const val BUFFER_SIZE = 2048
    const val SAMPLE_RATE = 48000

    // Control Commands
    const val CMD_MUSIC_REQUEST = "REQ_MUSIC"
    const val CMD_MUSIC_APPROVE = "APP_MUSIC"
    const val CMD_MUSIC_DENY = "DENY_MUSIC"
    const val CMD_MUSIC_STOP = "STOP_MUSIC"
    const val CMD_MUSIC_BROADCAST_START = "START_MUSIC_MSG"
}
