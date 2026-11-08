package app.nimisham

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Everything a companion needs to draw itself. */
data class PetState(
    val growth: Float,
    val fruits: Int,
    val asleep: Boolean,
    val level: Int = 1,
    val mood: Float = 0.7f,
    /** 0..1 click/jump animation progress. */
    val jump: Float = 0f,
    /** Recent drift: a falling leaf / a wobble, shown softly. */
    val drifted: Boolean = false,
)

data class AvatarInfo(val id: String, val name: String, val blurb: String)

val AVATARS = listOf(
    AvatarInfo("tree", "Maram", "A tree that grows with every good block and fruits on perfect ones."),
    AvatarInfo("spirit", "Pulli", "A little cloud spirit that glows brighter as your day fills up."),
    AvatarInfo("hunter", "Hunter", "Levels up, bulks up and powers an aura as your rank climbs."),
)

/** Animated, clickable companion. Click → it jumps. */
@Composable
fun PetView(kind: String, state: PetState, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val transition = rememberInfiniteTransition()
    val t by transition.animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing), RepeatMode.Restart))
    val jump = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val click = if (interactive) Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
        scope.launch { jump.snapTo(0f); jump.animateTo(1f, tween(900, easing = LinearEasing)); jump.snapTo(0f) }
    } else Modifier
    Box(modifier.then(click)) {
        Canvas(Modifier.fillMaxSize()) { drawPet(kind, state.copy(jump = jump.value), t) }
    }
}

fun DrawScope.drawPet(kind: String, s: PetState, t: Float) = when (kind) {
    "spirit" -> drawSpirit(s, t)
    "hunter" -> drawHunter(s, t)
    else -> drawTree(s, t)
}

private fun bounce(j: Float): Float = if (j <= 0f) 0f else sin(j * PI.toFloat()) * (1f - j * 0.3f)

private fun DrawScope.drawZs(at: Offset, t: Float, scale: Float, color: Color) {
    for (i in 0 until 3) {
        val phase = ((t * 0.35f + i / 3f) % 1f)
        val sz = scale * (0.5f + i * 0.25f)
        val p = at + Offset(phase * scale * 2.2f, -phase * scale * 3.5f)
        val a = (1f - phase) * 0.9f
        val path = Path().apply {
            moveTo(p.x, p.y); lineTo(p.x + sz, p.y); lineTo(p.x, p.y + sz); lineTo(p.x + sz, p.y + sz)
        }
        drawPath(path, color.copy(alpha = a), style = Stroke(sz * 0.16f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.groundShadow(cx: Float, y: Float, w: Float, alpha: Float = 0.45f) {
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = alpha), Color.Transparent), Offset(cx, y), w / 2),
        topLeft = Offset(cx - w / 2, y - w * 0.09f), size = Size(w, w * 0.18f),
    )
}

// ---------------- Tree ----------------

