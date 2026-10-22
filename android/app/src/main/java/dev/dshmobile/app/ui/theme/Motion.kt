package dev.dshmobile.app.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * One motion vocabulary for the app: three short durations on FastOutSlowIn, all collapsing to an
 * instant change when the system animator scale is 0 ([LocalMotionEnabled]). Transitions are plain
 * values built from a `motion` flag, so they can be used inside non-composable transition lambdas.
 */
internal object Motion {
    /** Small state changes: fades of inline markers, glyph swaps, exits. */
    const val Short = 150
    /** Enter/exit of strips, cards and chips; size changes of a container. */
    const val Medium = 220
    /** Screen-level transitions. */
    const val Long = 300

    fun <T> tween(duration: Int, enabled: Boolean, delay: Int = 0): FiniteAnimationSpec<T> =
        if (enabled) androidx.compose.animation.core.tween(duration, delay, FastOutSlowInEasing) else snap()

    /** Vertical reveal for strips, cards and rows that push content: fade + expand from the top edge. */
    fun revealIn(motion: Boolean): EnterTransition = if (!motion) EnterTransition.None else
        fadeIn(tween(Medium, true)) + expandVertically(tween<IntSize>(Medium, true), expandFrom = Alignment.Top)
    fun revealOut(motion: Boolean): ExitTransition = if (!motion) ExitTransition.None else
        fadeOut(tween(Short, true)) + shrinkVertically(tween<IntSize>(Medium, true), shrinkTowards = Alignment.Top)

    /** The same reveal anchored at the bottom: things that grow upward from the composer. */
    fun riseIn(motion: Boolean): EnterTransition = if (!motion) EnterTransition.None else
        fadeIn(tween(Medium, true)) + expandVertically(tween<IntSize>(Medium, true), expandFrom = Alignment.Bottom)
    fun riseOut(motion: Boolean): ExitTransition = if (!motion) ExitTransition.None else
        fadeOut(tween(Short, true)) + shrinkVertically(tween<IntSize>(Medium, true), shrinkTowards = Alignment.Bottom)

    /** Horizontal reveal for chips in a row: fade + scale, the row reflows smoothly. */
    fun popIn(motion: Boolean): EnterTransition = if (!motion) EnterTransition.None else
        fadeIn(tween(Medium, true)) + scaleIn(tween(Medium, true), initialScale = 0.85f) + expandHorizontally(tween<IntSize>(Medium, true), expandFrom = Alignment.Start)
    fun popOut(motion: Boolean): ExitTransition = if (!motion) ExitTransition.None else
        fadeOut(tween(Short, true)) + scaleOut(tween(Short, true), targetScale = 0.85f) + shrinkHorizontally(tween<IntSize>(Medium, true), shrinkTowards = Alignment.Start)

    /** Floating controls (a pill over content): fade with a short lift, no layout change. */
    fun floatIn(motion: Boolean): EnterTransition = if (!motion) EnterTransition.None else
        fadeIn(tween(Medium, true)) + slideInVertically(tween<IntOffset>(Medium, true)) { it / 2 } + scaleIn(tween(Medium, true), initialScale = 0.92f)
    fun floatOut(motion: Boolean): ExitTransition = if (!motion) ExitTransition.None else
        fadeOut(tween(Short, true)) + slideOutVertically(tween<IntOffset>(Short, true)) { it / 2 } + scaleOut(tween(Short, true), targetScale = 0.92f)

    /** Inline swap (a glyph or label replaced in place): crossfade only, no size animation. */
    fun crossfade(motion: Boolean): ContentTransform = if (!motion) (EnterTransition.None togetherWith ExitTransition.None) else
        (fadeIn(tween(Short, true)) togetherWith fadeOut(tween(Short, true)))

    /** Plain fade for whole panels (viewer chrome): no layout change. */
    fun panelIn(motion: Boolean): EnterTransition = if (!motion) EnterTransition.None else fadeIn(tween(Short, true))
    fun panelOut(motion: Boolean): ExitTransition = if (!motion) ExitTransition.None else fadeOut(tween(Short, true))
}

/** Motion is enabled unless the user turned system animations off. */
internal val motionEnabled: Boolean
    @Composable @ReadOnlyComposable get() = LocalMotionEnabled.current

/**
 * A keyed, non-lazy list whose entries animate in and out. A removed entry stays composed with its
 * last value until its exit finishes; entries present on first composition appear without animation.
 */
@Composable
internal fun <T> AnimatedKeyed(items: List<T>, key: (T) -> Any, enter: EnterTransition, exit: ExitTransition, content: @Composable (T) -> Unit) {
    val retained = remember { LinkedHashMap<Any, T>() }
    val firstPass = remember { booleanArrayOf(true) }
    val first = firstPass[0]
    val current = items.associateBy(key)
    retained.putAll(current)
    SideEffect { firstPass[0] = false }
    retained.entries.toList().forEach { (id, value) ->
        androidx.compose.runtime.key(id) {
            val visibility = remember { MutableTransitionState(first) }
            visibility.targetState = id in current
            if (!visibility.targetState && visibility.isIdle && !visibility.currentState) {
                SideEffect { if (id !in current) retained.remove(id) }
            } else AnimatedVisibility(visibility, enter = enter, exit = exit, label = "keyed item") { content(value) }
        }
    }
}

/** Container size follows its content smoothly; a no-op without motion. */
internal fun Modifier.animateSize(motion: Boolean): Modifier = if (motion) animateContentSize(Motion.tween(Motion.Medium, true)) else this
