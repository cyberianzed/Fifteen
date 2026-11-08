package app.nimisham

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NowScreen(store: Store, platform: PlatformUi, wide: Boolean, onFocus: () -> Unit) {
    val v = rememberView(store)
    Page(wide) {
        Header(v, wide)
        Spacer(Modifier.height(if (wide) 32.dp else 20.dp))
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1.05f)) { Hero(store, v, platform, onFocus) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) { TodayColumn(store, v) }
            }
        } else {
            Hero(store, v, platform, onFocus)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { TodayColumn(store, v) }
        }
    }
}

@Composable
private fun Header(v: View, wide: Boolean) {
    val streak = remember(v.db, v.today) { dayStreak(v.db, v.today) }
    val chips: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Lv ${v.level} · ${rankFor(v.level)}-rank", color = Ink.violet, selected = true)
            if (streak > 0) Pill("$streak-day streak", color = Ink.good, selected = true)
        }
    }
    val title: @Composable () -> Unit = {
        Column {
            Label(formatDay(v.today, "EEEE, d MMMM"))
            Spacer(Modifier.height(6.dp))
            Text(greeting(v.now), color = Ink.text, fontSize = 28.sp, fontWeight = FontWeight.Light)
        }
    }
    if (wide) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { title() }
            chips()
        }
    } else {
        title()
        Spacer(Modifier.height(12.dp))
        chips()
    }
}

private fun greeting(now: Long): String {
    val h = minuteOfDay(now) / 60
    return when {
        h < 5 -> "Late night, gently"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        h < 21 -> "Good evening"
        else -> "Winding down"
    }
}

@Composable
private fun Hero(store: Store, v: View, platform: PlatformUi, onFocus: () -> Unit) {
    val color = v.category?.color?.asColor() ?: Ink.mellow
    Card(Modifier.fillMaxWidth(), padding = 28.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            PetView(v.db.profile.avatar, v.petState(), Modifier.size(240.dp))
            val goal = v.db.profile.dailyGoal
            Text("${v.dayPoints}", color = Ink.text, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Muted(if (v.dayPoints >= goal) "points today · goal reached" else "of $goal points today")
            Spacer(Modifier.height(10.dp))
            ThinProgress((v.dayPoints.toFloat() / goal).coerceIn(0f, 1f), Ink.mellow, Modifier.width(200.dp).height(3.dp))
            Spacer(Modifier.height(28.dp))
            Hairline()
            Spacer(Modifier.height(24.dp))
            when (v.phase) {
                Phase.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(8.dp))
                        Text(v.category?.name ?: "", color = color, fontSize = 14.sp)
                    }
                    if (!v.live?.intent.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(v.live!!.intent, color = Ink.text, fontSize = 16.sp, textAlign = TextAlign.Center)
                    }
                    Text(durationText(v.remaining), color = Ink.text, fontSize = 64.sp, fontWeight = FontWeight.ExtraLight)
                    ThinProgress(v.progress, color, Modifier.fillMaxWidth(0.8f).height(4.dp))
                    bountyText(v.live?.bounty)?.let { Spacer(Modifier.height(14.dp)); BountyBanner(it) }
                    Spacer(Modifier.height(22.dp))
                    // Equal thirds, so all three always fit on one line (even on a 360dp phone).
                    Row(Modifier.fillMaxWidth().widthIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Focus view", Modifier.weight(1f), hPad = 6.dp, onClick = onFocus)
                        GhostButton("Switch", Modifier.weight(1f), hPad = 6.dp) { platform.openCheckIn() }
                        GhostButton("Stop", Modifier.weight(1f), hPad = 6.dp) { store.stopTracking() }
                    }
                }
                Phase.Due -> {
                    Text("Block finished", color = Ink.text, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    Muted("Tell your companion how it went.")
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("Check in") { platform.openCheckIn() }
                }
                Phase.Idle -> {
                    Text("Tracking is resting", color = Ink.text, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    Muted("Untracked time is fine — nothing is lost while you're away.")
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("Start a block") { platform.openCheckIn() }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayColumn(store: Store, v: View) {
    val today = remember(v.db, v.today) { computeInsights(v.db, DayRange(v.today, v.today)) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Today", "Each square is fifteen minutes. Brighter means more focused.")
            DayRibbon(v.db, v.today, v.now, live = v.live)
            Spacer(Modifier.height(22.dp))
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Stat("Focus", hoursText(today.focusMinutes), modifier = Modifier.weight(1f))
                Stat("Average", today.avgRating?.let { "${it.toInt()}%" } ?: "–", modifier = Modifier.weight(1f))
                Stat("Comebacks", "${today.comebacks}", modifier = Modifier.weight(1f))
                Stat("Finds", "${today.rewards}", modifier = Modifier.weight(1f))
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Blocks")
            val list = v.db.blocks.filter { !it.deleted && epochDay(it.startAt) == v.today && (it.rated || it.live) }.sortedByDescending { it.startAt }
            if (list.isEmpty()) Muted("Nothing yet today. Your first block plants the seed.")
            list.forEachIndexed { i, b ->
                if (i > 0) Hairline(Modifier.padding(vertical = 2.dp))
                BlockRow(store, b, store.category(b.categoryId, v.db))
            }
        }
    }
}

@Composable
private fun BlockRow(store: Store, b: Block, cat: Category?) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RatingBadge(b.rating)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(cat?.color?.asColor() ?: Ink.faint))
                Spacer(Modifier.width(7.dp))
                Text(b.intent.ifBlank { cat?.name ?: "?" }, color = Ink.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${clockText(b.startAt)} · ${b.minutes}m" + (if (b.live) " · now" else "") +
                    b.bonuses.filterNot { it.startsWith("next:") }.filter { it.contains("×") }.joinToString("") { " · $it" },
                color = Ink.faint, fontSize = 12.sp,
            )
        }
        artifactById(b.reward)?.let { ArtifactIcon(it, true, Modifier.size(28.dp)) }
        if (b.rated) Text("+${b.points}", color = Ink.mellow, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
        if (b.rated) {
            Text(
                if (confirm) "delete?" else "×", color = if (confirm) Ink.coral else Ink.faint, fontSize = if (confirm) 12.sp else 16.sp,
                modifier = Modifier.padding(start = 12.dp).clip(CircleShape).clickable { if (confirm) store.deleteBlock(b.id) else confirm = true }.padding(6.dp),
            )
        }
    }
}

