package pl.foxsoftware.batterywatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.foxsoftware.batterywatch.charging.ChargingMonitorService
import pl.foxsoftware.batterywatch.charging.ChargingTrigger
import pl.foxsoftware.batterywatch.data.AppNames
import pl.foxsoftware.batterywatch.data.ChargeSession
import pl.foxsoftware.batterywatch.data.ChargerInfo
import pl.foxsoftware.batterywatch.data.ChargingDb
import pl.foxsoftware.batterywatch.data.ChargingPrefs
import pl.foxsoftware.batterywatch.data.BatteryStatsParser
import pl.foxsoftware.batterywatch.data.BatteryStatsReport
import pl.foxsoftware.batterywatch.data.CpuInfo
import pl.foxsoftware.batterywatch.data.CpuInfoParser
import pl.foxsoftware.batterywatch.data.DeviceReader
import pl.foxsoftware.batterywatch.data.HistoryDb
import pl.foxsoftware.batterywatch.data.HistoryStats
import pl.foxsoftware.batterywatch.data.LiveBattery
import pl.foxsoftware.batterywatch.data.PermissionStatus
import pl.foxsoftware.batterywatch.data.Sample
import pl.foxsoftware.batterywatch.data.ShellRunner
import pl.foxsoftware.batterywatch.data.ShizukuShell
import pl.foxsoftware.batterywatch.domain.ActionExecutor
import pl.foxsoftware.batterywatch.domain.AppliedAction
import pl.foxsoftware.batterywatch.domain.ChargeNote
import pl.foxsoftware.batterywatch.domain.ChargeSample
import pl.foxsoftware.batterywatch.domain.ChargeSummary
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer
import pl.foxsoftware.batterywatch.domain.DeviceSnapshot
import pl.foxsoftware.batterywatch.domain.Finding
import pl.foxsoftware.batterywatch.domain.FindingsEngine
import pl.foxsoftware.batterywatch.domain.FixAction

data class DiagnosisState(
    val loading: Boolean = false,
    val error: String? = null,
    val report: BatteryStatsReport? = null,
    val findings: List<Finding> = emptyList(),
    val analyzedAt: Long? = null,
)

data class ChargingDetail(
    val session: ChargeSession,
    val samples: List<ChargeSample>,
    val summary: ChargeSummary?,
    val notes: List<ChargeNote>,
)

data class ChargingSettings(val autoMonitor: Boolean, val targetLevel: Int, val tempAlarmC: Int, val autoStartFailedAt: Long)

data class ChargingState(
    val charger: ChargerInfo? = null,
    val sessions: List<ChargeSession> = emptyList(),
    val detail: ChargingDetail? = null,
    /** Średnia szacowanej pojemności z ostatnich sesji (min. 15% doładowania każda). */
    val avgCapacityMah: Int? = null,
    val capacitySessions: Int = 0,
)

