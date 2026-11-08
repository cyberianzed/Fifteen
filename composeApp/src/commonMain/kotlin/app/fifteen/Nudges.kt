package app.fifteen

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.random.Random

/*
 * Add-ons: little conversations your companion starts while you work.
 *  - eye:   the 20-20-20 rule (every 20 min, look ~6 m away for 20 s), with a 20-second timer
 *  - water: a reminder, a "did you drink?" follow-up, and a gentle offer to remind again
 *  - well:  random well-being check-ins ("everything okay?")
 *  - rest:  while you rest, the sleeping companion wakes up now and then and checks on you
 * They only start while this device's companion is floating (not hidden, not turned off).
 * The live conversation is synced, so answering on one device closes it on the other.
 */

/** A conversation on screen right now. */
@Serializable
data class ActiveNudge(
    val id: String,
    val kind: String,
    val stage: String,
    val stageAt: Long,
    val timerEnd: Long = 0,
)

/** A follow-up the companion will bring up later (e.g. "did you drink?"). */
@Serializable
data class FollowUp(val kind: String, val stage: String, val at: Long)

@Serializable
data class NudgeState(
    val active: ActiveNudge? = null,
    val followUp: FollowUp? = null,
    val eyeLast: Long = 0,
    val waterLast: Long = 0,
    val wellNext: Long = 0,
    val cooldownUntil: Long = 0,
    /** Rest check-ins asked since the last block (at most two in a row). */
    val restAsked: Int = 0,
    val restLast: Long = 0,
    /** Day number → eye breaks / glasses of water. */
    val eyes: Map<Int, Int> = emptyMap(),
    val water: Map<Int, Int> = emptyMap(),
    val updatedAt: Long = 0,
)

/** One step of a conversation: what the companion says, the answers you can tap, an optional timer. */
data class NudgeStep(
    val messages: List<String>,
    val buttons: List<Pair<String, String>> = emptyList(),
    val timerSec: Int = 0,
    /** Closes (or moves on) by itself this many seconds after the last message appears. */
    val autoCloseSec: Int = 0,
)

const val MESSAGE_GAP_MS = 1500L

/** 250 ml glasses → litres, without a trailing ".0". */
private fun litres(glasses: Int): String {
    val l = glasses * 0.25
    return if (l % 1.0 == 0.0) l.toInt().toString() else l.toString()
}

class NudgeEngine(private val store: Store) {

    fun state(db: LocalDb = store.db.value) = db.nudge

    fun enabled(kind: String, p: Profile) = p.addons && when (kind) {
        "eye" -> p.eyeOn
        "water" -> p.waterOn
        "rest" -> p.restOn
        else -> p.wellOn
    }

    /** Whether this device's companion is out (floating, not hidden). Set by each platform. */
    var petOut: (LocalPrefs) -> Boolean = { true }

    /** Opens the "what's next?" picker (rest check-in -> "Start a block"). Set by each platform. */
    var onStartRequested: () -> Unit = {}

    /** When the companion last came out on this device: add-on timers count from here, so nothing piles up. */
    private var outSince = 0L

    /** True when this device should show the live conversation. */
    fun visible(db: LocalDb, prefs: LocalPrefs): Boolean {
        val a = db.nudge.active ?: return false
        return prefs.showNudges && enabled(a.kind, db.profile) && petOut(prefs)
    }

    private fun write(f: (NudgeState) -> NudgeState) = store.mutateDb { db ->
        val n = f(db.nudge)
        db.copy(nudge = n.copy(updatedAt = maxOf(nowMs(), db.nudge.updatedAt + 1)), dirty = db.dirty + "nudge:me")
    }

    suspend fun loop() {
        while (true) {
            runCatching { tick(nowMs()) }
            delay(1000)
        }
    }

