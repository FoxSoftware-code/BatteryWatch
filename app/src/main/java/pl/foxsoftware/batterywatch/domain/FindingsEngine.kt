package pl.foxsoftware.batterywatch.domain

import androidx.annotation.StringRes
import pl.foxsoftware.batterywatch.R
import pl.foxsoftware.batterywatch.data.AppUsage
import pl.foxsoftware.batterywatch.data.BatteryStatsReport
import kotlin.math.roundToInt

enum class Severity(@StringRes val label: Int) {
    HIGH(R.string.severity_high), MEDIUM(R.string.severity_medium), LOW(R.string.severity_low), INFO(R.string.severity_info),
}

/** Akcja proponowana przy ustaleniu. Wykonanie w [ActionExecutor]. */
sealed interface FixAction {
    val label: Txt

    data class SetGlobal(val key: String, val value: Int, override val label: Txt) : FixAction
    data class SetSystem(val key: String, val value: Int, override val label: Txt) : FixAction
    /** Wymaga Shizuku; bez niego [fallback]. */
    data class ShellCommand(val command: String, val undoCommand: String?, override val label: Txt, val fallback: FixAction?) : FixAction
    data class OpenSettings(val intentAction: String, override val label: Txt, val packageName: String? = null) : FixAction
    data class RestrictApp(val packageName: String, override val label: Txt = txt(R.string.action_restrict_background)) : FixAction
}

data class Finding(
    val id: String,
    val severity: Severity,
    val title: Txt,
    val detail: Txt,
    val actions: List<FixAction> = emptyList(),
)

/** Bieżące ustawienia i stan telefonu potrzebne regułom. Null = nieznane. */
data class DeviceSnapshot(
    val screenOffTimeoutMs: Long? = null,
    val autoBrightness: Boolean? = null,
    val wifiScanAlways: Boolean? = null,
    val bleScanAlways: Boolean? = null,
    val screenOffGestures: Boolean? = null,
    val levelPct: Int? = null,
    val chargeCounterUah: Long? = null,
)

object FindingsEngine {

    const val ACTION_SCANNING_SETTINGS = "android.settings.LOCATION_SCANNING_SETTINGS"
    const val ACTION_OPLUS_GESTURES = "com.oplus.action.ScreenOffGestureList"
    const val ACTION_DISPLAY_SETTINGS = "android.settings.DISPLAY_SETTINGS"
    const val ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"

    /** Systemowe UID-y, których nie ma sensu "ograniczać". */
    private fun isSystemUid(uid: Int) = uid % 100_000 < 10_000

