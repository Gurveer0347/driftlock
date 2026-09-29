package `in`.driftlock.ui.design

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}
val Mint = Color(0xFF236447)
val Amber = Color(0xFF855900)
val Muted = Color(0xFF56625A)
val Outline = Color(0xFFD5DED4)
val DemoBlue = Color(0xFF345C87)

@Composable
fun DriftlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF163F32), onPrimary = Color.White,
            secondary = DemoBlue, onSecondary = Color.White,
            secondaryContainer = Color(0xFFE6EDE3), onSecondaryContainer = Color(0xFF1D3028),
            primaryContainer = Color(0xFFE6EDE3), onPrimaryContainer = Color(0xFF1D3028),
            background = Color(0xFFF7F5F0), onBackground = Color(0xFF172C25),
            surface = Color(0xFFFEFCF8), onSurface = Color(0xFF172C25),
            surfaceVariant = Color(0xFFECF0E9), onSurfaceVariant = Muted,
            surfaceContainer = Color(0xFFF0F2EC),
            outline = Color(0xFFD8DFD4), error = Color(0xFFA63E32)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp), extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
        content = content
    )
}