    fun tick(now: Long) {
        store.checkPetReturn(now)
        val prefs = store.prefs.value
        val out = prefs.showNudges && petOut(prefs)
        if (!out) outSince = 0 else if (outSince == 0L) outSince = now
        val db = store.db.value
        val p = db.profile
        val n = db.nudge
        val a = n.active
        if (a != null) {
            if (!enabled(a.kind, p)) { write { it.copy(active = null) }; return }
            val step = step(a, n, p)
            if (step.timerSec > 0 && a.timerEnd in 1..now) { advance(a, "timerDone", now); return }
            val shownAt = a.stageAt + (step.messages.size - 1).coerceAtLeast(0) * MESSAGE_GAP_MS + 900
            if (step.autoCloseSec > 0 && step.timerSec == 0 && now - shownAt > step.autoCloseSec * 1000L) advance(a, "auto", now)
            return
        }
        val phase = store.phase(now, db)
        if (phase == Phase.Running && n.restAsked != 0) write { it.copy(restAsked = 0) }
        if (!out || !p.addons || now < n.cooldownUntil) return
        if (phase == Phase.Idle && p.restOn && n.restAsked < 2) {
            val lastBlock = db.blocks.maxOfOrNull { it.stoppedAt ?: it.ratedAt ?: it.endAt } ?: 0L
            if (now - maxOf(lastBlock, n.restLast, outSince) >= p.restAfter * 60_000L) begin("rest", "ask", now)
            return
        }
        if (phase != Phase.Running) return
        val f = n.followUp
        if (f != null && now >= f.at && enabled(f.kind, p)) { begin(f.kind, f.stage, now); return }
        val chain = maxOf(store.chainStart(db, now), outSince)
        if (p.eyeOn && now - maxOf(n.eyeLast, chain) >= p.eyeEvery * 60_000L) { begin("eye", "ask", now); return }
        if (p.waterOn && f?.kind != "water" && now - maxOf(n.waterLast, chain) >= p.waterEvery * 60_000L) { begin("water", "remind", now); return }
        if (p.wellOn) {
            if (n.wellNext == 0L || n.wellNext < chain) write { it.copy(wellNext = now + wellGap(p, now)) }
            else if (now >= n.wellNext) begin("well", "ask", now)
        }
    }

    private fun wellGap(p: Profile, seed: Long): Long {
        val mean = when (p.wellFreq) { 0 -> 120; 2 -> 45; else -> 80 }
        val r = Random(seed)
        return ((mean * (0.6 + r.nextDouble() * 0.8)) * 60_000L).toLong()
    }

    /** Starts a conversation now (also used by "Try it" in the Add-ons tab). */
    fun begin(kind: String, stage: String, now: Long = nowMs()) {
        write { n ->
            n.copy(
                active = ActiveNudge(newId(), kind, stage, now),
                followUp = if (n.followUp?.kind == kind) null else n.followUp,
                waterLast = if (kind == "water" && stage == "remind") now else n.waterLast,
                restAsked = if (kind == "rest") n.restAsked + 1 else n.restAsked,
                restLast = if (kind == "rest") now else n.restLast,
            )
        }
        Sounds.play(Sfx.Okay, store.db.value.profile.avatar)
    }

    fun answer(action: String) {
        val a = state().active ?: return
        advance(a, action, nowMs())
    }

    fun addGlass(delta: Int = 1) {
        val day = epochDay(nowMs())
        write { n -> n.copy(water = n.water + (day to ((n.water[day] ?: 0) + delta).coerceAtLeast(0)), waterLast = nowMs()) }
    }

