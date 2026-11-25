package pl.foxsoftware.batterywatch.charging

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.foxsoftware.batterywatch.MainActivity
import pl.foxsoftware.batterywatch.R
import pl.foxsoftware.batterywatch.data.ChargingPrefs

/**
 * Jednorazowe zadanie z warunkiem „ładuje się”: WorkManager uruchamia je po podłączeniu ładowarki,
 * a ono startuje [ChargingMonitorService]. Po odłączeniu usługa uzbraja je ponownie.
 *
 * Start usługi pierwszoplanowej z tła Android 12+ dopuszcza m.in. dla aplikacji wyłączonych
 * z optymalizacji baterii (setup_permissions.sh robi to przez `dumpsys deviceidle whitelist`).
 * Gdy system odmówi, pokazujemy powiadomienie, którego dotknięcie startuje monitor.
 */
class ChargingTrigger(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ChargingPrefs(ctx)
        if (!prefs.autoMonitor || prefs.suppressedUntilUnplug || ChargingMonitorService.activeSession.value != null) {
            return Result.success()
        }
        if (!ChargingMonitorService.start(ctx)) {
            prefs.lastAutoStartFailedAt = System.currentTimeMillis()
            notifyTapToStart(ctx)
        }
        return Result.success()
    }

    private fun notifyTapToStart(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ChargingMonitorService.createChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CHARGING)
            .putExtra(MainActivity.EXTRA_START_CHARGING_MONITOR, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(NotificationManager::class.java).notify(
            103,
            Notification.Builder(ctx, ChargingMonitorService.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(ctx.getString(R.string.notif_tap_title))
                .setContentText(ctx.getString(R.string.notif_tap_text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build(),
        )
    }

    companion object {
        private const val NAME = "charging-trigger"

        /** Uzbraja wyzwalacz (no-op, jeśli już czeka). */
        fun arm(context: Context) {
            if (!ChargingPrefs(context).autoMonitor) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ChargingTrigger>()
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                    .build(),
            )
        }

        fun disarm(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
