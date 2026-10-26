package dev.hongguo.dramaassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.io.PrintWriter
import kotlin.math.abs

/** Only an interaction can schedule the single hide task; collapsed controls have no timer. */
@SuppressLint("RtlHardcoded") // Window coordinates use physical screen edges.
internal class FloatingControl(context: Context, private val toggle: () -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("floating_control", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val fullWidth = dp(48)
    private val collapsedWidth = dp(28)
    private val height = dp(48)
    private var attached = false
    private var running = false
    private var expanded = false
    private var dockLeft = true
    private var dragging = false
    private var hideScheduled = false
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0
    private var initialY = 0

    private val params = WindowManager.LayoutParams(
        collapsedWidth, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = preferences.getInt("x", 0)
        y = preferences.getInt("y", dp(240))
        title = "短剧切集助手悬浮控制"
    }

    private val control = ControlView(context)
    private val hideTask = Runnable {
        hideScheduled = false
        if (attached && !dragging) collapse()
    }

    private inner class ControlView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val oval = RectF()
        private val play = context.getDrawable(R.drawable.ic_play)!!
        private val pause = context.getDrawable(R.drawable.ic_pause)!!

        init {
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
            updateDescription()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = 20 * density
            // The clipped circle exposes only a 16dp arc at the screen edge.
            val centerX = when {
                expanded -> width / 2f
                dockLeft -> -4 * density
                else -> width + 4 * density
            }
            val centerY = height / 2f
            oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            fill.color = if (isPressed) 0xD9141B17.toInt() else 0x99141B17.toInt()
            stroke.color = if (isPressed) 0x66FFFFFF else 0x38FFFFFF
            canvas.drawOval(oval, fill)
            canvas.drawOval(oval, stroke)
            if (expanded) {
                val icon = if (running) pause else play
                val half = dp(10)
                val cx = width / 2
                val cy = height / 2
                icon.setBounds(cx - half, cy - half, cx + half, cy + half)
                icon.draw(canvas)
            }
        }

        // The delegated handler calls this view's performClick for a completed tap.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Keep dragging the small handle from becoming the system Back gesture.
            if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (!expanded) expand() else toggle()
            scheduleHide()
            return true
        }

        fun updateDescription() {
            contentDescription = if (!expanded) {
                "展开自动切集控制，当前${if (running) "运行中" else "已暂停"}；可拖动"
            } else if (running) {
                "暂停自动切集；可拖动，三秒无操作后收起"
            } else {
                "开始自动切集；可拖动，三秒无操作后收起"
            }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelHide()
                downX = event.rawX
                downY = event.rawY
                initialX = params.x
                initialY = params.y
                dragging = false
                control.isPressed = true
                control.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    if (!expanded) {
                        expand()
                        initialX = params.x
                        initialY = params.y
                    }
                }
                if (dragging) {
                    control.isPressed = false
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    clampPosition()
                    updateLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                control.isPressed = false
                control.invalidate()
                val wasDragging = dragging
                dragging = false
                if (wasDragging) {
                    dockAndSave()
                    scheduleHide()
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    control.performClick()
                } else if (expanded) {
                    scheduleHide()
                }
            }
        }
        return true
    }

    fun show() {
        if (attached) return
        clampPosition(snapToEdge = true)
        windowManager.addView(control, params)
        attached = true
    }

    private fun expand() {
        expanded = true
        params.width = fullWidth
        placeAtEdge()
        control.updateDescription()
        updateLayout()
    }

    fun collapse() {
        cancelHide()
        dragging = false
        control.isPressed = false
        expanded = false
        params.width = collapsedWidth
        placeAtEdge()
        control.updateDescription()
        updateLayout()
    }

    fun setRunning(value: Boolean) {
        running = value
        control.updateDescription()
        control.invalidate()
    }

    private fun scheduleHide() {
        cancelHide()
        if (!attached || !expanded) return
        hideScheduled = true
        handler.postDelayed(hideTask, 3_000)
    }

    private fun cancelHide() {
        handler.removeCallbacks(hideTask)
        hideScheduled = false
    }

    fun onConfigurationChanged() {
        collapse()
    }

    private fun dockAndSave() {
        clampPosition(snapToEdge = true)
        updateLayout()
        preferences.edit().putInt("x", params.x).putInt("y", params.y).apply()
    }

    private fun updateLayout() {
        if (attached) windowManager.updateViewLayout(control, params)
        control.invalidate()
    }

    private fun placeAtEdge() {
        val bounds = displayBounds()
        params.x = if (dockLeft) 0 else (bounds.width() - params.width).coerceAtLeast(0)
        params.y = params.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
    }

    private fun clampPosition(snapToEdge: Boolean = false) {
        val bounds = displayBounds()
        val maxX = (bounds.width() - params.width).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        if (snapToEdge) {
            dockLeft = params.x <= maxX / 2
            params.x = if (dockLeft) 0 else maxX
        }
        params.y = params.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
    }

    private fun displayBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        android.util.DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
            .let { Rect(0, 0, it.widthPixels, it.heightPixels) }
    }

    fun dump(writer: PrintWriter) {
        writer.println("overlayExpanded=$expanded")
        writer.println("pendingOverlayHide=$hideScheduled")
        writer.println("overlayDockLeft=$dockLeft")
        writer.println("overlayX=${params.x}")
        writer.println("overlayY=${params.y}")
        writer.println("overlayWidth=${params.width}")
    }

    fun destroy() {
        cancelHide()
        if (attached) windowManager.removeView(control)
        attached = false
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()
}
