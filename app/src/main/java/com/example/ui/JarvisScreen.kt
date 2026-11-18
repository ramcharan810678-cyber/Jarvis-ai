package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.components.JarvisCommandBar
import com.example.ui.components.JarvisDiagnosticsSheet
import com.example.ui.components.JarvisErrorBanner
import com.example.ui.components.JarvisHologramCore
import com.example.ui.components.JarvisMessageBubble
import com.example.ui.components.JarvisQuickCommandsRow
import com.example.ui.components.JarvisTopTelemetryBar
import com.example.ui.components.jarvisHudBackdrop
import com.example.ui.theme.LocalJarvisColors

@Composable
fun JarvisScreen(
    viewModel: JarvisViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val jarvisColors = LocalJarvisColors.current

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startAudioRecording()
        } else {
            viewModel.onVoicePermissionDenied()
        }
    }

    val handleStartRecording = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            viewModel.startAudioRecording()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .jarvisHudBackdrop(
                topColor = MaterialTheme.colorScheme.background,
                bottomColor = MaterialTheme.colorScheme.surface,
                gridColor = jarvisColors.gridLine
            ),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            val isExpandedLayout = maxWidth >= 700.dp

            if (isExpandedLayout) {
                ExpandedJarvisLayout(
                    state = state,
                    canRetryLastCommand = viewModel.canRetryLastCommand(),
                    onOpenDiagnostics = { viewModel.setDiagnosticsSheetVisible(true) },
                    onToggleTts = viewModel::toggleTts,
                    onStopSpeaking = viewModel::stopSpeaking,
                    onClearSession = viewModel::clearConversation,
                    onReplayVoice = viewModel::replayMessage,
                    onQuickCommand = viewModel::sendQuickCommand,
                    onInputChanged = viewModel::onInputChanged,
                    onSendClicked = viewModel::sendCurrentInput,
                    onStartRecording = handleStartRecording,
                    onStopRecording = viewModel::stopAudioRecordingForPreview,
                    onToggleAudioPreview = viewModel::togglePendingAudioPreview,
                    onSubmitAudioPreview = viewModel::submitPendingAudioPreview,
                    onDiscardAudioPreview = viewModel::discardPendingAudioPreview,
                    onRetryError = viewModel::retryLastCommand,
                    onDismissError = viewModel::dismissError
                )
            } else {
                CompactJarvisLayout(
                    state = state,
                    compactReactor = maxHeight < 640.dp || state.messages.size > 3,
                    canRetryLastCommand = viewModel.canRetryLastCommand(),
                    onOpenDiagnostics = { viewModel.setDiagnosticsSheetVisible(true) },
                    onToggleTts = viewModel::toggleTts,
                    onStopSpeaking = viewModel::stopSpeaking,
                    onClearSession = viewModel::clearConversation,
                    onReplayVoice = viewModel::replayMessage,
                    onQuickCommand = viewModel::sendQuickCommand,
                    onInputChanged = viewModel::onInputChanged,
                    onSendClicked = viewModel::sendCurrentInput,
                    onStartRecording = handleStartRecording,
                    onStopRecording = viewModel::stopAudioRecordingForPreview,
                    onToggleAudioPreview = viewModel::togglePendingAudioPreview,
                    onSubmitAudioPreview = viewModel::submitPendingAudioPreview,
                    onDiscardAudioPreview = viewModel::discardPendingAudioPreview,
                    onRetryError = viewModel::retryLastCommand,
                    onDismissError = viewModel::dismissError
                )
            }
        }
    }

    if (state.showDiagnosticsSheet) {
        JarvisDiagnosticsSheet(
            state = state,
            onSelectModel = viewModel::selectModel,
            onDismiss = { viewModel.setDiagnosticsSheetVisible(false) }
        )
    }
}

