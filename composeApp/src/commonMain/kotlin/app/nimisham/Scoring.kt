package app.nimisham

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Points never go down. A drifted block earns an "honest check-in" point and charges a comeback (×2 on
 * the next good block), because noticing the drift is the skill being trained. Momentum rests instead of
 * breaking, and surprise drops (variable rewards) keep it fresh.
 */

/** Points per 15 minutes for each rating. */
fun basePoints(rating: Int): Int = when (rating) {
    100 -> 10
    90 -> 9
    75 -> 7
    50 -> 4
    else -> 0
}

data class Outcome(
    val points: Int,
    val multiplier: Double,
    val bonuses: List<String>,
    val reward: Artifact?,
    val message: String,
    /** Event attached to the *next* block. */
    val nextBounty: String?,
)

/**
 * Scores [block] rated [rating], given the earlier rated blocks (newest first) for momentum, comebacks
 * and the reward pity timer. Deterministic for a given block id, so a retry gives the same drop.
 */
fun score(block: Block, rating: Int, rest: Boolean, history: List<Block>): Outcome {
    val rng = Random(block.id.hashCode() * 31 + rating)
    val units = block.minutes / 15.0
    val bonuses = mutableListOf<String>()
    var mult = 1.0
    val previous = history.firstOrNull { it.rated }
    val contiguous = previous != null && block.startAt - (previous.ratedAt ?: previous.endAt) < 20 * 60_000L

    if (rest) {
        val pts = (3 * units).roundToInt().coerceAtLeast(1)
        return Outcome(pts, 1.0, listOf("Break honoured"), null, restMessages.random(rng), rollBounty(rng, null))
    }

    var base = basePoints(rating) * units
    if (rating == 0) {
        bonuses += "Honest check-in +1"
        base = 1.0
    }
    if (rating >= 75) {
        if (block.bounty == "comeback" || (contiguous && previous?.rating == 0)) {
            mult *= 2.0; bonuses += "Comeback ×2"
        }
        val streak = history.takeWhile { it.rated && (it.rating ?: 0) >= 75 }.size
        if (contiguous && streak >= 2) {
            mult *= 1.5; bonuses += "Momentum ×1.5"
        } else if (contiguous && streak == 1) {
            mult *= 1.2; bonuses += "Momentum ×1.2"
        }
        if (block.bounty == "golden") {
            mult *= 3.0; bonuses += "Golden block ×3"
        }
    }
    if (rating == 100) bonuses += "Perfect block"
    mult = min(mult, 6.0)
    val points = (base * mult).roundToInt()

    // Variable reward: more likely for better blocks, and a pity timer so dry spells end.
    val dry = history.takeWhile { it.reward == null }.size
    val reward: Artifact? = when {
        block.bounty == "egg" && rating >= 75 -> drawArtifact(rng, minRarity = 1)
        rating >= 50 -> {
            val chance = 0.18 + (if (rating >= 90) 0.10 else 0.0) + dry * 0.07
            if (rng.nextDouble() < chance) drawArtifact(rng, 0) else null
        }
        else -> null
    }
    val message = when (rating) {
        100 -> perfectMessages.random(rng)
        90, 75 -> goodMessages.random(rng)
        50 -> halfMessages.random(rng)
        else -> driftMessages.random(rng)
    }
    val next = if (rating == 0) "comeback" else rollBounty(rng, block.bounty)
    return Outcome(points, mult, bonuses, reward, message, next)
}

private fun rollBounty(rng: Random, current: String?): String? {
    val r = rng.nextDouble()
    return when {
        current != "golden" && r < 0.10 -> "golden"
        r < 0.24 -> "egg"
        else -> null
    }
}

fun bountyText(bounty: String?): String? = when (bounty) {
    "golden" -> "Golden block — score 75%+ for triple points"
    "egg" -> "An egg is warming — 75%+ hatches it"
    "comeback" -> "Comeback ready — 75%+ doubles this block"
    else -> null
}

// ---------- Levels & ranks ----------

