package pl.foxsoftware.batterywatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: MainViewModel) {
    var hours by remember { mutableIntStateOf(24) }
    val history by vm.history.collectAsState()
    LaunchedEffect(hours) { vm.loadHistory(hours) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24 to "24 h", 72 to "3 dni", 168 to "7 dni").forEach { (h, label) ->
                FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) })
            }
        }
        val (samples, stats) = history ?: return@Column
        Text(
            "Próbka co ~15 min (WorkManager). Zapisanych: ${samples.size}" +
                (samples.firstOrNull()?.let { " od ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.timestamp))}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                "Ekran zgaszony",
                stats.screenOffPctPerHour?.let { "%.1f%%/h".format(it) } ?: "–",
                Modifier.weight(1f),
                "z %.1f h".format(stats.screenOffHours),
            )
            StatTile(
                "Ekran włączony",
                stats.screenOnPctPerHour?.let { "%.1f%%/h".format(it) } ?: "–",
                Modifier.weight(1f),
                "z %.1f h".format(stats.screenOnHours),
            )
        }
        SectionTitle("Poziom baterii [%] (tło: ekran włączony)")
        LineChart(
            samples.map { it.levelPct.toFloat() },
            minY = 0f, maxY = 100f,
            shade = samples.map { it.screenOnFraction.toFloat() },
        )
        SectionTitle("Temperatura [°C]")
        LineChart(samples.map { it.temperatureC.toFloat() })
        Text(
            "Gdy historia jest pusta: pierwsza próbka pojawi się w ciągu 15 min od instalacji. " +
                "Do rozróżnienia ekranu włączonego i zgaszonego potrzebny jest dostęp do danych o użyciu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
