package com.hemanth.myassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.hemanth.myassistant.ui.AssistantScreen
import com.hemanth.myassistant.ui.theme.MyAssistantTheme
import com.hemanth.myassistant.wakeword.WakeWordBus
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {

    // Goes up by 1 every time the app is opened as the assistant (kept from 6B).
    private val assistRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null && intent.isAssistLaunch()) {
            assistRequests.intValue++
        }

        setContent {
            MyAssistantTheme {
                AssistantScreen(assistRequest = assistRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.isAssistLaunch()) {
            assistRequests.intValue++
        }
    }

    // The app is on screen → tell the wake word to PAUSE and release the microphone.
    override fun onStart() {
        super.onStart()
        WakeWordBus.screensVisible.update { it + 1 }
    }

    // The app left the screen → the wake word may listen again.
    override fun onStop() {
        WakeWordBus.screensVisible.update { it - 1 }
        super.onStop()
    }
}

/** True when Android opened us as the assistant, not from the app icon. */
private fun Intent?.isAssistLaunch(): Boolean =
    this?.action == Intent.ACTION_ASSIST || this?.action == Intent.ACTION_VOICE_COMMAND