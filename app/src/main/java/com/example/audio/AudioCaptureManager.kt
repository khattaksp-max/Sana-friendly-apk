package com.example.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.util.SanaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class AudioCaptureManager(
    private val context: Context,
    private val onBargeInRequested: () -> Unit = {}
) {
    private val COMPONENT = "AudioCaptureManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingJob: Job? = null

    // Parallel SpeechRecognizer for immediate local transcription
    private var speechRecognizer: SpeechRecognizer? = null
    private var latestSpeechText: String? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    var onSpeechStartedListener: (() -> Unit)? = null
    var onSpeechFinishedListener: ((wavAudioBytes: ByteArray, transcript: String?) -> Unit)? = null
    var onErrorListener: ((String) -> Unit)? = null

    init {
        mainHandler.post {
            initializeSpeechRecognizer()
        }
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            SanaLogger.w(COMPONENT, "SpeechRecognizer service not available; relying directly on AudioRecord PCM")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        onBargeInRequested()
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        SanaLogger.d(COMPONENT, "SpeechRecognizer info/error code: $error")
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            latestSpeechText = text
                            _recognizedText.value = text
                            SanaLogger.i(COMPONENT, "Speech transcript captured: '$text'")
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim()
                        if (!partial.isNullOrBlank()) {
                            _recognizedText.value = partial
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            SanaLogger.w(COMPONENT, "Error setting up parallel SpeechRecognizer: ${e.message}")
        }
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts recording microphone audio using AudioRecord with VAD (Voice Activity Detection).
     */
    fun startListening() {
        if (!hasRecordPermission()) {
            SanaLogger.e(COMPONENT, "RECORD_AUDIO permission is not granted")
            onErrorListener?.invoke("Microphone permission is required for voice conversation.")
            return
        }

        stopListening()
        onBargeInRequested()

        latestSpeechText = null
        _recognizedText.value = ""

        recordingJob = scope.launch {
            var minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                minBufferSize = SAMPLE_RATE * 2
            }
            val bufferSize = maxOf(minBufferSize * 2, 4096)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                val record = audioRecord ?: throw IllegalStateException("AudioRecord creation failed")

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord state not initialized ($bufferSize)")
                }

                // Enable Acoustic Echo Canceler and Noise Suppressor if supported
                try {
                    val sessionId = record.audioSessionId
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                            enabled = true
                        }
                        SanaLogger.i(COMPONENT, "AcousticEchoCanceler enabled")
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                            enabled = true
                        }
                        SanaLogger.i(COMPONENT, "NoiseSuppressor enabled")
                    }
                } catch (e: Exception) {
                    SanaLogger.w(COMPONENT, "Could not attach audio effects: ${e.message}")
                }

                record.startRecording()
                _isListening.value = true
                SanaLogger.i(COMPONENT, "Microphone AudioRecord capture started (16kHz PCM)")

                // Also trigger parallel SpeechRecognizer on main thread
                mainHandler.post {
                    try {
                        speechRecognizer?.cancel()
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                        }
                        speechRecognizer?.startListening(intent)
                    } catch (e: Exception) {
                        SanaLogger.w(COMPONENT, "SpeechRecognizer parallel start failed: ${e.message}")
                    }
                }

                val buffer = ShortArray(1024)
                val byteBuffer = ByteArray(2048)
                val accumulatedAudio = ByteArrayOutputStream()

                var isUserSpeaking = false
                var speechStartTimeMs = 0L
                var lastSpeechTimestampMs = 0L

                val SPEECH_RMS_THRESHOLD = 0.022f // Minimum energy for active speech
                val SILENCE_TIMEOUT_MS = 1400L // 1.4 seconds of silence after speaking to trigger send
                val MAX_RECORDING_DURATION_MS = 25000L // 25s max safety limit

                while (isActive && _isListening.value) {
                    val readSamples = record.read(buffer, 0, buffer.size)
                    if (readSamples <= 0) {
                        delay(20)
                        continue
                    }

                    // Convert short samples to bytes
                    for (i in 0 until readSamples) {
                        val sample = buffer[i]
                        byteBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                    }
                    val bytesRead = readSamples * 2

                    // Calculate RMS
                    var sum = 0.0
                    for (i in 0 until readSamples) {
                        val s = buffer[i]
                        sum += s * s
                    }
                    val rms = (Math.sqrt(sum / readSamples) / 32768.0).toFloat()
                    _soundLevel.value = (rms * 4f).coerceIn(0f, 1f)

                    val now = System.currentTimeMillis()

                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        if (!isUserSpeaking) {
                            isUserSpeaking = true
                            speechStartTimeMs = now
                            SanaLogger.i(COMPONENT, "Voice activity detected (RMS: $rms)")
                            onSpeechStartedListener?.invoke()
                        }
                        lastSpeechTimestampMs = now
                        accumulatedAudio.write(byteBuffer, 0, bytesRead)
                    } else if (isUserSpeaking) {
                        // User was speaking, now in silence
                        accumulatedAudio.write(byteBuffer, 0, bytesRead)

                        val silenceDuration = now - lastSpeechTimestampMs
                        val totalSpeechDuration = now - speechStartTimeMs

                        // End-of-speech detected: silence timeout exceeded and minimum speech length met
                        if (silenceDuration >= SILENCE_TIMEOUT_MS && totalSpeechDuration >= 400L) {
                            SanaLogger.i(COMPONENT, "End of speech detected (duration: ${totalSpeechDuration}ms, silence: ${silenceDuration}ms)")
                            break
                        }

                        if (totalSpeechDuration >= MAX_RECORDING_DURATION_MS) {
                            SanaLogger.i(COMPONENT, "Max recording duration reached")
                            break
                        }
                    } else {
                        // Keep a rolling pre-speech buffer so initial consonants aren't clipped
                        if (accumulatedAudio.size() > 16000) { // ~500ms
                            accumulatedAudio.reset()
                        }
                        accumulatedAudio.write(byteBuffer, 0, bytesRead)
                    }
                }

                // If speech was captured, build WAV and dispatch
                val rawPcm = accumulatedAudio.toByteArray()
                if (isUserSpeaking && rawPcm.size > 3200) { // at least 100ms of audio
                    val wavBytes = buildWavFile(rawPcm, SAMPLE_RATE, 1, 16)
                    SanaLogger.i(COMPONENT, "Generated WAV payload: ${wavBytes.size} bytes. Dispatching to Gemini...")

                    // Give SpeechRecognizer a small 200ms window to finalize transcript if available
                    delay(200)
                    val transcript = latestSpeechText
                    stopListening()
                    onSpeechFinishedListener?.invoke(wavBytes, transcript)
                } else {
                    stopListening()
                }

            } catch (e: Exception) {
                SanaLogger.e(COMPONENT, "AudioRecord capture exception", e)
                stopListening()
                onErrorListener?.invoke("Audio capture error: ${e.message}")
            }
        }
    }

    /**
     * Stops microphone capture and cleans up AudioRecord and effects.
     */
    fun stopListening() {
        _isListening.value = false
        _soundLevel.value = 0f

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            SanaLogger.w(COMPONENT, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            SanaLogger.w(COMPONENT, "Error releasing audio effects: ${e.message}")
        }

        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Error cancelling SpeechRecognizer: ${e.message}")
            }
        }

        SanaLogger.i(COMPONENT, "AudioCaptureManager stopped and released")
    }

    private fun buildWavFile(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
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
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    fun destroy() {
        stopListening()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Error destroying speechRecognizer: ${e.message}")
            }
        }
    }
}
