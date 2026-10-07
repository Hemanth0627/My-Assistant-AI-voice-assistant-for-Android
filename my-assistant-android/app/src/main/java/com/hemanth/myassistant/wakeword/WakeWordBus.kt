package com.hemanth.myassistant.wakeword

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Shared state between the wake-word service and the screens. */
object WakeWordBus {
    /** How many assistant screens (full app / panel) are visible right now. */
    val screensVisible = MutableStateFlow(0)

    /** True while WakeWordService is running (drives the on/off switch). */
    val serviceRunning = MutableStateFlow(false)

    /** Last start-up problem, shown under the switch. */
    val lastError = MutableStateFlow<String?>(null)

    /** True ONLY while Vosk has the microphone open. */
    val voskHoldsMic = MutableStateFlow(false)

    /**
     * Waits until Vosk has released the microphone.
     * The timeout is only a safety net in case the service died without updating the flag.
     */
    suspend fun awaitMicFree(timeoutMs: Long = 2000) {
        withTimeoutOrNull(timeoutMs) { voskHoldsMic.first { holds -> !holds } }
    }
}