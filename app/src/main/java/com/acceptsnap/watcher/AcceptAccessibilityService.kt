package com.acceptsnap.watcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AcceptAccessibilityService : AccessibilityService() {

    private var lastAccessibilityTriggerMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (WatcherConfig.getDetectionMode(this) == "ocr_only") return
        val root = rootInActiveWindow ?: return

        val keywords = WatcherConfig.getKeywords(this)
        val matchedKeyword = findKeywordInNodeTree(root, keywords) ?: return

        val now = System.currentTimeMillis()
        if (now - lastAccessibilityTriggerMs < WatcherConfig.getCooldownMs(this)) return
        lastAccessibilityTriggerMs = now

        if (ScreenWatcherService.isRunning) {
            val triggerIntent = Intent(this, ScreenWatcherService::class.java).apply {
                action = ScreenWatcherService.ACTION_TRIGGER_IMMEDIATE
                putExtra(ScreenWatcherService.EXTRA_TRIGGER_KEYWORD, matchedKeyword)
                putExtra(ScreenWatcherService.EXTRA_TRIGGER_SOURCE, "accessibility_node")
            }
            startService(triggerIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        val softCopy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBuffer.close()
                        if (softCopy != null) {
                            ScreenshotStorage.saveBitmap(this@AcceptAccessibilityService, softCopy)
                            softCopy.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) = Unit
                }
            )
        }
    }

    private fun findKeywordInNodeTree(node: AccessibilityNodeInfo?, keywords: List<String>): String? {
        if (node == null) return null

        val nodeText = node.text?.toString() ?: ""
        WatcherConfig.matchesKeyword(this, nodeText, keywords)?.let { return it }

        val contentDesc = node.contentDescription?.toString() ?: ""
        WatcherConfig.matchesKeyword(this, contentDesc, keywords)?.let { return it }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val childMatch = findKeywordInNodeTree(child, keywords)
            if (childMatch != null) return childMatch
        }
        return null
    }

    override fun onInterrupt() = Unit
}
