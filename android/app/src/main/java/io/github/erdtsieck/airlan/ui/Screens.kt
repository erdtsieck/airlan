package io.github.erdtsieck.airlan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.data.aimsBothVanes
import io.github.erdtsieck.airlan.data.macLabel
import io.github.erdtsieck.airlan.ui.MainViewModel.Companion.TEMP_MAX
import io.github.erdtsieck.airlan.ui.MainViewModel.Companion.TEMP_MIN
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Mode
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val TIMER_MINUTES = listOf(15, 30, 60, 120, 180, 240, 300, 360)

private val GAP = 8.dp
private val CHIP_PADDING = 8.dp

/**
 * Equal buttons side by side, [maxColumns] to a row, or fewer when the longest label would
 * not fit (a large font, a narrow screen, a long translation): then they wrap onto more rows.
 * Labels are measured, so a button never cuts its text off. [chrome] is the width a button
 * adds around its label (padding, icon, border).
 */
@Composable
private fun FittingGrid(
    labels: List<String>,
    maxColumns: Int,
    chrome: Dp,
    modifier: Modifier = Modifier,
    item: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium)
    val density = LocalDensity.current
    // A little slack for rounding.
    val needed = with(density) { labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp() } + chrome + 2.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxColumns downTo 1).first { n -> n == 1 || (maxWidth - GAP * (n - 1)) / n >= needed }
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            labels.indices.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    row.forEach { i -> item(i, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun fmt(t: Double?) = t?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–"

@Composable
private fun displayName(state: UiState, unit: UnitUi) =
    unit.stored.name ?: stringResource(R.string.new_unit, state.unnamedIndex(unit))

@Composable
private fun modeName(mode: Mode?) = stringResource(
    when (mode) {
        Mode.COOL -> R.string.cool
        Mode.HEAT -> R.string.heat
        Mode.AUTO -> R.string.mode_auto
        Mode.FAN -> R.string.mode_fan
        Mode.DRY -> R.string.mode_dry
        null -> R.string.mode_unknown
    },
)

// ---------- building blocks ----------

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAirColors.current
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAirColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .border(BorderStroke(1.dp, accent ?: c.line), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Color? = null,
    textColor: Color? = null,
    icon: ImageVector? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 11.dp,
) {
    val c = LocalAirColors.current
    Row(
        modifier
            .clip(shape)
            .background(filled ?: c.card)
            .border(1.dp, filled ?: c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = (textColor ?: if (filled != null) Color.White else c.text).copy(alpha = if (enabled) 1f else 0.4f)
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        // Never cut a label off: one too long for the button wraps (see FittingGrid for keeping it on one line).
        Text(text, color = color, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector? = null,
    label: String,
    onClick: () -> Unit,
    size: Dp,
    enabled: Boolean,
    filled: Color? = null,
    text: String? = null,
) {
    val c = LocalAirColors.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(filled ?: Color.Transparent)
            .border(1.dp, if (filled != null) filled else c.line, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = (if (filled != null && filled != c.line) Color.White else c.text).copy(alpha = if (enabled) 1f else 0.4f)
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.45f))
        if (text != null) Text(text, color = tint, fontSize = 30.sp)
    }
}

@Composable
private fun StatusLine(status: Status?) {
    val c = LocalAirColors.current
    val text = status?.let {
        when {
            it.plural -> pluralStringResource(it.text, it.arg ?: 0, it.arg ?: 0)
            it.arg != null -> stringResource(it.text, it.arg)
            else -> stringResource(it.text)
        }
    } ?: ""
    Text(
        text,
        color = if (status?.isError == true) c.danger else c.muted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Marks a simulated unit next to its name, so it is never mistaken for a real one. */
@Composable
private fun DemoBadge() {
    val c = LocalAirColors.current
    Text(
        stringResource(R.string.demo_badge),
        color = c.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .border(1.dp, c.line, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Hint(text: String) = Text(text, color = LocalAirColors.current.muted, fontSize = 14.sp)

@Composable
internal fun Heading(text: String) =
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = LocalAirColors.current.text, modifier = Modifier.padding(bottom = 8.dp))

// ---------- unit screen ----------

@Composable
fun UnitScreen(
    state: UiState,
    permission: NetworkPermission,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onPower: (Boolean) -> Unit,
    onMode: (Mode) -> Unit,
    onFanSpeed: (Int) -> Unit,
    onVane: (Int) -> Unit,
    onVaneLR: (Int) -> Unit,
    onAuto3d: () -> Unit,
    onNudge: (Double) -> Unit,
    onTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onRename: (String, String) -> Unit,
) = Page {
    val c = LocalAirColors.current
    val unit = state.selected ?: return@Page
    val s = unit.state
    val disabled = s == null || state.busy
    var renaming by remember { mutableStateOf(false) }

    // Every unit at a glance, each tile saying whether it runs: a compact grid rather than a
    // row of tabs, which hides units once there are more than fit. The gear closes the grid;
    // with a single unit it sits on the unit card instead.
    val single = state.units.size <= 1
    if (!single) UnitTiles(state, unit.stored.airconId, onSelect, onManage)

    if (!state.onLocalNetwork) Card(accent = c.heat) { Hint(stringResource(R.string.home_network_only)) }
    if (unit.needsPermission) NetworkPermissionCard(permission)

    if (unit.stored.name == null) {
        var name by remember(unit.stored.airconId) { mutableStateOf("") }
        Card(accent = c.cool) {
            Heading(stringResource(R.string.name_this_unit))
            Hint(stringResource(R.string.name_hint))
            // The address and MAC tell new units apart, also from what other apps show.
            Text(
                unit.stored.host + " · " + stringResource(R.string.unit_mac, unit.stored.macLabel),
                color = c.text,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = { Text(stringResource(R.string.name_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onRename(unit.stored.airconId, name) }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.save), { onRename(unit.stored.airconId, name) }, enabled = name.isNotBlank(), filled = c.text, textColor = c.bg)
            }
        }
    }

    val accent = if (s?.power == true) c.of(s.mode) else c.off
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName(state, unit), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (unit.stored.demo) DemoBadge()
                    Icon(
                        AirIcons.edit,
                        stringResource(R.string.rename),
                        tint = c.muted,
                        modifier = Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable { renaming = true }.padding(6.dp),
                    )
                    if (single) {
                        Spacer(Modifier.weight(1f))
                        RoundButton(AirIcons.gear, stringResource(R.string.manage), onManage, 36.dp, enabled = true)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                val climate = if (s == null) stringResource(unit.availability)
                else listOfNotNull(
                    when {
                        s.indoorTemp != null && s.outdoorTemp != null -> stringResource(R.string.climate, fmt(s.indoorTemp), fmt(s.outdoorTemp))
                        s.indoorTemp != null -> stringResource(R.string.climate_indoor, fmt(s.indoorTemp))
                        else -> null
                    },
                    s.errorCode?.let { stringResource(R.string.fault, it) },
                ).joinToString(" · ")
                Text(climate, color = c.muted, fontSize = 14.sp)
            }
            RoundButton(
                AirIcons.power,
                stringResource(if (s?.power == true) R.string.turn_off else R.string.turn_on),
                { onPower(s?.power != true) },
                52.dp,
                enabled = !disabled,
                filled = if (s?.power == true) accent else c.line,
            )
        }

        // Fan only has no temperature to set: one quiet line instead of the setpoint.
        if (s?.mode == Mode.FAN) {
            Text(
                stringResource(R.string.fan_no_temp),
                color = c.muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
            )
        } else {
            val temp = state.pendingTemp ?: s?.presetTemp
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                RoundButton(null, stringResource(R.string.colder), { onNudge(-0.5) }, 56.dp, enabled = !disabled && (temp ?: 0.0) > TEMP_MIN, text = "−")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (s == null) "–" else fmt(temp) + "°", fontSize = 52.sp, fontWeight = FontWeight.Light, color = accent, lineHeight = 56.sp)
                    // Which mode the unit runs in, in its colour; "off" when it is off.
                    Text(
                        if (s?.power == true) modeName(s.mode).lowercase(LocalConfiguration.current.locales[0]) else stringResource(R.string.off),
                        color = if (s?.power == true) accent else c.muted,
                        fontSize = 13.sp,
                        fontWeight = if (s?.power == true) FontWeight.Medium else FontWeight.Normal,
                    )
                }
                RoundButton(null, stringResource(R.string.warmer), { onNudge(0.5) }, 56.dp, enabled = !disabled && (temp ?: 99.0) < TEMP_MAX, text = "+")
            }
        }

        ModeSelector(s, enabled = !disabled, onMode, Modifier.padding(top = 14.dp))
    }

    Card {
        Heading(stringResource(R.string.auto_off))
        val offAt = unit.stored.timer?.at
        when {
            offAt != null -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(offAt) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(15_000)
                    }
                }
                val minutes = ((offAt - now + 59_999) / 60_000).coerceAtLeast(0).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.off_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(offAt))), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text)
                        Hint(
                            when {
                                minutes >= 60 && minutes % 60 == 0 -> stringResource(R.string.remaining_whole_hours, minutes / 60)
                                minutes >= 60 -> stringResource(R.string.remaining_hours, minutes / 60, minutes % 60)
                                else -> stringResource(R.string.remaining_minutes, minutes)
                            },
                        )
                    }
                    Pill(stringResource(R.string.cancel), onCancelTimer)
                }
            }
            s?.power == true -> {
                val labels = TIMER_MINUTES.map { m -> if (m < 60) stringResource(R.string.minutes_short, m) else stringResource(R.string.hours_short, m / 60) }
                FittingGrid(labels, maxColumns = 4, chrome = CHIP_PADDING * 2 + 2.dp) { i, modifier ->
                    Pill(labels[i], { onTimer(TIMER_MINUTES[i]) }, modifier, enabled = !disabled, horizontalPadding = CHIP_PADDING, verticalPadding = 9.dp)
                }
            }
            else -> Hint(stringResource(R.string.turn_on_for_timer))
        }
    }

    AirflowCard(s, unit.stored.aimsBothVanes, enabled = !disabled, onFanSpeed, onVane, onVaneLR, onAuto3d)

    StatusLine(state.status ?: unit.error?.let { Status(it, isError = true) })

    if (renaming) {
        RenameDialog(unit.stored.name ?: "", onDismiss = { renaming = false }) {
            renaming = false
            onRename(unit.stored.airconId, it)
        }
    }
}

