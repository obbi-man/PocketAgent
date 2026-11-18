package ru.pocketagent

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** «Что я о вас знаю»: факты (правка/удаление), стиль общения, выключатель памяти. Всё хранится только на телефоне. */
class MemoryActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private val agent get() = Engine.agent(this)
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(24), px(20), px(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(getColor(R.color.pa_bg)); addView(box) })
        render()
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = R.color.pa_text, top: Int = 0) = TextView(this).apply {
        text = s; textSize = size; setTextColor(getColor(color)); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, px(top), 0, px(6))
    }.also { box.addView(it) }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        text = label; textSize = 18f; isChecked = value; minHeight = px(56)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun render() {
        box.removeAllViews()
        val facts = agent.facts; val style = agent.style
        text("Что я о вас знаю", 26f, bold = true)
        text("Хранится только на этом телефоне, не отправляется в интернет и не попадает в облачную копию.", 16f, color = R.color.pa_sub)
        switch("Память: ${if (facts.enabled) "вкл" else "выкл"}", facts.enabled) { facts.toggle(it); render() }

        text("Факты", 21f, bold = true, top = 16)
        val all = facts.all()
        if (all.isEmpty()) text("Пока пусто. Скажите в чате: «Меня зовут Пётр», «Я живу в Екатеринбурге», «Внука зовут Миша» или «Запомни, что у меня аллергия на пенициллин».", 16f, color = R.color.pa_sub)
        for (f in all) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(56) }
            row.addView(TextView(this).apply {
                text = f.text; textSize = 18f; setTextColor(getColor(R.color.pa_text))
                setOnClickListener { edit(f) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_edit); setPadding(px(12), px(12), px(12), px(12)); contentDescription = "Изменить"
                setOnClickListener { edit(f) }
            }, LinearLayout.LayoutParams(px(48), px(48)))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_trash); setPadding(px(12), px(12), px(12), px(12)); contentDescription = "Удалить"
                setOnClickListener { facts.remove(f.id); render() }
            }, LinearLayout.LayoutParams(px(48), px(48)))
            box.addView(row)
        }
        box.addView(MaterialButton(this).apply {
            text = "Добавить факт"; isAllCaps = false; textSize = 17f
            setOnClickListener { edit(null) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

        text("Стиль общения", 21f, bold = true, top = 20)
        val s = style.style()
        text("Сейчас: на «${if (s.vy) "вы" else "ты"}», ${if (s.short) "коротко" else "обычная длина"}, ${if (s.emoji) "со смайликами" else "без смайликов"}.", 16f, color = R.color.pa_sub)
        switch("Подстраиваться под мой стиль", style.adapt) { style.adapt = it; style.save(); render() }
        switch("Обращаться на «ты»", !s.vy) { style.forceVy = !it; style.save(); render() }
        switch("Короткие ответы", s.short) { style.forceShort = it; style.save(); render() }
        switch("Смайлики", s.emoji) { style.forceEmoji = it; style.save(); render() }
        text("Можно и голосом: «отвечай короче», «говори на ты», «без смайликов».", 15f, color = R.color.pa_sub)

        if (all.isNotEmpty()) box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Забыть всё обо мне"; isAllCaps = false; textSize = 17f
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MemoryActivity).setTitle("Забыть всё?").setMessage("Все факты о вас будут удалены с телефона.")
                    .setPositiveButton("Забыть") { _, _ -> facts.clear(); render() }.setNegativeButton("Отмена", null).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
    }

    private fun edit(f: Fact?) {
        val et = EditText(this).apply { setText(f?.text ?: ""); textSize = 18f; setSelection(text.length); hint = "Например: Внука зовут Миша" }
        val wrap = LinearLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        MaterialAlertDialogBuilder(this).setTitle(if (f == null) "Новый факт" else "Изменить факт").setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) {
                    if (f != null) agent.facts.update(f.id, t)
                    else { val ex = Personal.extract(t).firstOrNull(); agent.facts.put(ex?.key ?: "fact", ex?.text ?: t) }
                }
                render()
            }.setNegativeButton("Отмена", null).show()
    }
}
