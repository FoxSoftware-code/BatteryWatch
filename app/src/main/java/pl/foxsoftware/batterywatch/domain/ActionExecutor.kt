package pl.foxsoftware.batterywatch.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import pl.foxsoftware.batterywatch.data.ShizukuShell

/** Wpis w dzienniku zmian, z danymi do cofnięcia. */
data class AppliedAction(
    val id: Long,
    val description: String,
    val kind: String, // "global" | "system" | "shell"
    val key: String,
    val undoValue: String,
)

class ActionExecutor(private val context: Context) {

    private val prefs = context.getSharedPreferences("actions", Context.MODE_PRIVATE)

    /** @return komunikat dla użytkownika. */
    suspend fun execute(action: FixAction): String = when (action) {
        is FixAction.SetGlobal -> {
            val cr = context.contentResolver
            val old = Settings.Global.getString(cr, action.key) ?: "0"
            try {
                Settings.Global.putInt(cr, action.key, action.value)
                log(action.label, "global", action.key, old)
                "Zrobione: ${action.label}"
            } catch (_: SecurityException) {
                "Brak uprawnienia WRITE_SECURE_SETTINGS (uruchom setup_permissions.sh)"
            }
        }

        is FixAction.SetSystem -> {
            if (!Settings.System.canWrite(context)) {
                open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                "Zezwól na modyfikację ustawień systemu i spróbuj ponownie"
            } else {
                val cr = context.contentResolver
                val old = Settings.System.getString(cr, action.key) ?: "0"
                Settings.System.putInt(cr, action.key, action.value)
                log(action.label, "system", action.key, old)
                "Zrobione: ${action.label}"
            }
        }

        is FixAction.ShellCommand -> {
            if (ShizukuShell.hasPermission()) {
                val r = ShizukuShell.run(action.command)
                if (r.ok) {
                    action.undoCommand?.let { log(action.label, "shell", action.command, it) }
                    "Zrobione: ${action.label}"
                } else {
                    "Błąd: ${r.output.take(200)}"
                }
            } else {
                action.fallback?.let { execute(it) } ?: "Ta akcja wymaga Shizuku"
            }
        }

        is FixAction.OpenSettings -> {
            val intent = Intent(action.intentAction)
            action.packageName?.let { intent.data = Uri.parse("package:$it") }
            open(intent)
            "Otwieram: ${action.label}"
        }

        is FixAction.RestrictApp -> restrict(action.packageName)
    }

    private suspend fun restrict(pkg: String): String {
        if (!ShizukuShell.hasPermission()) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            return "Bez Shizuku: w ustawieniach aplikacji wybierz Bateria → Ograniczone"
        }
        val oldBucket = ShizukuShell.run("am get-standby-bucket $pkg").output.trim().toIntOrNull() ?: 10
        val oldOp = Regex("""RUN_ANY_IN_BACKGROUND: (\w+)""")
            .find(ShizukuShell.run("cmd appops get $pkg RUN_ANY_IN_BACKGROUND").output)?.groupValues?.get(1) ?: "allow"
        val r = ShizukuShell.run(
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore && am set-standby-bucket $pkg restricted",
        )
        if (!r.ok) return "Błąd: ${r.output.take(200)}"
        log(
            "Ograniczono w tle: $pkg", "shell", pkg,
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND $oldOp && am set-standby-bucket $pkg $oldBucket",
        )
        return "Ograniczono $pkg w tle"
    }

    suspend fun forceStop(pkg: String): String {
        if (!ShizukuShell.hasPermission()) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            return "Bez Shizuku: użyj przycisku Wymuś zatrzymanie"
        }
        val r = ShizukuShell.run("am force-stop $pkg")
        return if (r.ok) "Zatrzymano $pkg" else "Błąd: ${r.output.take(200)}"
    }

    suspend fun undo(a: AppliedAction): String {
        val cr = context.contentResolver
        val msg = try {
            when (a.kind) {
                "global" -> { Settings.Global.putString(cr, a.key, a.undoValue); "Cofnięto: ${a.description}" }
                "system" -> {
                    if (!Settings.System.canWrite(context)) return "Brak uprawnienia do zmiany ustawień systemu"
                    Settings.System.putString(cr, a.key, a.undoValue); "Cofnięto: ${a.description}"
                }
                "shell" -> {
                    if (!ShizukuShell.hasPermission()) return "Cofnięcie wymaga działającego Shizuku"
                    val r = ShizukuShell.run(a.undoValue)
                    if (r.ok) "Cofnięto: ${a.description}" else return "Błąd: ${r.output.take(200)}"
                }
                else -> return "Nieznany typ akcji"
            }
        } catch (e: SecurityException) {
            return "Brak uprawnień: ${e.message}"
        }
        save(history().filterNot { it.id == a.id })
        return msg
    }

    fun history(): List<AppliedAction> {
        val arr = JSONArray(prefs.getString("log", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AppliedAction(o.getLong("id"), o.getString("d"), o.getString("k"), o.getString("key"), o.getString("u"))
        }
    }

    private fun log(description: String, kind: String, key: String, undoValue: String) {
        // Przy powtórzeniu tej samej zmiany zachowujemy pierwotną wartość do cofnięcia
        val existing = history()
        if (existing.any { it.kind == kind && it.key == key }) return
        save(existing + AppliedAction(System.currentTimeMillis(), description, kind, key, undoValue))
    }

    private fun save(list: List<AppliedAction>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("d", it.description).put("k", it.kind).put("key", it.key).put("u", it.undoValue))
        }
        prefs.edit().putString("log", arr.toString()).apply()
    }

    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
