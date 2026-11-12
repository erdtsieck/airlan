package io.github.erdtsieck.airlan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.data.Schedule
import io.github.erdtsieck.airlan.data.ScheduleRule
import io.github.erdtsieck.airlan.data.Setting
import io.github.erdtsieck.airlan.data.TimerAction
import io.github.erdtsieck.airlan.data.weekOn
import io.github.erdtsieck.airlan.ui.MainViewModel.Companion.TEMP_MAX
import io.github.erdtsieck.airlan.ui.MainViewModel.Companion.TEMP_MIN
import io.github.erdtsieck.airlan.wfrac.Mode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** A switch that worked this much after its time is reported as late on the schedule. */
private const val LATE_MS = 2 * 60_000L

@Composable
private fun locale(): Locale = LocalConfiguration.current.locales[0]

private fun timeOfDay(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** When the schedule switches next, as people say it: "Tomorrow 06:45 on". */
@Composable
private fun nextText(schedule: Schedule): String {
    val run = schedule.run ?: return stringResource(R.string.schedule_nothing_planned)
    val action = schedule.rules.firstOrNull { it.id == run.ruleId }?.action ?: TimerAction.ON
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(run.at).atZone(zone)
    val today = LocalDate.now(zone)
    val time = clockTime(run.at)
    val on = action == TimerAction.ON
    return when (at.toLocalDate()) {
        today -> stringResource(if (on) R.string.next_on_today else R.string.next_off_today, time)
        today.plusDays(1) -> stringResource(if (on) R.string.next_on_tomorrow else R.string.next_off_tomorrow, time)
        else -> {
            val locale = locale()
            val day = at.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
            stringResource(if (on) R.string.next_on_day else R.string.next_off_day, day, time)
        }
    }
}

/** The days of a rule in a few words: "weekdays", "Sat, Sun". */
@Composable
private fun daysText(days: Set<DayOfWeek>): String {
    val locale = locale()
    return when {
        days.isEmpty() -> stringResource(R.string.once)
        days.size == 7 -> stringResource(R.string.every_day)
        days == WEEKDAYS -> stringResource(R.string.weekdays)
        days == WEEKEND -> stringResource(R.string.weekend)
        else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

@Composable
private fun settingText(setting: Setting?): String {
    if (setting == null) return stringResource(R.string.last_setting)
    val mode = modeName(setting.mode).lowercase(locale())
    if (setting.mode == Mode.FAN) return mode
    return stringResource(R.string.setting_summary, mode, String.format(locale(), "%.1f", setting.presetTemp))
}

/**
 * The schedule on the unit screen: its next switch, and the week as seven thin bars, the
 * stretches the unit is on in the colour of their mode. All of it opens the schedule.
 */
@Composable
internal fun ScheduleSummary(schedule: Schedule, onOpen: () -> Unit) {
    val c = LocalAirColors.current
    // The ripple's rounded shape would clip the strip's last row: it gets a little room.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen).padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Heading(stringResource(R.string.schedule))
                if (schedule.rules.isEmpty()) {
                    Hint(stringResource(R.string.schedule_empty_hint))
                } else {
                    Text(nextText(schedule), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text)
                    schedule.pausedUntil?.let { Hint(stringResource(R.string.paused_until, dateText(it))) }
                }
            }
            Icon(AirIcons.forward, null, tint = c.muted, modifier = Modifier.size(22.dp))
        }
        WeekStrip(schedule.rules, Modifier.padding(top = 10.dp))
    }
}

/**
 * The week, Monday to Sunday, one bar per day: when the repeating rules keep the unit on
 * (see [weekOn]), in the colour of the mode it is switched on in, or the text colour for
 * its last setting. Today's label stands out. Nothing at all when the schedule never
 * switches on. It only illustrates the list beside it, so screen readers skip it.
 */
