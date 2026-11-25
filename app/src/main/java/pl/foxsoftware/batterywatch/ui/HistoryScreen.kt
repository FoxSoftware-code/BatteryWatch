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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.foxsoftware.batterywatch.R
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: MainViewModel) {
    var hours by remember { mutableIntStateOf(24) }
    val history by vm.history.collectAsState()
    LaunchedEffect(hours) { vm.loadHistory(hours) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24 to "24 h", 72 to stringResource(R.string.hist_3d), 168 to stringResource(R.string.hist_7d)).forEach { (h, label) ->
                FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) })
            }
        }
        val (samples, stats) = history ?: return@Column
        val first = samples.firstOrNull()?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.timestamp)) }
        Text(
            if (first != null) stringResource(R.string.hist_samples_since, samples.size, first)
            else stringResource(R.string.hist_samples, samples.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.screen_off),
                stats.screenOffPctPerHour?.let { "%.1f%%/h".format(it) } ?: "–",
                Modifier.weight(1f),
                stringResource(R.string.hist_from_hours, stats.screenOffHours),
            )
            StatTile(
                stringResource(R.string.screen_on),
                stats.screenOnPctPerHour?.let { "%.1f%%/h".format(it) } ?: "–",
                Modifier.weight(1f),
                stringResource(R.string.hist_from_hours, stats.screenOnHours),
            )
        }
        SectionTitle(stringResource(R.string.hist_level_chart))
        LineChart(
            samples.map { it.levelPct.toFloat() },
            minY = 0f, maxY = 100f,
            shade = samples.map { it.screenOnFraction.toFloat() },
        )
        SectionTitle(stringResource(R.string.chart_temperature))
        LineChart(samples.map { it.temperatureC.toFloat() })
        Text(
            stringResource(R.string.hist_empty_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
