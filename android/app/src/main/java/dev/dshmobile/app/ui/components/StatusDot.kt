package dev.dshmobile.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.LocalMotionEnabled

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val opacity = if (pulse && LocalMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "task activity")
        val value by transition.animateFloat(0.45f, 1f,
            infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "running dot")
        value
    } else 1f
    Box(modifier.size(8.dp).alpha(opacity).background(color, CircleShape))
}

/** Rotating arc for work in progress; a static three-quarter arc when system animations are off. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = MaterialTheme.colorScheme.onSurface, strokeWidth: Dp = 2.dp) {
    val angle = if (LocalMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "spinner")
        val value by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinner angle")
        value
    } else 0f
    val track = color.copy(alpha = 0.18f)
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color, angle - 90f, 270f * 0.4f + 40f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Quiet marker for a chat with no work in progress; it deliberately claims no success or failure. */
@Composable
internal fun IdleRing(modifier: Modifier = Modifier, size: Dp = ControlSize.StatusGlyph, strokeWidth: Dp = ControlSize.StatusStroke) {
    Box(modifier.size(size).border(strokeWidth, MaterialTheme.colorScheme.outline, CircleShape))
}
