package app.nimisham

import kotlinx.serialization.Serializable

/** Something you spend blocks on. `rest` categories are breaks: they're honoured, not rated. */
@Serializable
data class Category(
    val id: String,
    val name: String,
    val color: Long,
    val rest: Boolean = false,
    val sort: Int = 0,
    val archived: Boolean = false,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/**
 * One tracked stretch (15/30/60 min). A block with no rating and no stop time is the live one.
 * Points, multiplier and reward are decided once, by the device that rated it, then synced.
 */
@Serializable
data class Block(
    val id: String,
    val categoryId: String,
    val intent: String = "",
    val startAt: Long,
    val minutes: Int,
    /** 0, 50, 75, 90 or 100; null while running or awaiting check-in. */
    val rating: Int? = null,
    val ratedAt: Long? = null,
    /** Tracking was switched off before the block ended (counts as untracked). */
    val stoppedAt: Long? = null,
    val points: Int = 0,
    val multiplier: Double = 1.0,
    val bonuses: List<String> = emptyList(),
    /** Artifact id found at check-in, if any. */
    val reward: String? = null,
    /** Event riding on this block: "golden", "egg", "comeback". */
    val bounty: String? = null,
    val device: String = "",
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val endAt get() = startAt + minutes * 60_000L
    val live get() = rating == null && stoppedAt == null && !deleted
    val rated get() = rating != null && !deleted
}

/** Settings shared by every device. */
@Serializable
data class Profile(
    val avatar: String = "tree",
    val dailyGoal: Int = 80,
    val defaultMinutes: Int = 15,
    val quotes: Boolean = true,
    val updatedAt: Long = 0,
)

/** Settings that belong to this device only. */
@Serializable
data class LocalPrefs(
    val deviceId: String = "",
    val showBar: Boolean = true,
    val showPet: Boolean = true,
    /** Desktop floating window: "bar" (companion + bar) or "pet" (bigger companion, tiny timer). */
    val barMode: String = "bar",
    val barX: Int = -1,
    val barY: Int = -1,
    val askOnStart: Boolean = true,
    val autostart: Boolean = true,
    val overlay: Boolean = false,
    val overlayStyle: String = "line",
    val richNotification: Boolean = true,
    /** Little sound effects that match the companion. */
    val sounds: Boolean = true,
    val supabaseUrl: String = "",
    val supabaseKey: String = "",
    val email: String = "",
    val userId: String = "",
    val refreshToken: String = "",
    val lastSeq: Long = 0,
)

@Serializable
data class LocalDb(
    val categories: List<Category> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val profile: Profile = Profile(),
    /** "kind:id" of records changed here and not yet pushed. */
    val dirty: Set<String> = emptySet(),
)

enum class Phase { Idle, Running, Due }

val RATINGS = listOf(0, 50, 75, 90, 100)
val DURATIONS = listOf(15, 30, 60)

fun defaultCategories(now: Long): List<Category> = listOf(
    Category("deep", "Deep work", 0xFFF2C14E, sort = 0, updatedAt = now),
    Category("learn", "Learning", 0xFF7FB3D5, sort = 1, updatedAt = now),
    Category("admin", "Admin & email", 0xFFB39DDB, sort = 2, updatedAt = now),
    Category("meet", "Meetings", 0xFF80CBC4, sort = 3, updatedAt = now),
    Category("create", "Creative", 0xFFF48FB1, sort = 4, updatedAt = now),
    Category("move", "Exercise", 0xFFA5D6A7, sort = 5, updatedAt = now),
    Category("rest", "Break", 0xFF90A4AE, rest = true, sort = 6, updatedAt = now),
)

/** Palette offered when adding a category: soft, distinct, readable on the dark background. */
val CATEGORY_COLORS = listOf(
    0xFFF2C14E, 0xFFF4A261, 0xFFE76F51, 0xFFF48FB1, 0xFFB39DDB, 0xFF7FB3D5,
    0xFF80CBC4, 0xFFA5D6A7, 0xFFD4E157, 0xFFBCAAA4, 0xFF90A4AE, 0xFFFFFFFF,
)
