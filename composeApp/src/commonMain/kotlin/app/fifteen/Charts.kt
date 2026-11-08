package app.fifteen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private val axisStyle = TextStyle(color = Ink.faint, fontSize = 10.sp)
private val tipStyle = TextStyle(color = Ink.text, fontSize = 11.sp)

/** Hover (desktop) or touch (phone) index inside an evenly split chart. */
private fun Modifier.hoverIndex(count: Int, onIndex: (Int?) -> Unit) = pointerInput(count) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            val p = e.changes.firstOrNull() ?: continue
            if (e.type == PointerEventType.Exit) onIndex(null)
            else if (count > 0) onIndex((p.position.x / size.width * count).toInt().coerceIn(0, count - 1))
        }
    }
}

/** Bars with an optional dashed target line; hover shows the value. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    target: Float? = null,
    format: (Float) -> String = { it.toInt().toString() },
    labelEvery: Int = 1,
) {
    val tm = rememberTextMeasurer()
    var hover by remember { mutableStateOf<Int?>(null) }
    Canvas(modifier.fillMaxWidth().height(height).hoverIndex(values.size) { hover = it }) {
        val bottomPad = 18f * density / 1.5f + 8f
        val chartH = size.height - bottomPad - 14f
        val maxV = max(values.maxOrNull() ?: 0f, target ?: 0f).coerceAtLeast(1f) * 1.1f
        val slot = size.width / values.size.coerceAtLeast(1)
        val bw = (slot * 0.62f).coerceAtMost(28f * density)
        for (i in values.indices) {
            val v = values[i]
            val h = chartH * (v / maxV)
            val x = i * slot + (slot - bw) / 2
            val top = 14f + chartH - h
            drawRoundRect(Color(0x0CFFFFFF), Offset(x, 14f), Size(bw, chartH), CornerRadius(bw / 3))
            if (v > 0) {
                val c = colors.getOrElse(i) { Ink.mellow }
                drawRoundRect(
                    Brush.verticalGradient(listOf(c, c.copy(alpha = 0.55f)), top, top + h),
                    Offset(x, top), Size(bw, h), CornerRadius(bw / 3),
                    alpha = if (hover == null || hover == i) 1f else 0.45f,
                )
            }
            if (i % labelEvery == 0 && i < labels.size) {
                val l = tm.measure(labels[i], axisStyle)
                drawText(l, topLeft = Offset(i * slot + slot / 2 - l.size.width / 2, size.height - l.size.height.toFloat()))
            }
        }
        target?.let { t ->
            val y = 14f + chartH - chartH * (t / maxV)
            drawLine(Ink.mellow.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        hover?.let { i ->
            if (i in values.indices) {
                val txt = "${labels.getOrElse(i) { "" }}  ${format(values[i])}"
                val l = tm.measure(txt, tipStyle)
                val x = (i * slot + slot / 2 - l.size.width / 2).coerceIn(0f, size.width - l.size.width - 12f)
                drawRoundRect(Ink.surface2, Offset(x - 6f, 0f), Size(l.size.width + 12f, l.size.height + 6f), CornerRadius(8f))
                drawText(l, topLeft = Offset(x, 3f))
            }
        }
    }
}

/** Smooth line of focus % (nulls are gaps) with a softer moving average. */
@Composable
fun LineChart(values: List<Double?>, labels: List<String>, modifier: Modifier = Modifier, height: Dp = 170.dp, labelEvery: Int = 1, color: Color = Ink.good) {
    val tm = rememberTextMeasurer()
    var hover by remember { mutableStateOf<Int?>(null) }
    Canvas(modifier.fillMaxWidth().height(height).hoverIndex(values.size) { hover = it }) {
        val bottom = size.height - 22f
        val top = 16f
        fun y(v: Double) = (bottom - (bottom - top) * (v / 100.0)).toFloat()
        val n = values.size
        val step = if (n <= 1) size.width else size.width / (n - 1)
        for (g in listOf(50, 75, 100)) {
            drawLine(Color(0x12FFFFFF), Offset(0f, y(g.toDouble())), Offset(size.width, y(g.toDouble())), strokeWidth = 1f)
            val l = tm.measure("$g%", axisStyle)
            drawText(l, topLeft = Offset(size.width - l.size.width, y(g.toDouble()) - l.size.height))
        }
        // Moving average (7 points) behind.
        val avg = values.indices.map { i ->
            val window = values.subList(max(0, i - 6), i + 1).filterNotNull()
            if (window.isEmpty()) null else window.average()
        }
        fun pathOf(vs: List<Double?>): Path {
            val p = Path()
            var started = false
            vs.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val pt = Offset(i * step, y(v))
                if (!started) { p.moveTo(pt.x, pt.y); started = true } else p.lineTo(pt.x, pt.y)
            }
            return p
        }
        if (n > 7) drawPath(pathOf(avg), color.copy(alpha = 0.3f), style = Stroke(6f, cap = StrokeCap.Round))
        drawPath(pathOf(values), color, style = Stroke(2.5f, cap = StrokeCap.Round))
        values.forEachIndexed { i, v ->
            if (v != null) drawCircle(if (hover == i) Color.White else lerp(Ink.coral, Ink.good, (v / 100.0).toFloat()), radius = if (hover == i) 6f else 3.5f, center = Offset(i * step, y(v)))
            if (i % labelEvery == 0 && i < labels.size) {
                val l = tm.measure(labels[i], axisStyle)
                drawText(l, topLeft = Offset((i * step - l.size.width / 2).coerceIn(0f, size.width - l.size.width), size.height - l.size.height))
            }
        }
        hover?.let { i ->
            val v = values.getOrNull(i) ?: return@let
            val l = tm.measure("${labels.getOrElse(i) { "" }}  ${v.toInt()}% focus", tipStyle)
            val x = (i * step - l.size.width / 2).coerceIn(0f, size.width - l.size.width - 12f)
            drawRoundRect(Ink.surface2, Offset(x - 6f, 0f), Size(l.size.width + 12f, l.size.height + 6f), CornerRadius(8f))
            drawText(l, topLeft = Offset(x, 3f))
        }
    }
}

