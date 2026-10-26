package dev.hongguo.dramaassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileDescriptor
import java.io.PrintWriter

class DramaAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = InspectionGate()
    private val engine = AutomationEngine()
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private var overlay: FloatingControl? = null
    private var screenReceiverRegistered = false
    private var verifying = false
    private var firstEventAt = 0L
    private var generation = 0L
    private var lastSignals = PageSignals(false)
    private var lastEventType = 0
    private var lastContentChanges = 0
    private var lastEventClass = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                overlay?.collapse()
                if (engine.running) pause("已锁屏，自动化已暂停")
            }
        }
    }

    private val inspectTask = Runnable {
        if (gate.begin(SystemClock.uptimeMillis()) && canOperate()) inspect()
    }

    private val verifyTask = Runnable {
        if (verifying && engine.running) {
            verifying = false
            if (!canOperate()) {
                pause("设备当前不可操作，已暂停")
            } else {
                val result = readPage(confirmDeparture = true)
                when {
                    result == null -> pause("无法读取页面，已暂停；请检查后重新开始")
                    !result.signals.isTarget -> pause("已离开红果，本次操作已结束")
                    result.signals.ordinary -> {
                        engine.shouldSwipe(result.signals)
                        RuntimeState.update("已划走广告，继续等待")
                    }
                    else -> pause("滑动后仍有广告标记，已暂停；请手动检查")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine.pause()
        cancelTasks()
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        RuntimeState.service = this
        configureEvents(false)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
        overlay = FloatingControl(this) { toggle() }
        overlay?.show()
        RuntimeState.running = false
        RuntimeState.update("权限已就绪，点击开始")
    }

    fun toggle() {
        if (engine.running) pause("已暂停") else start()
    }

    private fun start() {
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        cancelTasks()
        engine.start()
        RuntimeState.running = true
        configureEvents(true)
        overlay?.setRunning(true)
        RuntimeState.update("运行中，等待红果广告")
        requestInspection(SystemClock.uptimeMillis())
    }

    private fun pause(message: String) {
        engine.pause()
        RuntimeState.running = false
        cancelTasks()
        configureEvents(false)
        overlay?.setRunning(false)
        RuntimeState.update(message)
    }

    private fun configureEvents(enabled: Boolean) {
        val info = serviceInfo ?: return
        info.packageNames = arrayOf(TARGET_PACKAGE)
        info.notificationTimeout = 200
        info.eventTypes = if (enabled) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        } else {
            0
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !engine.running || event.packageName?.toString() != TARGET_PACKAGE) return
        RuntimeState.events++
        lastEventType = event.eventType
        lastContentChanges = event.contentChangeTypes
        lastEventClass = event.className?.toString().orEmpty()
        // Screen-off cancels the task via receiver; check power/keyguard once per inspection.
        if (verifying) return
        requestInspection(event.eventTime)
    }

    private fun requestInspection(eventTime: Long) {
        val now = SystemClock.uptimeMillis()
        val due = gate.request(now) ?: return
        firstEventAt = eventTime.coerceIn(0, now)
        handler.postAtTime(inspectTask, due)
    }

    private fun canOperate(): Boolean = engine.running && power.isInteractive && !keyguard.isKeyguardLocked

    private fun inspect() {
        if (verifying) return
        val page = readPage() ?: return
        if (!engine.shouldSwipe(page.signals)) return
        // Re-read the current root just before input: a window may have changed during traversal.
        val current = rootInActiveWindow ?: return
        val stillTarget = try {
            current.packageName?.toString() == TARGET_PACKAGE
        } finally {
            release(current)
        }
        if (!canOperate() || !stillTarget) return
        val area = page.bounds
        if (area.width() <= 0 || area.height() <= 0) {
            pause("播放区域不可用，已暂停")
            return
        }
        val path = Path().apply {
            moveTo(area.exactCenterX(), area.top + area.height() * 0.75f)
            lineTo(area.exactCenterX(), area.top + area.height() * 0.25f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        val token = generation
        verifying = true
        val submitted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (token == generation && engine.running && verifying) {
                    handler.postDelayed(verifyTask, 3_000)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (token == generation && engine.running) pause("滑动被取消，已暂停；请重新开始")
            }
        }, handler)
        if (!submitted) {
            pause("系统未接受滑动，已暂停；请检查无障碍权限")
            return
        }
        RuntimeState.gestures++
        RuntimeState.lastResponseMs = SystemClock.uptimeMillis() - firstEventAt
        RuntimeState.update("已识别广告并上滑，正在确认结果")
    }

    private data class Page(val signals: PageSignals, val bounds: Rect)

    private fun readPage(confirmDeparture: Boolean = false): Page? {
        val started = SystemClock.elapsedRealtimeNanos()
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != TARGET_PACKAGE) {
                lastSignals = PageSignals(false)
                return Page(PageSignals(false), Rect())
            }
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            // The system searches within the target process. Avoid one Binder call per child.
            val hasContinue = hasVisibleLabel(root, "上滑继续观看短剧")
            val checkAd = hasContinue || confirmDeparture
            val hasAd = checkAd && hasVisibleLabel(root, "广告")
            val signals = PageSignals(true, hasAd, hasContinue, fullyInspected = checkAd)
            lastSignals = signals
            return Page(signals, bounds)
        } catch (_: IllegalStateException) {
            // Windows can be removed while their nodes are being read. Wait for the next event.
            return null
        } finally {
            release(root)
            RuntimeState.inspections++
            val micros = (SystemClock.elapsedRealtimeNanos() - started) / 1_000
            RuntimeState.totalInspectMicros += micros
            RuntimeState.maxInspectMicros = maxOf(RuntimeState.maxInspectMicros, micros)
        }
    }

    private fun hasVisibleLabel(root: AccessibilityNodeInfo, expected: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(expected)
        try {
            return matches.any { node ->
                node.isVisibleToUser && matchesLabel(node.text, node.contentDescription, expected)
            }
        } finally {
            matches.forEach { if (it !== root) release(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }

    private fun cancelTasks() {
        generation++
        handler.removeCallbacks(inspectTask)
        handler.removeCallbacks(verifyTask)
        gate.cancel()
        verifying = false
    }

    override fun onInterrupt() {
        pause("无障碍服务被中断，已暂停")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("running=${engine.running}")
        writer.println("status=${RuntimeState.status}")
        writer.println("events=${RuntimeState.events}")
        writer.println("inspections=${RuntimeState.inspections}")
        writer.println("gestures=${RuntimeState.gestures}")
        writer.println("pendingInspection=${gate.dueAt != null}")
        writer.println("pendingVerification=$verifying")
        writer.println("subscribedEventTypes=${serviceInfo?.eventTypes ?: 0}")
        writer.println("totalInspectMicros=${RuntimeState.totalInspectMicros}")
        writer.println("maxInspectMicros=${RuntimeState.maxInspectMicros}")
        writer.println("lastResponseMs=${RuntimeState.lastResponseMs}")
        writer.println("uptimeMs=${SystemClock.uptimeMillis()}")
        writer.println("processCpuMs=${android.os.Process.getElapsedCpuTime()}")
        writer.println("pid=${android.os.Process.myPid()}")
        writer.println("elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        writer.println("lastPageTarget=${lastSignals.isTarget}")
        writer.println("lastHasAd=${lastSignals.hasAd}")
        writer.println("lastHasContinue=${lastSignals.hasContinue}")
        writer.println("lastEventType=$lastEventType")
        writer.println("lastContentChanges=$lastContentChanges")
        writer.println("lastEventClass=$lastEventClass")
        overlay?.dump(writer)
    }

    override fun onDestroy() {
        engine.pause()
        cancelTasks()
        overlay?.destroy()
        overlay = null
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        RuntimeState.service = null
        RuntimeState.running = false
        RuntimeState.update("无障碍服务已关闭")
        super.onDestroy()
    }
}
