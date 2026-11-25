package pl.foxsoftware.batterywatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import pl.foxsoftware.batterywatch.domain.FindingsEngine
import pl.foxsoftware.batterywatch.domain.FindingsEngine.fmt
import pl.foxsoftware.batterywatch.domain.FixAction

@Composable
fun ActionsScreen(vm: MainViewModel, onAction: (FixAction) -> Unit) {
    val snap by vm.snapshot.collectAsState()
    val shizuku by vm.shizuku.collectAsState()
    val applied by vm.applied.collectAsState()
    val diagnosis by vm.diagnosis.collectAsState()
    LaunchedEffect(Unit) { vm.refreshStatus() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Ustawienia")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SwitchRow("Automatyczna jasność", snap.autoBrightness) {
                    onAction(FixAction.SetSystem("screen_brightness_mode", if (it) 1 else 0, if (it) "Włącz auto-jasność" else "Wyłącz auto-jasność"))
                }
                Text("Wygaszanie ekranu: ${snap.screenOffTimeoutMs?.let(::fmt) ?: "?"}", Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L).forEach { t ->
                        FilterChip(
                            selected = snap.screenOffTimeoutMs == t,
                            onClick = { onAction(FixAction.SetSystem("screen_off_timeout", t.toInt(), "Wygaszanie po ${fmt(t)}")) },
                            label = { Text(fmt(t)) },
                        )
                    }
                }
                SwitchRow("Skanowanie Bluetooth (zawsze)", snap.bleScanAlways) {
                    onAction(FixAction.SetGlobal("ble_scan_always_enabled", if (it) 1 else 0, if (it) "Włącz skan Bluetooth" else "Wyłącz skan Bluetooth"))
                }
                SwitchRow("Skanowanie Wi-Fi (zawsze)", snap.wifiScanAlways) {
                    val v = if (it) "enabled" else "disabled"
                    onAction(
                        FixAction.ShellCommand(
                            "cmd wifi set-scan-always-available $v",
                            "cmd wifi set-scan-always-available ${if (it) "disabled" else "enabled"}",
                            if (it) "Włącz skan Wi-Fi" else "Wyłącz skan Wi-Fi",
                            FixAction.OpenSettings(FindingsEngine.ACTION_SCANNING_SETTINGS, "Ustawienia skanowania"),
                        ),
                    )
                }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAction(FixAction.OpenSettings(FindingsEngine.ACTION_OPLUS_GESTURES, "Gesty przy zgaszonym ekranie")) }) {
                        Text("Gesty (latarka!)")
                    }
                    OutlinedButton(onClick = { onAction(FixAction.OpenSettings(FindingsEngine.ACTION_DISPLAY_SETTINGS, "Wyświetlacz / AOD / 120 Hz")) }) {
                        Text("Wyświetlacz / AOD")
                    }
                    OutlinedButton(onClick = { onAction(FixAction.OpenSettings(FindingsEngine.ACTION_WIFI_SETTINGS, "Wi-Fi")) }) {
                        Text("Wi-Fi")
                    }
                }
            }
        }

        SectionTitle("Shizuku (ograniczanie aplikacji w tle)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when {
                        shizuku.granted -> "Działa i ma uprawnienie. Akcje na aplikacjach wykonują się bezpośrednio."
                        shizuku.running -> "Działa, ale brak zgody dla tej aplikacji."
                        else -> "Nie działa. Bez Shizuku przycisk otworzy ustawienia aplikacji, gdzie wybierzesz Bateria → Ograniczone. " +
                            "Shizuku (Google Play) uruchomisz przez Debugowanie bezprzewodowe."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (shizuku.running && !shizuku.granted) {
                    TextButton(onClick = { vm.requestShizuku() }) { Text("Udziel zgody") }
                }
            }
        }

        val report = diagnosis.report
        if (report != null) {
            SectionTitle("Aplikacje (wg zużycia na baterii)")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    report.apps.mapNotNull { a -> vm.names.restrictablePackage(a.uid)?.let { a to it } }
                        .filter { it.first.onBatteryMah >= 1 }
                        .take(15)
                        .forEach { (a, pkg) ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                KeyValueRow(
                                    vm.names.name(a.uid), "%.0f mAh".format(a.onBatteryMah),
                                    "zgaszony ekran %.0f mAh · w tle %s · alarmy %d".format(a.onBatteryScreenOffMah, fmt(a.backgroundMs), a.wakeupAlarms),
                                )
                                Row {
                                    TextButton(onClick = { onAction(FixAction.RestrictApp(pkg)) }) { Text("Ogranicz w tle") }
                                    TextButton(onClick = { vm.forceStop(pkg) }) { Text("Zatrzymaj") }
                                }
                            }
                        }
                }
            }
        } else {
            Text("Lista aplikacji pojawi się po analizie w zakładce Diagnoza.", style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle("Wprowadzone zmiany")
        if (applied.isEmpty()) Text("Brak.", style = MaterialTheme.typography.bodySmall)
        applied.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.description, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.undo(a) }) { Text("Cofnij") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean?, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label + if (checked == null) " (nieznane)" else "", Modifier.weight(1f))
        Switch(checked = checked == true, onCheckedChange = onChange)
    }
}
