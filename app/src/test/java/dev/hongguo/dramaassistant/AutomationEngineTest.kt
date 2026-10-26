package dev.hongguo.dramaassistant

import org.junit.Assert.*
import org.junit.Test

class AutomationEngineTest {
    private val ad = PageSignals(true, hasAd = true, hasContinue = true)
    private val ordinary = PageSignals(true)

    @Test fun `requires both visible labels in the target package`() {
        val engine = AutomationEngine().apply { start() }
        assertFalse(engine.shouldSwipe(PageSignals(false, true, true)))
        assertFalse(engine.shouldSwipe(PageSignals(true, true, false)))
        assertFalse(engine.shouldSwipe(PageSignals(true, false, true)))
        assertTrue(engine.shouldSwipe(ad))
    }

    @Test fun `only one gesture per ad despite duplicate and partially rebuilt pages`() {
        val engine = AutomationEngine().apply { start() }
        assertTrue(engine.shouldSwipe(ad))
        repeat(100) { assertFalse(engine.shouldSwipe(ad)) }
        assertFalse(engine.shouldSwipe(PageSignals(true, true, false)))
        assertFalse(engine.shouldSwipe(PageSignals(true, false, true)))
        assertFalse(engine.shouldSwipe(ad))
    }

    @Test fun `rearms after ordinary target page but not after another app`() {
        val engine = AutomationEngine().apply { start() }
        assertTrue(engine.shouldSwipe(ad))
        assertFalse(engine.shouldSwipe(PageSignals(false)))
        assertFalse(engine.shouldSwipe(ad))
        assertFalse(engine.shouldSwipe(ordinary))
        assertTrue(engine.shouldSwipe(ad))
    }

    @Test fun `paused and newly created engines cannot swipe`() {
        val engine = AutomationEngine()
        assertFalse(engine.shouldSwipe(ad))
        engine.start()
        engine.pause()
        assertFalse(engine.shouldSwipe(ad))
        engine.start()
        assertTrue(engine.shouldSwipe(ad))
    }

    @Test fun `negative prompt query alone cannot rearm a consumed advertisement`() {
        val engine = AutomationEngine().apply { start() }
        assertTrue(engine.shouldSwipe(ad))
        assertFalse(engine.shouldSwipe(PageSignals(true, fullyInspected = false)))
        assertFalse(engine.shouldSwipe(ad))
        assertFalse(engine.shouldSwipe(ordinary))
        assertTrue(engine.shouldSwipe(ad))
    }

    @Test fun `labels match exact text and content descriptions without advertiser false positives`() {
        assertFalse(matchesLabel("这不是广告标记", null, "广告"))
        assertFalse(matchesLabel("广告详情", null, "广告"))
        assertFalse(matchesLabel(null, null, "广告"))
        assertTrue(matchesLabel(null, " 广告 ", "广告"))
        assertTrue(matchesLabel("上滑继续观看短剧", null, "上滑继续观看短剧"))
    }

    @Test fun `burst events coalesce into one inspection without starvation`() {
        val gate = InspectionGate()
        assertEquals(1_000L, gate.request(1_000))
        repeat(1_000) { assertNull(gate.request(1_001)) }
        assertEquals(1_000L, gate.dueAt)
        assertTrue(gate.begin(1_010))
        assertEquals(1_510L, gate.request(1_020))
        repeat(1_000) { assertNull(gate.request(1_250)) }
        assertEquals(1_510L, gate.dueAt)
        assertFalse(gate.begin(1_509))
        assertTrue(gate.begin(1_510))
        assertNull(gate.dueAt)
    }

    @Test fun `no events means no scheduled work and cancellation removes pending inspection`() {
        val gate = InspectionGate()
        assertNull(gate.dueAt)
        assertFalse(gate.begin(100))
        gate.request(100)
        gate.cancel()
        assertNull(gate.dueAt)
        assertFalse(gate.begin(1_000))
        assertEquals(1_000L, gate.request(1_000))
    }
}
