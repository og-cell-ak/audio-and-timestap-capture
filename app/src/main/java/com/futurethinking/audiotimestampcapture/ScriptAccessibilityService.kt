package com.futurethinking.audiotimestampcapture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScriptAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ScriptAccessibilityService? = null

        fun scrollForward(): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            return scrollNode(root)
        }

        private fun scrollNode(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable &&
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (scrollNode(child)) return true
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally passive. Scrolling is requested only while recording.
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
