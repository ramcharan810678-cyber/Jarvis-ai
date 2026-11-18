package com.example.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

private const val TAG = "AudioPreviewPlayer"

data class AudioPreviewPlaybackState(
    val isPlaying: Boolean = false,
    val errorMessage: String? = null
)

class AudioPreviewPlayer {

    private val _state = MutableStateFlow(AudioPreviewPlaybackState())
    val state: StateFlow<AudioPreviewPlaybackState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    fun play(filePath: String?): Boolean {
        stop()
        if (filePath.isNullOrBlank()) {
            val msg = "No recorded audio file is available for playback preview."
            _state.update { it.copy(isPlaying = false, errorMessage = msg) }
            return false
        }
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            val msg = "Recorded preview file is missing or empty."
            _state.update { it.copy(isPlaying = false, errorMessage = msg) }
            return false
        }

        return try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    Log.i(TAG, "Audio preview playback completed.")
                    releaseInternal()
                    _state.update { it.copy(isPlaying = false) }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer playback error: what=$what, extra=$extra")
                    releaseInternal()
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            errorMessage = "Audio preview playback failed (code=$what/$extra)."
                        )
                    }
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _state.update { it.copy(isPlaying = true, errorMessage = null) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start audio preview playback", e)
            releaseInternal()
            _state.update {
                it.copy(
                    isPlaying = false,
                    errorMessage = "Unable to play recorded preview: ${e.localizedMessage ?: e::class.java.simpleName}"
                )
            }
            false
        }
    }

    fun stop() {
        releaseInternal()
        _state.update { it.copy(isPlaying = false) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun release() {
        stop()
    }

    private fun releaseInternal() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Exception) {
            }
            try {
                player.reset()
                player.release()
            } catch (_: Exception) {
            }
        }
    }
}
