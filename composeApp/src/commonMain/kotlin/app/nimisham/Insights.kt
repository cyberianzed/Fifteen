package app.nimisham

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val PERIODS = listOf("Day", "Week", "Month", "Year")

private fun rangeFor(period: Int, offset: Int, today: Int): DayRange = when (period) {
    0 -> (today + offset).let { DayRange(it, it) }
    1 -> {
        val monday = today - weekdayOf(today) + offset * 7
        DayRange(monday, monday + 6)
    }
    2 -> calendarRange(today, monthly = true, offset = offset).let { DayRange(it.first, it.second) }
    else -> calendarRange(today, monthly = false, offset = offset).let { DayRange(it.first, it.second) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsightsScreen(store: Store, wide: Boolean) {
    val v = rememberView(store, 30_000)
    var period by remember { mutableIntStateOf(1) }
    var offset by remember { mutableIntStateOf(0) }
    val range = rangeFor(period, offset, v.today)
    val s = remember(v.db, range) { computeInsights(v.db, range) }
    val goal = v.db.profile.dailyGoal

    Page(wide) {
        Label("Insights")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rangeTitle(period, range, v.today), color = Ink.text, fontSize = 28.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            GhostButton("‹") { offset-- }
            Spacer(Modifier.width(6.dp))
            GhostButton("›") { if (offset < 0) offset++ }
        }
        Spacer(Modifier.height(16.dp))
        Segmented(PERIODS, period) { period = it; offset = 0 }
        Spacer(Modifier.height(26.dp))

        if (s.ratedBlocks == 0 && s.restMinutes == 0) {
            Card(Modifier.fillMaxWidth()) { Muted("No check-ins in this period yet. Finish a block and the charts fill in.") }
            Spacer(Modifier.height(24.dp))
        }

        // KPIs
        Card(Modifier.fillMaxWidth()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(22.dp), maxItemsInEachRow = if (wide) 4 else 2) {
                val w = Modifier.weight(1f)
                Stat("Focused time", hoursText(s.focusMinutes), delta(s.focusMinutes, s.previousFocus, "vs previous"), deltaColor(s.focusMinutes, s.previousFocus), w)
                Stat("Points", "${s.points}", delta(s.points.toDouble(), s.previousPoints.toDouble(), "vs previous"), deltaColor(s.points.toDouble(), s.previousPoints.toDouble()), w)
                Stat("Average focus", s.avgRating?.let { "${it.roundToInt()}%" } ?: "–", "${s.ratedBlocks} blocks · ${hoursText(s.trackedMinutes.toDouble())} tracked", modifier = w)
                Stat("Comebacks", "${s.comebacks}", s.comebackRate?.let { "bounced back from ${(it * 100).roundToInt()}% of drifts" } ?: "no drifts to bounce from", modifier = w)
                Stat("Perfect blocks", "${s.perfects}", "each one fruits the tree", modifier = w)
                Stat("Longest run", "${s.longestRun}", "good blocks in a row", modifier = w)
                Stat("Breaks", hoursText(s.restMinutes.toDouble()), "rest is part of the work", modifier = w)
                Stat("Finds", "${s.rewards}", "artifacts collected", modifier = w)
            }
        }
        Spacer(Modifier.height(24.dp))

        // Highlights in plain words.
        val lines = highlights(s)
        if (lines.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("What stands out")
                    lines.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.mellow))
                            Spacer(Modifier.width(12.dp))
                            Text(l, color = Ink.text, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        val twoCol: @Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit = { a, b ->
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f)) { a() }
                Box(Modifier.weight(1f)) { b() }
            } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { a(); b() }
        }

        twoCol({
            Card(Modifier.fillMaxWidth()) {
                Column {
                    if (period == 0) {
                        SectionTitle("Points by hour")
                        val byHour = (0..23).map { h -> s.blocks.filter { minuteOfDay(it.startAt) / 60 == h }.sumOf { it.points }.toFloat() }
                        BarChart(byHour, (0..23).map { hourName(it) }, List(24) { Ink.mellow }, labelEvery = 6)
                    } else {
                        SectionTitle("Points per day", "Dashed line is your daily goal")
                        val labels = s.perDay.map { if (period == 1) formatDay(it.day, "EEE") else formatDay(it.day, "d") }
                        val colors = s.perDay.map { if (it.points >= goal) Ink.good else Ink.mellow }
                        if (period == 3) {
                            val months = s.perDay.groupBy { formatDay(it.day, "MMM") }
                            BarChart(months.values.map { m -> m.sumOf { it.points }.toFloat() }, months.keys.toList(), List(months.size) { Ink.mellow })
                        } else {
                            BarChart(s.perDay.map { it.points.toFloat() }, labels, colors, target = goal.toFloat(), labelEvery = if (period == 2) 5 else 1)
                        }
                    }
                }
            }
        }, {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("Focus quality", "Average rating; the soft line is a 7-point average")
                    if (period == 0) {
                        val pts = s.blocks.filter { store.category(it.categoryId, v.db)?.rest != true }.map { it.rating?.toDouble() }
                        LineChart(pts, s.blocks.map { clockText(it.startAt) }, labelEvery = (pts.size / 5).coerceAtLeast(1))
                    } else if (period == 3) {
                        val months = s.perDay.groupBy { formatDay(it.day, "MMM") }
                        LineChart(months.values.map { m -> m.mapNotNull { it.avgRating }.takeIf { it.isNotEmpty() }?.average() }, months.keys.toList())
                    } else {
                        LineChart(s.perDay.map { it.avgRating }, s.perDay.map { if (period == 1) formatDay(it.day, "EEE") else formatDay(it.day, "d") }, labelEvery = if (period == 2) 5 else 1)
                    }
                }
            }
        })
        Spacer(Modifier.height(24.dp))

        twoCol({
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("Where the time went")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Donut(
                            s.perCategory.map { (it.category?.color?.asColor() ?: Ink.faint) to it.minutes.toFloat() },
                            Modifier.size(150.dp), hoursText(s.trackedMinutes.toDouble()), "tracked",
                        )
                        Spacer(Modifier.width(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            s.perCategory.take(7).forEach { c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.category?.color?.asColor() ?: Ink.faint))
                                    Spacer(Modifier.width(8.dp))
                                    Text(c.category?.name ?: "Removed", color = Ink.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(hoursText(c.minutes.toDouble()), color = Ink.muted, fontSize = 12.sp)
                                    if (c.category?.rest != true) Text("  ${c.avgRating.roundToInt()}%", color = ratingColor(roundRating(c.avgRating)), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }, {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("How blocks went", "Share of check-ins at each rating")
                    val total = s.ratingCounts.values.sum().coerceAtLeast(1)
                    for (r in RATINGS.reversed()) {
                        val n = s.ratingCounts[r] ?: 0
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r == 0) "drift" else "$r%", color = ratingColor(r), fontSize = 12.sp, modifier = Modifier.width(44.dp))
                            ThinProgress(n.toFloat() / total, ratingColor(r), Modifier.weight(1f).height(8.dp))
                            Text("$n", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.width(36.dp).padding(start = 10.dp))
                        }
                    }
                }
            }
        })
        Spacer(Modifier.height(24.dp))

        Card(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle(
                    "When you focus best",
                    listOfNotNull(
                        s.bestHour?.let { "Strongest around ${hourName(it)}" },
                        s.bestWeekday?.let { "best day: ${weekdayName(it)}" },
                    ).joinToString(" · ").ifBlank { "Fills in as you check in at different times" },
                )
                HeatGrid(s.heat, s.heatCount)
            }
        }
        Spacer(Modifier.height(24.dp))

        Card(Modifier.fillMaxWidth()) {
            Column {
                val byDay = remember(v.db) { v.db.blocks.filter { it.rated }.groupBy { epochDay(it.startAt) }.mapValues { e -> e.value.sumOf { it.points } } }
                SectionTitle("The year so far", "${byDay.count { it.value > 0 }} active days · ${byDay.count { it.value >= goal }} days at goal")
                CalendarHeat(byDay, v.today, goal)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun roundRating(avg: Double): Int = RATINGS.minBy { kotlin.math.abs(it - avg) }

private fun rangeTitle(period: Int, r: DayRange, today: Int): String = when (period) {
    0 -> if (r.from == today) "Today" else if (r.from == today - 1) "Yesterday" else formatDay(r.from, "EEEE d MMM")
    1 -> if (today in r) "This week" else "${formatDay(r.from, "d MMM")} – ${formatDay(r.to, "d MMM")}"
    2 -> formatDay(r.from, "MMMM yyyy")
    else -> formatDay(r.from, "yyyy")
}

private fun delta(now: Double, before: Double, suffix: String): String? {
    if (before <= 0.0) return if (now > 0) "first data for this period" else null
    val pct = ((now - before) / before * 100).roundToInt()
    return if (pct >= 0) "▲ $pct% $suffix" else "▼ ${-pct}% $suffix"
}

private fun deltaColor(now: Double, before: Double): Color = if (before > 0 && now < before) Ink.muted else Ink.good

/** Positive, specific sentences — never a scolding. */
private fun highlights(s: Insights): List<String> {
    val out = mutableListOf<String>()
    val best = s.perDay.maxByOrNull { it.points }
    if (s.range.days > 1 && best != null && best.points > 0) out += "Best day: ${formatDay(best.day, "EEEE d MMM")} with ${best.points} points."
    s.perCategory.filter { it.category?.rest != true && it.blocks >= 3 }.maxByOrNull { it.avgRating }?.let {
        out += "You focus most deeply on ${it.category?.name ?: "?"} (${it.avgRating.roundToInt()}% average)."
    }
    if (s.comebacks > 0) out += "${s.comebacks} comeback${if (s.comebacks > 1) "s" else ""}: drifting and returning is exactly the skill you're training."
    if (s.longestRun >= 3) out += "Longest run of good blocks: ${s.longestRun} in a row."
    s.bestHour?.let { out += "Your sharpest hour is ${hourName(it)} — guard it for hard work." }
    if (s.restMinutes > 0 && s.ratedBlocks > 0) out += "You took ${hoursText(s.restMinutes.toDouble())} of breaks. Rested brains focus better."
    return out.take(5)
}
