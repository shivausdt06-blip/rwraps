package lab.arl.target.interaction

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class RemoteInteractionService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        InteractionDiag.log("accessibility_service_connected")
        listeners.forEach { it(true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            connected = false
            listeners.forEach { it(false) }
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            connected = false
            listeners.forEach { it(false) }
        }
        super.onDestroy()
    }

    suspend fun execute(command: InteractionCommand): Pair<Boolean, String?> {
        val (width, height) = screenSize()
        return when (command.operation) {
            "BACK" -> global(GLOBAL_ACTION_BACK)
            "HOME" -> global(GLOBAL_ACTION_HOME)
            "RECENTS" -> global(GLOBAL_ACTION_RECENTS)
            "TAP" -> tap(command.params.nx!!, command.params.ny!!, width, height, command.params.durationMs ?: 50)
            "LONG_PRESS" -> tap(command.params.nx!!, command.params.ny!!, width, height, command.params.durationMs ?: 600)
            "SWIPE" -> swipe(
                command.params.nx!!, command.params.ny!!,
                command.params.nx2!!, command.params.ny2!!,
                width, height,
                command.params.durationMs ?: 300
            )
            "SCROLL" -> scroll(command.params.direction ?: "DOWN", width, height, command.params.durationMs ?: 350)
            "NODE_CLICK" -> nodeAction(command.params.viewId!!, AccessibilityNodeInfo.ACTION_CLICK, "click")
            "NODE_FOCUS" -> nodeAction(command.params.viewId!!, AccessibilityNodeInfo.ACTION_FOCUS, "focus")
            "TEXT_ENTRY" -> setText(command.params.text!!)
            else -> false to "Unsupported operation"
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun global(action: Int): Pair<Boolean, String?> {
        val ok = performGlobalAction(action)
        return ok to if (ok) null else "Android rejected the global action"
    }

    private suspend fun tap(nx: Double, ny: Double, w: Int, h: Int, duration: Long): Pair<Boolean, String?> {
        val x = (nx * w).toFloat()
        val y = (ny * h).toFloat()
        InteractionDiag.log("gesture_dispatch_attempt", extra = "op=TAP x=${x.toInt()} y=${y.toInt()} duration=$duration")
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, duration)
    }

    private suspend fun swipe(nx: Double, ny: Double, nx2: Double, ny2: Double, w: Int, h: Int, duration: Long): Pair<Boolean, String?> {
        val path = Path().apply {
            moveTo((nx * w).toFloat(), (ny * h).toFloat())
            lineTo((nx2 * w).toFloat(), (ny2 * h).toFloat())
        }
        return gesture(path, duration)
    }

    private suspend fun scroll(direction: String, w: Int, h: Int, duration: Long): Pair<Boolean, String?> {
        val cx = w / 2f
        val cy = h / 2f
        val path = Path()
        when (direction) {
            "UP" -> {
                path.moveTo(cx, cy + h * 0.2f)
                path.lineTo(cx, cy - h * 0.2f)
            }
            "DOWN" -> {
                path.moveTo(cx, cy - h * 0.2f)
                path.lineTo(cx, cy + h * 0.2f)
            }
            "LEFT" -> {
                path.moveTo(cx + w * 0.2f, cy)
                path.lineTo(cx - w * 0.2f, cy)
            }
            else -> {
                path.moveTo(cx - w * 0.2f, cy)
                path.lineTo(cx + w * 0.2f, cy)
            }
        }
        return gesture(path, duration)
    }

    private suspend fun gesture(path: Path, duration: Long): Pair<Boolean, String?> {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(1, 5_000))
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val ok = suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                Handler(Looper.getMainLooper())
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
        if (ok) {
            InteractionDiag.log("gesture_dispatch_success")
        } else {
            InteractionDiag.log("gesture_dispatch_failure", extra = "dispatchGesture returned false")
        }
        return ok to if (ok) null else "dispatchGesture was cancelled or unavailable"
    }

    private fun nodeAction(viewId: String, action: Int, label: String): Pair<Boolean, String?> {
        val root = rootInActiveWindow ?: return false to "No active window"
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
        val node = nodes.firstOrNull() ?: findByContentDescription(root, viewId)
        if (node == null) {
            return false to "Accessible node not found"
        }
        if (node.isPassword) {
            return false to "Password fields are not controllable"
        }
        val ok = node.performAction(action)
        return ok to if (ok) null else "Android rejected node $label"
    }

    private fun setText(text: String): Pair<Boolean, String?> {
        val root = rootInActiveWindow ?: return false to "No active window"
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused == null) {
            return false to "No focused editable node"
        }
        if (focused.isPassword) {
            return false to "Password fields are not controllable"
        }
        if (!focused.isEditable && !focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            return false to "Focused node does not accept accessibility text"
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ok to if (ok) null else "ACTION_SET_TEXT was rejected"
    }

    private fun findByContentDescription(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString() == needle) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findByContentDescription(child, needle)
            if (match != null) return match
        }
        return null
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        var instance: RemoteInteractionService? = null
            private set

        private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        fun addListener(listener: (Boolean) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (Boolean) -> Unit) {
            listeners.remove(listener)
        }
    }
}
