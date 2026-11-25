package pl.foxsoftware.batterywatch.data

import androidx.annotation.StringRes
import pl.foxsoftware.batterywatch.R

/** Rodzaj zasilania zapisywany w bazie jako stały kod (starsze wersje zapisywały polskie nazwy). */
object Plug {
    const val AC = "ac"
    const val USB = "usb"
    const val WIRELESS = "wireless"
    const val OTHER = "other"

    private val legacy = mapOf("ładowarka" to AC, "USB" to USB, "indukcja" to WIRELESS, "zasilanie" to OTHER)

    fun normalize(code: String?): String? = code?.let { legacy[it] ?: it }

    fun isWired(code: String?) = normalize(code).let { it == AC || it == USB }

    @StringRes
    fun label(code: String?): Int? = when (normalize(code)) {
        null -> null
        AC -> R.string.plug_ac
        USB -> R.string.plug_usb
        WIRELESS -> R.string.plug_wireless
        else -> R.string.plug_other
    }
}