    private fun advance(a: ActiveNudge, action: String, now: Long) {
        val day = epochDay(now)
        val p = store.db.value.profile
        fun go(stage: String, timer: Long = 0, extra: (NudgeState) -> NudgeState = { it }) =
            write { n -> extra(n).copy(active = a.copy(stage = stage, stageAt = now, timerEnd = timer)) }
        fun close(extra: (NudgeState) -> NudgeState = { it }) = write { n ->
            extra(n).copy(
                active = null,
                cooldownUntil = now + 2 * 60_000L,
                wellNext = if (a.kind == "well") now + wellGap(p, now) else n.wellNext,
            )
        }
        when (a.kind) {
            "eye" -> when (a.stage to action) {
                "ask" to "start" -> go("timer", now + 20_000)
                "ask" to "skip" -> go("skipped") { it.copy(eyeLast = now) }
                "timer" to "timerDone" -> go("done") { n ->
                    // Same result on every device that reaches this point, so it never double counts.
                    if (n.eyeLast == a.timerEnd) n else n.copy(eyeLast = a.timerEnd, eyes = n.eyes + (day to ((n.eyes[day] ?: 0) + 1)))
                }
                else -> close { it.copy(eyeLast = maxOf(it.eyeLast, now)) }
            }
            "water" -> when (a.stage to action) {
                "remind" to "ack", "remind" to "auto" -> close { it.copy(followUp = FollowUp("water", "ask1", now + 5 * 60_000L)) }
                "ask1" to "yes", "ask2" to "yes" -> go("yes") { n -> n.copy(water = n.water + (day to ((n.water[day] ?: 0) + 1)), waterLast = now) }
                "ask1" to "no" -> go("no1")
                "ask1" to "auto" -> close { it.copy(followUp = FollowUp("water", "ask2", now + 5 * 60_000L)) }
                "no1" to "auto" -> close { it.copy(followUp = FollowUp("water", "ask2", now + 5 * 60_000L)) }
                "ask2" to "no" -> go("again")
                "again" to "later" -> go("later")
                "again" to "nothanks" -> go("nothanks")
                "later" to "auto" -> close { it.copy(followUp = FollowUp("water", "ask2", now + 15 * 60_000L)) }
                else -> close()
            }
            "rest" -> when (a.stage to action) {
                "ask" to "start" -> { close(); onStartRequested() }
                "ask" to "good" -> go("good")
                "ask" to "low" -> go("low")
                "low" to "thanks" -> go("thanks")
                else -> close()
            }
            else -> when (a.stage to action) {
                "ask" to "good" -> go("good")
                "ask" to "low" -> go("low")
                "low" to "thanks" -> go("thanks")
                else -> close()
            }
        }
    }