private fun DrawScope.drawTree(s: PetState, t: Float) {
    val w = size.width; val h = size.height
    val cx = w / 2
    val groundY = h * 0.86f
    val g = s.growth.coerceIn(0f, 1f)
    val extra = (s.growth - 1f).coerceAtLeast(0f)
    val night = s.asleep
    val sway = sin(t * 1.3f) * (if (night) 0.6f else 2.2f) + bounce(s.jump) * sin(t * 40f) * 6f

    // Halo when the goal is reached.
    if (g >= 1f && !night) {
        val pulse = 0.85f + 0.15f * sin(t * 2f)
        drawCircle(
            Brush.radialGradient(listOf(Ink.mellow.copy(alpha = 0.22f * pulse), Color.Transparent), Offset(cx, h * 0.42f), w * 0.5f),
            radius = w * 0.5f, center = Offset(cx, h * 0.42f),
        )
    }
    groundShadow(cx, groundY + h * 0.02f, w * (0.45f + 0.3f * g))
    // Earth mound with a lit top.
    drawOval(
        Brush.verticalGradient(listOf(Color(0xFF5B4636), Color(0xFF2C2119)), groundY - h * 0.03f, groundY + h * 0.05f),
        topLeft = Offset(cx - w * 0.2f, groundY - h * 0.03f), size = Size(w * 0.4f, h * 0.08f),
    )

    val trunkH = h * (0.08f + 0.40f * g)
    val trunkW = w * (0.025f + 0.05f * g)
    val top = Offset(cx, groundY - trunkH)
    rotate(sway, pivot = Offset(cx, groundY)) {
        if (g < 0.08f) {
            // Sprout: two leaves.
            val stemTop = Offset(cx, groundY - h * 0.1f)
            drawLine(Color(0xFF8BC34A), Offset(cx, groundY), stemTop, strokeWidth = w * 0.015f, cap = StrokeCap.Round)
            leaf(stemTop, w * 0.07f, -35f, Color(0xFF9CCC65))
            leaf(stemTop, w * 0.07f, 215f, Color(0xFF8BC34A))
            return@rotate
        }
        val trunk = Path().apply {
            moveTo(cx - trunkW, groundY)
            cubicTo(cx - trunkW * 0.6f, groundY - trunkH * 0.5f, cx - trunkW * 0.3f, top.y + trunkH * 0.2f, cx - trunkW * 0.25f, top.y)
            lineTo(cx + trunkW * 0.25f, top.y)
            cubicTo(cx + trunkW * 0.3f, top.y + trunkH * 0.2f, cx + trunkW * 0.6f, groundY - trunkH * 0.5f, cx + trunkW, groundY)
            close()
        }
        drawPath(trunk, Brush.horizontalGradient(listOf(Color(0xFF8D6E52), Color(0xFF5D4433), Color(0xFF3B2A20)), cx - trunkW, cx + trunkW))
        if (g > 0.3f) {
            drawLine(Color(0xFF5D4433), Offset(cx, top.y + trunkH * 0.35f), Offset(cx + w * 0.12f * g, top.y + trunkH * 0.12f), strokeWidth = trunkW * 0.5f, cap = StrokeCap.Round)
            drawLine(Color(0xFF5D4433), Offset(cx, top.y + trunkH * 0.45f), Offset(cx - w * 0.11f * g, top.y + trunkH * 0.2f), strokeWidth = trunkW * 0.45f, cap = StrokeCap.Round)
        }
        // Canopy: lit spheres in a spiral, growing in number and size.
        val count = 3 + (g * 10).toInt()
        val crownR = w * (0.10f + 0.24f * g)
        val young = Color(0xFFB5D86F); val mature = Color(0xFF3E8E4E)
        var leafColor = lerp(young, mature, g)
        if (night) leafColor = lerp(leafColor, Color(0xFF2B3A55), 0.55f)
        val centers = (0 until count).map { i ->
            val a = i * 2.39996f
            val r = crownR * 0.75f * kotlin.math.sqrt((i + 0.5f) / count)
            Offset(top.x + cos(a) * r, top.y - crownR * 0.35f + sin(a) * r * 0.75f)
        }.sortedBy { it.y }
        val blobR = crownR * (0.42f + 0.1f * (1 - g))
        for ((i, c) in centers.withIndex()) {
            val wob = sin(t * 1.7f + i) * blobR * 0.03f
            val center = c + Offset(wob, 0f)
            drawCircle(
                Brush.radialGradient(
                    listOf(lerp(leafColor, Color.White, 0.28f), leafColor, lerp(leafColor, Color.Black, 0.45f)),
                    center = center + Offset(-blobR * 0.35f, -blobR * 0.4f), radius = blobR * 1.5f,
                ),
                radius = blobR, center = center,
            )
        }
        // Blossoms and fruit.
        if (g > 0.55f) {
            val blossoms = ((g - 0.55f) * 22).toInt()
            for (i in 0 until blossoms) {
                val c = centers[i % centers.size]
                val a = i * 1.7f
                val p = c + Offset(cos(a) * blobR * 0.6f, sin(a) * blobR * 0.5f)
                drawCircle(if (night) Color(0xFF8E9AB8) else Color(0xFFFFE4EC), radius = w * 0.008f, center = p)
            }
        }
        val fruitCount = min(s.fruits, 14)
        for (i in 0 until fruitCount) {
            val c = centers[(i * 3 + 1) % centers.size]
            val a = i * 2.1f + 0.5f
            val p = c + Offset(cos(a) * blobR * 0.55f, sin(a) * blobR * 0.45f + blobR * 0.2f)
            val fr = w * (0.018f + 0.006f * extra.coerceAtMost(1f))
            val base = if (i % 3 == 2) Color(0xFFE85D3F) else Color(0xFFFFC233)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, base, lerp(base, Color.Black, 0.4f)), p + Offset(-fr * 0.4f, -fr * 0.4f), fr * 1.6f),
                radius = fr, center = p,
            )
        }
    }
    // A leaf drifts down after a drifted block (gentle, not punishing).
    if (s.drifted && !night) {
        val ph = (t * 0.25f) % 1f
        leaf(Offset(cx + w * 0.18f + sin(ph * 9f) * w * 0.05f, top.y + (groundY - top.y) * ph), w * 0.035f, ph * 360f, Color(0xFFD9A441).copy(alpha = 1f - ph))
    }
    if (night) {
        val moon = Offset(w * 0.82f, h * 0.16f)
        drawCircle(Color(0xFFF6E7B0), radius = w * 0.055f, center = moon)
        drawCircle(Ink.bg, radius = w * 0.05f, center = moon + Offset(w * 0.025f, -w * 0.012f))
        drawZs(Offset(cx + w * 0.16f, top.y - h * 0.12f), t, w * 0.05f, Color(0xFFCFD8DC))
    }
    sparkles(s, t, Offset(cx, h * 0.4f), w * 0.4f)
}