private val MODES = listOf(
    Mode.AUTO to AirIcons.auto,
    Mode.COOL to AirIcons.cool,
    Mode.HEAT to AirIcons.heat,
    Mode.DRY to AirIcons.dry,
    Mode.FAN to AirIcons.fan,
)

/**
 * The five operating modes as one segmented row of icons, in the order of the remote. The
 * current mode is filled in its colour (outlined while the unit is off); its name is under the
 * setpoint, so no label has to fit a fifth of the width. Each icon is named for screen readers.
 */
@Composable
private fun ModeSelector(s: AirconState?, enabled: Boolean, onMode: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAirColors.current
    val track = RoundedCornerShape(16.dp)
    val groupLabel = stringResource(R.string.mode)
    Row(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = groupLabel }
            .clip(track)
            .background(c.bg)
            .border(1.dp, c.line, track)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((mode, icon) in MODES) {
            val selected = s?.mode == mode
            val color = c.of(mode)
            val on = s?.power == true
            val shape = RoundedCornerShape(12.dp)
            val name = modeName(mode)
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(if (selected && on) color else if (selected) c.card else Color.Transparent)
                    .border(if (selected && !on) 1.5.dp else 0.dp, if (selected && !on) color else Color.Transparent, shape)
                    .selectable(selected, enabled = enabled, role = Role.RadioButton) { if (!selected) onMode(mode) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                val tint = when {
                    selected && on -> Color.White
                    selected -> color
                    else -> c.muted
                }
                Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun UnitTiles(state: UiState, selectedId: String, onSelect: (String) -> Unit, onManage: () -> Unit) {
    // A name may wrap, but never inside a word: one column when a word does not fit a tile.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = tileNameStyle()
    val longestWord = state.units.flatMap { displayName(state, it).split(' ') }
        .maxOf { with(density) { measurer.measure(it, style, maxLines = 1).size.width.toDp() } }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        val wanted = if (maxWidth >= 600.dp) 3 else 2
        val columns = (wanted downTo 1).first { n -> n == 1 || (maxWidth - GAP * (n - 1)) / n - TILE_CHROME >= longestWord }
        val rows = state.units.chunked(columns)
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            rows.forEachIndexed { r, row ->
                val last = r == rows.lastIndex
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { u ->
                        UnitTile(state, u, u.stored.airconId == selectedId, Modifier.weight(1f).fillMaxHeight()) { onSelect(u.stored.airconId) }
                    }
                    // The gear ends the last row: in an empty cell when there is one, else
                    // beside the tiles, which then give up its width.
                    if (last) {
                        if (row.size < columns) {
                            repeat(columns - row.size - 1) { Spacer(Modifier.weight(1f)) }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { GearButton(onManage) }
                        } else {
                            GearButton(onManage)
                        }
                    }
                }
            }
        }
    }
}

