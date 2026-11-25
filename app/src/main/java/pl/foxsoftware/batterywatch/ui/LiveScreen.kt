package pl.foxsoftware.batterywatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(vm: MainViewModel) {
    val samples by vm.live.collectAsState()
    val cpu by vm.cpu.collectAsState()
    val diagnosis by vm.diagnosis.collectAsState()

    // Odświeżanie tylko, gdy ten ekran jest widoczny
    LaunchedEffect(Unit) {
        while (true) {
            vm.sampleLive()
            delay(2_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshCpu()
            delay(10_000)
        }
    }

    val s = samples.lastOrNull()
    val capacity = diagnosis.report?.estimatedCapacityMah?.takeIf { it > 0 } ?: 5500
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (s == null) {
            Text("Odczyt…")
            return@Column
        }
        // Średnia z ostatnich 30 s wygładza skoki prądu
        val recent = samples.takeLast(15)
        val avgMa = recent.map { it.dischargeMa }.average()
        val pctPerHour = avgMa / capacity * 100

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Bateria", "${s.levelPct}%", Modifier.weight(1f), s.plugged?.let { "ładowanie: $it" } ?: "na baterii")
            StatTile(
                if (s.charging) "Prąd ładowania" else "Pobór",
                "${kotlin.math.abs(s.dischargeMa)} mA",
                Modifier.weight(1f),
                "%.2f W".format(kotlin.math.abs(s.powerMw) / 1000),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Temperatura", "%.1f °C".format(s.temperatureC), Modifier.weight(1f), "${s.voltageMv} mV")
            StatTile(
                "Tempo (30 s)",
                if (s.charging) "–" else "%.1f %%/h".format(pctPerHour),
                Modifier.weight(1f),
                if (!s.charging && pctPerHour > 0.1) "~%.1f h do 0%%".format(s.levelPct / pctPerHour) else null,
            )
        }

        SectionTitle("Pobór prądu [mA], ostatnie ${samples.size * 2 / 60} min")
        LineChart(samples.map { it.dischargeMa.toFloat() }, minY = 0f, caption = "co 2 s")
        Text(
            "Uwaga: ten ekran jest włączony, więc pokazuje pobór z ekranem. Zużycie przy zgaszonym ekranie " +
                "zobaczysz w Diagnozie i Historii.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionTitle("Najbardziej obciążające procesy" + (cpu?.window?.let { " ($it)" } ?: ""))
        val c = cpu
        if (c == null) {
            Text("Brak danych. Potrzebne uprawnienie DUMP.", style = MaterialTheme.typography.bodySmall)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    c.load?.let { Text("Obciążenie (load avg): $it", style = MaterialTheme.typography.bodySmall) }
                    c.processes.take(12).forEach { p ->
                        KeyValueRow(p.name, "%.1f%%".format(p.totalPct), "pid ${p.pid} · user %.1f%% + kernel %.1f%%".format(p.userPct, p.kernelPct))
                    }
                }
            }
        }
    }
}
