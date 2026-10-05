package com.example.network

import android.util.Log
import com.example.BuildConfig
import com.example.data.AssistantMode
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

    private val TAG = "GeminiVoiceService"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val apiKey: String
        get() = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

    fun isApiKeyConfigured(): Boolean {
        val key = apiKey.trim()
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    /**
     * Executes the conversational turn and speech synthesis using real Gemini Native Audio.
     */
    suspend fun processVoiceTurn(
        userInput: String,
        mode: AssistantMode,
        voiceName: String,
        history: List<Pair<String, String>>,
        memoryNotes: List<String> = emptyList()
    ): GeminiVoiceResult = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext GeminiVoiceResult.Error(
                message = "Gemini AI is not configured yet. Please add the required API configuration in the Secrets panel or .env file.",
                isConfigurationError = true,
                canRetry = false
            )
        }

        try {
            // Step 1: Generate conversational text response in matching language and style
            val systemInstruction = buildSystemPrompt(mode, memoryNotes)
            val textResponse = generateText(userInput, systemInstruction, history)

            if (textResponse.isNullOrBlank()) {
                return@withContext GeminiVoiceResult.Error(
                    message = "Could not generate response. Please check your network and try again.",
                    canRetry = true
                )
            }

            // Step 2: Generate REAL Gemini Native Audio with specified voice
            val (base64Audio, mimeType) = synthesizeNativeAudioDirect(textResponse, voiceName, mode)

            if (base64Audio.isNullOrBlank()) {
                return@withContext GeminiVoiceResult.Error(
                    message = "Gemini Native Audio synthesis did not return audio. Please retry.",
                    canRetry = true
                )
            }

            GeminiVoiceResult.Success(
                text = textResponse,
                audioBase64 = base64Audio,
                audioMimeType = mimeType ?: "audio/wav"
            )
        } catch (e: Exception) {
            Log.e(TAG, "processVoiceTurn exception", e)
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
        userInput: String,
        systemInstruction: String,
        history: List<Pair<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        val contentsArray = JSONArray()

        // Include last 6 turns for context
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
        val currentPart = JSONObject()
        currentPart.put("text", userInput)
        currentParts.put(currentPart)
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

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.e(TAG, "Gemini text gen failed: ${response.code} $responseBody")
            return@withContext null
        }

        val json = JSONObject(responseBody)
        val candidates = json.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
        val content = firstCandidate?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val text = parts?.optJSONObject(0)?.optString("text")

        cleanSpokenText(text ?: "")
    }

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
            AssistantMode.ROMANTIC -> "Speak with an affectionate, warm, gentle, and soft feminine voice: "
            AssistantMode.COMPANION -> "Speak with a deeply caring, soothing, and supportive feminine voice: "
            AssistantMode.FRIEND -> "Speak in a cheerful, sunny, friendly, and lively feminine tone: "
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

        val ttsModels = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-flash")
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
                    val firstPart = parts?.optJSONObject(0)
                    val inlineData = firstPart?.optJSONObject("inlineData")
                    val base64Data = inlineData?.optString("data")
                    val mimeType = inlineData?.optString("mimeType")

                    if (!base64Data.isNullOrBlank()) {
                        return@withContext Pair(base64Data, mimeType ?: "audio/wav")
                    }
                } else {
                    Log.w(TAG, "Audio synthesis failed with model $model: ${response.code} $responseBody")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception calling TTS model $model", e)
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
