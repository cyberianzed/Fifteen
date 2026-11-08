package app.fifteen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ink {
    val bg = Color(0xFF0E0F13)
    val surface = Color(0xFF16181E)
    val surface2 = Color(0xFF1E2129)
    val line = Color(0x1FFFFFFF)
    val text = Color(0xFFF3F1EA)
    val muted = Color(0xFFA9ABB3)
    val faint = Color(0xFF72757F)
    val mellow = Color(0xFFF2C14E)
    val good = Color(0xFF7BD389)
    val coral = Color(0xFFE5735C)
    val sky = Color(0xFF7FB3D5)
    val violet = Color(0xFFB39DDB)
}

fun ratingColor(r: Int?): Color = when (r) {
    null -> Ink.faint
    0 -> Ink.coral
    50 -> Color(0xFFE9A15B)
    75 -> Ink.mellow
    90 -> Color(0xFFB5D86F)
    else -> Ink.good
}

fun Long.asColor() = Color(this.toInt())

@Composable
fun FifteenTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.mellow,
            onPrimary = Color(0xFF1A1405),
            secondary = Ink.sky,
            background = Ink.bg,
            surface = Ink.surface,
            surfaceVariant = Ink.surface2,
            onSurface = Ink.text,
            onBackground = Ink.text,
            onSurfaceVariant = Ink.muted,
            outline = Ink.line,
            error = Ink.coral,
        ),
        typography = base.copy(
            displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.merge(TextStyle(platformStyle = crispText())),
            content = content,
        )
    }
}
