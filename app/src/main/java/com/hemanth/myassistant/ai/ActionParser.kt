package com.hemanth.myassistant.ai

import android.util.Log
import com.hemanth.myassistant.model.AssistantAction
import com.hemanth.myassistant.model.MessageApp
import com.hemanth.myassistant.model.SettingsPage
import org.json.JSONObject

private const val TAG = "AssistantAI"

/**
 * Converts an action requested by the backend into a whitelisted [AssistantAction].
 * The phone is the final authority: anything unknown or invalid is rejected here.
 */
object ActionParser {

    fun parse(json: JSONObject?): AssistantAction? {
        if (json == null) return null

        val name = json.optString("name")
        val args = json.optJSONObject("args") ?: JSONObject()

        val action: AssistantAction? = when (name) {

            "open_app" -> {
                val appName = args.optString("app_name").trim()

                if (appName.isEmpty() || appName.length > 50) {
                    null
                } else {
                    AssistantAction.OpenApp(appName)
                }
            }

            "open_settings" -> {
                val page = args
                    .optString("page")
                    .trim()
                    .uppercase()

                SettingsPage.entries
                    .firstOrNull { it.name == page }
                    ?.let {
                        AssistantAction.OpenSettings(it)
                    }
            }

            "get_current_time" -> {
                AssistantAction.GetTime
            }

            "get_battery_level" -> {
                AssistantAction.GetBattery
            }

            "make_phone_call" -> {
                val contact = args
                    .optString("contact_name")
                    .trim()

                if (contact.isEmpty() || contact.length > 60) {
                    null
                } else {
                    AssistantAction.MakeCall(contact)
                }
            }

            "send_message" -> {
                val contact = args
                    .optString("contact_name")
                    .trim()

                val message = args
                    .optString("message")
                    .trim()

                val app =
                    if (
                        args.optString("app")
                            .equals("whatsapp", ignoreCase = true)
                    ) {
                        MessageApp.WHATSAPP
                    } else {
                        MessageApp.SMS
                    }

                if (
                    contact.isEmpty() ||
                    contact.length > 60 ||
                    message.isEmpty() ||
                    message.length > 1000
                ) {
                    null
                } else {
                    AssistantAction.SendMessage(
                        contactName = contact,
                        message = message,
                        app = app
                    )
                }
            }

            "clear_conversation" -> {
                AssistantAction.ClearConversation
            }

            else -> {
                null
            }
        }

        // Only log the action name.
        // Do NOT log contact names or message contents.
        if (action == null) {
            Log.w(
                TAG,
                "Rejected action from backend: ${json.optString("name")}"
            )
        }

        return action
    }
}