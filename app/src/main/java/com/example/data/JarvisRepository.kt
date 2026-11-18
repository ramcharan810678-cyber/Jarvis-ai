package com.example.data

import android.util.Log
import com.example.voice.AudioRecorderManager
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

private const val TAG = "JarvisRepository"

interface JarvisAiGateway {
    fun isModelSupported(modelId: String): Boolean

    fun switchModel(
        newModel: JarvisModelOption,
        existingMessages: List<ChatMessage>
    )

    fun resetSession()

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        seedDiagnostics: AudioPipelineDiagnostics = AudioPipelineDiagnostics()
    ): TranscriptionResult

    fun sendMessageStream(
        prompt: String,
        priorMessages: List<ChatMessage>,
        modelOption: JarvisModelOption
    ): Flow<String>
}

class JarvisRepository : JarvisAiGateway {

    private var activeModelOption: JarvisModelOption = JarvisModelOption.default
    private var activeModel: GenerativeModel? = null
    private var activeChat: Chat? = null

    private val transcriptionModel: GenerativeModel by lazy {
        val config = generationConfig {
            temperature = 0.0f
            topP = 0.9f
            maxOutputTokens = 1024
        }
        Firebase.ai.generativeModel(
            modelName = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
            generationConfig = config
        )
    }

    private val jarvisSystemInstruction: Content by lazy {
        content {
            text(
                """
                You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), a composed, articulate,
                highly capable tactical AI assistant.
                Respond in a calm, refined, helpful, and subtly witty tone.
                Keep answers clear, structured, and concise enough to sound natural when spoken aloud
                via text-to-speech, while remaining thorough and accurate for technical or everyday tasks.
                Avoid excessive markdown clutter such as decorative asterisks or excessive headings unless
                code or structured steps are specifically requested.
                """.trimIndent()
            )
        }
    }

    private fun getOrCreateModel(modelOption: JarvisModelOption): GenerativeModel {
        val existing = activeModel
        if (existing != null && activeModelOption == modelOption) {
            return existing
        }
        val config = generationConfig {
            temperature = 0.7f
            topP = 0.95f
            maxOutputTokens = 2048
        }
        val created = Firebase.ai.generativeModel(
            modelName = modelOption.modelId,
            generationConfig = config,
            systemInstruction = jarvisSystemInstruction
        )
        activeModelOption = modelOption
        activeModel = created
        return created
    }

    override fun isModelSupported(modelId: String): Boolean {
        return JarvisModelOption.fromModelId(modelId)?.isSupportedByProject == true
    }

    override fun switchModel(
        newModel: JarvisModelOption,
        existingMessages: List<ChatMessage>
    ) {
        if (!newModel.isSupportedByProject) {
            throw IllegalArgumentException("Model ${newModel.modelId} is not supported by this project.")
        }
        activeModelOption = newModel
        activeModel = null
        activeChat = null
        ensureChatSession(existingMessages)
    }

    override fun resetSession() {
        activeChat = null
    }

    private fun ensureChatSession(priorMessages: List<ChatMessage>): Chat {
        val currentChat = activeChat
        if (currentChat != null) return currentChat

        val model = getOrCreateModel(activeModelOption)
        val history = priorMessages
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .map { msg ->
                val role = if (msg.role == MessageRole.USER) "user" else "model"
                content(role) { text(msg.text) }
            }

        val chat = model.startChat(history = history)
        activeChat = chat
        return chat
    }

