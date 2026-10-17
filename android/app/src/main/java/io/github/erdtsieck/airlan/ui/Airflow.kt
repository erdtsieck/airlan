package io.github.erdtsieck.airlan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Mode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fan of vane positions as rays from a pivot, in a box of [width]×[height] dp. The rays'
 * ends lie on an ellipse with semi-axes [a] and [b] around the pivot. [sideView]: the up/down
 * vane seen from the side, angles below the horizontal; otherwise the left/right vane from
 * above, angles from straight ahead (negative is left). The same numbers as the web app.
 */
private class Fan(
    val width: Float,
    val height: Float,
    val px: Float,
    val py: Float,
    val a: Float,
    val b: Float,
    val angles: List<Float>,
    val sideView: Boolean,
) {
    fun end(i: Int): Offset {
        val r = angles[i] * PI.toFloat() / 180f
        return if (sideView) Offset(px + a * cos(r), py + b * sin(r)) else Offset(px + a * sin(r), py - b * cos(r))
    }

    /** The ray nearest to a point, in dp from the box's top left. */
    fun nearest(x: Float, y: Float): Int {
        val angle = (if (sideView) atan2((y - py) / b, (x - px) / a) else atan2((x - px) / a, (py - y) / b)) * 180f / PI.toFloat()
        return angles.indices.minBy { abs(angles[it] - angle) }
    }
}

private val UP_DOWN = Fan(112f, 64f, 16f, 12f, 90f, 50f, listOf(6f, 24f, 42f, 60f), sideView = true)
private val LEFT_RIGHT = Fan(120f, 60f, 60f, 56f, 54f, 50f, listOf(-66f, -33f, 0f, 33f, 66f), sideView = false)

/** The colours of the card: the accent is the unit's mode (cooling or heating). */
private class FlowColors(val accent: Color, val soft: Color, val idle: Color, val muted: Color, val line: Color)

@Composable
private fun flowColors(mode: Mode?): FlowColors {
    val c = LocalAirColors.current
    val accent = when (mode) {
        Mode.COOL -> c.cool
        Mode.HEAT -> c.heat
        else -> c.text
    }
    return FlowColors(accent, accent.copy(alpha = 0.16f).compositeOver(c.card), c.muted.copy(alpha = 0.4f).compositeOver(c.card), c.muted, c.line)
}

/**
 * Fan speed and the vanes, below the timer as they are needed least: rows of a label with the
 * current value on the left and a drawing to tap on the right (below the label when they do
 * not fit side by side). 3D auto, which aims both vanes, is a toggle in the header; it and the
 * left/right rows are only there for a unit that [aimsBothVanes].
 */
