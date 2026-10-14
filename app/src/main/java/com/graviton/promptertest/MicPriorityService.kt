package com.graviton.promptertest

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Intentionally empty. Being an enabled accessibility service is what lets Android keep feeding
 * microphone audio to this app while a camera app records at the same time.
 * `connected` tells the rest of the app whether Android is really running it.
 */
class MicPriorityService : AccessibilityService() {

    companion object {
        @Volatile var connected = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