fun levelFor(xp: Int): Int = floor(sqrt(xp / 12.0)).toInt() + 1
fun xpForLevel(level: Int): Int = (level - 1) * (level - 1) * 12
fun rankFor(level: Int): String = when {
    level >= 30 -> "S"
    level >= 20 -> "A"
    level >= 15 -> "B"
    level >= 10 -> "C"
    level >= 5 -> "D"
    else -> "E"
}

// ---------- Artifacts ----------

enum class Shape { Gem, Orb, Leaf, Feather, Coin, Egg, Star, Crystal, Lamp, Shell }

data class Artifact(val id: String, val name: String, val rarity: Int, val shape: Shape, val color: Long, val lore: String)

val RARITY_NAMES = listOf("Common", "Rare", "Epic", "Legendary")
val RARITY_COLORS = listOf(0xFFB0BEC5, 0xFF64B5F6, 0xFFBA68C8, 0xFFFFD54F)

val ARTIFACTS = listOf(
    Artifact("pebble", "River Pebble", 0, Shape.Orb, 0xFF9E9E9E, "Smoothed by a thousand quiet minutes."),
    Artifact("banana_leaf", "Banana Leaf", 0, Shape.Leaf, 0xFF66BB6A, "Big enough to hold a whole sadya of focus."),
    Artifact("copper", "Copper Kasu", 0, Shape.Coin, 0xFFD4895A, "Old coin. Spend it on nothing."),
    Artifact("jasmine", "Jasmine Bud", 0, Shape.Star, 0xFFF5F5F5, "Smells like a calm morning."),
    Artifact("raindrop", "Monsoon Drop", 0, Shape.Orb, 0xFF4FC3F7, "June, bottled."),
    Artifact("coconut", "Coconut Shell", 0, Shape.Shell, 0xFF8D6E63, "Hard outside, sweet within."),
    Artifact("crow_feather", "Crow Feather", 0, Shape.Feather, 0xFF455A64, "Clever birds finish what they start."),
    Artifact("pepper", "Black Pepper Pearl", 0, Shape.Orb, 0xFF3E2723, "Small, sharp, valuable."),
    Artifact("tulsi", "Tulsi Leaf", 0, Shape.Leaf, 0xFF2E7D32, "Steady roots."),
    Artifact("seashell", "Kovalam Shell", 0, Shape.Shell, 0xFFFFCCBC, "Hold it to your ear: waves, not notifications."),
    Artifact("moonstone", "Moonstone", 1, Shape.Gem, 0xFFB3E5FC, "Glows a little when you're on track."),
    Artifact("peacock", "Peacock Feather", 1, Shape.Feather, 0xFF26A69A, "An eye for detail."),
    Artifact("lamp", "Nilavilakku", 1, Shape.Lamp, 0xFFFFB300, "A brass lamp. Light one thing at a time."),
    Artifact("cardamom", "Cardamom Crystal", 1, Shape.Crystal, 0xFF9CCC65, "Green and sharp, like a fresh mind."),
    Artifact("silver", "Silver Panam", 1, Shape.Coin, 0xFFCFD8DC, "Earned, not given."),
    Artifact("kingfisher", "Kingfisher Plume", 1, Shape.Feather, 0xFF29B6F6, "Dives straight to the point."),
    Artifact("amber", "Jackwood Amber", 1, Shape.Gem, 0xFFFFA726, "Time, held still."),
    Artifact("lotus", "Pink Lotus", 1, Shape.Star, 0xFFF48FB1, "Blooms out of the mud anyway."),
    Artifact("ruby", "Temple Ruby", 2, Shape.Gem, 0xFFE53935, "Warm as a deep-work afternoon."),
    Artifact("sapphire", "Monsoon Sapphire", 2, Shape.Crystal, 0xFF3949AB, "The colour of the sky before rain."),
    Artifact("hornbill", "Hornbill Egg", 2, Shape.Egg, 0xFFFFE082, "Something big is hatching."),
    Artifact("conch", "Valampiri Conch", 2, Shape.Shell, 0xFFFFF8E1, "Rare spiral that turns the right way."),
    Artifact("emerald", "Western Ghats Emerald", 2, Shape.Gem, 0xFF00C853, "Green all year."),
    Artifact("comet", "Comet Shard", 2, Shape.Crystal, 0xFF80DEEA, "Fell during a perfect block."),
    Artifact("jackfruit", "Golden Jackfruit", 3, Shape.Egg, 0xFFFFD600, "Legend says it feeds focus for a week."),
    Artifact("phoenix", "Phoenix Egg", 3, Shape.Egg, 0xFFFF7043, "Born from a comeback."),
    Artifact("seed", "Thousand-Year Seed", 3, Shape.Orb, 0xFF76FF03, "Plant it in your best day."),
    Artifact("starlamp", "Star Lamp", 3, Shape.Lamp, 0xFFFFF59D, "Never goes out."),
)

