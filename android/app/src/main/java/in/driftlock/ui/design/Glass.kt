package `in`.driftlock.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Lightweight glass appearance for API 26+: no backdrop capture, content blur or extra dependency. */
object GlassTokens {
    val fill = Color(0xFFF9FCF8).copy(alpha = .78f)
    val edge = Color(0xFF375B4B).copy(alpha = .14f)
    val backdrop = Color(0xFFF4F6F0)
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    elevation: Dp = 0.dp,
    tint: Color = GlassTokens.fill,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = GlassTokens.edge,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(), shape = shape,
        color = tint, contentColor = contentColor,
        border = BorderStroke(.5.dp, borderColor),
        shadowElevation = elevation, tonalElevation = 0.dp
    ) {
        Box { content() }
    }
}
