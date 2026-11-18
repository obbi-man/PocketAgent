package ru.pocketagent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Пошаговая инструкция: как заменить Google Ассистента / Gemini на PocketAgent. */
class GuideActivity : AppCompatActivity() {
    companion object {
        val SAMSUNG = listOf(
            "Откройте Настройки → Приложения.",
            "Нажмите «Выбор приложений по умолчанию» (One UI 4–5) или «Приложения по умолчанию» (One UI 6).",
            "Выберите «Цифровой помощник» → «Приложение цифрового помощника» и отметьте PocketAgent. Подтвердите «OK».",
            "На этом же экране включите «Анализировать текст на экране» (или «Использовать текст с экрана») и при желании «Использовать снимок экрана» — без этого PocketAgent не сможет «спросить про экран».",
            "Боковая клавиша: Настройки → Дополнительные функции → Боковая клавиша → «Нажатие и удержание» → «Цифровой помощник» (вместо «Выключение» или Bixby). Можно и «Двойное нажатие» → «Открыть приложение» → PocketAgent.",
            "Жест: при навигации жестами проведите от нижнего угла экрана к центру; с кнопками — удерживайте кнопку «Домой».",
            "Если появляется Gemini: откройте приложение Google → профиль → Настройки → Gemini/Google Ассистент → «Цифровые помощники от Google» → отключите, затем повторите шаг 3.",
        )
        val STOCK = listOf(
            "Настройки → Приложения → Приложения по умолчанию → Цифровой помощник (на Pixel: «Помощник и голосовой ввод»).",
            "«Приложение цифрового помощника» → PocketAgent.",
            "Включите «Использовать текст с экрана» и «Использовать скриншот».",
            "Запуск: долгое нажатие «Домой», жест от нижнего угла или (на некоторых телефонах) удержание кнопки питания: Настройки → Система → Жесты → «Кнопка питания» → «Цифровой помощник».",
        )
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(24), px(20), px(32)) }
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = R.color.pa_text, top: Int = 0) = TextView(this).apply {
            text = s; textSize = size * Prefs.fontScale(this@GuideActivity); setTextColor(getColor(color)); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(top), 0, px(6)); setLineSpacing(px(3).toFloat(), 1f)
        }.also { box.addView(it) }
        fun button(label: String, run: () -> Unit) = MaterialButton(this).apply {
            text = label; textSize = 17f; isAllCaps = false; minHeight = px(56); setOnClickListener { run() }
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }) }

        text("Как сделать PocketAgent ассистентом", 26f, bold = true)
        text("Тогда жест, кнопка «Домой» или боковая клавиша откроют PocketAgent поверх любого приложения — вместо Google Ассистента / Gemini.", 17f, color = R.color.pa_sub)
        status = text("", 17f, top = 8)
        button("Открыть настройки помощника") { openAssistantSettings() }
        text("Samsung Galaxy (One UI 4–6)", 21f, bold = true, top = 18)
        SAMSUNG.forEachIndexed { i, s -> text("${i + 1}. $s", 17f) }
        button("Настройки боковой клавиши (Samsung)") { openSideKey() }
        text("Другие телефоны (чистый Android)", 21f, bold = true, top = 18)
        STOCK.forEachIndexed { i, s -> text("${i + 1}. $s", 17f) }
        text("Что важно знать", 21f, bold = true, top = 18)
        text("• «Окей, Google» PocketAgent не слушает — запуск жестом или кнопкой.\n" +
            "• Текст экрана читается только когда вы сами вызвали помощника и нажали «Спросить про экран». Всё обрабатывается на телефоне, в интернет не отправляется.\n" +
            "• Банковские и защищённые приложения не отдают свой текст — это нормально.", 17f)
        setContentView(ScrollView(this).apply { setBackgroundColor(getColor(R.color.pa_bg)); addView(box) })
    }

    override fun onResume() { super.onResume(); status.text = statusText() }

    private fun statusText(): String {
        val cr = contentResolver
        val isAssistant = try { Settings.Secure.getString(cr, "voice_interaction_service")?.startsWith(packageName) == true ||
            Settings.Secure.getString(cr, "assistant")?.startsWith(packageName) == true } catch (_: Throwable) { false }
        val structure = try { Settings.Secure.getInt(cr, "assist_structure_enabled", 1) == 1 } catch (_: Throwable) { true }
        val shot = try { Settings.Secure.getInt(cr, "assist_screenshot_enabled", 1) == 1 } catch (_: Throwable) { true }
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        return "Сейчас:\n${mark(isAssistant)} PocketAgent — цифровой помощник по умолчанию\n" +
            "${mark(structure)} «Использовать текст с экрана»\n${mark(shot)} «Использовать снимок экрана» (необязательно)"
    }

    private fun openAssistantSettings() {
        val tries = listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) try { startActivity(i); return } catch (_: Exception) {}
    }

    private fun openSideKey() {
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$FunctionKeySettingsActivity"),
            Intent("com.samsung.settings.SIDE_KEY_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$AdvancedFeaturesActivity"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) try { startActivity(i); return } catch (_: Exception) {}
    }
}
