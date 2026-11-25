package pl.foxsoftware.batterywatch.domain

import pl.foxsoftware.batterywatch.R
import kotlin.math.max

/** Jedna próbka zapisana podczas ładowania (co ~10 s). */
data class ChargeSample(
    val timestamp: Long,
    val levelPct: Int,
    /** Prąd do baterii w mA (dodatni = ładowanie, ujemny = telefon bierze więcej niż daje ładowarka). */
    val currentMa: Int,
    val voltageMv: Int,
    val temperatureC: Double,
    val screenOn: Boolean,
    val chargeCounterUah: Long?,
    /** BatteryManager.BATTERY_STATUS_*. */
    val status: Int,
) {
    val powerW: Double get() = currentMa * voltageMv / 1_000_000.0
}

data class ChargeSummary(
    val durationMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val pctPerHour: Double?,
    val mahAdded: Double,
    val mahFromCounter: Boolean,
    val estimatedCapacityMah: Int?,
    val avgCurrentMa: Int,
    val peakCurrentMa: Int,
    val avgPowerW: Double,
    val peakPowerW: Double,
    val maxTempC: Double,
    val avgTempC: Double,
    val minutesAbove40C: Double,
    val minutesAtFull: Double,
    val minutesPaused: Double,
    val throttledMinutes: Double,
    val screenOnFraction: Double,
    val avgMaScreenOn: Int?,
    val avgMaScreenOff: Int?,
    /** Czas od startu do 80% (gdy start był poniżej 80%). */
    val timeTo80Ms: Long?,
    /** Szacowany czas do 100% od ostatniej próbki. */
    val etaFullMs: Long?,
)

/** [id] jest stały (do testów i kluczy UI), tekst rozwiązuje UI. */
data class ChargeNote(val id: String, val severity: Severity, val title: Txt, val detail: Txt)

object ChargingAnalyzer {

    const val STATUS_CHARGING = 2
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    /** Przerwy dłuższe niż to (np. zabity proces) nie są liczone jako czas ładowania. */
    private const val MAX_GAP_MS = 120_000L

    fun summarize(samples: List<ChargeSample>, capacityHintMah: Int = 5500): ChargeSummary? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()

        var totalMs = 0L
        var mahIntegral = 0.0
        var maMs = 0.0; var wMs = 0.0; var tempMs = 0.0
        var above40 = 0L; var atFull = 0L; var paused = 0L; var throttled = 0L
        var onMs = 0L; var onMaMs = 0.0; var offMs = 0L; var offMaMs = 0.0

        // Szczytowy prąd poniżej 80%: punkt odniesienia do wykrywania dławienia temperaturowego
        val peakBelow80 = samples.filter { it.levelPct < 80 && it.status == STATUS_CHARGING }.maxOfOrNull { it.currentMa } ?: 0

        for ((a, b) in samples.zipWithNext()) {
            val dt = b.timestamp - a.timestamp
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            totalMs += dt
            mahIntegral += a.currentMa * dt / 3_600_000.0
            maMs += a.currentMa.toDouble() * dt
            wMs += a.powerW * dt
            tempMs += a.temperatureC * dt
            if (a.temperatureC >= 40.0) above40 += dt
            if (a.levelPct >= 100) atFull += dt
            if (a.status == STATUS_NOT_CHARGING) paused += dt
            if (a.status == STATUS_CHARGING && a.levelPct < 80 && a.temperatureC >= 39.0 &&
                peakBelow80 > 500 && a.currentMa < peakBelow80 * 0.6
            ) throttled += dt
            if (a.screenOn) { onMs += dt; onMaMs += a.currentMa.toDouble() * dt } else { offMs += dt; offMaMs += a.currentMa.toDouble() * dt }
        }
        if (totalMs == 0L) return null

        val counterDelta = if (first.chargeCounterUah != null && last.chargeCounterUah != null) {
            (last.chargeCounterUah - first.chargeCounterUah) / 1000.0
        } else null
        val fromCounter = counterDelta != null && counterDelta > 0
        val mah = if (fromCounter) counterDelta!! else max(mahIntegral, 0.0)

        val gained = last.levelPct - first.levelPct
        val hours = (last.timestamp - first.timestamp) / 3_600_000.0
        val capacity = if (gained >= 15 && mah > 0) (mah * 100 / gained).toInt() else null

        val timeTo80 = if (first.levelPct < 80) {
            samples.firstOrNull { it.levelPct >= 80 }?.let { it.timestamp - first.timestamp }
        } else null

