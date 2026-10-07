package com.hemanth.myassistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

private const val TAG = "AssistantVoice"

class TTSManager(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onFinishedSpeaking: () -> Unit = {} // only when speech ENDED NATURALLY
) : TextToSpeech.OnInitListener {
    private var isReady = false
    private var pendingText: String? = null        // text waiting for the engine to start
    private var utteranceCount = 0
    @Volatile private var currentUtteranceId: String? = null

    // TTS reports progress from a background thread. Updating a StateFlow from
    // there is safe, so we can pass these straight to the ViewModel.
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // Ignore a late "started" for speech that was already stopped or replaced.
            if (utteranceId == currentUtteranceId) onSpeakingChanged(true)
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == currentUtteranceId) {
                onSpeakingChanged(false)
                onFinishedSpeaking() // not called when stop() interrupts speech
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId == currentUtteranceId) onSpeakingChanged(false)
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "TTS error code: $errorCode")
            if (utteranceId == currentUtteranceId) onSpeakingChanged(false)
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId == currentUtteranceId) onSpeakingChanged(false)
        }
    }

    // Starting the engine is asynchronous: onInit() is called when it's ready.
    private val tts = TextToSpeech(context.applicationContext, this)

    val isAvailable: Boolean get() = isReady

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS engine failed to start (status=$status)")
            return
        }
        if (!trySetLanguage(Locale.getDefault()) && !trySetLanguage(Locale.US)) {
            Log.e(TAG, "No usable TTS language is installed")
            return
        }
        pickBestVoice()
        tts.setOnUtteranceProgressListener(progressListener)
        isReady = true
        Log.d(TAG, "TTS ready, voice = ${tts.voice?.name}")

        pendingText?.let { speak(it) }
        pendingText = null
    }

    fun speak(text: String) {
        if (!isReady) {
            pendingText = text // speak it as soon as the engine is ready
            return
        }
        val id = "utterance_${utteranceCount++}"
        currentUtteranceId = id
        // QUEUE_FLUSH: stop anything currently being said, then say this.
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) Log.e(TAG, "speak() failed")
    }

    fun stop() {
        currentUtteranceId = null
        tts.stop()
        onSpeakingChanged(false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun trySetLanguage(locale: Locale): Boolean {
        val result = tts.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Choose the highest-quality installed, offline voice for the current language. */
    private fun pickBestVoice() {
        try {
            val current = tts.voice ?: return
            val best = tts.voices
                ?.filter { voice ->
                    voice.locale == current.locale &&
                            !voice.isNetworkConnectionRequired &&
                            voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                ?.maxByOrNull { it.quality }
            if (best != null && best.quality > current.quality) tts.voice = best
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't choose a voice; using the default", e)
        }
    }
}