private fun DrawScope.leaf(at: Offset, len: Float, angle: Float, color: Color) {
    rotate(angle, pivot = at) {
        val p = Path().apply {
            moveTo(at.x, at.y)
            quadraticTo(at.x + len * 0.5f, at.y - len * 0.45f, at.x + len, at.y)
            quadraticTo(at.x + len * 0.5f, at.y + len * 0.45f, at.x, at.y)
        }
        drawPath(p, color)
    }
}

/** Click celebration: a ring of sparkles that bursts outwards. */
private fun DrawScope.sparkles(s: PetState, t: Float, center: Offset, r: Float) {
    if (s.jump <= 0f) return
    val j = s.jump
    for (i in 0 until 10) {
        val a = i * (2 * PI.toFloat() / 10) + t
        val d = r * (0.4f + j * 0.8f)
        val p = center + Offset(cos(a) * d, sin(a) * d)
        drawCircle(lerp(Ink.mellow, Color.White, (i % 3) / 3f).copy(alpha = (1 - j)), radius = r * 0.03f * (1 - j * 0.5f), center = p)
    }
}

// ---------------- Spirit ----------------

private fun DrawScope.drawSpirit(s: PetState, t: Float) {
    val w = size.width; val h = size.height
    val g = s.growth.coerceIn(0f, 1f)
    val night = s.asleep
    val breathe = if (night) 1f + 0.03f * sin(t * 1.1f) else 1f + 0.02f * sin(t * 2.2f)
    val bob = if (night) 0f else sin(t * 2f) * h * 0.02f
    val jumpY = -bounce(s.jump) * h * 0.22f
    val squash = 1f + (if (s.jump in 0.85f..1f || s.jump in 0.001f..0.12f) 0.08f else 0f)
    val r = w * (0.2f + 0.1f * g) * breathe
    val c = Offset(w / 2, h * 0.56f + bob + jumpY + (if (night) h * 0.05f else 0f))

    groundShadow(w / 2, h * 0.88f, r * 2.2f * (1f - bounce(s.jump) * 0.4f))

    val warm = lerp(Color(0xFFBFD7EA), Color(0xFFFFE08A), g)
    val bodyColor = if (night) lerp(warm, Color(0xFF6C7A99), 0.55f) else warm
    // Glow
    drawCircle(
        Brush.radialGradient(listOf(bodyColor.copy(alpha = if (night) 0.12f else 0.18f + 0.22f * g), Color.Transparent), c, r * 2.4f),
        radius = r * 2.4f, center = c,
    )
    withTransform({ scale(squash, 2f - squash, pivot = Offset(c.x, c.y + r)) }) {
        val body = Path()
        val n = 48
        for (i in 0..n) {
            val a = i / n.toFloat() * 2 * PI.toFloat()
            val wob = 1f + 0.035f * sin(a * 5 + t * 1.6f) + 0.02f * sin(a * 3 - t)
            val rr = r * wob * (if (sin(a) > 0) 0.94f else 1.05f)
            val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
            if (i == 0) body.moveTo(p.x, p.y) else body.lineTo(p.x, p.y)
        }
        body.close()
        drawPath(
            body,
            Brush.radialGradient(listOf(lerp(bodyColor, Color.White, 0.6f), bodyColor, lerp(bodyColor, Color(0xFF3A3F55), 0.55f)), c + Offset(-r * 0.35f, -r * 0.45f), r * 1.6f),
        )
        // Specular highlight for a soft 3D read.
        drawOval(Color.White.copy(alpha = 0.35f), topLeft = c + Offset(-r * 0.55f, -r * 0.7f), size = Size(r * 0.45f, r * 0.25f))

        val eyeY = c.y - r * 0.05f
        val eyeDx = r * 0.32f
        val blink = (t % 4.2f) < 0.13f
        val eyeColor = Color(0xFF2A2D3A)
        if (night || blink) {
            for (sx in listOf(-1f, 1f)) {
                val e = Offset(c.x + sx * eyeDx, eyeY)
                drawArc(eyeColor, 0f, 180f, false, topLeft = e + Offset(-r * 0.1f, -r * 0.06f), size = Size(r * 0.2f, r * 0.12f), style = Stroke(r * 0.045f, cap = StrokeCap.Round))
            }
        } else {
            for (sx in listOf(-1f, 1f)) {
                val e = Offset(c.x + sx * eyeDx, eyeY)
                drawOval(eyeColor, topLeft = e + Offset(-r * 0.075f, -r * 0.11f), size = Size(r * 0.15f, r * 0.22f))
                drawCircle(Color.White, radius = r * 0.035f, center = e + Offset(r * 0.025f, -r * 0.05f))
            }
        }
        if (s.mood > 0.5f && !night) {
            for (sx in listOf(-1f, 1f)) {
                drawOval(Color(0xFFFF8A80).copy(alpha = 0.45f), topLeft = Offset(c.x + sx * r * 0.55f - r * 0.12f, eyeY + r * 0.16f), size = Size(r * 0.24f, r * 0.12f))
            }
        }
        val smile = (0.08f + 0.14f * s.mood) * r
        if (!night) drawArc(eyeColor, 20f, 140f, false, topLeft = Offset(c.x - smile, eyeY + r * 0.1f), size = Size(smile * 2, smile * 1.2f), style = Stroke(r * 0.04f, cap = StrokeCap.Round))
    }
    // Orbiting fruit-lights for perfect blocks.
    val lights = min(s.fruits, 12)
    for (i in 0 until lights) {
        val a = t * 0.8f + i * (2 * PI.toFloat() / lights)
        val p = c + Offset(cos(a) * r * 1.55f, sin(a) * r * 0.45f - r * 0.2f)
        val front = sin(a) > 0
        drawCircle(Color(0xFFFFE082).copy(alpha = if (front) 0.95f else 0.45f), radius = w * (if (front) 0.016f else 0.011f), center = p)
    }
    if (night) drawZs(c + Offset(r * 0.7f, -r * 1.1f), t, w * 0.05f, Color(0xFFCFD8DC))
    sparkles(s, t, c, r * 2)
}

