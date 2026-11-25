package pl.foxsoftware.batterywatch.domain

import pl.foxsoftware.batterywatch.data.AppUsage
import pl.foxsoftware.batterywatch.data.BatteryStatsReport
import kotlin.math.roundToInt

enum class Severity(val label: String) { HIGH("Wysoki"), MEDIUM("Średni"), LOW("Niski"), INFO("Info") }

/** Akcja proponowana przy ustaleniu. Wykonanie w [ActionExecutor]. */
sealed interface FixAction {
    val label: String

    data class SetGlobal(val key: String, val value: Int, override val label: String) : FixAction
    data class SetSystem(val key: String, val value: Int, override val label: String) : FixAction
    /** Wymaga Shizuku; bez niego [fallback]. */
    data class ShellCommand(val command: String, val undoCommand: String?, override val label: String, val fallback: FixAction?) : FixAction
    data class OpenSettings(val intentAction: String, override val label: String, val packageName: String? = null) : FixAction
    data class RestrictApp(val packageName: String, override val label: String = "Ogranicz w tle") : FixAction
}

data class Finding(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String,
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
                "Ekran zgaszony: %.1f%%/h (norma < 1%%/h)".format(rate),
                "Przez ${fmt(r.screenOffTimeMs)} zgaszonego ekranu zeszło ${r.dischargedPctScreenOff}% " +
                    "(${r.screenOffDischargeMah.roundToInt()} mAh). Poniżej przyczyny, które to wyjaśniają.",
            )
        }

        // 2. Ekran włączony
        if (onH >= 0.5) {
            val bright = r.brightnessPct["bright"] ?: 0.0
            val share = if (r.dischargeMah > 0) r.screenOnDischargeMah / r.dischargeMah * 100 else 0.0
            if (share > 60 && (bright > 35 || device.autoBrightness == false)) out += Finding(
                "screen_on", Severity.MEDIUM,
                "Ekran to ${share.roundToInt()}% zużycia, często na wysokiej jasności",
                "Ekran włączony: %.1f%%/h, %.0f%% czasu w najwyższym kubełku jasności.".format(r.screenOnPctPerHour, bright) +
                    if (device.autoBrightness == false) " Automatyczna jasność jest wyłączona." else "",
                listOfNotNull(
                    if (device.autoBrightness == false) FixAction.SetSystem("screen_brightness_mode", 1, "Włącz auto-jasność") else null,
                    FixAction.OpenSettings(ACTION_DISPLAY_SETTINGS, "Ustawienia wyświetlacza (120 Hz, AOD)"),
                ),
            )
        }
        device.screenOffTimeoutMs?.let { t ->
            if (t > 120_000) out += Finding(
                "timeout", if (t >= 600_000) Severity.MEDIUM else Severity.LOW,
                "Ekran gaśnie dopiero po ${fmt(t)}",
                "Każde odłożenie telefonu bez blokady to ekran świecący bez potrzeby.",
                listOf(
                    FixAction.SetSystem("screen_off_timeout", 30_000, "Ustaw 30 s"),
                    FixAction.SetSystem("screen_off_timeout", 60_000, "Ustaw 1 min"),
                ),
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
                    "Latarka świeciła ${fmt(ms)} przy zgaszonym ekranie",
                    "${f.mah.roundToInt()} mAh" + (who?.let { ", włączona przez: $it" } ?: "") +
                        if (gesture || device.screenOffGestures == true)
                            ". Najpewniej gest na zgaszonym ekranie odpala ją w kieszeni." else ".",
                    listOf(FixAction.OpenSettings(ACTION_OPLUS_GESTURES, "Gesty przy zgaszonym ekranie")),
                )
            }
        }

        // 4. Łączność
        val onBattH = r.timeOnBatteryMs / 3_600_000.0
        if (onBattH > 1 && r.connectivityChanges / onBattH > 30) out += Finding(
            "connectivity", if (r.connectivityChanges / onBattH > 100) Severity.HIGH else Severity.MEDIUM,
            "Niestabilna łączność: ${r.connectivityChanges} zmian sieci",
            "Wi-Fi rozłączone przez ${fmt(r.wifiDisconnectedMs)}, słaby sygnał komórkowy przez " +
                "%.0f%% czasu. Każde przełączenie budzi modem i CPU. ".format(r.cellPoorSignalPct) +
                "Pomaga wyłączenie przełączania Wi-Fi/sieć komórkowa (asystent Wi-Fi) i zapomnienie słabych sieci.",
            listOf(FixAction.OpenSettings(ACTION_WIFI_SETTINGS, "Ustawienia Wi-Fi")),
        )
        if (r.wifiMulticastWakelockMs > 30 * 60_000) out += Finding(
            "multicast", Severity.LOW,
            "Wi-Fi multicast trzymał wakelock ${fmt(r.wifiMulticastWakelockMs)}",
            "Zwykle to aplikacje wykrywające urządzenia w sieci (Chromecast, kamery, smart home).",
        )

        // 5. Doze
        if (offH >= 2) {
            val deepShare = r.deepIdleMs.toDouble() / r.screenOffTimeMs * 100
            if (deepShare < 40) out += Finding(
                "doze", Severity.MEDIUM,
                "Głęboki Doze tylko przez %.0f%% czasu zgaszonego ekranu".format(deepShare),
                "Telefon rzadko zasypia na dobre. Powody: ruch (kieszeń), wakelocki i alarmy aplikacji, gesty, latarka.",
            )
        }

        // 6. Skanowanie
        if (device.bleScanAlways == true || device.wifiScanAlways == true) out += Finding(
            "scanning", Severity.LOW,
            "Skanowanie Wi-Fi/Bluetooth działa nawet przy wyłączonych modułach",
            "Skan BT łącznie: ${fmt(r.bluetoothScanMs)}. Wyłączenie trochę pogarsza dokładność lokalizacji w budynkach.",
            listOfNotNull(
                if (device.bleScanAlways == true) FixAction.SetGlobal("ble_scan_always_enabled", 0, "Wyłącz skan Bluetooth") else null,
                if (device.wifiScanAlways == true) FixAction.ShellCommand(
                    "cmd wifi set-scan-always-available disabled",
                    "cmd wifi set-scan-always-available enabled",
                    "Wyłącz skan Wi-Fi",
                    FixAction.OpenSettings(ACTION_SCANNING_SETTINGS, "Wyłącz skan Wi-Fi"),
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
                    "$name zużywa baterię przy zgaszonym ekranie (${offMah.roundToInt()} mAh)",
                    "W tle: ${fmt(app.backgroundMs)}, usługa pierwszoplanowa: ${fmt(app.fgServiceMs)}, " +
                        "czas CPU: ${fmt(app.cpuUserMs + app.cpuSystemMs)}, alarmy budzące: ${app.wakeupAlarms}.",
                    restrict,
                )
            } else if (app.wakeupAlarms > 50) {
                out += Finding(
                    "alarms_${app.uid}", Severity.LOW,
                    "$name budzi telefon ${app.wakeupAlarms}× (alarmy)",
                    "Częste alarmy budzące utrudniają głęboki sen telefonu.",
                    restrict,
                )
            }
            if (app.chargingMah > 500) out += Finding(
                "charging_heavy_${app.uid}", Severity.INFO,
                "$name mocno pracuje podczas ładowania (${app.chargingMah.roundToInt()} mAh)",
                "Nie rozładowuje baterii, ale nagrzewa ją w trakcie ładowania, co przyspiesza jej zużycie.",
            )
        }
        r.partialWakelocks.filter { it.durationMs > 10 * 60_000 }.forEach { w ->
            val uid = w.owner?.let(pl.foxsoftware.batterywatch.data.BatteryStatsParser::parseUid)
            out += Finding(
                "wl_${w.owner}_${w.name}", Severity.LOW,
                "Wakelock ${fmt(w.durationMs)}: ${uid?.let(appName) ?: w.owner}",
                "${w.name} (${w.count}×). Audio/odtwarzanie muzyki to normalny powód.",
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
                "Bateria może mieć ok. %.0f%% pojemności".format(health),
                "Licznik ładunku: ${(counter / 1000)} mAh przy $level%% → ~${fullMah.roundToInt()} z ${r.estimatedCapacityMah} mAh. ".replace("%%", "%") +
                    "To przybliżenie; sprawdź Ustawienia → Bateria → Kondycja baterii.",
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