    override suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        seedDiagnostics: AudioPipelineDiagnostics
    ): TranscriptionResult {
        val modelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID
        val resolvedMimeType = mimeType.trim().ifBlank { "audio/mp4" }
        val base64Check = AudioRecorderManager.verifyBase64Encoding(audioBytes)
        val containerCheck = if (seedDiagnostics.containerValidation == "Not checked" && audioBytes.size >= 16) {
            AudioRecorderManager.verifyMp4ContainerBytes(audioBytes)
        } else {
            null
        }
        var diag = seedDiagnostics.copy(
            audioSizeBytes = audioBytes.size,
            recordedMimeType = resolvedMimeType,
            hasNonEmptyAudioData = audioBytes.isNotEmpty(),
            containerValidation = containerCheck?.summary ?: seedDiagnostics.containerValidation,
            base64PayloadLength = base64Check.encodedLength,
            base64Verified = base64Check.roundTripMatches,
            geminiSentMimeType = resolvedMimeType,
            transcriptionModelId = modelId
        )

        if (!JarvisSpeechConfig.isTranscriptionModelSupported()) {
            val detail = "Selected transcription model '$modelId' is not supported by this Gemini API configuration."
            return TranscriptionResult.NoSpeech(
                title = "Unsupported Transcription Model",
                reason = detail,
                diagnostics = diag.copy(
                    transcriptionStatus = "Model unsupported",
                    actualErrorDetail = detail
                )
            )
        }

        if (audioBytes.isEmpty()) {
            val detail = "Recorded audio payload is 0 bytes."
            return TranscriptionResult.NoSpeech(
                title = "Empty Audio Payload",
                reason = detail,
                diagnostics = diag.copy(
                    transcriptionStatus = "Empty audio payload",
                    actualErrorDetail = detail
                )
            )
        }

        if (!resolvedMimeType.startsWith("audio/")) {
            val detail = "Invalid audio MIME type '$resolvedMimeType'."
            return TranscriptionResult.NoSpeech(
                title = "Invalid Audio MIME Type",
                reason = detail,
                diagnostics = diag.copy(
                    transcriptionStatus = "Invalid MIME type",
                    actualErrorDetail = detail
                )
            )
        }

        if (!base64Check.roundTripMatches) {
            val detail = "Base64 verification failed for ${audioBytes.size}-byte audio payload."
            return TranscriptionResult.NoSpeech(
                title = "Audio Encoding Error",
                reason = detail,
                diagnostics = diag.copy(
                    transcriptionStatus = "Base64 check failed",
                    actualErrorDetail = detail
                )
            )
        }

        val transcriptionPrompt = """
            ${JarvisSpeechConfig.TRANSCRIPTION_INSTRUCTION}
            Primary languages: English (en), Indian English (en-IN), Telugu (te).
            Instructions:
            - Transcribe all spoken words accurately, including quiet, fast, or accented speech.
            - Keep the speaker's exact words without translating, summarizing, or adding commentary.
            - Do not describe background noise, music, tones, or frequencies.
            - Only respond with NO_SPEECH if the entire clip is completely silent or contains zero spoken human words.
        """.trimIndent()

        val requestSummary =
            "role=user | parts=[InlineDataPart(mime=$resolvedMimeType, bytes=${audioBytes.size}, base64Len=${base64Check.encodedLength}), TextPart(chars=${transcriptionPrompt.length})]"
        diag = diag.copy(requestStructureSummary = requestSummary)

        val transcriptionRequest = content("user") {
            inlineData(bytes = audioBytes, mimeType = resolvedMimeType)
            text(transcriptionPrompt)
        }

        Log.i(TAG, "Sending single Stage 1 STT request [$modelId]: $requestSummary")

        // Single Gemini request — never send duplicate or automatic retry requests
        return try {
            val response = withTimeout(60_000L) {
                transcriptionModel.generateContent(transcriptionRequest)
            }
            val meta = inspectRawResponse(modelId, response)
            when (val parsed = classifyTranscriptionResponse(meta.extractedText, meta.structureSummary)) {
                is ParsedTranscriptOutcome.ValidSpeech -> TranscriptionResult.Success(
                    transcript = parsed.transcript,
                    diagnostics = diag.copy(
                        transcriptionStatus = "Success (${parsed.transcript.length} chars)",
                        rawResponseStructure = meta.structureSummary,
                        rawSttResponse = null, // Keep spoken words private in diagnostics as well
                        actualErrorDetail = null
                    )
                )

                is ParsedTranscriptOutcome.EmptyResponse -> {
                    val detail = "Gemini ($modelId) returned empty transcription text. ${meta.structureSummary}"
                    TranscriptionResult.NoSpeech(
                        title = "Empty Transcription Response",
                        reason = detail,
                        diagnostics = diag.copy(
                            transcriptionStatus = "Empty / NULL_TEXT",
                            rawResponseStructure = meta.structureSummary,
                            rawSttResponse = meta.extractedText ?: "<null>",
                            actualErrorDetail = detail
                        )
                    )
                }

                is ParsedTranscriptOutcome.ExplicitNoSpeech -> {
                    val detail = "Model ($modelId) returned '${parsed.rawToken}' (${meta.structureSummary}). Try previewing your recording to confirm audible speech."
                    TranscriptionResult.NoSpeech(
                        title = "No Speech Detected (${parsed.rawToken})",
                        reason = detail,
                        diagnostics = diag.copy(
                            transcriptionStatus = "NO_SPEECH (${parsed.rawToken})",
                            rawResponseStructure = meta.structureSummary,
                            rawSttResponse = meta.extractedText ?: parsed.rawToken,
                            actualErrorDetail = detail
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val rawErr = "${e::class.java.simpleName}: ${e.localizedMessage ?: e.message}"
            Log.e(TAG, "Stage 1 transcription ($modelId) failed: $rawErr", e)
            throw TranscriptionPipelineException(
                causeError = e,
                diagnostics = diag.copy(
                    transcriptionStatus = if (rawErr.contains("429") || rawErr.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) {
                        "HTTP 429 Quota Exceeded"
                    } else {
                        "API Error"
                    },
                    rawResponseStructure = "Exception before response",
                    rawSttResponse = rawErr,
                    actualErrorDetail = rawErr
                )
            )
        }
    }

    private fun inspectRawResponse(modelId: String, response: GenerateContentResponse): RawResponseMeta {
        val candidates = response.candidates
        val firstCandidate = candidates.firstOrNull()
        val parts = firstCandidate?.content?.parts.orEmpty()
        val partTypes = if (parts.isEmpty()) "none" else parts.joinToString(",") { it::class.java.simpleName }
        val textFromParts = parts
            .filterIsInstance<TextPart>()
            .joinToString(separator = "") { it.text }
            .ifBlank { null }
        val extractedText = textFromParts ?: response.text
        val finishReason = firstCandidate?.finishReason?.toString() ?: "NONE"
        val blockReason = response.promptFeedback?.blockReason?.toString() ?: "NONE"
        val textLabel = when {
            extractedText == null -> "NULL_TEXT"
            extractedText.isBlank() -> "EMPTY_TEXT"
            isExplicitNoSpeech(extractedText) -> "RAW='$extractedText'"
            else -> "OK(${extractedText.trim().length} chars)"
        }
        val structureSummary =
            "$textLabel | candidates=${candidates.size}, parts=[$partTypes], finishReason=$finishReason, blockReason=$blockReason"
        Log.i(TAG, "Raw Gemini STT response [$modelId]: $structureSummary")
        return RawResponseMeta(
            extractedText = extractedText,
            structureSummary = structureSummary
        )
    }

    override fun sendMessageStream(
        prompt: String,
        priorMessages: List<ChatMessage>,
        modelOption: JarvisModelOption
    ): Flow<String> = flow {
        if (!modelOption.isSupportedByProject) {
            throw IllegalStateException("Selected model ${modelOption.modelId} is unavailable or unsupported.")
        }
        if (activeModelOption != modelOption) {
            activeModelOption = modelOption
            activeModel = null
            activeChat = null
        }

        val chat = ensureChatSession(priorMessages)
        withTimeout(60_000L) {
            chat.sendMessageStream(prompt).collect { chunk ->
                val delta = chunk.text.orEmpty()
                if (delta.isNotEmpty()) {
                    emit(delta)
                }
            }
        }
    }

    private data class RawResponseMeta(
        val extractedText: String?,
        val structureSummary: String
    )

    class TranscriptionPipelineException(
        val causeError: Throwable,
        val diagnostics: AudioPipelineDiagnostics
    ) : RuntimeException(causeError.message, causeError)

    sealed interface ParsedTranscriptOutcome {
        data class ValidSpeech(val transcript: String) : ParsedTranscriptOutcome
        data class EmptyResponse(val rawSummary: String) : ParsedTranscriptOutcome
        data class ExplicitNoSpeech(val rawToken: String) : ParsedTranscriptOutcome
    }

    companion object {
        private val nonSpeechTokens = setOf(
            "no_speech",
            "no_speech.",
            "no speech",
            "[no speech]",
            "(no speech)",
            "[no_speech]",
            "(no_speech)",
            "[silence]",
            "(silence)",
            "[inaudible]",
            "(inaudible)",
            "[noise]",
            "(noise)",
            "[music]",
            "(music)",
            "[blank_audio]"
        )

        internal fun isExplicitNoSpeech(text: String): Boolean {
            val normalized = text.trim().removeSurrounding("\"").trim().lowercase()
            return normalized in nonSpeechTokens
        }

        internal fun classifyTranscriptionResponse(
            rawText: String?,
            rawSummary: String = ""
        ): ParsedTranscriptOutcome {
            val cleaned = rawText
                ?.trim()
                ?.removeSurrounding("\"")
                ?.trim()
                .orEmpty()

            if (cleaned.isBlank()) {
                return ParsedTranscriptOutcome.EmptyResponse(
                    rawSummary.ifBlank { "NULL_TEXT / empty transcription response" }
                )
            }

            if (isExplicitNoSpeech(cleaned)) {
                return ParsedTranscriptOutcome.ExplicitNoSpeech(cleaned)
            }

            return ParsedTranscriptOutcome.ValidSpeech(cleaned)
        }

        internal fun parseTranscriptionOutput(rawText: String?): TranscriptionResult {
            return when (val outcome = classifyTranscriptionResponse(rawText)) {
                is ParsedTranscriptOutcome.ValidSpeech -> TranscriptionResult.Success(
                    transcript = outcome.transcript,
                    diagnostics = AudioPipelineDiagnostics(
                        transcriptionModelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
                        transcriptionStatus = "Success (${outcome.transcript.length} chars)"
                    )
                )
                is ParsedTranscriptOutcome.EmptyResponse -> TranscriptionResult.NoSpeech(
                    title = "Empty Transcription Response",
                    reason = outcome.rawSummary,
                    diagnostics = AudioPipelineDiagnostics(
                        transcriptionModelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
                        transcriptionStatus = "Empty / NULL_TEXT",
                        rawSttResponse = rawText ?: "<null>",
                        actualErrorDetail = outcome.rawSummary
                    )
                )
                is ParsedTranscriptOutcome.ExplicitNoSpeech -> TranscriptionResult.NoSpeech(
                    title = "No Speech Detected (${outcome.rawToken})",
                    reason = "Model reported '${outcome.rawToken}'",
                    diagnostics = AudioPipelineDiagnostics(
                        transcriptionModelId = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
                        transcriptionStatus = "NO_SPEECH (${outcome.rawToken})",
                        rawSttResponse = outcome.rawToken,
                        actualErrorDetail = outcome.rawToken
                    )
                )
            }
        }
    }
}