@Composable
internal fun AirflowCard(
    s: AirconState?,
    aimsBothVanes: Boolean,
    enabled: Boolean,
    onFanSpeed: (Int) -> Unit,
    onVane: (Int) -> Unit,
    onVaneLR: (Int) -> Unit,
    onAuto3d: () -> Unit,
) {
    val f = flowColors(s?.mode)
    val auto3d = aimsBothVanes && s?.entrust == true
    val auto3dLabel = stringResource(R.string.auto_3d)
    val fans = stringArrayResource(R.array.fan_speeds)
    val uds = stringArrayResource(R.array.vanes_ud)
    val lrs = stringArrayResource(R.array.vanes_lr)
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading(stringResource(R.string.airflow)) }
            if (aimsBothVanes) {
                // Off again: the position before is unknown, so the highest.
                FlowPill(auto3dLabel, auto3d, enabled, f, filled = true, icon = AirIcons.cube) { if (auto3d) onVane(1) else onAuto3d() }
            }
        }

        val level = s?.airFlow ?: -1
        LabelledRow(stringResource(R.string.fan_speed), fans.getOrNull(level), f) {
            FlowPill(fans[0], level == 0, enabled, f) { onFanSpeed(0) }
            Spacer(Modifier.width(4.dp))
            Bars(level, fans, enabled, f, onFanSpeed)
        }
        HorizontalDivider(color = f.line)

        val ud = s?.windUD ?: -1
        LabelledRow(stringResource(R.string.vane), if (auto3d) auto3dLabel else uds.getOrNull(ud), f) {
            RayFan(
                UP_DOWN, lit = if (ud in 1..4 && !auto3d) listOf(ud - 1) else emptyList(), dot = if (auto3d) null else ud - 1,
                faint = ud == 0 || auto3d, labels = uds.drop(1), selected = if (auto3d) -1 else ud - 1, enabled, f,
            ) { onVane(it + 1) }
            RoundToggle(AirIcons.swingUpDown, uds[0], ud == 0 && !auto3d, enabled, f) { onVane(0) }
        }

        if (aimsBothVanes) {
            HorizontalDivider(color = f.line)
            val lr = s?.windLR ?: -1
            val lit = when {
                auto3d -> emptyList()
                lr == 6 -> listOf(0, 4)
                lr == 7 -> listOf(1, 3)
                lr in 1..5 -> listOf(lr - 1)
                else -> emptyList()
            }
            LabelledRow(stringResource(R.string.vane_lr), if (auto3d) auto3dLabel else lrs.getOrNull(lr), f) {
                RayFan(
                    LEFT_RIGHT, lit = lit, dot = if (lr in 1..5 && !auto3d) lr - 1 else null,
                    faint = lr == 0 || auto3d, labels = lrs.slice(1..5), selected = if (auto3d) -1 else lr - 1, enabled, f,
                ) { onVaneLR(it + 1) }
                RoundToggle(AirIcons.swingLeftRight, lrs[0], lr == 0 && !auto3d, enabled, f) { onVaneLR(0) }
            }
            HorizontalDivider(color = f.line)
            LabelledRow(stringResource(R.string.spread), null, f, minor = true) {
                for (v in 6..7) {
                    FlowPill(lrs[v], lr == v && !auto3d, enabled, f) { onVaneLR(v) }
                    if (v == 6) Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

/**
 * A label (and the current value under it) on the left and [control] on the right; when the
 * two do not fit side by side, the control goes below the label, still on the right.
 */
@Composable
private fun LabelledRow(title: String, value: String?, f: FlowColors, minor: Boolean = false, control: @Composable () -> Unit) {
    val c = LocalAirColors.current
    Layout(
        content = {
            Column {
                Text(title, fontSize = 14.sp, fontWeight = if (minor) FontWeight.Normal else FontWeight.Medium, color = if (minor) f.muted else c.text)
                if (value != null) Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = f.accent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { control() }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (minor) 52.dp else 68.dp).padding(vertical = 6.dp),
    ) { measurables, constraints ->
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        // At its natural width: a Row squeezed into less would shrink its last items to nothing.
        val ctl = measurables[1].measure(free.copy(maxWidth = Constraints.Infinity))
        val gap = 12.dp.roundToPx()
        val label = measurables[0].measure(free)
        val side = label.width + gap + ctl.width <= constraints.maxWidth
        val height = maxOf(constraints.minHeight, if (side) maxOf(label.height, ctl.height) else label.height + 4.dp.roundToPx() + ctl.height)
        layout(constraints.maxWidth, height) {
            if (side) {
                label.place(0, (height - label.height) / 2)
                ctl.place(constraints.maxWidth - ctl.width, (height - ctl.height) / 2)
            } else {
                label.place(0, 0)
                ctl.place(constraints.maxWidth - ctl.width, height - ctl.height)
            }
        }
    }
}

/** A rounded pill; selected it takes the accent, softly or ([filled]) solid. */
@Composable
private fun FlowPill(text: String, selected: Boolean, enabled: Boolean, f: FlowColors, filled: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val dim = if (enabled) 1f else 0.45f
    val bg = when {
        !selected -> Color.Transparent
        filled -> f.accent
        else -> f.soft
    }.let { it.copy(alpha = it.alpha * dim) }
    val ring = (if (selected) bg else f.line).let { it.copy(alpha = it.alpha * dim) }
    val fg = when {
        !selected -> f.muted
        filled -> Color.White
        else -> f.accent
    }.copy(alpha = dim)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(bg, cornerRadius = r)
                val w = 1.dp.toPx()
                drawRoundRect(ring, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(size.height / 2 - w / 2), Stroke(w))
            }
            .clickable(enabled = enabled, role = Role.Button) { if (!selected || filled) onClick() }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false)
    }
}

/** A round button with an icon: swinging. */
@Composable
private fun RoundToggle(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, f: FlowColors, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button) { if (!selected) onClick() }
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        val fill = if (selected) f.soft else Color.Transparent
        val ring = if (selected) f.soft else f.line
        val dim = if (enabled) 1f else 0.45f
        Box(
            Modifier.size(44.dp).drawBehind {
                drawCircle(fill.copy(alpha = fill.alpha * dim))
                drawCircle(ring.copy(alpha = ring.alpha * dim), radius = size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = (if (selected) f.accent else f.muted).copy(alpha = dim), modifier = Modifier.size(20.dp)) }
    }
}

