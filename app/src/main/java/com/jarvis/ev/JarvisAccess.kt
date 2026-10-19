package com.jarvis.ev

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Jarvis'in "elleri ve gözleri": ekranı okur, dokunur, yazar, kaydırır. */
class JarvisAccess : AccessibilityService() {
    companion object {
        @Volatile var inst: JarvisAccess? = null
    }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun global(name: String): Boolean {
        val a = when (name) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else -1
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        return a != -1 && performGlobalAction(a)
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Ekran okunamadı (kilitli olabilir)."
        val sb = StringBuilder("Uygulama: ${root.packageName}. (* dokunulabilir, parantez içi x,y) Ekran: ")
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 3500 || !n.isVisibleToUser) return
            val t = n.text?.toString()?.trim().orEmpty()
            val cd = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (t.isNotEmpty()) t else cd
            if (label.isNotEmpty() || n.isEditable) {
                n.getBoundsInScreen(r)
                sb.append(if (n.isClickable) "*" else "")
                    .append(if (n.isEditable) "[yazı kutusu]" else "")
                    .append(label.take(80))
                    .append("(${r.centerX()},${r.centerY()}) | ")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null
        )
    }

    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val pts = when (dir) {
            "left" -> listOf(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f)
            "right" -> listOf(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f)
            "up" -> listOf(w * 0.5f, h * 0.3f, w * 0.5f, h * 0.7f)
            else -> listOf(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.3f)
        }
        val path = Path().apply { moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null
        )
    }

    fun longClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var x: AccessibilityNodeInfo? = n
            var d = 0
            while (x != null && d < 6) {
                if (x.isLongClickable && x.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
                x = x.parent
                d++
            }
        }
        return false
    }

    fun click(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var x: AccessibilityNodeInfo? = n
            var d = 0
            while (x != null && d < 6) {
                if (x.isClickable && x.isEnabled && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                x = x.parent
                d++
            }
        }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun type(text: String, submit: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        if (ok && submit && Build.VERSION.SDK_INT >= 30)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return ok
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /** down=true: içerikte aşağı in. */
    fun scroll(down: Boolean): Boolean {
        val node = findScrollable(rootInActiveWindow)
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node != null && node.performAction(act)) return true
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (down) 0.7f else 0.3f)
        val y2 = dm.heightPixels * (if (down) 0.3f else 0.7f)
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null
        )
    }
}
