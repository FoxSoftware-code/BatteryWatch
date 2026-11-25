package pl.foxsoftware.batterywatch.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import pl.foxsoftware.batterywatch.domain.ChargeSample

data class ChargeSession(
    val id: Long,
    val startTs: Long,
    val endTs: Long?,
    val plug: String?,
    val maxChargerW: Double?,
    val startLevel: Int?,
    val endLevel: Int?,
    val maxTempC: Double?,
    val peakMa: Int?,
) {
    val active get() = endTs == null
}

class ChargingDb(context: Context) : SQLiteOpenHelper(context, "charging.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER, plug TEXT, max_charger_w REAL)",
        )
        db.execSQL(
            "CREATE TABLE samples (session_id INTEGER, ts INTEGER, level INTEGER, ma INTEGER, mv INTEGER, temp REAL, " +
                "screen_on INTEGER, counter INTEGER, status INTEGER)",
        )
        db.execSQL("CREATE INDEX samples_session ON samples(session_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startSession(ts: Long, plug: String?, maxChargerW: Double?): Long {
        // Trzymamy 30 dni
        val cutoff = (ts - 30 * 86_400_000L).toString()
        writableDatabase.delete("samples", "session_id IN (SELECT id FROM sessions WHERE start_ts < ?)", arrayOf(cutoff))
        writableDatabase.delete("sessions", "start_ts < ?", arrayOf(cutoff))
        return writableDatabase.insert("sessions", null, ContentValues().apply {
            put("start_ts", ts)
            put("plug", plug)
            put("max_charger_w", maxChargerW)
        })
    }

    fun endSession(id: Long, ts: Long) {
        writableDatabase.update("sessions", ContentValues().apply { put("end_ts", ts) }, "id = ?", arrayOf(id.toString()))
        // Sesje z jedną-dwiema próbkami (np. chwilowe podłączenie) nic nie wnoszą
        val n = writableDatabase.rawQuery("SELECT COUNT(*) FROM samples WHERE session_id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (n < 3) deleteSession(id)
    }

    fun deleteSession(id: Long) {
        writableDatabase.delete("samples", "session_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
    }

    fun updateMaxChargerW(id: Long, w: Double) {
        writableDatabase.execSQL(
            "UPDATE sessions SET max_charger_w = ? WHERE id = ? AND (max_charger_w IS NULL OR max_charger_w < ?)",
            arrayOf<Any>(w, id, w),
        )
    }

    fun insert(sessionId: Long, s: ChargeSample) {
        writableDatabase.insert("samples", null, ContentValues().apply {
            put("session_id", sessionId)
            put("ts", s.timestamp)
            put("level", s.levelPct)
            put("ma", s.currentMa)
            put("mv", s.voltageMv)
            put("temp", s.temperatureC)
            put("screen_on", if (s.screenOn) 1 else 0)
            put("counter", s.chargeCounterUah)
            put("status", s.status)
        })
    }

    fun samples(sessionId: Long): List<ChargeSample> = readableDatabase.rawQuery(
        "SELECT ts, level, ma, mv, temp, screen_on, counter, status FROM samples WHERE session_id = ? ORDER BY ts",
        arrayOf(sessionId.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                ChargeSample(
                    timestamp = c.getLong(0),
                    levelPct = c.getInt(1),
                    currentMa = c.getInt(2),
                    voltageMv = c.getInt(3),
                    temperatureC = c.getDouble(4),
                    screenOn = c.getInt(5) == 1,
                    chargeCounterUah = if (c.isNull(6)) null else c.getLong(6),
                    status = c.getInt(7),
                ),
            )
        }
    }

    fun lastSampleTs(sessionId: Long): Long? = readableDatabase.rawQuery(
        "SELECT MAX(ts) FROM samples WHERE session_id = ?", arrayOf(sessionId.toString()),
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun openSession(): ChargeSession? = sessions(1).firstOrNull { it.active }

    fun sessions(limit: Int = 30): List<ChargeSession> = readableDatabase.rawQuery(
        """
        SELECT s.id, s.start_ts, s.end_ts, s.plug, s.max_charger_w, MIN(m.level), MAX(m.level), MAX(m.temp), MAX(m.ma)
        FROM sessions s LEFT JOIN samples m ON m.session_id = s.id
        GROUP BY s.id ORDER BY s.start_ts DESC LIMIT ?
        """.trimIndent(),
        arrayOf(limit.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                ChargeSession(
                    id = c.getLong(0),
                    startTs = c.getLong(1),
                    endTs = if (c.isNull(2)) null else c.getLong(2),
                    plug = c.getString(3),
                    maxChargerW = if (c.isNull(4)) null else c.getDouble(4),
                    startLevel = if (c.isNull(5)) null else c.getInt(5),
                    endLevel = if (c.isNull(6)) null else c.getInt(6),
                    maxTempC = if (c.isNull(7)) null else c.getDouble(7),
                    peakMa = if (c.isNull(8)) null else c.getInt(8),
                ),
            )
        }
    }
}

/** Ustawienia monitora ładowania. */
class ChargingPrefs(context: Context) {
    private val sp = context.getSharedPreferences("charging", Context.MODE_PRIVATE)

    /** Start monitorowania automatycznie po podłączeniu ładowarki. */
    var autoMonitor: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Powiadomienie po osiągnięciu poziomu (0 = wyłączone). */
    var targetLevel: Int
        get() = sp.getInt("target", 0)
        set(v) = sp.edit().putInt("target", v).apply()

    /** Alarm temperatury w °C (0 = wyłączony). */
    var tempAlarmC: Int
        get() = sp.getInt("temp_alarm", 42)
        set(v) = sp.edit().putInt("temp_alarm", v).apply()

    /** Użytkownik zatrzymał monitor ręcznie; nie wznawiamy automatycznie do odłączenia ładowarki. */
    var suppressedUntilUnplug: Boolean
        get() = sp.getBoolean("suppressed", false)
        set(v) = sp.edit().putBoolean("suppressed", v).apply()

    /** Ostatnia nieudana próba startu w tle (system zablokował usługę pierwszoplanową). */
    var lastAutoStartFailedAt: Long
        get() = sp.getLong("auto_fail", 0)
        set(v) = sp.edit().putLong("auto_fail", v).apply()
}
