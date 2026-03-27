package ai.openclaw.imapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PrimaryGreen = Color(0xFF10A37F)
private val PrimaryGreenDark = Color(0xFF0D8A6B)
private val OnPrimary = Color.White
private val Background = Color(0xFFF5F7FA)
private val Surface = Color(0xFFFFFFFF)
private val SurfaceVariant = Color(0xFFF0F3F6)
private val Outline = Color(0xFFD9E0E8)
private val AgentBubble = Color(0xFFFFFFFF)
private val UserBubble = Color(0xFFE3F7F1)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = OnPrimary,
    primaryContainer = Color(0xFFD8F3EA),
    onPrimaryContainer = Color(0xFF073B2F),
    secondary = Color(0xFF334155),
    tertiary = PrimaryGreenDark,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    outline = Outline,
)

@Composable
fun ImappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography(),
        content = content,
    )
}

val UserBubbleColor = UserBubble
val AgentBubbleColor = AgentBubble
