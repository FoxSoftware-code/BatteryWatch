package pl.foxsoftware.batterywatch.data

data class ProcessCpu(val pid: Int, val name: String, val totalPct: Double, val userPct: Double, val kernelPct: Double)

data class CpuInfo(val window: String?, val load: String?, val processes: List<ProcessCpu>)

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
        return CpuInfo(
            window = lines.firstOrNull { it.startsWith("CPU usage from") }?.substringAfter("CPU usage from ")?.substringBefore(" (")
                ?.let { w ->
                    val ms = Regex("""(\d+)ms to (\d+)ms ago""").find(w)
                    if (ms != null) "ostatnie ${(ms.groupValues[1].toLong() - ms.groupValues[2].toLong()) / 1000} s" else w
                },
            load = lines.firstOrNull { it.startsWith("Load:") }?.substringAfter("Load: "),
            processes = procs.sortedByDescending { it.totalPct },
        )
    }
}
