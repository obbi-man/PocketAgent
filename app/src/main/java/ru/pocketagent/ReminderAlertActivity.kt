package ru.pocketagent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Полноэкранное напоминание (поверх блокировки, как будильник): крупный текст, «Отложить 10 мин» и «Готово». */
class ReminderAlertActivity : AppCompatActivity() {
    private var id = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render(intent) }

    private fun render(i: Intent) {
        id = i.getIntExtra(AndroidReminders.EXTRA_ID, -1)
        val alarm = i.getBooleanExtra("alarm", false)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(28), px(48), px(28), px(48))
            setBackgroundColor(getColor(R.color.pa_bg))
        }
        box.addView(TextView(this).apply { text = if (alarm) "⏰" else "🔔"; textSize = 64f; gravity = Gravity.CENTER })
        box.addView(TextView(this).apply {
            text = if (alarm) "Будильник" else "Напоминание"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.pa_sub))
            setPadding(0, px(8), 0, px(8))
        })
        box.addView(TextView(this).apply {
            text = i.getStringExtra("text") ?: "Напоминание"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.pa_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(8), 0, px(40))
        })
        fun button(label: String, action: String, outlined: Boolean) = MaterialButton(this, null,
            if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
            text = label; textSize = 22f; isAllCaps = false; minHeight = px(68)
            setOnClickListener {
                sendBroadcast(Intent(this@ReminderAlertActivity, ReminderReceiver::class.java).setAction(action).putExtra(AndroidReminders.EXTRA_ID, id))
                finish()
            }
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) }) }
        button("Готово", AndroidReminders.ACTION_DONE, outlined = false)
        button("Отложить 10 мин", AndroidReminders.ACTION_SNOOZE, outlined = true)
        setContentView(box)
    }
}
