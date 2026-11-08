package app.fifteen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * The companion's chat: a small avatar and speech bubbles that appear one by one after "typing" dots,
 * then the answer buttons and, for eye breaks, a 20-second ring timer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NudgeBubble(
    store: Store,
    modifier: Modifier = Modifier,
    showAvatar: Boolean = true,
    maxWidth: Dp = 360.dp,
    /** Companion on the right with messages lined up towards it (for a bubble sitting on the right edge). */
    mirrored: Boolean = false,
    /** Only the companion, like a chat head (while it's being dragged). */
    collapsed: Boolean = false,
) {
    val db by store.db.collectAsState()
    val prefs by store.prefs.collectAsState()
    if (!store.nudges.visible(db, prefs)) return
    val a = db.nudge.active ?: return
    val now by rememberNow(200)
    val step = store.nudges.step(a, db.nudge, db.profile)
    val since = now - a.stageAt
    val shown = ((since - 900) / MESSAGE_GAP_MS + 1).toInt().coerceIn(0, step.messages.size)
    val typing = shown < step.messages.size
    val done = !typing

    val avatar: @Composable () -> Unit = {
        PetView(db.profile.avatar, PetState(0.8f, 2, asleep = false, level = levelFor(store.totalXp(db))), Modifier.size(52.dp), interactive = false)
    }
    if (collapsed) {
        Box(modifier) { avatar() }
        return
    }
    val side = if (mirrored) Alignment.End else Alignment.Start
    Row(modifier.widthIn(max = maxWidth), verticalAlignment = Alignment.Bottom) {
        if (showAvatar && !mirrored) {
            avatar()
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = side) {
            // Solid pill so the name and close button stay readable over any wallpaper or window.
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xE6121318)).padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(AVATARS.firstOrNull { it.id == db.profile.avatar }?.name ?: "", color = Ink.muted, fontSize = 11.sp)
                Text(
                    "×", color = Ink.muted, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 6.dp).clip(CircleShape).clickable { store.nudges.answer("close") }.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            for (i in 0 until shown) {
                androidx.compose.runtime.key(a.id, a.stage, i) {
                    AnimatedVisibility(true, enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 }) {
                        ChatBubble(step.messages[i], mirrored)
                    }
                }
            }
            if (typing) TypingDots()
            if (done && step.timerSec > 0 && a.timerEnd > 0) {
                val left = ((a.timerEnd - now).coerceAtLeast(0) + 999) / 1000
                RingTimer(1f - (a.timerEnd - now).coerceAtLeast(0) / (step.timerSec * 1000f), "$left")
            }
            if (done && step.buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, if (mirrored) Alignment.End else Alignment.Start), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    step.buttons.forEachIndexed { i, (label, action) ->
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (i == 0) Ink.mellow else Color(0xF21E2129))
                                .border(1.dp, if (i == 0) Ink.mellow else Color(0x33FFFFFF), RoundedCornerShape(50))
                                .clickable { store.nudges.answer(action) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(label, color = if (i == 0) Color(0xFF16130A) else Ink.text, fontSize = 13.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        if (showAvatar && mirrored) {
            Spacer(Modifier.width(6.dp))
            avatar()
        }
    }
}

@Composable
private fun ChatBubble(text: String, mirrored: Boolean = false) {
    // The small corner points at the companion.
    val shape = if (mirrored) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
    else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    Box(
        Modifier.clip(shape)
            .background(Color(0xF21E2129)).border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(text, color = Ink.text, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
}

@Composable
fun TypingDots() {
    val t by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xF21E2129)).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until 3) {
                val ph = ((t - i * 0.18f) * 2 * kotlin.math.PI).toFloat()
                Box(Modifier.offset(y = (-3 * maxOf(0f, sin(ph))).dp).size(6.dp).clip(CircleShape).background(Ink.muted.copy(alpha = 0.5f + 0.5f * maxOf(0f, sin(ph)))))
            }
        }
    }
}

