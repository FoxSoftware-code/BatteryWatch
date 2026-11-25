package pl.foxsoftware.batterywatch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.foxsoftware.batterywatch.domain.FixAction
import pl.foxsoftware.batterywatch.domain.Severity

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun severityColor(s: Severity): Color = when (s) {
    Severity.HIGH -> MaterialTheme.colorScheme.error
    Severity.MEDIUM -> Color(0xFFE08A00)
    Severity.LOW -> MaterialTheme.colorScheme.primary
    Severity.INFO -> MaterialTheme.colorScheme.outline
}

/** Wiersz "nazwa ........ wartość". */
@Composable
fun KeyValueRow(key: String, value: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(key, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * Prosty wykres liniowy. [shade] (0..1 na punkt) rysuje tło, np. udział włączonego ekranu.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    minY: Float? = null,
    maxY: Float? = null,
    shade: List<Float>? = null,
    caption: String? = null,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val shadeColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            if (values.size < 2) {
                Text("Za mało danych do wykresu", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val lo = minY ?: values.min()
            val hi = (maxY ?: values.max()).let { if (it - lo < 1e-3f) lo + 1f else it }
            Row {
                Text("%.0f".format(hi), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                caption?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val stepX = size.width / (values.size - 1)
                fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
                shade?.forEachIndexed { i, s ->
                    if (s > 0.01f && i > 0) {
                        drawRect(shadeColor.copy(alpha = shadeColor.alpha * s * 2), Offset((i - 1) * stepX, 0f), Size(stepX, size.height))
                    }
                }
                for (g in 0..4) {
                    val gy = size.height * g / 4
                    drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                }
                val path = Path()
                values.forEachIndexed { i, v ->
                    val p = Offset(i * stepX, y(v.coerceIn(lo, hi)))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, lineColor, style = Stroke(width = 4f))
            }
            Text("%.0f".format(lo), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Potwierdzenie przed każdą zmianą w telefonie. */
@Composable
fun ConfirmDialog(action: FixAction?, onConfirm: (FixAction) -> Unit, onDismiss: () -> Unit) {
    if (action == null) return
    val details = when (action) {
        is FixAction.SetGlobal -> "Zmiana ustawienia ${action.key} na ${action.value}. Można cofnąć w zakładce Akcje."
        is FixAction.SetSystem -> "Zmiana ustawienia ${action.key} na ${action.value}. Można cofnąć w zakładce Akcje."
        is FixAction.ShellCommand -> "Polecenie: ${action.command}\n(bez Shizuku zostaną otwarte ustawienia)"
        is FixAction.OpenSettings -> "Otworzę odpowiedni ekran ustawień."
        is FixAction.RestrictApp -> "Aplikacja ${action.packageName} nie będzie mogła działać w tle " +
            "(powiadomienia push mogą przychodzić z opóźnieniem). Z Shizuku można to cofnąć w zakładce Akcje; " +
            "bez Shizuku otworzę ustawienia aplikacji."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.label) },
        text = { Text(details) },
        confirmButton = { TextButton(onClick = { onConfirm(action) }) { Text("Wykonaj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
