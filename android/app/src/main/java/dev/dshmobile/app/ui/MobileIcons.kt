package dev.dshmobile.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small authored outline set; no icon-font or material-icons dependency. */
object MobileIcons {
    val Menu = outline("Menu") {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
    }
    val Back = outline("Back", true) {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
    val Send = outline("Send") {
        moveTo(12f, 20f); lineTo(12f, 4f)
        moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f)
    }
    val Search = outline("Search") {
        moveTo(17f, 17f); lineTo(21f, 21f)
        moveTo(18f, 10f); curveTo(18f, 14.4f, 14.4f, 18f, 10f, 18f); curveTo(5.6f, 18f, 2f, 14.4f, 2f, 10f)
        curveTo(2f, 5.6f, 5.6f, 2f, 10f, 2f); curveTo(14.4f, 2f, 18f, 5.6f, 18f, 10f); close()
    }
    // Symmetric six-tooth gear with open gaps and a 3-unit hub: same optical weight as Search at the same size.
    val Settings = outline("Settings") {
        moveTo(9.57f, 5.86f); lineTo(10.04f, 3.22f); lineTo(13.96f, 3.22f); lineTo(14.43f, 5.86f); lineTo(16.10f, 6.83f); lineTo(18.62f, 5.91f)
        lineTo(20.59f, 9.31f); lineTo(18.53f, 11.04f); lineTo(18.53f, 12.96f); lineTo(20.59f, 14.69f); lineTo(18.62f, 18.09f); lineTo(16.10f, 17.17f)
        lineTo(14.43f, 18.14f); lineTo(13.96f, 20.78f); lineTo(10.04f, 20.78f); lineTo(9.57f, 18.14f); lineTo(7.90f, 17.17f); lineTo(5.38f, 18.09f)
        lineTo(3.41f, 14.69f); lineTo(5.47f, 12.96f); lineTo(5.47f, 11.04f); lineTo(3.41f, 9.31f); lineTo(5.38f, 5.91f); lineTo(7.90f, 6.83f); close()
        moveTo(15f, 12f); curveTo(15f, 13.66f, 13.66f, 15f, 12f, 15f); curveTo(10.34f, 15f, 9f, 13.66f, 9f, 12f)
        curveTo(9f, 10.34f, 10.34f, 9f, 12f, 9f); curveTo(13.66f, 9f, 15f, 10.34f, 15f, 12f); close()
    }
    val Chat = outline("Chat") {
        moveTo(5f, 3f); lineTo(19f, 3f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f); lineTo(21f, 16f)
        curveTo(21f, 17.1f, 20.1f, 18f, 19f, 18f); lineTo(8f, 18f); lineTo(3f, 22f); lineTo(3f, 5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
        moveTo(7f, 8f); lineTo(17f, 8f); moveTo(7f, 12f); lineTo(13f, 12f)
    }
    val Folder = outline("Folder") {
        moveTo(3f, 6f); lineTo(3f, 19f); lineTo(21f, 19f); lineTo(21f, 7f); lineTo(11f, 7f); lineTo(9f, 4f); lineTo(3f, 4f); close()
    }
    val Edit = outline("Edit") {
        moveTo(15f, 4f); lineTo(20f, 9f); lineTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f); close()
        moveTo(13f, 6f); lineTo(18f, 11f)
    }
    val Stop = outline("Stop") { moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close() }
    val Copy = outline("Copy") {
        moveTo(8f, 8f); lineTo(21f, 8f); lineTo(21f, 21f); lineTo(8f, 21f); close()
        moveTo(16f, 4f); lineTo(16f, 3f); lineTo(3f, 3f); lineTo(3f, 16f); lineTo(4f, 16f)
    }
    val Check = outline("Check") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 6f) }
    val Close = outline("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Refresh = outline("Refresh") {
        moveTo(20f, 8f); curveTo(18f, 3f, 11f, 1f, 6f, 5f); curveTo(1f, 9f, 3f, 18f, 9f, 20f); curveTo(14f, 22f, 19f, 19f, 20f, 15f)
        moveTo(20f, 3f); lineTo(20f, 8f); lineTo(15f, 8f)
    }
    val Computer = outline("Computer") {
        moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 16f); lineTo(3f, 16f); close()
        moveTo(12f, 16f); lineTo(12f, 21f); moveTo(7f, 21f); lineTo(17f, 21f)
    }
    val Shield = outline("Shield") {
        moveTo(12f, 2f); lineTo(21f, 6f); lineTo(20f, 14f); curveTo(19f, 18f, 15f, 21f, 12f, 22f)
        curveTo(9f, 21f, 5f, 18f, 4f, 14f); lineTo(3f, 6f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(17f, 9f)
    }
    val More = ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f).apply {
        listOf(5f, 12f, 19f).forEach { x ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(x - 1.6f, 12f); curveTo(x - 1.6f, 11.1f, x - 0.9f, 10.4f, x, 10.4f); curveTo(x + 0.9f, 10.4f, x + 1.6f, 11.1f, x + 1.6f, 12f)
                curveTo(x + 1.6f, 12.9f, x + 0.9f, 13.6f, x, 13.6f); curveTo(x - 0.9f, 13.6f, x - 1.6f, 12.9f, x - 1.6f, 12f); close()
            }
        }
    }.build()
    val Plus = outline("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val ChevronDown = outline("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val ChevronRight = outline("ChevronRight", true) { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val ChevronLeft = outline("ChevronLeft", true) { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
    val Terminal = outline("Terminal") {
        moveTo(4f, 7f); lineTo(9f, 12f); lineTo(4f, 17f)
        moveTo(12f, 18f); lineTo(20f, 18f)
    }
    val StopFilled = ImageVector.Builder("StopFilled", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 7f); lineTo(16f, 7f); curveTo(16.6f, 7f, 17f, 7.4f, 17f, 8f); lineTo(17f, 16f)
            curveTo(17f, 16.6f, 16.6f, 17f, 16f, 17f); lineTo(8f, 17f); curveTo(7.4f, 17f, 7f, 16.6f, 7f, 16f)
            lineTo(7f, 8f); curveTo(7f, 7.4f, 7.4f, 7f, 8f, 7f); close()
        }
    }.build()
    val Mark = outline("DSH") {
        moveTo(9f, 4f); lineTo(3f, 10f); lineTo(9f, 16f); moveTo(15f, 8f); lineTo(21f, 14f); lineTo(15f, 20f)
        moveTo(14f, 3f); lineTo(10f, 21f)
    }
    private fun outline(name: String, mirrored: Boolean = false, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()
}