@Composable
private fun WeekStrip(rules: List<ScheduleRule>, modifier: Modifier = Modifier) {
    val stretches = weekOn(rules)
    if (stretches.isEmpty()) return
    val c = LocalAirColors.current
    val today = LocalDate.now().dayOfWeek
    Column(modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (day in DayOfWeek.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale()),
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                    color = if (day == today) c.text else c.muted,
                    modifier = Modifier.width(14.dp),
                )
                BoxWithConstraints(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line.copy(alpha = 0.6f))) {
                    for (s in stretches.filter { it.day == day }) {
                        val start = maxWidth * (s.fromMinute / ScheduleRule.MINUTES_PER_DAY.toFloat())
                        val width = maxWidth * ((s.toMinute - s.fromMinute) / ScheduleRule.MINUTES_PER_DAY.toFloat())
                        Box(
                            Modifier.offset(x = start).width(width).height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(c.of(s.setting?.mode)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()))

/**
 * A unit's schedule: when it switches next (with a way to skip that once), how the last
 * switch went when it was late or failed, the rules, and a pause for holidays. A rule is
 * added or changed in a sheet over this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    state: UiState,
    unit: UnitUi,
    onBack: () -> Unit,
    newRuleId: () -> String,
    onSave: (ScheduleRule) -> Unit,
    onDelete: (String) -> Unit,
    onEnable: (String, Boolean) -> Unit,
    onSkip: () -> Unit,
    onUnskip: () -> Unit,
    onPause: (LocalDate?) -> Unit,
) = Page {
    BackHandler(onBack = onBack)
    val c = LocalAirColors.current
    val schedule = unit.stored.schedule
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    var pausing by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
        RoundButton(AirIcons.back, stringResource(R.string.back), onBack, 44.dp, enabled = true)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.schedule), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
            Hint(displayNameOf(state, unit))
        }
    }

    if (!state.onLocalNetwork) Card(accent = c.heat) { Hint(stringResource(R.string.home_network_only)) }

    if (schedule.rules.isNotEmpty()) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nextText(schedule), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text, modifier = Modifier.weight(1f))
                if (schedule.run != null) {
                    Spacer(Modifier.width(12.dp))
                    Pill(stringResource(R.string.skip), onSkip)
                }
            }
            schedule.skipAt?.let { skipped ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Hint(stringResource(R.string.skipped, clockTime(skipped)), Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Pill(stringResource(R.string.unskip), onUnskip)
                }
            }
            LastOutcome(schedule)
            WeekStrip(schedule.rules, Modifier.padding(top = 14.dp))
        }

        Card {
            schedule.rules.forEachIndexed { i, rule ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                RuleRow(rule, onClick = { editing = rule }, onEnable = { onEnable(rule.id, it) })
            }
        }
    }

    Pill(
        stringResource(R.string.add_rule),
        { editing = ScheduleRule(newRuleId(), TimerAction.ON, 7 * 60, WEEKDAYS) },
        Modifier.fillMaxWidth(),
        filled = c.text,
        textColor = c.bg,
        icon = AirIcons.plus,
    )

    Card(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val paused = schedule.pausedUntil
            Column(Modifier.weight(1f)) {
                if (paused != null) {
                    Text(stringResource(R.string.paused_until, dateText(paused)), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                } else {
                    Heading(stringResource(R.string.pause_title))
                    Hint(stringResource(R.string.pause_hint))
                }
            }
            Spacer(Modifier.width(12.dp))
            if (paused != null) Pill(stringResource(R.string.resume), { onPause(null) }) else Pill(stringResource(R.string.pause), { pausing = true })
        }
    }

    Hint(stringResource(R.string.schedule_phone_hint), Modifier.padding(horizontal = 4.dp))

    editing?.let { rule ->
        RuleSheet(
            initial = rule,
            isNew = schedule.rules.none { it.id == rule.id },
            // A new choice starts from how the unit runs now.
            defaultSetting = unit.state?.let { s -> s.mode?.let { Setting(it, s.presetTemp) } } ?: Setting(Mode.HEAT, 21.0),
            onDismiss = { editing = null },
            onSave = {
                editing = null
                onSave(it)
            },
            onDelete = {
                editing = null
                onDelete(rule.id)
            },
        )
    }

    if (pausing) PauseDialog(onDismiss = { pausing = false }) { date ->
        pausing = false
        onPause(date)
    }
}

@Composable
private fun displayNameOf(state: UiState, unit: UnitUi) =
    unit.stored.name ?: stringResource(R.string.new_unit, state.unnamedIndex(unit))

/** How the last switch went, when that is worth saying: it was late, or it did not happen. Only for a day. */
@Composable
private fun LastOutcome(schedule: Schedule) {
    val last = schedule.last ?: return
    if (System.currentTimeMillis() - last.at > 24 * 3_600_000L) return
    val on = last.action == TimerAction.ON
    val text = when (val done = last.doneAt) {
        null -> stringResource(if (on) R.string.last_on_missed else R.string.last_off_missed, clockTime(last.at))
        else -> if (done - last.at < LATE_MS) return else stringResource(if (on) R.string.last_on_late else R.string.last_off_late, clockTime(last.at), clockTime(done))
    }
    val c = LocalAirColors.current
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(c.line))
    Hint(text, Modifier.padding(top = 10.dp))
}

/** One rule: its time large, what it does and on which days, and a switch to turn it off for a while. */
@Composable
private fun RuleRow(rule: ScheduleRule, onClick: () -> Unit, onEnable: (Boolean) -> Unit) {
    val c = LocalAirColors.current
    val on = rule.action == TimerAction.ON
    val what = if (on) stringResource(R.string.tag_on) + " · " + settingText(rule.setting) else stringResource(R.string.tag_off)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).alpha(if (rule.enabled) 1f else 0.55f), verticalAlignment = Alignment.CenterVertically) {
            // A 12-hour time ("10:30 PM") is wider than "22:30": never wrapped, at least as wide as the widest 24-hour one.
            Text(
                timeOfDay(rule.minuteOfDay), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 84.dp).padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) c.of(rule.setting?.mode) else c.off))
                    Spacer(Modifier.width(6.dp))
                    Text(what, fontSize = 14.sp, color = c.text)
                }
                val days = daysText(rule.days)
                Hint(if (rule.enabled) days else stringResource(R.string.rule_disabled, days))
            }
        }
        val label = stringResource(R.string.rule_active)
        Switch(
            checked = rule.enabled,
            onCheckedChange = onEnable,
            modifier = Modifier.semantics { contentDescription = label },
            colors = SwitchDefaults.colors(checkedTrackColor = c.text, checkedThumbColor = c.card, checkedBorderColor = c.text),
        )
    }
}

