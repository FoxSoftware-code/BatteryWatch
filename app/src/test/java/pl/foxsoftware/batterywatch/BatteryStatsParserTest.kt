package pl.foxsoftware.batterywatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.foxsoftware.batterywatch.data.BatteryStatsParser
import pl.foxsoftware.batterywatch.data.BatteryStatsReport
import pl.foxsoftware.batterywatch.domain.DeviceSnapshot
import pl.foxsoftware.batterywatch.domain.FindingsEngine
import pl.foxsoftware.batterywatch.domain.Severity

/** Zanonimizowany zrzut z OnePlus CPH2609 (Android 16), na którym zrobiono ręczną diagnozę. */
class BatteryStatsParserTest {

    private val dump = javaClass.classLoader!!.getResource("batterystats_cph2609.txt")!!.readText()
    private val report = BatteryStatsParser.parse(dump)

    @Test
    fun durationsAndNumbers() {
        assertEquals(7_891_572L, BatteryStatsParser.parseDuration("2h 11m 31s 572ms"))
        assertEquals(10456, BatteryStatsParser.parseUid("u0a456"))
        assertEquals(1000, BatteryStatsParser.parseUid("1000"))
        assertEquals((757.28 * 1024 * 1024).toLong(), BatteryStatsParser.parseBytes("757,28MB"))
    }

    @Test
    fun summary() {
        assertEquals(13 * 3_600_000L + 14 * 60_000 + 33_828, report.timeOnBatteryMs)
        assertEquals(3906.0, report.dischargeMah, 0.01)
        assertEquals(1144.0, report.screenOffDischargeMah, 0.01)
        assertEquals(2762.0, report.screenOnDischargeMah, 0.01)
        assertEquals(37, report.dischargedPctScreenOff)
        assertEquals(55, report.dischargedPctScreenOn)
        assertEquals(2304, report.connectivityChanges)
        assertEquals(180, report.screenOnCount)
        assertEquals(5500, report.estimatedCapacityMah)
        assertEquals(48.6, report.brightnessPct["bright"]!!, 0.01)
        assertEquals(11.4, report.cellPoorSignalPct, 0.01)
        assertTrue(report.screenOffPctPerHour in 4.0..5.0)
    }

    @Test
    fun globalFlashlightScreenOff() {
        val f = report.globalOnBatteryScreenOff("flashlight")!!
        assertEquals(137.0, f.mah, 0.5)
        assertEquals(45 * 60_000L + 44_861, f.durationMs)
    }

    @Test
    fun apps() {
        val callSummaries = report.apps.first { it.uid == 10456 }
        assertEquals(2291.0, callSummaries.totalMah, 0.5)
        assertTrue("większość podczas ładowania", callSummaries.chargingMah > 2000)
        assertTrue(callSummaries.onBatteryMah < 20)

        val gesture = report.apps.first { it.uid == 10221 }
        assertEquals(137.0, gesture.screenOffComponents["flashlight"]!!.mah, 0.5)

        val facebook = report.apps.first { it.uid == 10340 }
        assertEquals(5 * 3_600_000L + 42 * 60_000 + 17_749, facebook.backgroundMs)
        assertTrue(facebook.wakeupAlarms >= 62)
    }

    @Test
    fun wakelocksAndWakeups() {
        val top = report.partialWakelocks.first()
        assertEquals("u0a285", top.owner)
        assertEquals("AudioMix", top.name)
        assertTrue(report.partialWakelocks.any { it.name == "WorkManager: ProcessorForegroundLck" })
        assertEquals("PowerManagerService.WakeLocks", report.kernelWakelocks.first().name)
        assertEquals(1345, report.wakeupReasons.first { it.name.contains("[timerfd]") && it.durationMs > 3_600_000 }.count)
    }

    @Test
    fun findingsMatchManualDiagnosis() {
        val snapshot = DeviceSnapshot(
            screenOffTimeoutMs = 1_800_000, autoBrightness = false, wifiScanAlways = true,
            bleScanAlways = true, screenOffGestures = true, levelPct = 45, chargeCounterUah = 1_912_000,
        )
        val findings = FindingsEngine.evaluate(report, snapshot, appName = { uid -> "uid$uid" })
        assertTrue(findings.any { it.id == "screen_off_drain" && it.severity == Severity.HIGH })
        assertTrue(findings.any { it.id == "flashlight_screen_off" })
        assertTrue(findings.any { it.id == "connectivity" })
        assertTrue(findings.any { it.id == "timeout" })
        assertTrue(findings.any { it.id == "charging_heavy_10456" })
        assertTrue(findings.any { it.id == "bg_10340" })
        assertTrue("oplus.gesture to latarka, nie praca w tle", findings.none { it.id == "bg_10221" })
        assertTrue(findings.any { it.id == "alarms_10442" })
        assertTrue(findings.any { it.id == "battery_health" })
        assertEquals(findings.sortedBy { it.severity.ordinal }, findings)
    }

    @Test
    fun emptyReportDoesNotCrash() {
        val r = BatteryStatsParser.parse("Statistics since last charge:\n  Time on battery: 1m 0s 0ms (1%) realtime\n")
        assertEquals(60_000L, r.timeOnBatteryMs)
        assertEquals(0.0, BatteryStatsReport.perHour(5.0, 0), 0.0)
    }
}
