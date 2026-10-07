package com.hemanth.myassistant.actions

import com.hemanth.myassistant.model.AssistantAction
import com.hemanth.myassistant.model.SettingsPage

/**
 * The offline "fast path": recognises a few simple, unambiguous commands.
 * Deliberately strict: when unsure, return null and let the AI decide.
 */
object LocalCommandParser {

    private val timePhrases = setOf(
        "time",
        "the time",
        "current time",
        "what time is it",
        "what time is it now",
        "whats the time",
        "whats the time now",
        "what is the time",
        "tell me the time"
    )

    private val batteryPhrases = setOf(
        "battery",
        "battery level",
        "battery status",
        "battery percentage",
        "battery percent",
        "whats my battery",
        "whats my battery level",
        "what is my battery level",
        "whats the battery level",
        "how much battery",
        "how much battery do i have",
        "how much charge",
        "how much charge do i have"
    )

    private val clearPhrases = setOf(
        "clear conversation",
        "clear the conversation",
        "clear chat",
        "clear the chat",
        "clear history",
        "clear chat history",
        "delete conversation",
        "delete chat",
        "forget everything",
        "forget our conversation",
        "new conversation",
        "reset conversation",
        "start over"
    )

    private val settingsWords = mapOf(
        "wifi" to SettingsPage.WIFI,
        "wi fi" to SettingsPage.WIFI,
        "bluetooth" to SettingsPage.BLUETOOTH,
        "display" to SettingsPage.DISPLAY,
        "brightness" to SettingsPage.DISPLAY,
        "sound" to SettingsPage.SOUND,
        "volume" to SettingsPage.SOUND,
        "battery" to SettingsPage.BATTERY,
        "location" to SettingsPage.LOCATION,
        "gps" to SettingsPage.LOCATION,
        "network" to SettingsPage.NETWORK,
        "mobile data" to SettingsPage.NETWORK,
        "mobile network" to SettingsPage.NETWORK,
        "airplane mode" to SettingsPage.AIRPLANE_MODE,
        "aeroplane mode" to SettingsPage.AIRPLANE_MODE,
        "flight mode" to SettingsPage.AIRPLANE_MODE,
        "app" to SettingsPage.APPS,
        "apps" to SettingsPage.APPS
    )

    private val fillerPrefixes = listOf(
        "hey assistant",
        "ok assistant",
        "okay assistant",
        "assistant",
        "please",
        "can you",
        "could you",
        "would you",
        "will you"
    )

    private val openCommand = Regex("^(open|launch) (.+)$")

    // Recognises simple call commands such as:
    // "call dad"
    // "call appa"
    // "call Kailash"
    // "dial 9876543210"
    // "phone dad"
    private val callCommand = Regex("^(call|dial|phone) (.+)$")

    fun parse(input: String): AssistantAction? {

        val text = clean(input)

        if (text.isEmpty()) return null

        // ---------------------------------------------------------
        // TIME
        // ---------------------------------------------------------

        if (text in timePhrases) {
            return AssistantAction.GetTime
        }

        // ---------------------------------------------------------
        // BATTERY
        // ---------------------------------------------------------

        if (text in batteryPhrases) {
            return AssistantAction.GetBattery
        }

        // ---------------------------------------------------------
        // CLEAR CONVERSATION
        // ---------------------------------------------------------

        if (text in clearPhrases) {
            return AssistantAction.ClearConversation
        }

        // ---------------------------------------------------------
        // CALL
        // ---------------------------------------------------------

        // "call dad"
        // "call 9876543210"
        //
        // This only creates a MakeCall action.
        // The actual call still requires the confirmation flow.
        callCommand.find(text)?.groupValues?.get(2)?.let { who ->

            if (who.split(" ").size <= 3) {
                return AssistantAction.MakeCall(who)
            }
        }

        // ---------------------------------------------------------
        // SETTINGS
        // ---------------------------------------------------------

        // "wifi settings", "settings"
        // without the word "open"
        settingsPageFor(text)?.let {
            return AssistantAction.OpenSettings(it)
        }

        // ---------------------------------------------------------
        // OPEN / LAUNCH APP
        // ---------------------------------------------------------

        val target = openCommand.find(text)
            ?.groupValues
            ?.get(2)
            ?.removeSuffix(" app")
            ?.trim()
            ?: return null

        // "open wifi settings"
        // "open bluetooth"
        (settingsPageFor(target) ?: settingsWords[target])?.let {
            return AssistantAction.OpenSettings(it)
        }

        // Long requests such as:
        // "open youtube and play lofi music"
        //
        // are handled by the AI instead.
        if (target.split(" ").size > 3) {
            return null
        }

        return AssistantAction.OpenApp(target)
    }

    /**
     * "settings" → MAIN
     * "wifi settings" → WIFI
     * unknown "privacy settings" → MAIN
     */
    private fun settingsPageFor(
        phrase: String
    ): SettingsPage? {

        if (phrase == "settings") {
            return SettingsPage.MAIN
        }

        if (!phrase.endsWith(" settings")) {
            return null
        }

        return settingsWords[
            phrase.removeSuffix(" settings").trim()
        ] ?: SettingsPage.MAIN
    }

    private fun clean(input: String): String {

        var text = input
            .lowercase()
            .replace(
                Regex("[^a-z0-9 ]"),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()

        // Strip polite/wake words from the front.
        //
        // Example:
        // "hey assistant please call dad"
        // becomes:
        // "call dad"
        var changed = true

        while (changed) {

            changed = false

            for (prefix in fillerPrefixes) {

                if (text.startsWith("$prefix ")) {

                    text = text
                        .removePrefix("$prefix ")
                        .trim()

                    changed = true
                }
            }
        }

        return text
            .removeSuffix(" please")
            .removeSuffix(" for me")
            .trim()
    }
}