package app.fifteen

import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle

/**
 * Windows: snap letters to the pixel grid (full hinting) so small text stays crisp, also in the see-through
 * floating windows where ClearType can't be used. Subpixel smoothing still applies wherever Windows allows it.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual fun crispText(): PlatformTextStyle? {
    val hinting = when (System.getenv("FIFTEEN_TEXT")) {
        "default" -> return null
        "normal" -> FontHinting.Normal
        "slight" -> FontHinting.Slight
        else -> FontHinting.Full
    }
    return PlatformTextStyle(
        spanStyle = null,
        paragraphStyle = PlatformParagraphStyle(
            FontRasterizationSettings(FontSmoothing.SubpixelAntiAlias, hinting, subpixelPositioning = true, autoHintingForced = false),
        ),
    )
}
