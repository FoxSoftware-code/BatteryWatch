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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.foxsoftware.batterywatch.R
import pl.foxsoftware.batterywatch.domain.FindingsEngine
import pl.foxsoftware.batterywatch.domain.Txt
import pl.foxsoftware.batterywatch.domain.txt
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
        SectionTitle(stringResource(R.string.act_settings))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SwitchRow(stringResource(R.string.act_auto_brightness), snap.autoBrightness) {
                    onAction(
                        FixAction.SetSystem(
                            "screen_brightness_mode", if (it) 1 else 0,
                            txt(if (it) R.string.action_enable_auto_brightness else R.string.action_disable_auto_brightness),
                        ),
                    )
                }
                Text(stringResource(R.string.act_timeout, snap.screenOffTimeoutMs?.let(::fmt) ?: "?"), Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L).forEach { t ->
                        FilterChip(
                            selected = snap.screenOffTimeoutMs == t,
                            onClick = { onAction(FixAction.SetSystem("screen_off_timeout", t.toInt(), txt(R.string.action_timeout_after, fmt(t)))) },
                            label = { Text(fmt(t)) },
                        )
                    }
                }
                SwitchRow(stringResource(R.string.act_bt_scan), snap.bleScanAlways) {
                    onAction(
                        FixAction.SetGlobal(
                            "ble_scan_always_enabled", if (it) 1 else 0,
                            txt(if (it) R.string.action_enable_bt_scan else R.string.action_disable_bt_scan),
                        ),
                    )
                }
                SwitchRow(stringResource(R.string.act_wifi_scan), snap.wifiScanAlways) {
                    val v = if (it) "enabled" else "disabled"
                    onAction(
                        FixAction.ShellCommand(
                            "cmd wifi set-scan-always-available $v",
                            "cmd wifi set-scan-always-available ${if (it) "disabled" else "enabled"}",
                            txt(if (it) R.string.action_enable_wifi_scan else R.string.action_disable_wifi_scan),
                            FixAction.OpenSettings(FindingsEngine.ACTION_SCANNING_SETTINGS, txt(R.string.action_scanning_settings)),
                        ),
                    )
                }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        onAction(FixAction.OpenSettings(FindingsEngine.ACTION_OPLUS_GESTURES, txt(R.string.action_screen_off_gestures)))
                    }) {
                        Text(stringResource(R.string.act_gestures_btn))
                    }
                    OutlinedButton(onClick = {
                        onAction(FixAction.OpenSettings(FindingsEngine.ACTION_DISPLAY_SETTINGS, txt(R.string.action_display_aod)))
                    }) {
                        Text(stringResource(R.string.act_display_btn))
                    }
                    OutlinedButton(onClick = { onAction(FixAction.OpenSettings(FindingsEngine.ACTION_WIFI_SETTINGS, Txt.Raw("Wi-Fi"))) }) {
                        Text("Wi-Fi")
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.act_shizuku_title))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(
                        when {
                            shizuku.granted -> R.string.shizuku_granted
                            shizuku.running -> R.string.shizuku_no_perm
                            else -> R.string.shizuku_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (shizuku.running && !shizuku.granted) {
                    TextButton(onClick = { vm.requestShizuku() }) { Text(stringResource(R.string.shizuku_grant)) }
                }
            }
        }

        val report = diagnosis.report
        if (report != null) {
            SectionTitle(stringResource(R.string.act_apps))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    report.apps.mapNotNull { a -> vm.names.restrictablePackage(a.uid)?.let { a to it } }
                        .filter { it.first.onBatteryMah >= 1 }
                        .take(15)
                        .forEach { (a, pkg) ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                KeyValueRow(
                                    vm.names.name(a.uid), "%.0f mAh".format(a.onBatteryMah),
                                    stringResource(R.string.act_app_sub, a.onBatteryScreenOffMah, fmt(a.backgroundMs), a.wakeupAlarms),
                                )
                                Row {
                                    TextButton(onClick = { onAction(FixAction.RestrictApp(pkg)) }) { Text(stringResource(R.string.action_restrict_background)) }
                                    TextButton(onClick = { vm.forceStop(pkg) }) { Text(stringResource(R.string.stop)) }
                                }
                            }
                        }
                }
            }
        } else {
            Text(stringResource(R.string.act_apps_after_diag), style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle(stringResource(R.string.act_applied))
        if (applied.isEmpty()) Text(stringResource(R.string.act_none), style = MaterialTheme.typography.bodySmall)
        applied.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.description, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.undo(a) }) { Text(stringResource(R.string.undo)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean?, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (checked == null) stringResource(R.string.unknown_suffix, label) else label, Modifier.weight(1f))
        Switch(checked = checked == true, onCheckedChange = onChange)
    }
}
