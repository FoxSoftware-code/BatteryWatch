package pl.foxsoftware.batterywatch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.foxsoftware.batterywatch.R
import pl.foxsoftware.batterywatch.data.ChargeSession
import pl.foxsoftware.batterywatch.data.ChargerInfo
import pl.foxsoftware.batterywatch.domain.ChargeNote
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChargingScreen(vm: MainViewModel) {
    val state by vm.charging.collectAsState()
    val active by vm.chargingActive.collectAsState()
    val settings by vm.chargingSettings.collectAsState()
    val ctx = LocalContext.current

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotifications() {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        ensureNotifications()
        vm.refreshCharging(full = true)
        while (true) {
            delay(ChargingMonitorInterval)
            vm.refreshCharging()
        }
    }
    // Po starcie/zatrzymaniu usługi przeładuj od razu
    LaunchedEffect(active) { vm.refreshCharging(full = active == null) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        MonitorCard(
            charger = state.charger,
            active = active != null,
            activeSince = state.sessions.firstOrNull { it.id == active }?.startTs,
            autoMonitor = settings.autoMonitor,
            autoStartFailedAt = settings.autoStartFailedAt,
            onStart = { ensureNotifications(); vm.startChargingMonitor() },
            onStop = vm::stopChargingMonitor,
            onAuto = vm::setAutoMonitor,
        )

        val off = stringResource(R.string.off)
        SectionTitle(stringResource(R.string.ch_alerts))
        Text(stringResource(R.string.ch_notify_at), style = MaterialTheme.typography.labelMedium)
        ChipRow(listOf(0 to off, 80 to "80%", 85 to "85%", 90 to "90%", 100 to "100%"), settings.targetLevel, vm::setTargetLevel)
        Text(stringResource(R.string.ch_temp_alarm), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        ChipRow(listOf(0 to off, 40 to "40 °C", 42 to "42 °C", 45 to "45 °C"), settings.tempAlarmC, vm::setTempAlarm)

        state.charger?.let { BatteryInfoCard(it, state.avgCapacityMah, state.capacitySessions) }

        val d = state.detail
        if (d == null) {
            SectionTitle(stringResource(R.string.ch_sessions))
            Text(stringResource(R.string.ch_no_sessions), style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val s = d.summary
        SectionTitle(
            if (d.session.active) stringResource(R.string.ch_current)
            else stringResource(R.string.ch_session_from, dateTime(d.session.startTs)),
        )
        if (s == null) {
            Text(stringResource(R.string.ch_collecting), style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    stringResource(R.string.ch_level), "${s.startLevel}→${s.endLevel}%", Modifier.weight(1f),
                    duration(s.durationMs) + (plugLabel(d.session.plug)?.let { " · $it" } ?: ""),
                )
                StatTile(
                    stringResource(R.string.ch_rate),
                    s.pctPerHour?.let { "%.0f %%/h".format(it) } ?: "–",
                    Modifier.weight(1f),
                    s.etaFullMs?.let { stringResource(R.string.ch_eta_full, duration(it)) }
                        ?: s.timeTo80Ms?.let { stringResource(R.string.ch_to80, duration(it)) },
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    stringResource(R.string.ch_added),
                    "%.0f mAh".format(s.mahAdded),
                    Modifier.weight(1f),
                    s.estimatedCapacityMah?.let { stringResource(R.string.ch_capacity_sub, it) }
                        ?: stringResource(if (s.mahFromCounter) R.string.ch_counter else R.string.ch_from_current),
                )
                StatTile(
                    stringResource(R.string.ch_power), "%.1f W".format(s.peakPowerW), Modifier.weight(1f),
                    stringResource(R.string.ch_power_sub, s.avgPowerW, s.peakCurrentMa),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    stringResource(R.string.temperature),
                    "%.1f °C".format(s.maxTempC),
                    Modifier.weight(1f),
                    stringResource(R.string.ch_temp_sub, s.avgTempC) +
                        if (s.minutesAbove40C >= 1) " · >40 °C: %.0f min".format(s.minutesAbove40C) else "",
                )
                StatTile(
                    stringResource(R.string.screen_on),
                    "%.0f%%".format(s.screenOnFraction * 100),
                    Modifier.weight(1f),
                    if (s.minutesAtFull >= 1) stringResource(R.string.ch_at_full, duration((s.minutesAtFull * 60_000).toLong()))
                    else stringResource(R.string.ch_of_charging_time),
                )
            }
            d.notes.forEach { NoteCard(it) }
        }

        if (d.samples.size >= 2) {
            val down = { v: List<Float> -> ChargingAnalyzer.downsample(v) }
            SectionTitle(stringResource(R.string.chart_level))
            LineChart(down(d.samples.map { it.levelPct.toFloat() }), maxY = 100f, caption = duration(d.samples.last().timestamp - d.samples.first().timestamp))
            SectionTitle(stringResource(R.string.chart_current_bg))
            LineChart(
                down(d.samples.map { it.currentMa.toFloat() }),
                minY = 0f,
                shade = down(d.samples.map { if (it.screenOn) 1f else 0f }),
            )
            SectionTitle(stringResource(R.string.chart_power))
            LineChart(down(d.samples.map { it.powerW.toFloat() }), minY = 0f)
            SectionTitle(stringResource(R.string.chart_temperature))
            LineChart(down(d.samples.map { it.temperatureC.toFloat() }))
            SectionTitle(stringResource(R.string.chart_voltage))
            LineChart(down(d.samples.map { it.voltageMv.toFloat() }))
        }

        SectionTitle(stringResource(R.string.ch_history))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                state.sessions.forEach { session ->
                    SessionRow(session, selected = session.id == d.session.id, onClick = { vm.selectChargingSession(session.id) })
                }
            }
        }
        if (!d.session.active) {
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { vm.selectChargingSession(null) }) { Text(stringResource(R.string.ch_show_latest)) }
                TextButton(onClick = { vm.deleteChargingSession(d.session.id) }) { Text(stringResource(R.string.ch_delete_session)) }
            }
        }
    }
}

