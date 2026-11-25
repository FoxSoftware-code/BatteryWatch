package pl.foxsoftware.batterywatch.data

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class PermissionDeniedException(message: String) : Exception(message)

/** Uruchamia polecenia jako proces aplikacji (dumpsys działa dzięki nadanemu przez adb uprawnieniu DUMP). */
object ShellRunner {

    suspend fun dumpsys(vararg args: String): String = withContext(Dispatchers.IO) {
        val out = exec(listOf("dumpsys", *args))
        if (out.startsWith("Permission Denial")) {
            val missing = Regex("""android\.permission\.(\w+)""").find(out)?.groupValues?.get(1) ?: "?"
            throw PermissionDeniedException("System odmówił dostępu (brak $missing). Uruchom setup_permissions.sh na komputerze.")
        }
        out
    }

    private fun exec(cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }
}

/**
 * Polecenia z uprawnieniami powłoki (uid shell) przez Shizuku.
 * Shizuku jest opcjonalne; bez niego akcje mają ścieżkę zastępczą (otwarcie ustawień).
 */
object ShizukuShell {

    const val REQUEST_CODE = 4242

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = isRunning() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        if (isRunning()) Shizuku.requestPermission(REQUEST_CODE)
    }

    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    /** Shizuku API 13 ukrywa newProcess; to jest najprostsza droga bez pisania UserService. */
    suspend fun run(command: String): Result = withContext(Dispatchers.IO) {
        check(hasPermission()) { "Shizuku nie działa albo nie ma uprawnienia" }
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = p.inputStream.bufferedReader().use { it.readText() } +
            p.errorStream.bufferedReader().use { it.readText() }
        Result(p.waitFor(), out.trim())
    }
}
