package com.example.network

import android.util.Base64
import com.example.BuildConfig
import com.example.data.AssistantMode
import com.example.util.SanaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class GeminiVoiceResult {
    data class Success(
        val text: String,
        val audioBase64: String,
        val audioMimeType: String,
        val detectedLanguage: String = "English"
    ) : GeminiVoiceResult()

    data class Error(
        val message: String,
        val isConfigurationError: Boolean = false,
        val canRetry: Boolean = true
    ) : GeminiVoiceResult()
}

class GeminiVoiceService {

    private val COMPONENT = "GeminiVoiceService"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var customApiKey: String? = null

    val apiKey: String
        get() {
            if (!customApiKey.isNullOrBlank()) return customApiKey!!.trim()
            return try {
                BuildConfig.GEMINI_API_KEY.trim()
            } catch (e: Exception) {
                ""
            }
        }

    fun isApiKeyConfigured(): Boolean {
        val key = apiKey.trim()
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    /**
     * Executes conversational turn with Gemini.
     * Supports real microphone WAV audio bytes AND/OR text input.
     */
    suspend fun processVoiceTurn(
        userAudioBytes: ByteArray? = null,
        userInputText: String? = null,
        mode: AssistantMode,
        voiceName: String,
        history: List<Pair<String, String>>,
        memoryNotes: List<String> = emptyList()
    ): GeminiVoiceResult = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            SanaLogger.e(COMPONENT, "Gemini API key is not configured")
            return@withContext GeminiVoiceResult.Error(
                message = "Gemini AI is not configured yet. Please add the required API configuration in the Secrets panel, .env file, or Settings.",
                isConfigurationError = true,
                canRetry = false
            )
        }

        val audioDesc = if (userAudioBytes != null) "${userAudioBytes.size} bytes audio" else "text only"
        SanaLogger.i(COMPONENT, "Sending user input to Gemini ($audioDesc). Mode: ${mode.name}, Voice: $voiceName")

