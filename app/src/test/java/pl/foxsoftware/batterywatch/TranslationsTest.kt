package pl.foxsoftware.batterywatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import pl.foxsoftware.batterywatch.data.BatteryStatsParser
import pl.foxsoftware.batterywatch.domain.ChargeSummary
import pl.foxsoftware.batterywatch.domain.ChargingAnalyzer
import pl.foxsoftware.batterywatch.domain.DeviceSnapshot
import pl.foxsoftware.batterywatch.domain.FindingsEngine
import pl.foxsoftware.batterywatch.domain.Txt
import pl.foxsoftware.batterywatch.domain.resolve
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Sprawdza tłumaczenia bez Androida: każdy tekst z reguł diagnozy i analizy ładowania
 * formatuje się w obu językach, a pliki strings.xml mają te same klucze i argumenty.
 * Łapie np. %d z liczbą zmiennoprzecinkową, który na telefonie skończyłby się wyjątkiem.
 */
class TranslationsTest {

    private val languages = mapOf("en" to "values", "pl" to "values-pl")
    private val strings = languages.mapValues { (_, dir) -> load(File("src/main/res/$dir/strings.xml")) }
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val formatArg = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[sdfxc]""")

    private fun load(f: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }

    private fun render(t: Txt, lang: String): String = t.resolve { id, args ->
        val text = strings.getValue(lang)[names.getValue(id)] ?: error("Brak ${names[id]} w $lang")
        if (args.isEmpty()) text else String.format(Locale.forLanguageTag(lang), text, *args)
    }

    private fun assertRenders(texts: List<Txt>) {
        assertTrue(texts.isNotEmpty())
        for (lang in languages.keys) for (t in texts) {
            val out = render(t, lang)
            assertFalse("$lang: niesformatowany argument w \"$out\"", out.contains(formatArg) || out.contains("%%"))
        }
    }

    @Test
    fun sameKeysAndArgumentsInAllLanguages() {
        val en = strings.getValue("en")
        for ((lang, map) in strings) {
            assertEquals("klucze w $lang", en.keys, map.keys)
            for ((k, v) in map) {
                assertEquals("argumenty $k w $lang", formatArg.findAll(en.getValue(k).replace("%%", "")).map { it.value }.sorted().toList(),
                    formatArg.findAll(v.replace("%%", "")).map { it.value }.sorted().toList())
            }
        }
    }

    @Test
    fun findingsRenderInAllLanguages() {
        val dump = javaClass.classLoader!!.getResource("batterystats_cph2609.txt")!!.readText()
        val report = BatteryStatsParser.parse(dump)
        val snapshot = DeviceSnapshot(
            screenOffTimeoutMs = 1_800_000, autoBrightness = false, wifiScanAlways = true,
            bleScanAlways = true, screenOffGestures = true, levelPct = 45, chargeCounterUah = 1_912_000,
        )
        val findings = FindingsEngine.evaluate(report, snapshot, { "App $it" }, { "com.example.gesture" }, { "com.example.app" })
        assertTrue("za mało ustaleń do sprawdzenia", findings.size >= 10)
        assertRenders(findings.flatMap { f -> listOf(f.title, f.detail) + f.actions.map { it.label } })
    }

    @Test
    fun chargingNotesRenderInAllLanguages() {
        val hot = summary(maxTempC = 46.0)
        val warm = summary(maxTempC = 42.0, mahFromCounter = false)
        val notes = ChargingAnalyzer.notes(hot, 33.0, 5500, wired = true) + ChargingAnalyzer.notes(warm, null, 5500, wired = true)
        val ids = notes.map { it.id }.toSet()
        assertEquals(setOf("temp_high", "temp_warm", "throttled", "at_full", "screen_use", "slow", "paused", "capacity"), ids)
        assertRenders(notes.flatMap { listOf(it.title, it.detail) })
    }

    private fun summary(maxTempC: Double, mahFromCounter: Boolean = true) = ChargeSummary(
        durationMs = 7_200_000, startLevel = 20, endLevel = 100, pctPerHour = 40.0, mahAdded = 4400.0,
        mahFromCounter = mahFromCounter, estimatedCapacityMah = 5200, avgCurrentMa = 1500, peakCurrentMa = 2000,
        avgPowerW = 6.0, peakPowerW = 8.0, maxTempC = maxTempC, avgTempC = 36.0, minutesAbove40C = 12.0,
        minutesAtFull = 90.0, minutesPaused = 15.0, throttledMinutes = 5.0, screenOnFraction = 0.5,
        avgMaScreenOn = 800, avgMaScreenOff = 1800, timeTo80Ms = 3_600_000, etaFullMs = null,
    )
}
