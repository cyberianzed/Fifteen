package app.nimisham

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NudgeTest {
    private fun working(minutesAgo: Int, addons: Boolean = true): Store {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        val now = nowMs()
        store.mutateDb { it.copy(blocks = listOf(Block("b", "deep", startAt = now - minutesAgo * 60_000L, minutes = 60))) }
        store.updateProfile { it.copy(addons = addons, wellOn = false) }
        return store
    }

    @Test
    fun nothingWhenAddonsAreOff() {
        val s = working(40, addons = false)
        s.nudges.tick(nowMs())
        assertNull(s.db.value.nudge.active)
    }

    @Test
    fun eyeBreakAfterTwentyMinutesWithTimer() {
        val s = working(10)
        s.nudges.tick(nowMs())
        assertNull(s.db.value.nudge.active, "too early")
        val s2 = working(21)
        val now = nowMs()
        s2.nudges.tick(now)
        val a = s2.db.value.nudge.active
        assertNotNull(a)
        assertEquals("eye", a.kind)
        s2.nudges.answer("start")
        val timer = s2.db.value.nudge.active!!
        assertEquals("timer", timer.stage)
        assertTrue(timer.timerEnd > now)
        s2.nudges.tick(timer.timerEnd + 1)
        assertEquals("done", s2.db.value.nudge.active!!.stage)
        assertEquals(1, s2.db.value.nudge.eyes[epochDay(now)])
        assertTrue("nudge:me" in s2.db.value.dirty, "syncs to the other device")
    }

    @Test
    fun waterConversationFollowsUp() {
        val s = working(50)
        s.updateProfile { it.copy(eyeOn = false) }
        val now = nowMs()
        s.nudges.tick(now)
        assertEquals("remind", s.db.value.nudge.active!!.stage)
        s.nudges.answer("ack")
        assertNull(s.db.value.nudge.active)
        val f = s.db.value.nudge.followUp!!
        assertEquals("ask1", f.stage)
        // Comes back with "did you drink?" after the follow-up time (and the short cooldown).
        s.nudges.tick(f.at + 3 * 60_000L)
        assertEquals("ask1", s.db.value.nudge.active!!.stage)
        s.nudges.answer("no")
        assertEquals("no1", s.db.value.nudge.active!!.stage)
        s.nudges.answer("auto")
        assertEquals("ask2", s.db.value.nudge.followUp!!.stage)
        s.nudges.begin("water", "ask2")
        s.nudges.answer("no")
        assertEquals("again", s.db.value.nudge.active!!.stage)
        s.nudges.answer("later")
        s.nudges.answer("auto")
        assertTrue(s.db.value.nudge.followUp!!.at > nowMs() + 14 * 60_000L, "reminds again in 15 minutes")
        s.nudges.begin("water", "ask2")
        s.nudges.answer("yes")
        assertEquals("yes", s.db.value.nudge.active!!.stage)
        assertEquals(1, s.db.value.nudge.water[epochDay(nowMs())])
    }

    @Test
    fun scriptsHaveTextForEveryStage() {
        val s = working(1)
        val p = s.db.value.profile
        val stages = mapOf(
            "eye" to listOf("ask", "timer", "done", "skipped"),
            "water" to listOf("remind", "ask1", "no1", "ask2", "again", "later", "nothanks", "yes"),
            "well" to listOf("ask", "good", "low", "thanks"),
        )
        for ((kind, list) in stages) for (st in list) {
            val step = s.nudges.step(ActiveNudge("x$st", kind, st, nowMs()), s.db.value.nudge, p)
            assertTrue(step.messages.isNotEmpty() && step.messages.all { it.isNotBlank() }, "$kind/$st")
        }
    }
}
