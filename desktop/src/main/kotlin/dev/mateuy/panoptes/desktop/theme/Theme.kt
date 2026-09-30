package dev.mateuy.panoptes.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette of the release board: a dark board on a warm light page. */
object PanoptesColors {
    val Page = Color(0xFFE9E6E0)
    val PageText = Color(0xFF6B6A66)
    val PageBorder = Color(0xFFCFCBC4)
    val Ink = Color(0xFF1B1C1F)

    val Board = Color(0xFF1E1F23)
    val BoardDivider = Color(0xFF2C2E33)
    val BoardHeader = Color(0xFF9A9CA3)
    val Text = Color(0xFFF2F2F3)
    val TextSecondary = Color(0xFF8E9098)
    val TextMuted = Color(0xFF63656C)
    val VersionBox = Color(0xFF2A2C31)
    val ButtonBorder = Color(0xFF4A4C52)

    val Live = Color(0xFF1FA055)
    val Review = Color(0xFFF5B800)
    val Behind = Color(0xFFE5484D)
    val BehindRow = Color(0xFF2C1F23)
    val BehindText = Color(0xFFF07B7B)
    val Neutral = Color(0xFF5A5C63)

    val WarningContainer = Color(0xFFFBE9C0)
    val WarningContent = Color(0xFF6B4E00)
}

@Composable
fun PanoptesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PanoptesColors.Ink,
            onPrimary = Color.White,
            background = PanoptesColors.Page,
            surface = PanoptesColors.Page,
            surfaceContainer = Color(0xFFF3F1EC),
            surfaceContainerLow = Color(0xFFF3F1EC),
            surfaceContainerHigh = Color(0xFFF6F4F0),
            surfaceContainerHighest = Color(0xFFF6F4F0),
            error = PanoptesColors.Behind,
        ),
        content = content,
    )
}
