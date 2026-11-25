package pl.foxsoftware.batterywatch.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.foxsoftware.batterywatch.charging.ChargingTrigger
import java.util.concurrent.TimeUnit

data class Sample(
    val timestamp: Long,
    val levelPct: Int,
    val dischargeMa: Int,
    val temperatureC: Double,
    val charging: Boolean,
    /** Udział czasu z włączonym ekranem od poprzedniej próbki (0..1). */
    val screenOnFraction: Double,
)

data class HistoryStats(
    val screenOffPctPerHour: Double?,
    val screenOnPctPerHour: Double?,
    val screenOffHours: Double,
    val screenOnHours: Double,
)

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (ts INTEGER PRIMARY KEY, level INTEGER, ma INTEGER, temp REAL, charging INTEGER, screen_on REAL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(s: Sample) {
        writableDatabase.insertWithOnConflict("samples", null, ContentValues().apply {
            put("ts", s.timestamp)
            put("level", s.levelPct)
            put("ma", s.dischargeMa)
            put("temp", s.temperatureC)
            put("charging", if (s.charging) 1 else 0)
            put("screen_on", s.screenOnFraction)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // Trzymamy 14 dni
        writableDatabase.delete("samples", "ts < ?", arrayOf((s.timestamp - 14 * 86_400_000L).toString()))
    }

    fun since(fromTs: Long): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT ts, level, ma, temp, charging, screen_on FROM samples WHERE ts >= ? ORDER BY ts",
            arrayOf(fromTs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Sample(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4) == 1, c.getDouble(5)),
                )
            }
        }

    fun last(): Sample? = readableDatabase.rawQuery(
        "SELECT ts, level, ma, temp, charging, screen_on FROM samples ORDER BY ts DESC LIMIT 1", null,
    ).use { c ->
        if (c.moveToFirst()) Sample(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4) == 1, c.getDouble(5)) else null
    }

    companion object {
        /** Tempo rozładowania osobno dla przedziałów z ekranem prawie stale zgaszonym / włączonym. */
        fun stats(samples: List<Sample>): HistoryStats {
            var offDrop = 0.0; var offH = 0.0; var onDrop = 0.0; var onH = 0.0
            for ((a, b) in samples.zipWithNext()) {
                val h = (b.timestamp - a.timestamp) / 3_600_000.0
                if (a.charging || b.charging || h <= 0 || h > 2) continue
                val drop = (a.levelPct - b.levelPct).toDouble()
                if (drop < 0) continue
                when {
                    b.screenOnFraction < 0.05 -> { offDrop += drop; offH += h }
                    b.screenOnFraction > 0.8 -> { onDrop += drop; onH += h }
                }
            }
            return HistoryStats(
                screenOffPctPerHour = if (offH >= 1) offDrop / offH else null,
                screenOnPctPerHour = if (onH >= 0.5) onDrop / onH else null,
                screenOffHours = offH,
                screenOnHours = onH,
            )
        }
    }
}

/** Co 15 min zapisuje próbkę. Bez stałej usługi, żeby narzędzie samo nie zużywało baterii. */
class HistoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = HistoryDb(applicationContext)
        val live = DeviceReader(applicationContext).live()
        val prev = db.last()
        val from = prev?.timestamp ?: (live.timestamp - 15 * 60_000)
        db.insert(
            Sample(
                timestamp = live.timestamp,
                levelPct = live.levelPct,
                dischargeMa = live.dischargeMa,
                temperatureC = live.temperatureC,
                charging = live.charging,
                screenOnFraction = screenOnFraction(applicationContext, from, live.timestamp, live.screenOn),
            ),
        )
        // Wyzwalacz monitora ładowania uzbrajamy tylko przy odłączonej ładowarce, inaczej odpalałby co 15 min
        if (!live.charging && live.plugged == null) {
            ChargingPrefs(applicationContext).suppressedUntilUnplug = false
            ChargingTrigger.arm(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "history-sampler"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HistoryWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /** Na podstawie zdarzeń SCREEN_INTERACTIVE/NON_INTERACTIVE z UsageStats. */
        fun screenOnFraction(context: Context, from: Long, to: Long, screenOnNow: Boolean): Double {
            if (to <= from) return if (screenOnNow) 1.0 else 0.0
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val events = try {
                usm.queryEvents(from, to)
            } catch (_: SecurityException) {
                return if (screenOnNow) 1.0 else 0.0
            }
            val changes = mutableListOf<Pair<Long, Boolean>>()
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> changes += e.timeStamp to true
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> changes += e.timeStamp to false
                }
            }
            if (changes.isEmpty()) return if (screenOnNow) 1.0 else 0.0
            // Stan początkowy to przeciwieństwo pierwszej zmiany
            var state = !changes.first().second
            var t = from
            var onMs = 0L
            for ((ts, s) in changes) {
                if (state) onMs += ts - t
                state = s; t = ts
            }
            if (state) onMs += to - t
            return onMs.toDouble() / (to - from)
        }
    }
}