/** Four volume-style bars, quiet to high; tapping bar n selects step n, bars up to it light up. */
@Composable
private fun Bars(level: Int, labels: Array<String>, enabled: Boolean, f: FlowColors, onSelect: (Int) -> Unit) {
    Row {
        for (n in 1..4) {
            Box(
                Modifier
                    .size(width = 30.dp, height = 48.dp)
                    .clickable(enabled = enabled, role = Role.Button) { if (level != n) onSelect(n) }
                    .semantics { contentDescription = labels[n]; selected = level == n }
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val color = (if (n <= level) f.accent else f.idle).copy(alpha = if (enabled) 1f else 0.45f)
                Box(
                    Modifier
                        .size(width = 18.dp, height = (2 + 6 * n).dp)
                        .drawBehind { drawRoundRect(color, cornerRadius = CornerRadius(5.dp.toPx())) },
                )
            }
        }
    }
}

/**
 * A [fan] of rays: [lit] in the accent, [dot] with an end dot, [faint] all half-transparent in
 * the accent (swinging, 3D auto). A tap anywhere picks the nearest ray; for screen readers
 * every ray is its own option, named by [labels].
 */
@Composable
private fun RayFan(
    fan: Fan,
    lit: List<Int>,
    dot: Int?,
    faint: Boolean,
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    f: FlowColors,
    onPick: (Int) -> Unit,
) {
    Box(
        Modifier
            .size(fan.width.dp, fan.height.dp)
            .semantics { isTraversalGroup = true }
            .pointerInput(enabled, fan) {
                if (enabled) detectTapGestures { p -> onPick(fan.nearest(p.x / density, p.y / density)) }
            },
    ) {
        val dim = if (enabled) 1f else 0.45f
        Canvas(Modifier.size(fan.width.dp, fan.height.dp)) {
            val k = density
            val pivot = Offset(fan.px * k, fan.py * k)
            if (fan.sideView) {
                drawRoundRect(
                    color = f.muted.copy(alpha = 0.35f * dim), topLeft = Offset(0f, 2 * k),
                    size = Size(28 * k, 11 * k),
                    cornerRadius = CornerRadius(3 * k),
                )
            }
            for (i in fan.angles.indices) {
                val e = fan.end(i).let { Offset(it.x * k, it.y * k) }
                val color = when {
                    faint -> f.accent.copy(alpha = 0.45f)
                    i in lit -> f.accent
                    else -> f.idle
                }.let { it.copy(alpha = it.alpha * dim) }
                drawLine(color, pivot, e, strokeWidth = 4.5f * k, cap = StrokeCap.Round)
                if (dot == i) drawCircle(f.accent.copy(alpha = dim), radius = 4 * k, center = e)
            }
        }
        // One node per ray for screen readers, at the ray's end; touches go to the box above.
        for (i in fan.angles.indices) {
            val e = fan.end(i)
            Box(
                Modifier
                    .offset((e.x - 12).dp, (e.y - 12).dp)
                    .size(24.dp)
                    .semantics {
                        contentDescription = labels[i]
                        role = Role.Button
                        this.selected = i == selected
                        if (enabled) onClick { onPick(i); true } else disabled()
                    },
            )
        }
    }
}

