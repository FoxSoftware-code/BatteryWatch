package pl.foxsoftware.batterywatch.data

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import pl.foxsoftware.batterywatch.domain.DeviceSnapshot
import kotlin.math.abs

data class LiveBattery(
    val timestamp: Long,
    val levelPct: Int,
    /** Dodatni = pobór z baterii, ujemny = ładowanie. */
    val dischargeMa: Int,
    val voltageMv: Int,
    val temperatureC: Double,
    val charging: Boolean,
    val plugged: String?,
    val chargeCounterUah: Long?,
    val screenOn: Boolean,
) {
    val powerMw: Double get() = dischargeMa * voltageMv / 1000.0
}

data class PermissionStatus(
    val dump: Boolean,
    val batteryStats: Boolean,
    val writeSecureSettings: Boolean,
    val writeSettings: Boolean,
    val usageStats: Boolean,
) {
    val allAdbGranted get() = dump && batteryStats && writeSecureSettings && usageStats
}

/** Szczegóły zasilania i stanu baterii (część pól producenci podają, część nie). */
data class ChargerInfo(
    /** BatteryManager.BATTERY_PLUGGED_*; 0 = odłączony. */
    val pluggedRaw: Int,
    val plugged: String?,
    val status: Int,
    /** Moc deklarowana przez ładowarkę (max_charging_current × max_charging_voltage). */
    val maxChargerW: Double?,
    val health: String,
    val cycleCount: Int?,
    /** State of Health w %, jeśli system go udostępnia. */
    val stateOfHealthPct: Int?,
    val technology: String?,
) {
    val connected get() = pluggedRaw != 0
    val wired get() = pluggedRaw == BatteryManager.BATTERY_PLUGGED_AC || pluggedRaw == BatteryManager.BATTERY_PLUGGED_USB
    val statusLabel: String
        get() = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "ładuje"
            BatteryManager.BATTERY_STATUS_FULL -> "naładowana"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "podłączona, wstrzymane"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "rozładowuje"
            else -> "nieznany"
        }
}

class DeviceReader(private val context: Context) {

    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)

    fun live(): LiveBattery {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }
        return LiveBattery(
            timestamp = System.currentTimeMillis(),
            levelPct = level,
            dischargeMa = normalizeCurrent(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), charging),
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            temperatureC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            charging = charging,
            plugged = pluggedLabel(plugged),
            chargeCounterUah = counter,
            screenOn = pm.isInteractive,
        )
    }

    fun charger(): ChargerInfo {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        // Ukryte, ale publicznie dostępne extra: µA i µV
        val maxUa = sticky?.getIntExtra("max_charging_current", 0) ?: 0
        val maxUv = sticky?.getIntExtra("max_charging_voltage", 0) ?: 0
        val maxW = if (maxUa > 0 && maxUv > 0) maxUa.toDouble() * maxUv / 1e12 else null
        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        // BATTERY_PROPERTY_STATE_OF_HEALTH (=10) to @SystemApi, ale z BATTERY_STATS bywa dostępne
        val soh = try {
            bm.getIntProperty(10).takeIf { it in 1..100 }
        } catch (_: Exception) {
            null
        }
        return ChargerInfo(
            pluggedRaw = plugged,
            plugged = pluggedLabel(plugged),
            status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            maxChargerW = maxW?.takeIf { it in 0.5..300.0 },
            health = when (sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "dobra"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "przegrzana"
                BatteryManager.BATTERY_HEALTH_DEAD -> "zużyta"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "przepięcie"
                BatteryManager.BATTERY_HEALTH_COLD -> "za zimna"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "błąd"
                else -> "nieznana"
            },
            cycleCount = cycles,
            stateOfHealthPct = soh,
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
        )
    }

    private fun pluggedLabel(plugged: Int): String? = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ładowarka"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "indukcja"
        0 -> null
        else -> "zasilanie"
    }

    /**
     * Producenci raportują CURRENT_NOW różnie: w µA albo mA, z różnym znakiem.
     * Wynik: mA, dodatni przy rozładowaniu.
     */
    private fun normalizeCurrent(raw: Int, charging: Boolean): Int {
        if (raw == Int.MIN_VALUE) return 0
        val ma = if (abs(raw) >= 20_000) raw / 1000 else raw
        return if (charging) -abs(ma) else abs(ma)
    }

    fun snapshot(): DeviceSnapshot {
        val cr = context.contentResolver
        val live = live()
        return DeviceSnapshot(
            screenOffTimeoutMs = Settings.System.getLong(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1).takeIf { it > 0 },
            autoBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
                .takeIf { it >= 0 }?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC },
            wifiScanAlways = Settings.Global.getInt(cr, "wifi_scan_always_enabled", -1).takeIf { it >= 0 }?.let { it == 1 },
            bleScanAlways = Settings.Global.getInt(cr, "ble_scan_always_enabled", -1).takeIf { it >= 0 }?.let { it == 1 },
            screenOffGestures = Settings.System.getInt(cr, "oplus_screen_off_gesture_state", -1).takeIf { it >= 0 }?.let { it == 1 },
            levelPct = live.levelPct,
            chargeCounterUah = live.chargeCounterUah,
        )
    }

    fun permissions(): PermissionStatus {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val usage = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
        // Do zrzutu batterystats potrzebne jest i uprawnienie, i appop
        val usageGranted = usage && granted(Manifest.permission.PACKAGE_USAGE_STATS)
        return PermissionStatus(
            dump = granted(Manifest.permission.DUMP),
            batteryStats = granted(Manifest.permission.BATTERY_STATS),
            writeSecureSettings = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
            writeSettings = Settings.System.canWrite(context),
            usageStats = usageGranted,
        )
    }
}

