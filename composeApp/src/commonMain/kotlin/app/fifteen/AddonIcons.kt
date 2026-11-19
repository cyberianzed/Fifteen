package app.fifteen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

// ---------- Add-on icons, drawn like the pets: soft rounded shapes, a darker shade on one side, a small highlight ----------

/** The little picture beside each add-on: "eye", "water", "well", "rest" (back-to-it nudge, a coffee cup) or "countdown". */
@Composable
fun AddonIcon(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (kind) {
            "eye" -> cucumber()
            "water" -> drop()
            "well" -> buddy()
            "rest" -> coffee()
            "countdown" -> hourglass()
        }
    }
}

private fun DrawScope.p(x: Float, y: Float) = Offset(size.width * x, size.height * y)
private fun DrawScope.w(x: Float) = size.width * x

private fun DrawScope.eye() {
    drawOval(Color(0xFFE9ECEF), p(0.08f, 0.24f), Size(w(0.84f), w(0.52f)))
    drawOval(Color(0xFFC9D0D5), p(0.14f, 0.5f), Size(w(0.72f), w(0.22f)))
    drawCircle(Color(0xFF1D9E75), w(0.2f), p(0.5f, 0.49f))
    drawCircle(Color(0xFF0F6E56), w(0.2f), p(0.5f, 0.55f), alpha = 0.45f)
    drawCircle(Color(0xFF04342C), w(0.095f), p(0.5f, 0.5f))
    drawCircle(Color.White, w(0.045f), p(0.57f, 0.42f))
}

private fun DrawScope.drop() {
    val body = Path().apply {
        moveTo(w(0.5f), w(0.06f))
        cubicTo(w(0.66f), w(0.3f), w(0.84f), w(0.48f), w(0.84f), w(0.64f))
        cubicTo(w(0.84f), w(0.84f), w(0.68f), w(0.96f), w(0.5f), w(0.96f))
        cubicTo(w(0.32f), w(0.96f), w(0.16f), w(0.84f), w(0.16f), w(0.64f))
        cubicTo(w(0.16f), w(0.48f), w(0.34f), w(0.3f), w(0.5f), w(0.06f))
        close()
    }
    drawPath(body, Color(0xFF378ADD))
    val shade = Path().apply {
        moveTo(w(0.6f), w(0.3f))
        cubicTo(w(0.74f), w(0.48f), w(0.84f), w(0.56f), w(0.84f), w(0.66f))
        cubicTo(w(0.84f), w(0.84f), w(0.68f), w(0.96f), w(0.5f), w(0.96f))
        cubicTo(w(0.66f), w(0.84f), w(0.72f), w(0.6f), w(0.6f), w(0.3f))
        close()
    }
    drawPath(shade, Color(0xFF185FA5))
    drawOval(Color(0xFFB5D4F4), p(0.3f, 0.52f), Size(w(0.1f), w(0.2f)))
}

private fun DrawScope.buddy() {
    drawCircle(Color(0xFFED93B1), w(0.4f), p(0.5f, 0.52f))
    val shade = Path().apply {
        moveTo(w(0.9f), w(0.52f))
        cubicTo(w(0.9f), w(0.78f), w(0.7f), w(0.92f), w(0.5f), w(0.92f))
        cubicTo(w(0.36f), w(0.92f), w(0.24f), w(0.86f), w(0.17f), w(0.76f))
        cubicTo(w(0.4f), w(0.86f), w(0.82f), w(0.8f), w(0.9f), w(0.52f))
        close()
    }
    drawPath(shade, Color(0xFFD4537E))
    drawCircle(Color(0xFF4B1528), w(0.045f), p(0.37f, 0.46f))
    drawCircle(Color(0xFF4B1528), w(0.045f), p(0.63f, 0.46f))
    drawArc(Color(0xFF4B1528), 20f, 140f, false, p(0.38f, 0.48f), Size(w(0.24f), w(0.16f)), style = Stroke(w(0.045f), cap = StrokeCap.Round))
    drawCircle(Color(0xFFF4C0D1), w(0.06f), p(0.27f, 0.58f))
    drawCircle(Color(0xFFF4C0D1), w(0.06f), p(0.73f, 0.58f))
    drawCircle(Color.White, w(0.05f), p(0.34f, 0.27f), alpha = 0.55f)
}

