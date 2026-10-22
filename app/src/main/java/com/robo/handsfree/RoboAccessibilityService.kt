package com.robo.handsfree

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.robo.handsfree.core.normalizeText

/**
 * The hands. Everything Robo does to the phone beyond simple intents happens here:
 * finding a button by its label, filling a text box, scrolling, opening quick settings
 * or reading the screen aloud.
 */
class RoboAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------ tapping

    fun clickText(target: String): Boolean {
        val node = findNode(target) ?: return false
        val clickable = clickableAncestor(node) ?: node
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun clickAny(targets: List<String>): Boolean {
        for (target in targets) {
            if (clickText(target)) return true
        }
        return false
    }

    fun typeText(value: String): Boolean {
        val nodes = allNodes()
        val field = nodes.firstOrNull { it.isEditable && it.isFocused }
            ?: nodes.firstOrNull { it.isEditable }
            ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(forward: Boolean): Boolean {
        val scrollable = allNodes().firstOrNull { it.isScrollable }
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        return swipe(forward)
    }

    private fun swipe(forward: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val top = metrics.heightPixels * 0.26f
        val bottom = metrics.heightPixels * 0.74f
        val path = Path().apply {
            moveTo(x, if (forward) bottom else top)
            lineTo(x, if (forward) top else bottom)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Clicks a quick-settings tile such as Wi-Fi or Bluetooth. Returns true if it was handled. */
    fun clickQuickTile(labels: List<String>, desiredOn: Boolean?): Boolean {
        val nodes = allNodes()
        for (label in labels) {
            val node = nodes.firstOrNull { matches(it, label) } ?: continue
            val stateText = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ")
                .lowercase()
            val looksOn = Regex("\\bon\\b").containsMatchIn(stateText) && !stateText.contains("off")
            if (desiredOn != null && looksOn == desiredOn) return true
            val target = clickableAncestor(node) ?: node
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ reading

    /** Package currently in front, used to avoid pressing buttons in the wrong app. */
    fun currentPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (t: Throwable) {
        null
    }

    fun screenText(): String {
        val parts = LinkedHashSet<String>()
        for (node in allNodes()) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        }
        return parts.joinToString(". ").take(1200)
    }

    // --------------------------------------------------------------- screenshots

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        screenshot.hardwareBuffer.close()
                    }
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            }
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun findNode(target: String): AccessibilityNodeInfo? {
        val wanted = normalizeText(target)
        if (wanted.isEmpty()) return null
        var partial: AccessibilityNodeInfo? = null
        for (node in allNodes()) {
            for (value in listOfNotNull(node.text?.toString(), node.contentDescription?.toString())) {
                if (value.isBlank()) continue
                val normalised = normalizeText(value)
                if (normalised == wanted) return node
                if (partial == null && normalised.contains(wanted)) partial = node
            }
        }
        return partial
    }

    private fun matches(node: AccessibilityNodeInfo, label: String): Boolean {
        val wanted = normalizeText(label)
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .any { it.isNotBlank() && normalizeText(it).contains(wanted) }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = try {
                current.parent
            } catch (t: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (t: Throwable) {
            // Window went away between calls; fall through.
        }
        try {
            windows?.forEach { window ->
                val root = try {
                    window.root
                } catch (t: Throwable) {
                    null
                }
                if (root != null && roots.none { it == root }) roots.add(root)
            }
        } catch (t: Throwable) {
            // No permission for other windows on some OEM skins.
        }

        val result = ArrayList<AccessibilityNodeInfo>(256)
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard < 1500) {
                val node = queue.removeFirst()
                guard++
                result.add(node)
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(queue::add)
                }
            }
        }
        return result
    }

    companion object {
        @Volatile
        var instance: RoboAccessibilityService? = null
            private set
    }
}
