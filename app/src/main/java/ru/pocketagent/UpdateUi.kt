package ru.pocketagent

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Диалоги обновления: проверка → «что нового» → загрузка → проверка → установка с подтверждением. */
class UpdateUi(private val a: Activity) {
    private val ui = Handler(Looper.getMainLooper())

    companion object {
        private val net = Executors.newSingleThreadExecutor()
        /** Файл, ожидающий разрешения «Установка неизвестных приложений». */
        var pendingFile: File? = null
        const val TAG = "PocketUpdate"
        /** Дружелюбные тексты: подробности — только в журнале (файл на Диске может быть ещё не открыт для всех, нет сети и т. п.). */
        const val CHECK_FAILED = "Не удалось проверить обновления, попробую позже. Проверьте интернет."
        const val DOWNLOAD_FAILED = "Не удалось скачать обновление, попробую позже. Если повторить вручную, загрузка продолжится с места обрыва."
    }

    /** Автопроверка при запуске: не чаще раза в сутки, по умолчанию только по Wi-Fi. */
    fun autoCheck() {
        val c = a.applicationContext
        if (!Updater.shouldAutoCheck(Updater.enabled(), Updater.autoCheck(c), Updater.lastCheck(c), System.currentTimeMillis(), Updater.unmetered(c), Updater.wifiOnly(c))) return
        net.execute {
            val info = try { Updater.fetchManifest() } catch (e: Exception) { android.util.Log.w(TAG, "auto check: $e"); null }
            android.util.Log.i(TAG, "auto check → ${info?.versionCode}")
            if (info != null) Updater.markChecked(c)
            if (info != null && Updater.isNewer(info, Updater.currentCode(c)) && info.versionCode != Updater.skipped(c))
                ui.post { if (!a.isFinishing) offer(info, auto = true) }
        }
    }

    /** Пункт меню «Проверить обновления». */
    fun checkNow() {
        if (!Updater.enabled()) {
            MaterialAlertDialogBuilder(a).setTitle("Обновления").setMessage("В этой сборке проверка обновлений выключена (не задан источник).").setPositiveButton("OK", null).show()
            return
        }
        val dlg = MaterialAlertDialogBuilder(a).setTitle("Обновления").setMessage("Проверяю…").setCancelable(true).show()
        net.execute {
            val r = try { Result.success(Updater.fetchManifest()) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                dlg.dismiss()
                if (a.isFinishing) return@post
                r.onSuccess { info ->
                    Updater.markChecked(a)
                    when {
                        Updater.isNewer(info, Updater.currentCode(a)) -> offer(info, auto = false)
                        info.versionCode > Updater.currentCode(a) -> msg("Версия ${info.versionName} требует Android новее, чем на этом телефоне.")
                        else -> msg("У вас последняя версия (${BuildConfig.VERSION_NAME}).")
                    }
                }.onFailure { android.util.Log.w(TAG, "check failed: $it"); msg(CHECK_FAILED) }
            }
        }
    }


    private fun msg(s: String) = MaterialAlertDialogBuilder(a).setTitle("Обновления").setMessage(s).setPositiveButton("OK", null).show()

