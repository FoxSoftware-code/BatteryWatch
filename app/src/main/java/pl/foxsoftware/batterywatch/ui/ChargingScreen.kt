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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.foxsoftware.batterywatch.data.ChargeSession
import pl.foxsoftware.batterywatch.data.ChargerInfo
import pl.foxsoftware.batterywatch.domain.ChargeNote
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer
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

        SectionTitle("Alerty")
        Text("Powiadom po naładowaniu do", style = MaterialTheme.typography.labelMedium)
        ChipRow(listOf(0 to "wył.", 80 to "80%", 85 to "85%", 90 to "90%", 100 to "100%"), settings.targetLevel, vm::setTargetLevel)
        Text("Alarm temperatury", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        ChipRow(listOf(0 to "wył.", 40 to "40 °C", 42 to "42 °C", 45 to "45 °C"), settings.tempAlarmC, vm::setTempAlarm)

        state.charger?.let { BatteryInfoCard(it, state.avgCapacityMah, state.capacitySessions) }

        val d = state.detail
        if (d == null) {
            SectionTitle("Sesje ładowania")
            Text(
                "Brak zapisanych sesji. Podłącz ładowarkę – monitor zapisze przebieg co 10 s.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        val s = d.summary
        SectionTitle(if (d.session.active) "Bieżące ładowanie" else "Sesja z ${dateTime(d.session.startTs)}")
        if (s == null) {
            Text("Zbieranie danych…", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Poziom", "${s.startLevel}→${s.endLevel}%", Modifier.weight(1f), duration(s.durationMs) + (d.session.plug?.let { " · $it" } ?: ""))
                StatTile(
                    "Tempo",
                    s.pctPerHour?.let { "%.0f %%/h".format(it) } ?: "–",
                    Modifier.weight(1f),
                    s.etaFullMs?.let { "do 100%: ~${duration(it)}" } ?: s.timeTo80Ms?.let { "do 80% w ${duration(it)}" },
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    "Dodano",
                    "%.0f mAh".format(s.mahAdded),
                    Modifier.weight(1f),
                    s.estimatedCapacityMah?.let { "pojemność ~$it mAh" } ?: if (s.mahFromCounter) "licznik ładunku" else "z prądu",
                )
                StatTile("Moc", "%.1f W".format(s.peakPowerW), Modifier.weight(1f), "szczyt · śr. %.1f W · %d mA".format(s.avgPowerW, s.peakCurrentMa))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    "Temperatura",
                    "%.1f °C".format(s.maxTempC),
                    Modifier.weight(1f),
                    "maks. · śr. %.1f °C".format(s.avgTempC) + if (s.minutesAbove40C >= 1) " · >40 °C: %.0f min".format(s.minutesAbove40C) else "",
                )
                StatTile(
                    "Ekran włączony",
                    "%.0f%%".format(s.screenOnFraction * 100),
                    Modifier.weight(1f),
                    if (s.minutesAtFull >= 1) "na 100%%: %s".format(duration((s.minutesAtFull * 60_000).toLong())) else "czasu ładowania",
                )
            }
            d.notes.forEach { NoteCard(it) }
        }

        if (d.samples.size >= 2) {
            val down = { v: List<Float> -> ChargingAnalyzer.downsample(v) }
            SectionTitle("Poziom baterii [%]")
            LineChart(down(d.samples.map { it.levelPct.toFloat() }), maxY = 100f, caption = duration(d.samples.last().timestamp - d.samples.first().timestamp))
            SectionTitle("Prąd do baterii [mA] (tło: ekran włączony)")
            LineChart(
                down(d.samples.map { it.currentMa.toFloat() }),
                minY = 0f,
                shade = down(d.samples.map { if (it.screenOn) 1f else 0f }),
            )
            SectionTitle("Moc ładowania [W]")
            LineChart(down(d.samples.map { it.powerW.toFloat() }), minY = 0f)
            SectionTitle("Temperatura [°C]")
            LineChart(down(d.samples.map { it.temperatureC.toFloat() }))
            SectionTitle("Napięcie [mV]")
            LineChart(down(d.samples.map { it.voltageMv.toFloat() }))
        }

        SectionTitle("Historia ładowań (30 dni)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                state.sessions.forEach { session ->
                    SessionRow(session, selected = session.id == d.session.id, onClick = { vm.selectChargingSession(session.id) })
                }
            }
        }
        if (!d.session.active) {
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { vm.selectChargingSession(null) }) { Text("Pokaż najnowszą") }
                TextButton(onClick = { vm.deleteChargingSession(d.session.id) }) { Text("Usuń tę sesję") }
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
                active -> "Monitorowanie aktywne" + (activeSince?.let { " od ${time(it)}" } ?: "")
                charger?.connected == true -> "Podłączono (${charger.plugged}) – monitor wyłączony"
                else -> "Ładowarka odłączona"
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    active -> "Próbka co 10 s: poziom, prąd, napięcie, temperatura, ekran. Zatrzyma się po odłączeniu."
                    charger?.connected == true -> "Status baterii: ${charger.statusLabel}."
                    autoMonitor -> "Monitor uruchomi się sam po podłączeniu ładowarki."
                    else -> "Włącz automatyczny start albo uruchom monitor ręcznie po podłączeniu."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    OutlinedButton(onClick = onStop) { Text("Zatrzymaj") }
                } else {
                    Button(onClick = onStart, enabled = charger?.connected == true) { Text("Monitoruj teraz") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Start automatycznie po podłączeniu", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoMonitor, onCheckedChange = onAuto)
            }
            if (autoMonitor && autoStartFailedAt > 0 && !active) {
                Text(
                    "Ostatnio system zablokował automatyczny start (${dateTime(autoStartFailedAt)}). Uruchom ponownie " +
                        "setup_permissions.sh – doda aplikację do wyjątków optymalizacji baterii.",
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
    SectionTitle("Bateria i ładowarka")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            KeyValueRow("Zasilanie", c.plugged ?: "brak", c.statusLabel)
            c.maxChargerW?.let { KeyValueRow("Moc deklarowana przez ładowarkę", "%.1f W".format(it), "wg sterownika; SUPERVOOC bywa raportowane niżej") }
            KeyValueRow("Kondycja (system)", c.health, c.technology)
            c.stateOfHealthPct?.let { KeyValueRow("State of Health", "$it%") }
            c.cycleCount?.let { KeyValueRow("Cykle ładowania", "$it") }
            avgCapacity?.let {
                KeyValueRow("Szacowana pojemność", "$it mAh", "średnia z $capacitySessions sesji (każda ≥ 15% doładowania)")
            }
        }
    }
}

@Composable
private fun NoteCard(n: ChargeNote) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(n.title, style = MaterialTheme.typography.titleSmall, color = severityColor(n.severity))
            Text(n.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                dateTime(s.startTs) + if (s.active) " · trwa" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            Text(
                "$levels · $dur" + (s.plug?.let { " · $it" } ?: ""),
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

private fun time(ts: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
private fun dateTime(ts: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))