@Composable
private fun CompactJarvisLayout(
    state: JarvisScreenState,
    compactReactor: Boolean,
    canRetryLastCommand: Boolean,
    onOpenDiagnostics: () -> Unit,
    onToggleTts: () -> Unit,
    onStopSpeaking: () -> Unit,
    onClearSession: () -> Unit,
    onReplayVoice: (String) -> Unit,
    onQuickCommand: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleAudioPreview: () -> Unit,
    onSubmitAudioPreview: () -> Unit,
    onDiscardAudioPreview: () -> Unit,
    onRetryError: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
        ) {
            JarvisTopTelemetryBar(
                state = state,
                onOpenDiagnostics = onOpenDiagnostics,
                onToggleTts = onToggleTts,
                onStopSpeaking = onStopSpeaking,
                onClearSession = onClearSession
            )

            Spacer(modifier = Modifier.height(10.dp))

            JarvisHologramCore(
                mode = state.hologramMode,
                voiceRmsLevel = state.recordingAmplitude,
                coreSize = if (compactReactor) 112.dp else 156.dp,
                showStatusReadout = true,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConversationFeed(
                state = state,
                onReplayVoice = onReplayVoice,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            AnimatedVisibility(
                visible = state.generationState is GenerationUiState.Error,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                val err = (state.generationState as? GenerationUiState.Error)?.error
                if (err != null) {
                    JarvisErrorBanner(
                        error = err,
                        canRetryLastCommand = canRetryLastCommand,
                        onRetry = onRetryError,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onDismiss = onDismissError,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            if (state.messages.size <= 2) {
                JarvisQuickCommandsRow(
                    enabled = !state.isGenerating && !state.isRecording,
                    onSelectCommand = onQuickCommand,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            JarvisCommandBar(
                inputText = state.inputText,
                isGenerating = state.isGenerating,
                isRecording = state.isRecording,
                isTranscribing = state.isTranscribing,
                isProcessingAudio = state.isProcessingAudio,
                isTtsSpeaking = state.isTtsSpeaking,
                pendingAudioPreview = state.pendingAudioPreview,
                isPreviewPlaying = state.isPreviewPlaying,
                audioRecorderStatus = state.audioRecorderStatus,
                onInputChanged = onInputChanged,
                onSendClicked = onSendClicked,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onStopSpeaking = onStopSpeaking,
                onToggleAudioPreview = onToggleAudioPreview,
                onSubmitAudioPreview = onSubmitAudioPreview,
                onDiscardAudioPreview = onDiscardAudioPreview,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun ExpandedJarvisLayout(
    state: JarvisScreenState,
    canRetryLastCommand: Boolean,
    onOpenDiagnostics: () -> Unit,
    onToggleTts: () -> Unit,
    onStopSpeaking: () -> Unit,
    onClearSession: () -> Unit,
    onReplayVoice: (String) -> Unit,
    onQuickCommand: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleAudioPreview: () -> Unit,
    onSubmitAudioPreview: () -> Unit,
    onDiscardAudioPreview: () -> Unit,
    onRetryError: () -> Unit,
    onDismissError: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            JarvisTopTelemetryBar(
                state = state,
                onOpenDiagnostics = onOpenDiagnostics,
                onToggleTts = onToggleTts,
                onStopSpeaking = onStopSpeaking,
                onClearSession = onClearSession
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JarvisHologramCore(
                        mode = state.hologramMode,
                        voiceRmsLevel = state.recordingAmplitude,
                        coreSize = 220.dp,
                        showStatusReadout = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.jarvis_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            JarvisQuickCommandsRow(
                enabled = !state.isGenerating && !state.isRecording,
                onSelectCommand = onQuickCommand,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
        ) {
            ConversationFeed(
                state = state,
                onReplayVoice = onReplayVoice,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            AnimatedVisibility(
                visible = state.generationState is GenerationUiState.Error,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                val err = (state.generationState as? GenerationUiState.Error)?.error
                if (err != null) {
                    JarvisErrorBanner(
                        error = err,
                        canRetryLastCommand = canRetryLastCommand,
                        onRetry = onRetryError,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onDismiss = onDismissError,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            JarvisCommandBar(
                inputText = state.inputText,
                isGenerating = state.isGenerating,
                isRecording = state.isRecording,
                isTranscribing = state.isTranscribing,
                isProcessingAudio = state.isProcessingAudio,
                isTtsSpeaking = state.isTtsSpeaking,
                pendingAudioPreview = state.pendingAudioPreview,
                isPreviewPlaying = state.isPreviewPlaying,
                audioRecorderStatus = state.audioRecorderStatus,
                onInputChanged = onInputChanged,
                onSendClicked = onSendClicked,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onStopSpeaking = onStopSpeaking,
                onToggleAudioPreview = onToggleAudioPreview,
                onSubmitAudioPreview = onSubmitAudioPreview,
                onDiscardAudioPreview = onDiscardAudioPreview,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun ConversationFeed(
    state: JarvisScreenState,
    onReplayVoice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val lastTextLen = state.messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(state.messages.size, lastTextLen) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = state.messages,
            key = { it.id }
        ) { message ->
            JarvisMessageBubble(
                message = message,
                onReplayVoice = onReplayVoice
            )
        }
    }
}
