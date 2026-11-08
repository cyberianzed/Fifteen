package app.nimisham

/** Inclusive day range. */
data class DayRange(val from: Int, val to: Int) {
    val days get() = to - from + 1
    operator fun contains(day: Int) = day in from..to
}

data class CategoryStat(val category: Category?, val minutes: Int, val focusMinutes: Double, val avgRating: Double, val points: Int, val blocks: Int)

data class DayStat(val day: Int, val points: Int, val trackedMinutes: Int, val focusMinutes: Double, val avgRating: Double?, val blocks: Int)

data class Insights(
    val range: DayRange,
    val blocks: List<Block>,
    val points: Int,
    val trackedMinutes: Int,
    /** Minutes weighted by how focused they were (a 75% block of 15 min = 11.25). */
    val focusMinutes: Double,
    val avgRating: Double?,
    val ratedBlocks: Int,
    val drifts: Int,
    val comebacks: Int,
    val comebackRate: Double?,
    val perfects: Int,
    val longestRun: Int,
    val restMinutes: Int,
    val rewards: Int,
    val perDay: List<DayStat>,
    val perCategory: List<CategoryStat>,
    /** [weekday][hour] → average rating, or null with no data. */
    val heat: List<List<Double?>>,
    val heatCount: List<List<Int>>,
    val hourly: List<Double?>,
    val ratingCounts: Map<Int, Int>,
    val bestHour: Int?,
    val bestWeekday: Int?,
    val previousPoints: Int,
    val previousFocus: Double,
)

fun computeInsights(db: LocalDb, range: DayRange): Insights {
    val cats = db.categories.associateBy { it.id }
    val all = db.blocks.filter { it.rated }.sortedBy { it.startAt }
    val inRange = all.filter { epochDay(it.startAt) in range }
    fun isRest(b: Block) = cats[b.categoryId]?.rest == true
    val work = inRange.filter { !isRest(it) }

    var comebacks = 0
    var drifts = 0
    var run = 0
    var longest = 0
    for ((i, b) in work.withIndex()) {
        val r = b.rating ?: 0
        if (r == 0) drifts++
        if (i > 0 && work[i - 1].rating == 0 && r >= 75) comebacks++
        run = if (r >= 75) run + 1 else 0
        longest = maxOf(longest, run)
    }

    val perDay = (range.from..range.to).map { day ->
        val d = inRange.filter { epochDay(it.startAt) == day }
        val dw = d.filter { !isRest(it) }
        DayStat(
            day, d.sumOf { it.points }, d.sumOf { it.minutes }, dw.sumOf { it.minutes * (it.rating ?: 0) / 100.0 },
            dw.takeIf { it.isNotEmpty() }?.map { (it.rating ?: 0).toDouble() }?.average(), d.size,
        )
    }

    val perCategory = inRange.groupBy { it.categoryId }.map { (id, list) ->
        val rest = cats[id]?.rest == true
        CategoryStat(
            cats[id], list.sumOf { it.minutes },
            if (rest) 0.0 else list.sumOf { it.minutes * (it.rating ?: 0) / 100.0 },
            if (rest) 0.0 else list.map { (it.rating ?: 0).toDouble() }.average(),
            list.sumOf { it.points }, list.size,
        )
    }.sortedByDescending { it.minutes }

    val heatSum = List(7) { DoubleArray(24) }
    val heatN = List(7) { IntArray(24) }
    for (b in work) {
        val wd = weekdayOf(epochDay(b.startAt))
        val h = minuteOfDay(b.startAt) / 60
        heatSum[wd][h] += (b.rating ?: 0).toDouble()
        heatN[wd][h]++
    }
    val heat = List(7) { w -> List(24) { h -> if (heatN[w][h] == 0) null else heatSum[w][h] / heatN[w][h] } }
    val hourly = List(24) { h ->
        val n = (0..6).sumOf { heatN[it][h] }
        if (n == 0) null else (0..6).sumOf { heatSum[it][h] } / n
    }
    val hourCounts = List(24) { h -> (0..6).sumOf { heatN[it][h] } }
    val bestHour = (0..23).filter { hourCounts[it] >= 2 }.maxByOrNull { hourly[it] ?: 0.0 }
    val weekdayAvg = (0..6).map { w -> val n = heatN[w].sum(); if (n == 0) null else heatSum[w].sum() / n }
    val bestWeekday = (0..6).filter { heatN[it].sum() >= 2 }.maxByOrNull { weekdayAvg[it] ?: 0.0 }

    val prev = DayRange(range.from - range.days, range.from - 1)
    val prevBlocks = all.filter { epochDay(it.startAt) in prev }

    return Insights(
        range = range,
        blocks = inRange,
        points = inRange.sumOf { it.points },
        trackedMinutes = inRange.sumOf { it.minutes },
        focusMinutes = work.sumOf { it.minutes * (it.rating ?: 0) / 100.0 },
        avgRating = work.takeIf { it.isNotEmpty() }?.map { (it.rating ?: 0).toDouble() }?.average(),
        ratedBlocks = work.size,
        drifts = drifts,
        comebacks = comebacks,
        comebackRate = if (drifts == 0) null else comebacks.toDouble() / drifts,
        perfects = work.count { it.rating == 100 },
        longestRun = longest,
        restMinutes = inRange.filter { isRest(it) }.sumOf { it.minutes },
        rewards = inRange.count { it.reward != null },
        perDay = perDay,
        perCategory = perCategory,
        heat = heat,
        heatCount = List(7) { w -> heatN[w].toList() },
        hourly = hourly,
        ratingCounts = RATINGS.associateWith { r -> work.count { it.rating == r } },
        bestHour = bestHour,
        bestWeekday = bestWeekday,
        previousPoints = prevBlocks.sumOf { it.points },
        previousFocus = prevBlocks.filter { !isRest(it) }.sumOf { it.minutes * (it.rating ?: 0) / 100.0 },
    )
}

/** Consecutive days (ending today or yesterday) with at least [minBlocks] rated blocks; one rest day per week is forgiven. */
fun dayStreak(db: LocalDb, today: Int, minBlocks: Int = 4): Int {
    val counts = db.blocks.filter { it.rated }.groupingBy { epochDay(it.startAt) }.eachCount()
    var day = if ((counts[today] ?: 0) >= minBlocks) today else today - 1
    var streak = 0
    var graceUsedAt = Int.MIN_VALUE
    while (true) {
        if ((counts[day] ?: 0) >= minBlocks) {
            streak++
        } else if (day - graceUsedAt >= 7 && streak > 0 && (counts[day - 1] ?: 0) >= minBlocks) {
            graceUsedAt = day // a missed day inside a run is forgiven once a week
        } else break
        day--
    }
    return streak
}

/** Growth of today's companion, 0..1 by points against the daily goal; beyond 1 it fruits. */
fun growthFor(points: Int, goal: Int): Float = (points.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1.6f)

fun weekdayName(w: Int) = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[w]

fun hourName(h: Int) = when {
    h == 0 -> "12am"
    h < 12 -> "${h}am"
    h == 12 -> "12pm"
    else -> "${h - 12}pm"
}
