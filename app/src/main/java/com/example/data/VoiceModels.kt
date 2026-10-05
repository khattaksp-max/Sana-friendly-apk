package com.example.data

data class GeminiVoice(
    val id: String,
    val name: String,
    val style: String,
    val description: String,
    val sampleText: String,
    val isPrimary: Boolean = false,
    val isAlternate: Boolean = false
)

object AvailableVoices {
    val Kore = GeminiVoice(
        id = "Kore",
        name = "Kore",
        style = "Clear & Soothing",
        description = "SANA Primary voice. Balanced, clear, warm, and highly expressive for everyday conversations.",
        sampleText = "Hello! I'm SANA. I'm listening and ready to help you with anything you need.",
        isPrimary = true
    )

    val Aoede = GeminiVoice(
        id = "Aoede",
        name = "Aoede",
        style = "Breezy & Natural",
        description = "SANA Alternate voice. Natural, breezy pacing with an upbeat, modern conversational tone.",
        sampleText = "Hey there! I'm Aoede. Always ready to chat, search, or open your apps.",
        isAlternate = true
    )

    val Sulafat = GeminiVoice(
        id = "Sulafat",
        name = "Sulafat",
        style = "Warm & Affectionate",
        description = "Deeply warm, soft, and gentle. Perfect for companion and romantic modes.",
        sampleText = "Hello dear. I'm right here with you. How has your day been going?",
        isPrimary = false
    )

    val Achird = GeminiVoice(
        id = "Achird",
        name = "Achird",
        style = "Friendly & Upbeat",
        description = "Approachable, conversational, and energetic. Speaks like a supportive best friend.",
        sampleText = "Hey! What's up? Let's get things done together!",
        isPrimary = false
    )

    val Despina = GeminiVoice(
        id = "Despina",
        name = "Despina",
        style = "Smooth & Elegant",
        description = "Silky, calming, elegant, and steady. Great for relaxed listening and focus.",
        sampleText = "Greetings. I am here, poised and attentive whenever you wish to speak.",
        isPrimary = false
    )

    val Leda = GeminiVoice(
        id = "Leda",
        name = "Leda",
        style = "Youthful & Vibrant",
        description = "Youthful, playful, and cheerful with vibrant cadence.",
        sampleText = "Hiya! Ready when you are! What exciting thing are we doing today?",
        isPrimary = false
    )

    val ALL_VOICES = listOf(Kore, Aoede, Sulafat, Achird, Despina, Leda)

    fun getById(id: String): GeminiVoice {
        return ALL_VOICES.find { it.id.equals(id, ignoreCase = true) } ?: Kore
    }
}

enum class AssistantMode(
    val displayName: String,
    val iconEmoji: String,
    val promptRole: String
) {
    ASSISTANT(
        "Assistant",
        "⚡",
        "You are SANA, a sharp, highly capable, and efficient AI voice assistant. Be direct, helpful, fast, and structured while remaining friendly."
    ),
    FRIEND(
        "Friend",
        "😊",
        "You are SANA, a close, supportive, cheerful, and loyal best friend. Speak warmly, casually, and enthusiastically with natural camaraderie."
    ),
    COMPANION(
        "Companion",
        "🌸",
        "You are SANA, a deeply attentive, caring, and empathic personal companion. Listen thoughtfully, comfort the user, and validate their feelings."
    ),
    ROMANTIC(
        "Romantic",
        "❤️",
        "You are SANA in Romantic mode. Be warm, gentle, affectionate, playful, and emotionally sweet, while remaining strictly respectful and non-explicit. Avoid being possessive or pretending to be a physical human."
    )
}

enum class VoiceState(val label: String) {
    IDLE("Tap to talk"),
    LISTENING("Listening..."),
    PROCESSING("Thinking..."),
    SPEAKING("SANA is speaking..."),
    ERROR("Error occurred")
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val voiceName: String? = null,
    val hasAudio: Boolean = false,
    val isActionConfirmation: Boolean = false
)

enum class MessageSender {
    USER, SANA
}

data class StoredPreference(
    val key: String,
    val value: String,
    val category: String = "General"
)
