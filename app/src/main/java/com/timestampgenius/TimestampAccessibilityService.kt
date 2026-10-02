package com.timestampgenius

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TimestampAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: TimestampAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun scrollUp(distance: Float) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val centerX = screenWidth * 0.5f
        val startY = screenHeight * 0.72f
        val endY = (startY - screenHeight * distance).coerceAtLeast(screenHeight * 0.15f)
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