// ---------------- Hunter ----------------

private fun rankColor(level: Int): Color = when (rankFor(level)) {
    "S" -> Color(0xFFFFD54F)
    "A" -> Color(0xFFFF5252)
    "B" -> Color(0xFFB388FF)
    "C" -> Color(0xFF40C4FF)
    "D" -> Color(0xFF69F0AE)
    else -> Color(0xFFB0BEC5)
}

private fun DrawScope.drawHunter(s: PetState, t: Float) {
    val w = size.width; val h = size.height
    val g = s.growth.coerceIn(0f, 1f)
    val night = s.asleep
    val muscle = ((s.level - 1) / 30f).coerceIn(0f, 1f) * 0.7f + g * 0.3f
    val aura = rankColor(s.level)
    val cx = w / 2
    val jumpY = -bounce(s.jump) * h * 0.15f
    val baseY = h * 0.9f
    val power = 0.25f + 0.75f * g

    groundShadow(cx, baseY, w * 0.42f)

    if (!night) {
        // Soft glow behind the body.
        drawCircle(
            Brush.radialGradient(listOf(aura.copy(alpha = 0.10f + 0.22f * power), Color.Transparent), Offset(cx, h * 0.5f + jumpY), w * 0.45f),
            radius = w * 0.45f, center = Offset(cx, h * 0.5f + jumpY),
        )
        // Flame aura hugging the silhouette: tall in the middle, tapering to the sides.
        for (layer in 0 until 3) {
            val lw = w * (0.2f + 0.05f * layer + 0.05f * muscle)
            val top = h * (0.16f - 0.08f * power) + layer * h * 0.05f
            val path = Path()
            val n = 36
            path.moveTo(cx - lw, baseY)
            for (i in 0..n) {
                val u = i / n.toFloat()
                val x = cx - lw + 2 * lw * u
                val profile = sin(u * PI.toFloat())
                val tongue = (0.55f + 0.45f * sin(i * 2.1f + t * (4f + layer))) * profile
                val flick = sin(t * (6f + layer) + i * 1.7f) * h * 0.025f * power
                val y = baseY - (baseY - top) * (profile * 0.75f + tongue * 0.25f) + flick
                path.lineTo(x, y + jumpY)
            }
            path.lineTo(cx + lw, baseY)
            path.close()
            drawPath(path, Brush.verticalGradient(listOf(aura.copy(alpha = 0.0f), aura.copy(alpha = (0.32f - layer * 0.08f) * power + 0.04f), aura.copy(alpha = 0.05f)), top, baseY))
        }
        val embers = (4 + g * 12).toInt()
        for (i in 0 until embers) {
            val ph = (t * 0.35f + i * 0.137f) % 1f
            val x = cx + sin(i * 12.9f + ph * 3f) * w * 0.22f
            val y = baseY - ph * h * 0.85f
            drawCircle(aura.copy(alpha = (1 - ph) * 0.85f), radius = w * 0.007f, center = Offset(x, y + jumpY))
        }
    }

    translate(top = jumpY) {
        val skin = if (night) Color(0xFF323A4E) else Color(0xFF2A2F3D)
        val light = lerp(skin, if (night) Color(0xFF8090B0) else aura, 0.28f)
        val dark = lerp(skin, Color.Black, 0.35f)
        val rim = aura.copy(alpha = if (night) 0.15f else 0.75f)
        val stroke = Stroke(w * 0.005f)
        val breathe = sin(t * (if (night) 1.1f else 2f)) * h * 0.004f

        val headR = w * 0.072f
        val neckY = h * 0.34f
        val shoulderY = neckY + h * 0.03f + breathe
        val hipY = h * 0.6f
        val shoulder = w * (0.12f + 0.07f * muscle)
        val waist = w * (0.075f + 0.02f * muscle)
        val headC = Offset(cx, neckY - headR * 0.95f + (if (night) h * 0.012f else 0f))
        val flex = !night && g > 0.35f
        val armW = w * (0.026f + 0.016f * muscle)

        // Legs: tapered thighs into shins.
        for (sx in listOf(-1f, 1f)) {
            val hip = Offset(cx + sx * waist * 0.55f, hipY)
            val knee = Offset(cx + sx * waist * 0.8f, (hipY + baseY) / 2 + h * 0.01f)
            val foot = Offset(cx + sx * waist * (if (night) 0.8f else 1.05f), baseY - h * 0.012f)
            drawLine(dark, hip, knee, strokeWidth = armW * 2.3f, cap = StrokeCap.Round)
            drawLine(dark, knee, foot, strokeWidth = armW * 1.7f, cap = StrokeCap.Round)
            drawOval(dark, topLeft = foot + Offset(-armW * 1.2f + sx * armW * 0.4f, -armW * 0.5f), size = Size(armW * 2.4f, armW * 1.1f))
        }
        // Shorts.
        val shorts = Path().apply {
            moveTo(cx - waist * 1.05f, hipY - h * 0.02f); lineTo(cx + waist * 1.05f, hipY - h * 0.02f)
            lineTo(cx + waist * 1.2f, hipY + h * 0.07f); lineTo(cx + waist * 0.15f, hipY + h * 0.07f)
            lineTo(cx, hipY + h * 0.03f); lineTo(cx - waist * 0.15f, hipY + h * 0.07f); lineTo(cx - waist * 1.2f, hipY + h * 0.07f); close()
        }
        drawPath(shorts, Color(0xFF15171F))

        // Arm geometry: double-biceps when powered, relaxed otherwise.
        val arms = listOf(-1f, 1f).map { sx ->
            val sh = Offset(cx + sx * shoulder * 0.92f, shoulderY + h * 0.01f)
            if (flex) {
                val elbow = sh + Offset(sx * w * 0.13f, -h * 0.015f)
                Triple(sh, elbow, elbow + Offset(-sx * w * 0.015f, -h * 0.11f + sin(t * 3f) * h * 0.004f))
            } else {
                val elbow = sh + Offset(sx * w * 0.035f, h * 0.12f)
                Triple(sh, elbow, elbow + Offset(sx * w * 0.005f, h * 0.11f))
            }
        }
        for ((sh, elbow, _) in arms) drawLine(skin, sh, elbow, strokeWidth = armW * 2.1f, cap = StrokeCap.Round)

        // Torso: rounded V.
        val torso = Path().apply {
            moveTo(cx - shoulder, shoulderY)
            cubicTo(cx - shoulder * 0.4f, shoulderY - h * 0.025f, cx + shoulder * 0.4f, shoulderY - h * 0.025f, cx + shoulder, shoulderY)
            cubicTo(cx + shoulder * 1.02f, shoulderY + h * 0.09f, cx + waist * 1.1f, hipY - h * 0.07f, cx + waist, hipY)
            lineTo(cx - waist, hipY)
            cubicTo(cx - waist * 1.1f, hipY - h * 0.07f, cx - shoulder * 1.02f, shoulderY + h * 0.09f, cx - shoulder, shoulderY)
            close()
        }
        drawPath(torso, Brush.horizontalGradient(listOf(light, skin, dark), cx - shoulder, cx + shoulder))
        drawPath(torso, rim, style = stroke)
        // Pecs and abs show up as muscle grows.
        val detail = rim.copy(alpha = rim.alpha * (0.2f + 0.5f * muscle))
        drawArc(detail, 10f, 160f, false, Offset(cx - shoulder * 0.78f, shoulderY), Size(shoulder * 0.76f, h * 0.075f), style = stroke)
        drawArc(detail, 10f, 160f, false, Offset(cx + shoulder * 0.02f, shoulderY), Size(shoulder * 0.76f, h * 0.075f), style = stroke)
        if (muscle > 0.3f) {
            drawLine(detail, Offset(cx, shoulderY + h * 0.08f), Offset(cx, hipY - h * 0.03f), strokeWidth = w * 0.004f)
            for (k in 1..2) {
                val y = shoulderY + h * (0.08f + 0.045f * k)
                drawLine(detail, Offset(cx - waist * 0.7f, y), Offset(cx + waist * 0.7f, y), strokeWidth = w * 0.003f)
            }
        }
        // Shoulders, biceps, forearms, fists.
        for ((sh, elbow, hand) in arms) {
            drawCircle(Brush.radialGradient(listOf(light, skin), sh + Offset(-armW * 0.4f, -armW * 0.5f), armW * 2f), radius = armW * (1.25f + 0.5f * muscle), center = sh)
            val mid = (sh + elbow) / 2f
            if (muscle > 0.1f) {
                val bulge = armW * (0.9f + 0.9f * muscle) * (if (flex) 1.15f else 0.9f)
                val bc = mid + Offset(0f, if (flex) -bulge * 0.35f else 0f)
                drawCircle(Brush.radialGradient(listOf(light, skin), bc + Offset(0f, -bulge * 0.6f), bulge * 1.8f), radius = bulge, center = bc)
            }
            drawLine(skin, elbow, hand, strokeWidth = armW * 1.75f, cap = StrokeCap.Round)
            drawCircle(light, radius = armW * 1.15f, center = hand)
            if (!night) drawCircle(rim, radius = armW * 1.15f, center = hand, style = stroke)
        }
        // Neck and head.
        drawLine(skin, Offset(cx, neckY - headR * 0.2f), Offset(cx, shoulderY), strokeWidth = headR * 0.8f, cap = StrokeCap.Round)
        drawCircle(Brush.radialGradient(listOf(lerp(skin, Color.White, 0.14f), skin), headC + Offset(-headR * 0.35f, -headR * 0.35f), headR * 1.6f), radius = headR, center = headC)
        drawCircle(rim, radius = headR, center = headC, style = stroke)
        val hair = Path().apply {
            moveTo(headC.x - headR * 1.05f, headC.y)
            val spikes = 6
            for (i in 0..spikes) {
                val u = i / spikes.toFloat()
                val x = headC.x - headR * 1.05f + headR * 2.1f * u
                val lift = headR * (1.25f + 0.25f * sin(i * 1.9f)) * sin(u * PI.toFloat()).coerceAtLeast(0.45f)
                lineTo(x - headR * 0.12f, headC.y - lift)
                lineTo(x + headR * 0.2f, headC.y - headR * 0.55f)
            }
            lineTo(headC.x + headR * 1.05f, headC.y)
            quadraticTo(headC.x, headC.y - headR * 0.45f, headC.x - headR * 1.05f, headC.y)
        }
        drawPath(hair, Color(0xFF101219))
        val eyeY = headC.y + headR * 0.12f
        if (night) {
            for (sx in listOf(-1f, 1f)) drawArc(Color(0xFF9AA3B5), 0f, 180f, false, Offset(headC.x + sx * headR * 0.42f - headR * 0.16f, eyeY - headR * 0.08f), Size(headR * 0.32f, headR * 0.18f), style = Stroke(w * 0.006f, cap = StrokeCap.Round))
        } else {
            val glow = s.level >= 10
            for (sx in listOf(-1f, 1f)) {
                val e = Offset(headC.x + sx * headR * 0.42f, eyeY)
                if (glow) drawCircle(aura.copy(alpha = 0.4f), radius = headR * 0.3f, center = e)
                drawLine(if (glow) Color.White else Color(0xFFDDE3EE), e - Offset(headR * 0.15f, -sx * headR * 0.03f), e + Offset(headR * 0.15f, sx * headR * 0.03f), strokeWidth = w * 0.01f, cap = StrokeCap.Round)
            }
        }
    }
    if (night) drawZs(Offset(cx + w * 0.12f, h * 0.18f), t, w * 0.05f, Color(0xFFCFD8DC))
    sparkles(s, t, Offset(cx, h * 0.45f), w * 0.4f)
}

