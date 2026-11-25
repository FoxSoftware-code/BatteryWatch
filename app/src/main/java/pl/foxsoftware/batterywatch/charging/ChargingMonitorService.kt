package pl.foxsoftware.batterywatch.charging

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.foxsoftware.batterywatch.MainActivity
import pl.foxsoftware.batterywatch.data.ChargingDb
import pl.foxsoftware.batterywatch.data.ChargingPrefs
import pl.foxsoftware.batterywatch.data.DeviceReader
import pl.foxsoftware.batterywatch.domain.ChargeSample

/**
 * Usługa pierwszoplanowa działająca tylko, gdy telefon jest podłączony do zasilania.
 * Co 10 s zapisuje próbkę (poziom, prąd, napięcie, temperatura, ekran, licznik ładunku, status)
 * i kończy się sama po odłączeniu ładowarki.
 */
class ChargingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: ChargingDb
    private lateinit var reader: DeviceReader
    private lateinit var prefs: ChargingPrefs
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId = -1L
    private var loopStarted = false

    private var targetNotified = false
    private var tempNotifiedAt = 0L

    private val unplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { finish(unplugged = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = ChargingDb(this)
        reader = DeviceReader(this)
        prefs = ChargingPrefs(this)
        createChannels(this)
        registerReceiver(unplugReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.suppressedUntilUnplug = true
            scope.launch { finish(unplugged = false) }
            return START_NOT_STICKY
        }
        val n = buildNotification("Uruchamianie…", null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!loopStarted) {
            loopStarted = true
            prefs.suppressedUntilUnplug = false
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val charger = reader.charger()
        if (!charger.connected) {
            finish(unplugged = true)
            return
        }
        // Kontynuacja sesji po restarcie procesu, jeśli przerwa była krótka
        val open = db.openSession()
        sessionId = if (open != null && System.currentTimeMillis() - (db.lastSampleTs(open.id) ?: 0) < 5 * 60_000) {
            open.id
        } else {
            open?.let { db.endSession(it.id, db.lastSampleTs(it.id) ?: it.startTs) }
            db.startSession(System.currentTimeMillis(), charger.plugged, charger.maxChargerW)
        }
        _activeSession.value = sessionId
        acquireWakeLock()

        var unpluggedReads = 0
        while (scope.isActive && sessionId > 0) {
            val c = reader.charger()
            if (!c.connected) {
                if (++unpluggedReads >= 2) break
            } else {
                unpluggedReads = 0
                val live = reader.live()
                val sample = ChargeSample(
                    timestamp = live.timestamp,
                    levelPct = live.levelPct,
                    currentMa = -live.dischargeMa,
                    voltageMv = live.voltageMv,
                    temperatureC = live.temperatureC,
                    screenOn = live.screenOn,
                    chargeCounterUah = live.chargeCounterUah,
                    status = c.status,
                )
                db.insert(sessionId, sample)
                c.maxChargerW?.let { db.updateMaxChargerW(sessionId, it) }
                _lastSample.value = sample
                updateNotification(sample, c.plugged)
                checkAlerts(sample)
            }
            delay(SAMPLE_INTERVAL_MS)
        }
        finish(unplugged = true)
    }

    private fun checkAlerts(s: ChargeSample) {
        val nm = getSystemService(NotificationManager::class.java)
        val target = prefs.targetLevel
        if (target > 0 && s.levelPct >= target && !targetNotified) {
            targetNotified = true
            nm.notify(
                ALERT_TARGET_ID,
                alert("Bateria naładowana do ${s.levelPct}%", "Możesz odłączyć ładowarkę (ustawiony próg: $target%)."),
            )
        }
        val alarm = prefs.tempAlarmC
        val now = System.currentTimeMillis()
        if (alarm > 0 && s.temperatureC >= alarm && now - tempNotifiedAt > 15 * 60_000) {
            tempNotifiedAt = now
            nm.notify(
                ALERT_TEMP_ID,
                alert(
                    "Telefon grzeje się podczas ładowania: %.1f °C".format(s.temperatureC),
                    "Odłóż telefon, zdejmij etui albo przenieś go w chłodniejsze miejsce.",
                ),
            )
        }
    }

    @Synchronized
    private fun finish(unplugged: Boolean) {
        if (sessionId > 0) {
            db.endSession(sessionId, System.currentTimeMillis())
            sessionId = -1
        }
        _activeSession.value = null
        _lastSample.value = null
        if (unplugged) {
            prefs.suppressedUntilUnplug = false
            ChargingTrigger.arm(this)
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Bez blokady CPU usypia przy zgaszonym ekranie i próbki byłyby rzadkie. Na zasilaniu koszt jest pomijalny. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryWatch:charging")
            .apply { acquire(12 * 3_600_000L) }
    }

    private fun updateNotification(s: ChargeSample, plug: String?) {
        val text = "%d%% · %d mA · %.1f W · %.1f °C".format(s.levelPct, s.currentMa, s.powerW, s.temperatureC)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, plug))
    }

    private fun buildNotification(text: String, plug: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CHARGING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ChargingMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Monitorowanie ładowania" + (plug?.let { " ($it)" } ?: ""))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Zatrzymaj", stop).build())
            .build()
    }

    private fun alert(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CHARGING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(unplugReceiver)
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        _activeSession.value = null
        super.onDestroy()
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 10_000L
        private const val ACTION_STOP = "pl.foxsoftware.batterywatch.STOP_CHARGING_MONITOR"
        private const val CHANNEL_MONITOR = "charging_monitor"
        const val CHANNEL_ALERTS = "charging_alerts"
        private const val NOTIF_ID = 100
        private const val ALERT_TARGET_ID = 101
        private const val ALERT_TEMP_ID = 102

        private val _activeSession = MutableStateFlow<Long?>(null)
        /** Id bieżącej sesji, gdy usługa działa (w tym samym procesie co UI). */
        val activeSession: StateFlow<Long?> = _activeSession.asStateFlow()

        private val _lastSample = MutableStateFlow<ChargeSample?>(null)
        val lastSample: StateFlow<ChargeSample?> = _lastSample.asStateFlow()

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MONITOR, "Monitorowanie ładowania", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Alerty ładowania", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        /** @return false, gdy system nie pozwolił uruchomić usługi z tła. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ChargingMonitorService::class.java))
            true
        } catch (_: ForegroundServiceStartNotAllowedException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

        fun stop(context: Context) {
            if (_activeSession.value == null) return
            context.startService(Intent(context, ChargingMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