private fun DrawScope.sprout() {
    // A little pot of soil with a sprout coming up: getting back to it.
    val pot = Path().apply {
        moveTo(w(0.22f), w(0.64f)); lineTo(w(0.78f), w(0.64f)); lineTo(w(0.7f), w(0.94f)); lineTo(w(0.3f), w(0.94f)); close()
    }
    drawPath(pot, Color(0xFFC0773A))
    val potShade = Path().apply {
        moveTo(w(0.56f), w(0.64f)); lineTo(w(0.78f), w(0.64f)); lineTo(w(0.7f), w(0.94f)); lineTo(w(0.52f), w(0.94f)); close()
    }
    drawPath(potShade, Color(0xFF8E4F22))
    drawRoundRect(Color(0xFFD48B4B), p(0.17f, 0.58f), Size(w(0.66f), w(0.1f)), androidx.compose.ui.geometry.CornerRadius(w(0.04f)))
    drawLine(Color(0xFF3B6D11), p(0.5f, 0.6f), p(0.5f, 0.32f), strokeWidth = w(0.06f), cap = StrokeCap.Round)
    val left = Path().apply {
        moveTo(w(0.5f), w(0.4f)); cubicTo(w(0.4f), w(0.18f), w(0.16f), w(0.18f), w(0.12f), w(0.24f))
        cubicTo(w(0.16f), w(0.42f), w(0.38f), w(0.46f), w(0.5f), w(0.4f)); close()
    }
    val right = Path().apply {
        moveTo(w(0.5f), w(0.32f)); cubicTo(w(0.58f), w(0.08f), w(0.82f), w(0.04f), w(0.9f), w(0.1f))
        cubicTo(w(0.86f), w(0.3f), w(0.64f), w(0.38f), w(0.5f), w(0.32f)); close()
    }
    drawPath(left, Color(0xFF97C459))
    drawPath(right, Color(0xFF639922))
    drawCircle(Color.White, w(0.035f), p(0.26f, 0.26f), alpha = 0.5f)
}

private fun DrawScope.hourglass() {
    val glass = Color(0x40FAC775)
    val top = Path().apply {
        moveTo(w(0.26f), w(0.16f)); lineTo(w(0.74f), w(0.16f))
        cubicTo(w(0.74f), w(0.36f), w(0.6f), w(0.46f), w(0.5f), w(0.5f))
        cubicTo(w(0.4f), w(0.46f), w(0.26f), w(0.36f), w(0.26f), w(0.16f)); close()
    }
    val bottom = Path().apply {
        moveTo(w(0.26f), w(0.84f)); lineTo(w(0.74f), w(0.84f))
        cubicTo(w(0.74f), w(0.64f), w(0.6f), w(0.54f), w(0.5f), w(0.5f))
        cubicTo(w(0.4f), w(0.54f), w(0.26f), w(0.64f), w(0.26f), w(0.84f)); close()
    }
    drawPath(top, glass)
    drawPath(bottom, glass)
    // Sand: a little left on top, a heap below, a thin stream between.
    val topSand = Path().apply {
        moveTo(w(0.34f), w(0.3f)); lineTo(w(0.66f), w(0.3f))
        cubicTo(w(0.62f), w(0.4f), w(0.55f), w(0.45f), w(0.5f), w(0.47f))
        cubicTo(w(0.45f), w(0.45f), w(0.38f), w(0.4f), w(0.34f), w(0.3f)); close()
    }
    val heap = Path().apply {
        moveTo(w(0.29f), w(0.84f)); cubicTo(w(0.36f), w(0.68f), w(0.64f), w(0.68f), w(0.71f), w(0.84f)); close()
    }
    drawPath(topSand, Color(0xFFEF9F27))
    drawPath(heap, Color(0xFFEF9F27))
    drawLine(Color(0xFFEF9F27), p(0.5f, 0.48f), p(0.5f, 0.74f), strokeWidth = w(0.025f))
    drawRoundRect(Color(0xFF854F0B), p(0.18f, 0.08f), Size(w(0.64f), w(0.1f)), androidx.compose.ui.geometry.CornerRadius(w(0.05f)))
    drawRoundRect(Color(0xFF854F0B), p(0.18f, 0.82f), Size(w(0.64f), w(0.1f)), androidx.compose.ui.geometry.CornerRadius(w(0.05f)))
    drawCircle(Color.White, w(0.03f), p(0.33f, 0.22f), alpha = 0.5f)
}