private val TILE_HEIGHT = 44.dp

@Composable
private fun GearButton(onManage: () -> Unit) = RoundButton(AirIcons.gear, stringResource(R.string.manage), onManage, TILE_HEIGHT, enabled = true)

/**
 * A unit's name with a dot for how it stands (filled in the mode's colour: on; grey: off;
 * hollow red: out of reach) and, when on, its set temperature. Nothing is cut off: the name
 * wraps, and the temperature goes below it when the two do not fit on one line.
 */
@Composable
private fun UnitTile(state: UiState, u: UnitUi, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalAirColors.current
    val s = u.state
    val on = s?.power == true
    val color = if (on) c.of(s.mode) else c.off
    val unreachable = s == null && (u.error != null || u.needsPermission)
    val standing = when {
        on -> stringResource(R.string.tile_on, modeName(s.mode))
        s != null -> stringResource(R.string.tile_off)
        unreachable -> stringResource(u.availability)
        else -> ""
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = TILE_HEIGHT)
            .clip(shape)
            .background(if (selected) c.line.copy(alpha = 0.5f) else c.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.text else c.line, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val name = displayName(state, u)
        val nameStyle = tileNameStyle()
        val tempStyle = nameStyle.copy(fontSize = 14.sp)
        val temp = if (on) fmt(s.presetTemp) + "°" else null
        Row(verticalAlignment = Alignment.Top) {
            // As tall as the name's first line, so the dot sits beside it.
            Box(Modifier.height(with(LocalDensity.current) { TILE_LINE.toDp() }), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (s != null) color else Color.Transparent)
                        .border(1.5.dp, if (unreachable) c.danger else if (s != null) color else c.off, CircleShape)
                        .semantics { contentDescription = standing },
                )
            }
            Spacer(Modifier.width(8.dp))
            NameAndTemperature(name, nameStyle, temp, tempStyle, color, Modifier.weight(1f))
        }
    }
}

