package com.example

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.data.AudioPipelineDiagnostics
import com.example.data.ChatMessage
import com.example.data.JarvisAiGateway
import com.example.data.JarvisErrorType
import com.example.data.JarvisModelOption
import com.example.data.JarvisRepository
import com.example.data.JarvisSpeechConfig
import com.example.data.MessageRole
import com.example.data.TranscriptionResult
import com.example.ui.GenerationUiState
import com.example.ui.HologramActivityMode
import com.example.ui.JarvisScreenState
import com.example.ui.JarvisViewModel
import com.example.voice.AudioPreviewPlayer
import com.example.voice.AudioRecorderManager
import com.example.voice.JarvisTtsManager
import com.example.voice.RecordedAudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisAudioFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeJarvisGateway(
        private val transcriptionResult: TranscriptionResult = TranscriptionResult.Success(
            transcript = "Run a telemetry status check in Hyderabad",
            diagnostics = AudioPipelineDiagnostics(
                micCaptureStatus = "Captured MIC audio (peak=12400)",
                recorderLifecycleStatus = "Started -> Stopped cleanly",
                recordingDurationMs = 3_000L,
                audioSizeBytes = 6,
                recordedMimeType = "audio/mp4",
                hasNonEmptyAudioData = true,
                containerValidation = "MP4+AAC track verified (audio/mp4a-latm, 44100Hz, 1ch)",
                signalTelemetry = "peak=12400, avg=4800, activePolls=25/35 (Speech signal present)",
                base64PayloadLength = 8,
                base64Verified = true,
                geminiSentMimeType = "audio/mp4",
                requestStructureSummary = "role=user | parts=[InlineDataPart(mime=audio/mp4, bytes=6, base64Len=8), TextPart(chars=320)]",
                transcriptionModelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
                transcriptionStatus = "Success (41 chars)",
                rawResponseStructure = "OK(41 chars) | candidates=1, parts=[TextPart], finishReason=STOP, blockReason=NONE"
            )
        ),
        private val replyChunks: List<String> = listOf("All systems ", "are nominal, Sir."),
        private val stage1Exception: Throwable? = null,
        private val failStage2: Boolean = false,
        private val stage1DelayMs: Long = 0L
    ) : JarvisAiGateway {
        var transcribeCallCount: Int = 0
        var sendMessageStreamCallCount: Int = 0
        var lastAudioBytes: ByteArray? = null
        var lastMimeType: String? = null
        var lastTextPrompt: String? = null
        var lastSeedDiagnostics: AudioPipelineDiagnostics? = null

        override fun isModelSupported(modelId: String): Boolean =
            JarvisModelOption.fromModelId(modelId)?.isSupportedByProject == true

        override fun switchModel(newModel: JarvisModelOption, existingMessages: List<ChatMessage>) = Unit

        override fun resetSession() = Unit

        override suspend fun transcribeAudio(
            audioBytes: ByteArray,
            mimeType: String,
            seedDiagnostics: AudioPipelineDiagnostics
        ): TranscriptionResult {
            transcribeCallCount++
            lastAudioBytes = audioBytes
            lastMimeType = mimeType
            lastSeedDiagnostics = seedDiagnostics
            if (stage1DelayMs > 0L) delay(stage1DelayMs)
            stage1Exception?.let { throw it }
            return transcriptionResult
        }

        override fun sendMessageStream(
            prompt: String,
            priorMessages: List<ChatMessage>,
            modelOption: JarvisModelOption
        ): Flow<String> = flow {
            sendMessageStreamCallCount++
            lastTextPrompt = prompt
            if (failStage2) throw IllegalStateException("Network error")
            replyChunks.forEach { emit(it) }
        }
    }

    @Test
    fun selectedTranscriptionModelIsOfficiallySupportedByGeminiApi() {
        assertTrue(JarvisSpeechConfig.isTranscriptionModelSupported())
        assertEquals("gemini-3.5-flash-lite", JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID)
    }

    @Test
    fun base64EncodingVerificationConfirmsNonEmptyRoundTrip() {
        val sample = byteArrayOf(0x00, 0x01, 0x7F, -0x80, 0x2A, 0x55)
        val check = AudioRecorderManager.verifyBase64Encoding(sample)
        assertTrue(check.roundTripMatches)
        assertEquals(8, check.encodedLength)

        val emptyCheck = AudioRecorderManager.verifyBase64Encoding(byteArrayOf())
        assertFalse(emptyCheck.roundTripMatches)
        assertEquals(0, emptyCheck.encodedLength)
    }

    @Test
    fun mp4ContainerValidationDistinguishesValidBoxesFromCorruptPayloads() {
        val validMp4 = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
            'm'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(),
            'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte()
        )
        val validResult = AudioRecorderManager.verifyMp4ContainerBytes(validMp4)
        assertTrue(validResult.isValid)
        assertTrue(validResult.summary.contains("ftyp"))

        val missingFtyp = ByteArray(24) { 0x11 }
        val noHeaderResult = AudioRecorderManager.verifyMp4ContainerBytes(missingFtyp)
        assertFalse(noHeaderResult.isValid)
        assertTrue(noHeaderResult.summary.contains("ftyp"))

        val missingMdat = byteArrayOf(
            0x00, 0x00, 0x00, 0x10,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        val noPayloadResult = AudioRecorderManager.verifyMp4ContainerBytes(missingMdat)
        assertFalse(noPayloadResult.isValid)
        assertTrue(noPayloadResult.summary.contains("mdat"))
    }

    @Test
    fun twoStageAudioPipelineTranscribesThenChatsWithoutShowingTranscriptInUi() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeGateway = FakeJarvisGateway()
        val ttsManager = JarvisTtsManager(app)
        val recorderManager = AudioRecorderManager(app)
        val viewModel = JarvisViewModel(
            application = app,
            repository = fakeGateway,
            ttsManager = ttsManager,
            audioRecorderManager = recorderManager
        )

        val clip = RecordedAudioClip(
            bytes = byteArrayOf(1, 2, 3, 4, 5, 6),
            mimeType = "audio/mp4",
            durationMs = 3_000L,
            base64Length = 8,
            base64Verified = true,
            diagnostics = AudioPipelineDiagnostics(
                recordingDurationMs = 3_000L,
                audioSizeBytes = 6,
                recordedMimeType = "audio/mp4",
                hasNonEmptyAudioData = true,
                base64PayloadLength = 8,
                base64Verified = true,
                geminiSentMimeType = "audio/mp4"
            )
        )
        viewModel.sendRecordedAudioClip(clip)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, fakeGateway.transcribeCallCount)
        assertEquals("audio/mp4", fakeGateway.lastMimeType)
        assertEquals(6, fakeGateway.lastAudioBytes?.size)
        assertEquals(3_000L, fakeGateway.lastSeedDiagnostics?.recordingDurationMs)
        assertFalse(state.isTranscribing)
        assertTrue(state.audioDiagnostics.base64Verified)
        assertEquals(JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID, state.audioDiagnostics.transcriptionModelId)

        assertEquals(1, fakeGateway.sendMessageStreamCallCount)
        assertEquals("Run a telemetry status check in Hyderabad", fakeGateway.lastTextPrompt)
        assertTrue(state.generationState is GenerationUiState.Success)
        assertEquals("All systems are nominal, Sir.", (state.generationState as GenerationUiState.Success).text)

        // Privacy verified: transcript and USER message must NOT appear in chat UI
        assertTrue(state.messages.none { it.role == MessageRole.USER })
        assertTrue(state.messages.none { it.text.contains("Hyderabad") })

        val jarvisReply = state.messages.last { it.role == MessageRole.JARVIS }
        assertEquals("All systems are nominal, Sir.", jarvisReply.text)
        assertFalse(jarvisReply.isStreaming)
        assertEquals(HologramActivityMode.IDLE, state.hologramMode)

        ttsManager.onInit(TextToSpeech.ERROR)
        assertEquals("TTS UNAVAILABLE", ttsManager.state.value.voiceStatusLabel)
        recorderManager.destroy()
        ttsManager.shutdown()
    }

    @Test
    fun audioPreviewStagesBeforeSendingAndSupportsDiscardSubmitAndCleanup() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeGateway = FakeJarvisGateway()
        val ttsManager = JarvisTtsManager(app)
        val recorderManager = AudioRecorderManager(app)
        val previewPlayer = AudioPreviewPlayer()
        val viewModel = JarvisViewModel(
            application = app,
            repository = fakeGateway,
            ttsManager = ttsManager,
            audioRecorderManager = recorderManager,
            audioPreviewPlayer = previewPlayer
        )

        val tempPreviewFile = File.createTempFile("jarvis_preview_test", ".m4a", app.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
        assertTrue(tempPreviewFile.exists())

        val clipToDiscard = RecordedAudioClip(
            bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            mimeType = "audio/mp4",
            durationMs = 2_400L,
            base64Length = 12,
            base64Verified = true,
            maxAmplitudeObserved = 80,
            isLikelySilent = true,
            previewFilePath = tempPreviewFile.absolutePath,
            diagnostics = AudioPipelineDiagnostics(
                recordingDurationMs = 2_400L,
                audioSizeBytes = 8,
                signalTelemetry = "peak=80, avg=40, activePolls=0/30 (LOW / NEAR-SILENT)"
            )
        )

        // Staging for preview must NOT call Gemini automatically
        viewModel.stageAudioClipForPreview(clipToDiscard)
        assertEquals(0, fakeGateway.transcribeCallCount)
        assertNotNull(viewModel.uiState.value.pendingAudioPreview)
        assertTrue(viewModel.uiState.value.pendingAudioPreview!!.isLikelySilent)
        assertEquals(2_400L, viewModel.uiState.value.pendingAudioPreview!!.durationMs)

        // Discarding deletes temp file and never triggers Gemini
        viewModel.discardPendingAudioPreview()
        assertNull(viewModel.uiState.value.pendingAudioPreview)
        assertFalse(tempPreviewFile.exists())
        assertEquals(0, fakeGateway.transcribeCallCount)

        // Staging and submitting sends exactly once and clears pending preview
        val secondTempFile = File.createTempFile("jarvis_preview_submit", ".m4a", app.cacheDir).apply {
            writeBytes(byteArrayOf(9, 8, 7, 6, 5, 4))
        }
        val clipToSubmit = RecordedAudioClip(
            bytes = byteArrayOf(9, 8, 7, 6, 5, 4),
            mimeType = "audio/mp4",
            durationMs = 3_100L,
            base64Length = 8,
            base64Verified = true,
            maxAmplitudeObserved = 9_400,
            isLikelySilent = false,
            previewFilePath = secondTempFile.absolutePath
        )
        viewModel.stageAudioClipForPreview(clipToSubmit)
        viewModel.submitPendingAudioPreview()
        viewModel.submitPendingAudioPreview() // Duplicate submit must be ignored
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingAudioPreview)
        assertEquals(1, fakeGateway.transcribeCallCount)
        assertEquals(1, fakeGateway.sendMessageStreamCallCount)

        // Clearing conversation also deletes any newly staged preview file
        val thirdTempFile = File.createTempFile("jarvis_preview_clear", ".m4a", app.cacheDir).apply {
            writeBytes(byteArrayOf(4, 3, 2, 1))
        }
        viewModel.stageAudioClipForPreview(
            RecordedAudioClip(
                bytes = byteArrayOf(4, 3, 2, 1),
                mimeType = "audio/mp4",
                durationMs = 1_100L,
                previewFilePath = thirdTempFile.absolutePath
            )
        )
        assertNotNull(viewModel.uiState.value.pendingAudioPreview)
        viewModel.clearConversation()
        assertNull(viewModel.uiState.value.pendingAudioPreview)
        assertFalse(thirdTempFile.exists())

        secondTempFile.delete()
        previewPlayer.release()
        recorderManager.destroy()
        ttsManager.shutdown()
    }

    @Test
    fun audioPreviewPlayerHandlesMissingOrNullFilesSafely() {
        val player = AudioPreviewPlayer()
        assertFalse(player.play(null))
        assertFalse(player.state.value.isPlaying)
        assertNotNull(player.state.value.errorMessage)

        player.clearError()
        assertNull(player.state.value.errorMessage)

        assertFalse(player.play("/tmp/non_existent_jarvis_clip_12345.m4a"))
        assertFalse(player.state.value.isPlaying)
        assertTrue(player.state.value.errorMessage!!.contains("missing or empty"))
        player.release()
    }

    @Test
    fun duplicateAudioSubmissionsDoNotSendDuplicateGeminiRequests() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeGateway = FakeJarvisGateway(stage1DelayMs = 500L)
        val ttsManager = JarvisTtsManager(app)
        val recorderManager = AudioRecorderManager(app)
        val viewModel = JarvisViewModel(
            application = app,
            repository = fakeGateway,
            ttsManager = ttsManager,
            audioRecorderManager = recorderManager
        )

        val clip = RecordedAudioClip(byteArrayOf(1, 2, 3, 4), "audio/mp4", 1_500L)
        viewModel.sendRecordedAudioClip(clip)
        advanceTimeBy(100L)
        // Second call while Stage 1 is still running must be ignored
        viewModel.sendRecordedAudioClip(clip)
        advanceUntilIdle()

        assertEquals(1, fakeGateway.transcribeCallCount)
        assertEquals(1, fakeGateway.sendMessageStreamCallCount)

        recorderManager.destroy()
        ttsManager.shutdown()
    }

    @Test
    fun quotaExceeded429NeverRetriesAutomaticallyAndShowsClearMessage() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val quotaException = JarvisRepository.TranscriptionPipelineException(
            causeError = IllegalStateException("429 RESOURCE_EXHAUSTED: Free Tier quota exceeded"),
            diagnostics = AudioPipelineDiagnostics(
                recordingDurationMs = 2_000L,
                audioSizeBytes = 12_000,
                recordedMimeType = "audio/mp4",
                hasNonEmptyAudioData = true,
                base64PayloadLength = 16_000,
                base64Verified = true,
                geminiSentMimeType = "audio/mp4",
                transcriptionModelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
                transcriptionStatus = "HTTP 429 Quota Exceeded",
                rawResponseStructure = "Exception before response",
                actualErrorDetail = "IllegalStateException: 429 RESOURCE_EXHAUSTED: Free Tier quota exceeded"
            )
        )
        val fakeGateway = FakeJarvisGateway(stage1Exception = quotaException)
        val ttsManager = JarvisTtsManager(app)
        val recorderManager = AudioRecorderManager(app)
        val viewModel = JarvisViewModel(
            application = app,
            repository = fakeGateway,
            ttsManager = ttsManager,
            audioRecorderManager = recorderManager
        )

        viewModel.sendRecordedAudioClip(
            RecordedAudioClip(byteArrayOf(9, 8, 7, 6), "audio/mp4", 2_000L)
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, fakeGateway.transcribeCallCount)
        assertEquals(0, fakeGateway.sendMessageStreamCallCount)
        assertTrue(state.generationState is GenerationUiState.Error)
        val err = (state.generationState as GenerationUiState.Error).error
        assertEquals(JarvisErrorType.QUOTA_EXCEEDED, err.type)
        assertFalse(err.canRetry)
        assertFalse(viewModel.canRetryLastCommand())
        assertTrue(err.title.contains("429"))
        assertTrue(err.detail.contains("RESOURCE_EXHAUSTED"))
        assertEquals("HTTP 429 Quota Exceeded", state.audioDiagnostics.transcriptionStatus)

        // Calling retryLastCommand() must NOT re-trigger a 429 request
        viewModel.retryLastCommand()
        advanceUntilIdle()
        assertEquals(1, fakeGateway.transcribeCallCount)

        recorderManager.destroy()
        ttsManager.shutdown()
    }

    @Test
    fun nullTextAndEmptyResponsesHandledSafelyWithoutMisclassifyingBracketedWords() = runTest {
        val nullParsed = JarvisRepository.parseTranscriptionOutput(null)
        assertTrue(nullParsed is TranscriptionResult.NoSpeech)
        assertEquals("Empty Transcription Response", (nullParsed as TranscriptionResult.NoSpeech).title)
        assertEquals("Empty / NULL_TEXT", nullParsed.diagnostics.transcriptionStatus)

        val emptyParsed = JarvisRepository.parseTranscriptionOutput("   ")
        assertTrue(emptyParsed is TranscriptionResult.NoSpeech)
        assertEquals("Empty Transcription Response", (emptyParsed as TranscriptionResult.NoSpeech).title)

        val rejected = listOf("NO_SPEECH", "no_speech.", "[music]", "(silence)", "[noise]", "[inaudible]")
        rejected.forEach { sample ->
            val parsed = JarvisRepository.parseTranscriptionOutput(sample)
            assertTrue("Expected NoSpeech for '$sample'", parsed is TranscriptionResult.NoSpeech)
            assertTrue((parsed as TranscriptionResult.NoSpeech).title.startsWith("No Speech Detected"))
            assertNotNull(parsed.diagnostics.rawSttResponse)
        }

        // Valid single-word bracketed or parenthesized speech must NOT be misclassified as NO_SPEECH
        val bracketedWord = JarvisRepository.parseTranscriptionOutput("[Hello]")
        assertTrue(bracketedWord is TranscriptionResult.Success)
        assertEquals("[Hello]", (bracketedWord as TranscriptionResult.Success).transcript)

        val teluguWord = JarvisRepository.parseTranscriptionOutput("(నమస్కారం)")
        assertTrue(teluguWord is TranscriptionResult.Success)
        assertEquals("(నమస్కారం)", (teluguWord as TranscriptionResult.Success).transcript)

        val accepted = JarvisRepository.parseTranscriptionOutput("\"Check system diagnostics\"")
        assertTrue(accepted is TranscriptionResult.Success)
        assertEquals("Check system diagnostics", (accepted as TranscriptionResult.Success).transcript)
        assertEquals(
            "Listen carefully and transcribe all spoken human words in this audio verbatim. Do not analyze music, tones or frequencies.",
            JarvisSpeechConfig.TRANSCRIPTION_INSTRUCTION
        )
    }

    @Test
    fun networkFailureAllowsRetryWithoutCallingStage2() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeGateway = FakeJarvisGateway(stage1Exception = java.net.SocketTimeoutException("Timed out"))
        val ttsManager = JarvisTtsManager(app)
        val recorderManager = AudioRecorderManager(app)
        val viewModel = JarvisViewModel(
            application = app,
            repository = fakeGateway,
            ttsManager = ttsManager,
            audioRecorderManager = recorderManager
        )

        viewModel.sendRecordedAudioClip(
            RecordedAudioClip(byteArrayOf(9, 8, 7), "audio/mp4", 2_000L)
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, fakeGateway.transcribeCallCount)
        assertEquals(0, fakeGateway.sendMessageStreamCallCount)
        assertTrue(state.generationState is GenerationUiState.Error)
        assertTrue(viewModel.canRetryLastCommand())
        assertEquals(HologramActivityMode.ALERT, state.hologramMode)
        assertTrue(state.messages.none { it.role == MessageRole.USER })

        recorderManager.destroy()
        ttsManager.shutdown()
    }

    @Test
    fun typedTextChatStillCreatesUserAndJarvisMessages() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeGateway = FakeJarvisGateway(replyChunks = listOf("Telemetry ", "confirmed."))
        val ttsManager = JarvisTtsManager(app)
        val recorderManager = AudioRecorderManager(app)
        val viewModel = JarvisViewModel(
            application = app,
            repository = fakeGateway,
            ttsManager = ttsManager,
            audioRecorderManager = recorderManager
        )

        viewModel.onInputChanged("Status report")
        viewModel.sendCurrentInput()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, fakeGateway.transcribeCallCount)
        assertEquals(1, fakeGateway.sendMessageStreamCallCount)
        assertEquals("Status report", fakeGateway.lastTextPrompt)
        val userMessages = state.messages.filter { it.role == MessageRole.USER }
        assertEquals(1, userMessages.size)
        assertEquals("Status report", userMessages.first().text)
        assertFalse(userMessages.first().fromVoice)

        val jarvisReply = state.messages.last { it.role == MessageRole.JARVIS }
        assertEquals("Telemetry confirmed.", jarvisReply.text)
        assertFalse(jarvisReply.isStreaming)

        recorderManager.destroy()
        ttsManager.shutdown()
    }

    @Test
    fun voiceIndicatorStringsAndHologramModesCoverAllFourStages() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("Listening...", app.getString(R.string.status_recording))
        assertEquals("Transcribing...", app.getString(R.string.status_transcribing))
        assertEquals("Thinking...", app.getString(R.string.status_thinking))
        assertEquals("Speaking...", app.getString(R.string.status_speaking))

        assertEquals(HologramActivityMode.RECORDING, JarvisScreenState(isRecording = true).hologramMode)
        assertEquals(HologramActivityMode.TRANSCRIBING, JarvisScreenState(isTranscribing = true).hologramMode)
        assertEquals(HologramActivityMode.THINKING, JarvisScreenState(generationState = GenerationUiState.Loading).hologramMode)
        assertEquals(HologramActivityMode.SPEAKING, JarvisScreenState(isTtsSpeaking = true).hologramMode)
        assertEquals(HologramActivityMode.IDLE, JarvisScreenState().hologramMode)
    }

    @Test
    fun missingPermissionPreventsRecordingWithClearError() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recorder = AudioRecorderManager(app)

        val started = recorder.startRecording()
        assertFalse(started)
        assertFalse(recorder.state.value.isRecording)
        assertTrue(recorder.state.value.errorMessage!!.contains("RECORD_AUDIO"))
        assertTrue(recorder.state.value.lastDiagnostics.micCaptureStatus.contains("DENIED"))
        recorder.destroy()
    }
}
