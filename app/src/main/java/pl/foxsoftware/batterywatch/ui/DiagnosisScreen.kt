package pl.foxsoftware.batterywatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.foxsoftware.batterywatch.data.BatteryStatsParser
import pl.foxsoftware.batterywatch.data.TimedEntry
import pl.foxsoftware.batterywatch.domain.Finding
import pl.foxsoftware.batterywatch.domain.FindingsEngine.fmt
import pl.foxsoftware.batterywatch.domain.FixAction
import pl.foxsoftware.batterywatch.domain.topScreenOffComponent
import java.text.DateFormat
import java.util.Date

@Composable
fun DiagnosisScreen(vm: MainViewModel, onAction: (FixAction) -> Unit) {
    val state by vm.diagnosis.collectAsState()
    LaunchedEffect(Unit) { if (state.report == null && !state.loading) vm.analyze() }

    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp)) {
                Button(onClick = { vm.analyze() }, enabled = !state.loading) { Text("Analizuj ponownie") }
                if (state.loading) CircularProgressIndicator(Modifier.padding(start = 16.dp))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            state.analyzedAt?.let {
                Text(
                    "Dane od ostatniego pełnego ładowania · analiza ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        val r = state.report ?: return@LazyColumn

        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Na baterii", fmt(r.timeOnBatteryMs), Modifier.weight(1f), "zeszło ${r.dischargedPctLower}–${r.dischargedPctUpper}%")
                StatTile("Ekran włączony", "%.1f%%/h".format(r.screenOnPctPerHour), Modifier.weight(1f), "${fmt(r.screenOnTimeMs)}, ${r.screenOnCount}×")
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Ekran zgaszony", "%.1f%%/h".format(r.screenOffPctPerHour), Modifier.weight(1f), "norma < 1%/h")
                StatTile("Głęboki sen", fmt(r.deepIdleMs), Modifier.weight(1f), "z ${fmt(r.screenOffTimeMs)} zgaszenia")
            }
        }

        item { SectionTitle("Ustalenia (${state.findings.size})") }
        items(state.findings, key = { it.id }) { FindingCard(it, onAction) }

        item { SectionTitle("Aplikacje na baterii (mAh, estymata systemu)") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    r.apps.filter { it.onBatteryMah >= 1 }.take(25).forEach { a ->
                        val off = a.topScreenOffComponent()?.let { " · zgaszony: najwięcej $it" } ?: ""
                        KeyValueRow(
                            vm.names.name(a.uid),
                            "%.0f".format(a.onBatteryMah),
                            "ekran wł. %.0f · zgaszony %.0f · w tle %s%s".format(
                                a.onBatteryScreenOnMah, a.onBatteryScreenOffMah, fmt(a.backgroundMs), off,
                            ),
                        )
                    }
                }
            }
        }
        item { TimedList("Wakelocki aplikacji (trzymanie CPU)", r.partialWakelocks) { it.owner?.let { o -> vm.names.name(BatteryStatsParser.parseUid(o)) } } }
        item { TimedList("Wakelocki jądra", r.kernelWakelocks) { null } }
        item { TimedList("Powody wybudzeń", r.wakeupReasons) { null } }
        item {
            SectionTitle("Sieć")
            Card(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Column(Modifier.padding(12.dp)) {
                    KeyValueRow("Zmiany łączności", r.connectivityChanges.toString())
                    KeyValueRow("Wi-Fi rozłączone", fmt(r.wifiDisconnectedMs))
                    KeyValueRow("Słaby sygnał komórkowy", "%.0f%% czasu".format(r.cellPoorSignalPct))
                    KeyValueRow("Wakelock Wi-Fi multicast", fmt(r.wifiMulticastWakelockMs))
                    KeyValueRow("Skanowanie Bluetooth", fmt(r.bluetoothScanMs))
                }
            }
        }
    }
}

@Composable
private fun FindingCard(f: Finding, onAction: (FixAction) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(f.severity.label.uppercase(), color = severityColor(f.severity), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(f.title, style = MaterialTheme.typography.titleSmall)
            Text(f.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            if (f.actions.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.actions.forEach { a -> OutlinedButton(onClick = { onAction(a) }) { Text(a.label) } }
                }
            }
        }
    }
}

@Composable
private fun TimedList(title: String, entries: List<TimedEntry>, owner: (TimedEntry) -> String?) {
    var expanded by remember { mutableStateOf(false) }
    SectionTitle(title)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (entries.isEmpty()) Text("Brak danych", style = MaterialTheme.typography.bodySmall)
            entries.take(if (expanded) 40 else 6).forEach { e ->
                KeyValueRow(e.name, fmt(e.durationMs), listOfNotNull(owner(e), "${e.count}×").joinToString(" · "))
            }
            if (entries.size > 6) {
                Text(
                    if (expanded) "Zwiń" else "Pokaż więcej (${entries.size})",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
                )
            }
        }
    }
}