        try {
            val systemInstruction = buildSystemPrompt(mode, memoryNotes)

            // Step 1: Generate response text
            val textResponse = generateText(userAudioBytes, userInputText, systemInstruction, history)

            if (textResponse.isNullOrBlank()) {
                SanaLogger.w(COMPONENT, "Gemini returned empty text response")
                return@withContext GeminiVoiceResult.Error(
                    message = "Could not generate response from voice. Please check network connection and try again.",
                    canRetry = true
                )
            }

            SanaLogger.i(COMPONENT, "Gemini response text received (${textResponse.length} chars). Generating Native Audio...")

            // Step 2: Generate REAL Gemini Native Audio with Kore / Aoede voice
            val (base64Audio, mimeType) = synthesizeNativeAudioDirect(textResponse, voiceName, mode)

            if (base64Audio.isNullOrBlank()) {
                SanaLogger.e(COMPONENT, "Gemini Native Audio synthesis did not return audio")
                return@withContext GeminiVoiceResult.Error(
                    message = "Gemini Native Audio synthesis did not return audio. Please retry.",
                    canRetry = true
                )
            }

            SanaLogger.i(COMPONENT, "Gemini Native Audio response received (${base64Audio.length} base64 chars).")

            GeminiVoiceResult.Success(
                text = textResponse,
                audioBase64 = base64Audio,
                audioMimeType = mimeType ?: "audio/wav"
            )
        } catch (e: Exception) {
            SanaLogger.e(COMPONENT, "processVoiceTurn exception", e)
            val msg = e.localizedMessage ?: "Network error communicating with Gemini"
            GeminiVoiceResult.Error(
                message = if (msg.contains("403") || msg.contains("API_KEY")) {
                    "Invalid Gemini API key or quota exceeded. Please check your API configuration."
                } else {
                    "Network error: $msg"
                },
                canRetry = true
            )
        }
    }

    private suspend fun generateText(
        userAudioBytes: ByteArray?,
        userInputText: String?,
        systemInstruction: String,
        history: List<Pair<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        val contentsArray = JSONArray()

        val recentHistory = history.takeLast(6)
        for ((role, text) in recentHistory) {
            val turnObj = JSONObject()
            turnObj.put("role", if (role == "user") "user" else "model")
            val partsArray = JSONArray()
            val part = JSONObject()
            part.put("text", text)
            partsArray.put(part)
            turnObj.put("parts", partsArray)
            contentsArray.put(turnObj)
        }

        val currentUserObj = JSONObject()
        currentUserObj.put("role", "user")
        val currentParts = JSONArray()

        // If recorded microphone audio is provided, attach as inlineData audio/wav
        if (userAudioBytes != null && userAudioBytes.isNotEmpty()) {
            val base64Audio = Base64.encodeToString(userAudioBytes, Base64.NO_WRAP)
            val audioPart = JSONObject().apply {
                val inlineData = JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", base64Audio)
                }
                put("inlineData", inlineData)
            }
            currentParts.put(audioPart)
        }

        // If text or transcript is available, attach as text part
        if (!userInputText.isNullOrBlank()) {
            val textPart = JSONObject().apply {
                put("text", userInputText)
            }
            currentParts.put(textPart)
        } else if (userAudioBytes != null) {
            val promptPart = JSONObject().apply {
                put("text", "Please listen to the user's voice message above and reply naturally, warmly, and concisely.")
            }
            currentParts.put(promptPart)
        }

        currentUserObj.put("parts", currentParts)
        contentsArray.put(currentUserObj)

        val payload = JSONObject().apply {
            put("contents", contentsArray)

            val sysObj = JSONObject()
            val sysParts = JSONArray()
            val sysPart = JSONObject()
            sysPart.put("text", systemInstruction)
            sysParts.put(sysPart)
            sysObj.put("parts", sysParts)
            put("systemInstruction", sysObj)

            val genConfig = JSONObject()
            genConfig.put("temperature", 0.7)
            genConfig.put("topP", 0.95)
            genConfig.put("maxOutputTokens", 250)
            put("generationConfig", genConfig)
        }

        // Try gemini-2.5-flash (multimodal audio) first, fallback to gemini-3.5-flash
        val models = listOf("gemini-2.5-flash", "gemini-3.5-flash")
        for (model in models) {
            try {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")

                    var textResult: String? = null
                    for (i in 0 until (parts?.length() ?: 0)) {
                        val p = parts?.optJSONObject(i)
                        val t = p?.optString("text")
                        if (!t.isNullOrBlank()) {
                            textResult = t
                            break
                        }
                    }

                    if (!textResult.isNullOrBlank()) {
                        return@withContext cleanSpokenText(textResult)
                    }
                } else {
                    SanaLogger.w(COMPONENT, "Model $model returned HTTP ${response.code}: $responseBody")
                }
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Exception with model $model: ${e.message}")
            }
        }

        null
    }

    /**
     * Synthesizes audio using Gemini Native Audio model with the chosen voice.
     * Iterates through ALL parts in the response to extract the audio payload.
     */
    suspend fun synthesizeNativeAudioDirect(
        text: String,
        voiceName: String,
        mode: AssistantMode
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext Pair(null, null)
        }

        val validVoice = when (voiceName.trim().lowercase()) {
            "aoede" -> "Aoede"
            "sulafat" -> "Sulafat"
            "achird" -> "Achird"
            "despina" -> "Despina"
            "leda" -> "Leda"
            "kore" -> "Kore"
            else -> "Kore" // Primary default
        }

        val speechPrompt = when (mode) {
            AssistantMode.ROMANTIC -> "Speak in a warm, gentle, affectionate, and playful tone: "
            AssistantMode.COMPANION -> "Speak in a comforting, deeply empathetic, and soothing tone: "
            AssistantMode.FRIEND -> "Speak in a cheerful, sunny, friendly, and lively tone: "
            AssistantMode.ASSISTANT -> "Speak clearly, naturally, and warmly: "
        }

        val payload = JSONObject().apply {
            val contents = JSONArray()
            val content = JSONObject()
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", speechPrompt + text)
            parts.put(part)
            content.put("parts", parts)
            contents.put(content)
            put("contents", contents)

            val genConfig = JSONObject()
            val responseModalities = JSONArray()
            responseModalities.put("AUDIO")
            genConfig.put("responseModalities", responseModalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", validVoice)
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)
            genConfig.put("speechConfig", speechConfig)

            put("generationConfig", genConfig)
        }

        val ttsModels = listOf(
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-flash"
        )

        for (model in ttsModels) {
            try {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")

                    val partsCount = parts?.length() ?: 0
                    for (i in 0 until partsCount) {
                        val partObj = parts?.optJSONObject(i)
                        val inlineData = partObj?.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val base64Data = inlineData.optString("data")
                            val mimeType = inlineData.optString("mimeType", "audio/wav")
                            if (!base64Data.isNullOrBlank()) {
                                SanaLogger.i(COMPONENT, "Extracted native audio from model $model ($mimeType)")
                                return@withContext Pair(base64Data, mimeType)
                            }
                        }
                    }
                } else {
                    SanaLogger.w(COMPONENT, "TTS model $model returned HTTP ${response.code}")
                }
            } catch (e: Exception) {
                SanaLogger.w(COMPONENT, "Exception with TTS model $model: ${e.message}")
            }
        }

        Pair(null, null)
    }

    private fun buildSystemPrompt(mode: AssistantMode, memoryNotes: List<String>): String {
        val memoryBlock = if (memoryNotes.isNotEmpty()) {
            "\nUSER CONTEXT & STORED PREFERENCES:\n" + memoryNotes.joinToString("\n") { "- $it" }
        } else ""

        return """
You are SANA, a highly intelligent, natural AI voice assistant and companion.
You speak directly through Gemini Native Audio.

PRIMARY MODE: ${mode.displayName}
${mode.promptRole}

LANGUAGE INSTRUCTIONS:
- SANA supports English, Urdu, and Roman Urdu.
- Automatically detect the user's language and respond naturally in the EXACT SAME language/script.
- If the user speaks English, respond in natural, warm English.
- If the user speaks Urdu (e.g. "واٹس ایپ کھولو"), respond in natural Urdu (e.g. "جی، میں واٹس ایپ کھول رہی ہوں۔").
- If the user speaks Roman Urdu (e.g. "WhatsApp kholo"), respond in natural Roman Urdu (e.g. "Ji, WhatsApp khol rahi hoon.").

CONVERSATION & SPOKEN AUDIO CONSTRAINTS:
- Responses will be directly synthesized into spoken voice.
- Keep responses concise (1 to 3 spoken sentences maximum).
- Avoid bullet points, numbered lists, markdown asterisks, or raw URLs.
- Speak warmly, smoothly, and directly to the user.
$memoryBlock
""".trimIndent()
    }

    private fun cleanSpokenText(raw: String): String {
        return raw.replace(Regex("\\*\\*|\\*"), "")
            .replace(Regex("(?m)^[-*]\\s+"), "")
            .trim()
    }
}