private val TILE_LINE = 20.sp

/** What a tile adds around the name: padding, the dot and its gap, the border. */
private val TILE_CHROME = 12.dp * 2 + 10.dp + 8.dp + 2.dp * 2

@Composable
private fun tileNameStyle() = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = TILE_LINE)

/**
 * The name with the temperature at the end of its first line, or, when the two do not fit
 * side by side, the temperature on a line of its own below the name. A layout of its own
 * rather than BoxWithConstraints: the tile rows ask for intrinsic sizes, which that cannot give.
 */
@Composable
private fun NameAndTemperature(name: String, nameStyle: TextStyle, temp: String?, tempStyle: TextStyle, tempColor: Color, modifier: Modifier) {
    val c = LocalAirColors.current
    Layout(
        content = {
            Text(name, color = c.text, style = nameStyle)
            if (temp != null) Text(temp, color = tempColor, style = tempStyle, maxLines = 1)
        },
        modifier = modifier,
        measurePolicy = NameAndTemperaturePolicy,
    )
}

/** Measures [NameAndTemperature]; with intrinsics of its own, as the tile rows ask for them. */
private object NameAndTemperaturePolicy : MeasurePolicy {
    private const val GAP_DP = 6

    /** Whether the temperature fits beside the name in [width], and the width the name gets. */
    private fun IntrinsicMeasureScope.split(measurables: List<IntrinsicMeasurable>, width: Int): Pair<Boolean, Int> {
        val temp = measurables.getOrNull(1)?.let { it.maxIntrinsicWidth(Constraints.Infinity) + GAP_DP.dp.roundToPx() } ?: 0
        val beside = measurables[0].maxIntrinsicWidth(Constraints.Infinity) + temp <= width
        return beside to if (beside) width - temp else width
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val tempPlaceable = measurables.getOrNull(1)?.measure(Constraints())
        val natural = measurables[0].maxIntrinsicWidth(Constraints.Infinity) + (tempPlaceable?.let { it.width + GAP_DP.dp.roundToPx() } ?: 0)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural
        val (beside, nameWidth) = split(measurables, width)
        val namePlaceable = measurables[0].measure(Constraints(maxWidth = nameWidth))
        val tempHeight = tempPlaceable?.height ?: 0
        val height = if (beside) maxOf(namePlaceable.height, tempHeight) else namePlaceable.height + tempHeight
        return layout(width, height) {
            namePlaceable.place(0, 0)
            tempPlaceable?.place(width - tempPlaceable.width, if (beside) 0 else namePlaceable.height)
        }
    }

