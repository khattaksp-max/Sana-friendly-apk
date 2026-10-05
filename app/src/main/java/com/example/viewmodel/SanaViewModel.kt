package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureManager
import com.example.audio.AudioPlayerManager
import com.example.audio.PhoneActionController
import com.example.data.AssistantMode
import com.example.data.AvailableVoices
import com.example.data.ChatMessage
import com.example.data.GeminiVoice
import com.example.data.MessageSender
import com.example.data.SanaMemoryManager
import com.example.data.StoredPreference
import com.example.data.VoiceState
import com.example.network.GeminiVoiceResult
import com.example.network.GeminiVoiceService
import com.example.util.SanaLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class NavigateToTab(val tabIndex: Int) : UiEvent()
    object OpenAppSettings : UiEvent()
}

class SanaViewModel(application: Application) : AndroidViewModel(application) {

    private val COMPONENT = "SanaViewModel"
    private val prefs = application.getSharedPreferences("sana_app_prefs", Context.MODE_PRIVATE)

    private val audioPlayer = AudioPlayerManager(application)
    private val geminiService = GeminiVoiceService()
    private val phoneActionController = PhoneActionController(application)
    val memoryManager = SanaMemoryManager(application)

    // Real microphone PCM capture with VAD and echo cancellation
    private val audioCapture = AudioCaptureManager(application) {
        interruptSpeaking()
    }

    // State Machine
    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    // Continuous conversation loop flag
    private val _isContinuousMode = MutableStateFlow(false)
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    // Configuration
    private val _selectedVoice = MutableStateFlow<GeminiVoice>(AvailableVoices.Kore)
    val selectedVoice: StateFlow<GeminiVoice> = _selectedVoice.asStateFlow()

    private val _assistantMode = MutableStateFlow(AssistantMode.ASSISTANT)
    val assistantMode: StateFlow<AssistantMode> = _assistantMode.asStateFlow()

    private val _voiceSpeed = MutableStateFlow(1.0f)
    val voiceSpeed: StateFlow<Float> = _voiceSpeed.asStateFlow()

    private val _voiceVolume = MutableStateFlow(1.0f)
    val voiceVolume: StateFlow<Float> = _voiceVolume.asStateFlow()

    // Chat History
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Current Error
    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val _previewingVoiceId = MutableStateFlow<String?>(null)
    val previewingVoiceId: StateFlow<String?> = _previewingVoiceId.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    val isListening: StateFlow<Boolean> = audioCapture.isListening
    val recognizedSpeechText: StateFlow<String> = audioCapture.recognizedText
    val micSoundLevel: StateFlow<Float> = audioCapture.soundLevel
    val isPlayingAudio: StateFlow<Boolean> = audioPlayer.isPlaying
    val memories: StateFlow<List<StoredPreference>> = memoryManager.memories

    private var activeJob: Job? = null
    private var lastUserTurnText: String? = null
    private var lastUserAudioBytes: ByteArray? = null

    init {
        // Load custom API key if user saved one
        val savedApiKey = prefs.getString("custom_gemini_api_key", null)
        if (!savedApiKey.isNullOrBlank()) {
            geminiService.customApiKey = savedApiKey
        }

        // Audio focus loss / phone call interruptions
        audioPlayer.onAudioFocusLossListener = {
            SanaLogger.w(COMPONENT, "Audio focus lost. Stopping playback and conversation.")
            interruptSpeaking()
            if (_isContinuousMode.value) {
                _isContinuousMode.value = false
                _voiceState.value = VoiceState.IDLE
            }
        }

        // Voice Activity Detection listeners
        audioCapture.onSpeechStartedListener = {
            if (_voiceState.value == VoiceState.LISTENING) {
                SanaLogger.i(COMPONENT, "User is actively speaking")
            }
        }

        audioCapture.onSpeechFinishedListener = { wavBytes, transcript ->
            handleCapturedVoice(wavBytes, transcript)
        }

        audioCapture.onErrorListener = { errorMsg ->
            SanaLogger.e(COMPONENT, "Microphone capture error: $errorMsg")
            if (_isContinuousMode.value) {
                // If microphone timed out with no speech in continuous loop, restart listening after guard pause
                if (errorMsg.contains("timed out", ignoreCase = true) || errorMsg.contains("No speech", ignoreCase = true)) {
                    viewModelScope.launch {
                        delay(400)
                        if (_isContinuousMode.value && _voiceState.value != VoiceState.SPEAKING) {
                            _voiceState.value = VoiceState.LISTENING
                            audioCapture.startListening()
                        }
                    }
                } else {
                    _voiceState.value = VoiceState.ERROR
                    _lastErrorMessage.value = errorMsg
                }
            } else {
                _voiceState.value = VoiceState.ERROR
                _lastErrorMessage.value = errorMsg
            }
        }

        val welcome = ChatMessage(
            sender = MessageSender.SANA,
            text = "Hello! I'm SANA. Tap Start Conversation for hands-free voice chat, or type a message below.",
            voiceName = selectedVoice.value.name
        )
        _messages.value = listOf(welcome)
    }

