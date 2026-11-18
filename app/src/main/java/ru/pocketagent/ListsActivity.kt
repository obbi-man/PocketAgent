package ru.pocketagent

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.time.LocalDateTime

/** Экран «Напоминания и заметки» с удалением — крупно и просто. */
class ListsActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private lateinit var reminders: AndroidReminders
    private lateinit var notes: PrefsNotes
    private val scale get() = Prefs.fontScale(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        reminders = AndroidReminders(this); notes = PrefsNotes(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val tb = MaterialToolbar(this).apply {
            title = "Напоминания и заметки"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationContentDescription("Назад")
            setNavigationOnClickListener { finish() }
        }
        root.addView(tb)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, bold: Boolean = false, attr: Int = com.google.android.material.R.attr.colorOnSurface) =
        TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale)
            setTextColor(MaterialColors.getColor(this@ListsActivity, attr, 0xFF000000.toInt()))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun header(s: String) = box.addView(text(s, 24f, true).apply { setPadding(0, dp(20), 0, dp(8)) })

    private fun row(title: String, sub: String?, onDelete: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFEEEEEE.toInt()))
            useCompatPadding = false
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(8), dp(14)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 20f, true))
        if (!sub.isNullOrBlank()) col.addView(text(sub, 18f, attr = com.google.android.material.R.attr.colorOnSurfaceVariant))
        line.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val del = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Удалить"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale); minHeight = dp(56)
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ListsActivity).setTitle("Удалить?").setMessage(title)
                    .setPositiveButton("Удалить") { _, _ -> onDelete(); render() }.setNegativeButton("Отмена", null).show()
            }
        }
        line.addView(del)
        card.addView(line)
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun banner(msg: String, button: String, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6.toInt()))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(10)) }
        col.addView(text(msg, 18f, attr = com.google.android.material.R.attr.colorOnErrorContainer))
        col.addView(MaterialButton(this).apply { text = button; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale); minHeight = dp(56); setOnClickListener { action() } })
        card.addView(col)
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    private fun render() {
        box.removeAllViews()
        if (!AndroidReminders.notificationsAllowed(this)) banner("Уведомления выключены — напоминания не будут видны и слышны.", "Включить уведомления") {
            try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } catch (_: Exception) {}
        }
        if (!AndroidReminders.canExact(this) && Build.VERSION.SDK_INT >= 31) banner("Нет разрешения на точные будильники — напоминания могут опаздывать на несколько минут.", "Разрешить") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        header("🔔 Напоминания")
        val now = LocalDateTime.now()
        val rs = reminders.all().sortedBy { it.atMs }
        if (rs.isEmpty()) box.addView(text("Пока нет. Скажите: «Напомни завтра в 9 позвонить врачу».", 18f, attr = com.google.android.material.R.attr.colorOnSurfaceVariant))
        for (r in rs) row(r.text, (if (r.alarm) "⏰ " else "") + TimeParser.human(r.localAt(), now, TimeParser.fromMask(r.daysMask))) { reminders.remove(r.id) }
        header("📝 Заметки")
        val ns = notes.all()
        if (ns.isEmpty()) box.addView(text("Пока нет. Скажите: «Запиши: купить хлеб».", 18f, attr = com.google.android.material.R.attr.colorOnSurfaceVariant))
        ns.forEachIndexed { i, n -> row(n, null) { notes.removeAt(i) } }
        if (ns.isNotEmpty()) box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Удалить все заметки"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale); minHeight = dp(56)
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ListsActivity).setTitle("Удалить все заметки?")
                    .setPositiveButton("Удалить") { _, _ -> notes.clear(); render() }.setNegativeButton("Отмена", null).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(40)))
    }
}

/** Настройки интерфейса. */
object Prefs {
    private fun p(c: android.content.Context) = c.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    fun fontScale(c: android.content.Context) = p(c).getFloat("font", 1.15f)
    fun setFontScale(c: android.content.Context, v: Float) = p(c).edit().putFloat("font", v).apply()
    fun tts(c: android.content.Context) = p(c).getBoolean("tts", true)
    fun setTts(c: android.content.Context, v: Boolean) = p(c).edit().putBoolean("tts", v).apply()
    /** «Всегда озвучивать» — и ответы на набранный текст (по умолчанию выкл.). */
    fun ttsAlways(c: android.content.Context) = p(c).getBoolean("tts_always", false)
    fun setTtsAlways(c: android.content.Context, v: Boolean) = p(c).edit().putBoolean("tts_always", v).apply()
    fun debug(c: android.content.Context) = p(c).getBoolean("debug", false)
    fun setDebug(c: android.content.Context, v: Boolean) = p(c).edit().putBoolean("debug", v).apply()
    fun web(c: android.content.Context) = p(c).getBoolean("web", true)
    fun setWeb(c: android.content.Context, v: Boolean) = p(c).edit().putBoolean("web", v).apply()
    fun theme(c: android.content.Context) = p(c).getInt("theme", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    fun setTheme(c: android.content.Context, v: Int) { p(c).edit().putInt("theme", v).apply(); androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(v) }
    fun applyTheme(c: android.content.Context) = androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(theme(c))
}
