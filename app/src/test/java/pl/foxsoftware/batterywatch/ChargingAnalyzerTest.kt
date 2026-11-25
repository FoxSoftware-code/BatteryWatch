package pl.foxsoftware.batterywatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.foxsoftware.batterywatch.domain.ChargeSample
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer.STATUS_CHARGING
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer.STATUS_FULL
import pl.foxsoftware.batterywatch.domain.Severity

class ChargingAnalyzerTest {

    private val step = 10_000L

    /** Ładowanie 20→100% stałym prądem 5500 mA (1%/36 s przy 5500 mAh), potem 70 min na 100%. */
    private fun session(hotThrottle: Boolean = false): List<ChargeSample> {
        val out = mutableListOf<ChargeSample>()
        var t = 0L
        var mah = 0.0
        var level = 20.0
        while (level < 100) {
            val hot = hotThrottle && level in 40.0..60.0
            val ma = if (hot) 2000 else 5500
            out += ChargeSample(t, level.toInt(), ma, 4200, if (hot) 41.0 else 35.0, false, (1_100_000 + mah * 1000).toLong(), STATUS_CHARGING)
            val dmah = ma * step / 3_600_000.0
            mah += dmah
            level += dmah / 5500 * 100
            t += step
        }
        repeat(420) {
            out += ChargeSample(t, 100, 0, 4450, 33.0, false, (1_100_000 + mah * 1000).toLong(), STATUS_FULL)
            t += step
        }
        return out
    }

    @Test
    fun summaryOfFullCharge() {
        val s = ChargingAnalyzer.summarize(session())!!
        assertEquals(20, s.startLevel)
        assertEquals(100, s.endLevel)
        assertTrue(s.mahFromCounter)
        assertEquals(4400.0, s.mahAdded, 30.0)
        assertEquals(5500.0, s.estimatedCapacityMah!!.toDouble(), 100.0)
        assertEquals(5500, s.peakCurrentMa)
        assertEquals(23.1, s.peakPowerW, 0.01)
        assertEquals(70.0, s.minutesAtFull, 1.0)
        assertEquals(0.0, s.throttledMinutes, 0.01)
        // 80% po ~36 min (60% × 5500 mAh / 5500 mA = 0,6 h)
        assertEquals(36.0, s.timeTo80Ms!! / 60_000.0, 0.5)
        assertNull(s.etaFullMs)

        val notes = ChargingAnalyzer.notes(s, maxChargerW = 80.0, designCapacityMah = 5500, wired = true)
        assertTrue(notes.any { it.id == "at_full" })
        assertTrue(notes.any { it.id == "capacity" && it.severity == Severity.INFO })
    }

    @Test
    fun detectsThermalThrottling() {
        val s = ChargingAnalyzer.summarize(session(hotThrottle = true))!!
        assertTrue(s.throttledMinutes > 20)
        assertTrue(s.minutesAbove40C > 20)
        val titles = ChargingAnalyzer.notes(s, null, 5500, wired = true).map { it.id }
        assertTrue(titles.any { it == "throttled" })
        assertTrue(titles.any { it == "temp_warm" })
    }

    @Test
    fun etaFromLevelRate() {
        // 1% na minutę przez 15 min, poziom 50 → 50 min do pełna
        val samples = (0..90).map { i ->
            val t = i * step
            ChargeSample(t, 35 + (t / 60_000).toInt(), 3000, 4100, 30.0, true, null, STATUS_CHARGING)
        }
        val eta = ChargingAnalyzer.etaToFull(samples, 5500)
        assertNotNull(eta)
        assertEquals(50.0, eta!! / 60_000.0, 3.0)
    }

    @Test
    fun gapsAreIgnoredAndShortSessionsHaveNoSummary() {
        assertNull(ChargingAnalyzer.summarize(listOf(ChargeSample(0, 50, 1000, 4000, 30.0, false, null, STATUS_CHARGING))))
        val gap = listOf(
            ChargeSample(0, 50, 1000, 4000, 30.0, false, null, STATUS_CHARGING),
            ChargeSample(10_000, 50, 1000, 4000, 30.0, false, null, STATUS_CHARGING),
            ChargeSample(3_600_000, 70, 1000, 4000, 30.0, false, null, STATUS_CHARGING),
        )
        val s = ChargingAnalyzer.summarize(gap)!!
        // Liczy się tylko 10 s między pierwszymi próbkami, nie godzina przerwy
        assertEquals(1000 * 10_000 / 3_600_000.0, s.mahAdded, 0.01)
        assertFalse(s.mahFromCounter)
    }

    @Test
    fun downsampleKeepsShape() {
        val v = List(3000) { it.toFloat() }
        val d = ChargingAnalyzer.downsample(v, 300)
        assertEquals(300, d.size)
        assertEquals(4.5f, d.first(), 0.01f)
        assertEquals(2994.5f, d.last(), 0.01f)
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)
}
