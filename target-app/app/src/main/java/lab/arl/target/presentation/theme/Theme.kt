package lab.arl.target.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PerkOrange = Color(0xFFFF8C00)
val PerkBlack = Color(0xFF1A1A1A)

private val PerkDevilColors = lightColorScheme(
    primary = PerkOrange,
    onPrimary = Color.White,
    secondary = PerkBlack,
    onSecondary = Color.White,
    background = Color.White,
    surface = Color.White,
    onBackground = PerkBlack,
    onSurface = PerkBlack,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF666666),
    error = Color(0xFFD32F2F),
    outline = Color(0xFFCCCCCC)
)

@Composable
fun PerkDevilTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PerkDevilColors, content = content)
}

// Keep old name as alias so any stray references compile
@Composable
fun ArlTargetTheme(content: @Composable () -> Unit) = PerkDevilTheme(content)
