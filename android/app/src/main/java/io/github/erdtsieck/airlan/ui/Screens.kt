package io.github.erdtsieck.airlan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.ui.MainViewModel.Companion.TEMP_MAX
import io.github.erdtsieck.airlan.ui.MainViewModel.Companion.TEMP_MIN
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

/**
 * A block of text ([start]) with buttons beside it when the text fits next to them, or the
 * buttons below it (as a [FittingGrid]) when not. Decided by measuring [startTexts], each with
 * the style it is shown in, and [buttons]; [buttonChrome] is the width a button adds around
 * its label.
 */
@Composable
private fun SideBySideOrStacked(
    modifier: Modifier,
    startTexts: List<Pair<String, TextStyle>>,
    buttons: List<String>,
    buttonChrome: Dp,
    start: @Composable () -> Unit,
    button: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val buttonStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium)
    fun width(text: String, style: TextStyle) = with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp() }
    val startNeeds = startTexts.maxOf { (text, style) -> width(text, style) } + 2.dp
    val buttonsNeed = buttons.fold(0.dp) { sum, label -> sum + width(label, buttonStyle) + buttonChrome + GAP } + 2.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (startNeeds + buttonsNeed <= maxWidth) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Column(Modifier.weight(1f)) { start() }
                buttons.indices.forEach { button(it, Modifier) }
            }
        } else {
            Column {
                start()
                FittingGrid(buttons, maxColumns = buttons.size, chrome = buttonChrome, Modifier.padding(top = 10.dp)) { i, m -> button(i, m) }
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
private fun Card(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAirColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .border(BorderStroke(1.dp, accent ?: c.line), RoundedCornerShape(20.dp))
            .padding(20.dp),
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
) {
    val c = LocalAirColors.current
    Row(
        modifier
            .clip(shape)
            .background(filled ?: c.card)
            .border(1.dp, filled ?: c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 11.dp),
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
private fun Heading(text: String) =
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

    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.units.size > 1) {
                for (u in state.units) {
                    val selected = u.stored.airconId == unit.stored.airconId
                    val dot = when {
                        u.state?.power != true -> c.off
                        u.state.mode == Mode.HEAT -> c.heat
                        u.state.mode == Mode.COOL -> c.cool
                        else -> c.off
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.card)
                            .border(1.dp, if (selected) c.text else c.line, RoundedCornerShape(50))
                            .clickable(role = Role.Tab) { onSelect(u.stored.airconId) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(8.dp))
                        Text(displayName(state, u), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = c.text)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(AirIcons.gear, stringResource(R.string.manage), onManage, 40.dp, enabled = true)
    }

    if (!state.onLocalNetwork) Card(accent = c.heat) { Hint(stringResource(R.string.home_network_only)) }
    if (unit.needsPermission) NetworkPermissionCard(permission)

    if (unit.stored.name == null) {
        var name by remember(unit.stored.airconId) { mutableStateOf("") }
        Card(accent = c.cool) {
            Heading(stringResource(R.string.name_this_unit))
            Hint(stringResource(R.string.name_hint))
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

    val accent = when {
        s?.power != true -> c.off
        s.mode == Mode.COOL -> c.cool
        s.mode == Mode.HEAT -> c.heat
        else -> c.off
    }
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
                56.dp,
                enabled = !disabled,
                filled = if (s?.power == true) accent else c.line,
            )
        }

        val temp = state.pendingTemp ?: s?.presetTemp
        Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(null, stringResource(R.string.colder), { onNudge(-0.5) }, 64.dp, enabled = !disabled && (temp ?: 0.0) > TEMP_MIN, text = "−")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s == null) "–" else fmt(temp) + "°", fontSize = 60.sp, fontWeight = FontWeight.Light, color = accent)
                Text(stringResource(if (s?.power == true) R.string.set else R.string.off), color = c.muted, fontSize = 13.sp)
            }
            RoundButton(null, stringResource(R.string.warmer), { onNudge(0.5) }, 64.dp, enabled = !disabled && (temp ?: 99.0) < TEMP_MAX, text = "+")
        }

        val modes = listOf(Triple(Mode.COOL, AirIcons.cool, c.cool), Triple(Mode.HEAT, AirIcons.heat, c.heat))
        val modeLabels = modes.map { modeName(it.first) }
        // Padding, icon and the gap after it, border: see Pill.
        FittingGrid(modeLabels, maxColumns = 2, chrome = 14.dp * 2 + 20.dp + 8.dp + 2.dp, Modifier.padding(top = 12.dp)) { i, modifier ->
            val (mode, icon, color) = modes[i]
            Pill(
                modeLabels[i],
                { if (s?.mode != mode) onMode(mode) },
                modifier,
                enabled = !disabled,
                filled = if (s?.mode == mode) color else null,
                icon = icon,
                shape = RoundedCornerShape(14.dp),
            )
        }
        if (s != null && s.mode != Mode.COOL && s.mode != Mode.HEAT) {
            Text(stringResource(R.string.current_mode, modeName(s.mode)), color = c.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
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
                    Pill(labels[i], { onTimer(TIMER_MINUTES[i]) }, modifier, enabled = !disabled, horizontalPadding = CHIP_PADDING)
                }
            }
            else -> Hint(stringResource(R.string.turn_on_for_timer))
        }
    }

    StatusLine(state.status ?: unit.error?.let { Status(it, isError = true) })

    if (renaming) {
        RenameDialog(unit.stored.name ?: "", onDismiss = { renaming = false }) {
            renaming = false
            onRename(unit.stored.airconId, it)
        }
    }
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
    onForget: (String) -> Unit,
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
            val actions = listOf(stringResource(R.string.rename), stringResource(R.string.forget))
            // Name and buttons side by side when both fit; with a long name, a large font or a
            // long translation the buttons go below the name instead of squeezing it.
            val body = LocalTextStyle.current
            SideBySideOrStacked(
                Modifier.padding(vertical = 12.dp),
                // As shown below: the name in the body style, the detail as a Hint.
                startTexts = listOf(name to body.copy(fontWeight = FontWeight.SemiBold), detail to body.copy(fontSize = 14.sp)),
                buttons = actions,
                buttonChrome = 14.dp * 2 + 2.dp,
                start = {
                    Text(name, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(detail)
                },
                button = { i, modifier ->
                    if (i == 0) Pill(actions[0], { renaming = u }, modifier)
                    else Pill(actions[1], { forgetting = u }, modifier, textColor = c.danger)
                },
            )
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
