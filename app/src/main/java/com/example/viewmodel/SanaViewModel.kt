package com.example.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerManager
import com.example.audio.PhoneActionController
import com.example.audio.VoiceInputManager
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
}

class SanaViewModel(application: Application) : AndroidViewModel(application) {

    private val audioPlayer = AudioPlayerManager(application)
    private val geminiService = GeminiVoiceService()
    private val phoneActionController = PhoneActionController(application)
    val memoryManager = SanaMemoryManager(application)

    private val voiceInput = VoiceInputManager(application) {
        // User started speaking while audio was playing -> immediate barge-in / stop playback!
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

    val isListening: StateFlow<Boolean> = voiceInput.isListening
    val recognizedSpeechText: StateFlow<String> = voiceInput.recognizedText
    val micSoundLevel: StateFlow<Float> = voiceInput.soundLevel
    val isPlayingAudio: StateFlow<Boolean> = audioPlayer.isPlaying
    val memories: StateFlow<List<StoredPreference>> = memoryManager.memories

    private var activeJob: Job? = null
    private var lastUserTurnText: String? = null

    init {
        // Setup Voice Input Listeners
        voiceInput.onEndOfSpeechListener = {
            if (_voiceState.value == VoiceState.LISTENING) {
                _voiceState.value = VoiceState.PROCESSING
            }
        }

        voiceInput.onSpeechResultListener = { recognizedText ->
            if (recognizedText.isNotBlank()) {
                handleUserVoiceInput(recognizedText)
            }
        }

        voiceInput.onErrorListener = { errorMsg ->
            if (_isContinuousMode.value) {
                // If temporary no speech in continuous loop, restart listening after a moment
                if (errorMsg.contains("No speech", ignoreCase = true) || errorMsg.contains("timeout", ignoreCase = true)) {
                    viewModelScope.launch {
                        delay(400)
                        if (_isContinuousMode.value && _voiceState.value != VoiceState.SPEAKING) {
                            _voiceState.value = VoiceState.LISTENING
                            voiceInput.startListening()
                        }
                    }
                } else {
                    _voiceState.value = VoiceState.ERROR
                    _lastErrorMessage.value = errorMsg
                }
            } else {
                if (!errorMsg.contains("No speech", ignoreCase = true)) {
                    _voiceState.value = VoiceState.ERROR
                    _lastErrorMessage.value = errorMsg
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }
        }

        // Add welcome message if chat is empty
        val welcome = ChatMessage(
            sender = MessageSender.SANA,
            text = "Hello! I'm SANA. Tap Start Conversation for hands-free voice chat, or type a message below.",
            voiceName = selectedVoice.value.name
        )
        _messages.value = listOf(welcome)
    }

    fun triggerInitialGreeting() {
        if (_messages.value.size <= 1 && isGeminiConfigured()) {
            val greetingText = "Hello! I'm SANA. I'm listening and ready whenever you want to talk."
            viewModelScope.launch {
                delay(400)
                val (base64, mimeType) = geminiService.synthesizeNativeAudioDirect(
                    text = greetingText,
                    voiceName = selectedVoice.value.id,
                    mode = assistantMode.value
                )
                if (!base64.isNullOrBlank()) {
                    _voiceState.value = VoiceState.SPEAKING
                    audioPlayer.playGeminiNativeAudio(base64, mimeType) {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            }
        }
    }

    /**
     * ONE-CLICK START:
     * User taps Start Conversation ONCE. SANA continuously listens, understands,
     * speaks real Gemini Native Audio, and automatically resumes listening!
     */
    fun startContinuousConversation(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            _voiceState.value = VoiceState.ERROR
            _lastErrorMessage.value = "Microphone permission required for voice conversation."
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Please grant Microphone permission to talk with SANA."))
            }
            return
        }

        interruptSpeaking()
        _isContinuousMode.value = true
        _lastErrorMessage.value = null
        _voiceState.value = VoiceState.LISTENING
        voiceInput.startListening()
    }

    /**
     * CLEARLY VISIBLE STOP BUTTON:
     * Immediately stops microphone, terminates continuous loop, stops audio playback, returns to IDLE.
     */
    fun stopContinuousConversation() {
        _isContinuousMode.value = false
        activeJob?.cancel()
        voiceInput.stopListening()
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

    private fun handleUserVoiceInput(spokenText: String) {
        lastUserTurnText = spokenText
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = spokenText
        )
        _messages.value = _messages.value + userMsg

        // Check for phone action intents first (WhatsApp, YouTube, Camera, Settings)
        val actionResult = phoneActionController.evaluateAndExecute(spokenText)
        if (actionResult != null) {
            processActionResponse(actionResult.spokenConfirmation)
            return
        }

        // Process with Gemini Native Audio
        executeGeminiTurn(spokenText)
    }

    fun sendTypedMessage(text: String) {
        if (text.isBlank()) return
        interruptSpeaking()

        lastUserTurnText = text
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

        executeGeminiTurn(text)
    }

    private fun executeGeminiTurn(userInput: String) {
        _voiceState.value = VoiceState.PROCESSING
        _lastErrorMessage.value = null

        activeJob = viewModelScope.launch {
            val history = _messages.value.map {
                (if (it.sender == MessageSender.USER) "user" else "model") to it.text
            }
            val memoryNotes = memoryManager.memories.value.map { "${it.key}: ${it.value}" }

            val result = geminiService.processVoiceTurn(
                userInput = userInput,
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
                    _voiceState.value = VoiceState.SPEAKING
                    audioPlayer.playGeminiNativeAudio(
                        base64Audio = result.audioBase64,
                        mimeType = result.audioMimeType,
                        onFinished = {
                            onSpeechPlaybackCompleted()
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

            // Generate real Gemini Native Audio for the confirmation
            val (base64Audio, mimeType) = geminiService.synthesizeNativeAudioDirect(
                text = confirmationText,
                voiceName = _selectedVoice.value.id,
                mode = _assistantMode.value
            )

            if (!base64Audio.isNullOrBlank()) {
                _voiceState.value = VoiceState.SPEAKING
                audioPlayer.playGeminiNativeAudio(
                    base64Audio = base64Audio,
                    mimeType = mimeType,
                    onFinished = {
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
     */
    private fun onSpeechPlaybackCompleted() {
        if (_isContinuousMode.value) {
            viewModelScope.launch {
                // Buffer delay to prevent acoustic feedback / self-listening
                delay(300)
                if (_isContinuousMode.value) {
                    _voiceState.value = VoiceState.LISTENING
                    voiceInput.startListening()
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }
        } else {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun retryLastTurn() {
        val lastText = lastUserTurnText
        if (!lastText.isNullOrBlank()) {
            _lastErrorMessage.value = null
            executeGeminiTurn(lastText)
        } else if (_isContinuousMode.value) {
            _lastErrorMessage.value = null
            _voiceState.value = VoiceState.LISTENING
            voiceInput.startListening()
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
        _voiceState.value = VoiceState.SPEAKING

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
                    onFinished = {
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
        voiceInput.destroy()
    }
}