    fun hasRecordAudioPermission(): Boolean {
        val app = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        SanaLogger.i(COMPONENT, "Microphone permission status: $granted")
        return granted
    }

    /**
     * ONE-CLICK START:
     * User taps Start Conversation ONCE. SANA continuously listens with AudioRecord PCM,
     * speaks real Gemini Native Audio, and automatically resumes listening!
     */
    fun startContinuousConversation(hasMicPermission: Boolean) {
        if (!hasMicPermission || !hasRecordAudioPermission()) {
            _voiceState.value = VoiceState.ERROR
            _lastErrorMessage.value = "Microphone permission is required for voice conversation."
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Microphone permission is required for voice conversation."))
            }
            return
        }

        interruptSpeaking()
        _isContinuousMode.value = true
        _lastErrorMessage.value = null
        _voiceState.value = VoiceState.LISTENING
        SanaLogger.i(COMPONENT, "Starting continuous conversation loop with AudioRecord")
        audioCapture.startListening()
    }

    /**
     * CLEARLY VISIBLE STOP BUTTON:
     * Immediately stops microphone, terminates continuous loop, stops audio playback, releases resources.
     */
    fun stopContinuousConversation() {
        SanaLogger.i(COMPONENT, "Stopping continuous conversation loop")
        _isContinuousMode.value = false
        activeJob?.cancel()
        audioCapture.stopListening()
        audioPlayer.stopPlayback()
        _previewingVoiceId.value = null
        _voiceState.value = VoiceState.IDLE
    }

    /**
     * Interruption / Barge-in: stops audio playback and cancels active speech jobs.
     */
    fun interruptSpeaking() {
        activeJob?.cancel()
        audioPlayer.stopPlayback()
        _previewingVoiceId.value = null
        if (_voiceState.value == VoiceState.SPEAKING) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    private fun handleCapturedVoice(wavBytes: ByteArray, transcript: String?) {
        lastUserAudioBytes = wavBytes
        lastUserTurnText = transcript

        val displayText = if (!transcript.isNullOrBlank()) transcript else "🎙️ [Voice Message]"
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = displayText
        )
        _messages.value = _messages.value + userMsg

        // Check for phone action intents if text was transcribed
        if (!transcript.isNullOrBlank()) {
            val actionResult = phoneActionController.evaluateAndExecute(transcript)
            if (actionResult != null) {
                processActionResponse(actionResult.spokenConfirmation)
                return
            }
        }

        executeGeminiTurn(userAudioBytes = wavBytes, userInputText = transcript)
    }

    fun sendTypedMessage(text: String) {
        if (text.isBlank()) return
        interruptSpeaking()

        lastUserTurnText = text
        lastUserAudioBytes = null
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = text
        )
        _messages.value = _messages.value + userMsg

        // Check for phone action intents
        val actionResult = phoneActionController.evaluateAndExecute(text)
        if (actionResult != null) {
            processActionResponse(actionResult.spokenConfirmation)
            return
        }

        executeGeminiTurn(userAudioBytes = null, userInputText = text)
    }

    private fun executeGeminiTurn(userAudioBytes: ByteArray?, userInputText: String?) {
        _voiceState.value = VoiceState.PROCESSING
        _lastErrorMessage.value = null

        activeJob = viewModelScope.launch {
            val history = _messages.value.map {
                (if (it.sender == MessageSender.USER) "user" else "model") to it.text
            }
            val memoryNotes = memoryManager.memories.value.map { "${it.key}: ${it.value}" }

            val result = geminiService.processVoiceTurn(
                userAudioBytes = userAudioBytes,
                userInputText = userInputText,
                mode = _assistantMode.value,
                voiceName = _selectedVoice.value.id,
                history = history,
                memoryNotes = memoryNotes
            )

            when (result) {
                is GeminiVoiceResult.Success -> {
                    val sanaMsg = ChatMessage(
                        sender = MessageSender.SANA,
                        text = result.text,
                        voiceName = _selectedVoice.value.name,
                        hasAudio = true
                    )
                    _messages.value = _messages.value + sanaMsg

                    // Play REAL Gemini Native Audio through phone speaker
                    // Only set SPEAKING state when playback actually starts!
                    audioPlayer.playGeminiNativeAudio(
                        base64Audio = result.audioBase64,
                        mimeType = result.audioMimeType,
                        onStarted = {
                            _voiceState.value = VoiceState.SPEAKING
                        },
                        onFinished = {
                            onSpeechPlaybackCompleted()
                        },
                        onError = { errMsg ->
                            SanaLogger.e(COMPONENT, "Playback error: $errMsg")
                            _voiceState.value = VoiceState.ERROR
                            _lastErrorMessage.value = "Audio output error: $errMsg"
                            viewModelScope.launch {
                                _uiEvents.emit(UiEvent.ShowSnackbar("Audio error: $errMsg"))
                            }
                        }
                    )
                }

                is GeminiVoiceResult.Error -> {
                    _voiceState.value = VoiceState.ERROR
                    _lastErrorMessage.value = result.message

                    val errMsg = ChatMessage(
                        sender = MessageSender.SANA,
                        text = result.message,
                        voiceName = _selectedVoice.value.name,
                        hasAudio = false
                    )
                    _messages.value = _messages.value + errMsg

                    viewModelScope.launch {
                        _uiEvents.emit(UiEvent.ShowSnackbar(result.message))
                    }
                }
            }
        }
    }

    private fun processActionResponse(confirmationText: String) {
        _voiceState.value = VoiceState.PROCESSING
        activeJob = viewModelScope.launch {
            val sanaMsg = ChatMessage(
                sender = MessageSender.SANA,
                text = confirmationText,
                voiceName = _selectedVoice.value.name,
                hasAudio = true,
                isActionConfirmation = true
            )
            _messages.value = _messages.value + sanaMsg

            val (base64Audio, mimeType) = geminiService.synthesizeNativeAudioDirect(
                text = confirmationText,
                voiceName = _selectedVoice.value.id,
                mode = _assistantMode.value
            )

            if (!base64Audio.isNullOrBlank()) {
                audioPlayer.playGeminiNativeAudio(
                    base64Audio = base64Audio,
                    mimeType = mimeType,
                    onStarted = {
                        _voiceState.value = VoiceState.SPEAKING
                    },
                    onFinished = {
                        onSpeechPlaybackCompleted()
                    },
                    onError = {
                        onSpeechPlaybackCompleted()
                    }
                )
            } else {
                onSpeechPlaybackCompleted()
            }
        }
    }

    /**
     * AUTOMATIC RETURN TO LISTENING:
     * When SANA finishes speaking, safely return to LISTENING if continuous mode is active.
     * Prevents self-listening by keeping mic strictly closed during playback and adding guard delay.
     */
    private fun onSpeechPlaybackCompleted() {
        if (_isContinuousMode.value) {
            viewModelScope.launch {
                // Guard delay to allow phone speaker acoustic reverberation to completely settle
                delay(350)
                if (_isContinuousMode.value) {
                    SanaLogger.i(COMPONENT, "Microphone restarted for next turn in continuous conversation")
                    _voiceState.value = VoiceState.LISTENING
                    audioCapture.startListening()
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }
        } else {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun retryLastTurn() {
        val audioBytes = lastUserAudioBytes
        val text = lastUserTurnText
        if (audioBytes != null || !text.isNullOrBlank()) {
            _lastErrorMessage.value = null
            executeGeminiTurn(audioBytes, text)
        } else if (_isContinuousMode.value) {
            _lastErrorMessage.value = null
            _voiceState.value = VoiceState.LISTENING
            audioCapture.startListening()
        }
    }

    fun selectVoice(voice: GeminiVoice) {
        interruptSpeaking()
        _selectedVoice.value = voice
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Switched voice to ${voice.name} (${voice.style})"))
        }
    }

    fun previewVoice(voice: GeminiVoice) {
        interruptSpeaking()
        _previewingVoiceId.value = voice.id

        activeJob = viewModelScope.launch {
            val (base64Audio, mimeType) = geminiService.synthesizeNativeAudioDirect(
                text = voice.sampleText,
                voiceName = voice.id,
                mode = _assistantMode.value
            )

            if (!base64Audio.isNullOrBlank()) {
                audioPlayer.playGeminiNativeAudio(
                    base64Audio = base64Audio,
                    mimeType = mimeType,
                    onStarted = {
                        _voiceState.value = VoiceState.SPEAKING
                    },
                    onFinished = {
                        _previewingVoiceId.value = null
                        _voiceState.value = VoiceState.IDLE
                    },
                    onError = {
                        _previewingVoiceId.value = null
                        _voiceState.value = VoiceState.IDLE
                    }
                )
            } else {
                _previewingVoiceId.value = null
                _voiceState.value = VoiceState.IDLE
                _uiEvents.emit(UiEvent.ShowSnackbar("Could not preview audio. Check Gemini API configuration."))
            }
        }
    }

    fun setAssistantMode(mode: AssistantMode) {
        _assistantMode.value = mode
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("SANA mode switched to ${mode.displayName}"))
        }
    }

    fun setVoiceSpeed(speed: Float) {
        _voiceSpeed.value = speed
        audioPlayer.speechSpeed = speed
    }

    fun setVoiceVolume(volume: Float) {
        _voiceVolume.value = volume
        audioPlayer.speechVolume = volume
    }

    fun saveCustomApiKey(key: String) {
        val trimmed = key.trim()
        geminiService.customApiKey = trimmed
        prefs.edit().putString("custom_gemini_api_key", trimmed).apply()
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("API key saved successfully."))
        }
    }

    fun clearConversation() {
        interruptSpeaking()
        _messages.value = emptyList()
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Conversation history cleared."))
        }
    }

    fun clearAllMemory() {
        memoryManager.clearAllMemories()
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("All stored preferences cleared."))
        }
    }

    fun isGeminiConfigured(): Boolean {
        return geminiService.isApiKeyConfigured()
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        audioCapture.destroy()
    }
}
