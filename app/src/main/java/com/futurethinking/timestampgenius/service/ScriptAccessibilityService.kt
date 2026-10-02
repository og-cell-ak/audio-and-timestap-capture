package com.futurethinking.timestampgenius.service
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference
class ScriptAccessibilityService:AccessibilityService(){override fun onAccessibilityEvent(event:AccessibilityEvent?){if(event!=null)latestRoot.set(rootInActiveWindow)};override fun onInterrupt(){latestRoot.set(null)}
companion object{private val latestRoot=AtomicReference<AccessibilityNodeInfo?>(null);fun currentText():String{val root=latestRoot.get()?:return "";val sb=StringBuilder();walk(root,sb);return sb.toString().trim()};fun scrollForward():Boolean{val root=latestRoot.get()?:return false;val node=findScrollable(root)?:return false;return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)};private fun findScrollable(n:AccessibilityNodeInfo):AccessibilityNodeInfo?{if(n.isScrollable)return n;for(i in 0 until n.childCount)n.getChild(i)?.let{findScrollable(it)?.let{return it}};return null};private fun walk(n:AccessibilityNodeInfo,s:StringBuilder){n.text?.toString()?.takeIf{it.isNotBlank()}?.let{s.append(it).append(' ')};for(i in 0 until n.childCount)n.getChild(i)?.let{walk(it,s)}}}}
