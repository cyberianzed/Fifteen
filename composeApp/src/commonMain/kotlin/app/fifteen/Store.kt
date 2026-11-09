package app.fifteen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** What the check-in screen shows right after rating a block. */
data class Reveal(val blockId: String, val rating: Int, val outcome: Outcome, val dayPoints: Int)

/**
 * The single source of truth on a device: local JSON log + sync with Supabase.
 * Local-first: everything works offline; changes are pushed when a connection exists.
 */
class Store(private val files: TextFiles, private val scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    private val _db = MutableStateFlow(loadDb())
    val db: StateFlow<LocalDb> = _db.asStateFlow()

    private val _prefs = MutableStateFlow(loadPrefs())
    val prefs: StateFlow<LocalPrefs> = _prefs.asStateFlow()

    init { Sounds.enabled = _prefs.value.sounds }

    private val _reveal = MutableStateFlow<Reveal?>(null)
    val reveal: StateFlow<Reveal?> = _reveal.asStateFlow()

    val sync = SyncEngine(this, json)
    val nudges = NudgeEngine(this)
    internal val kick = Channel<Unit>(Channel.CONFLATED)

    private fun loadDb(): LocalDb {
        val text = runCatching { files.read("db.json") }.onFailure { logError("read db.json", it) }.getOrNull()
        val db = text?.let { t ->
            runCatching { json.decodeFromString(LocalDb.serializer(), t) }
                .onFailure { logError("decode db.json", it); runCatching { files.backup("db.json") } }
                .getOrNull()
        }
        if (db != null) return db
        val now = nowMs()
        val cats = defaultCategories(now)
        return LocalDb(categories = cats, dirty = cats.map { "category:${it.id}" }.toSet())
    }

    private fun loadPrefs(): LocalPrefs {
        val text = runCatching { files.read("prefs.json") }.onFailure { logError("read prefs.json", it) }.getOrNull()
        val p = text?.let { t ->
            runCatching { json.decodeFromString(LocalPrefs.serializer(), t) }
                .onFailure { logError("decode prefs.json", it); runCatching { files.backup("prefs.json") } }
                .getOrNull()
        } ?: LocalPrefs()
        var out = if (p.deviceId.isBlank()) p.copy(deviceId = newId().take(8)) else p
        // Fifteen 1.1: the big floating pet is the default on both the computer and the phone.
        if (out.version < 2) out = out.copy(barMode = "pet", showPet = true, overlay = true, overlayStyle = "pet", version = 2)
        return out
    }

    // One writer that always saves the latest state, so an older snapshot can never overwrite a newer one.
    // It never stops: a failed save is logged and retried, and unsaved changes are written at least every 30 s.
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private var changes = 1L // the first pass writes once, which also tidies files from older versions
    private var savedChanges = 0L

    init {
        scope.launch(Dispatchers.Default) {
            var failures = 0
            while (true) {
                try {
                    val wait = if (failures > 0) minOf(2_000L shl minOf(failures, 4), 30_000L) else 30_000L
                    withTimeoutOrNull(wait) { saveSignal.receive() }
                    failures = if (flush()) 0 else failures + 1
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures++
                    logError("save loop", e)
                }
            }
        }
    }

    /** Writes the current state to disk now if anything changed. Returns false if the save failed (it's retried). */
    fun flush(): Boolean = saveLocked {
        val target = changes
        if (target == savedChanges) return@saveLocked true
        try {
            files.write("db.json", json.encodeToString(LocalDb.serializer(), _db.value))
            files.write("prefs.json", json.encodeToString(LocalPrefs.serializer(), _prefs.value))
            savedChanges = target
            true
        } catch (e: Throwable) {
            logError("save", e)
            false
        }
    }

    private var lastError = ""

    private fun logError(what: String, e: Throwable) {
        val line = "$what: ${e::class.simpleName}: ${e.message}"
        if (line == lastError) return // retries would otherwise repeat the same line every few seconds
        lastError = line
        runCatching { files.log(line) }
    }

    private fun markChanged() {
        saveLocked { changes++ }
        saveSignal.trySend(Unit)
    }

    private fun persistDb() = markChanged()

    private fun persistPrefs() = markChanged()

    internal fun mutateDb(changeLocal: Boolean = true, f: (LocalDb) -> LocalDb) {
        _db.update(f)
        persistDb()
        if (changeLocal) kick.trySend(Unit)
    }

    fun updatePrefs(f: (LocalPrefs) -> LocalPrefs) {
        _prefs.update(f)
        Sounds.enabled = _prefs.value.sounds
        persistPrefs()
    }

    // ---------- Derived ----------

    fun liveBlock(db: LocalDb = _db.value): Block? = db.blocks.filter { it.live }.maxByOrNull { it.startAt }

    /** Start of the current stretch of work: blocks chained with gaps under 10 minutes. */
    fun chainStart(db: LocalDb = _db.value, now: Long = nowMs()): Long {
        val list = db.blocks.filter { !it.deleted && it.startAt <= now }.sortedByDescending { it.startAt }
        if (list.isEmpty()) return now
        var start = list[0].startAt
        for (i in 1 until list.size) {
            val prev = list[i]
            val prevEnd = prev.ratedAt ?: prev.stoppedAt ?: prev.endAt
            if (start - prevEnd > 10 * 60_000L) break
            start = prev.startAt
        }
        return start
    }

    fun phase(now: Long = nowMs(), db: LocalDb = _db.value): Phase {
        val live = liveBlock(db) ?: return Phase.Idle
        return if (now < live.endAt) Phase.Running else Phase.Due
    }

    fun category(id: String?, db: LocalDb = _db.value): Category? = db.categories.firstOrNull { it.id == id }

    fun activeCategories(db: LocalDb = _db.value): List<Category> =
        db.categories.filter { !it.deleted && !it.archived }.sortedBy { it.sort }

    fun dayPoints(day: Int, db: LocalDb = _db.value): Int =
        db.blocks.filter { it.rated && epochDay(it.startAt) == day }.sumOf { it.points }

    fun totalXp(db: LocalDb = _db.value): Int = db.blocks.filter { it.rated }.sumOf { it.points }

    /** Bounty waiting for the next block: set by the last check-in, used once. */
    fun pendingBounty(db: LocalDb = _db.value, now: Long = nowMs()): String? {
        val last = db.blocks.filter { !it.deleted }.maxByOrNull { it.startAt } ?: return null
        if (!last.rated) return null
        val ratedAt = last.ratedAt ?: return null
        if (now - ratedAt > 45 * 60_000L) return null
        // Next-block bounties live in the rated block's bonuses as "next:<bounty>" so they sync too.
        return last.bonuses.firstOrNull { it.startsWith("next:") }?.removePrefix("next:")
    }

    // ---------- Actions ----------

    private fun stamp(old: Long): Long = maxOf(nowMs(), old + 1)

    /** Starts a new block now (ending any live one without a rating). */
    fun start(categoryId: String, minutes: Int, intent: String = "", sound: Boolean = true) {
        val now = nowMs()
        val bounty = pendingBounty()
        val block = Block(
            id = newId(), categoryId = categoryId, intent = intent.trim(), startAt = now, minutes = minutes,
            bounty = bounty, device = _prefs.value.deviceId, updatedAt = now,
        )
        mutateDb { db ->
            val stopped = db.blocks.map { if (it.live) it.copy(stoppedAt = now, updatedAt = stamp(it.updatedAt)) else it }
            val changed = db.blocks.filter { it.live }.map { "block:${it.id}" }
            db.copy(blocks = stopped + block, dirty = db.dirty + changed + "block:${block.id}")
        }
        _reveal.value = null
        if (sound) Sounds.play(Sfx.Start, _db.value.profile.avatar)
    }

    /** Rates a block and returns what was earned. */
    fun rate(blockId: String, rating: Int): Outcome? {
        val db = _db.value
        val block = db.blocks.firstOrNull { it.id == blockId } ?: return null
        val rest = category(block.categoryId)?.rest == true
        val history = db.blocks.filter { it.rated && it.startAt < block.startAt }.sortedByDescending { it.startAt }
        val outcome = score(block, rating, rest, history)
        val now = nowMs()
        val bonuses = outcome.bonuses + listOfNotNull(outcome.nextBounty?.let { "next:$it" })
        mutateDb { d ->
            d.copy(
                blocks = d.blocks.map {
                    if (it.id == blockId) it.copy(
                        rating = rating, ratedAt = now, points = outcome.points, multiplier = outcome.multiplier,
                        bonuses = bonuses, reward = outcome.reward?.id, updatedAt = stamp(it.updatedAt),
                    ) else it
                },
                dirty = d.dirty + "block:$blockId",
            )
        }
        _reveal.value = Reveal(blockId, rating, outcome, dayPoints(epochDay(block.startAt)))
        val avatar = db.profile.avatar
        when {
            outcome.reward != null -> Sounds.play(Sfx.Reward, avatar, outcome.reward.rarity)
            rest || rating >= 75 -> Sounds.play(Sfx.Good, avatar)
            rating == 0 -> Sounds.play(Sfx.Drift, avatar)
            else -> Sounds.play(Sfx.Okay, avatar)
        }
        return outcome
    }

    /** Phone quick action: rate the due block and carry on with the same category. */
    fun rateAndContinue(rating: Int) {
        val live = liveBlock() ?: return
        rate(live.id, rating)
        start(live.categoryId, _db.value.profile.defaultMinutes, live.intent, sound = false)
    }

    private fun petKey(now: Long = nowMs()) = "${liveBlock()?.id ?: ""}:${phase(now)}"

    /** Tucks the floating companion away until the block ends (or, while resting, until a block starts). */
    fun hidePet() = updatePrefs { it.copy(petHidden = true, petHiddenAt = petKey()) }

    fun showPet() = updatePrefs { it.copy(petHidden = false, petHiddenAt = "", showBar = true, overlay = true, overlayStyle = "pet") }

    /** A hidden companion comes back when the block it was hidden in ends, or when a new block starts. */
    fun checkPetReturn(now: Long = nowMs()) {
        val p = _prefs.value
        if (!p.petHidden) return
        if (phase(now) != Phase.Idle && petKey(now) != p.petHiddenAt) showPet()
    }

    fun stopTracking() {
        val now = nowMs()
        mutateDb { db ->
            val ids = db.blocks.filter { it.live }.map { it.id }.toSet()
            db.copy(
                blocks = db.blocks.map { if (it.id in ids) it.copy(stoppedAt = now, updatedAt = stamp(it.updatedAt)) else it },
                dirty = db.dirty + ids.map { "block:$it" },
            )
        }
    }

    /** Pushes the live block's end out (e.g. "5 more minutes"). */
    fun extend(minutes: Int) {
        val live = liveBlock() ?: return
        val now = nowMs()
        val newMinutes = (((now - live.startAt) / 60_000L).toInt() + minutes).coerceAtLeast(live.minutes)
        mutateDb { db ->
            db.copy(
                blocks = db.blocks.map { if (it.id == live.id) it.copy(minutes = newMinutes, updatedAt = stamp(it.updatedAt)) else it },
                dirty = db.dirty + "block:${live.id}",
            )
        }
    }

    fun deleteBlock(id: String) = mutateDb { db ->
        db.copy(
            blocks = db.blocks.map { if (it.id == id) it.copy(deleted = true, updatedAt = stamp(it.updatedAt)) else it },
            dirty = db.dirty + "block:$id",
        )
    }

    fun dismissReveal() { _reveal.value = null }

    fun saveCategory(c: Category) = mutateDb { db ->
        val updated = c.copy(updatedAt = stamp(c.updatedAt))
        val exists = db.categories.any { it.id == c.id }
        db.copy(
            categories = if (exists) db.categories.map { if (it.id == c.id) updated else it } else db.categories + updated,
            dirty = db.dirty + "category:${c.id}",
        )
    }

    fun addCategory(name: String, color: Long, rest: Boolean = false): Category {
        val c = Category(newId(), name.trim(), color, rest, sort = (_db.value.categories.maxOfOrNull { it.sort } ?: 0) + 1)
        saveCategory(c)
        return c
    }

    fun moveCategory(id: String, delta: Int) {
        val list = activeCategories().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val a = list[i]; val b = list[j]
        list[i] = b; list[j] = a
        list.forEachIndexed { index, c -> if (c.sort != index) saveCategory(c.copy(sort = index)) }
    }

    fun updateProfile(f: (Profile) -> Profile) = mutateDb { db ->
        val p = f(db.profile)
        db.copy(profile = p.copy(updatedAt = stamp(db.profile.updatedAt)), dirty = db.dirty + "profile:me")
    }

    /** Runs push/pull forever; wakes early after local changes. */
    suspend fun syncLoop(intervalMs: () -> Long) {
        while (true) {
            val ok = sync.syncNow()
            val wait = if (ok) intervalMs() else 30_000L
            withTimeoutOrNull(wait) { kick.receive() }
            delay(300)
        }
    }
}
