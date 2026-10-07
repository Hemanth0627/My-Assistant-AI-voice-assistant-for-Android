package com.hemanth.myassistant.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hemanth.myassistant.actions.ActionManager
import com.hemanth.myassistant.actions.ActionResult
import com.hemanth.myassistant.actions.CommunicationManager
import com.hemanth.myassistant.actions.Contact
import com.hemanth.myassistant.actions.ContactLookup
import com.hemanth.myassistant.ai.AIResult
import com.hemanth.myassistant.ai.AIService
import com.hemanth.myassistant.model.AssistantAction
import com.hemanth.myassistant.model.AssistantStatus
import com.hemanth.myassistant.model.ChatMessage
import com.hemanth.myassistant.model.Sender
import com.hemanth.myassistant.model.WebSource
import com.hemanth.myassistant.voice.SpeechRecognizerManager
import com.hemanth.myassistant.voice.TTSManager
import com.hemanth.myassistant.wakeword.WakeWordBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "AssistantVM"
private const val MAX_HISTORY_MESSAGES = 20
private const val MAX_STORED_MESSAGES = 100
private const val LISTENING_TIMEOUT_MS = 20_000L

// CONVERSATION MODE: saying one of these ends the conversation (not sent to the AI).
private val END_PHRASES = setOf(
    "stop", "stop listening", "thats all", "that is all", "thank you", "thanks",
    "ok thanks", "okay thanks", "thank you so much", "bye", "goodbye", "good bye",
    "never mind", "nevermind", "nothing", "no thanks", "cancel", "done", "im done"
)

/** What the confirmation popup shows. */
data class ConfirmationRequest(
    val title: String,
    val details: String,
    val confirmLabel: String
)

