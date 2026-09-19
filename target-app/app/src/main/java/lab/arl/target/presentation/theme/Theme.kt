package lab.arl.target.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import lab.arl.target.R

val PerkOrange = Color(0xFFFF8C00)
val PerkBlack = Color(0xFF1A1A1A)

val Jersey10 = FontFamily(
    Font(R.font.jersey_10_regular, FontWeight.Normal),
    Font(R.font.jersey_10_regular, FontWeight.Bold),
    Font(R.font.jersey_10_regular, FontWeight.Medium),
    Font(R.font.jersey_10_regular, FontWeight.SemiBold),
    Font(R.font.jersey_10_regular, FontWeight.Black)
)

private val defaultTypography = Typography()
val PerkDevilTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = Jersey10),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = Jersey10),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = Jersey10),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = Jersey10),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = Jersey10),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = Jersey10),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = Jersey10),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = Jersey10),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = Jersey10),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = Jersey10),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = Jersey10),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = Jersey10),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = Jersey10),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = Jersey10),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = Jersey10)
)

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
    MaterialTheme(
        colorScheme = PerkDevilColors,
        typography = PerkDevilTypography
    ) {
        ProvideTextStyle(value = TextStyle(fontFamily = Jersey10, color = PerkBlack)) {
            content()
        }
    }
}

// Keep old name as alias so any stray references compile
@Composable
fun ArlTargetTheme(content: @Composable () -> Unit) = PerkDevilTheme(content)
