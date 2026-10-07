package com.hemanth.myassistant.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hemanth.myassistant.model.AssistantAction

private const val TAG = "AssistantActions"

/** What happened, in a sentence that can be shown and spoken. */
data class ActionResult(
    val success: Boolean,
    val message: String
)

class ActionManager(context: Context) {

    private val appLauncher = AppLauncher(context)
    private val settingsManager = SettingsManager(context)
    private val deviceInfo = DeviceInfo(context)

    /** Returns an action if [text] is a simple phone command, or null to let the AI handle it. */
    fun tryLocalCommand(text: String): AssistantAction? =
        LocalCommandParser.parse(text)

    fun execute(action: AssistantAction): ActionResult {
        Log.d(TAG, "Executing ${action::class.simpleName}")

        return when (action) {

            is AssistantAction.OpenApp ->
                appLauncher.launch(action.appName)

            is AssistantAction.OpenSettings ->
                settingsManager.open(action.page)

            AssistantAction.GetTime ->
                deviceInfo.currentTime()

            AssistantAction.GetBattery ->
                deviceInfo.batteryStatus()

            // Safety net:
            // Calls and messages must NEVER be executed directly.
            // They must go through the confirmation flow first.
            is AssistantAction.MakeCall,
            is AssistantAction.SendMessage,
            AssistantAction.ClearConversation ->
                ActionResult(false, "That needs your confirmation first.")
        }
    }
}

/**
 * Opens a screen, turning "doesn't exist" / "not allowed"
 * into a friendly message instead of crashing the app.
 */
internal fun Context.startSafely(
    intent: Intent,
    successMessage: String,
    failureMessage: String
): ActionResult = try {

    // Required when starting an Activity from a non-Activity Context.
    startActivity(
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    ActionResult(
        true,
        successMessage
    )

} catch (e: ActivityNotFoundException) {

    Log.w(
        TAG,
        "Nothing can handle $intent"
    )

    ActionResult(
        false,
        failureMessage
    )

} catch (e: SecurityException) {

    Log.w(
        TAG,
        "Not allowed to start $intent",
        e
    )

    ActionResult(
        false,
        "Android didn't allow me to open that."
    )
}