    /** What the companion says at a stage. Random wording is seeded by the conversation id so devices agree. */
    fun step(a: ActiveNudge, n: NudgeState, p: Profile): NudgeStep {
        val rng = Random(a.id.hashCode())
        fun pick(vararg s: String) = s[rng.nextInt(s.size)]
        val today = epochDay(a.stageAt)
        val glasses = n.water[today] ?: 0
        val minutes = p.eyeEvery
        return when (a.kind) {
            "eye" -> when (a.stage) {
                "ask" -> NudgeStep(
                    listOf(
                        pick("You've been focused for $minutes minutes 👀", "$minutes minutes of screen time — nice work!", "Hey, your eyes have been busy for $minutes minutes."),
                        "Quick 20-20-20: look at something far away (about 6 metres) for 20 seconds.",
                    ),
                    listOf("Start 20s" to "start", "Skip" to "skip"), autoCloseSec = 75,
                )
                "timer" -> NudgeStep(listOf(pick("Look far away… and blink slowly.", "Eyes off the screen. Find the farthest thing you can see.", "Look out of the window if you can 🌳")), listOf("Stop" to "skip"), timerSec = 20)
                "done" -> NudgeStep(listOf(pick("Good for your eyes! ✨ Back to it.", "Done! Your eyes say thanks 👁️", "Refreshed. Let's keep going.")), autoCloseSec = 5)
                else -> NudgeStep(listOf("No problem — I'll remind you in $minutes minutes."), autoCloseSec = 4)
            }
            "water" -> when (a.stage) {
                "remind" -> NudgeStep(
                    listOf(pick("Time for some water 💧", "Hydration check 💧", "Your brain is ~75% water. Top it up? 💧"), "Grab a glass — I'll check back in a few minutes."),
                    listOf("On it" to "ack"), autoCloseSec = 20,
                )
                "ask1" -> NudgeStep(listOf("Did you drink some water?"), listOf("Yes" to "yes", "No" to "no"), autoCloseSec = 90)
                "no1" -> NudgeStep(listOf(pick("Go drink a glass now — I'll ask again in a bit.", "Quick sip break? I'll check in again soon.")), autoCloseSec = 6)
                "ask2" -> NudgeStep(
                    listOf(
                        "Back again — did you get that water?",
                        "About ${p.waterGoal} glasses (≈${litres(p.waterGoal)} litres) a day is a good amount. You're at $glasses so far.",
                    ),
                    listOf("Yes" to "yes", "No" to "no"), autoCloseSec = 90,
                )
                "again" -> NudgeStep(listOf("Want me to remind you again later?"), listOf("Yes, in 15 min" to "later", "No thanks" to "nothanks"), autoCloseSec = 60)
                "later" -> NudgeStep(listOf("Okay! I'll be back in 15 minutes."), autoCloseSec = 4)
                "nothanks" -> NudgeStep(listOf("Alright — just don't forget to drink some soon, okay? 💙"), autoCloseSec = 5)
                else -> NudgeStep(listOf(pick("Nice! That's glass #${glasses} today 💧", "Great job! $glasses of ${p.waterGoal} glasses today.", "Hydrated and focused 💪 ($glasses today)")), autoCloseSec = 5)
            }
            "rest" -> when (a.stage) {
                "ask" -> NudgeStep(
                    listOf(
                        pick("*yawn*… oh, hey 👋", "Mm… I just woke up 😴", "Psst… you still there? 🌙"),
                        pick("Still resting? Everything okay?", "Taking a break? All good with you?", "How are you doing over there?"),
                    ),
                    listOf("Start a block" to "start", "All good" to "good", "Not great" to "low"), autoCloseSec = 90,
                )
                "good" -> NudgeStep(listOf(pick("Enjoy the rest 🌿 I'll nap too.", "Rest well. I'll be right here 💤", "Good. Breaks count too 💛")), autoCloseSec = 5)
                "low" -> NudgeStep(
                    listOf(
                        "Sorry it's a rough one. Be kind to yourself.",
                        pick(
                            "A glass of water and a short walk can help 🚶",
                            "Try five slow breaths, then stretch your shoulders.",
                            "Step outside for a minute of fresh air.",
                            "When you're ready, one tiny 15-minute block is enough.",
                        ),
                    ),
                    listOf("Thanks" to "thanks"), autoCloseSec = 40,
                )
                else -> NudgeStep(listOf("I'm right here whenever you want to start. 💛"), autoCloseSec = 4)
            }
            else -> when (a.stage) {
                "ask" -> NudgeStep(
                    listOf(pick("Quick check-in 🌿", "Hey, just checking on you 🙂", "Little pause from me 🫶"), pick("Is everything okay? Feeling productive?", "How's it going — in the zone?", "Doing alright? Getting things done?")),
                    listOf("I'm good" to "good", "Not really" to "low"), autoCloseSec = 75,
                )
                "good" -> NudgeStep(listOf(pick("Love that. Keep going! 🚀", "Awesome — you've got this.", "Nice! I'll leave you to it.")), autoCloseSec = 4)
                "low" -> NudgeStep(
                    listOf(
                        "That's okay. It happens to everyone.",
                        pick(
                            "Maybe take a 5-minute break and stretch.",
                            "A short walk could reset your head 🚶",
                            "Try a glass of water and three slow breaths.",
                            "Step outside for a minute of fresh air.",
                            "Put on a song you love, then pick one tiny task.",
                            "Write down the very next small step. Just one.",
                        ),
                    ),
                    listOf("Thanks" to "thanks"), autoCloseSec = 40,
                )
                else -> NudgeStep(listOf("Take care of yourself. I'm right here. 💛"), autoCloseSec = 4)
            }
        }
    }
}
