package app.fifteen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Current time, re-read every [stepMs]. */
@Composable
fun rememberNow(stepMs: Long = 1000): State<Long> {
    val now = remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(stepMs) {
        while (true) {
            now.longValue = nowMs()
            delay(stepMs - (now.longValue % stepMs).coerceAtMost(stepMs - 1))
        }
    }
    return now
}

/** Live view of the store for composables. */
data class View(
    val db: LocalDb,
    val prefs: LocalPrefs,
    val now: Long,
    val live: Block?,
    val phase: Phase,
    val category: Category?,
    val today: Int,
    val dayPoints: Int,
    val xp: Int,
) {
    val level get() = levelFor(xp)
    val progress: Float get() = live?.let { ((now - it.startAt).toFloat() / (it.minutes * 60_000f)).coerceIn(0f, 1f) } ?: 0f
    val remaining: Long get() = live?.let { it.endAt - now } ?: 0
    fun petState(): PetState {
        val todays = db.blocks.filter { it.rated && epochDay(it.startAt) == today }
        val recent = todays.sortedByDescending { it.startAt }.take(4).mapNotNull { it.rating }
        return PetState(
            growth = growthFor(dayPoints, db.profile.dailyGoal),
            fruits = todays.count { it.rating == 100 },
            // Wakes up to talk (e.g. the rest check-in).
            asleep = phase == Phase.Idle && db.nudge.active == null,
            level = level,
            mood = if (recent.isEmpty()) 0.6f else recent.average().toFloat() / 100f,
            drifted = todays.maxByOrNull { it.startAt }?.rating == 0,
        )
    }
}

@Composable
fun rememberView(store: Store, stepMs: Long = 1000): View {
    val db by store.db.collectAsState()
    val prefs by store.prefs.collectAsState()
    val now by rememberNow(stepMs)
    val live = store.liveBlock(db)
    val today = epochDay(now)
    return View(
        db, prefs, now, live, store.phase(now, db), store.category(live?.categoryId, db), today,
        remember(db, today) { store.dayPoints(today, db) }, remember(db) { store.totalXp(db) },
    )
}

/**
 * The calm progress bar: category dot, a thin filling line and the time left. Used as the desktop
 * floating bar and inside the apps.
 */
@Composable
fun FocusBar(v: View, modifier: Modifier = Modifier, showQuote: Boolean = true, onClick: (() -> Unit)? = null) {
    val color = v.category?.color?.asColor() ?: Ink.mellow
    val pulse = rememberInfiniteTransition()
    val glow by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse))
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE6121318))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (v.phase == Phase.Due) Ink.mellow.copy(alpha = glow) else color))
        Spacer(Modifier.width(9.dp))
        when (v.phase) {
            Phase.Idle -> {
                Text("Paused — tap to start a block", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            }
            Phase.Due -> {
                Text("Block done · check in", color = Ink.mellow.copy(alpha = glow), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            }
            Phase.Running -> {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            v.live?.intent?.ifBlank { null } ?: v.category?.name ?: "",
                            color = Ink.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (showQuote && v.db.profile.quotes) {
                            val (q, _) = quoteFor(v.live?.id ?: "")
                            Text("  ·  $q", color = Ink.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    ThinProgress(v.progress, color, Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (v.phase == Phase.Running) {
            Text(durationText(v.remaining), color = Ink.text.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
fun ThinProgress(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color(0x22FFFFFF), cornerRadius = r)
        if (progress > 0f) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color), 0f, size.width * progress),
                size = Size(size.width * progress, size.height), cornerRadius = r,
            )
            drawCircle(color, radius = size.height * 0.9f, center = Offset(size.width * progress, size.height / 2))
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.surface)
            .border(1.dp, Ink.line, RoundedCornerShape(22.dp))
            .padding(padding),
    ) { content() }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Ink.muted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun Pill(text: String, color: Color = Ink.muted, selected: Boolean = false, modifier: Modifier = Modifier, dot: Color? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.18f) else Color(0x0FFFFFFF))
            .border(1.dp, if (selected) color.copy(alpha = 0.7f) else Color(0x14FFFFFF), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (selected) Ink.text else Ink.muted, fontSize = 13.sp)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Ink.mellow, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) color else Ink.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color(0xFF16130A) else Ink.faint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Ink.muted, hPad: Dp = 18.dp, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = hPad, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Grid whose columns fill the available width: as many columns of at least [minItem] as fit (up to
 * [maxColumns]), each stretched so rows end flush with the right edge.
 */
@Composable
fun AdaptiveGrid(minItem: Dp, gap: Dp, count: Int, modifier: Modifier = Modifier, maxColumns: Int = 12, item: @Composable (index: Int, itemModifier: Modifier) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = ((maxWidth + gap) / (minItem + gap)).toInt().coerceIn(1, maxColumns)
        val w = (maxWidth - gap * (cols - 1)) / cols
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until (count + cols - 1) / cols) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (c in 0 until cols) {
                        val i = row * cols + c
                        if (i < count) item(i, Modifier.width(w))
                    }
                }
            }
        }
    }
}

