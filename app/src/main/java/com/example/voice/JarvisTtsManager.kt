package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID

private const val TAG = "JarvisTtsManager"
private const val DEFAULT_MAX_CHUNK_LENGTH = 3500

data class TtsState(
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val isEnabled: Boolean = true,
    val voiceStatusLabel: String = "INITIALIZING TTS"
)

class JarvisTtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var pendingUtterance: String? = null
    private var pendingForceSpeak: Boolean = false
    private var finalChunkUtteranceId: String? = null
    private var preferredLocale: Locale = Locale.UK

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech initialization failed with status=$status")
            _state.update {
                it.copy(
                    isReady = false,
                    isSpeaking = false,
                    voiceStatusLabel = "TTS UNAVAILABLE"
                )
            }
            return
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val label = applyLocaleToEngine(engine, preferredLocale)

        engine.setPitch(0.95f)
        engine.setSpeechRate(1.03f)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.update { it.copy(isSpeaking = true) }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null || utteranceId == finalChunkUtteranceId) {
                    _state.update { it.copy(isSpeaking = false) }
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _state.update { it.copy(isSpeaking = false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.update { it.copy(isSpeaking = false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS utterance errorCode=$errorCode")
                _state.update { it.copy(isSpeaking = false) }
            }
        })

        _state.update {
            it.copy(
                isReady = true,
                voiceStatusLabel = label
            )
        }

        pendingUtterance?.let { queued ->
            val force = pendingForceSpeak
            pendingUtterance = null
            pendingForceSpeak = false
            speak(queued, forceSpeak = force)
        }
    }

    fun setPreferredLocale(locale: Locale) {
        preferredLocale = locale
        val engine = tts
        if (engine != null && _state.value.isReady) {
            val label = applyLocaleToEngine(engine, locale)
            _state.update { it.copy(voiceStatusLabel = label) }
        }
    }

    private fun applyLocaleToEngine(engine: TextToSpeech, target: Locale): String {
        val targetResult = engine.setLanguage(target)
        if (targetResult != TextToSpeech.LANG_MISSING_DATA &&
            targetResult != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return "${target.toLanguageTag().uppercase(Locale.US)} VOICE"
        }
        val ukResult = engine.setLanguage(Locale.UK)
        if (ukResult != TextToSpeech.LANG_MISSING_DATA &&
            ukResult != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return "EN-GB VOICE"
        }
        val usResult = engine.setLanguage(Locale.US)
        if (usResult != TextToSpeech.LANG_MISSING_DATA &&
            usResult != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return "EN-US VOICE"
        }
        engine.setLanguage(Locale.getDefault())
        return "SYSTEM VOICE"
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }
        if (!enabled) {
            stop()
        }
    }

    fun speak(rawText: String, forceSpeak: Boolean = false) {
        if (!forceSpeak && !_state.value.isEnabled) return
        val cleanText = sanitizeForSpeech(rawText)
        if (cleanText.isBlank()) return

        val engine = tts ?: return
        if (!_state.value.isReady) {
            pendingUtterance = cleanText
            pendingForceSpeak = forceSpeak
            return
        }

        val maxLimit = TextToSpeech.getMaxSpeechInputLength()
            .takeIf { it > 0 }
            ?.coerceAtMost(DEFAULT_MAX_CHUNK_LENGTH)
            ?: DEFAULT_MAX_CHUNK_LENGTH

        val chunks = splitIntoSpeechChunks(cleanText, maxLimit)
        if (chunks.isEmpty()) return

        val ids = chunks.map { UUID.randomUUID().toString() }
        finalChunkUtteranceId = ids.last()

        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, queueMode, null, ids[index])
        }
    }

    fun stop() {
        pendingUtterance = null
        pendingForceSpeak = false
        finalChunkUtteranceId = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        _state.update { it.copy(isSpeaking = false) }
    }

    fun shutdown() {
        pendingUtterance = null
        pendingForceSpeak = false
        finalChunkUtteranceId = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        } finally {
            tts = null
        }
    }

    companion object {
        @VisibleForTesting
        internal fun sanitizeForSpeech(text: String): String {
            return text
                .replace(Regex("```[\\s\\S]*?```"), " code block omitted. ")
                .replace(Regex("[*#`_~>]"), "")
                .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        @VisibleForTesting
        internal fun splitIntoSpeechChunks(text: String, maxChunkLength: Int): List<String> {
            if (text.length <= maxChunkLength) return listOf(text)
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val end = (start + maxChunkLength).coerceAtMost(text.length)
                if (end == text.length) {
                    chunks.add(text.substring(start).trim())
                    break
                }
                val slice = text.substring(start, end)
                val sentenceBreak = slice.lastIndexOfAny(charArrayOf('.', '!', '?'))
                val splitAt = when {
                    sentenceBreak > maxChunkLength / 2 -> start + sentenceBreak + 1
                    slice.lastIndexOf(' ') > 0 -> start + slice.lastIndexOf(' ')
                    else -> end
                }
                val chunk = text.substring(start, splitAt).trim()
                if (chunk.isNotEmpty()) {
                    chunks.add(chunk)
                }
                start = splitAt
                while (start < text.length && text[start].isWhitespace()) {
                    start++
                }
            }
            return chunks
        }
    }
}
