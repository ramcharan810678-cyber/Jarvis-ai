package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ChatMessage
import com.example.data.JarvisError
import com.example.data.JarvisErrorType
import com.example.data.JarvisModelOption
import com.example.data.MessageRole
import com.example.ui.JarvisScreenState
import com.example.ui.PendingAudioPreviewUi
import com.example.ui.theme.LocalJarvisColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Modifier.jarvisHudBackdrop(
    topColor: Color,
    bottomColor: Color,
    gridColor: Color
): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(topColor, bottomColor)
        )
    )
    val step = 48.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = gridColor.copy(alpha = 0.22f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f
        )
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = gridColor.copy(alpha = 0.22f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
        y += step
    }
}

@Composable
fun JarvisTopTelemetryBar(
    state: JarvisScreenState,
    onOpenDiagnostics: () -> Unit,
    onToggleTts: () -> Unit,
    onStopSpeaking: () -> Unit,
    onClearSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val jarvisColors = LocalJarvisColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = jarvisColors.surfaceGlass,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.jarvis_full_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .testTag("open_diagnostics_button")
                        .minimumInteractiveComponentSize()
                        .clickable(onClick = onOpenDiagnostics)
                        .padding(vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = stringResource(R.string.cd_open_diagnostics),
                        tint = jarvisColors.holoTeal,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = state.selectedModel.modelId,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (state.isTtsSpeaking) {
                    IconButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier
                            .testTag("stop_speaking_button")
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.cd_stop_speaking),
                            tint = jarvisColors.amberAlert
                        )
                    }
                }

                IconButton(
                    onClick = onToggleTts,
                    modifier = Modifier
                        .testTag("toggle_tts_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (state.isTtsEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = stringResource(R.string.cd_toggle_tts),
                        tint = if (state.isTtsEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .testTag("developer_diagnostics_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = stringResource(R.string.cd_developer_diagnostics),
                        tint = jarvisColors.holoTeal
                    )
                }

                IconButton(
                    onClick = onClearSession,
                    modifier = Modifier
                        .testTag("clear_session_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.cd_clear_session),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun JarvisMessageBubble(
    message: ChatMessage,
    onReplayVoice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val jarvisColors = LocalJarvisColors.current
    val borderColor = if (isUser) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    }
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(message.timestampMillis))

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .testTag(if (isUser) "user_message_card" else "jarvis_message_card"),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isUser) "COMMANDER" else "J.A.R.V.I.S.",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        if (message.fromVoice) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = "Voice command",
                                tint = jarvisColors.emeraldActive,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (message.text.isEmpty() && message.isStreaming) {
                        "Synthesizing telemetry response…"
                    } else {
                        message.text
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (!isUser && !message.isStreaming && message.text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message.modelId.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { onReplayVoice(message.text) },
                            modifier = Modifier
                                .testTag("replay_tts_button")
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = stringResource(R.string.cd_replay_voice),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JarvisErrorBanner(
    error: JarvisError,
    canRetryLastCommand: Boolean,
    onRetry: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val jarvisColors = LocalJarvisColors.current
    val accent = when (error.type) {
        JarvisErrorType.QUOTA_EXCEEDED,
        JarvisErrorType.VOICE_INPUT -> jarvisColors.amberAlert
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("jarvis_error_banner"),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = "System alert",
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "${error.type.badge} • ${error.title}",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = error.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .testTag("error_diagnostics_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_open_diagnostics),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .testTag("dismiss_error_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = stringResource(R.string.action_dismiss),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (error.canRetry && canRetryLastCommand) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        border = BorderStroke(1.dp, accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                        modifier = Modifier
                            .testTag("retry_command_button")
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

@Composable
fun JarvisQuickCommandsRow(
    enabled: Boolean,
    onSelectCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val prompts = listOf(
        "Run a quick system status check",
        "Brief me on productivity priorities",
        "Explain quantum computing concisely"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEachIndexed { index, prompt ->
            AssistChip(
                onClick = { onSelectCommand(prompt) },
                enabled = enabled,
                label = {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .testTag("quick_prompt_chip_$index")
                    .minimumInteractiveComponentSize()
            )
        }
    }
}

@Composable
fun JarvisCommandBar(
    inputText: String,
    isGenerating: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    isProcessingAudio: Boolean,
    isTtsSpeaking: Boolean,
    pendingAudioPreview: PendingAudioPreviewUi?,
    isPreviewPlaying: Boolean,
    audioRecorderStatus: String,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStopSpeaking: () -> Unit,
    onToggleAudioPreview: () -> Unit,
    onSubmitAudioPreview: () -> Unit,
    onDiscardAudioPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val jarvisColors = LocalJarvisColors.current
    val isThinking = !isRecording && !isTranscribing && (isProcessingAudio || isGenerating)
    val hasPendingPreview = pendingAudioPreview != null &&
        !isRecording && !isTranscribing && !isThinking && !isTtsSpeaking
    val showStatusStrip = isRecording || isTranscribing || isThinking || isTtsSpeaking || hasPendingPreview
    val statusStripText = when {
        isRecording -> stringResource(R.string.status_recording)
        isTranscribing -> stringResource(R.string.status_transcribing)
        isThinking -> stringResource(R.string.status_thinking)
        isTtsSpeaking -> stringResource(R.string.status_speaking)
        hasPendingPreview && pendingAudioPreview != null -> {
            val sec = String.format(Locale.US, "%.1fs", pendingAudioPreview.durationMs / 1000.0)
            val kb = (pendingAudioPreview.sizeBytes / 1024).coerceAtLeast(1)
            if (pendingAudioPreview.isLikelySilent) {
                "Low signal ($sec • $kb KB) — preview first"
            } else {
                "Voice ready ($sec • $kb KB)"
            }
        }
        else -> audioRecorderStatus
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = showStatusStrip,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val stripColor = when {
                isRecording -> jarvisColors.emeraldActive
                isTranscribing || isThinking -> jarvisColors.amberAlert
                isTtsSpeaking -> jarvisColors.holoTeal
                hasPendingPreview && pendingAudioPreview?.isLikelySilent == true -> jarvisColors.amberAlert
                hasPendingPreview -> MaterialTheme.colorScheme.primary
                else -> jarvisColors.amberAlert
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("audio_recording_strip"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, stripColor)
            ) {
                if (hasPendingPreview) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = stripColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = statusStripText,
                                style = MaterialTheme.typography.labelMedium,
                                color = stripColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("voice_status_indicator_text")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = onToggleAudioPreview,
                                border = BorderStroke(1.dp, jarvisColors.holoTeal),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = jarvisColors.holoTeal
                                ),
                                modifier = Modifier
                                    .testTag("preview_audio_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = if (isPreviewPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isPreviewPlaying) {
                                        stringResource(R.string.action_stop_preview)
                                    } else {
                                        stringResource(R.string.action_preview_audio)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedButton(
                                onClick = onSubmitAudioPreview,
                                border = BorderStroke(1.dp, jarvisColors.emeraldActive),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = jarvisColors.emeraldActive
                                ),
                                modifier = Modifier
                                    .testTag("submit_audio_preview_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.action_send_audio),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }

                            IconButton(
                                onClick = onDiscardAudioPreview,
                                modifier = Modifier
                                    .testTag("discard_audio_preview_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_discard_audio),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = stripColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = statusStripText,
                                style = MaterialTheme.typography.labelMedium,
                                color = stripColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("voice_status_indicator_text")
                            )
                        }
                        if (isRecording) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onStopRecording,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier
                                    .testTag("stop_recording_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.action_stop_recording),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        } else if (isTtsSpeaking && !isTranscribing && !isThinking) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onStopSpeaking,
                                border = BorderStroke(1.dp, jarvisColors.amberAlert),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = jarvisColors.amberAlert
                                ),
                                modifier = Modifier
                                    .testTag("strip_stop_speaking_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.action_stop),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = jarvisColors.surfaceGlass,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(
                width = 1.dp,
                color = when {
                    isRecording -> jarvisColors.emeraldActive
                    isTranscribing || isThinking -> jarvisColors.amberAlert
                    isTtsSpeaking -> jarvisColors.holoTeal
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledIconButton(
                    onClick = {
                        if (isRecording) {
                            onStopRecording()
                        } else {
                            onStartRecording()
                        }
                    },
                    enabled = !isGenerating,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRecording) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (isRecording) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .testTag(if (isRecording) "mic_stop_recording_button" else "start_recording_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isRecording) {
                            stringResource(R.string.cd_stop_recording)
                        } else {
                            stringResource(R.string.cd_start_recording)
                        }
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input_field"),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.input_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !isGenerating) {
                                onSendClicked()
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )

                FilledIconButton(
                    onClick = onSendClicked,
                    enabled = inputText.isNotBlank() && !isGenerating,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .testTag("send_command_button")
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.cd_send_command)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisDiagnosticsSheet(
    state: JarvisScreenState,
    onSelectModel: (JarvisModelOption) -> Unit,
    onDismiss: () -> Unit
) {
    val jarvisColors = LocalJarvisColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val diag = state.audioDiagnostics
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.diagnostics_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticRow("Gemini Transport", "Firebase AI Logic (Keyless Attestation)")
                    DiagnosticRow("Speech STT (Stage 1)", "${diag.transcriptionModelId} (Official Audio STT)")
                    DiagnosticRow("App Check", "Configured in MainActivity")
                    DiagnosticRow("Audio Capture", state.audioRecorderStatus)
                    DiagnosticRow("Text-to-Speech", state.ttsVoiceLabel)
                    DiagnosticRow("History Turns", "${state.messages.size} entries active")
                }
            }

            Text(
                text = stringResource(R.string.dev_diagnostics_title),
                style = MaterialTheme.typography.labelLarge,
                color = jarvisColors.holoTeal
            )

            Surface(
                modifier = Modifier.testTag("developer_audio_diagnostics_card"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, jarvisColors.holoTeal.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticRow("Recording Duration", "${diag.recordingDurationMs} ms")
                    DiagnosticRow("Payload Size", "${diag.audioSizeBytes} bytes")
                    DiagnosticRow("Recorded MIME Type", diag.recordedMimeType)
                    DiagnosticRow("Gemini Sent MIME", diag.geminiSentMimeType)
                    DiagnosticRow("Container & Codec", diag.containerValidation)
                    DiagnosticRow("Signal Level", diag.signalTelemetry)
                    DiagnosticRow(
                        "Non-Empty Payload",
                        if (diag.hasNonEmptyAudioData) "CONFIRMED" else "EMPTY / NOT SENT"
                    )
                    DiagnosticRow(
                        "Base64 Encoding",
                        if (diag.base64Verified) {
                            "VERIFIED (${diag.base64PayloadLength} chars)"
                        } else {
                            "PENDING / NOT VERIFIED"
                        }
                    )
                    DiagnosticRow("Mic & Recorder", "${diag.micCaptureStatus} (${diag.recorderLifecycleStatus})")
                    DiagnosticRow("Request Structure", diag.requestStructureSummary)
                    DiagnosticRow("STT Model", diag.transcriptionModelId)
                    DiagnosticRow("Transcription Status", diag.transcriptionStatus)
                    DiagnosticRow("Raw API Structure", diag.rawResponseStructure)
                    if (!diag.rawSttResponse.isNullOrBlank()) {
                        DiagnosticRow("Raw STT Response", diag.rawSttResponse)
                    }
                    if (!diag.actualErrorDetail.isNullOrBlank()) {
                        DiagnosticRow("Actual Error", diag.actualErrorDetail)
                    }
                }
            }

            Text(
                text = "SUPPORTED GEMINI CORES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            JarvisModelOption.entries.forEach { option ->
                val isSelected = option == state.selectedModel
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_option_${option.modelId}")
                        .clickable {
                            onSelectModel(option)
                            onDismiss()
                        },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${option.modelId} • ${option.description}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Selected model",
                                tint = jarvisColors.emeraldActive
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
