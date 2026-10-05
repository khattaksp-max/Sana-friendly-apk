package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.util.SanaLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceInputManager(
    private val context: Context,
    private val onBargeInRequested: () -> Unit = {}
) {
    private val COMPONENT = "VoiceInputManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isSessionActive = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    var onSpeechResultListener: ((String) -> Unit)? = null
    var onEndOfSpeechListener: (() -> Unit)? = null
    var onErrorListener: ((String, Boolean) -> Unit)? = null // errorMsg, isFatal

    init {
        mainHandler.post {
            initializeRecognizerOnMain()
        }
    }

    private fun initializeRecognizerOnMain() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            SanaLogger.e(COMPONENT, "Speech recognition hardware/service is NOT available on this device")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            SanaLogger.i(COMPONENT, "Microphone speech recognizer initialized successfully")
        } catch (e: Exception) {
            SanaLogger.e(COMPONENT, "Error creating SpeechRecognizer", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                isSessionActive = true
                SanaLogger.i(COMPONENT, "Voice session started. Listening for speech...")
            }

            override fun onBeginningOfSpeech() {
                SanaLogger.i(COMPONENT, "Speech detected from user")
                onBargeInRequested()
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
                _soundLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                SanaLogger.i(COMPONENT, "End of speech detected. Finalizing audio transcript...")
                _isListening.value = false
                _soundLevel.value = 0f
                isSessionActive = false
                onEndOfSpeechListener?.invoke()
            }

            override fun onError(error: Int) {
                _isListening.value = false
                _soundLevel.value = 0f
                isSessionActive = false

                val isSilence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                val isBusy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT

                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording hardware error"
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error from voice service"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timed out"
                    else -> "Speech recognition error ($error)"
                }

                SanaLogger.w(COMPONENT, "Recognizer error ($error): $errorMsg (isSilence=$isSilence, isBusy=$isBusy)")

                if (isBusy) {
                    // Re-instantiate recognizer cleanly if native service enters busy state
                    mainHandler.post {
                        initializeRecognizerOnMain()
                    }
                }

                onErrorListener?.invoke(errorMsg, !isSilence)
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                _soundLevel.value = 0f
                isSessionActive = false

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim() ?: ""

                if (recognized.isNotBlank()) {
                    SanaLogger.i(COMPONENT, "Speech recognized (${recognized.length} chars). Sending to Gemini...")
                    _recognizedText.value = recognized
                    onSpeechResultListener?.invoke(recognized)
                } else {
                    SanaLogger.w(COMPONENT, "Empty speech results received")
                    onErrorListener?.invoke("No speech detected", false)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim() ?: ""
                if (partial.isNotBlank()) {
                    _recognizedText.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Starts listening. Guaranteed to execute safely on the Main Looper.
     */
    fun startListening() {
        mainHandler.post {
            onBargeInRequested()

            if (speechRecognizer == null) {
                initializeRecognizerOnMain()
            }

            try {
                // Cancel any pending session before starting new one
                speechRecognizer?.cancel()
                _recognizedText.value = ""

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("en-US", "ur-PK"))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                    // Generous silence thresholds so user is not cut off prematurely
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                }

                speechRecognizer?.startListening(intent)
                _isListening.value = true
                isSessionActive = true
            } catch (e: Exception) {
                SanaLogger.e(COMPONENT, "Failed starting speech recognizer", e)
                _isListening.value = false
                isSessionActive = false
                onErrorListener?.invoke("Could not start microphone: ${e.message}", true)
            }
        }
    }

    /**
     * Stops listening and halts current session.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                if (isSessionActive) {
                    speechRecognizer?.stopListening()
                }
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Error stopping recognizer: ${e.message}")
            }
            _isListening.value = false
            _soundLevel.value = 0f
            isSessionActive = false
        }
    }

    /**
     * Cancels active recognition without triggering results.
     */
    fun cancel() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Error cancelling recognizer: ${e.message}")
            }
            _isListening.value = false
            _soundLevel.value = 0f
            isSessionActive = false
        }
    }

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Error destroying recognizer: ${e.message}")
            }
            _isListening.value = false
            _soundLevel.value = 0f
            isSessionActive = false
        }
    }
}
