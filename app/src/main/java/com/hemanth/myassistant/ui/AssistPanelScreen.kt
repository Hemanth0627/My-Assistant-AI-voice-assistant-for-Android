package com.hemanth.myassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hemanth.myassistant.model.AssistantStatus
import com.hemanth.myassistant.model.Sender


@Composable
fun AssistPanelScreen(
    assistRequest: Int,
    onClose: () -> Unit,
    onOpenFullApp: () -> Unit
) {
    val viewModel = sharedAssistantViewModel()
    val state by viewModel.uiState.collectAsState()
    KeepScreenOnAlways()
    val context = LocalContext.current

    // ----- Permissions (same behaviour as the full screen) -----
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening() else viewModel.onMicPermissionDenied()
    }

    val actionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(state.permissionRequest) {
        state.permissionRequest?.let { actionPermissionLauncher.launch(it) }
    }

    fun startListeningWithPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) viewModel.startListening()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun onMicClick() {
        when (state.status) {
            AssistantStatus.LISTENING -> viewModel.stopListening()
            AssistantStatus.SPEAKING -> viewModel.stopSpeaking()
            AssistantStatus.PROCESSING -> { /* busy */ }
            else -> startListeningWithPermission()
        }
    }

    // Every power-button press / wake word → start listening.
    // (The ViewModel waits for Vosk to release the microphone first.)
    LaunchedEffect(assistRequest) {
        if (assistRequest > 0 &&
            state.status != AssistantStatus.LISTENING &&
            state.status != AssistantStatus.PROCESSING
        ) {
            viewModel.stopSpeaking()
            startListeningWithPermission()
        }
    }
    // When the panel goes away, don't leave the microphone listening.
    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.uiState.value.status == AssistantStatus.LISTENING) {
                viewModel.cancelListening()
            }
        }
    }

    // Calls/messages/clear still need a tap on the confirmation popup.
    state.confirmation?.let { request ->
        ConfirmationDialog(
            request = request,
            onConfirm = viewModel::onConfirm,
            onCancel = viewModel::onCancelConfirmation
        )
    }

    // ----- What the card shows -----
    val lastUserText = state.messages.lastOrNull { it.sender == Sender.USER }?.text
    val youText = when {
        state.status == AssistantStatus.LISTENING -> state.inputText.ifBlank { "Listening…" }
        else -> lastUserText
    }
    // Only show a reply if it's the newest message (i.e. it answers the last thing you said).
    val replyText = state.messages.lastOrNull()
        ?.takeIf { it.sender == Sender.ASSISTANT }
        ?.text

    val noRipple = remember { MutableInteractionSource() }

    // Dimmed background: tapping it closes the panel.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                // Taps INSIDE the card must not close the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                StatusText(state.status)

                Spacer(Modifier.height(12.dp))

                youText?.let {
                    Text(
                        text = "You: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                replyText?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                state.hint?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(16.dp))

                MicButton(status = state.status, onClick = ::onMicClick)

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onOpenFullApp) { Text("Open full app") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}