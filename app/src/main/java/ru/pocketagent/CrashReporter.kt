package ru.pocketagent

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/** Сбор отчётов о падениях: Kotlin (uncaught) + нативные сигналы + «маркер незавершённой операции». */
object CrashReporter {
    private lateinit var dir: File
    val kotlinFile get() = File(dir, "crash_kotlin.txt")
    val nativeFile get() = File(dir, "crash_native.txt")
    val busyFile get() = File(dir, "native_busy.txt")
    var nativeInstalled = false; private set

    fun deviceInfo(): String = "PocketAgent ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.LITE) " lite" else ""}\n" +
            "Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), SoC: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE}\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "ОЗУ: ${DeviceInfo.totalRamMb(appCtx)} МБ, ядер: ${Runtime.getRuntime().availableProcessors()}\n"

    private lateinit var appCtx: Context
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        dir = File(ctx.filesDir, "crash").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                kotlinFile.writeText("KOTLIN CRASH в потоке «${t.name}» ${now()}\n" + deviceInfo() + "\n" + sw.toString().take(12000))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        try {
            LlamaBridge.nativeInstallCrashHandler(nativeFile.absolutePath)
            LlamaBridge.nativeSetVerbose(BuildConfig.DEBUG)
            nativeInstalled = true
        } catch (t: Throwable) {
            // libpocketagent.so не загрузилась — это тоже отчёт
            kotlinFile.writeText("Не удалось загрузить нативную библиотеку ${now()}\n" + deviceInfo() + "\n" + t.toString())
        }
    }

    fun markBusy(stage: String) = try { busyFile.writeText("$stage ${now()}") } catch (_: Throwable) {}
    fun clearBusy() { try { busyFile.delete() } catch (_: Throwable) {} }

    /** Отчёт о предыдущем падении (или null). */
    fun pendingReport(): String? {
        val parts = mutableListOf<String>()
        if (kotlinFile.exists()) parts += kotlinFile.readText()
        if (nativeFile.exists()) parts += deviceInfo() + "\n" + nativeFile.readText().take(12000)
        if (parts.isEmpty() && busyFile.exists())
            parts += deviceInfo() + "\nПрошлый запуск оборвался во время операции «${busyFile.readText()}» " +
                    "(без отчёта — вероятно, система закрыла приложение из-за нехватки памяти)."
        return if (parts.isEmpty()) null else parts.joinToString("\n\n-----\n\n")
    }

    fun clear() { kotlinFile.delete(); nativeFile.delete(); busyFile.delete() }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object DeviceInfo {
    fun totalRamMb(ctx: Context): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); mi.totalMem / (1024 * 1024)
    } catch (_: Throwable) { -1 }
    fun isLowRam(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice || totalRamMb(ctx) in 1..2500
    } catch (_: Throwable) { false }
}