/** UID -> czytelna nazwa i pakiet. */
class AppNames(private val context: Context) {

    private val pm = context.packageManager
    private val cache = mutableMapOf<Int, Pair<String, String?>>()

    private val systemUids = mapOf(
        0 to "Jądro / root",
        1000 to "System Android",
        1001 to "Telefonia (radio)",
        1002 to "Bluetooth",
        1010 to "Wi-Fi",
        1013 to "Media",
        1021 to "GPS",
        1027 to "NFC",
        1041 to "Serwer audio",
        1047 to "Kamera (serwer)",
        1073 to "Stos sieciowy",
        1082 to "ART (optymalizacja aplikacji)",
        2000 to "Powłoka ADB",
    )

    @Synchronized
    private fun resolve(uid: Int): Pair<String, String?> = cache.getOrPut(uid) {
        systemUids[uid]?.let { return@getOrPut it to null }
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            ?: return@getOrPut "UID $uid" to null
        val label = try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        val clone = if (uid / 100_000 > 0) " (klon)" else ""
        "$label$clone" to pkg
    }

    fun name(uid: Int): String = resolve(uid).first
    fun packageOf(uid: Int): String? = resolve(uid).second

    /**
     * Pakiet, który można bezpiecznie ograniczyć w tle. Wykluczamy rdzeń systemu i usługi Google/OnePlus,
     * ale nie wszystkie aplikacje z flagą systemową (np. preinstalowany YouTube można ograniczyć).
     */
    fun restrictablePackage(uid: Int): String? {
        val pkg = packageOf(uid) ?: return null
        if (pkg == context.packageName || protectedPrefixes.any { pkg == it || pkg.startsWith("$it.") }) return null
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val persistent = info.flags and ApplicationInfo.FLAG_PERSISTENT != 0
            if (persistent) null else pkg
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private val protectedPrefixes = listOf(
        "android", "com.android", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.ext", "com.oplus", "com.oneplus", "com.coloros", "com.heytap",
        "com.qualcomm", "com.qti", "vendor", "org.codeaurora",
    )
}
