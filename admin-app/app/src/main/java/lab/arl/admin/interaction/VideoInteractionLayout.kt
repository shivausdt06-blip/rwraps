package lab.arl.admin.interaction

import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.GestureDetectorCompat
import org.webrtc.SurfaceViewRenderer

/**
 * Hosts [SurfaceViewRenderer] with a transparent native touch layer above it.
 * SurfaceView punches through Compose; this guarantees Managed Mode receives pointer events.
 */
class VideoInteractionLayout(
    context: Context,
    onRendererReady: (SurfaceViewRenderer) -> Unit
) : FrameLayout(context) {
    val renderer: SurfaceViewRenderer = SurfaceViewRenderer(context)
    private val touchOverlay = android.view.View(context)
    private var interactionEnabled = false
    private var dragStart: Pair<Float, Float>? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    var touchListener: TouchListener? = null

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (!interactionEnabled) return false
                touchListener?.onTap(event.x, event.y)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (!interactionEnabled) return
                touchListener?.onLongPress(event.x, event.y)
            }
        }
    )

    init {
        renderer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        touchOverlay.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        touchOverlay.setBackgroundColor(Color.TRANSPARENT)
        renderer.isClickable = false
        renderer.isFocusable = false
        renderer.isFocusableInTouchMode = false
        touchOverlay.setOnTouchListener { _, event ->
            if (!interactionEnabled) return@setOnTouchListener false
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStart = event.x to event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val start = dragStart
                    dragStart = null
                    if (start != null) {
                        val dx = event.x - start.first
                        val dy = event.y - start.second
                        if (kotlin.math.hypot(dx, dy) >= touchSlop) {
                            touchListener?.onSwipe(start.first, start.second, event.x, event.y)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragStart = null
                    true
                }
                else -> true
            }
        }
        addView(renderer)
        addView(touchOverlay)
        touchOverlay.bringToFront()
        renderer.post { onRendererReady(renderer) }
    }

    fun setInteractionEnabled(enabled: Boolean) {
        interactionEnabled = enabled
        touchOverlay.isClickable = enabled
        touchOverlay.isFocusable = enabled
        touchOverlay.isFocusableInTouchMode = enabled
    }

    interface TouchListener {
        fun onTap(x: Float, y: Float)
        fun onLongPress(x: Float, y: Float)
        fun onSwipe(x1: Float, y1: Float, x2: Float, y2: Float)
    }
}