    private fun IntrinsicMeasureScope.height(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val (beside, nameWidth) = split(measurables, width)
        val name = measurables[0].minIntrinsicHeight(nameWidth)
        val temp = measurables.getOrNull(1)?.minIntrinsicHeight(Constraints.Infinity) ?: 0
        return if (beside) maxOf(name, temp) else name + temp
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = height(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = height(measurables, width)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        maxOf(measurables[0].minIntrinsicWidth(height), measurables.getOrNull(1)?.maxIntrinsicWidth(height) ?: 0)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].maxIntrinsicWidth(height) + (measurables.getOrNull(1)?.let { it.maxIntrinsicWidth(height) + GAP_DP.dp.roundToPx() } ?: 0)
}

// ---------- manage screen ----------

@Composable
fun ManageScreen(
    state: UiState,
    permission: NetworkPermission,
    onDone: () -> Unit,
    onScan: () -> Unit,
    onAdd: (String, () -> Unit) -> Unit,
    onDemo: () -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String, Int) -> Unit,
    onForget: (String) -> Unit,
    onShareDiagnostics: (String) -> Unit,
) = Page {
    val c = LocalAirColors.current
    var renaming by remember { mutableStateOf<UnitUi?>(null) }
    var forgetting by remember { mutableStateOf<UnitUi?>(null) }

    // Back returns to the unit screen, like Done. Without units this is the home screen, and
    // back leaves the app. The dialogs below are windows of their own and handle back first.
    BackHandler(enabled = state.units.isNotEmpty(), onBack = onDone)

    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.manage), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
        if (state.units.isNotEmpty()) Pill(stringResource(R.string.done), onDone)
    }

    if (!state.onLocalNetwork) Card(accent = c.heat) { Hint(stringResource(R.string.home_network_only)) }
    // Without the local network permission there is nothing to search or add by address;
    // the units already known stay listed, and the demo unit works as ever.
    if (!state.networkAllowed) NetworkPermissionCard(permission)

    if (state.networkAllowed || state.units.isNotEmpty()) Card {
        if (state.units.isEmpty()) Hint(stringResource(R.string.no_units))
        state.units.forEachIndexed { i, u ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            val name = displayName(state, u)
            // A demo unit has no address; say what it is instead.
            val detail = (if (u.stored.demo) stringResource(R.string.demo_badge) else u.stored.host) + " · " + stringResource(u.availability)
            val mac = if (u.stored.demo) null else stringResource(R.string.unit_mac, u.stored.macLabel)
            val actions = listOf(stringResource(R.string.rename), stringResource(R.string.forget))
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Hint(detail)
                    }
                    // The tiles on the unit screen follow this order.
                    if (state.units.size > 1) {
                        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RoundButton(AirIcons.up, stringResource(R.string.move_up, name), { onMove(u.stored.airconId, -1) }, 40.dp, enabled = i > 0)
                            RoundButton(AirIcons.down, stringResource(R.string.move_down, name), { onMove(u.stored.airconId, 1) }, 40.dp, enabled = i < state.units.lastIndex)
                        }
                    }
                }
                // Full width, below the arrows: a MAC address does not break nicely.
                if (mac != null) Hint(mac)
                FittingGrid(actions, maxColumns = 2, chrome = CHIP_PADDING * 2 + 2.dp, Modifier.padding(top = 10.dp)) { b, modifier ->
                    if (b == 0) Pill(actions[0], { renaming = u }, modifier, horizontalPadding = CHIP_PADDING)
                    else Pill(actions[1], { forgetting = u }, modifier, textColor = c.danger, horizontalPadding = CHIP_PADDING)
                }
                // Needed rarely, so a quiet link rather than a third button.
                Text(
                    stringResource(R.string.share_diagnostics),
                    color = c.muted,
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onShareDiagnostics(u.stored.airconId) }
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                )
            }
        }
        if (state.networkAllowed) {
            Pill(
                stringResource(R.string.search_network),
                onScan,
                Modifier.padding(top = 12.dp),
                enabled = !state.scanning,
                filled = c.text,
                textColor = c.bg,
            )
        }
    }

    if (state.units.none { it.stored.demo }) {
        Card {
            Heading(stringResource(R.string.demo_title))
            Hint(stringResource(R.string.demo_hint))
            Pill(stringResource(R.string.try_demo), onDemo, Modifier.padding(top = 12.dp))
        }
    }

    if (state.networkAllowed) Card {
        var host by remember { mutableStateOf("") }
        Heading(stringResource(R.string.add_by_address))
        Hint(stringResource(R.string.add_by_address_hint))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd(host) { host = "" } }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.add), { onAdd(host) { host = "" } }, enabled = host.isNotBlank() && !state.busy)
        }
    }

    StatusLine(state.status)

    renaming?.let { u ->
        RenameDialog(u.stored.name ?: "", onDismiss = { renaming = null }) {
            renaming = null
            onRename(u.stored.airconId, it)
        }
    }
    forgetting?.let { u ->
        val name = displayName(state, u)
        AlertDialog(
            onDismissRequest = { forgetting = null },
            text = { Text(stringResource(if (u.stored.demo) R.string.forget_demo_confirm else R.string.forget_confirm, name)) },
            confirmButton = {
                TextButton({ forgetting = null; onForget(u.stored.airconId) }) { Text(stringResource(R.string.forget), color = c.danger) }
            },
            dismissButton = { TextButton({ forgetting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- dialogs and gates ----------

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = { Text(stringResource(R.string.name_placeholder)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton({ onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ExactAlarmDialog(onAllow: () -> Unit, onSkip: () -> Unit) = AlertDialog(
    onDismissRequest = onSkip,
    title = { Text(stringResource(R.string.exact_alarm_title)) },
    text = { Text(stringResource(R.string.exact_alarm_text)) },
    confirmButton = { TextButton(onAllow) { Text(stringResource(R.string.exact_alarm_allow)) } },
    dismissButton = { TextButton(onSkip) { Text(stringResource(R.string.exact_alarm_skip)) } },
)

/** Asking for the local network permission: [denied] once the user said no to the prompt. */
class NetworkPermission(val denied: Boolean, val onAllow: () -> Unit, val onSettings: () -> Unit)

/** Shown where real units would be while the app may not use the local network (Android 17+). */
@Composable
private fun NetworkPermissionCard(permission: NetworkPermission) {
    val c = LocalAirColors.current
    Card(accent = c.cool) {
        Heading(stringResource(R.string.permission_title))
        Hint(stringResource(R.string.permission_text))
        if (permission.denied) Text(stringResource(R.string.permission_denied), color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.permission_allow), permission.onAllow, filled = c.cool)
            if (permission.denied) Pill(stringResource(R.string.permission_settings), permission.onSettings)
        }
    }
}
