package dev.dshmobile.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Icon scale: three sizes only. Every MobileIcons outline is drawn on a 24-unit viewport with a
 * 2-unit stroke, so one size per role also means one stroke weight per role.
 */
internal object IconSize {
    /** Top-bar actions, composer actions, hero glyphs inside a disc. */
    val Action = 24.dp
    /** List rows, menus, text-field adornments. */
    val Row = 20.dp
    /** Meta: copy, chips, inline markers next to small text. */
    val Meta = 16.dp
}

/** Spacing scale used by screens; the gutter is the horizontal screen margin. */
internal object Spacing {
    val Gutter = 20.dp
    val XXS = 2.dp
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
}

/** Control geometry. Visual size may be smaller than the touch target; the target never drops below 48dp. */
internal object ControlSize {
    val TouchTarget = 48.dp
    /** Round composer actions (stop, send, new task): one visual diameter for all of them. */
    val RoundAction = 40.dp
    /** Leading status column in chat rows: one cell, glyph centred. */
    val StatusCell = 20.dp
    /** Running spinner and idle ring share one outer diameter and one stroke. */
    val StatusGlyph = 14.dp
    val StatusStroke = 1.5.dp
    /** Compact meta row under a message (time + copy); Compose extends the small copy target's touch area to 48dp. */
    val MetaRow = 24.dp
    /** Floating secondary pill ("new messages"); the button keeps a 48dp touch target. */
    val Pill = 36.dp
    /** Width of the edge fade on horizontally scrolling tab rows. */
    val EdgeFade = 24.dp
}
