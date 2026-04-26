package ro.pub.cs.systems.eim.googlehack.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme

object WatchColors {
    val Background    = Color(0xFF000000)
    val SurfaceLow    = Color(0xFF0D0D1A)
    val SurfaceMid    = Color(0xFF141428)
    val SurfaceHigh   = Color(0xFF1E1E35)

    val Accent        = Color(0xFFA78BFA)
    val AccentDim     = Color(0xFF3B2070)
    val AccentGlow    = Color(0x33A78BFA)

    val CalmPrimary   = Color(0xFF38BDF8)
    val CalmSurface   = Color(0xFF0C1A2E)

    val Warning       = Color(0xFFF59E0B)
    val Danger        = Color(0xFFEF4444)
    val DangerOverlay = Color(0xDD1A0500)

    val TextPrimary   = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B949E)
    val TextTertiary  = Color(0xFF484F58)

    val Online        = Accent
    val Offline       = Danger
    val FocusButton   = Color(0xFF4C1D95)
}

@Composable
fun GoogleHackTheme(
    content: @Composable () -> Unit
) {
    /**
     * Empty theme to customize for your app.
     * See: https://developer.android.com/jetpack/compose/designsystems/custom
     */
    MaterialTheme(
        content = content
    )
}