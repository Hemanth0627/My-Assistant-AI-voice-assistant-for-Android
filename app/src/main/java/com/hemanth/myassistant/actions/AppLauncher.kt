package com.hemanth.myassistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.MediaStore

class AppLauncher(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    private data class InstalledApp(val label: String, val packageName: String)

    // Nicknames people say → the name shown under the app icon.
    private val aliases = mapOf(
        "yt" to "youtube",
        "insta" to "instagram",
        "google maps" to "maps",
        "google chrome" to "chrome",
        "mail" to "gmail",
        "email" to "gmail",
        "google play" to "play store"
    )

    fun launch(requestedName: String): ActionResult {
        val name = normalize(requestedName)
        if (name.isEmpty() || name.length > 50) {
            return ActionResult(false, "Which app should I open?")
        }
        val target = aliases[name] ?: name
        val displayName = titleCase(name)

        // 1. Kinds of apps Android can open directly (works with any brand's app).
        systemAppIntent(target)?.let { intent ->
            return appContext.startSafely(
                intent,
                successMessage = "Opening $displayName.",
                failureMessage = "I couldn't find a $displayName app on this phone."
            )
        }

        // 2. Search the installed apps by the name under their icon.
        val app = findInstalledApp(target)
            ?: return ActionResult(false, "I couldn't find an app called $displayName on this phone.")

        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ActionResult(false, "${app.label} is installed, but it can't be opened directly.")

        return appContext.startSafely(
            launchIntent,
            successMessage = "Opening ${app.label}.",
            failureMessage = "I couldn't open ${app.label}."
        )
    }

    private fun systemAppIntent(name: String): Intent? = when (name) {
        "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        "browser", "web browser", "internet" -> selector(Intent.CATEGORY_APP_BROWSER)
        "calculator" -> selector(Intent.CATEGORY_APP_CALCULATOR)
        "calendar" -> selector(Intent.CATEGORY_APP_CALENDAR)
        "contacts" -> selector(Intent.CATEGORY_APP_CONTACTS)
        "gallery" -> selector(Intent.CATEGORY_APP_GALLERY)
        "messages", "sms" -> selector(Intent.CATEGORY_APP_MESSAGING)
        // ACTION_DIAL only OPENS the dialer. It never places a call by itself.
        "phone", "dialer", "dialler" -> Intent(Intent.ACTION_DIAL)
        else -> null
    }

    private fun selector(category: String): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

    private fun findInstalledApp(name: String): InstalledApp? {
        val wanted = compact(name)
        val apps = launchableApps()

        // Exact match first ("youtube" == "YouTube")...
        apps.firstOrNull { compact(it.label) == wanted }?.let { return it }

        // ...then a partial match ("maps" in "Google Maps"), preferring the shortest name.
        if (wanted.length < 3) return null
        return apps
            .filter { compact(it.label).contains(wanted) }
            .minByOrNull { it.label.length }
    }

    /** Every app that has a home-screen icon (needs the <queries> entry in the manifest). */
    private fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
        return results
            .map { InstalledApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != appContext.packageName } // don't "open" ourselves
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\b(the|app|application)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun compact(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun titleCase(text: String): String =
        text.split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}