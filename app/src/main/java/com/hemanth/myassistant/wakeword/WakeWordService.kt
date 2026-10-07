package com.hemanth.myassistant.wakeword

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.hemanth.myassistant.AssistPanelActivity
import com.hemanth.myassistant.MainActivity
import com.hemanth.myassistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

private const val TAG = "AssistantWake"

private const val CHANNEL_LISTENING = "wake_word"
private const val CHANNEL_HEARD = "wake_word_heard"

private const val LISTENING_NOTIFICATION_ID = 42
private const val HEARD_NOTIFICATION_ID = 43

private const val SAMPLE_RATE = 16000f

private const val WAKE_PHRASE = "hey brain"

// Keep this at 0.6 initially.
// If wake word is still missed frequently, we can tune this later.
private const val MIN_CONFIDENCE = 0.6

// Vosk may ONLY hear the wake phrase or "[unk]" (= anything else).
private val GRAMMAR = "[\"$WAKE_PHRASE\", \"[unk]\"]"

const val ACTION_STOP_WAKE_WORD =
    "com.hemanth.myassistant.STOP_WAKE_WORD"

class WakeWordService : Service() {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    private var resumeAttempts = 0 // how many times in a row reopening the mic failed
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        WakeWordBus.lastError.value = null

        createChannels()

