package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantMode
import com.example.data.AvailableVoices
import com.example.data.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SANA", appName)
    }

    @Test
    fun `verify primary voice is Kore and alternate is Aoede`() {
        val voiceIds = AvailableVoices.ALL_VOICES.map { it.id }
        assertTrue(voiceIds.contains("Kore"))
        assertTrue(voiceIds.contains("Aoede"))
        assertTrue(voiceIds.contains("Sulafat"))
        assertTrue(voiceIds.contains("Achird"))
        assertTrue(voiceIds.contains("Despina"))
        assertTrue(voiceIds.contains("Leda"))

        val kore = AvailableVoices.getById("Kore")
        assertNotNull(kore)
        assertTrue(kore.isPrimary)

        val aoede = AvailableVoices.getById("Aoede")
        assertNotNull(aoede)
        assertTrue(aoede.isAlternate)
    }

    @Test
    fun `verify assistant modes and voice states`() {
        val modes = AssistantMode.values()
        assertTrue(modes.contains(AssistantMode.ASSISTANT))
        assertTrue(modes.contains(AssistantMode.FRIEND))
        assertTrue(modes.contains(AssistantMode.COMPANION))
        assertTrue(modes.contains(AssistantMode.ROMANTIC))

        val states = VoiceState.values()
        assertTrue(states.contains(VoiceState.IDLE))
        assertTrue(states.contains(VoiceState.LISTENING))
        assertTrue(states.contains(VoiceState.PROCESSING))
        assertTrue(states.contains(VoiceState.SPEAKING))
        assertTrue(states.contains(VoiceState.ERROR))
    }
}