// ---------------- Garden ----------------

@Composable
fun GardenScreen(store: Store, wide: Boolean) {
    val v = rememberView(store, 5000)
    val owned = remember(v.db) { v.db.blocks.filter { it.rated && it.reward != null }.groupingBy { it.reward!! }.eachCount() }
    Page(wide) {
        Label("Garden")
        Spacer(Modifier.height(6.dp))
        Text("Your companion", color = Ink.text, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(24.dp))
        val preview = v.petState().copy(asleep = false, growth = maxOf(0.7f, v.petState().growth))
        // Three across on every screen; on phones the description of the chosen one sits underneath.
        AdaptiveGrid(minItem = if (wide) 220.dp else 96.dp, gap = if (wide) 16.dp else 10.dp, count = AVATARS.size, maxColumns = 3) { i, mod ->
            val a = AVATARS[i]
            val sel = v.db.profile.avatar == a.id
            Column(
                mod.clip(RoundedCornerShape(22.dp))
                    .background(if (sel) Ink.surface2 else Ink.surface)
                    .border(1.dp, if (sel) Ink.mellow.copy(alpha = 0.6f) else Ink.line, RoundedCornerShape(22.dp))
                    .clickable { store.updateProfile { it.copy(avatar = a.id) } }.padding(if (wide) 16.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PetView(a.id, preview, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(a.name, color = Ink.text, fontSize = if (wide) 15.sp else 13.sp)
                if (wide) {
                    Spacer(Modifier.height(4.dp))
                    Muted(a.blurb, size = 12, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
        if (!wide) {
            Spacer(Modifier.height(10.dp))
            Muted(AVATARS.firstOrNull { it.id == v.db.profile.avatar }?.blurb ?: "", size = 12)
        }
        Spacer(Modifier.height(28.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                val lv = v.level
                val from = xpForLevel(lv); val to = xpForLevel(lv + 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Label("Level")
                        Text("$lv", color = Ink.text, fontSize = 40.sp, fontWeight = FontWeight.Light)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Label("Rank")
                        Text(rankFor(lv), color = Ink.violet, fontSize = 40.sp, fontWeight = FontWeight.Light)
                    }
                }
                Spacer(Modifier.height(10.dp))
                ThinProgress(((v.xp - from).toFloat() / (to - from)).coerceIn(0f, 1f), Ink.violet, Modifier.fillMaxWidth().height(4.dp))
                Spacer(Modifier.height(8.dp))
                Muted("${v.xp} XP · ${to - v.xp} to level ${lv + 1}. XP never goes down.")
            }
        }
        Spacer(Modifier.height(28.dp))
        SectionTitle("Your forest", "One companion per day, grown by that day's points.")
        val days = if (wide) 28 else 15
        val first = v.today - days + 1
        AdaptiveGrid(minItem = if (wide) 112.dp else 96.dp, gap = 10.dp, count = days) { i, mod ->
            val d = first + i
            val pts = store.dayPoints(d, v.db)
            val fruits = v.db.blocks.count { it.rated && it.rating == 100 && epochDay(it.startAt) == d }
            Column(
                mod.clip(RoundedCornerShape(16.dp)).background(Ink.surface).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pts > 0) PetView(v.db.profile.avatar, PetState(growthFor(pts, v.db.profile.dailyGoal), fruits, asleep = false, level = v.level), Modifier.size(80.dp), interactive = false)
                else Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.faint)) }
                Text(formatDay(d, "EEE d"), color = if (d == v.today) Ink.text else Ink.muted, fontSize = 11.sp)
                Text(if (pts > 0) "$pts pts" else "—", color = Ink.faint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
        SectionTitle("Collection", "${owned.size} of ${ARTIFACTS.size} found. Drops are random — better blocks and comebacks raise the odds.")
        val artifacts = ARTIFACTS.sortedBy { it.rarity }
        AdaptiveGrid(minItem = if (wide) 128.dp else 96.dp, gap = if (wide) 12.dp else 10.dp, count = artifacts.size) { i, mod ->
            val a = artifacts[i]
            val n = owned[a.id] ?: 0
            Column(
                mod.clip(RoundedCornerShape(18.dp)).background(Ink.surface)
                    .border(1.dp, if (n > 0) RARITY_COLORS[a.rarity].asColor().copy(alpha = 0.35f) else Ink.line, RoundedCornerShape(18.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtifactIcon(a, n > 0, Modifier.size(56.dp))
                Text(if (n > 0) a.name else "???", color = if (n > 0) Ink.text else Ink.faint, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, minLines = 2)
                Text(if (n > 1) "${RARITY_NAMES[a.rarity]} ×$n" else RARITY_NAMES[a.rarity], color = RARITY_COLORS[a.rarity].asColor().copy(alpha = if (n > 0) 1f else 0.4f), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** Tiny helper used where a square spacer keeps layouts aligned. */
@Composable
fun SquareBox(modifier: Modifier = Modifier, color: Color = Color.Transparent) = Box(modifier.aspectRatio(1f).background(color))
