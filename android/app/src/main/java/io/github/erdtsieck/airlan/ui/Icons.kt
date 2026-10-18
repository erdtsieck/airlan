package io.github.erdtsieck.airlan.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The web app's stroke icons (24×24 viewBox), drawn in the current content colour via Icon(). */
object AirIcons {
    private fun stroke(width: Float, vararg paths: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        for (d in paths) {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val power = stroke(2.2f, "M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0")
    val cool = stroke(2f, "M12 2v20M4.9 6.5l14.2 11M4.9 17.5l14.2-11", "M9 4l3 2.5L15 4M9 20l3-2.5L15 20")
    val heat = stroke(
        2f,
        "M12 7.8a4.2 4.2 0 1 0 0 8.4a4.2 4.2 0 1 0 0-8.4z",
        "M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8",
    )
    /** An "A" in a circling arrow. */
    val auto = stroke(2f, "M20 12a8 8 0 1 1-2.6-5.9", "M20 3.5v3.5h-3.5", "M8.8 16l3.2-8l3.2 8M10 13.2h4")
    val dry = stroke(2f, "M12 3.2c3.4 4 5.8 7.3 5.8 10.3a5.8 5.8 0 0 1-11.6 0c0-3 2.4-6.3 5.8-10.3z", "M9.3 14.2a2.8 2.8 0 0 0 2.4 2.6")
    /** Three blades around a hub; the same blade turned by a third each time. */
    val fan = stroke(
        2f,
        "M12 9.6a2.4 2.4 0 1 0 0 4.8a2.4 2.4 0 1 0 0-4.8z",
        "M12 9.6c-.6-2.8.2-5.9 2.8-6.3c2.4-.3 3 2.5 1.2 4.2c-1.1 1-2.4 1.6-4 2.1z",
        "M14.1 13.2c2.7-.9 5.9-.1 6.8 2.4c.8 2.3-2 3.6-4 2.2c-1.2-.9-2.2-2.4-2.8-4.6z",
        "M9.9 13.2c-2.1 2-5.2 2.9-6.9.8c-1.5-1.9.6-4 3-3.2c1.4.5 2.7 1.3 3.9 2.4z",
    )
    val up = stroke(2f, "M6 15l6-6l6 6")
    val down = stroke(2f, "M6 9l6 6l6-6")
    val edit = stroke(2f, "M4 20h4L19 9l-4-4L4 16z")
    val cube = stroke(2f, "M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5M12 12v9M12 12L4 7.5")
    val swingUpDown = stroke(2f, "M12 3v18M8 7l4-4 4 4M8 17l4 4 4-4")
    val swingLeftRight = stroke(2f, "M3 12h18M7 8l-4 4 4 4M17 8l4 4-4 4")
    val gear = stroke(
        2f,
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )
}
