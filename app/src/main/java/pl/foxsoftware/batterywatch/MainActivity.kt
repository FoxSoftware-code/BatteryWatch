package pl.foxsoftware.batterywatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import pl.foxsoftware.batterywatch.charging.ChargingMonitorService
import pl.foxsoftware.batterywatch.charging.ChargingTrigger
import pl.foxsoftware.batterywatch.data.HistoryWorker
import pl.foxsoftware.batterywatch.data.PermissionStatus
import pl.foxsoftware.batterywatch.domain.FixAction
import pl.foxsoftware.batterywatch.ui.ActionsScreen
import pl.foxsoftware.batterywatch.ui.ChargingScreen
import pl.foxsoftware.batterywatch.ui.ConfirmDialog
import pl.foxsoftware.batterywatch.ui.DiagnosisScreen
import pl.foxsoftware.batterywatch.ui.HistoryScreen
import pl.foxsoftware.batterywatch.ui.LiveScreen
import pl.foxsoftware.batterywatch.ui.MainViewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> vm.refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HistoryWorker.schedule(this)
        ChargingMonitorService.createChannels(this)
        // Gdy telefon już się ładuje, wyzwalacz odpali od razu (aplikacja jest na pierwszym planie, więc start usługi jest dozwolony)
        ChargingTrigger.arm(this)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        handleIntent(intent)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra(EXTRA_TAB)) vm.requestTab(intent.getIntExtra(EXTRA_TAB, 0))
        if (intent.getBooleanExtra(EXTRA_START_CHARGING_MONITOR, false)) vm.startChargingMonitor()
        intent.removeExtra(EXTRA_START_CHARGING_MONITOR)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_START_CHARGING_MONITOR = "start_charging_monitor"
        /** Indeks zakładki Ładowanie w [Tab]. */
        const val TAB_CHARGING = 1
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }
}

private enum class Tab(val label: Int, val icon: Int) {
    LIVE(R.string.tab_live, android.R.drawable.ic_menu_recent_history),
    CHARGING(R.string.tab_charging, android.R.drawable.ic_lock_idle_charging),
    DIAGNOSIS(R.string.tab_diagnosis, android.R.drawable.ic_menu_search),
    HISTORY(R.string.tab_history, android.R.drawable.ic_menu_my_calendar),
    ACTIONS(R.string.tab_actions, android.R.drawable.ic_menu_manage),
}

@Composable
private fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<FixAction?>(null) }
    val permissions by vm.permissions.collectAsState()
    val message by vm.message.collectAsState()
    val tabRequest by vm.tabRequest.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }
    LaunchedEffect(tabRequest) {
        tabRequest?.let {
            if (it in Tab.entries.indices) tab = it
            vm.consumeTabRequest()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (!permissions.allAdbGranted) PermissionBanner(permissions)
            val onAction: (FixAction) -> Unit = { pending = it }
            when (Tab.entries[tab]) {
                Tab.LIVE -> LiveScreen(vm)
                Tab.CHARGING -> ChargingScreen(vm)
                Tab.DIAGNOSIS -> DiagnosisScreen(vm, onAction)
                Tab.HISTORY -> HistoryScreen(vm)
                Tab.ACTIONS -> ActionsScreen(vm, onAction)
            }
        }
    }
    ConfirmDialog(pending, onConfirm = { vm.run(it); pending = null }, onDismiss = { pending = null })
}

@Composable
private fun PermissionBanner(p: PermissionStatus) {
    val ctx = LocalContext.current
    val pkg = ctx.packageName
    val commands = buildList {
        if (!p.dump) add("adb shell pm grant $pkg android.permission.DUMP")
        if (!p.batteryStats) add("adb shell pm grant $pkg android.permission.BATTERY_STATS")
        if (!p.writeSecureSettings) add("adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        if (!p.usageStats) {
            add("adb shell pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
            add("adb shell appops set $pkg GET_USAGE_STATS allow")
        }
        if (!p.writeSettings) add("adb shell appops set $pkg WRITE_SETTINGS allow")
    }.joinToString("\n")
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.perm_missing_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.perm_missing_text), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", commands))
            }) { Text(stringResource(R.string.perm_copy_adb)) }
        }
    }
}
