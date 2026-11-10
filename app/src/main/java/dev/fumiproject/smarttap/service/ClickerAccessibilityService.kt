package dev.fumiproject.smarttap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class ClickerAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClickerAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 1回タップ */
    fun tap(x: Float, y: Float, durationMs: Long = 50L, onComplete: (() -> Unit)? = null) {
        stroke(x, y, x, y, durationMs, onComplete)
    }

    /**
     * 1本指の操作。始点と終点が同じならタップ／長押し、違えばスワイプ。
     * durationMs が押している時間。
     */
    fun stroke(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long, onComplete: (() -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke()
            }
        }, null)
    }
}
