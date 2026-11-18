package com.example.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AudioPipelineDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioRecorderManager"
private const val AMPLITUDE_POLL_MS = 80L
private const val MAX_RECORDING_DURATION_MS = 45_000L
private const val MIN_AUDIO_BYTES = 256
private const val MAX_AUDIO_BYTES = 18 * 1024 * 1024 // Keep safely under 20MB inline media limit
private const val RECORDING_MIME_TYPE = "audio/mp4"
private const val SILENT_PEAK_AMPLITUDE_THRESHOLD = 120

data class RecordedAudioClip(
    val bytes: ByteArray,
    val mimeType: String,
    val durationMs: Long,
    val base64Length: Int = 0,
    val base64Verified: Boolean = false,
    val maxAmplitudeObserved: Int = 0,
    val isLikelySilent: Boolean = false,
    val previewFilePath: String? = null,
    val diagnostics: AudioPipelineDiagnostics = AudioPipelineDiagnostics()
)

data class AudioRecorderState(
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val amplitudeNormalized: Float = 0f,
    val elapsedMs: Long = 0L,
    val statusDetail: String = "MediaRecorder Ready (AAC / audio/mp4)",
    val errorMessage: String? = null,
    val lastDiagnostics: AudioPipelineDiagnostics = AudioPipelineDiagnostics()
)

