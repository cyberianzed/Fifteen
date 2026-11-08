package app.fifteen

import androidx.compose.ui.text.PlatformTextStyle

/** Android already renders text sharply; keep its defaults. */
actual fun crispText(): PlatformTextStyle? = null
