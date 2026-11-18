package com.example.data

import com.google.firebase.ai.type.FirebaseAIException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class JarvisModelOption(
    val modelId: String,
    val displayName: String,
    val description: String,
    val isSupportedByProject: Boolean = true
) {
    GEMINI_3_8_FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        description = "Tactical Core — Default balanced reasoning and low latency"
    ),
    GEMINI_3_5_FLASH_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        description = "Rapid Core — Ultra-fast conversational telemetry"
    );

    companion object {
        val default: JarvisModelOption = GEMINI_3_8_FLASH

        fun fromModelId(modelId: String): JarvisModelOption? =
            entries.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
    }
}

object JarvisSpeechConfig {
    const val TRANSCRIPTION_MODEL_ID = "gemini-3.5-flash-lite"
    const val TRANSCRIPTION_INSTRUCTION =
        "Listen carefully and transcribe all spoken human words in this audio verbatim. Do not analyze music, tones or frequencies."

    fun isTranscriptionModelSupported(): Boolean =
        JarvisModelOption.fromModelId(TRANSCRIPTION_MODEL_ID)?.isSupportedByProject == true
}

data class AudioPipelineDiagnostics(
    val micCaptureStatus: String = "Not started",
    val recorderLifecycleStatus: String = "Idle",
    val recordingDurationMs: Long = 0L,
    val audioSizeBytes: Int = 0,
    val recordedMimeType: String = "audio/mp4",
    val hasNonEmptyAudioData: Boolean = false,
    val containerValidation: String = "Not checked",
    val signalTelemetry: String = "Not measured",
    val base64PayloadLength: Int = 0,
    val base64Verified: Boolean = false,
    val geminiSentMimeType: String = "audio/mp4",
    val requestStructureSummary: String = "Not sent",
    val transcriptionModelId: String = JarvisSpeechConfig.TRANSCRIPTION_MODEL_ID,
    val transcriptionStatus: String = "Idle",
    val rawResponseStructure: String = "Not run",
    val rawSttResponse: String? = null,
    val actualErrorDetail: String? = null
)

sealed interface TranscriptionResult {
    data class Success(
        val transcript: String,
        val diagnostics: AudioPipelineDiagnostics
    ) : TranscriptionResult

    data class NoSpeech(
        val title: String,
        val reason: String,
        val diagnostics: AudioPipelineDiagnostics
    ) : TranscriptionResult
}

enum class MessageRole {
    USER,
    JARVIS
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val fromVoice: Boolean = false,
    val modelId: String? = null
)

enum class JarvisErrorType(val badge: String) {
    AUTH_APP_CHECK("AUTH / APP CHECK"),
    QUOTA_EXCEEDED("QUOTA LIMIT"),
    NETWORK_TIMEOUT("NETWORK / TIMEOUT"),
    MODEL_UNAVAILABLE("MODEL UNAVAILABLE"),
    VOICE_INPUT("VOICE SUBSYSTEM"),
    UNKNOWN("CORE FAULT")
}

data class JarvisError(
    val type: JarvisErrorType,
    val title: String,
    val detail: String,
    val canRetry: Boolean = true
)

object JarvisErrorMapper {

    fun map(throwable: Throwable, activeModelId: String): JarvisError {
        val rawMessage = buildString {
            append(throwable.message.orEmpty())
            throwable.cause?.message?.let {
                append(" ")
                append(it)
            }
        }
        val normalized = rawMessage.lowercase()

        return when {
            throwable is TimeoutCancellationException ||
                throwable is SocketTimeoutException -> JarvisError(
                type = JarvisErrorType.NETWORK_TIMEOUT,
                title = "Neural Link Timed Out",
                detail = "The Gemini core did not complete transmission within 60 seconds. Verify connectivity and retry.",
                canRetry = true
            )

            throwable is UnknownHostException ||
                throwable is ConnectException ||
                throwable is IOException ||
                normalized.contains("unable to resolve host") ||
                normalized.contains("failed to connect") ||
                normalized.contains("network") ||
                normalized.contains("unavailable") && !normalized.contains("model") -> JarvisError(
                type = JarvisErrorType.NETWORK_TIMEOUT,
                title = "Network Link Interrupted",
                detail = "Unable to reach the Firebase AI Logic endpoint. Check your internet connection and try again.",
                canRetry = true
            )

            normalized.contains("403") ||
                normalized.contains("app check") ||
                normalized.contains("appcheck") ||
                normalized.contains("attestation") ||
                normalized.contains("unauthenticated") ||
                normalized.contains("permission_denied") ||
                normalized.contains("api key") ||
                normalized.contains("401") -> JarvisError(
                type = JarvisErrorType.AUTH_APP_CHECK,
                title = "Authentication / App Check Rejected",
                detail = "Firebase App Check attestation failed ($rawMessage). Ensure debug token registration or reCAPTCHA configuration is valid.",
                canRetry = true
            )

            normalized.contains("429") ||
                normalized.contains("quota") ||
                normalized.contains("resource_exhausted") ||
                normalized.contains("rate limit") -> JarvisError(
                type = JarvisErrorType.QUOTA_EXCEEDED,
                title = "Gemini Quota Exceeded (HTTP 429)",
                detail = "The Free Tier / project quota for model '$activeModelId' was exceeded (RESOURCE_EXHAUSTED). Wait ~60 seconds before sending another request, or continue once quota resets.",
                canRetry = false
            )

            normalized.contains("404") ||
                normalized.contains("model") && (
                    normalized.contains("not found") ||
                        normalized.contains("unsupported") ||
                        normalized.contains("unavailable") ||
                        normalized.contains("deprecated")
                    ) -> JarvisError(
                type = JarvisErrorType.MODEL_UNAVAILABLE,
                title = "Selected Model Unavailable",
                detail = "Model '$activeModelId' is currently unavailable or unsupported on this project. Switch to another supported Gemini core in Diagnostics.",
                canRetry = false
            )

            throwable is FirebaseAIException -> JarvisError(
                type = JarvisErrorType.UNKNOWN,
                title = "Firebase AI Logic Fault",
                detail = throwable.localizedMessage ?: "An unexpected error occurred in Firebase AI Logic.",
                canRetry = true
            )

            else -> JarvisError(
                type = JarvisErrorType.UNKNOWN,
                title = "Unexpected Subsystem Error",
                detail = throwable.localizedMessage ?: "JARVIS encountered an unexpected fault.",
                canRetry = true
            )
        }
    }
}
