package com.example.interlink.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.Uri
import com.example.interlink.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.buney.kopus.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioHandler @Inject constructor(@ApplicationContext private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sampleRate = 48000 
    private val frameSize = 960 // 20ms at 48kHz
    private val voiceBitrate = 16000
    private val musicBitrate = 96000

    private var voiceEncoder: OpusEncoder? = null
    private var musicEncoder: OpusEncoder? = null
    private var voiceDecoder: OpusDecoder? = null
    private var musicDecoder: OpusDecoder? = null

    private var audioRecord: AudioRecord? = null
    private var voiceTrack: AudioTrack? = null
    private var musicTrack: AudioTrack? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null
    private var musicJob: Job? = null
    private var playbackJob: Job? = null

    private val musicPacketChannel = Channel<Packet>(Channel.UNLIMITED)
    private val musicJitterBuffer = PriorityQueue<Packet>(compareBy { it.seq })
    
    class Packet(val type: Byte, val seq: Int, val data: ByteArray)

    @SuppressLint("MissingPermission")
    fun startRecording(onDataEncoded: (ByteArray) -> Unit) {
        stopRecording()
        if (voiceEncoder == null) {
            voiceEncoder = OpusEncoder(sampleRate, 1, OpusApplication.Voip).apply { 
                ctl(OPUS_SET_BITRATE_REQUEST, voiceBitrate) 
            }
        }
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = minBufSize.coerceAtLeast(frameSize * 4)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord failed to initialize")
            return
        }
        
        audioRecord?.startRecording()
        var seq = 0
        recordingJob = scope.launch {
            val pcm = ShortArray(frameSize)
            val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive) {
                val read = audioRecord?.read(pcm, 0, frameSize) ?: -1
                if (read == frameSize) {
                    val encoded = voiceEncoder?.encode(pcm)
                    if (encoded != null) {
                        header.clear()
                        header.put(0.toByte()) // Voice
                        header.putInt(seq++)
                        onDataEncoded(header.array() + encoded)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }

    fun startMusicStreaming(uri: Uri, onDataEncoded: (ByteArray) -> Unit) {
        stopMusicStreaming()
        if (musicEncoder == null) {
            musicEncoder = OpusEncoder(sampleRate, 1, OpusApplication.Audio).apply { 
                ctl(OPUS_SET_BITRATE_REQUEST, musicBitrate) 
            }
        }
        
        musicJob = scope.launch {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                
                val trackIdx = (0 until extractor.trackCount).firstOrNull { 
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true 
                } ?: return@launch
                
                val inputFormat = extractor.getTrackFormat(trackIdx)
                extractor.selectTrack(trackIdx)
                
                val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                
                decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                
                val info = MediaCodec.BufferInfo()
                var seq = 0
                val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                
                // Buffer to accumulate exactly 20ms of mono 48kHz PCM
                val pcmBuffer = ShortArray(frameSize)
                var pcmBufferPos = 0
                
                while (isActive) {
                    val inIdx = decoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inputBuf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    
                    var outIdx = decoder.dequeueOutputBuffer(info, 10000)
                    while (outIdx >= 0 && isActive) {
                        val outBuf = decoder.getOutputBuffer(outIdx)!!
                        val pcmCount = info.size / 2
                        val rawPcm = ShortArray(pcmCount)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(rawPcm)
                        decoder.releaseOutputBuffer(outIdx, false)

                        // 1. Downmix to Mono if Stereo
                        val monoPcm = if (channelCount == 2) {
                            ShortArray(pcmCount / 2) { i ->
                                ((rawPcm[i * 2].toInt() + rawPcm[i * 2 + 1].toInt()) / 2).toShort()
                            }
                        } else {
                            rawPcm
                        }

                        // 2. Accumulate and Send
                        var tempPos = 0
                        while (tempPos < monoPcm.size) {
                            val copyLen = minOf(monoPcm.size - tempPos, frameSize - pcmBufferPos)
                            System.arraycopy(monoPcm, tempPos, pcmBuffer, pcmBufferPos, copyLen)
                            pcmBufferPos += copyLen
                            tempPos += copyLen

                            if (pcmBufferPos == frameSize) {
                                // Play locally
                                if (musicTrack == null) startPlayback()
                                musicTrack?.write(pcmBuffer, 0, frameSize)
                                
                                // Encode and Broadcast
                                val encoded = musicEncoder?.encode(pcmBuffer)
                                if (encoded != null) {
                                    header.clear()
                                    header.put(1.toByte())
                                    header.putInt(seq++)
                                    onDataEncoded(header.array() + encoded)
                                }
                                pcmBufferPos = 0
                                // Blocking musicTrack.write paces the loop naturally to ~20ms
                            }
                        }
                        outIdx = decoder.dequeueOutputBuffer(info, 0)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                Timber.e(e, "Music sharing failure")
            } finally {
                decoder?.stop(); decoder?.release()
                extractor?.release()
                stopMusicStreaming()
            }
        }
    }

    fun startPlayback() {
        if (voiceTrack != null && musicTrack != null) return
        
        if (voiceDecoder == null) voiceDecoder = OpusDecoder(sampleRate, 1)
        if (musicDecoder == null) musicDecoder = OpusDecoder(sampleRate, 1)
        
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        
        val bufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(frameSize * 8)

        if (voiceTrack == null) {
            voiceTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            voiceTrack?.play()
        }
        
        if (musicTrack == null) {
            musicTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            musicTrack?.play()
        }
            
        startJitterBuffer()
    }

    private fun startJitterBuffer() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                val p = musicPacketChannel.receive()
                synchronized(musicJitterBuffer) { musicJitterBuffer.add(p) }
                
                // Jitter buffer: wait until we have a 100ms cushion
                if (musicJitterBuffer.size > 5) { 
                    val packet = synchronized(musicJitterBuffer) { musicJitterBuffer.poll() } ?: continue
                    val decoded = musicDecoder?.decode(packet.data, frameSize) ?: continue
                    musicTrack?.write(decoded, 0, frameSize)
                }
            }
        }
    }

    private var lastVoice = 0L
    fun playEncodedData(data: ByteArray) {
        if (data.size < 5) return
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get()
        val seq = buffer.getInt()
        val payload = data.copyOfRange(5, data.size)
        
        if (voiceTrack == null || musicTrack == null) startPlayback()
        
        if (type == 0.toByte()) { // Voice
            lastVoice = System.currentTimeMillis()
            musicTrack?.setVolume(0.2f) // Duck music
            val dec = voiceDecoder?.decode(payload, frameSize) ?: return
            voiceTrack?.write(dec, 0, frameSize)
            
            // Restore music volume after 1s of silence
            scope.launch { 
                delay(1000)
                if (System.currentTimeMillis() - lastVoice >= 1000) {
                    musicTrack?.setVolume(1.0f)
                }
            }
        } else if (type == 1.toByte()) {
            musicPacketChannel.trySend(Packet(type, seq, payload))
        }
    }

    fun stopMusicStreaming() { 
        musicJob?.cancel()
        musicJob = null 
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            voiceTrack?.stop(); voiceTrack?.release()
            musicTrack?.stop(); musicTrack?.release()
        } catch (e: Exception) {}
        voiceTrack = null
        musicTrack = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun release() {
        stopRecording()
        stopMusicStreaming()
        stopPlayback()
        voiceEncoder?.close()
        musicEncoder?.close()
        voiceDecoder?.close()
        musicDecoder?.close()
    }
}