        // Show the required notification and declare that this
        // foreground service uses the microphone.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                startForeground(
                    LISTENING_NOTIFICATION_ID,
                    listeningNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            } else {

                startForeground(
                    LISTENING_NOTIFICATION_ID,
                    listeningNotification()
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Couldn't start as a foreground service",
                e
            )

            WakeWordBus.lastError.value =
                "Couldn't start the wake word. Open the app and try again."

            stopSelf()
            return
        }

        WakeWordBus.serviceRunning.value = true

        // Copy the model out of the APK the first time,
        // then start listening.
        StorageService.unpack(
            this,
            "model-en-us",
            "model",

            { loadedModel ->

                model = loadedModel

                Log.d(
                    TAG,
                    "Wake word model ready"
                )

                observeScreens()
            },

            { error ->

                Log.e(
                    TAG,
                    "Couldn't load the speech model",
                    error
                )

                WakeWordBus.lastError.value =
                    "Couldn't load the wake word model. Check assets/model-en-us and its uuid file."

                stopSelf()
            }
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP_WAKE_WORD) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {

        scope.cancel()

        mainHandler.removeCallbacksAndMessages(null)

        // Fully release Vosk and its microphone.
        pauseDetection()

        model?.close()
        model = null

        removeOverlay()

        WakeWordBus.serviceRunning.value = false

        super.onDestroy()
    }

    // ---------------------------------------------------------
    // Listening
    // ---------------------------------------------------------

    /**
     * Pause while an assistant screen is open;
     * resume shortly after it closes.
     */
    private fun observeScreens() {

        scope.launch {

            WakeWordBus.screensVisible.collectLatest { visible ->

                if (visible > 0) {

                    pauseDetection()

                } else {

                    delay(1000)

                    resumeDetection()
                }
            }
        }
    }

    /**
     * Starts Vosk wake-word detection.
     */

    private fun resumeDetection() {
        val loadedModel = model ?: return
        if (speechService != null) return // already listening
        try {
            recognizer = Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR).apply { setWords(true) }
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(recognitionListener)
            }
            resumeAttempts = 0
            Log.d(TAG, "Listening for wake word")
            WakeWordBus.voskHoldsMic.value = true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start listening (is the mic busy?)", e)
            pauseDetection()
            retryResumeLater()
        }
    }

    /** The mic was busy: try again a little later (1s, 2s, 3s… up to 5 tries). */
    private fun retryResumeLater() {
        resumeAttempts++
        if (resumeAttempts > 5) {
            Log.w(TAG, "Gave up reopening the mic; it will retry when you next close the app")
            resumeAttempts = 0
            return
        }
        mainHandler.postDelayed({
            if (WakeWordBus.screensVisible.value == 0) resumeDetection()
        }, 1000L * resumeAttempts)
    }

    /**
     * Fully stops and releases the microphone so
     * Android SpeechRecognizer can use it.
     */
    private fun pauseDetection() {

        speechService?.let {

            it.stop()
            it.shutdown()
        }

        speechService = null

        recognizer?.close()
        recognizer = null

        // Vosk has now released the microphone.
        WakeWordBus.voskHoldsMic.value = false

        Log.d(
            TAG,
            "Wake word paused"
        )
    }

    // ---------------------------------------------------------
    // Vosk recognition
    // ---------------------------------------------------------

    private val recognitionListener =
        object : RecognitionListener {

            override fun onResult(
                hypothesis: String?
            ) {

                hypothesis?.let {
                    checkForWakeWord(it)
                }
            }

            override fun onPartialResult(
                hypothesis: String?
            ) {
                // We only act on final results.
            }

            override fun onFinalResult(
                hypothesis: String?
            ) {
                // Handled through onResult().
            }

            override fun onTimeout() {
                // Vosk timed out; normal restart logic handles it.
            }

            override fun onError(
                exception: Exception?
            ) {

                Log.w(
                    TAG,
                    "Recognizer error",
                    exception
                )

                mainHandler.post {
                    pauseDetection()
                }

                mainHandler.postDelayed({

                    if (
                        WakeWordBus.screensVisible.value == 0
                    ) {
                        resumeDetection()
                    }

                }, 3000)
            }
        }

    /**
     * Vosk may return JSON such as:
     *
     * {"text":"[unk] hey brain",
     *  "result":[
     *      {"word":"hey","conf":0.93},
     *      {"word":"brain","conf":0.91}
     *  ]}
     *
     * We search for the wake phrase anywhere in the
     * recognized words instead of requiring the entire
     * result to equal "hey brain".
     */
    private fun checkForWakeWord(
        json: String
    ) {

        val result = try {

            JSONObject(json)

        } catch (e: JSONException) {

            return
        }

        val text =
            result.optString("text")

        if (text.isBlank()) {
            return
        }

        Log.d(
            TAG,
            "Vosk heard: \"$text\""
        )

        val words =
            result.optJSONArray("result")
                ?: return

        val heard =
            (0 until words.length()).map {
                words.getJSONObject(it)
            }

        val wanted =
            WAKE_PHRASE.split(" ")

        // Not enough recognized words to contain
        // the wake phrase.
        if (heard.size < wanted.size) {
            return
        }

        // Find the wake phrase anywhere in the utterance.
        //
        // Example:
        // [unk] hey brain
        //
        // We still find:
        //       hey brain
        val start =
            (0..heard.size - wanted.size)
                .firstOrNull { i ->

                    wanted.indices.all { j ->

                        heard[i + j]
                            .optString("word")
                            .equals(
                                wanted[j],
                                ignoreCase = true
                            )
                    }
                }
                ?: return

        // Confidence is calculated ONLY from
        // the actual wake phrase words.
        //
        // This means [unk] or other surrounding
        // words don't lower the confidence.
        val confidence =
            wanted.indices.minOf { j ->

                heard[start + j]
                    .optDouble(
                        "conf",
                        0.0
                    )
            }

        Log.d(
            TAG,
            "Heard wake phrase, confidence %.2f"
                .format(confidence)
        )

        if (confidence >= MIN_CONFIDENCE) {

            onWakeWord()
        }
    }

    // ---------------------------------------------------------
    // Wake word heard
    // ---------------------------------------------------------

    private fun onWakeWord() {

        mainHandler.post {

            Log.d(
                TAG,
                "Wake word accepted"
            )

            // IMPORTANT:
            // Release the Vosk microphone BEFORE
            // opening the assistant panel.
            pauseDetection()

            if (Settings.canDrawOverlays(this)) {

                showBriefBubble()

                mainHandler.postDelayed(
                    {
                        openPanel()
                    },
                    150
                )

            } else {

                showTapToTalkNotification()
            }

            // Safety net:
            // If no screen opened, resume wake-word detection.
            mainHandler.postDelayed({

                if (
                    WakeWordBus.screensVisible.value == 0
                ) {
                    resumeDetection()
                }

            }, 6000)
        }
    }

    // ---------------------------------------------------------
    // Bubble
    // ---------------------------------------------------------

    private fun showBriefBubble() {

        val bubble =
            TextView(this).apply {

                text = "● Brain"
                textSize = 16f

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    0xE61A57D6.toInt()
                )

                setPadding(
                    48,
                    24,
                    48,
                    24
                )
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.CENTER_HORIZONTAL

                y = 120
            }

        try {

            getSystemService(
                WindowManager::class.java
            ).addView(
                bubble,
                params
            )

            overlayView = bubble

            mainHandler.postDelayed(
                {
                    removeOverlay()
                },
                2000
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Couldn't show the bubble",
                e
            )
        }
    }

    private fun removeOverlay() {

        overlayView?.let {

            try {

                getSystemService(
                    WindowManager::class.java
                ).removeView(it)

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Couldn't remove the bubble",
                    e
                )
            }
        }

        overlayView = null
    }

    // ---------------------------------------------------------
    // Panel
    // ---------------------------------------------------------

    private fun panelIntent() =
        Intent(
            this,
            AssistPanelActivity::class.java
        )
            .setAction(Intent.ACTION_ASSIST)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

    private fun openPanel() {

        try {

            startActivity(
                panelIntent()
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Couldn't open the panel",
                e
            )

            showTapToTalkNotification()
        }
    }

    // ---------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun showTapToTalkNotification() {

        // Android 13+ notification permission.
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.w(
                TAG,
                "Notifications not allowed; can't show 'tap to talk'"
            )

            return
        }

        val tap =
            PendingIntent.getActivity(
                this,
                1,
                panelIntent(),
                PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT
            )

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_HEARD
            )
                .setSmallIcon(
                    R.drawable.ic_mic
                )
                .setContentTitle(
                    "Brain heard you"
                )
                .setContentText(
                    "Tap to talk"
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .setContentIntent(tap)
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            HEARD_NOTIFICATION_ID,
            notification
        )
    }

    // ---------------------------------------------------------
    // Notification channels
    // ---------------------------------------------------------

    private fun createChannels() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING,
                "Wake word",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HEARD,
                "Wake word heard",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun listeningNotification(): Notification {

        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE
            )

        val stop =
            PendingIntent.getService(
                this,
                2,
                Intent(
                    this,
                    WakeWordService::class.java
                ).setAction(
                    ACTION_STOP_WAKE_WORD
                ),
                PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_LISTENING
        )
            .setSmallIcon(
                R.drawable.ic_mic
            )
            .setContentTitle(
                "Listening for \"Hey Brain\""
            )
            .setContentText(
                "Audio is processed on your phone and never sent anywhere."
            )
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                0,
                "Stop",
                stop
            )
            .build()
    }
}