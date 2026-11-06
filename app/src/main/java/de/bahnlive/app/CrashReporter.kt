package de.bahnlive.app

import android.content.Context
import android.os.Build
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Hält Abstürze und abgefangene Fehler fest, damit man sie beim nächsten Start sehen
 * und teilen kann. Es wird nichts automatisch verschickt.
 */
object CrashReporter {
    private const val FILE = "absturzbericht.txt"
    @Volatile private var installed = false

    /** Fängt Abstürze ab, speichert den Bericht und übergibt dann an Android. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, "Absturz (${thread.name})", error, fatal = true) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Speichert einen Bericht. Ein Absturz ersetzt immer den vorigen Bericht; ein abgefangener
     * Fehler nur, wenn noch kein Absturzbericht vorliegt.
     */
    fun record(context: Context, what: String, error: Throwable, fatal: Boolean) {
        val file = File(context.filesDir, FILE)
        if (!fatal && file.exists() && file.readText().startsWith("ABSTURZ")) return
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val text = buildString {
            appendLine(if (fatal) "ABSTURZ" else "FEHLER (abgefangen, App lief weiter)")
            appendLine("Wobei: $what")
            appendLine("Zeit: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))}")
            appendLine("BahnSchmerz $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(error.stackTraceToString().take(12_000))
        }
        runCatching { file.writeText(text) }
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