data class ShizukuState(val running: Boolean, val granted: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val reader = DeviceReader(app)
    val names = AppNames(app)
    private val executor = ActionExecutor(app)
    private val historyDb = HistoryDb(app)

    private val _live = MutableStateFlow<List<LiveBattery>>(emptyList())
    val live: StateFlow<List<LiveBattery>> = _live.asStateFlow()

    private val _cpu = MutableStateFlow<CpuInfo?>(null)
    val cpu: StateFlow<CpuInfo?> = _cpu.asStateFlow()

    private val _diagnosis = MutableStateFlow(DiagnosisState())
    val diagnosis: StateFlow<DiagnosisState> = _diagnosis.asStateFlow()

    private val _permissions = MutableStateFlow(reader.permissions())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _snapshot = MutableStateFlow(DeviceSnapshot())
    val snapshot: StateFlow<DeviceSnapshot> = _snapshot.asStateFlow()

    private val _shizuku = MutableStateFlow(ShizukuState(false, false))
    val shizuku: StateFlow<ShizukuState> = _shizuku.asStateFlow()

    private val _applied = MutableStateFlow<List<AppliedAction>>(emptyList())
    val applied: StateFlow<List<AppliedAction>> = _applied.asStateFlow()

    private val _history = MutableStateFlow<Pair<List<Sample>, HistoryStats>?>(null)
    val history = _history.asStateFlow()

    private val chargingDb = ChargingDb(app)
    private val chargingPrefs = ChargingPrefs(app)

    /** Id sesji ładowania, gdy usługa monitorująca działa. */
    val chargingActive: StateFlow<Long?> = ChargingMonitorService.activeSession

    private val _charging = MutableStateFlow(ChargingState())
    val charging: StateFlow<ChargingState> = _charging.asStateFlow()

    private val _chargingSettings = MutableStateFlow(readChargingSettings())
    val chargingSettings: StateFlow<ChargingSettings> = _chargingSettings.asStateFlow()

    /** Wybrana sesja w historii; null = bieżąca albo ostatnia. */
    private var selectedSessionId: Long? = null

    /** Żądanie przełączenia zakładki (np. z powiadomienia). */
    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refreshStatus() {
        _permissions.value = reader.permissions()
        _snapshot.value = reader.snapshot()
        _shizuku.value = ShizukuState(ShizukuShell.isRunning(), ShizukuShell.hasPermission())
        _applied.value = executor.history()
    }

    fun sampleLive() {
        val s = reader.live()
        _live.update { (it + s).takeLast(300) }
    }

    fun refreshCpu() = viewModelScope.launch {
        _cpu.value = try {
            CpuInfoParser.parse(ShellRunner.dumpsys("cpuinfo"))
        } catch (_: Exception) {
            null
        }
    }

    fun analyze() = viewModelScope.launch {
        _diagnosis.update { it.copy(loading = true, error = null) }
        try {
            val result = withContext(Dispatchers.Default) {
                val report = BatteryStatsParser.parse(ShellRunner.dumpsys("batterystats"))
                val snap = reader.snapshot()
                _snapshot.value = snap
                report to FindingsEngine.evaluate(report, snap, names::name, names::packageOf, names::restrictablePackage)
            }
            _diagnosis.value = DiagnosisState(report = result.first, findings = result.second, analyzedAt = System.currentTimeMillis())
        } catch (e: Exception) {
            _diagnosis.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    fun loadHistory(hours: Int) = viewModelScope.launch {
        val samples = withContext(Dispatchers.IO) { historyDb.since(System.currentTimeMillis() - hours * 3_600_000L) }
        _history.value = samples to HistoryDb.stats(samples)
    }

    fun run(action: FixAction) = viewModelScope.launch {
        _message.value = try {
            executor.execute(action)
        } catch (e: Exception) {
            "Błąd: ${e.message}"
        }
        refreshStatus()
    }

    fun forceStop(pkg: String) = viewModelScope.launch {
        _message.value = executor.forceStop(pkg)
    }

    fun undo(a: AppliedAction) = viewModelScope.launch {
        _message.value = executor.undo(a)
        refreshStatus()
    }

    fun requestShizuku() {
        ShizukuShell.requestPermission()
    }

    private fun designCapacityMah(): Int = _diagnosis.value.report?.estimatedCapacityMah?.takeIf { it > 0 } ?: 5500

    private fun readChargingSettings() = ChargingSettings(
        chargingPrefs.autoMonitor, chargingPrefs.targetLevel, chargingPrefs.tempAlarmC, chargingPrefs.lastAutoStartFailedAt,
    )

    /** [full] przelicza też średnią pojemność ze wszystkich sesji (cięższe, tylko przy wejściu na ekran). */
    fun refreshCharging(full: Boolean = false) = viewModelScope.launch {
        val design = designCapacityMah()
        val state = withContext(Dispatchers.IO) {
            val sessions = chargingDb.sessions()
            val chosen = selectedSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                ?: sessions.firstOrNull { it.id == chargingActive.value }
                ?: sessions.firstOrNull()
            val charger = reader.charger()
            val detail = chosen?.let { session ->
                val samples = chargingDb.samples(session.id)
                val summary = ChargingAnalyzer.summarize(samples, design)
                val notes = summary?.let {
                    ChargingAnalyzer.notes(it, session.maxChargerW, design, wired = session.plug == "ładowarka" || session.plug == "USB")
                }.orEmpty()
                ChargingDetail(session, samples, summary, notes)
            }
            var avg = _charging.value.avgCapacityMah
            var avgN = _charging.value.capacitySessions
            if (full) {
                val caps = sessions.filter { !it.active }.take(10).mapNotNull {
                    ChargingAnalyzer.summarize(chargingDb.samples(it.id), design)?.estimatedCapacityMah
                }
                avg = caps.takeIf { it.isNotEmpty() }?.average()?.toInt()
                avgN = caps.size
            }
            ChargingState(charger, sessions, detail, avg, avgN)
        }
        _charging.value = state
        _chargingSettings.value = readChargingSettings()
    }

    fun selectChargingSession(id: Long?) {
        selectedSessionId = id
        refreshCharging()
    }

    fun deleteChargingSession(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { chargingDb.deleteSession(id) }
        if (selectedSessionId == id) selectedSessionId = null
        refreshCharging(full = true)
    }

    fun startChargingMonitor() {
        val app = getApplication<Application>()
        if (!reader.charger().connected) {
            _message.value = "Podłącz ładowarkę – monitor działa tylko podczas ładowania."
            return
        }
        chargingPrefs.suppressedUntilUnplug = false
        chargingPrefs.lastAutoStartFailedAt = 0
        selectedSessionId = null
        if (!ChargingMonitorService.start(app)) _message.value = "System nie pozwolił uruchomić monitora."
        refreshCharging()
    }

    fun stopChargingMonitor() {
        ChargingMonitorService.stop(getApplication())
        _message.value = "Monitor zatrzymany do odłączenia ładowarki."
    }

    fun setAutoMonitor(on: Boolean) {
        chargingPrefs.autoMonitor = on
        val app = getApplication<Application>()
        if (on) ChargingTrigger.arm(app) else ChargingTrigger.disarm(app)
        _chargingSettings.value = readChargingSettings()
    }

    fun setTargetLevel(pct: Int) {
        chargingPrefs.targetLevel = pct
        _chargingSettings.value = readChargingSettings()
    }

    fun setTempAlarm(c: Int) {
        chargingPrefs.tempAlarmC = c
        _chargingSettings.value = readChargingSettings()
    }

    fun requestTab(index: Int) {
        _tabRequest.value = index
    }

    fun consumeTabRequest() {
        _tabRequest.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
