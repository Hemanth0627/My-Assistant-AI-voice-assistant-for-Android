package com.hemanth.myassistant.model

/**
 * Every action the assistant is allowed to perform.
 * Nothing outside this list can ever be executed — this is the security whitelist.
 */
sealed interface AssistantAction {
    data class OpenApp(val appName: String) : AssistantAction
    data class OpenSettings(val page: SettingsPage) : AssistantAction
    data object GetTime : AssistantAction
    data object GetBattery : AssistantAction
    data object ClearConversation : AssistantAction

    // Consequential actions: these ALWAYS require the user's confirmation.
    data class MakeCall(val contactName: String) : AssistantAction
    data class SendMessage(
        val contactName: String,
        val message: String,
        val app: MessageApp
    ) : AssistantAction
}

enum class MessageApp(val displayName: String) {
    SMS("Messages"),
    WHATSAPP("WhatsApp")
}

/** The settings pages the assistant may open. */
enum class SettingsPage(val spokenName: String) {
    MAIN("Settings"),
    WIFI("Wi-Fi settings"),
    BLUETOOTH("Bluetooth settings"),
    DISPLAY("display settings"),
    SOUND("sound settings"),
    BATTERY("battery settings"),
    LOCATION("location settings"),
    NETWORK("network settings"),
    AIRPLANE_MODE("airplane mode settings"),
    APPS("app settings"),
    THIS_APP("My Assistant's settings")
}