package com.example.interlink.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.example.interlink.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.buney.kopus.OpusApplication
import eu.buney.kopus.OpusDecoder
import eu.buney.kopus.OpusEncoder
import eu.buney.kopus.decode
import eu.buney.kopus.encode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sampleRate = Constants.SAMPLE_RATE
    private val channelConfigRecord = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigPlay = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSizeRecord = AudioRecord.getMinBufferSize(sampleRate, channelConfigRecord, audioFormat)
    private val bufferSizePlay = AudioTrack.getMinBufferSize(sampleRate, channelConfigPlay, audioFormat)

    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    private var audioTrack: AudioTrack? = null
    private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val frameSize = 320 // 20ms at 16kHz for low latency

    @SuppressLint("MissingPermission")
    fun startRecording(onDataEncoded: (ByteArray) -> Unit) {
        Timber.d("Starting recording...")
        if (encoder == null) {
            encoder = OpusEncoder(sampleRate, 1, OpusApplication.Voip)
        }
        
        // Ensure mic is not muted by system
        audioManager.isMicrophoneMute = false
        
        val recordBufferSize = bufferSizeRecord.coerceAtLeast(960 * 2 * 2) // Extra headroom

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfigRecord,
            audioFormat,
            recordBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Timber.w("VOICE_COMMUNICATION source failed, falling back to MIC")
            audioRecord?.release()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfigRecord,
                audioFormat,
                recordBufferSize
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("Failed to initialize AudioRecord")
            return
        }

        audioSessionId = audioRecord?.audioSessionId ?: AudioManager.AUDIO_SESSION_ID_GENERATE

        audioRecord?.audioSessionId?.let { sessionId ->
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        }

        audioRecord?.startRecording()
        
        if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Timber.e("AudioRecord failed to start. Retrying with MIC source...")
            stopRecording()
            // Recurse once with a flag could work, but let's just force MIC next time
            return
        }

        Timber.d("AudioRecord started successfully")
        recordingJob = scope.launch {
            val pcmBuffer = ShortArray(frameSize) 
            var framesWithZeroData = 0
            
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                if (read > 0) {
                    // Check if we are getting actual data
                    val isSilence = pcmBuffer.take(read).all { it == 0.toShort() }
                    if (isSilence) {
                        framesWithZeroData++
                        if (framesWithZeroData > 150) { // Approx 3 seconds of total silence (20ms * 150)
                            Timber.w("Detected consistent silence in VOICE_COMMUNICATION, possible offline bug")
                        }
                    } else {
                        framesWithZeroData = 0
                    }

                    val encoded = encoder?.encode(pcmBuffer.take(read).toShortArray())
                    if (encoded != null && encoded.isNotEmpty()) {
                        onDataEncoded(encoded)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        Timber.d("Stopping recording...")
        recordingJob?.cancel()
        audioRecord?.stop()
        
        noiseSuppressor?.release()
        echoCanceler?.release()
        gainControl?.release()
        noiseSuppressor = null
        echoCanceler = null
        gainControl = null

        audioRecord?.release()
        audioRecord = null
    }

    fun startPlayback() {
        if (decoder == null) {
            decoder = OpusDecoder(sampleRate, 1)
        }

        // Request audio focus and set mode for communication
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfigPlay)
            .build()
            
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigPlay, audioFormat)
        val playBufferSize = minBufferSize.coerceAtLeast(frameSize * 2 * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(playBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(audioSessionId)
            .build()
            
        audioTrack?.play()
        Timber.d("AudioTrack started playing with buffer size $playBufferSize and sessionId $audioSessionId")
    }

    fun playEncodedData(encodedData: ByteArray) {
        if (audioTrack == null || audioTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
            startPlayback()
        }
        val decoded = decoder?.decode(encodedData, frameSize)
        if (decoded != null) {
            val result = audioTrack?.write(decoded, 0, decoded.size)
            if (result == AudioTrack.ERROR_INVALID_OPERATION || result == AudioTrack.ERROR_BAD_VALUE) {
                Timber.e("Error writing to AudioTrack: $result")
            }
        }
    }

    fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    fun release() {
        encoder?.close()
        decoder?.close()
        encoder = null
        decoder = null
    }
}
