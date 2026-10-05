package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class SanaMemoryManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sana_local_memory", Context.MODE_PRIVATE)

    private val _memories = MutableStateFlow<List<StoredPreference>>(emptyList())
    val memories: StateFlow<List<StoredPreference>> = _memories.asStateFlow()

    init {
        loadMemories()
    }

    private fun loadMemories() {
        val raw = prefs.getString("user_memory_items", null)
        val list = mutableListOf<StoredPreference>()

        if (!raw.isNullOrBlank()) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        StoredPreference(
                            key = obj.getString("key"),
                            value = obj.getString("value"),
                            category = obj.optString("category", "General")
                        )
                    )
                }
            } catch (e: Exception) {
                // If corrupted, fallback
            }
        } else {
            // Default friendly baseline preferences
            list.add(StoredPreference("Preferred Address", "Boss / User", "Identity"))
            list.add(StoredPreference("Languages", "English, Urdu, Roman Urdu", "Speech"))
            list.add(StoredPreference("Response Style", "Concise, Natural, Expressive", "Voice"))
        }

        _memories.value = list
    }

    private fun saveMemories() {
        val array = JSONArray()
        for (item in _memories.value) {
            val obj = JSONObject()
            obj.put("key", item.key)
            obj.put("value", item.value)
            obj.put("category", item.category)
            array.put(obj)
        }
        prefs.edit().putString("user_memory_items", array.toString()).apply()
    }

    fun addOrUpdateMemory(key: String, value: String, category: String = "Custom") {
        if (key.isBlank() || value.isBlank()) return
        val current = _memories.value.toMutableList()
        val index = current.indexOfFirst { it.key.equals(key, ignoreCase = true) }
        if (index >= 0) {
            current[index] = StoredPreference(key, value, category)
        } else {
            current.add(StoredPreference(key, value, category))
        }
        _memories.value = current
        saveMemories()
    }

    fun removeMemory(key: String) {
        val current = _memories.value.filterNot { it.key.equals(key, ignoreCase = true) }
        _memories.value = current
        saveMemories()
    }

    fun clearAllMemories() {
        _memories.value = emptyList()
        prefs.edit().remove("user_memory_items").apply()
    }
}
