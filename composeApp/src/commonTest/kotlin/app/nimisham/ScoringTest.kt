package app.nimisham

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemFiles : TextFiles {
    // Tests stay silent.
    val map = mutableMapOf("prefs.json" to "{\"sounds\":false}")
    override fun read(name: String) = map[name]
    override fun write(name: String, text: String) { map[name] = text }
}

class ScoringTest {
    private val t0 = 1_700_000_000_000L
    private fun block(id: String, start: Long, rating: Int? = null, bounty: String? = null, reward: String? = null) =
        Block(id, "deep", startAt = start, minutes = 15, rating = rating, ratedAt = rating?.let { start + 15 * 60_000L }, bounty = bounty, reward = reward)

    @Test
    fun pointsNeverNegativeAndDriftEarnsHonestyPoint() {
        val o = score(block("a", t0), 0, rest = false, history = emptyList())
        assertEquals(1, o.points)
        assertEquals("comeback", o.nextBounty)
        assertNull(o.reward)
    }

    @Test
    fun comebackDoublesTheNextGoodBlock() {
        val drift = block("a", t0, rating = 0)
        val next = block("b", t0 + 15 * 60_000L, bounty = "comeback")
        val o = score(next, 100, rest = false, history = listOf(drift))
        assertTrue("Comeback ×2" in o.bonuses)
        assertEquals(20, o.points)
    }

    @Test
    fun momentumGrowsWithBackToBackGoodBlocks() {
        val h = listOf(block("b", t0 + 15 * 60_000L, 90), block("a", t0, 100))
        val o = score(block("c", t0 + 30 * 60_000L), 100, rest = false, history = h)
        assertTrue("Momentum ×1.5" in o.bonuses)
        assertEquals(15, o.points)
    }

    @Test
    fun scaleWithBlockLength() {
        val long = Block("x", "deep", startAt = t0, minutes = 60)
        assertEquals(40, score(long, 100, false, emptyList()).points)
    }

    @Test
    fun restBlocksAreHonoured() {
        val o = score(block("r", t0), 100, rest = true, history = emptyList())
        assertEquals(3, o.points)
    }

    @Test
    fun eggBountyAlwaysHatchesAtSeventyFive() {
        val o = score(block("e", t0, bounty = "egg"), 75, false, emptyList())
        assertNotNull(o.reward)
        assertTrue(o.reward!!.rarity >= 1)
    }

    @Test
    fun levelsGrow() {
        assertEquals(1, levelFor(0))
        assertEquals(2, levelFor(12))
        assertEquals("E", rankFor(1))
        assertEquals("S", rankFor(30))
    }
}

class StoreTest {
    @Test
    fun startRateAndContinueFlow() {
        val files = MemFiles()
        val store = Store(files, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(Phase.Idle, store.phase())
        store.start("deep", 15, "write intro")
        val live = store.liveBlock()!!
        assertEquals(Phase.Running, store.phase())
        assertEquals(Phase.Due, store.phase(live.endAt + 1))
        val o = store.rate(live.id, 0)!!
        assertEquals(1, o.points)
        assertEquals(Phase.Idle, store.phase())
        assertEquals("comeback", store.pendingBounty())
        store.start("deep", 15)
        assertEquals("comeback", store.liveBlock()!!.bounty)
        assertTrue(store.db.value.dirty.any { it.startsWith("block:") })
        // Reloads from disk.
        store.flush()
        val again = Store(files, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(2, again.db.value.blocks.size)
    }

    @Test
    fun insightsCountComebacks() {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        val t = nowMs() - 3 * 3600_000L
        val blocks = listOf(
            Block("1", "deep", startAt = t, minutes = 15, rating = 0, points = 1),
            Block("2", "deep", startAt = t + 900_000, minutes = 15, rating = 100, points = 20),
            Block("3", "deep", startAt = t + 1_800_000, minutes = 15, rating = 75, points = 8),
        )
        val db = store.db.value.copy(blocks = blocks)
        val day = epochDay(t)
        val s = computeInsights(db, DayRange(day, day))
        assertEquals(1, s.comebacks)
        assertEquals(1, s.drifts)
        assertEquals(29, s.points)
        assertEquals(2, s.longestRun)
    }
}
