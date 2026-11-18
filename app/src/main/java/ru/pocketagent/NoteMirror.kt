package ru.pocketagent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Копирование заметок в системное приложение заметок (Samsung Notes, Google Keep…) через ACTION_SEND. */
object NoteMirror {
    data class App(val pkg: String, val label: String)

    /** Известные приложения заметок — первыми в списке. */
    val PREFERRED = listOf(
        "com.samsung.android.app.notes",        // Samsung Notes
        "com.google.android.keep",              // Google Keep
        "com.miui.notes", "com.android.notes", "com.oneplus.note", "com.coloros.note", "com.huawei.notepad",
        "com.microsoft.office.onenote", "com.evernote", "md.obsidian", "com.simplemobiletools.notes.pro",
    )
    private const val P = "note_mirror"

    fun target(c: Context): String? = c.getSharedPreferences(P, 0).getString("pkg", null)?.takeIf { installed(c, it) }
    fun setTarget(c: Context, pkg: String?) = c.getSharedPreferences(P, 0).edit().putString("pkg", pkg).putBoolean("asked", true).apply()
    fun asked(c: Context) = c.getSharedPreferences(P, 0).getBoolean("asked", false)
    fun setAsked(c: Context) = c.getSharedPreferences(P, 0).edit().putBoolean("asked", true).apply()

    fun installed(c: Context, pkg: String) = try { c.packageManager.getApplicationInfo(pkg, 0); true } catch (_: Exception) { false }

    fun shareIntent(text: String, title: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
    }

    /** Приложения, принимающие текст; известные приложения заметок — сверху. */
    fun candidates(c: Context): List<App> {
        val pm = c.packageManager
        val ris = try { pm.queryIntentActivities(shareIntent("x", "x"), PackageManager.MATCH_DEFAULT_ONLY) } catch (_: Exception) { emptyList() }
        val apps = ris.map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != c.packageName }.distinctBy { it.pkg }
        return sort(apps)
    }

    fun sort(apps: List<App>): List<App> = apps.sortedWith(compareBy<App> { PREFERRED.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.label.lowercase() })

    /** Лучший найденный кандидат среди известных приложений заметок. */
    fun best(c: Context): App? = candidates(c).firstOrNull { it.pkg in PREFERRED }

    fun label(c: Context, pkg: String): String = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    fun title(text: String) = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { DocIndex.short(it, 40) } ?: "Заметка"

    /** Отправить заметку. Возвращает текст ошибки или null. */
    fun send(c: Context, pkg: String, text: String): String? {
        if (!installed(c, pkg)) return "приложение заметок не найдено — выберите другое в настройках"
        return try {
            c.startActivity(shareIntent(text, title(text)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
        } catch (e: Exception) { "не удалось открыть «${label(c, pkg)}»" }
    }

    fun open(c: Context, pkg: String): Boolean = try {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    fun exportAll(notes: List<String>): String = notes.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n\n")
}
