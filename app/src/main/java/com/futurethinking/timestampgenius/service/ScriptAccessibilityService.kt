package com.futurethinking.timestampgenius.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScriptAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We query a fresh root when needed instead of retaining stale node trees.
    }

    override fun onInterrupt() {
        // Nothing to cancel.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeService === this) {
            activeService = null
        }
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        private var activeService: ScriptAccessibilityService? = null

        fun currentText(): String {
            val root = activeService?.rootInActiveWindow ?: return ""

            return try {
                val builder = StringBuilder()
                appendText(root, builder)
                builder.toString().trim()
            } finally {
                root.recycle()
            }
        }

        fun scrollForward(): Boolean {
            val root = activeService?.rootInActiveWindow ?: return false

            return try {
                val scrollable = findScrollable(root) ?: return false
                scrollable.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                )
            } finally {
                root.recycle()
            }
        }

        private fun findScrollable(
            node: AccessibilityNodeInfo
        ): AccessibilityNodeInfo? {
            if (node.isScrollable) return node

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val result = findScrollable(child)
                if (result != null) return result
            }

            return null
        }

        private fun appendText(
            node: AccessibilityNodeInfo,
            builder: StringBuilder
        ) {
            node.text?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    builder.append(it).append(' ')
                }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let {
                    appendText(it, builder)
                }
            }
        }
    }
}
