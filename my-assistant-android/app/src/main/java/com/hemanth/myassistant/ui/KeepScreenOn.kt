package com.hemanth.myassistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.hemanth.myassistant.model.AssistantStatus
import kotlinx.coroutines.delay

/** How long the screen stays on after the assistant finishes, so you can read the answer. */
private const val READ_TIME_MS = 15_000L

/**
 * Keeps the screen awake while the assistant is busy (listening, thinking, speaking,
 * or waiting for a confirmation tap), plus a short time afterwards.
 * Uses Android's official "keep screen on" flag: no permission needed,
 * and it only applies while this screen is visible.
 */
@Composable
fun KeepScreenOnWhileActive(status: AssistantStatus, confirmationOpen: Boolean) {
    val view = LocalView.current
    val active = status != AssistantStatus.IDLE || confirmationOpen
    var keepOn by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (active) {
            keepOn = true
        } else {
            // Give time to read the answer. If the assistant becomes active again
            // (e.g. conversation mode listens again), this wait is cancelled automatically.
            delay(READ_TIME_MS)
            keepOn = false
        }
    }

    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false } // leaving the screen → normal timeout again
    }
}

/** Keeps the screen awake for as long as this screen is shown (used by the panel). */
@Composable
fun KeepScreenOnAlways() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
