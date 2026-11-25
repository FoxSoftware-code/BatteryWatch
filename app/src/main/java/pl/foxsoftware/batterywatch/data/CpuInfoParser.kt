package pl.foxsoftware.batterywatch.data

data class ProcessCpu(val pid: Int, val name: String, val totalPct: Double, val userPct: Double, val kernelPct: Double)

/** [windowSeconds] gdy udało się odczytać długość okna pomiaru, inaczej surowy opis w [windowRaw]. */
data class CpuInfo(val windowSeconds: Long?, val windowRaw: String?, val load: String?, val processes: List<ProcessCpu>)

/** Parser `dumpsys cpuinfo` (średnie obciążenie z ostatnich kilkudziesięciu sekund). */
object CpuInfoParser {

    private val line = Regex("""^\s*([\d.,]+)% (\d+)/(\S+): ([\d.,]+)% user \+ ([\d.,]+)% kernel""")

    fun parse(text: String): CpuInfo {
        val lines = text.lines()
        val procs = lines.mapNotNull { l ->
            line.find(l)?.let {
                ProcessCpu(
                    pid = it.groupValues[2].toInt(),
                    name = it.groupValues[3],
                    totalPct = BatteryStatsParser.parseNumber(it.groupValues[1]),
                    userPct = BatteryStatsParser.parseNumber(it.groupValues[4]),
                    kernelPct = BatteryStatsParser.parseNumber(it.groupValues[5]),
                )
            }
        }
        val window = lines.firstOrNull { it.startsWith("CPU usage from") }?.substringAfter("CPU usage from ")?.substringBefore(" (")
        val ms = window?.let { Regex("""(\d+)ms to (\d+)ms ago""").find(it) }
        return CpuInfo(
            windowSeconds = ms?.let { (it.groupValues[1].toLong() - it.groupValues[2].toLong()) / 1000 },
            windowRaw = if (ms == null) window else null,
            load = lines.firstOrNull { it.startsWith("Load:") }?.substringAfter("Load: "),
            processes = procs.sortedByDescending { it.totalPct },
        )
    }
}
