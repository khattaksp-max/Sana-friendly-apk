package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import com.example.util.SanaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayerManager(private val context: Context) {

    private val COMPONENT = "AudioPlayerManager"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    var speechSpeed: Float = 1.0f
    var speechVolume: Float = 1.0f

    var onAudioFocusLossListener: (() -> Unit)? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                SanaLogger.w(COMPONENT, "Audio focus lost ($focusChange). Stopping playback.")
                stopPlayback()
                onAudioFocusLossListener?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    audioTrack?.setVolume(0.2f * speechVolume)
                    mediaPlayer?.setVolume(0.2f * speechVolume, 0.2f * speechVolume)
                } catch (e: Exception) {
                    SanaLogger.w(COMPONENT, "Error ducking volume: ${e.message}")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    audioTrack?.setVolume(speechVolume)
                    mediaPlayer?.setVolume(speechVolume, speechVolume)
                } catch (e: Exception) {
                    SanaLogger.w(COMPONENT, "Error restoring volume: ${e.message}")
                }
            }
        }
    }

    /**
     * Immediately stops audio playback, releases AudioTrack/MediaPlayer, and abandons audio focus.
     */
    fun stopPlayback() {
        playJob?.cancel()
        playJob = null

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            SanaLogger.w(COMPONENT, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null

        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            SanaLogger.w(COMPONENT, "Error releasing MediaPlayer: ${e.message}")
        }
        mediaPlayer = null

        abandonAudioFocus()
        _isPlaying.value = false
        _audioAmplitude.value = 0f
    }

    /**
     * Plays REAL Gemini Native Audio stream.
     * Decodes Base64, verifies data, handles audio focus, and outputs through the phone speaker.
     */
    fun playGeminiNativeAudio(
        base64Audio: String,
        mimeType: String? = null,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stopPlayback()

        if (base64Audio.isBlank()) {
            SanaLogger.e(COMPONENT, "playGeminiNativeAudio failed: audio string is blank")
            onError("Received empty audio response from Gemini")
            return
        }

        val audioBytes: ByteArray
        try {
            audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            if (audioBytes.isEmpty()) {
                SanaLogger.e(COMPONENT, "playGeminiNativeAudio failed: decoded byte array is empty")
                onError("Decoded audio data is empty")
                return
            }
        } catch (e: Exception) {
            SanaLogger.e(COMPONENT, "Base64 decode failed", e)
            onError("Audio decoding error: ${e.message}")
            return
        }

        SanaLogger.i(COMPONENT, "Audio buffer received: ${audioBytes.size} bytes (mimeType: $mimeType)")

        val focusGranted = requestAudioFocus()
        if (!focusGranted) {
            SanaLogger.w(COMPONENT, "Could not acquire audio focus. Proceeding with playback anyway.")
        }

        // Determine if audio is raw PCM or WAV with RIFF header
        val isRiff = audioBytes.size > 44 &&
                audioBytes[0] == 'R'.code.toByte() &&
                audioBytes[1] == 'I'.code.toByte() &&
                audioBytes[2] == 'F'.code.toByte() &&
                audioBytes[3] == 'F'.code.toByte()

        val pcmPayload: ByteArray
        val sampleRate: Int
        val channelConfig: Int

        if (isRiff) {
            // Read channels from WAV header (bytes 22-23)
            val channelsBuffer = ByteBuffer.wrap(audioBytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN)
            val channels = channelsBuffer.short.toInt()
            channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

            // Read sample rate from WAV header (bytes 24-27)
            val rateBuffer = ByteBuffer.wrap(audioBytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN)
            val detectedRate = rateBuffer.int
            sampleRate = if (detectedRate in 8000..48000) detectedRate else 24000

            // Locate 'data' chunk
            val dataOffset = findWavDataOffset(audioBytes)
            pcmPayload = if (dataOffset in 12 until audioBytes.size) {
                audioBytes.copyOfRange(dataOffset, audioBytes.size)
            } else {
                audioBytes.copyOfRange(44, audioBytes.size)
            }
            SanaLogger.i(COMPONENT, "Parsed WAV header: rate=$sampleRate, channels=$channels, payloadSize=${pcmPayload.size}")
        } else {
            // Raw PCM from Gemini Native Audio (default: 24kHz 16-bit Mono)
            sampleRate = if (mimeType?.contains("rate=") == true) {
                mimeType.substringAfter("rate=").substringBefore(";").toIntOrNull() ?: 24000
            } else {
                24000
            }
            channelConfig = AudioFormat.CHANNEL_OUT_MONO
            pcmPayload = audioBytes
            SanaLogger.i(COMPONENT, "Raw PCM payload: rate=$sampleRate, size=${pcmPayload.size}")
        }

        // Play via high-performance AudioTrack through the phone speaker (USAGE_MEDIA)
        playJob = scope.launch {
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                // Use a safe streaming ring buffer size (at least 2x minBuffer and at least 8KB)
                val ringBufferSize = maxOf(minBufferSize * 2, 8192)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(ringBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.setVolume(speechVolume.coerceIn(0.1f, 1.0f))
                track.play()

                _isPlaying.value = true
                SanaLogger.i(COMPONENT, "AudioTrack playback started on phone speaker ($sampleRate Hz)")
                onStarted()

                val bytesPerFrame = if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) 4 else 2
                val totalFrames = pcmPayload.size / bytesPerFrame

                // Stream PCM payload in chunks
                var offset = 0
                val chunkSize = 4096
                while (offset < pcmPayload.size && _isPlaying.value) {
                    val bytesToWrite = minOf(chunkSize, pcmPayload.size - offset)
                    val written = track.write(pcmPayload, offset, bytesToWrite)
                    if (written < 0) {
                        SanaLogger.w(COMPONENT, "AudioTrack write returned error: $written")
                        break
                    }
                    offset += written

                    // Update amplitude for waveform animation
                    val amp = calculateRms(pcmPayload, offset - written, written)
                    _audioAmplitude.value = amp
                }

                // Wait until all buffered frames have physically finished playing out of the speaker
                while (_isPlaying.value && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val head = track.playbackHeadPosition
                    if (head >= totalFrames - (sampleRate * 0.05)) { // within 50ms of end
                        break
                    }
                    delay(40)
                }

                delay(100) // Brief tail settle
                SanaLogger.i(COMPONENT, "AudioTrack playback completed. Releasing resources.")
                stopPlayback()
                onFinished()

            } catch (e: Exception) {
                SanaLogger.e(COMPONENT, "AudioTrack playback exception", e)
                // Fallback to MediaPlayer
                fallbackPlayWithMediaPlayer(audioBytes, sampleRate, onStarted, onFinished, onError)
            }
        }
    }

    private fun fallbackPlayWithMediaPlayer(
        audioBytes: ByteArray,
        sampleRate: Int,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val tempFile = File(context.cacheDir, "sana_fallback_${System.currentTimeMillis()}.wav")
            val isRiff = audioBytes.size > 44 &&
                    audioBytes[0] == 'R'.code.toByte() &&
                    audioBytes[1] == 'I'.code.toByte() &&
                    audioBytes[2] == 'F'.code.toByte() &&
                    audioBytes[3] == 'F'.code.toByte()

            FileOutputStream(tempFile).use { fos ->
                if (isRiff) {
                    fos.write(audioBytes)
                } else {
                    val header = buildWavHeader(audioBytes.size, sampleRate, 1, 16)
                    fos.write(header)
                    fos.write(audioBytes)
                }
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                prepare()
                setVolume(speechVolume, speechVolume)

                setOnCompletionListener {
                    SanaLogger.i(COMPONENT, "MediaPlayer fallback playback completed.")
                    _isPlaying.value = false
                    _audioAmplitude.value = 0f
                    abandonAudioFocus()
                    tempFile.delete()
                    onFinished()
                }

                setOnErrorListener { _, what, extra ->
                    SanaLogger.e(COMPONENT, "MediaPlayer error what=$what extra=$extra")
                    _isPlaying.value = false
                    _audioAmplitude.value = 0f
                    abandonAudioFocus()
                    tempFile.delete()
                    onError("MediaPlayer error code $what")
                    true
                }

                start()
            }

            _isPlaying.value = true
            SanaLogger.i(COMPONENT, "Playback started via MediaPlayer fallback.")
            onStarted()

        } catch (e: Exception) {
            SanaLogger.e(COMPONENT, "MediaPlayer fallback failed", e)
            _isPlaying.value = false
            abandonAudioFocus()
            onError("Audio output error: ${e.message}")
        }
    }

    private fun findWavDataOffset(bytes: ByteArray): Int {
        for (i in 12 until bytes.size - 4) {
            if (bytes[i] == 'd'.code.toByte() &&
                bytes[i + 1] == 'a'.code.toByte() &&
                bytes[i + 2] == 't'.code.toByte() &&
                bytes[i + 3] == 'a'.code.toByte()
            ) {
                return i + 8 // skip 'data' (4 bytes) + data length (4 bytes)
            }
        }
        return 44
    }

    private fun calculateRms(bytes: ByteArray, offset: Int, length: Int): Float {
        var sum = 0.0
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f

        val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = buffer.short
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / sampleCount)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            focusRequest?.let {
                val res = audioManager.requestAudioFocus(it)
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } ?: false
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun buildWavHeader(pcmDataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataSize)

        return header.array()
    }

    fun release() {
        stopPlayback()
    }
}