data class AssistantUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            id = 0,
            text = "Hi! I'm your assistant. Type a message or tap the mic.",
            sender = Sender.ASSISTANT,
            includeInHistory = false
        )
    ),
    val inputText: String = "",
    val status: AssistantStatus = AssistantStatus.IDLE,
    val hint: String? = null,
    val showOpenSettings: Boolean = false,
    val confirmation: ConfirmationRequest? = null,
    val permissionRequest: String? = null
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var nextId = 1L

    private val aiService = AIService()
    private val actionManager = ActionManager(application)
    private val communication = CommunicationManager(application)

    private var pendingConfirmAction: (() -> Unit)? = null
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    private var listeningJob: Job? = null

    // CONVERSATION MODE
    private var conversationMode = false      // keep listening after each answer
    private var isFollowUpListen = false      // this listen was started automatically

    private val tts = TTSManager(
        application,
        onSpeakingChanged = { speaking ->
            _uiState.update { state ->
                when {
                    speaking -> state.copy(status = AssistantStatus.SPEAKING)
                    state.status == AssistantStatus.SPEAKING -> state.copy(status = AssistantStatus.IDLE)
                    else -> state
                }
            }
        },
        // CONVERSATION MODE: finished speaking naturally → maybe listen again (on the main thread).
        onFinishedSpeaking = { viewModelScope.launch { continueConversation() } }
    )

    private val speech = SpeechRecognizerManager(
        application,
        object : SpeechRecognizerManager.Listener {
            override fun onPartialText(text: String) {
                _uiState.update {
                    if (it.status == AssistantStatus.LISTENING) it.copy(inputText = text) else it
                }
            }

            override fun onFinalText(text: String) {
                listeningJob?.cancel()
                if (_uiState.value.status != AssistantStatus.LISTENING) {
                    Log.d(TAG, "Final text ignored: not listening")
                    return
                }
                _uiState.update { it.copy(status = AssistantStatus.IDLE, inputText = "") }

                // CONVERSATION MODE: "stop", "thanks", "bye"… end the conversation.
                if (normalize(text) in END_PHRASES) {
                    Log.d(TAG, "Conversation ended by the user")
                    conversationMode = false
                    addMessage(text, Sender.USER)
                    report(ActionResult(true, "Okay."))
                    return
                }

                sendText(text)
            }

            override fun onError(message: String, isSilence: Boolean) {
                listeningJob?.cancel()
                // CONVERSATION MODE: silence after an automatic re-listen ends quietly (no red error).
                val endQuietly = isSilence && isFollowUpListen
                conversationMode = false
                _uiState.update {
                    if (it.status == AssistantStatus.LISTENING) {
                        it.copy(status = AssistantStatus.IDLE, hint = if (endQuietly) null else message)
                    } else {
                        it
                    }
                }
            }
        }
    )

    init {
        viewModelScope.launch { aiService.warmUp() }
    }

    // ---------------- Sending ----------------

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text, hint = null) }
    }

    /** The Send button / keyboard: TYPED messages end voice conversation mode. */
    fun onSend() {
        conversationMode = false
        if (_uiState.value.status == AssistantStatus.LISTENING) cancelListening()
        sendText(_uiState.value.inputText)
    }

    /** Sends [rawText] (typed or spoken): fast path first, otherwise ONE AI request. */
    private fun sendText(rawText: String) {
        val state = _uiState.value
        if (state.status == AssistantStatus.PROCESSING) {
            Log.d(TAG, "Send ignored: already processing a request")
            return
        }
        val text = rawText.trim()
        if (text.isEmpty()) return

        val history = state.messages
            .filter { it.includeInHistory }
            .takeLast(MAX_HISTORY_MESSAGES)

        addMessage(text, Sender.USER)
        _uiState.update { it.copy(inputText = "", hint = null) }

        // 1. FAST PATH
        val localAction = actionManager.tryLocalCommand(text)
        if (localAction != null) {
            runAction(localAction)
            return
        }

        // 2. AI: exactly ONE request, with its own ID.
        val requestId = UUID.randomUUID().toString()
        Log.d(TAG, "Sending request ${requestId.take(8)}")
        _uiState.update { it.copy(status = AssistantStatus.PROCESSING) }

        viewModelScope.launch {
            val result = aiService.chat(text, history, requestId)

            _uiState.update {
                if (it.status == AssistantStatus.PROCESSING) it.copy(status = AssistantStatus.IDLE) else it
            }

            when (result) {
                is AIResult.Success -> {
                    val action = result.action
                    if (action != null) {
                        runAction(action)
                    } else {
                        addMessage(result.reply, Sender.ASSISTANT, sources = result.sources)
                        tts.speak(result.reply) // when it finishes → conversation may continue
                    }
                }
                is AIResult.Failure -> {
                    conversationMode = false // don't keep retrying into an error
                    _uiState.update { it.copy(hint = result.userMessage) }
                }
            }
        }
    }

    // ---------------- CONVERSATION MODE ----------------

    /** Called when the assistant finished speaking naturally. Listen again if appropriate. */
    private fun continueConversation() {
        if (!conversationMode) return
        val state = _uiState.value
        if (state.status != AssistantStatus.IDLE || state.confirmation != null) return

        // Never listen in the background: only while the full app or the panel is on screen.
        if (WakeWordBus.screensVisible.value == 0) {
            conversationMode = false
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(
            getApplication<Application>(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            conversationMode = false
            return
        }

        Log.d(TAG, "Conversation continues: listening again")
        startListeningInternal(followUp = true)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z ]"), "").replace(Regex("\\s+"), " ").trim()

    // ---------------- Actions ----------------

    private fun runAction(action: AssistantAction) {
        when (action) {
            // Calls/messages: if the contact is ambiguous or not found, report() speaks a
            // question and the conversation CONTINUES so you can answer.
            // If the contact is found, askConfirmation() shows the popup and ends it.
            is AssistantAction.MakeCall -> withContact(action.contactName) { contact ->
                askConfirmation(
                    title = "Call ${contact.name}?",
                    details = contact.number,
                    confirmLabel = "Call",
                    spokenQuestion = "Do you want me to call ${contact.name}?"
                ) { startCall(contact) }
            }

            is AssistantAction.SendMessage -> withContact(action.contactName) { contact ->
                val appName = action.app.displayName
                askConfirmation(
                    title = "Message ${contact.name}?",
                    details = "\"${action.message}\"\n\n$appName will open with this message ready. " +
                            "You tap Send there.",
                    confirmLabel = "Open $appName",
                    spokenQuestion = "Do you want to send ${contact.name} this message: ${action.message}?"
                ) { report(communication.openMessage(contact, action.message, action.app)) }
            }

            AssistantAction.ClearConversation -> onClearConversationClick()

            else -> {
                val result = actionManager.execute(action)
                // CONVERSATION MODE: only stop if we actually LEFT the assistant
                // (an app or settings page opened). If it failed, keep listening.
                val leftTheAssistant = result.success &&
                        (action is AssistantAction.OpenApp || action is AssistantAction.OpenSettings)
                if (leftTheAssistant) conversationMode = false
                report(result)
            }
        }
    }

    private fun report(result: ActionResult) {
        addMessage(result.message, Sender.ASSISTANT)
        tts.speak(result.message)
    }

    private fun withContact(name: String, onFound: (Contact) -> Unit) {
        communication.asPhoneNumber(name)?.let { onFound(it); return }

        withPermission(Manifest.permission.READ_CONTACTS) { granted ->
            if (!granted) {
                _uiState.update { it.copy(showOpenSettings = true) }
                report(
                    ActionResult(
                        false,
                        "I need permission to read your contacts to find $name. " +
                                "You can allow it in the app settings."
                    )
                )
                return@withPermission
            }
            when (val lookup = communication.findContact(name)) {
                is ContactLookup.Found -> onFound(lookup.contact)
                is ContactLookup.Ambiguous -> report(
                    ActionResult(
                        false,
                        "I found more than one match: ${lookup.names.joinToString(", ")}. " +
                                "Please say the full name."
                    )
                )
                ContactLookup.NotFound ->
                    report(ActionResult(false, "I couldn't find $name in your contacts."))
            }
        }
    }

    private fun startCall(contact: Contact) {
        withPermission(Manifest.permission.CALL_PHONE) { granted ->
            report(communication.placeCall(contact, directCall = granted))
        }
    }

    // ---------------- Confirmation popup ----------------

    private fun askConfirmation(
        title: String,
        details: String,
        confirmLabel: String,
        spokenQuestion: String,
        onConfirm: () -> Unit
    ) {
        conversationMode = false // a popup needs a TAP, so voice conversation stops here
        pendingConfirmAction = onConfirm
        _uiState.update { it.copy(confirmation = ConfirmationRequest(title, details, confirmLabel)) }
        tts.speak(spokenQuestion)
    }

    fun onConfirm() {
        val action = pendingConfirmAction
        pendingConfirmAction = null
        _uiState.update { it.copy(confirmation = null) }
        tts.stop()
        action?.invoke()
    }

    fun onCancelConfirmation() {
        pendingConfirmAction = null
        _uiState.update { it.copy(confirmation = null) }
        report(ActionResult(false, "Okay, cancelled."))
    }

    // ---------------- Conversation controls ----------------

    fun onClearConversationClick() {
        if (_uiState.value.confirmation != null) return
        askConfirmation(
            title = "Clear conversation?",
            details = "This deletes the chat on screen and everything the assistant remembers " +
                    "from it. This can't be undone.",
            confirmLabel = "Clear",
            spokenQuestion = "Do you want me to clear our conversation?"
        ) { clearConversation() }
    }

    private fun clearConversation() {
        _uiState.update { it.copy(messages = emptyList(), hint = null, inputText = "") }
        addMessage(
            "Conversation cleared. I've forgotten everything we talked about.",
            Sender.ASSISTANT,
            includeInHistory = false
        )
        tts.speak("Conversation cleared.")
    }

    // ---------------- Permissions requested by actions ----------------

    private fun withPermission(permission: String, block: (granted: Boolean) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            getApplication<Application>(), permission
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            block(true)
        } else {
            pendingPermissionCallback = block
            _uiState.update { it.copy(permissionRequest = permission) }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val callback = pendingPermissionCallback
        pendingPermissionCallback = null
        _uiState.update { it.copy(permissionRequest = null) }
        callback?.invoke(granted)
    }

    // ---------------- Voice ----------------

    /** Mic button / power button / wake word. Starts (or restarts) a voice conversation. */
    fun startListening() {
        startListeningInternal(followUp = false)
    }

    private fun startListeningInternal(followUp: Boolean) {
        val status = _uiState.value.status
        if (status == AssistantStatus.LISTENING || status == AssistantStatus.PROCESSING) {
            Log.d(TAG, "startListening ignored: already $status")
            return
        }
        conversationMode = true
        isFollowUpListen = followUp

        tts.stop()
        _uiState.update {
            it.copy(
                status = AssistantStatus.LISTENING,
                inputText = "",
                hint = null,
                showOpenSettings = false
            )
        }

        listeningJob?.cancel()
        listeningJob = viewModelScope.launch {
            WakeWordBus.awaitMicFree() // handshake: Vosk has released the mic
            if (_uiState.value.status != AssistantStatus.LISTENING) return@launch

            speech.startListening()

            delay(LISTENING_TIMEOUT_MS) // safety net only
            if (_uiState.value.status == AssistantStatus.LISTENING) {
                Log.w(TAG, "Listening timed out; resetting")
                speech.cancel()
                conversationMode = false
                _uiState.update {
                    it.copy(
                        status = AssistantStatus.IDLE,
                        inputText = "",
                        hint = if (isFollowUpListen) null
                        else "I didn't hear anything. Tap the mic and try again."
                    )
                }
            }
        }
    }

    /** "I'm done talking": process what was heard. The conversation continues after the answer. */
    fun stopListening() {
        if (!speech.stopListening()) {
            listeningJob?.cancel()
            _uiState.update {
                if (it.status == AssistantStatus.LISTENING) it.copy(status = AssistantStatus.IDLE) else it
            }
        }
    }

    /** "Never mind": stop WITHOUT sending, and end the conversation (e.g. panel closed). */
    fun cancelListening() {
        conversationMode = false
        listeningJob?.cancel()
        speech.cancel()
        _uiState.update {
            if (it.status == AssistantStatus.LISTENING) {
                it.copy(status = AssistantStatus.IDLE, inputText = "")
            } else {
                it
            }
        }
    }

    /** The ⏹ button while speaking: the user interrupted, so the conversation ends. */
    fun stopSpeaking() {
        conversationMode = false
        tts.stop()
    }

    fun onMicPermissionDenied() {
        conversationMode = false
        _uiState.update {
            it.copy(
                status = AssistantStatus.IDLE,
                hint = "Microphone permission is needed for voice input. You can still type.",
                showOpenSettings = true
            )
        }
    }

    private fun addMessage(
        text: String,
        sender: Sender,
        includeInHistory: Boolean = true,
        sources: List<WebSource> = emptyList()
    ) {
        _uiState.update {
            val updated = it.messages + ChatMessage(nextId++, text, sender, includeInHistory, sources)
            it.copy(messages = updated.takeLast(MAX_STORED_MESSAGES))
        }
    }

    override fun onCleared() {
        listeningJob?.cancel()
        speech.destroy()
        tts.shutdown()
        super.onCleared()
    }
}