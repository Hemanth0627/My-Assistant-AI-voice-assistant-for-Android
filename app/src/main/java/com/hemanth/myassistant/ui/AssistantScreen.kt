package com.hemanth.myassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hemanth.myassistant.R
import com.hemanth.myassistant.model.AssistantStatus
import com.hemanth.myassistant.model.ChatMessage
import com.hemanth.myassistant.model.Sender

@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel = sharedAssistantViewModel(),
    assistRequest: Int = 0
) {
    val state by viewModel.uiState.collectAsState()
    KeepScreenOnWhileActive(state.status, state.confirmation != null)
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // ------------------------------------------------------------
    // MICROPHONE PERMISSION
    // ------------------------------------------------------------

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startListening()
        } else {
            viewModel.onMicPermissionDenied()
        }
    }

    // ------------------------------------------------------------
    // ACTION PERMISSIONS
    // Contacts and phone calls.
    // ------------------------------------------------------------

    val actionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    LaunchedEffect(state.permissionRequest) {
        state.permissionRequest?.let { permission ->
            actionPermissionLauncher.launch(permission)
        }
    }

    // ------------------------------------------------------------
    // START LISTENING WITH PERMISSION
    // ------------------------------------------------------------

    fun startListeningWithPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            viewModel.startListening()
        } else {
            micPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    // ------------------------------------------------------------
    // MICROPHONE BUTTON
    // ------------------------------------------------------------

    fun onMicClick() {
        when (state.status) {

            AssistantStatus.LISTENING -> {
                viewModel.stopListening()
            }

            AssistantStatus.SPEAKING -> {
                viewModel.stopSpeaking()
            }

            AssistantStatus.PROCESSING -> {
                // Assistant is thinking.
                // Ignore mic taps.
            }

            else -> {
                startListeningWithPermission()
            }
        }
    }

    // ------------------------------------------------------------
    // ASSISTANT / POWER-BUTTON GESTURE
    // ------------------------------------------------------------

    LaunchedEffect(assistRequest) {
        if (
            assistRequest > 0 &&
            state.status != AssistantStatus.LISTENING &&
            state.status != AssistantStatus.PROCESSING
        ) {
            // Stop any current TTS speech and begin listening.
            viewModel.stopSpeaking()
            startListeningWithPermission()
        }
    }

    // ------------------------------------------------------------
    // AUTO-SCROLL
    // ------------------------------------------------------------

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(
                state.messages.lastIndex
            )
        }
    }

    // ------------------------------------------------------------
    // CONFIRMATION DIALOG
    // ------------------------------------------------------------

    state.confirmation?.let { request ->
        ConfirmationDialog(
            request = request,
            onConfirm = viewModel::onConfirm,
            onCancel = viewModel::onCancelConfirmation
        )
    }

    // ------------------------------------------------------------
    // MAIN SCREEN
    // ------------------------------------------------------------

    Scaffold { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {

            // ----------------------------------------------------
            // TITLE + CLEAR CONVERSATION BUTTON
            // ----------------------------------------------------

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {

                Text(
                    text = "My Assistant",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.Center)
                )

                IconButton(
                    onClick = viewModel::onClearConversationClick,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        painter = painterResource(
                            R.drawable.ic_delete
                        ),
                        contentDescription = "Clear conversation"
                    )
                }
            }

            // ----------------------------------------------------
            // STATUS
            // ----------------------------------------------------

            StatusText(
                state.status

            )
            WakeWordToggle()

            // ----------------------------------------------------
            // CONVERSATION
            // ----------------------------------------------------

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    vertical = 12.dp
                )
            ) {

                items(
                    state.messages,
                    key = { it.id }
                ) { message ->

                    MessageBubble(message)
                }
            }

            // ----------------------------------------------------
            // MICROPHONE
            // ----------------------------------------------------

            MicButton(
                status = state.status,
                onClick = ::onMicClick,
                modifier = Modifier.align(
                    Alignment.CenterHorizontally
                )
            )

            // ----------------------------------------------------
            // ERROR / HELP MESSAGE
            // ----------------------------------------------------

            state.hint?.let { hint ->

                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            // ----------------------------------------------------
            // OPEN APP SETTINGS
            // ----------------------------------------------------

            if (state.showOpenSettings) {

                TextButton(
                    onClick = {
                        openAppSettings(context)
                    },
                    modifier = Modifier.align(
                        Alignment.CenterHorizontally
                    )
                ) {

                    Text("Open app settings")
                }
            }

            Spacer(
                Modifier.height(12.dp)
            )

            // ----------------------------------------------------
            // TEXT INPUT
            // ----------------------------------------------------

            InputBar(
                text = state.inputText,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::onSend
            )

            Spacer(
                Modifier.height(12.dp)
            )
        }
    }
}


