package dev.dshmobile.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.dshmobile.app.ui.theme.ControlSize

/** Lets a child (a horizontally scrolling row) extend past its parent's horizontal padding to the container edge. */
internal fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = (horizontal * 2).roundToPx()
    val full = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-(extra / 2), 0) }
}

/**
 * Fades the edge of a horizontally scrolling row on the side where more content scrolls in, so a cut
 * label reads as "more" rather than as a layout bug. Reads scroll state in the draw phase only.
 */
internal fun Modifier.edgeFade(state: LazyListState, width: Dp = ControlSize.EdgeFade): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val fadeLeft = if (rtl) state.canScrollForward else state.canScrollBackward
        val fadeRight = if (rtl) state.canScrollBackward else state.canScrollForward
        if (fadeLeft) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstIn)
        if (fadeRight) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstIn)
    }
