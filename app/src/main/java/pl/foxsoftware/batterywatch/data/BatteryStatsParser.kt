package pl.foxsoftware.batterywatch.data

/**
 * Parser wyjścia `dumpsys batterystats` (sekcja "Statistics since last charge").
 * Czysty Kotlin, bez zależności od Androida, żeby dało się go testować na zrzutach z telefonu.
 */

data class PowerComponent(val mah: Double, val durationMs: Long?)

data class AppUsage(
    val uid: Int,
    val uidLabel: String,
    /** Cała estymata od ostatniego ładowania (również w trakcie ładowania). */
    val totalMah: Double,
    val onBatteryScreenOnMah: Double,
    val onBatteryScreenOffMah: Double,
    val chargingMah: Double,
    /** Składowe zużycia na baterii przy zgaszonym ekranie, np. cpu, flashlight, wifi. */
    val screenOffComponents: Map<String, PowerComponent>,
    // Poniższe z bloku szczegółowego UID (czasy na baterii)
    val topMs: Long = 0,
    val fgServiceMs: Long = 0,
    val backgroundMs: Long = 0,
    val cachedMs: Long = 0,
    val wakeupAlarms: Int = 0,
    val cpuUserMs: Long = 0,
    val cpuSystemMs: Long = 0,
    val wifiRxBytes: Long = 0,
    val mobileRxBytes: Long = 0,
) {
    val onBatteryMah get() = onBatteryScreenOnMah + onBatteryScreenOffMah
}

data class TimedEntry(val owner: String?, val name: String, val durationMs: Long, val count: Int)

data class BatteryStatsReport(
    val timeOnBatteryMs: Long,
    val screenOffTimeMs: Long,
    val screenOnTimeMs: Long,
    val screenOnCount: Int,
    val dischargeMah: Double,
    val screenOnDischargeMah: Double,
    val screenOffDischargeMah: Double,
    val deepDozeDischargeMah: Double,
    val lightDozeDischargeMah: Double,
    val dischargedPctLower: Int,
    val dischargedPctUpper: Int,
    val dischargedPctScreenOn: Int,
    val dischargedPctScreenOff: Int,
    val estimatedCapacityMah: Int,
    val deepIdleMs: Long,
    val lightIdleMs: Long,
    val connectivityChanges: Int,
    val fullWakelockMs: Long,
    val partialWakelockMs: Long,
    val wifiMulticastWakelockMs: Long,
    /** Udział czasu włączonego ekranu w kubełkach jasności: dark/dim/medium/light/bright -> %. */
    val brightnessPct: Map<String, Double>,
    val wifiDisconnectedMs: Long,
    /** Udział czasu z sygnałem komórkowym "poor" + "very poor" (RSRP), w %. */
    val cellPoorSignalPct: Double,
    val bluetoothScanMs: Long,
    /** Estymata zużycia w podziale na komponenty: stan -> komponent -> wartość. Stan "total" = całość. */
    val globalPower: Map<String, Map<String, PowerComponent>>,
    val apps: List<AppUsage>,
    val partialWakelocks: List<TimedEntry>,
    val kernelWakelocks: List<TimedEntry>,
    val wakeupReasons: List<TimedEntry>,
) {
    val screenOffPctPerHour: Double
        get() = perHour(dischargedPctScreenOff.toDouble(), screenOffTimeMs)
    val screenOnPctPerHour: Double
        get() = perHour(dischargedPctScreenOn.toDouble(), screenOnTimeMs)

    fun globalOnBatteryScreenOff(component: String): PowerComponent? =
        globalPower[STATE_BATTERY_SCREEN_OFF]?.get(component)

    companion object {
        const val STATE_TOTAL = "total"
        const val STATE_BATTERY_SCREEN_ON = "on battery, screen on"
        const val STATE_BATTERY_SCREEN_OFF = "on battery, screen off/doze"
        const val STATE_CHARGING_SCREEN_ON = "not on battery, screen on"
        const val STATE_CHARGING_SCREEN_OFF = "not on battery, screen off/doze"

        fun perHour(value: Double, ms: Long): Double =
            if (ms <= 0) 0.0 else value / (ms / 3_600_000.0)
    }
}

object BatteryStatsParser {

    private val durationToken = Regex("""(\d+)(ms|d|h|m|s)(?![a-z])""")
    private const val DURATION_CHARS = """[\d dhms]+?"""

