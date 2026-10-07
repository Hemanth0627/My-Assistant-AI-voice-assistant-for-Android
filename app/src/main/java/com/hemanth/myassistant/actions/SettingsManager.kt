package com.hemanth.myassistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.hemanth.myassistant.model.SettingsPage

class SettingsManager(context: Context) {

    private val appContext = context.applicationContext

    fun open(page: SettingsPage): ActionResult {
        val result = appContext.startSafely(
            intentFor(page),
            successMessage = "Opening ${page.spokenName}.",
            failureMessage = ""
        )
        if (result.success || page == SettingsPage.MAIN) return result

        // This phone doesn't have that exact screen: fall back to the main Settings page.
        return appContext.startSafely(
            Intent(Settings.ACTION_SETTINGS),
            successMessage = "I couldn't open ${page.spokenName} directly, so I opened Settings.",
            failureMessage = "I couldn't open Settings."
        )
    }

    private fun intentFor(page: SettingsPage): Intent = when (page) {
        SettingsPage.MAIN -> Intent(Settings.ACTION_SETTINGS)
        SettingsPage.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsPage.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsPage.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        SettingsPage.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        SettingsPage.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        SettingsPage.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SettingsPage.NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        SettingsPage.AIRPLANE_MODE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        SettingsPage.APPS -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        SettingsPage.THIS_APP -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null)
        )
    }
}