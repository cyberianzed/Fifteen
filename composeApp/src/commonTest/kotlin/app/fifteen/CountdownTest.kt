package app.fifteen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountdownTest {
    private val s = 1_000L
    private val m = 60 * s
    private val h = 60 * m
    private val d = 24 * h

    @Test
    fun theCountdownReadsNaturallyAtEveryScale() {
        assertEquals("3d 04:12:05", countdownText(3 * d + 4 * h + 12 * m + 5 * s))
        assertEquals("04:12:05", countdownText(4 * h + 12 * m + 5 * s))
        assertEquals("12:05", countdownText(12 * m + 5 * s))
        assertEquals("00:00", countdownText(-5 * s))
        // A part of a second still counts as a second left, so it never shows 00:00 early.
        assertEquals("00:01", countdownText(300))
    }

    @Test
    fun thePetsShortFormFitsItsLittleTimer() {
        assertEquals("3d 04h", countdownShort(3 * d + 4 * h + 59 * m))
        assertEquals("04:12:05", countdownShort(4 * h + 12 * m + 5 * s))
    }

    @Test
    fun itOnlyShowsWhenOnWithADate() {
        val p = Profile(addons = true)
        assertFalse(countdownActive(p))
        assertFalse(countdownActive(p.copy(countdownOn = true)))
        assertTrue(countdownActive(p.copy(countdownOn = true, countdownAt = 1)))
        assertFalse(countdownActive(p.copy(addons = false, countdownOn = true, countdownAt = 1)))
    }

    @Test
    fun skippingACheckInKeepsTheBlockWaitingToBeRated() {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        store.start("deep", 15)
        val live = store.liveBlock()!!
        store.skipCheckIn(live.id)
        assertEquals(live.id, store.skippedCheckIn.value)
        assertEquals(Phase.Due, store.phase(live.endAt + 1)) // still there to rate later
        assertFalse(store.db.value.blocks.first { it.id == live.id }.rated)
    }

    @Test
    fun onlyTheDeviceThatStartedABlockPopsUpItsCheckIn() {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        store.start("deep", 15)
        val mine = store.liveBlock()!!
        assertTrue(store.startedHere(mine))
        assertFalse(store.startedHere(mine.copy(device = "phone123")))
        assertTrue(store.startedHere(mine.copy(device = ""))) // older blocks without a device: ask everywhere
    }
}