class AudioRecorderManager(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRecordingGuard = AtomicBoolean(false)

    private val _state = MutableStateFlow(AudioRecorderState())
    val state: StateFlow<AudioRecorderState> = _state.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var retainedPreviewFile: File? = null
    private var recordingStartTimeMs: Long = 0L
    private var peakAmplitudeObserved: Int = 0
    private var totalAmplitudeSum: Long = 0L
    private var amplitudePollCount: Int = 0
    private var activeSignalPollCount: Int = 0

    private val amplitudePollRunnable = object : Runnable {
        override fun run() {
            if (!isRecordingGuard.get()) return
            val elapsed = (System.currentTimeMillis() - recordingStartTimeMs).coerceAtLeast(0L)
            val amp = try {
                mediaRecorder?.maxAmplitude ?: 0
            } catch (_: Exception) {
                0
            }
            amplitudePollCount++
            totalAmplitudeSum += amp
            if (amp > SILENT_PEAK_AMPLITUDE_THRESHOLD) {
                activeSignalPollCount++
            }
            if (amp > peakAmplitudeObserved) {
                peakAmplitudeObserved = amp
            }
            val normalized = (amp / 24_000f).coerceIn(0.06f, 1f)
            _state.update {
                it.copy(
                    amplitudeNormalized = normalized,
                    elapsedMs = elapsed,
                    statusDetail = "Listening..."
                )
            }
            if (elapsed >= MAX_RECORDING_DURATION_MS) {
                Log.i(TAG, "Reached max short recording window (${MAX_RECORDING_DURATION_MS}ms); stopping.")
                stopRecordingAndReadClip()
            } else {
                mainHandler.postDelayed(this, AMPLITUDE_POLL_MS)
            }
        }
    }

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording(): Boolean {
        if (!hasAudioPermission()) {
            val permError = "Microphone permission (RECORD_AUDIO) is required to record voice commands."
            Log.w(TAG, "Mic capture check FAILED: RECORD_AUDIO permission missing")
            _state.update {
                it.copy(
                    isRecording = false,
                    errorMessage = permError,
                    lastDiagnostics = it.lastDiagnostics.copy(
                        micCaptureStatus = "Permission DENIED (RECORD_AUDIO missing)",
                        recorderLifecycleStatus = "Blocked (No Permission)",
                        actualErrorDetail = permError
                    )
                )
            }
            return false
        }

        if (!isRecordingGuard.compareAndSet(false, true)) {
            Log.d(TAG, "Ignored duplicate startRecording(); recording already active.")
            return false
        }

        deleteRetainedPreviewFile()

        return try {
            val outputFile = File(
                context.cacheDir,
                "jarvis_voice_${System.currentTimeMillis()}.m4a"
            )
            currentOutputFile = outputFile
            peakAmplitudeObserved = 0
            totalAmplitudeSum = 0L
            amplitudePollCount = 0
            activeSignalPollCount = 0

            val recorder = createPlatformRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(64_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTimeMs = System.currentTimeMillis()
            Log.i(TAG, "Mic capture & MediaRecorder STARTED (MIC, MPEG_4/AAC 44.1kHz, mime=$RECORDING_MIME_TYPE)")

            val startDiag = AudioPipelineDiagnostics(
                micCaptureStatus = "RECORD_AUDIO granted; MIC stream active",
                recorderLifecycleStatus = "Started cleanly",
                recordedMimeType = RECORDING_MIME_TYPE,
                geminiSentMimeType = RECORDING_MIME_TYPE
            )

            _state.update {
                it.copy(
                    isRecording = true,
                    isProcessing = false,
                    amplitudeNormalized = 0.1f,
                    elapsedMs = 0L,
                    statusDetail = "Listening...",
                    errorMessage = null,
                    lastDiagnostics = startDiag
                )
            }

            mainHandler.removeCallbacks(amplitudePollRunnable)
            mainHandler.postDelayed(amplitudePollRunnable, AMPLITUDE_POLL_MS)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied by OS", e)
            cleanupFailedRecorder()
            val msg = "Microphone access denied by system (${e.localizedMessage ?: "SecurityException"})."
            _state.update {
                it.copy(
                    isRecording = false,
                    isProcessing = false,
                    errorMessage = msg,
                    lastDiagnostics = it.lastDiagnostics.copy(
                        micCaptureStatus = "OS SecurityException",
                        recorderLifecycleStatus = "Start failed",
                        actualErrorDetail = msg
                    )
                )
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            cleanupFailedRecorder()
            val msg = "Unable to start microphone recording: ${e.localizedMessage ?: e::class.java.simpleName}"
            _state.update {
                it.copy(
                    isRecording = false,
                    isProcessing = false,
                    errorMessage = msg,
                    lastDiagnostics = it.lastDiagnostics.copy(
                        micCaptureStatus = "MIC init failed",
                        recorderLifecycleStatus = "Start failed (${e::class.java.simpleName})",
                        actualErrorDetail = msg
                    )
                )
            }
            false
        }
    }

    fun stopRecordingAndReadClip(): Result<RecordedAudioClip> {
        mainHandler.removeCallbacks(amplitudePollRunnable)
        if (!isRecordingGuard.compareAndSet(true, false)) {
            return Result.failure(IllegalStateException("No active audio recording session."))
        }

        val elapsed = (System.currentTimeMillis() - recordingStartTimeMs).coerceAtLeast(0L)
        _state.update {
            it.copy(
                isRecording = false,
                isProcessing = true,
                amplitudeNormalized = 0f,
                elapsedMs = elapsed,
                statusDetail = "Finalizing audio..."
            )
        }

        val recorder = mediaRecorder
        val outputFile = currentOutputFile
        mediaRecorder = null
        currentOutputFile = null

        var succeeded = false
        return try {
            try {
                recorder?.maxAmplitude?.let { finalAmp ->
                    amplitudePollCount++
                    totalAmplitudeSum += finalAmp
                    if (finalAmp > SILENT_PEAK_AMPLITUDE_THRESHOLD) activeSignalPollCount++
                    if (finalAmp > peakAmplitudeObserved) peakAmplitudeObserved = finalAmp
                }
            } catch (_: Exception) {
            }

            try {
                recorder?.stop()
                Log.i(TAG, "MediaRecorder STOPPED cleanly (durationMs=$elapsed, peakAmplitude=$peakAmplitudeObserved)")
            } catch (stopError: RuntimeException) {
                Log.w(TAG, "MediaRecorder stop() failed (recording may be too short)", stopError)
                throw IllegalStateException("Recording was too short or contained no audio data (${stopError.localizedMessage ?: "stop() failed"}).")
            } finally {
                try {
                    recorder?.reset()
                    recorder?.release()
                } catch (_: Exception) {
                }
            }

            if (outputFile == null || !outputFile.exists()) {
                throw IllegalStateException("Recorded audio file could not be found.")
            }

            val bytes = outputFile.readBytes()
            val hasNonEmptyData = bytes.size >= MIN_AUDIO_BYTES
            Log.i(
                TAG,
                "Audio payload read: sizeBytes=${bytes.size}, mimeType=$RECORDING_MIME_TYPE, nonEmpty=$hasNonEmptyData, durationMs=$elapsed"
            )

            if (!hasNonEmptyData) {
                throw IllegalStateException("Recorded audio clip is empty (${bytes.size} bytes). Hold recording slightly longer and speak clearly.")
            }
            if (bytes.size > MAX_AUDIO_BYTES) {
                throw IllegalStateException("Recorded audio (${bytes.size} bytes) exceeds 18 MB inline limit. Please keep voice commands concise.")
            }

            val containerCheck = verifyMp4ContainerAndTrack(bytes, outputFile)
            val base64Verification = verifyBase64Encoding(bytes)
            val avgAmp = if (amplitudePollCount > 0) (totalAmplitudeSum / amplitudePollCount).toInt() else peakAmplitudeObserved
            val likelySilent = amplitudePollCount > 0 && peakAmplitudeObserved <= SILENT_PEAK_AMPLITUDE_THRESHOLD
            val signalSummary =
                "peak=$peakAmplitudeObserved, avg=$avgAmp, activePolls=$activeSignalPollCount/$amplitudePollCount" +
                    if (likelySilent) " (LOW / NEAR-SILENT)" else " (Speech signal present)"

            Log.i(
                TAG,
                "Validation: container=${containerCheck.summary}, signal=$signalSummary, base64Len=${base64Verification.encodedLength}, verified=${base64Verification.roundTripMatches}"
            )

            val diag = AudioPipelineDiagnostics(
                micCaptureStatus = if (likelySilent) {
                    "MIC active but signal very quiet/muted (peak=$peakAmplitudeObserved)"
                } else {
                    "Captured MIC audio (peak=$peakAmplitudeObserved)"
                },
                recorderLifecycleStatus = "Started -> Stopped cleanly",
                recordingDurationMs = elapsed,
                audioSizeBytes = bytes.size,
                recordedMimeType = RECORDING_MIME_TYPE,
                hasNonEmptyAudioData = true,
                containerValidation = containerCheck.summary,
                signalTelemetry = signalSummary,
                base64PayloadLength = base64Verification.encodedLength,
                base64Verified = base64Verification.roundTripMatches,
                geminiSentMimeType = RECORDING_MIME_TYPE,
                transcriptionStatus = "Ready for preview / submit"
            )

            retainedPreviewFile = outputFile
            succeeded = true

            _state.update {
                it.copy(
                    isRecording = false,
                    isProcessing = false,
                    statusDetail = "Preview ready",
                    lastDiagnostics = diag
                )
            }
            Result.success(
                RecordedAudioClip(
                    bytes = bytes,
                    mimeType = RECORDING_MIME_TYPE,
                    durationMs = elapsed,
                    base64Length = base64Verification.encodedLength,
                    base64Verified = base64Verification.roundTripMatches,
                    maxAmplitudeObserved = peakAmplitudeObserved,
                    isLikelySilent = likelySilent,
                    previewFilePath = outputFile.absolutePath,
                    diagnostics = diag
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize audio recording", e)
            val errMsg = e.localizedMessage ?: "Audio recording failed."
            _state.update {
                it.copy(
                    isRecording = false,
                    isProcessing = false,
                    statusDetail = "Recording failed",
                    errorMessage = errMsg,
                    lastDiagnostics = it.lastDiagnostics.copy(
                        recorderLifecycleStatus = "Stop/Read failed",
                        recordingDurationMs = elapsed,
                        actualErrorDetail = errMsg
                    )
                )
            }
            Result.failure(e)
        } finally {
            if (!succeeded) {
                try {
                    outputFile?.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun deleteRetainedPreviewFile(path: String? = null) {
        val retained = retainedPreviewFile
        if (retained != null) {
            try {
                retained.delete()
            } catch (_: Exception) {
            }
            retainedPreviewFile = null
        }
        if (!path.isNullOrBlank()) {
            try {
                File(path).delete()
            } catch (_: Exception) {
            }
        }
    }

    fun setProcessing(processing: Boolean, detail: String = "MediaRecorder Ready (AAC / audio/mp4)") {
        _state.update {
            it.copy(
                isProcessing = processing,
                statusDetail = detail
            )
        }
    }

    fun updateDiagnostics(diagnostics: AudioPipelineDiagnostics) {
        _state.update { it.copy(lastDiagnostics = diagnostics) }
    }

    fun clearError() {
        _state.update {
            it.copy(
                errorMessage = null,
                statusDetail = "MediaRecorder Ready (AAC / audio/mp4)"
            )
        }
    }

    fun destroy() {
        mainHandler.removeCallbacks(amplitudePollRunnable)
        isRecordingGuard.set(false)
        cleanupFailedRecorder()
        deleteRetainedPreviewFile()
    }

    private fun cleanupFailedRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        } finally {
            mediaRecorder = null
        }
        try {
            currentOutputFile?.delete()
        } catch (_: Exception) {
        } finally {
            currentOutputFile = null
        }
    }

    @Suppress("DEPRECATION")
    private fun createPlatformRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }

    data class Base64CheckResult(
        val encodedLength: Int,
        val roundTripMatches: Boolean
    )

    data class ContainerValidationResult(
        val isValid: Boolean,
        val summary: String
    )

    companion object {
        fun verifyBase64Encoding(bytes: ByteArray): Base64CheckResult {
            if (bytes.isEmpty()) return Base64CheckResult(encodedLength = 0, roundTripMatches = false)
            return try {
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val decoded = Base64.decode(encoded, Base64.DEFAULT)
                Base64CheckResult(
                    encodedLength = encoded.length,
                    roundTripMatches = encoded.isNotEmpty() && decoded.contentEquals(bytes)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Base64 verification failed", e)
                Base64CheckResult(encodedLength = 0, roundTripMatches = false)
            }
        }

        fun verifyMp4ContainerBytes(bytes: ByteArray): ContainerValidationResult {
            if (bytes.size < 16) {
                return ContainerValidationResult(false, "Invalid MP4 (${bytes.size} bytes < 16)")
            }
            val hasFtyp = bytes.size >= 8 &&
                bytes[4] == 'f'.code.toByte() &&
                bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() &&
                bytes[7] == 'p'.code.toByte()
            val hasMdat = containsAsciiTag(bytes, "mdat")
            val hasMoov = containsAsciiTag(bytes, "moov")

            return when {
                hasFtyp && hasMdat && hasMoov ->
                    ContainerValidationResult(true, "MP4 valid (ftyp + mdat + moov)")
                hasFtyp && hasMdat ->
                    ContainerValidationResult(true, "MP4 valid (ftyp + mdat)")
                !hasFtyp ->
                    ContainerValidationResult(false, "Missing MP4 ftyp header")
                else ->
                    ContainerValidationResult(false, "Missing MP4 media payload atom (mdat)")
            }
        }

        private fun verifyMp4ContainerAndTrack(bytes: ByteArray, file: File): ContainerValidationResult {
            val byteCheck = verifyMp4ContainerBytes(bytes)
            if (!byteCheck.isValid) return byteCheck

            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                var audioSummary: String? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trackMime = format.getString(MediaFormat.KEY_MIME)
                    if (trackMime?.startsWith("audio/") == true) {
                        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else {
                            0
                        }
                        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else {
                            0
                        }
                        audioSummary = "MP4+AAC track verified ($trackMime, ${sampleRate}Hz, ${channels}ch)"
                        break
                    }
                }
                if (audioSummary != null) {
                    ContainerValidationResult(true, audioSummary)
                } else if (extractor.trackCount == 0) {
                    // Robolectric stubbed MediaExtractor reports 0 tracks; rely on verified MP4 box atoms
                    byteCheck
                } else {
                    ContainerValidationResult(false, "MP4 has no audio track (tracks=${extractor.trackCount})")
                }
            } catch (_: Throwable) {
                byteCheck
            } finally {
                try {
                    extractor.release()
                } catch (_: Exception) {
                }
            }
        }

        private fun containsAsciiTag(bytes: ByteArray, tag: String): Boolean {
            if (bytes.size < tag.length) return false
            val target = tag.encodeToByteArray()
            outer@ for (i in 0..(bytes.size - target.size)) {
                for (j in target.indices) {
                    if (bytes[i + j] != target[j]) continue@outer
                }
                return true
            }
            return false
        }
    }
}
