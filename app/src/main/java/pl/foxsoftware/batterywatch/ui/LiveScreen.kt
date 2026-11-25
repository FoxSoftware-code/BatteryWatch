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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.foxsoftware.batterywatch.R

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
            Text(stringResource(R.string.live_reading))
            return@Column
        }
        // Średnia z ostatnich 30 s wygładza skoki prądu
        val recent = samples.takeLast(15)
        val avgMa = recent.map { it.dischargeMa }.average()
        val pctPerHour = avgMa / capacity * 100

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.live_battery), "${s.levelPct}%", Modifier.weight(1f),
                plugLabel(s.plugged)?.let { stringResource(R.string.live_charging_via, it) } ?: stringResource(R.string.live_on_battery),
            )
            StatTile(
                stringResource(if (s.charging) R.string.live_charge_current else R.string.live_draw),
                "${kotlin.math.abs(s.dischargeMa)} mA",
                Modifier.weight(1f),
                "%.2f W".format(kotlin.math.abs(s.powerMw) / 1000),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.temperature), "%.1f °C".format(s.temperatureC), Modifier.weight(1f), "${s.voltageMv} mV")
            StatTile(
                stringResource(R.string.live_rate),
                if (s.charging) "–" else "%.1f %%/h".format(pctPerHour),
                Modifier.weight(1f),
                if (!s.charging && pctPerHour > 0.1) stringResource(R.string.live_time_to_empty, s.levelPct / pctPerHour) else null,
            )
        }

        SectionTitle(stringResource(R.string.live_chart_title, samples.size * 2 / 60))
        LineChart(samples.map { it.dischargeMa.toFloat() }, minY = 0f, caption = stringResource(R.string.live_every_2s))
        Text(
            stringResource(R.string.live_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        val c = cpu
        SectionTitle(
            c?.windowSeconds?.let { stringResource(R.string.live_top_processes_window, it) }
                ?: (stringResource(R.string.live_top_processes) + (c?.windowRaw?.let { " ($it)" } ?: "")),
        )
        if (c == null) {
            Text(stringResource(R.string.live_no_cpu), style = MaterialTheme.typography.bodySmall)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    c.load?.let { Text(stringResource(R.string.live_load, it), style = MaterialTheme.typography.bodySmall) }
                    c.processes.take(12).forEach { p ->
                        KeyValueRow(p.name, "%.1f%%".format(p.totalPct), "pid ${p.pid} · user %.1f%% + kernel %.1f%%".format(p.userPct, p.kernelPct))
                    }
                }
            }
        }
    }
}