/** 24 columns × 4 quarter-hours: each cell is a 15-minute slot, tinted by category, brightness by focus. */
@Composable
fun DayRibbon(db: LocalDb, day: Int, now: Long, modifier: Modifier = Modifier, live: Block? = null) {
    val cats = remember(db) { db.categories.associateBy { it.id } }
    val slots = remember(db, day, live?.id) {
        val arr = arrayOfNulls<Pair<Color, Float>>(96)
        val start = dayStartMs(day)
        for (b in db.blocks) {
            if (b.deleted || epochDay(b.startAt) != day) continue
            if (!b.rated && !b.live) continue
            val color = cats[b.categoryId]?.color?.asColor() ?: Ink.mellow
            val alpha = when {
                b.live -> 0.55f
                cats[b.categoryId]?.rest == true -> 0.45f
                else -> 0.25f + 0.75f * (b.rating ?: 0) / 100f
            }
            val first = ((b.startAt - start) / (15 * 60_000L)).toInt()
            val n = (b.minutes + 14) / 15
            for (i in first until (first + n).coerceAtMost(96)) if (i >= 0) arr[i] = color to alpha
        }
        arr
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val gap = 3f
            val cw = (size.width - gap * 23) / 24
            val ch = (size.height - gap * 3) / 4
            val nowSlot = if (epochDay(now) == day) ((now - dayStartMs(day)) / (15 * 60_000L)).toInt() else -1
            for (i in 0 until 96) {
                val col = i / 4; val row = i % 4
                val tl = Offset(col * (cw + gap), row * (ch + gap))
                val s = slots[i]
                val c = s?.let { it.first.copy(alpha = it.second) } ?: Color(0x10FFFFFF)
                drawRoundRect(c, tl, Size(cw, ch), CornerRadius(3f))
                if (s?.first != null && s.second <= 0.26f) {
                    drawLine(Ink.coral.copy(alpha = 0.8f), tl + Offset(cw * 0.3f, ch * 0.5f), tl + Offset(cw * 0.7f, ch * 0.5f), strokeWidth = 1.5f)
                }
                if (i == nowSlot) drawRoundRect(Color.White.copy(alpha = 0.8f), tl, Size(cw, ch), CornerRadius(3f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            for (h in listOf(0, 6, 12, 18)) {
                Text(hourName(h), color = Ink.faint, fontSize = 10.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun RatingBadge(rating: Int?, size: Dp = 30.dp) {
    val c = ratingColor(rating)
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.16f)).border(1.dp, c.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
        if (rating == 0) {
            Canvas(Modifier.size(size * 0.36f)) {
                drawLine(c, Offset.Zero, Offset(this.size.width, this.size.height), strokeWidth = 4f)
                drawLine(c, Offset(this.size.width, 0f), Offset(0f, this.size.height), strokeWidth = 4f)
            }
        } else {
            Text(rating?.toString() ?: "–", color = c, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ArtifactIcon(a: Artifact, owned: Boolean, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition()
    val t by tr.animateFloat(0f, 100f, infiniteRepeatable(tween(100_000, easing = LinearEasing)))
    Canvas(modifier) { drawArtifact(a, owned, t) }
}

@Composable
fun Stat(label: String, value: String, sub: String? = null, subColor: Color = Ink.muted, modifier: Modifier = Modifier) {
    Column(modifier) {
        Label(label)
        Spacer(Modifier.height(8.dp))
        Text(value, color = Ink.text, fontSize = 26.sp, fontWeight = FontWeight.Light)
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, color = subColor, fontSize = 12.sp)
        }
    }
}

@Composable
fun SectionTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, color = Ink.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, color = Ink.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.line))
}

@Composable
fun Toggle(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink.text, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Ink.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.width(42.dp).height(24.dp).clip(RoundedCornerShape(50)).background(if (checked) Ink.mellow else Ink.surface2).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) Color(0xFF1A1405) else Ink.muted))
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0x0FFFFFFF)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (i == selected) Ink.surface2 else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = if (i == selected) Ink.text else Ink.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun VerticalGap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13) = Text(text, color = Ink.muted, fontSize = size.sp, modifier = modifier)

@Composable
fun FadeIf(visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (visible) 1f else 0f).fillMaxHeight()) { content() }
}

/** The tiny timer under the companion in pet mode. */
@Composable
fun MiniTimer(v: View) {
    val color = v.category?.color?.asColor() ?: Ink.mellow
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xD9121318))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (v.phase) {
                Phase.Running -> durationText(v.remaining)
                Phase.Due -> "check in"
                Phase.Idle -> "resting"
            },
            color = if (v.phase == Phase.Due) Ink.mellow else Ink.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
        if (v.phase == Phase.Running) {
            Spacer(Modifier.height(4.dp))
            ThinProgress(v.progress, color, Modifier.fillMaxWidth().height(3.dp))
        }
    }
}
