package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayerManager(private val context: Context) {

    private val TAG = "AudioPlayerManager"

    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    var speechSpeed: Float = 1.0f
    var speechVolume: Float = 1.0f

    private var onPlaybackFinishedListener: (() -> Unit)? = null

    /**
     * Stops audio playback and releases audio focus immediately.
     */
    fun stopPlayback() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer", e)
        }

        abandonAudioFocus()
        _isPlaying.value = false
        _audioAmplitude.value = 0f
    }

    /**
     * Plays REAL Gemini Native Audio stream received from the Gemini API.
     */
    fun playGeminiNativeAudio(
        base64Audio: String,
        mimeType: String? = null,
        onFinished: () -> Unit = {}
    ): Boolean {
        stopPlayback()
        this.onPlaybackFinishedListener = onFinished

        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            if (audioBytes.isEmpty()) {
                onFinished()
                return false
            }

            requestAudioFocus()

            val tempFile = File(context.cacheDir, "sana_native_audio_${System.currentTimeMillis()}.wav")

            // Check for RIFF header
            val isRiff = audioBytes.size > 4 &&
                    audioBytes[0] == 'R'.code.toByte() &&
                    audioBytes[1] == 'I'.code.toByte() &&
                    audioBytes[2] == 'F'.code.toByte() &&
                    audioBytes[3] == 'F'.code.toByte()

            FileOutputStream(tempFile).use { fos ->
                if (isRiff) {
                    fos.write(audioBytes)
                } else {
                    val sampleRate = if (mimeType?.contains("rate=") == true) {
                        mimeType.substringAfter("rate=").substringBefore(";").toIntOrNull() ?: 24000
                    } else {
                        24000
                    }
                    val header = buildWavHeader(audioBytes.size, sampleRate, 1, 16)
                    fos.write(header)
                    fos.write(audioBytes)
                }
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                prepare()

                setVolume(speechVolume, speechVolume)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        playbackParams = PlaybackParams().apply {
                            speed = speechSpeed.coerceIn(0.5f, 2.0f)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply speed", e)
                    }
                }

                setOnCompletionListener {
                    _isPlaying.value = false
                    _audioAmplitude.value = 0f
                    abandonAudioFocus()
                    tempFile.delete()
                    onPlaybackFinishedListener?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    _isPlaying.value = false
                    _audioAmplitude.value = 0f
                    abandonAudioFocus()
                    tempFile.delete()
                    onPlaybackFinishedListener?.invoke()
                    true
                }

                start()
            }

            _isPlaying.value = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing native audio", e)
            _isPlaying.value = false
            abandonAudioFocus()
            onFinished()
            return false
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
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