fun artifactById(id: String?): Artifact? = ARTIFACTS.firstOrNull { it.id == id }

private fun drawArtifact(rng: Random, minRarity: Int): Artifact {
    val roll = rng.nextDouble()
    var rarity = when {
        roll < 0.05 -> 3
        roll < 0.17 -> 2
        roll < 0.42 -> 1
        else -> 0
    }
    rarity = maxOf(rarity, minRarity)
    val pool = ARTIFACTS.filter { it.rarity == rarity }
    return pool[rng.nextInt(pool.size)]
}

// ---------- Words ----------

private val perfectMessages = listOf(
    "Locked in. That's what a full block feels like.",
    "Perfect block. Your future self says thanks.",
    "Clean run. The tree loved that one.",
    "100%. Ride this wave.",
)
private val goodMessages = listOf(
    "Solid block. Keep the rhythm.",
    "Good work. Small steps add up fast.",
    "That counts. Onwards.",
    "Nice. You're building momentum.",
)
private val halfMessages = listOf(
    "Half a block is still real progress.",
    "Some focus beats none. Next one's yours.",
    "Partial is fine. Reset and go again.",
)
private val driftMessages = listOf(
    "You noticed the drift — that's the skill. Comeback ×2 is charged.",
    "Drifting happens. Your next good block counts double.",
    "Honest check-in, +1. The comeback is the best part of the story.",
    "No harm done. One fresh block, double points.",
)
private val restMessages = listOf(
    "Rested. Breaks are part of the work.",
    "Recharged. Ready for the next one?",
    "Good break. Your brain thanks you.",
)

/** Short lines for the bar. Kept to public-domain sayings and plain encouragement. */
val QUOTES = listOf(
    "One thing at a time." to "",
    "Well begun is half done." to "Aristotle",
    "The secret of getting ahead is getting started." to "Mark Twain",
    "It does not matter how slowly you go as long as you do not stop." to "Confucius",
    "Action is the foundational key to all success." to "Pablo Picasso",
    "Small deeds done are better than great deeds planned." to "Peter Marshall",
    "Do what you can, with what you have, where you are." to "Theodore Roosevelt",
    "Energy and persistence conquer all things." to "Benjamin Franklin",
    "Drop by drop is the water pot filled." to "The Buddha",
    "Little by little, one travels far." to "J. R. R. Tolkien",
    "Focus is a muscle. You're training it." to "",
    "Just this block. Nothing else." to "",
    "Done is better than perfect." to "",
    "You don't have to feel ready to start." to "",
    "The tree grows while you work." to "",
    "Fifteen minutes is a whole world." to "",
    "Begin anywhere." to "John Cage",
    "Nothing will work unless you do." to "Maya Angelou",
    "Patience and time do more than strength or passion." to "Jean de La Fontaine",
    "Concentrate all your thoughts upon the work at hand." to "Alexander Graham Bell",
    "Whatever you are, be a good one." to "Abraham Lincoln",
    "Slow is smooth, smooth is fast." to "",
    "Notice, return, continue." to "",
    "A river cuts through rock by persistence." to "",
)

fun quoteFor(seed: String): Pair<String, String> = QUOTES[(seed.hashCode() and 0x7fffffff) % QUOTES.size]