/** Ring of category shares. */
@Composable
fun Donut(parts: List<Pair<Color, Float>>, modifier: Modifier = Modifier, centerText: String = "", centerSub: String = "") {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val total = parts.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val stroke = size.minDimension * 0.11f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        var start = -90f
        if (parts.isEmpty()) drawArc(Color(0x14FFFFFF), 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
        for ((c, v) in parts) {
            val sweep = 360f * v / total
            drawArc(c, start + 1f, (sweep - 2f).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Butt))
            start += sweep
        }
        val big = tm.measure(centerText, TextStyle(color = Ink.text, fontSize = 22.sp))
        val small = tm.measure(centerSub, axisStyle)
        drawText(big, topLeft = Offset(size.width / 2 - big.size.width / 2, size.height / 2 - big.size.height / 2 - 6f))
        drawText(small, topLeft = Offset(size.width / 2 - small.size.width / 2, size.height / 2 + big.size.height / 2 - 6f))
    }
}

/** Weekday × hour grid of average focus. */
@Composable
fun HeatGrid(heat: List<List<Double?>>, counts: List<List<Int>>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    var hover by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Canvas(
        modifier.fillMaxWidth().height(200.dp).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull()?.position ?: continue
                    if (e.type == PointerEventType.Exit) { hover = null; continue }
                    val left = 36f
                    val cw = (size.width - left) / 24f
                    val ch = (size.height - 20f) / 7f
                    val h = ((p.x - left) / cw).toInt()
                    val w = (p.y / ch).toInt()
                    hover = if (h in 0..23 && w in 0..6) w to h else null
                }
            }
        },
    ) {
        val left = 36f
        val cw = (size.width - left) / 24f
        val ch = (size.height - 20f) / 7f
        for (w in 0..6) {
            val l = tm.measure(weekdayName(w), axisStyle)
            drawText(l, topLeft = Offset(0f, w * ch + ch / 2 - l.size.height / 2))
            for (h in 0..23) {
                val v = heat[w][h]
                val c = if (v == null) Color(0x0AFFFFFF) else lerp(Ink.coral, Ink.good, (v / 100.0).toFloat()).copy(alpha = 0.35f + 0.65f * (counts[w][h].coerceAtMost(6) / 6f))
                drawRoundRect(c, Offset(left + h * cw + 1.5f, w * ch + 1.5f), Size(cw - 3f, ch - 3f), CornerRadius(4f))
            }
        }
        for (h in listOf(0, 6, 12, 18)) {
            val l = tm.measure(hourName(h), axisStyle)
            drawText(l, topLeft = Offset(left + h * cw, size.height - l.size.height))
        }
        hover?.let { (w, h) ->
            val v = heat[w][h] ?: return@let
            val l = tm.measure("${weekdayName(w)} ${hourName(h)} · ${v.toInt()}% avg · ${counts[w][h]} blocks", tipStyle)
            val x = (left + h * cw).coerceAtMost(size.width - l.size.width - 12f)
            val y = (w * ch - l.size.height - 8f).coerceAtLeast(0f)
            drawRoundRect(Ink.surface2, Offset(x - 6f, y), Size(l.size.width + 12f, l.size.height + 6f), CornerRadius(8f))
            drawText(l, topLeft = Offset(x, y + 3f))
        }
    }
}