private const val ChargingMonitorInterval = 10_000L

@Composable
private fun MonitorCard(
    charger: ChargerInfo?,
    active: Boolean,
    activeSince: Long?,
    autoMonitor: Boolean,
    autoStartFailedAt: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAuto: (Boolean) -> Unit,
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp)) {
            val title = when {
                active -> activeSince?.let { stringResource(R.string.ch_monitor_active_since, time(it)) }
                    ?: stringResource(R.string.ch_monitor_active)
                charger?.connected == true -> stringResource(R.string.ch_plugged_monitor_off, plugLabel(charger.plugged).orEmpty())
                else -> stringResource(R.string.ch_unplugged)
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    active -> stringResource(R.string.ch_active_desc)
                    charger?.connected == true -> stringResource(R.string.ch_battery_status, stringResource(charger.statusLabel))
                    autoMonitor -> stringResource(R.string.ch_auto_desc)
                    else -> stringResource(R.string.ch_manual_desc)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                } else {
                    Button(onClick = onStart, enabled = charger?.connected == true) { Text(stringResource(R.string.ch_monitor_now)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ch_auto_start), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoMonitor, onCheckedChange = onAuto)
            }
            if (autoMonitor && autoStartFailedAt > 0 && !active) {
                Text(
                    stringResource(R.string.ch_auto_blocked, dateTime(autoStartFailedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BatteryInfoCard(c: ChargerInfo, avgCapacity: Int?, capacitySessions: Int) {
    SectionTitle(stringResource(R.string.ch_battery_charger))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            KeyValueRow(
                stringResource(R.string.ch_power_source),
                plugLabel(c.plugged) ?: stringResource(R.string.none),
                stringResource(c.statusLabel),
            )
            c.maxChargerW?.let {
                KeyValueRow(stringResource(R.string.ch_charger_declared), "%.1f W".format(it), stringResource(R.string.ch_charger_declared_sub))
            }
            KeyValueRow(stringResource(R.string.ch_health), stringResource(c.health), c.technology)
            c.stateOfHealthPct?.let { KeyValueRow("State of Health", "$it%") }
            c.cycleCount?.let { KeyValueRow(stringResource(R.string.ch_cycles), "$it") }
            avgCapacity?.let {
                KeyValueRow(stringResource(R.string.ch_est_capacity), "$it mAh", pluralStringResource(R.plurals.ch_est_capacity_sub, capacitySessions, capacitySessions))
            }
        }
    }
}

@Composable
private fun NoteCard(n: ChargeNote) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(n.title.asString(), style = MaterialTheme.typography.titleSmall, color = severityColor(n.severity))
            Text(n.detail.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionRow(s: ChargeSession, selected: Boolean, onClick: () -> Unit) {
    val levels = if (s.startLevel != null && s.endLevel != null) "${s.startLevel}→${s.endLevel}%" else "–"
    val dur = duration((s.endTs ?: System.currentTimeMillis()) - s.startTs)
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (s.active) stringResource(R.string.ch_ongoing, dateTime(s.startTs)) else dateTime(s.startTs),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            Text(
                "$levels · $dur" + (plugLabel(s.plug)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            s.maxTempC?.let { Text("%.1f °C".format(it), style = MaterialTheme.typography.bodyMedium) }
            s.peakMa?.let { Text("$it mA", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

private fun format(skeleton: String, ts: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ts))
}

private fun time(ts: Long) = format("jm", ts)
private fun dateTime(ts: Long) = format("ddMMjm", ts)
