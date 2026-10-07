package com.hemanth.myassistant.actions

import android.content.Context
import android.os.BatteryManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class DeviceInfo(context: Context) {

    private val appContext = context.applicationContext

    fun currentTime(): ActionResult {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
        return ActionResult(true, "It's $time on $date.")
    }

    fun batteryStatus(): ActionResult {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
            ?: return ActionResult(false, "I couldn't read the battery status.")

        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return ActionResult(false, "I couldn't read the battery level.")

        val charging = if (batteryManager.isCharging) " and charging" else ""
        return ActionResult(true, "Battery is at $level percent$charging.")
    }
}