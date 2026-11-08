package app.nimisham

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The check-in: rate the block that just ended (0 / 50 / 75 / 90 / 100), see what it earned,
 * then pick what comes next and for how long.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckInPanel(store: Store, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val v = rememberView(store)
    val reveal by store.reveal.collectAsState()
    val dueBlock = if (v.phase == Phase.Due) v.live else null

    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            dueBlock != null -> RateStep(store, dueBlock, v)
            reveal != null -> RevealStep(reveal!!, v)
        }
        if (dueBlock == null) {
            if (reveal != null) VerticalGap(26.dp)
            NextStep(store, v, onClose)
        }
    }
}

@Composable
private fun RateStep(store: Store, block: Block, v: View) {
    val cat = store.category(block.categoryId, v.db)
    val rest = cat?.rest == true
    Label(if (rest) "Break's over" else "Check in", color = cat?.color?.asColor() ?: Ink.mellow)
    VerticalGap(10.dp)
    Text(
        if (rest) "Feeling rested?" else "How did that block go?",
        color = Ink.text, fontSize = 24.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
    )
    VerticalGap(6.dp)
    Muted("${block.intent.ifBlank { cat?.name ?: "" }} · ${clockText(block.startAt)}–${clockText(block.endAt)}")
    bountyText(block.bounty)?.let {
        VerticalGap(12.dp)
        BountyBanner(it)
    }
    VerticalGap(26.dp)
    if (rest) {
        PrimaryButton("I'm back", onClick = { store.rate(block.id, 100) })
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val words = listOf("drifted", "half", "most", "nearly", "all of it")
            RATINGS.forEachIndexed { i, r ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RatingChoice(r) { store.rate(block.id, r) }
                    VerticalGap(8.dp)
                    Text(words[i], color = Ink.faint, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
    VerticalGap(20.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("+5 min") { store.extend(5) }
        GhostButton("Stop tracking") { store.stopTracking() }
    }
}

@Composable
private fun RatingChoice(r: Int, onClick: () -> Unit) {
    val c = ratingColor(r)
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 300f + r * 2f)) }
    Box(
        Modifier.size(62.dp).scale(scale.value).clip(CircleShape)
            .background(c.copy(alpha = 0.12f)).border(1.5.dp, c.copy(alpha = 0.75f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (r == 0) {
            Canvas(Modifier.size(20.dp)) {
                drawLine(c, Offset.Zero, Offset(size.width, size.height), strokeWidth = 5f)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 5f)
            }
        } else {
            Text("$r", color = c, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RevealStep(reveal: Reveal, v: View) {
    val o = reveal.outcome
    val counter = remember(reveal.blockId) { Animatable(0f) }
    LaunchedEffect(reveal.blockId) { counter.animateTo(o.points.toFloat(), tween(900)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RatingBadge(reveal.rating, 36.dp)
        Spacer(Modifier.width(14.dp))
        Text("+${counter.value.toInt()}", color = Ink.mellow, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(8.dp))
        Muted("points", size = 14)
    }
    VerticalGap(8.dp)
    Text(o.message, color = Ink.text, fontSize = 15.sp, textAlign = TextAlign.Center)
    val shown = o.bonuses.filterNot { it.startsWith("next:") }
    if (shown.isNotEmpty()) {
        VerticalGap(12.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { Pill(it, color = Ink.mellow, selected = it.contains("×")) }
        }
    }
    VerticalGap(10.dp)
    val goal = v.db.profile.dailyGoal
    Muted("Today ${reveal.dayPoints} / $goal" + if (reveal.dayPoints >= goal) "  ·  goal reached, your companion is glowing" else "")
    o.reward?.let { a ->
        VerticalGap(18.dp)
        AnimatedVisibility(true, enter = fadeIn(tween(500)) + scaleIn(spring(dampingRatio = 0.45f))) {
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(RARITY_COLORS[a.rarity].asColor().copy(alpha = 0.10f))
                    .border(1.dp, RARITY_COLORS[a.rarity].asColor().copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtifactIcon(a, true, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Label("You found · ${RARITY_NAMES[a.rarity]}", color = RARITY_COLORS[a.rarity].asColor())
                    Text(a.name, color = Ink.text, fontSize = 17.sp)
                    Muted(a.lore, size = 12)
                }
            }
        }
    }
}

@Composable
fun BountyBanner(text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Ink.mellow.copy(alpha = 0.1f)).border(1.dp, Ink.mellow.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.mellow))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Ink.mellow, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextStep(store: Store, v: View, onClose: () -> Unit) {
    val cats = store.activeCategories(v.db)
    val lastCat = v.db.blocks.filter { !it.deleted }.maxByOrNull { it.startAt }?.categoryId
    var selected by remember { mutableStateOf(lastCat?.takeIf { id -> cats.any { it.id == id } } ?: cats.firstOrNull()?.id) }
    var minutes by remember { mutableIntStateOf(v.db.profile.defaultMinutes) }
    var intent by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Label("Next")
    VerticalGap(10.dp)
    Text("What are you doing next?", color = Ink.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
    store.pendingBounty(v.db, v.now)?.let { b ->
        bountyText(b)?.let { VerticalGap(12.dp); BountyBanner(it) }
    }
    VerticalGap(20.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in cats) {
            Pill(c.name, color = c.color.asColor(), dot = c.color.asColor(), selected = selected == c.id) { selected = c.id }
        }
        if (!adding) Pill("+ New", onClick = { adding = true })
    }
    if (adding) {
        VerticalGap(12.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputField(newName, { newName = it }, "Category name", Modifier.width(220.dp)) {
                if (newName.isNotBlank()) {
                    val c = store.addCategory(newName, CATEGORY_COLORS[(cats.size) % CATEGORY_COLORS.size])
                    selected = c.id; newName = ""; adding = false
                }
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("Add") {
                if (newName.isNotBlank()) {
                    val c = store.addCategory(newName, CATEGORY_COLORS[(cats.size) % CATEGORY_COLORS.size])
                    selected = c.id; newName = ""; adding = false
                }
            }
        }
    }
    VerticalGap(14.dp)
    InputField(intent, { intent = it }, "Optional: the one thing for this block", Modifier.fillMaxWidth()) {
        selected?.let { store.start(it, minutes, intent); onClose() }
    }
    VerticalGap(18.dp)
    Segmented(DURATIONS.map { "$it min" }, DURATIONS.indexOf(minutes).coerceAtLeast(0)) { minutes = DURATIONS[it] }
    VerticalGap(22.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (v.phase != Phase.Idle || v.live == null) GhostButton(if (v.phase == Phase.Idle) "Not now" else "Close") {
            store.dismissReveal(); onClose()
        }
        if (v.phase == Phase.Running) GhostButton("Stop tracking") { store.stopTracking(); onClose() }
        PrimaryButton("Start ${minutes}m", enabled = selected != null) {
            selected?.let { store.start(it, minutes, intent); onClose() }
        }
    }
}

@Composable
fun InputField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, password: Boolean = false, onDone: (() -> Unit)? = null) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x0FFFFFFF)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Ink.faint, fontSize = 14.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Ink.text, fontSize = 14.sp),
            cursorBrush = SolidColor(Ink.mellow),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
