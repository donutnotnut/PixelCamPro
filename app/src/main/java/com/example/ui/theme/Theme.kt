package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val PixelExpressiveFallbackScheme = darkColorScheme(
    primary = PixelBlue,
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1A3A6E),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = PixelAmber,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF473308),
    onSecondaryContainer = Color(0xFFFFE6A5),
    tertiary = HdrCyan,
    onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0xFF064B66),
    onTertiaryContainer = Color(0xFFC2EBFF),
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = Color(0xFF101319),
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Color(0xFF080A0F),
    surfaceContainerLow = Color(0xFF12161E),
    surfaceContainer = Color(0xFF181C25),
    surfaceContainerHigh = Color(0xFF212632),
    surfaceContainerHighest = Color(0xFF2B3140),
    outline = OutlineSubtle,
    outlineVariant = Color(0xFF262D3B),
    error = ZebraCoral,
    onError = Color(0xFF490013),
    errorContainer = Color(0xFF6B1028),
    onErrorContainer = Color(0xFFFFD9DF),
)

val PixelExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PixelCamTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(context, dynamicColor) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dynamic = dynamicDarkColorScheme(context)
            dynamic.copy(
                background = Color(0xFF090B0E),
                surfaceContainerLowest = Color(0xFF090B0E),
            )
        } else {
            PixelExpressiveFallbackScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PixelTypography,
        shapes = PixelExpressiveShapes,
        content = content,
    )
}
