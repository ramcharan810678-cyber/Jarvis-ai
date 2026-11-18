package com.example.ui

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatMessage
import com.example.data.JarvisAiGateway
import com.example.data.JarvisError
import com.example.data.JarvisErrorMapper
import com.example.data.JarvisErrorType
import com.example.data.JarvisModelOption
import com.example.data.JarvisRepository
import com.example.data.JarvisSpeechConfig
import com.example.data.MessageRole
import com.example.data.TranscriptionResult
import com.example.voice.AudioPreviewPlayer
import com.example.voice.AudioRecorderManager
import com.example.voice.JarvisTtsManager
import com.example.voice.RecordedAudioClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "JarvisViewModel"

class JarvisViewModel @VisibleForTesting internal constructor(
    application: Application,
    private val repository: JarvisAiGateway,
    private val ttsManager: JarvisTtsManager,
    private val audioRecorderManager: AudioRecorderManager,
    private val audioPreviewPlayer: AudioPreviewPlayer = AudioPreviewPlayer()
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = JarvisRepository(),
        ttsManager = JarvisTtsManager(application),
        audioRecorderManager = AudioRecorderManager(application),
        audioPreviewPlayer = AudioPreviewPlayer()
    )

    private val initialGreeting = ChatMessage(
        id = "jarvis-greeting",
        role = MessageRole.JARVIS,
        text = "Good evening. All telemetry systems are online and Firebase App Check attestation is active. How may I assist you?",
        modelId = JarvisModelOption.default.modelId
    )

    private val _uiState = MutableStateFlow(
        JarvisScreenState(messages = listOf(initialGreeting))
    )
    val uiState: StateFlow<JarvisScreenState> = _uiState.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var lastFailedAudioClip: RecordedAudioClip? = null
    private var pendingAudioClip: RecordedAudioClip? = null

    init {
        observeAudioRecorder()
        observeAudioPreviewPlayer()
        observeTtsSubsystem()
    }

    private fun observeAudioRecorder() {
        viewModelScope.launch {
            audioRecorderManager.state.collect { recState ->
                _uiState.update { current ->
                    val updatedGenState = if (!recState.errorMessage.isNullOrBlank()) {
                        GenerationUiState.Error(
                            JarvisError(
                                type = JarvisErrorType.VOICE_INPUT,
                                title = "Audio Recording Fault",
                                detail = recState.errorMessage,
                                canRetry = false
                            )
                        )
                    } else {
                        current.generationState
                    }
                    current.copy(
                        isRecording = recState.isRecording,
                        isProcessingAudio = recState.isProcessing,
                        recordingAmplitude = recState.amplitudeNormalized,
                        recordingElapsedMs = recState.elapsedMs,
                        audioRecorderStatus = recState.statusDetail,
                        audioDiagnostics = recState.lastDiagnostics,
                        generationState = updatedGenState
                    )
                }
            }
        }
    }

    private fun observeAudioPreviewPlayer() {
        viewModelScope.launch {
            audioPreviewPlayer.state.collect { previewState ->
                _uiState.update { current ->
                    val updatedError = if (!previewState.errorMessage.isNullOrBlank()) {
                        GenerationUiState.Error(
                            JarvisError(
                                type = JarvisErrorType.VOICE_INPUT,
                                title = "Audio Preview Playback Failed",
                                detail = previewState.errorMessage,
                                canRetry = false
                            )
                        )
                    } else {
                        current.generationState
                    }
                    current.copy(
                        isPreviewPlaying = previewState.isPlaying,
                        generationState = updatedError
                    )
                }
            }
        }
    }

    private fun observeTtsSubsystem() {
        viewModelScope.launch {
            ttsManager.state.collect { ttsState ->
                _uiState.update {
                    it.copy(
                        isTtsReady = ttsState.isReady,
                        isTtsEnabled = ttsState.isEnabled,
                        isTtsSpeaking = ttsState.isSpeaking,
                        ttsVoiceLabel = ttsState.voiceStatusLabel
                    )
                }
            }
        }
    }

    fun onInputChanged(newText: String) {
        _uiState.update { it.copy(inputText = newText) }
    }

    fun sendCurrentInput() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        _uiState.update { it.copy(inputText = "") }
        sendMessage(prompt = text, fromVoice = false)
    }

    fun sendQuickCommand(command: String) {
        if (_uiState.value.isGenerating || _uiState.value.isRecording) return
        sendMessage(prompt = command, fromVoice = false)
    }

    fun startAudioRecording() {
        if (_uiState.value.isGenerating) return
        ttsManager.stop()
        audioPreviewPlayer.stop()
        audioPreviewPlayer.clearError()
        pendingAudioClip?.let { audioRecorderManager.deleteRetainedPreviewFile(it.previewFilePath) }
        pendingAudioClip = null
        audioRecorderManager.clearError()
        _uiState.update { state ->
            val currentError = (state.generationState as? GenerationUiState.Error)?.error
            val nextGen = if (currentError?.type == JarvisErrorType.VOICE_INPUT) {
                GenerationUiState.Idle
            } else {
                state.generationState
            }
            state.copy(
                pendingAudioPreview = null,
                generationState = nextGen
            )
        }
        audioRecorderManager.startRecording()
    }

    fun stopAudioRecordingForPreview() {
        if (_uiState.value.isGenerating) {
            Log.d(TAG, "Ignored stopAudioRecordingForPreview(); request already in flight.")
            return
        }
        val result = audioRecorderManager.stopRecordingAndReadClip()
        result.onSuccess { clip ->
            stageAudioClipForPreview(clip)
        }
    }

    @VisibleForTesting
    internal fun stageAudioClipForPreview(clip: RecordedAudioClip) {
        audioPreviewPlayer.stop()
        pendingAudioClip = clip
        _uiState.update {
            it.copy(
                pendingAudioPreview = PendingAudioPreviewUi(
                    durationMs = clip.durationMs,
                    sizeBytes = clip.bytes.size,
                    mimeType = clip.mimeType,
                    peakAmplitude = clip.maxAmplitudeObserved,
                    isLikelySilent = clip.isLikelySilent
                ),
                audioDiagnostics = clip.diagnostics
            )
        }
    }

    fun togglePendingAudioPreview() {
        val clip = pendingAudioClip ?: return
        if (_uiState.value.isPreviewPlaying) {
            audioPreviewPlayer.stop()
        } else {
            ttsManager.stop()
            audioPreviewPlayer.play(clip.previewFilePath)
        }
    }

    fun submitPendingAudioPreview() {
        val clip = pendingAudioClip ?: return
        if (_uiState.value.isGenerating || activeGenerationJob?.isActive == true) {
            Log.d(TAG, "Ignored duplicate submitPendingAudioPreview(); request already in flight.")
            return
        }
        audioPreviewPlayer.stop()
        pendingAudioClip = null
        _uiState.update { it.copy(pendingAudioPreview = null) }
        sendRecordedAudioClip(clip)
    }

    fun discardPendingAudioPreview() {
        audioPreviewPlayer.stop()
        audioPreviewPlayer.clearError()
        val clip = pendingAudioClip
        pendingAudioClip = null
        audioRecorderManager.deleteRetainedPreviewFile(clip?.previewFilePath)
        audioRecorderManager.clearError()
        _uiState.update { it.copy(pendingAudioPreview = null) }
    }

    @VisibleForTesting
    internal fun sendRecordedAudioClip(clip: RecordedAudioClip) {
        if (_uiState.value.isGenerating || activeGenerationJob?.isActive == true) {
            Log.d(TAG, "Ignored duplicate sendRecordedAudioClip(); request already in flight.")
            return
        }
        audioPreviewPlayer.stop()
        ttsManager.stop()

        val currentModel = _uiState.value.selectedModel
        if (!repository.isModelSupported(currentModel.modelId)) {
            audioRecorderManager.setProcessing(false)
            _uiState.update {
                it.copy(
                    isTranscribing = false,
                    generationState = GenerationUiState.Error(
                        JarvisError(
                            type = JarvisErrorType.MODEL_UNAVAILABLE,
                            title = "Unsupported Gemini Model",
                            detail = "Model '${currentModel.modelId}' is not supported by this project.",
                            canRetry = false
                        )
                    )
                )
            }
            return
        }

        val priorConversation = _uiState.value.messages.filter { it.id != initialGreeting.id }
        audioRecorderManager.setProcessing(false, "Transcribing...")
        _uiState.update {
            it.copy(
                isTranscribing = true,
                generationState = GenerationUiState.Idle,
                lastFailedPrompt = null
            )
        }
        lastFailedAudioClip = null

        activeGenerationJob = viewModelScope.launch {
            try {
                // STAGE 1: Single request via supported Firebase AI Logic audio-capable model
                val transcription = repository.transcribeAudio(
                    audioBytes = clip.bytes,
                    mimeType = clip.mimeType,
                    seedDiagnostics = clip.diagnostics
                )

                when (transcription) {
                    is TranscriptionResult.NoSpeech -> {
                        audioRecorderManager.setProcessing(false, "MediaRecorder Ready (AAC / audio/mp4)")
                        audioRecorderManager.updateDiagnostics(transcription.diagnostics)
                        lastFailedAudioClip = clip
                        _uiState.update {
                            it.copy(
                                isTranscribing = false,
                                audioDiagnostics = transcription.diagnostics,
                                generationState = GenerationUiState.Error(
                                    JarvisError(
                                        type = JarvisErrorType.VOICE_INPUT,
                                        title = transcription.title,
                                        detail = transcription.reason,
                                        canRetry = true
                                    )
                                )
                            )
                        }
                    }

                    is TranscriptionResult.Success -> {
                        // STAGE 2: Pass transcription directly to existing Gemini chat handler
                        // without displaying the transcription in the chat UI.
                        audioRecorderManager.updateDiagnostics(transcription.diagnostics)
                        val transcriptText = transcription.transcript.trim()
                        if (transcriptText.isBlank()) {
                            val blankDiag = transcription.diagnostics.copy(
                                actualErrorDetail = "Transcript was blank after trimming."
                            )
                            audioRecorderManager.updateDiagnostics(blankDiag)
                            _uiState.update {
                                it.copy(
                                    isTranscribing = false,
                                    audioDiagnostics = blankDiag,
                                    generationState = GenerationUiState.Error(
                                        JarvisError(
                                            type = JarvisErrorType.VOICE_INPUT,
                                            title = "Empty Transcription",
                                            detail = "Gemini returned a blank transcript. Check Developer Diagnostics.",
                                            canRetry = true
                                        )
                                    )
                                )
                            }
                            return@launch
                        }

                        _uiState.update {
                            it.copy(
                                isTranscribing = false,
                                audioDiagnostics = transcription.diagnostics
                            )
                        }
                        executeConversationTurn(
                            prompt = transcriptText,
                            priorConversation = priorConversation,
                            modelOption = currentModel,
                            displayUserBubble = false,
                            failedPromptForRetry = null,
                            failedAudioForRetry = clip
                        )
                    }
                }
            } catch (e: CancellationException) {
                audioRecorderManager.setProcessing(false)
                _uiState.update { it.copy(isTranscribing = false) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stage 1 speech transcription failed", e)
                val cause = (e as? JarvisRepository.TranscriptionPipelineException)?.causeError ?: e
                val rawErr = "${cause::class.java.simpleName}: ${cause.localizedMessage ?: cause.message}"
                val errDiag = (e as? JarvisRepository.TranscriptionPipelineException)?.diagnostics
                    ?: clip.diagnostics.copy(
                        transcriptionModelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
                        transcriptionStatus = "API Error",
                        actualErrorDetail = rawErr
                    )
                audioRecorderManager.setProcessing(false, "Transcription failed")
                audioRecorderManager.updateDiagnostics(errDiag)
                val mappedError = JarvisErrorMapper.map(cause, JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID)
                // Never allow automatic or UI retry on HTTP 429 quota exhaustion
                lastFailedAudioClip = if (mappedError.type == JarvisErrorType.QUOTA_EXCEEDED) null else clip
                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        audioDiagnostics = errDiag,
                        generationState = GenerationUiState.Error(mappedError),
                        lastFailedPrompt = null
                    )
                }
            }
        }
    }

    fun sendMessage(prompt: String, fromVoice: Boolean = false) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        if (_uiState.value.isGenerating || activeGenerationJob?.isActive == true) {
            Log.d(TAG, "Ignored duplicate sendMessage(); request already in flight.")
            return
        }

        ttsManager.stop()
        audioRecorderManager.clearError()
        lastFailedAudioClip = null

        val currentModel = _uiState.value.selectedModel
        if (!repository.isModelSupported(currentModel.modelId)) {
            _uiState.update {
                it.copy(
                    generationState = GenerationUiState.Error(
                        JarvisError(
                            type = JarvisErrorType.MODEL_UNAVAILABLE,
                            title = "Unsupported Gemini Model",
                            detail = "Model '${currentModel.modelId}' is not supported by this project.",
                            canRetry = false
                        )
                    )
                )
            }
            return
        }

        val priorConversation = _uiState.value.messages.filter { it.id != initialGreeting.id }
        activeGenerationJob = viewModelScope.launch {
            executeConversationTurn(
                prompt = trimmed,
                priorConversation = priorConversation,
                modelOption = currentModel,
                displayUserBubble = true,
                failedPromptForRetry = trimmed,
                failedAudioForRetry = null
            )
        }
    }

    private suspend fun executeConversationTurn(
        prompt: String,
        priorConversation: List<ChatMessage>,
        modelOption: JarvisModelOption,
        displayUserBubble: Boolean,
        failedPromptForRetry: String?,
        failedAudioForRetry: RecordedAudioClip?
    ) {
        val assistantPlaceholderId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            role = MessageRole.JARVIS,
            text = "",
            isStreaming = true,
            modelId = modelOption.modelId
        )

        val additions = if (displayUserBubble) {
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                text = prompt,
                fromVoice = false
            )
            listOf(userMessage, assistantPlaceholder)
        } else {
            listOf(assistantPlaceholder)
        }

        audioRecorderManager.setProcessing(false, "Thinking...")
        _uiState.update {
            it.copy(
                messages = it.messages + additions,
                generationState = GenerationUiState.Loading,
                lastFailedPrompt = null
            )
        }

        val accumulated = StringBuilder()
        try {
            repository.sendMessageStream(
                prompt = prompt,
                priorMessages = priorConversation,
                modelOption = modelOption
            ).collect { chunk ->
                accumulated.append(chunk)
                val snapshot = accumulated.toString()
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.id == assistantPlaceholderId) {
                                msg.copy(text = snapshot, isStreaming = true)
                            } else {
                                msg
                            }
                        },
                        generationState = GenerationUiState.Streaming(snapshot)
                    )
                }
            }

            audioRecorderManager.setProcessing(false, "MediaRecorder Ready (AAC / audio/mp4)")
            val finalReply = accumulated.toString().trim()
            if (finalReply.isBlank()) {
                throw IllegalStateException("Gemini core returned an empty response.")
            }

            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == assistantPlaceholderId) {
                            msg.copy(text = finalReply, isStreaming = false)
                        } else {
                            msg
                        }
                    },
                    generationState = GenerationUiState.Success(finalReply),
                    lastFailedPrompt = null
                )
            }

            ttsManager.speak(finalReply)
        } catch (e: CancellationException) {
            audioRecorderManager.setProcessing(false)
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming timed out", e)
            audioRecorderManager.setProcessing(false)
            lastFailedAudioClip = failedAudioForRetry
            handleGenerationFailure(
                throwable = e,
                assistantPlaceholderId = assistantPlaceholderId,
                partialText = accumulated.toString(),
                failedPrompt = failedPromptForRetry,
                modelId = modelOption.modelId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemini generation failed", e)
            audioRecorderManager.setProcessing(false)
            lastFailedAudioClip = failedAudioForRetry
            handleGenerationFailure(
                throwable = e,
                assistantPlaceholderId = assistantPlaceholderId,
                partialText = accumulated.toString(),
                failedPrompt = failedPromptForRetry,
                modelId = modelOption.modelId
            )
        }
    }

    private fun handleGenerationFailure(
        throwable: Throwable,
        assistantPlaceholderId: String,
        partialText: String,
        failedPrompt: String?,
        modelId: String
    ) {
        val mappedError = JarvisErrorMapper.map(throwable, modelId)
        val allowRetry = mappedError.type != JarvisErrorType.QUOTA_EXCEEDED && mappedError.canRetry
        if (!allowRetry) {
            lastFailedAudioClip = null
        }
        _uiState.update { state ->
            val cleanedMessages = if (partialText.isBlank()) {
                state.messages.filterNot { it.id == assistantPlaceholderId }
            } else {
                state.messages.map { msg ->
                    if (msg.id == assistantPlaceholderId) {
                        msg.copy(text = partialText, isStreaming = false)
                    } else {
                        msg
                    }
                }
            }
            state.copy(
                messages = cleanedMessages,
                generationState = GenerationUiState.Error(mappedError),
                lastFailedPrompt = if (allowRetry) failedPrompt else null
            )
        }
    }

    fun retryLastCommand() {
        if (_uiState.value.isGenerating) return
        val currentError = (_uiState.value.generationState as? GenerationUiState.Error)?.error
        if (currentError?.type == JarvisErrorType.QUOTA_EXCEEDED) return

        val clip = lastFailedAudioClip
        if (clip != null) {
            sendRecordedAudioClip(clip)
            return
        }
        val prompt = _uiState.value.lastFailedPrompt ?: return
        sendMessage(prompt = prompt, fromVoice = false)
    }

    fun canRetryLastCommand(): Boolean {
        val currentError = (_uiState.value.generationState as? GenerationUiState.Error)?.error
        if (currentError?.type == JarvisErrorType.QUOTA_EXCEEDED) return false
        return lastFailedAudioClip != null || !_uiState.value.lastFailedPrompt.isNullOrBlank()
    }

    fun dismissError() {
        audioRecorderManager.clearError()
        if (_uiState.value.generationState is GenerationUiState.Error) {
            _uiState.update { it.copy(generationState = GenerationUiState.Idle) }
        }
    }

    fun onVoicePermissionDenied() {
        _uiState.update {
            it.copy(
                generationState = GenerationUiState.Error(
                    JarvisError(
                        type = JarvisErrorType.VOICE_INPUT,
                        title = "Microphone Permission Denied",
                        detail = "Grant RECORD_AUDIO permission in Android Settings to record voice commands, or continue using text input.",
                        canRetry = false
                    )
                )
            )
        }
    }

    fun toggleTts() {
        val nextEnabled = !_uiState.value.isTtsEnabled
        ttsManager.setEnabled(nextEnabled)
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    fun replayMessage(text: String) {
        ttsManager.speak(text, forceSpeak = true)
    }

    fun selectModel(model: JarvisModelOption) {
        try {
            val history = _uiState.value.messages.filter { it.id != initialGreeting.id }
            repository.switchModel(model, history)
            _uiState.update {
                it.copy(
                    selectedModel = model,
                    generationState = GenerationUiState.Idle
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch Gemini model", e)
            val err = JarvisErrorMapper.map(e, model.modelId)
            _uiState.update {
                it.copy(generationState = GenerationUiState.Error(err))
            }
        }
    }

    fun clearConversation() {
        activeGenerationJob?.cancel()
        ttsManager.stop()
        audioPreviewPlayer.stop()
        pendingAudioClip?.let { audioRecorderManager.deleteRetainedPreviewFile(it.previewFilePath) }
        pendingAudioClip = null
        audioRecorderManager.clearError()
        lastFailedAudioClip = null
        repository.resetSession()
        _uiState.update {
            it.copy(
                messages = listOf(initialGreeting.copy(modelId = it.selectedModel.modelId)),
                generationState = GenerationUiState.Idle,
                pendingAudioPreview = null,
                lastFailedPrompt = null
            )
        }
    }

    fun setDiagnosticsSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showDiagnosticsSheet = visible) }
    }

    override fun onCleared() {
        super.onCleared()
        activeGenerationJob?.cancel()
        audioPreviewPlayer.release()
        pendingAudioClip?.let { audioRecorderManager.deleteRetainedPreviewFile(it.previewFilePath) }
        pendingAudioClip = null
        audioRecorderManager.destroy()
        ttsManager.shutdown()
    }
}
