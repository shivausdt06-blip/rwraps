package lab.arl.target.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LabColors = darkColorScheme(
    primary = Color(0xFFC4453C),
    onPrimary = Color(0xFFFFF6F5),
    secondary = Color(0xFF8C2F28),
    background = Color(0xFF140A0A),
    surface = Color(0xFF1C1010),
    onBackground = Color(0xFFF2E8E8),
    onSurface = Color(0xFFF2E8E8),
    error = Color(0xFFFF8A80)
)

@Composable
fun ArlTargetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LabColors, content = content)
}
