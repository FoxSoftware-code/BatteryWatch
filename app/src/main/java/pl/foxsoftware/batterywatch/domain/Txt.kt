package pl.foxsoftware.batterywatch.domain

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * Tekst do pokazania użytkownikowi, rozwiązywany dopiero w UI (warstwa domeny nie ma [Context]).
 * Argumenty mogą same być [Txt].
 */
sealed interface Txt {
    data class Res(@StringRes val id: Int, val args: List<Any?> = emptyList()) : Txt
    data class Raw(val value: String) : Txt
    data class Join(val parts: List<Txt>, val separator: String = " ") : Txt
}

fun txt(@StringRes id: Int, vararg args: Any?): Txt = Txt.Res(id, args.toList())

/** Łączy zdania spacją, pomijając null. */
fun joinTxt(vararg parts: Txt?): Txt = Txt.Join(parts.filterNotNull())

/** [lookup] dostaje id zasobu i już rozwiązane argumenty (pusta tablica = tekst bez formatowania). */
fun Txt.resolve(lookup: (id: Int, args: Array<Any?>) -> String): String = when (this) {
    is Txt.Raw -> value
    is Txt.Res -> lookup(id, args.map { if (it is Txt) it.resolve(lookup) else it }.toTypedArray())
    is Txt.Join -> parts.joinToString(separator) { it.resolve(lookup) }
}

fun Txt.resolve(res: Resources): String =
    resolve { id, args -> if (args.isEmpty()) res.getString(id) else res.getString(id, *args) }

fun Txt.resolve(context: Context): String = resolve(context.resources)