/** A date to pause the schedule up to and including; only today and later can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseDialog(onDismiss: () -> Unit, onPause: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = today.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPause(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.pause)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state)
    }
}

/**
 * Adding or changing a rule: switch on or off, at what time, on which days (none: once),
 * and for a switch-on whether the unit comes back as it was or in a chosen mode and temperature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSheet(
    initial: ScheduleRule,
    isNew: Boolean,
    defaultSetting: Setting,
    onDismiss: () -> Unit,
    onSave: (ScheduleRule) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAirColors.current
    var action by remember { mutableStateOf(initial.action) }
    var minuteOfDay by remember { mutableIntStateOf(initial.minuteOfDay) }
    var days by remember { mutableStateOf(initial.days) }
    var chosen by remember { mutableStateOf(initial.setting != null) }
    var setting by remember { mutableStateOf(initial.setting ?: defaultSetting) }
    var pickingTime by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 18.dp).navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    stringResource(if (isNew) R.string.new_rule else R.string.edit_rule),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f),
                )
                if (!isNew) TextButton(onDelete) { Text(stringResource(R.string.delete), color = c.danger) }
            }

            Segmented(
                options = listOf(stringResource(R.string.turn_on), stringResource(R.string.turn_off)),
                selected = if (action == TimerAction.ON) 0 else 1,
                onSelect = { action = if (it == 0) TimerAction.ON else TimerAction.OFF },
            )

            Heading(stringResource(R.string.rule_at))
            Box(Modifier.padding(top = 0.dp, bottom = 18.dp)) {
                Pill(timeOfDay(minuteOfDay), { pickingTime = true }, horizontalPadding = 18.dp)
            }

            Heading(stringResource(R.string.rule_days))
            DayPicker(days) { days = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pill(stringResource(R.string.preset_weekdays), { days = WEEKDAYS }, verticalPadding = 7.dp, filled = if (days == WEEKDAYS) c.line else null, textColor = c.text)
                Pill(stringResource(R.string.preset_weekend), { days = WEEKEND }, verticalPadding = 7.dp, filled = if (days == WEEKEND) c.line else null, textColor = c.text)
                Pill(stringResource(R.string.preset_every_day), { days = DayOfWeek.entries.toSet() }, verticalPadding = 7.dp, filled = if (days.size == 7) c.line else null, textColor = c.text)
            }
            if (days.isEmpty()) Hint(stringResource(R.string.once_hint, timeOfDay(minuteOfDay)), Modifier.padding(top = 8.dp))

            if (action == TimerAction.ON) {
                Spacer(Modifier.height(18.dp))
                Heading(stringResource(R.string.setting))
                Column(Modifier.selectableGroup()) {
                    Choice(stringResource(R.string.setting_last), stringResource(R.string.setting_last_hint), selected = !chosen) { chosen = false }
                    Choice(stringResource(R.string.setting_choose), null, selected = chosen) { chosen = true }
                }
                if (chosen) {
                    ModeSelector(setting.mode, on = true, enabled = true, onMode = { setting = setting.copy(mode = it) }, Modifier.padding(top = 6.dp))
                    if (setting.mode != Mode.FAN) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundButton(null, stringResource(R.string.colder), { setting = setting.copy(presetTemp = (setting.presetTemp - 0.5).coerceAtLeast(TEMP_MIN)) }, 48.dp, enabled = setting.presetTemp > TEMP_MIN, text = "−")
                            Text(
                                String.format(locale(), "%.1f", setting.presetTemp) + "°",
                                fontSize = 36.sp, fontWeight = FontWeight.Light, color = c.of(setting.mode),
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                            RoundButton(null, stringResource(R.string.warmer), { setting = setting.copy(presetTemp = (setting.presetTemp + 0.5).coerceAtMost(TEMP_MAX)) }, 48.dp, enabled = setting.presetTemp < TEMP_MAX, text = "+")
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
                Pill(
                    stringResource(R.string.save),
                    {
                        onSave(
                            initial.copy(
                                action = action,
                                minuteOfDay = minuteOfDay,
                                days = days,
                                setting = if (action == TimerAction.ON && chosen) setting else null,
                                enabled = true,
                            ),
                        )
                    },
                    Modifier.weight(1f),
                    filled = c.text,
                    textColor = c.bg,
                )
            }
        }
    }

    if (pickingTime) {
        TimeDialog(minuteOfDay / 60, minuteOfDay % 60, onDismiss = { pickingTime = false }) { h, m ->
            pickingTime = false
            minuteOfDay = h * 60 + m
        }
    }
}

/** Two or more options side by side, one of them chosen. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalAirColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp).clip(shape).border(1.dp, c.line, shape).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) c.text else Color.Transparent)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) c.bg else c.text, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The seven days as round toggles, in the order of the week, named in full for screen readers. */
@Composable
private fun DayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val c = LocalAirColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (day in DayOfWeek.entries) {
            val on = day in days
            val name = day.getDisplayName(TextStyle.FULL, locale())
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (on) c.text else Color.Transparent)
                    .border(1.dp, if (on) c.text else c.line, CircleShape)
                    .toggleable(on, role = Role.Checkbox) { onChange(if (it) days + day else days - day) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Text(day.getDisplayName(TextStyle.NARROW, locale()), color = if (on) c.bg else c.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }
    }
}

/** A radio option with an optional second line. */
@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    val c = LocalAirColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(if (selected) 6.dp else 2.dp, if (selected) c.text else c.off, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, color = c.text)
            subtitle?.let { Hint(it) }
        }
    }
}
