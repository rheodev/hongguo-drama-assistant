package dev.hongguo.dramaassistant

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-process state; listeners are attached only while a screen is visible. Main thread only. */
internal object RuntimeState {
    var service: DramaAccessibilityService? = null
    var running = false
    var status = "请先开启无障碍权限"
    var inspections = 0L
    var events = 0L
    var gestures = 0L
    var totalInspectMicros = 0L
    var maxInspectMicros = 0L
    var lastResponseMs = 0L
    private val records = ArrayDeque<String>(50)
    private val listeners = mutableSetOf<() -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(message: String, record: Boolean = true) {
        status = message
        if (record) {
            if (records.size == 50) records.removeFirst()
            records.addLast("${timeFormat.format(Date())}  $message")
        }
        listeners.toList().forEach { it() }
    }

    fun recentRecords(): String = records.toList().asReversed().joinToString("\n\n")
}
