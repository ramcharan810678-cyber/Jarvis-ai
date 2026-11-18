package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.voice.JarvisTtsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisTtsManagerTest {

    @Test
    fun sanitizeForSpeechStripsMarkdownAndCodeBlocks() {
        val raw = "## Status Report\n**All systems** are [online](https://example.com). ```kotlin\nprintln(1)\n```"
        val cleaned = JarvisTtsManager.sanitizeForSpeech(raw)
        assertEquals("Status Report All systems are online. code block omitted.", cleaned)
    }

    @Test
    fun splitIntoSpeechChunksRespectsMaxLength() {
        val text = "Sentence one is clear. Sentence two is also clear. Sentence three completes the report."
        val chunks = JarvisTtsManager.splitIntoSpeechChunks(text, maxChunkLength = 40)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 40 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun initFailureAndToggleUpdateStateCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = JarvisTtsManager(context)

        manager.onInit(TextToSpeech.ERROR)
        assertFalse(manager.state.value.isReady)
        assertEquals("TTS UNAVAILABLE", manager.state.value.voiceStatusLabel)

        manager.setEnabled(false)
        assertFalse(manager.state.value.isEnabled)
        manager.setEnabled(true)
        assertTrue(manager.state.value.isEnabled)

        manager.shutdown()
    }
}