    /**
     * @param packageOf UID -> nazwa pakietu (null, gdy nieznana).
     * @param restrictablePackage UID -> pakiet, który wolno ograniczać (null dla aplikacji systemowych).
     */
    fun evaluate(
        r: BatteryStatsReport,
        device: DeviceSnapshot,
        appName: (Int) -> String = { "UID $it" },
        packageOf: (Int) -> String? = { null },
        restrictablePackage: (Int) -> String? = { null },
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val offH = r.screenOffTimeMs / 3_600_000.0
        val onH = r.screenOnTimeMs / 3_600_000.0

        // 1. Zużycie przy zgaszonym ekranie
        if (offH >= 1.0) {
            val rate = r.screenOffPctPerHour
            val sev = when {
                rate > 1.5 -> Severity.HIGH
                rate > 0.8 -> Severity.MEDIUM
                else -> null
            }
            if (sev != null) out += Finding(
                "screen_off_drain", sev,
                txt(R.string.finding_screen_off_drain_title, rate),
                txt(
                    R.string.finding_screen_off_drain_detail,
                    fmt(r.screenOffTimeMs), r.dischargedPctScreenOff, r.screenOffDischargeMah.roundToInt(),
                ),
            )
        }

        // 2. Ekran włączony
        if (onH >= 0.5) {
            val bright = r.brightnessPct["bright"] ?: 0.0
            val share = if (r.dischargeMah > 0) r.screenOnDischargeMah / r.dischargeMah * 100 else 0.0
            if (share > 60 && (bright > 35 || device.autoBrightness == false)) out += Finding(
                "screen_on", Severity.MEDIUM,
                txt(R.string.finding_screen_on_title, share.roundToInt()),
                joinTxt(
                    txt(R.string.finding_screen_on_detail, r.screenOnPctPerHour, bright),
                    if (device.autoBrightness == false) txt(R.string.finding_auto_brightness_off) else null,
                ),
                listOfNotNull(
                    if (device.autoBrightness == false) {
                        FixAction.SetSystem("screen_brightness_mode", 1, txt(R.string.action_enable_auto_brightness))
                    } else null,
                    FixAction.OpenSettings(ACTION_DISPLAY_SETTINGS, txt(R.string.action_display_settings)),
                ),
            )
        }
        device.screenOffTimeoutMs?.let { t ->
            if (t > 120_000) out += Finding(
                "timeout", if (t >= 600_000) Severity.MEDIUM else Severity.LOW,
                txt(R.string.finding_timeout_title, fmt(t)),
                txt(R.string.finding_timeout_detail),
                listOf(30_000, 60_000).map {
                    FixAction.SetSystem("screen_off_timeout", it, txt(R.string.action_set_timeout, fmt(it.toLong())))
                },
            )
        }

        // 3. Latarka przy zgaszonym ekranie
        r.globalOnBatteryScreenOff("flashlight")?.let { f ->
            val ms = f.durationMs ?: 0
            if (ms > 5 * 60_000) {
                val culprit = r.apps.filter { (it.screenOffComponents["flashlight"]?.mah ?: 0.0) > 1 }
                    .maxByOrNull { it.screenOffComponents["flashlight"]!!.mah }
                val who = culprit?.let { appName(it.uid) }
                val gesture = culprit?.let { packageOf(it.uid) }?.contains("gesture") == true
                out += Finding(
                    "flashlight_screen_off", if (ms > 20 * 60_000) Severity.HIGH else Severity.MEDIUM,
                    txt(R.string.finding_flashlight_title, fmt(ms)),
                    joinTxt(
                        who?.let { txt(R.string.finding_flashlight_by, f.mah.roundToInt(), it) }
                            ?: txt(R.string.finding_flashlight_mah, f.mah.roundToInt()),
                        if (gesture || device.screenOffGestures == true) txt(R.string.finding_flashlight_gesture) else null,
                    ),
                    listOf(FixAction.OpenSettings(ACTION_OPLUS_GESTURES, txt(R.string.action_screen_off_gestures))),
                )
            }
        }

        // 4. Łączność
        val onBattH = r.timeOnBatteryMs / 3_600_000.0
        if (onBattH > 1 && r.connectivityChanges / onBattH > 30) out += Finding(
            "connectivity", if (r.connectivityChanges / onBattH > 100) Severity.HIGH else Severity.MEDIUM,
            txt(R.string.finding_connectivity_title, r.connectivityChanges),
            txt(R.string.finding_connectivity_detail, fmt(r.wifiDisconnectedMs), r.cellPoorSignalPct),
            listOf(FixAction.OpenSettings(ACTION_WIFI_SETTINGS, txt(R.string.action_wifi_settings))),
        )
        if (r.wifiMulticastWakelockMs > 30 * 60_000) out += Finding(
            "multicast", Severity.LOW,
            txt(R.string.finding_multicast_title, fmt(r.wifiMulticastWakelockMs)),
            txt(R.string.finding_multicast_detail),
        )

        // 5. Doze
        if (offH >= 2) {
            val deepShare = r.deepIdleMs.toDouble() / r.screenOffTimeMs * 100
            if (deepShare < 40) out += Finding(
                "doze", Severity.MEDIUM,
                txt(R.string.finding_doze_title, deepShare),
                txt(R.string.finding_doze_detail),
            )
        }

        // 6. Skanowanie
        if (device.bleScanAlways == true || device.wifiScanAlways == true) out += Finding(
            "scanning", Severity.LOW,
            txt(R.string.finding_scanning_title),
            txt(R.string.finding_scanning_detail, fmt(r.bluetoothScanMs)),
            listOfNotNull(
                if (device.bleScanAlways == true) {
                    FixAction.SetGlobal("ble_scan_always_enabled", 0, txt(R.string.action_disable_bt_scan))
                } else null,
                if (device.wifiScanAlways == true) FixAction.ShellCommand(
                    "cmd wifi set-scan-always-available disabled",
                    "cmd wifi set-scan-always-available enabled",
                    txt(R.string.action_disable_wifi_scan),
                    FixAction.OpenSettings(ACTION_SCANNING_SETTINGS, txt(R.string.action_disable_wifi_scan)),
                ) else null,
            ),
        )

        // 7. Aplikacje
        for (app in r.apps) {
            if (isSystemUid(app.uid)) continue
            val name = appName(app.uid)
            val restrict = listOfNotNull(restrictablePackage(app.uid)?.let { FixAction.RestrictApp(it) })
            // Latarka ma osobne ustalenie, więc nie liczymy jej jako "pracy w tle".
            val offMah = app.onBatteryScreenOffMah - (app.screenOffComponents["flashlight"]?.mah ?: 0.0)
            if (offMah > 10) {
                out += Finding(
                    "bg_${app.uid}", if (offMah > 40) Severity.HIGH else Severity.MEDIUM,
                    txt(R.string.finding_bg_title, name, offMah.roundToInt()),
                    txt(
                        R.string.finding_bg_detail,
                        fmt(app.backgroundMs), fmt(app.fgServiceMs), fmt(app.cpuUserMs + app.cpuSystemMs), app.wakeupAlarms,
                    ),
                    restrict,
                )
            } else if (app.wakeupAlarms > 50) {
                out += Finding(
                    "alarms_${app.uid}", Severity.LOW,
                    txt(R.string.finding_alarms_title, name, app.wakeupAlarms),
                    txt(R.string.finding_alarms_detail),
                    restrict,
                )
            }
            if (app.chargingMah > 500) out += Finding(
                "charging_heavy_${app.uid}", Severity.INFO,
                txt(R.string.finding_charging_heavy_title, name, app.chargingMah.roundToInt()),
                txt(R.string.finding_charging_heavy_detail),
            )
        }
        r.partialWakelocks.filter { it.durationMs > 10 * 60_000 }.forEach { w ->
            val uid = w.owner?.let(pl.foxsoftware.batterywatch.data.BatteryStatsParser::parseUid)
            out += Finding(
                "wl_${w.owner}_${w.name}", Severity.LOW,
                txt(R.string.finding_wakelock_title, fmt(w.durationMs), uid?.let(appName) ?: w.owner),
                txt(R.string.finding_wakelock_detail, w.name, w.count),
            )
        }

        // 8. Kondycja baterii (przybliżenie z licznika ładunku)
        val level = device.levelPct
        val counter = device.chargeCounterUah
        if (level != null && counter != null && level >= 20 && r.estimatedCapacityMah > 0) {
            val fullMah = counter / 1000.0 / (level / 100.0)
            val health = fullMah / r.estimatedCapacityMah * 100
            if (health < 85) out += Finding(
                "battery_health", if (health < 75) Severity.MEDIUM else Severity.LOW,
                txt(R.string.finding_health_title, health),
                txt(R.string.finding_health_detail, counter / 1000, level, fullMah.roundToInt(), r.estimatedCapacityMah),
            )
        }

        return out.sortedBy { it.severity.ordinal }
    }

    fun fmt(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 -> "${h}h ${m}min"
            totalMin > 0 -> "${m} min"
            else -> "${ms / 1000} s"
        }
    }
}

/** Pomocniczo dla UI: krótka nazwa składowej zużycia. */
fun AppUsage.topScreenOffComponent(): String? =
    screenOffComponents.maxByOrNull { it.value.mah }?.takeIf { it.value.mah > 1 }?.key
