package com.graviton.promptertest

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Intentionally empty. Being an enabled accessibility service is (we hope) what lets Android
 * keep feeding microphone audio to this app while a camera app records at the same time.
 */
class MicPriorityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