@Composable
private fun RingTimer(progress: Float, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(54.dp)) {
                val s = 5.dp.toPx()
                drawArc(Color(0x22FFFFFF), 0f, 360f, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
                drawArc(Ink.sky, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
            }
            Text(label, color = Ink.text, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Muted("seconds of looking far away", size = 12)
    }
}

// ---------------- Add-ons tab ----------------

@Composable
fun AddonsScreen(store: Store, wide: Boolean) {
    val v = rememberView(store, 5000)
    val p = v.db.profile
    val n = v.db.nudge
    Page(wide) {
        Label("Add-ons")
        Spacer(Modifier.height(6.dp))
        Text("Little helpers", color = Ink.text, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(6.dp))
        Muted("Your companion brings these up in a chat bubble while it's floating on screen. Answer on any device.")
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("When", color = Ink.text, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("Anytime", "Only during blocks"), if (p.addonsAnytime) 0 else 1) { i -> store.updateProfile { it.copy(addonsAnytime = i == 0) } }
                Spacer(Modifier.height(8.dp))
                Muted(
                    if (p.addonsAnytime) "Reminders keep coming while you rest too, as long as your pet is out."
                    else "Reminders only come while a block is running.",
                    size = 12,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        AddonCard(
            "Eye breaks (20-20-20)",
            "Every ${p.eyeEvery} minutes: look about 6 metres away for 20 seconds. A small timer runs beside your block timer.",
            p.eyeOn, { on -> store.updateProfile { it.copy(eyeOn = on) } }, { store.nudges.begin("eye", "ask") },
        ) {
            Text("Every", color = Ink.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("20 min", "30 min", "45 min"), listOf(20, 30, 45).indexOf(p.eyeEvery).coerceAtLeast(0)) { i -> store.updateProfile { it.copy(eyeEvery = listOf(20, 30, 45)[i]) } }
            Spacer(Modifier.height(10.dp))
            Muted("${n.eyes[v.today] ?: 0} eye breaks today", size = 12)
        }
        Spacer(Modifier.height(18.dp))
        AddonCard(
            "Water",
            "A reminder, then a friendly \"did you drink?\" a few minutes later. Say no and it checks back or offers to remind you again.",
            p.waterOn, { on -> store.updateProfile { it.copy(waterOn = on) } }, { store.nudges.begin("water", "remind") },
        ) {
            Text("Remind every", color = Ink.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("30m", "45m", "1h", "1½h"), listOf(30, 45, 60, 90).indexOf(p.waterEvery).coerceAtLeast(0)) { i -> store.updateProfile { it.copy(waterEvery = listOf(30, 45, 60, 90)[i]) } }
            Spacer(Modifier.height(12.dp))
            Text("Daily goal", color = Ink.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("6 glasses", "8 glasses", "10 glasses"), listOf(6, 8, 10).indexOf(p.waterGoal).coerceAtLeast(0)) { i -> store.updateProfile { it.copy(waterGoal = listOf(6, 8, 10)[i]) } }
            Spacer(Modifier.height(14.dp))
            val glasses = n.water[v.today] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until p.waterGoal) {
                    Canvas(Modifier.size(18.dp, 22.dp)) {
                        val full = i < glasses
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width / 2, 0f)
                            cubicTo(size.width * 1.1f, size.height * 0.55f, size.width * 0.95f, size.height, size.width / 2, size.height)
                            cubicTo(size.width * 0.05f, size.height, -size.width * 0.1f, size.height * 0.55f, size.width / 2, 0f)
                        }
                        if (full) drawPath(path, Ink.sky) else drawPath(path, Color(0x33FFFFFF), style = Stroke(1.5.dp.toPx()))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(8.dp))
                Muted("$glasses / ${p.waterGoal}", size = 12)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("+1 glass") { store.nudges.addGlass(1) }
                if (glasses > 0) GhostButton("−1") { store.nudges.addGlass(-1) }
            }
        }
        Spacer(Modifier.height(18.dp))
        AddonCard(
            "Check-ins",
            "Every now and then: \"Everything okay? Feeling productive?\" If not, a small idea to reset: a stretch, a walk, some water.",
            p.wellOn, { on -> store.updateProfile { it.copy(wellOn = on) } }, { store.nudges.begin("well", "ask") },
        ) {
            Text("How often", color = Ink.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("Rarely", "Sometimes", "Often"), p.wellFreq.coerceIn(0, 2)) { i -> store.updateProfile { it.copy(wellFreq = i) } }
        }
        Spacer(Modifier.height(18.dp))
        AddonCard(
            "While you rest",
            "When you've been resting for a while, your sleeping companion wakes up and asks if everything's okay, and offers to start a block. It asks at most twice, then lets you be.",
            p.restOn, { on -> store.updateProfile { it.copy(restOn = on) } }, { store.nudges.begin("rest", "ask") },
        ) {
            Text("Wake up after", color = Ink.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            val options = listOf(20, 30, 45, 60)
            Segmented(listOf("20m", "30m", "45m", "1h"), options.indexOf(p.restAfter).coerceAtLeast(0)) { i -> store.updateProfile { it.copy(restAfter = options[i]) } }
        }
        Spacer(Modifier.height(18.dp))
        Muted("Have an idea for an add-on? Send it as a pull request on GitHub; add-ons are a great first contribution.", size = 12)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AddonCard(title: String, blurb: String, on: Boolean, onToggle: (Boolean) -> Unit, onTry: () -> Unit, settings: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Toggle(title, on, blurb, onToggle)
            if (on) {
                Spacer(Modifier.height(12.dp))
                settings()
                Spacer(Modifier.height(14.dp))
                GhostButton("Try it now", onClick = onTry)
            }
        }
    }
}