// ---------------- Artifacts ----------------

fun DrawScope.drawArtifact(a: Artifact, owned: Boolean, t: Float = 0f) {
    val w = size.width; val h = size.height
    val c = Offset(w / 2, h / 2)
    val r = min(w, h) * 0.34f
    val base = if (owned) a.color.asColor() else Color(0xFF2A2D36)
    val rarity = RARITY_COLORS[a.rarity].asColor()
    if (owned && a.rarity >= 2) {
        drawCircle(Brush.radialGradient(listOf(rarity.copy(alpha = 0.3f + 0.08f * sin(t * 2)), Color.Transparent), c, r * 1.6f), radius = r * 1.6f, center = c)
    }
    val lit = Brush.radialGradient(listOf(lerp(base, Color.White, 0.55f), base, lerp(base, Color.Black, 0.5f)), c + Offset(-r * 0.35f, -r * 0.4f), r * 1.6f)
    when (a.shape) {
        Shape.Orb -> drawCircle(lit, radius = r * 0.8f, center = c)
        Shape.Egg -> drawOval(lit, topLeft = c - Offset(r * 0.65f, r * 0.9f), size = Size(r * 1.3f, r * 1.75f))
        Shape.Coin -> {
            drawCircle(lit, radius = r * 0.85f, center = c)
            drawCircle(lerp(base, Color.Black, 0.35f), radius = r * 0.6f, center = c, style = Stroke(r * 0.08f))
            drawRect(lerp(base, Color.Black, 0.45f), topLeft = c - Offset(r * 0.15f, r * 0.15f), size = Size(r * 0.3f, r * 0.3f))
        }
        Shape.Gem, Shape.Crystal -> {
            val tall = a.shape == Shape.Crystal
            val p = Path().apply {
                if (tall) {
                    moveTo(c.x, c.y - r); lineTo(c.x + r * 0.45f, c.y - r * 0.3f); lineTo(c.x + r * 0.3f, c.y + r * 0.9f)
                    lineTo(c.x - r * 0.3f, c.y + r * 0.9f); lineTo(c.x - r * 0.45f, c.y - r * 0.3f)
                } else {
                    moveTo(c.x - r * 0.5f, c.y - r * 0.55f); lineTo(c.x + r * 0.5f, c.y - r * 0.55f); lineTo(c.x + r * 0.9f, c.y - r * 0.1f)
                    lineTo(c.x, c.y + r * 0.85f); lineTo(c.x - r * 0.9f, c.y - r * 0.1f)
                }
                close()
            }
            drawPath(p, lit)
            drawPath(p, Color.White.copy(alpha = if (owned) 0.35f else 0.08f), style = Stroke(r * 0.04f))
            drawLine(Color.White.copy(alpha = if (owned) 0.3f else 0.06f), c + Offset(-r * 0.9f, -r * 0.1f), c + Offset(r * 0.9f, -r * 0.1f), strokeWidth = r * 0.03f)
        }
        Shape.Leaf -> {
            val p = Path().apply {
                moveTo(c.x - r * 0.8f, c.y + r * 0.6f)
                quadraticTo(c.x - r * 0.6f, c.y - r * 0.9f, c.x + r * 0.8f, c.y - r * 0.7f)
                quadraticTo(c.x + r * 0.5f, c.y + r * 0.8f, c.x - r * 0.8f, c.y + r * 0.6f)
            }
            drawPath(p, lit)
            drawLine(lerp(base, Color.Black, 0.4f), c + Offset(-r * 0.8f, r * 0.6f), c + Offset(r * 0.6f, -r * 0.55f), strokeWidth = r * 0.05f)
        }
        Shape.Feather -> {
            rotate(-30f, c) {
                drawOval(lit, topLeft = c - Offset(r * 0.32f, r * 0.95f), size = Size(r * 0.64f, r * 1.9f))
                drawLine(lerp(base, Color.White, 0.6f), c + Offset(0f, -r * 0.95f), c + Offset(0f, r * 1.1f), strokeWidth = r * 0.05f)
                if (owned) drawCircle(Color(0xFF1A237E).copy(alpha = 0.6f), radius = r * 0.13f, center = c + Offset(0f, -r * 0.45f))
            }
        }
        Shape.Star -> {
            val p = Path()
            for (i in 0 until 10) {
                val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
                val rr = if (i % 2 == 0) r * 0.95f else r * 0.42f
                val pt = c + Offset(cos(a) * rr, sin(a) * rr)
                if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
            }
            p.close()
            drawPath(p, lit)
        }
        Shape.Lamp -> {
            drawOval(lit, topLeft = c + Offset(-r * 0.8f, r * 0.05f), size = Size(r * 1.6f, r * 0.55f))
            drawRect(lit, topLeft = c + Offset(-r * 0.1f, r * 0.3f), size = Size(r * 0.2f, r * 0.5f))
            drawOval(lit, topLeft = c + Offset(-r * 0.5f, r * 0.75f), size = Size(r, r * 0.25f))
            if (owned) {
                val flick = 1f + 0.12f * sin(t * 9f)
                drawOval(Brush.radialGradient(listOf(Color.White, Color(0xFFFFC107), Color.Transparent), c + Offset(0f, -r * 0.3f), r * 0.6f * flick), topLeft = c + Offset(-r * 0.2f, -r * 0.75f * flick), size = Size(r * 0.4f, r * 0.8f * flick))
            }
        }
        Shape.Shell -> {
            val p = Path().apply {
                moveTo(c.x, c.y + r * 0.8f)
                cubicTo(c.x - r * 1.1f, c.y + r * 0.2f, c.x - r * 0.7f, c.y - r * 0.9f, c.x, c.y - r * 0.8f)
                cubicTo(c.x + r * 0.7f, c.y - r * 0.9f, c.x + r * 1.1f, c.y + r * 0.2f, c.x, c.y + r * 0.8f)
            }
            drawPath(p, lit)
            for (i in -2..2) drawLine(lerp(base, Color.Black, 0.3f), c + Offset(0f, r * 0.75f), c + Offset(i * r * 0.32f, -r * 0.65f), strokeWidth = r * 0.04f)
        }
    }
    if (!owned) {
        drawCircle(Color(0xFF5C5F69), radius = r * 0.12f, center = c)
    }
}
