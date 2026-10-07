package com.hemanth.myassistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

private const val TAG = "AssistantVoice"

class SpeechRecognizerManager(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onError(message: String, isSilence: Boolean)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var sessionCounter = 0   // goes up by 1 for every new listening session
    private var activeSession = 0    // 0 = nothing is listening

    /** True while a listening session is running. */
    val isActive: Boolean get() = activeSession != 0

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening() {
        if (!isAvailable()) {
            listener.onError(
                "Speech recognition isn't available on this phone. " +
                        "Make sure Google's speech services are installed and enabled.",
                false
            )
            return
        }
        releaseRecognizer() // make sure no old recognizer is still attached

        val session = ++sessionCounter
        activeSession = session
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(SessionListener(session))
            startListening(buildIntent())
        }
        Log.d(TAG, "Session $session started")
    }

    /** Asks the recognizer to finish. Returns false if nothing was listening. */
    fun stopListening(): Boolean {
        if (!isActive) return false
        recognizer?.stopListening() // the result (or an error) will follow
        return true
    }

    /** Stops immediately WITHOUT delivering any result, and releases the mic. */
    fun cancel() {
        activeSession = 0
        releaseRecognizer()
    }

    /** Release everything (app closing). */
    fun destroy() = cancel()

    /** A session delivered its final result or error: mark it done and release the recognizer. */
    private fun finishSession(session: Int) {
        if (activeSession == session) activeSession = 0
        // Release on the next turn of the main thread, not inside the recognizer's own callback.
        mainHandler.post { if (activeSession == 0) releaseRecognizer() }
    }

    private fun releaseRecognizer() {
        recognizer?.let {
            try {
                it.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "cancel() failed", e)
            }
            it.destroy()
        }
        recognizer = null
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** Listener tied to ONE session. Messages for any other session are ignored. */
    private inner class SessionListener(private val session: Int) : RecognitionListener {

        private fun isCurrent() = session == activeSession

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent()) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) listener.onPartialText(text)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent()) {
                Log.d(TAG, "Ignored late result from session $session")
                return
            }
            finishSession(session) // from now on, this session's messages are ignored
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.d(TAG, "Session $session final result (${text.length} chars)")
            if (text.isBlank()) listener.onError("I didn't catch that. Please try again.", true)
            else listener.onFinalText(text)
        }

        override fun onError(error: Int) {
            if (!isCurrent()) {
                Log.d(TAG, "Ignored late error $error from session $session")
                return
            }
            finishSession(session)
            Log.w(TAG, "Session $session error code: $error")
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            listener.onError(errorMessage(error), silence)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "I didn't hear anything. Tap the mic and try again."
        SpeechRecognizer.ERROR_AUDIO ->
            "Couldn't use the microphone. Another app may be using it."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is missing."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Voice input needs an internet connection right now. Check your connection."
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT ->
            "The speech service was busy. Tap the mic to try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Your phone's language isn't available for voice input."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Too many voice requests. Wait a few seconds and try again."
        else ->
            "Voice input failed (code $error). Please try again."
    }
}