/** GitHub-style year of points, columns are weeks. */
@Composable
fun CalendarHeat(pointsByDay: Map<Int, Int>, today: Int, goal: Int, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    var hover by remember { mutableStateOf<Int?>(null) }
    val weeks = 53
    val lastMonday = today - weekdayOf(today)
    val firstDay = lastMonday - (weeks - 1) * 7
    Canvas(
        modifier.fillMaxWidth().height(130.dp).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull()?.position ?: continue
                    if (e.type == PointerEventType.Exit) { hover = null; continue }
                    val cell = size.width / weeks.toFloat()
                    val col = (p.x / cell).toInt(); val row = ((p.y - 16f) / cell).toInt()
                    hover = if (col in 0 until weeks && row in 0..6) firstDay + col * 7 + row else null
                }
            }
        },
    ) {
        val cell = size.width / weeks
        var lastMonth = ""
        for (col in 0 until weeks) {
            val m = formatDay(firstDay + col * 7, "MMM")
            if (m != lastMonth) {
                drawText(tm.measure(m, axisStyle), topLeft = Offset(col * cell, 0f))
                lastMonth = m
            }
            for (row in 0..6) {
                val day = firstDay + col * 7 + row
                if (day > today) continue
                val pts = pointsByDay[day] ?: 0
                val f = (pts.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1.2f)
                val c = if (pts == 0) Color(0x0DFFFFFF) else lerp(Color(0xFF3B3520), Ink.mellow, (f / 1.2f))
                drawRoundRect(c, Offset(col * cell + 1f, 16f + row * cell + 1f), Size(cell - 2f, cell - 2f), CornerRadius(cell * 0.22f))
                if (day == today) drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(col * cell + 1f, 16f + row * cell + 1f), Size(cell - 2f, cell - 2f), CornerRadius(cell * 0.22f), style = Stroke(1.2f))
            }
        }
        hover?.let { d ->
            if (d > today) return@let
            val l = tm.measure("${formatDay(d, "EEE d MMM")} · ${pointsByDay[d] ?: 0} pts", tipStyle)
            val col = (d - firstDay) / 7
            val x = (col * cell).coerceAtMost(size.width - l.size.width - 12f)
            drawRoundRect(Ink.surface2, Offset(x - 6f, 0f), Size(l.size.width + 12f, l.size.height + 6f), CornerRadius(8f))
            drawText(l, topLeft = Offset(x, 3f))
        }
    }
}