    private fun offer(info: Updater.Info, auto: Boolean) {
        val size = if (info.size > 0) " (${info.size / 1_000_000} МБ)" else ""
        val dp = a.resources.displayMetrics.density
        val text = TextView(a).apply {
            this.text = (info.changelog.ifBlank { "Исправления и улучшения." }) + "\n\nУ вас: ${BuildConfig.VERSION_NAME}. Загрузить$size и установить?"
            textSize = 16f; setTextColor(a.getColor(R.color.pa_text)); setLineSpacing(0f, 1.1f)
        }
        // список изменений прокручивается, а кнопки всегда видны (крупный шрифт на телефоне папы)
        val maxH = (a.resources.displayMetrics.heightPixels * 0.45).toInt()
        val scroll = object : android.widget.ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST))
        }.apply { val p = (24 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, 0); addView(text) }
        val b = MaterialAlertDialogBuilder(a).setTitle("Доступна версия ${info.versionName}")
            .setView(scroll)
            .setPositiveButton("Обновить") { _, _ -> download(info) }
            .setNegativeButton("Позже", null)
        if (auto) b.setNeutralButton("Пропустить") { _, _ -> Updater.skip(a, info.versionCode) }
        b.show()
    }

    private fun download(info: Updater.Info) {
        val dp = a.resources.displayMetrics.density
        val bar = LinearProgressIndicator(a).apply { isIndeterminate = true; max = 1000 }
        val label = TextView(a).apply { text = "Загрузка…"; textSize = 16f; setTextColor(a.getColor(R.color.pa_text)) }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; val p = (24 * dp).toInt(); setPadding(p, p / 2, p, 0); addView(label); addView(bar) }
        val cancel = AtomicBoolean(false)
        val dlg = MaterialAlertDialogBuilder(a).setTitle("Обновление ${info.versionName}").setView(box).setCancelable(false)
            .setNegativeButton("Отмена") { _, _ -> cancel.set(true) }.show()
        net.execute {
            try {
                val f = Updater.download(a, info, { done, total ->
                    ui.post {
                        if (total > 0) { bar.isIndeterminate = false; bar.setProgressCompat((done * 1000 / total).toInt(), true) }
                        label.text = "Загружено ${done / 1_000_000} из ${if (total > 0) "${total / 1_000_000} МБ" else "?"}"
                    }
                }, { cancel.get() })
                ui.post { label.text = "Проверяю подпись и контрольную сумму…"; bar.isIndeterminate = true }
                val err = Updater.verify(a, f, info)
                android.util.Log.i(TAG, "downloaded ${f.length()} bytes, verify=${err ?: "ok"}")
                ui.post {
                    dlg.dismiss()
                    if (err != null) msg("Обновление не установлено: $err.") else install(f)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download failed: $e")
                android.util.Log.w(TAG, "download failed: $e")
                ui.post { dlg.dismiss(); if (!cancel.get()) msg(DOWNLOAD_FAILED) }
            }
        }
    }

    /** Установка: всегда с подтверждением системы. Без разрешения на установку — сначала открываем настройку. */
    fun install(f: File) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            pendingFile = f
            MaterialAlertDialogBuilder(a).setTitle("Нужно разрешение")
                .setMessage("Чтобы установить обновление, разрешите PocketAgent устанавливать приложения: на следующем экране включите «Разрешить из этого источника», затем вернитесь назад.")
                .setPositiveButton("Открыть настройку") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                    catch (_: Exception) { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }.setNegativeButton("Отмена") { _, _ -> pendingFile = null }.show()
            return
        }
        pendingFile = null
        net.execute {
            try {
                val pi = a.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(a.packageName)
                    setSize(f.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)   // никогда не молча
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) }; s.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pend = PendingIntent.getBroadcast(a, id, Intent(a, InstallReceiver::class.java).setPackage(a.packageName), flags)
                    s.commit(pend.intentSender)
                }
                android.util.Log.i(TAG, "install session $id committed")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "install failed: $e")
                ui.post { msg("Не удалось начать установку: ${e.message}") }
            }
        }
    }

    /** Вызывать из onResume: если пользователь вернулся из настройки разрешения. */
    fun resumePending() {
        val f = pendingFile ?: return
        if (a.packageManager.canRequestPackageInstalls() && f.exists()) install(f)
    }
}

/** Статус установки от PackageInstaller: показываем системное подтверждение и ошибки. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        android.util.Log.i(UpdateUi.TAG, "install status=$status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(c, "Установка отменена", Toast.LENGTH_LONG).show()
            else -> Toast.makeText(c, "Обновление не установлено: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}", Toast.LENGTH_LONG).show()
        }
    }
}
