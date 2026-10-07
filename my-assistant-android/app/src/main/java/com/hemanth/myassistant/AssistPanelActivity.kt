package com.hemanth.myassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.hemanth.myassistant.ui.AssistPanelScreen
import com.hemanth.myassistant.ui.theme.MyAssistantTheme
import com.hemanth.myassistant.wakeword.WakeWordBus
import kotlinx.coroutines.flow.update

/** The compact panel shown by the power button / "Hey Brain". */
class AssistPanelActivity : ComponentActivity() {

    // +1 on every launch → the panel starts listening each time.
    private val assistRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) assistRequests.intValue++

        setContent {
            MyAssistantTheme {
                AssistPanelScreen(
                    assistRequest = assistRequests.intValue,
                    onClose = { finish() },
                    onOpenFullApp = { openFullApp() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        assistRequests.intValue++
    }

    // The panel is on screen → the wake word PAUSES and releases the microphone.
    override fun onStart() {
        super.onStart()
        WakeWordBus.screensVisible.update { it + 1 }
    }

    // The panel left the screen → the wake word may listen again, and the panel closes itself.
    override fun onStop() {
        WakeWordBus.screensVisible.update { it - 1 }
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}