private fun DrawScope.coffee() {
    // A cup of coffee with a little steam: the break is over, back to it.
    val steam = Color(0xFFB4B2A9)
    val s1 = Path().apply { moveTo(w(0.34f), w(0.06f)); quadraticTo(w(0.26f), w(0.14f), w(0.34f), w(0.24f)) }
    val s2 = Path().apply { moveTo(w(0.5f), w(0.02f)); quadraticTo(w(0.42f), w(0.11f), w(0.5f), w(0.22f)) }
    drawPath(s1, steam, style = Stroke(w(0.045f), cap = StrokeCap.Round))
    drawPath(s2, steam, style = Stroke(w(0.045f), cap = StrokeCap.Round))
    // Handle, then the cup over it.
    drawArc(Color(0xFFC9D0D5), -90f, 180f, false, p(0.62f, 0.42f), Size(w(0.26f), w(0.3f)), style = Stroke(w(0.07f)))
    val cup = Path().apply {
        moveTo(w(0.12f), w(0.34f)); lineTo(w(0.78f), w(0.34f)); lineTo(w(0.78f), w(0.66f))
        cubicTo(w(0.78f), w(0.86f), w(0.64f), w(0.96f), w(0.48f), w(0.96f)); lineTo(w(0.42f), w(0.96f))
        cubicTo(w(0.26f), w(0.96f), w(0.12f), w(0.86f), w(0.12f), w(0.66f)); close()
    }
    drawPath(cup, Color(0xFFE9ECEF))
    val shade = Path().apply {
        moveTo(w(0.56f), w(0.34f)); lineTo(w(0.78f), w(0.34f)); lineTo(w(0.78f), w(0.66f))
        cubicTo(w(0.78f), w(0.86f), w(0.64f), w(0.96f), w(0.48f), w(0.96f)); lineTo(w(0.46f), w(0.96f))
        cubicTo(w(0.6f), w(0.9f), w(0.66f), w(0.8f), w(0.66f), w(0.66f)); lineTo(w(0.66f), w(0.34f)); close()
    }
    drawPath(shade, Color(0xFFC9D0D5))
    drawOval(Color(0xFF854F0B), p(0.14f, 0.3f), Size(w(0.62f), w(0.09f)))
    drawCircle(Color.White, w(0.035f), p(0.24f, 0.5f), alpha = 0.8f)
}

private fun DrawScope.cucumber() {
    // A little buddy relaxing with cucumber slices on its eyes: one clean round head, no extra shading ring.
    drawCircle(Color(0xFF5DCAA5), w(0.44f), p(0.5f, 0.52f))
    for (cx in listOf(0.33f, 0.67f)) {
        drawCircle(Color(0xFF97C459), w(0.15f), p(cx, 0.44f))
        drawCircle(Color(0xFFEAF3DE), w(0.11f), p(cx, 0.44f))
        drawCircle(Color(0xFF97C459), w(0.018f), p(cx - 0.03f, 0.41f))
        drawCircle(Color(0xFF97C459), w(0.018f), p(cx + 0.03f, 0.47f))
        drawCircle(Color(0xFF97C459), w(0.015f), p(cx + 0.02f, 0.39f))
    }
    drawArc(Color(0xFF04342C), 20f, 140f, false, p(0.4f, 0.6f), Size(w(0.2f), w(0.14f)), style = Stroke(w(0.045f), cap = StrokeCap.Round))
    drawCircle(Color(0xFFF4C0D1), w(0.055f), p(0.2f, 0.68f))
    drawCircle(Color(0xFFF4C0D1), w(0.055f), p(0.8f, 0.68f))
}