// ================================================================
// APP SETTINGS
// ================================================================

private fun openAppSettings(
    context: Context
) {

    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts(
            "package",
            context.packageName,
            null
        )
    ).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK
    )

    context.startActivity(intent)
}


// ================================================================
// STATUS
// ================================================================

@Composable
fun StatusText(
    status: AssistantStatus
) {

    val label = when (status) {

        AssistantStatus.IDLE ->
            "● Ready"

        AssistantStatus.LISTENING ->
            "◉ Listening..."

        AssistantStatus.PROCESSING ->
            "… Thinking"

        AssistantStatus.SPEAKING ->
            "🔊 Speaking"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}


// ================================================================
// MESSAGE BUBBLE
// ================================================================

@Composable
private fun MessageBubble(
    message: ChatMessage
) {

    val isUser =
        message.sender == Sender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (isUser) {
                Arrangement.End
            } else {
                Arrangement.Start
            }
    ) {

        Surface(
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(
                max = 300.dp
            )
        ) {

            Column(
                Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                )
            ) {

                Text(
                    text =
                        if (isUser) {
                            "You"
                        } else {
                            "Assistant"
                        },
                    style = MaterialTheme.typography.labelSmall
                )

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}


// ================================================================
// MICROPHONE BUTTON
// ================================================================

@Composable
fun MicButton(
    status: AssistantStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {

    val isListening =
        status == AssistantStatus.LISTENING

    val isSpeaking =
        status == AssistantStatus.SPEAKING

    val transition =
        rememberInfiniteTransition(
            label = "micPulse"
        )

    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec =
            infiniteRepeatable(
                tween(600),
                RepeatMode.Reverse
            ),
        label = "micScale"
    )

    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier
            .size(72.dp)
            .scale(
                if (isListening || isSpeaking) {
                    pulse
                } else {
                    1f
                }
            ),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor =
                    if (isListening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
            )
    ) {

        Icon(
            painter =
                painterResource(
                    if (isSpeaking) {
                        R.drawable.ic_stop
                    } else {
                        R.drawable.ic_mic
                    }
                ),
            contentDescription =
                when {

                    isListening ->
                        "Stop listening"

                    isSpeaking ->
                        "Stop speaking"

                    else ->
                        "Start listening"
                },
            modifier = Modifier.size(32.dp)
        )
    }
}


// ================================================================
// INPUT BAR
// ================================================================

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {

    Row(
        verticalAlignment =
            Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text("Type a message…")
            },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
            keyboardActions =
                KeyboardActions(
                    onSend = {
                        onSend()
                    }
                ),
            modifier = Modifier.weight(1f)
        )

        Spacer(
            Modifier.width(8.dp)
        )

        FilledIconButton(
            onClick = onSend,
            enabled = text.isNotBlank()
        ) {

            Icon(
                painter =
                    painterResource(
                        R.drawable.ic_send
                    ),
                contentDescription = "Send"
            )
        }
    }
}


// ================================================================
// CONFIRMATION DIALOG
// ================================================================

@Composable
fun ConfirmationDialog(
    request: ConfirmationRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {

    AlertDialog(

        onDismissRequest = onCancel,

        title = {
            Text(request.title)
        },

        text = {
            Text(request.details)
        },

        confirmButton = {

            Button(
                onClick = onConfirm
            ) {

                Text(
                    request.confirmLabel
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick = onCancel
            ) {

                Text("Cancel")
            }
        }
    )
}