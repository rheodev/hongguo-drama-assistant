package dev.hongguo.dramaassistant

internal const val TARGET_PACKAGE = "com.phoenix.read"

/** An event may schedule one inspection, but may never postpone one already pending. */
internal class InspectionGate(private val intervalMs: Long = 500) {
    private var lastStartedAt: Long? = null
    var dueAt: Long? = null
        private set

    fun request(now: Long): Long? {
        if (dueAt != null) return null
        val next = lastStartedAt?.let { maxOf(now, it + intervalMs) } ?: now
        dueAt = next
        return next
    }

    fun begin(now: Long): Boolean {
        val due = dueAt ?: return false
        if (now < due) return false
        dueAt = null
        lastStartedAt = now
        return true
    }

    fun cancel() {
        dueAt = null
        lastStartedAt = null
    }
}

internal data class PageSignals(
    val isTarget: Boolean,
    val hasAd: Boolean = false,
    val hasContinue: Boolean = false,
    val fullyInspected: Boolean = true,
) {
    val ready: Boolean get() = isTarget && hasAd && hasContinue
    val ordinary: Boolean get() = isTarget && fullyInspected && !hasAd && !hasContinue
}

internal fun matchesLabel(text: CharSequence?, description: CharSequence?, expected: String): Boolean =
    text?.toString()?.trim() == expected || description?.toString()?.trim() == expected

/** Re-arm only after an ordinary target page, never during a partially built ad page. */
internal class AutomationEngine {
    var running = false
        private set
    private var consumed = false

    fun start() {
        running = true
        consumed = false
    }

    fun pause() {
        running = false
        consumed = false
    }

    fun shouldSwipe(page: PageSignals): Boolean {
        if (!running || !page.isTarget) return false
        if (page.ordinary) consumed = false
        if (!page.ready || consumed) return false
        consumed = true
        return true
    }
}
