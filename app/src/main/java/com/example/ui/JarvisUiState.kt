package com.example.ui

import com.example.data.AudioPipelineDiagnostics
import com.example.data.ChatMessage
import com.example.data.JarvisError
import com.example.data.JarvisModelOption

sealed interface GenerationUiState {
    data object Idle : GenerationUiState
    data object Loading : GenerationUiState
    data class Streaming(val text: String) : GenerationUiState
    data class Success(val text: String) : GenerationUiState
    data class Error(val error: JarvisError) : GenerationUiState
}

enum class HologramActivityMode {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    PROCESSING,
    THINKING,
    SPEAKING,
    ALERT
}

data class PendingAudioPreviewUi(
    val durationMs: Long,
    val sizeBytes: Int,
    val mimeType: String,
    val peakAmplitude: Int,
    val isLikelySilent: Boolean
)

data class JarvisScreenState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val selectedModel: JarvisModelOption = JarvisModelOption.default,
    val generationState: GenerationUiState = GenerationUiState.Idle,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val isProcessingAudio: Boolean = false,
    val recordingAmplitude: Float = 0f,
    val recordingElapsedMs: Long = 0L,
    val audioRecorderStatus: String = "MediaRecorder Ready (AAC / audio/mp4)",
    val audioDiagnostics: AudioPipelineDiagnostics = AudioPipelineDiagnostics(),
    val pendingAudioPreview: PendingAudioPreviewUi? = null,
    val isPreviewPlaying: Boolean = false,
    val isTtsReady: Boolean = false,
    val isTtsEnabled: Boolean = true,
    val isTtsSpeaking: Boolean = false,
    val ttsVoiceLabel: String = "INITIALIZING TTS",
    val lastFailedPrompt: String? = null,
    val showDiagnosticsSheet: Boolean = false
) {
    val hologramMode: HologramActivityMode
        get() = when {
            isRecording -> HologramActivityMode.RECORDING
            isTranscribing -> HologramActivityMode.TRANSCRIBING
            isProcessingAudio -> HologramActivityMode.PROCESSING
            generationState is GenerationUiState.Loading ||
                generationState is GenerationUiState.Streaming -> HologramActivityMode.THINKING
            isTtsSpeaking -> HologramActivityMode.SPEAKING
            generationState is GenerationUiState.Error -> HologramActivityMode.ALERT
            else -> HologramActivityMode.IDLE
        }

    val isGenerating: Boolean
        get() = isProcessingAudio ||
            isTranscribing ||
            generationState is GenerationUiState.Loading ||
            generationState is GenerationUiState.Streaming
}