        return ChargeSummary(
            durationMs = last.timestamp - first.timestamp,
            startLevel = first.levelPct,
            endLevel = last.levelPct,
            pctPerHour = if (hours >= 5.0 / 60 && gained > 0) gained / hours else null,
            mahAdded = mah,
            mahFromCounter = fromCounter,
            estimatedCapacityMah = capacity,
            avgCurrentMa = (maMs / totalMs).toInt(),
            peakCurrentMa = samples.maxOf { it.currentMa },
            avgPowerW = wMs / totalMs,
            peakPowerW = samples.maxOf { it.powerW },
            maxTempC = samples.maxOf { it.temperatureC },
            avgTempC = tempMs / totalMs,
            minutesAbove40C = above40 / 60_000.0,
            minutesAtFull = atFull / 60_000.0,
            minutesPaused = paused / 60_000.0,
            throttledMinutes = throttled / 60_000.0,
            screenOnFraction = onMs.toDouble() / totalMs,
            avgMaScreenOn = if (onMs >= 60_000) (onMaMs / onMs).toInt() else null,
            avgMaScreenOff = if (offMs >= 60_000) (offMaMs / offMs).toInt() else null,
            timeTo80Ms = timeTo80,
            etaFullMs = etaToFull(samples, capacity ?: capacityHintMah),
        )
    }

    /**
     * Najpierw z tempa zmiany poziomu w ostatnich 10 min, a gdy poziom stoi, z prądu.
     * Powyżej 80% prąd maleje, więc wynik z prądu to dolna granica.
     */
    fun etaToFull(samples: List<ChargeSample>, capacityMah: Int): Long? {
        val last = samples.lastOrNull() ?: return null
        if (last.levelPct >= 100 || last.status != STATUS_CHARGING) return null
        val windowStart = samples.lastOrNull { last.timestamp - it.timestamp >= 10 * 60_000 }
        if (windowStart != null) {
            val gained = last.levelPct - windowStart.levelPct
            val h = (last.timestamp - windowStart.timestamp) / 3_600_000.0
            if (gained >= 2 && h > 0) return ((100 - last.levelPct) / (gained / h) * 3_600_000).toLong()
        }
        val recentMa = samples.takeLast(6).map { it.currentMa }.average()
        if (recentMa < 50) return null
        val remainingMah = capacityMah * (100 - last.levelPct) / 100.0
        return (remainingMah / recentMa * 3_600_000).toLong()
    }

    fun notes(s: ChargeSummary, maxChargerW: Double?, designCapacityMah: Int?, wired: Boolean): List<ChargeNote> = buildList {
        when {
            s.maxTempC >= 45 -> add(
                ChargeNote(
                    "temp_high", Severity.HIGH, txt(R.string.note_temp_high_title, s.maxTempC), txt(R.string.note_temp_high_detail),
                ),
            )
            s.maxTempC >= 41 -> add(
                ChargeNote(
                    "temp_warm", Severity.MEDIUM,
                    txt(R.string.note_temp_warm_title, s.maxTempC),
                    txt(R.string.note_temp_warm_detail, s.minutesAbove40C),
                ),
            )
        }
        if (s.throttledMinutes >= 3) add(
            ChargeNote(
                "throttled", Severity.MEDIUM,
                txt(R.string.note_throttled_title),
                txt(R.string.note_throttled_detail, s.throttledMinutes),
            ),
        )
        if (s.minutesAtFull >= 60) add(
            ChargeNote(
                "at_full", Severity.LOW,
                txt(R.string.note_at_full_title, s.minutesAtFull / 60),
                txt(R.string.note_at_full_detail),
            ),
        )
        if (s.screenOnFraction > 0.3 && s.avgMaScreenOn != null && s.avgMaScreenOff != null && s.avgMaScreenOff > s.avgMaScreenOn) add(
            ChargeNote(
                "screen_use", Severity.INFO,
                txt(R.string.note_screen_use_title),
                txt(R.string.note_screen_use_detail, s.screenOnFraction * 100, s.avgMaScreenOn, s.avgMaScreenOff),
            ),
        )
        if (wired && s.startLevel < 70 && s.peakPowerW in 0.1..10.0) add(
            ChargeNote(
                "slow", Severity.LOW,
                txt(R.string.note_slow_title, s.peakPowerW),
                joinTxt(maxChargerW?.let { txt(R.string.note_slow_charger, it) }, txt(R.string.note_slow_detail)),
            ),
        )
        if (s.minutesPaused >= 10) add(
            ChargeNote(
                "paused", Severity.INFO,
                txt(R.string.note_paused_title, s.minutesPaused),
                txt(R.string.note_paused_detail),
            ),
        )
        val cap = s.estimatedCapacityMah
        if (cap != null && designCapacityMah != null && designCapacityMah > 0) {
            val pct = cap * 100.0 / designCapacityMah
            add(
                ChargeNote(
                    "capacity", if (pct < 80) Severity.MEDIUM else Severity.INFO,
                    txt(R.string.note_capacity_title, cap, pct),
                    txt(
                        if (s.mahFromCounter) R.string.note_capacity_detail_counter else R.string.note_capacity_detail_integral,
                        s.startLevel, s.endLevel,
                    ),
                ),
            )
        }
    }

    /** Zmniejsza liczbę punktów do wykresu (średnia w kubełkach). */
    fun downsample(values: List<Float>, maxPoints: Int = 300): List<Float> {
        if (values.size <= maxPoints) return values
        val bucket = values.size.toDouble() / maxPoints
        return List(maxPoints) { i ->
            val from = (i * bucket).toInt()
            val to = ((i + 1) * bucket).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
            values.subList(from, to).average().toFloat()
        }
    }
}