    /** "2h 11m 5s 572ms" -> ms. Zwraca 0, jeśli brak tokenów. */
    fun parseDuration(text: String): Long {
        var total = 0L
        for (m in durationToken.findAll(text)) {
            val v = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) {
                "d" -> v * 86_400_000
                "h" -> v * 3_600_000
                "m" -> v * 60_000
                "s" -> v * 1_000
                else -> v
            }
        }
        return total
    }

    /** Liczby w dumpsys mogą mieć przecinek dziesiętny (locale pl). */
    fun parseNumber(text: String): Double = text.trim().replace(',', '.').toDouble()

    /** "757,28MB" -> bajty. */
    fun parseBytes(text: String): Long {
        val m = Regex("""([\d.,]+)\s*([KMG]?B)""").find(text) ?: return 0
        val v = parseNumber(m.groupValues[1])
        val mult = when (m.groupValues[2]) {
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            else -> 1.0
        }
        return (v * mult).toLong()
    }

    /** "u0a456" -> 10456, "u10a12" -> 1010012, "1000" -> 1000. */
    fun parseUid(label: String): Int {
        val m = Regex("""u(\d+)a(\d+)""").matchEntire(label)
        if (m != null) return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
        val s = Regex("""u(\d+)s(\d+)""").matchEntire(label)
        if (s != null) return s.groupValues[1].toInt() * 100_000 + s.groupValues[2].toInt()
        return label.toIntOrNull() ?: -1
    }

    fun parse(dump: String): BatteryStatsReport {
        val all = dump.lines()
        val start = all.indexOfFirst { it.startsWith("Statistics since last charge") }
        require(start >= 0) {
            "Brak sekcji 'Statistics since last charge' w wyjściu dumpsys batterystats. Początek wyjścia: " +
                dump.take(200)
        }
        var end = all.size
        for (i in start + 1 until all.size) {
            if (all[i].isNotEmpty() && !all[i].startsWith(" ")) { end = i; break }
        }
        val lines = all.subList(start + 1, end)

        fun header(prefix: String): String? =
            lines.firstOrNull { it.startsWith("  $prefix") }?.substringAfter(prefix)

        fun headerDuration(prefix: String) = header(prefix)?.let { parseDuration(it.substringBefore("(")) } ?: 0L
        fun headerMah(prefix: String) =
            header(prefix)?.let { Regex("""([\d.,]+)\s*mAh""").find(it)?.groupValues?.get(1) }?.let(::parseNumber) ?: 0.0
        fun indentedInt(prefix: String) =
            lines.firstOrNull { it.trim().startsWith(prefix) }?.substringAfter(prefix)?.trim()?.toIntOrNull() ?: 0

        val screenOnLine = header("Screen on: ")
        val screenOnCount = screenOnLine?.let { Regex("""\)\s*(\d+)x""").find(it)?.groupValues?.get(1)?.toInt() } ?: 0

        // Jasność ekranu
        val brightness = mutableMapOf<String, Double>()
        val bIdx = lines.indexOfFirst { it.trim() == "Screen brightnesses:" }
        if (bIdx >= 0) {
            val r = Regex("""^\s+(dark|dim|medium|light|bright) .*\(([\d.,]+)%\)""")
            for (l in lines.drop(bIdx + 1).take(6)) {
                r.find(l)?.let { brightness[it.groupValues[1]] = parseNumber(it.groupValues[2]) }
            }
        }

        // Wi-Fi rozłączone
        val wifiStatesIdx = lines.indexOfFirst { it.trim() == "Wifi states:" }
        val wifiDisconn = if (wifiStatesIdx >= 0) {
            lines.drop(wifiStatesIdx + 1).take(8).firstOrNull { it.trim().startsWith("disconn ") }
                ?.let { parseDuration(it.substringBefore("(")) } ?: 0L
        } else 0L

        // Słaby sygnał komórkowy
        val rsrpIdx = lines.indexOfFirst { it.contains("Cellular Rx signal strength (RSRP):") }
        var poorPct = 0.0
        if (rsrpIdx >= 0) {
            val r = Regex("""^\s+(very poor|poor) \(.*\(([\d.,]+)%\)""")
            for (l in lines.drop(rsrpIdx + 1).take(6)) {
                r.find(l)?.let { poorPct += parseNumber(it.groupValues[2]) }
            }
        }

        val (globalPower, apps) = parseEstimatedPower(lines)
        val details = parseUidDetails(lines)
        val mergedApps = apps.map { app -> details[app.uidLabel]?.let { d -> d(app) } ?: app }

        return BatteryStatsReport(
            timeOnBatteryMs = headerDuration("Time on battery: "),
            screenOffTimeMs = headerDuration("Time on battery screen off: "),
            screenOnTimeMs = headerDuration("Screen on: "),
            screenOnCount = screenOnCount,
            dischargeMah = headerMah("Discharge: "),
            screenOnDischargeMah = headerMah("Screen on discharge: "),
            screenOffDischargeMah = headerMah("Screen off discharge: "),
            deepDozeDischargeMah = headerMah("Device deep doze discharge: "),
            lightDozeDischargeMah = headerMah("Device light doze discharge: "),
            dischargedPctLower = indentedInt("Amount discharged (lower bound):"),
            dischargedPctUpper = indentedInt("Amount discharged (upper bound):"),
            dischargedPctScreenOn = indentedInt("Amount discharged while screen on:"),
            dischargedPctScreenOff = indentedInt("Amount discharged while screen off:"),
            estimatedCapacityMah = headerMah("Estimated battery capacity: ").toInt(),
            deepIdleMs = headerDuration("Device full idling: "),
            lightIdleMs = headerDuration("Device light idling: "),
            connectivityChanges = header("Connectivity changes: ")?.trim()?.toIntOrNull() ?: 0,
            fullWakelockMs = headerDuration("Total full wakelock time: "),
            partialWakelockMs = headerDuration("Total partial wakelock time: "),
            wifiMulticastWakelockMs = headerDuration("Total WiFi Multicast wakelock time: "),
            brightnessPct = brightness,
            wifiDisconnectedMs = wifiDisconn,
            cellPoorSignalPct = poorPct,
            bluetoothScanMs = headerDuration("Bluetooth scan time: "),
            globalPower = globalPower,
            apps = mergedApps.sortedByDescending { it.onBatteryMah },
            partialWakelocks = parseTimedSection(lines, "All partial wake locks:", "Wake lock ", hasOwner = true),
            kernelWakelocks = parseTimedSection(lines, "All kernel wake locks:", "Kernel Wake lock ", hasOwner = false),
            wakeupReasons = parseTimedSection(lines, "All wakeup reasons:", "Wakeup reason ", hasOwner = false),
        )
    }

    private val stateHeader = Regex("""^\s+\((on battery|not on battery), (screen on|screen off/doze)\)\s*(.*)$""")
    private val globalComponent = Regex("""^\s+(\w+): ([\d.]+) apps: [\d.]+(?: duration: (.+))?$""")
    private val uidHeader = Regex("""^  UID (\S+): ([\d.]+)""")
    private val componentToken = Regex("""(\w+)=([\d.]+)(?: \(([^)]*)\))?""")

    private fun parseEstimatedPower(lines: List<String>): Pair<Map<String, Map<String, PowerComponent>>, List<AppUsage>> {
        val idx = lines.indexOfFirst { it.trim() == "Estimated power use (mAh):" }
        if (idx < 0) return emptyMap<String, Map<String, PowerComponent>>() to emptyList()

        val global = mutableMapOf<String, MutableMap<String, PowerComponent>>()
        val apps = mutableListOf<AppUsage>()
        var state = BatteryStatsReport.STATE_TOTAL
        var i = idx + 1

        // Blok "Global"
        while (i < lines.size && !lines[i].startsWith("  UID ")) {
            val l = lines[i]
            if (l.isBlank()) break
            val sh = stateHeader.find(l)
            if (sh != null) {
                state = "${sh.groupValues[1]}, ${sh.groupValues[2]}"
            } else {
                globalComponent.find(l)?.let {
                    val dur = it.groupValues[3].takeIf(String::isNotBlank)?.let(::parseDuration)
                    global.getOrPut(state) { mutableMapOf() }[it.groupValues[1]] =
                        PowerComponent(it.groupValues[2].toDouble(), dur)
                }
            }
            i++
        }

        // Bloki UID
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) break
            val uh = uidHeader.find(l)
            if (uh == null) { i++; continue }
            val label = uh.groupValues[1]
            val total = uh.groupValues[2].toDouble()
            val perState = mutableMapOf<String, Map<String, PowerComponent>>()
            i++
            while (i < lines.size && lines[i].startsWith("      ")) {
                stateHeader.find(lines[i])?.let { sh ->
                    perState["${sh.groupValues[1]}, ${sh.groupValues[2]}"] = parseComponents(sh.groupValues[3])
                }
                i++
            }
            fun sum(s: String) = perState[s]?.values?.sumOf { it.mah } ?: 0.0
            apps += AppUsage(
                uid = parseUid(label),
                uidLabel = label,
                totalMah = total,
                onBatteryScreenOnMah = sum(BatteryStatsReport.STATE_BATTERY_SCREEN_ON),
                onBatteryScreenOffMah = sum(BatteryStatsReport.STATE_BATTERY_SCREEN_OFF),
                chargingMah = sum(BatteryStatsReport.STATE_CHARGING_SCREEN_ON) + sum(BatteryStatsReport.STATE_CHARGING_SCREEN_OFF),
                screenOffComponents = perState[BatteryStatsReport.STATE_BATTERY_SCREEN_OFF] ?: emptyMap(),
            )
        }
        return global to apps
    }

    /** "cpu=5.68 cpu:bg=0.612 flashlight=137 (46m 57s 934ms)" -> tylko składowe główne (bez ":"). */
    private fun parseComponents(text: String): Map<String, PowerComponent> {
        val out = mutableMapOf<String, PowerComponent>()
        for (m in componentToken.findAll(text)) {
            // Pomijamy podział na stany procesu (np. "cpu:bg=..."), bo regex \w+ nie łapie dwukropka,
            // ale dopasowałby "bg=..." z "cpu:bg=...". Sprawdzamy znak przed dopasowaniem.
            if (m.range.first > 0 && text[m.range.first - 1] == ':') continue
            out[m.groupValues[1]] = PowerComponent(
                m.groupValues[2].toDouble(),
                m.groupValues[3].takeIf(String::isNotBlank)?.let(::parseDuration),
            )
        }
        return out
    }

    private val uidDetailHeader = Regex("""^  (u\d+[as]\d+|\d+):$""")

    /** Bloki szczegółowe "  u0a340:" z czasami stanów procesu, alarmami i ruchem sieciowym. */
    private fun parseUidDetails(lines: List<String>): Map<String, (AppUsage) -> AppUsage> {
        val out = mutableMapOf<String, (AppUsage) -> AppUsage>()
        var i = 0
        while (i < lines.size) {
            val h = uidDetailHeader.find(lines[i])
            if (h == null) { i++; continue }
            val label = h.groupValues[1]
            var top = 0L; var fgs = 0L; var bg = 0L; var cached = 0L; var alarms = 0
            var cpuU = 0L; var cpuS = 0L; var wifiRx = 0L; var mobileRx = 0L
            i++
            while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) {
                val t = lines[i].trim()
                when {
                    t.startsWith("Top for:") -> top = parseDuration(t)
                    t.startsWith("Fg Service for:") -> fgs = parseDuration(t)
                    t.startsWith("Background for:") -> bg = parseDuration(t)
                    t.startsWith("Cached for:") -> cached = parseDuration(t)
                    t.startsWith("Wakeup alarm ") ->
                        alarms += Regex(""": (\d+) times""").find(t)?.groupValues?.get(1)?.toInt() ?: 0
                    t.startsWith("Total cpu time: ") -> {
                        cpuU = parseDuration(t.substringAfter("u=").substringBefore("s="))
                        cpuS = parseDuration(t.substringAfter("s="))
                    }
                    t.startsWith("Wi-Fi network: ") -> wifiRx = parseBytes(t.substringAfter(": ").substringBefore(" received"))
                    t.startsWith("Mobile network: ") -> mobileRx = parseBytes(t.substringAfter(": ").substringBefore(" received"))
                }
                if (lines[i].isBlank() && i + 1 < lines.size && !lines[i + 1].startsWith("    ")) break
                i++
            }
            val a = alarms
            out[label] = { app ->
                app.copy(
                    topMs = top, fgServiceMs = fgs, backgroundMs = bg, cachedMs = cached, wakeupAlarms = a,
                    cpuUserMs = cpuU, cpuSystemMs = cpuS, wifiRxBytes = wifiRx, mobileRxBytes = mobileRx,
                )
            }
        }
        return out
    }

    private fun parseTimedSection(lines: List<String>, header: String, prefix: String, hasOwner: Boolean): List<TimedEntry> {
        val idx = lines.indexOfFirst { it.trim() == header }
        if (idx < 0) return emptyList()
        val r = Regex("""^\s+${Regex.escape(prefix)}(.+): ($DURATION_CHARS) \((\d+) times\)""")
        val out = mutableListOf<TimedEntry>()
        for (l in lines.drop(idx + 1)) {
            if (l.isBlank()) break
            val m = r.find(l) ?: continue
            var name = m.groupValues[1]
            var owner: String? = null
            if (hasOwner) {
                owner = name.substringBefore(' ')
                name = name.substringAfter(' ')
            }
            out += TimedEntry(owner, name, parseDuration(m.groupValues[2]), m.groupValues[3].toInt())
        }
        return out.sortedByDescending { it.durationMs }
    }
}
