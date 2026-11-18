package ru.pocketagent

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * Настоящие напоминания: AlarmManager.setAlarmClock (точно, будит телефон; на Android 12+ нужно разрешение
 * на точные будильники — USE_EXACT_ALARM/SCHEDULE_EXACT_ALARM), иначе — неточный setAndAllowWhileIdle.
 * После перезагрузки/смены времени — перепланирование (BootReceiver).
 */
class AndroidReminders(ctx: Context) : RemindersStore {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    companion object {
        const val CH_REMIND = "reminders_v1"
        const val CH_ALARM = "alarms_v1"
        /** 2.5: каналы нельзя менять после создания — поэтому новые id. */
        const val CH_LOUD = "reminders_loud_v2"      // звук играет служба (MediaPlayer, USAGE_ALARM) — у канала звука нет
        const val CH_SOUND = "reminders_sound_v2"    // запасной: звук будильника на самом канале + FLAG_INSISTENT
        const val CH_QUIET = "reminders_quiet_v2"    // «Звук напоминаний» выключен: всплывающее, только вибрация
        const val ACTION_FIRE = "ru.pocketagent.REMINDER_FIRE"
        const val ACTION_SNOOZE = "ru.pocketagent.REMINDER_SNOOZE"
        const val ACTION_DONE = "ru.pocketagent.REMINDER_DONE"
        const val EXTRA_ID = "id"

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val attrsN = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Напоминания, которые вы попросили поставить"
                enableVibration(true); vibrationPattern = longArrayOf(0, 400, 250, 400)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrsN)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
            val attrsA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Будильники (резерв)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Будильники, если на телефоне нет приложения «Часы»"
                enableVibration(true); vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrsA)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel(CH_LOUD, "Напоминания со звуком", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Звучит, пока не нажмёте «Готово» или «Отложить» (не дольше минуты)"
                setSound(null, null); enableVibration(false)   // звук и вибрацию даёт служба
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_SOUND, "Напоминания со звуком (резерв)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Если служба звука не запустилась"
                enableVibration(true); vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrsA)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Напоминания без звука", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Когда «Звук напоминаний» выключен: только всплывающее уведомление и вибрация"
                setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 300, 200, 300)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }

        fun canExact(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            return try { ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() } catch (_: Throwable) { false }
        }

        fun notificationsAllowed(ctx: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true)
    }

    private fun load(): MutableList<Reminder> {
        val a = try { JSONArray(p.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            try {
                val o = a.getJSONObject(i)
                ReminderJson.decode(o)
            } catch (_: Exception) { null }
        }.toMutableList()
    }

    private fun save(l: List<Reminder>) {
        val a = JSONArray()
        for (r in l) a.put(ReminderJson.encode(r))
        p.edit().putString("list", a.toString()).commit()
    }

    @Synchronized override fun all(): List<Reminder> = load()

    @Synchronized fun get(id: Int): Reminder? = load().firstOrNull { it.id == id }

    @Synchronized override fun add(atMs: Long, hour: Int, minute: Int, daysMask: Int, text: String, alarm: Boolean, sound: Int): Pair<Reminder, Boolean> {
        val l = load()
        val id = p.getInt("next_id", 1); p.edit().putInt("next_id", id + 1).commit()
        val r = Reminder(id, atMs, hour, minute, daysMask, text, alarm, sound)
        l += r; save(l)
        return r to schedule(r)
    }

    @Synchronized override fun remove(id: Int) {
        val l = load(); l.removeAll { it.id == id }; save(l)
        cancel(id)
    }

    /** Убрать сработавшее однократное напоминание из списка, НЕ трогая показанное уведомление. */
    @Synchronized fun forgetFired(id: Int) { val l = load(); l.removeAll { it.id == id }; save(l) }

    /** 2.6: перенос/правка — отменяем старый будильник и уведомление, сохраняем и планируем заново. */
    @Synchronized override fun replace(r: Reminder): Boolean {
        cancel(r.id)
        val l = load(); val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l += r
        save(l)
        return schedule(r)
    }

    override fun lastFired(nowMs: Long, withinMs: Long): Reminder? {
        val f = app.getSharedPreferences("reminders_fired", Context.MODE_PRIVATE)
        return f.all.mapNotNull { (k, v) ->
            try {
                val o = JSONObject(v as String); val at = o.optLong("fired", 0)
                if (at <= 0 || nowMs - at !in 0..withinMs) null
                else Reminder(k.toInt(), o.optLong("at", at), o.optInt("h", 0), o.optInt("m", 0), 0, o.optString("text", "Напоминание"), o.optBoolean("alarm"), o.optInt("snd", 0)) to at
            } catch (_: Throwable) { null }
        }.filter { !it.first.alarm }.maxByOrNull { it.second }?.first
    }

    override fun dropFired(id: Int) {
        app.getSharedPreferences("reminders_fired", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
        try { app.getSystemService(NotificationManager::class.java)?.cancel(1000 + id) } catch (_: Throwable) {}
    }

    @Synchronized fun update(r: Reminder) { val l = load(); val i = l.indexOfFirst { it.id == r.id }; if (i >= 0) { l[i] = r; save(l) } }

    private fun firePi(id: Int): PendingIntent = PendingIntent.getBroadcast(app, id,
        Intent(app, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun showPi(): PendingIntent = PendingIntent.getActivity(app, 0,
        Intent(app, ListsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    /** @return true — точный будильник, false — неточный (нет разрешения). */
    fun schedule(r: Reminder): Boolean {
        val am = app.getSystemService(AlarmManager::class.java)
        val at = maxOf(r.atMs, System.currentTimeMillis() + 1000)
        if (canExact(app)) {
            try { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi()), firePi(r.id)); return true }
            catch (_: SecurityException) { }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, firePi(r.id))
        return false
    }

    fun cancel(id: Int) {
        try { app.getSystemService(AlarmManager::class.java).cancel(firePi(id)) } catch (_: Throwable) {}
        try { app.getSystemService(NotificationManager::class.java)?.cancel(1000 + id) } catch (_: Throwable) {}
    }

    /** После загрузки/смены времени: повторяющиеся — на следующее время, просроченные однократные — показать сразу. */
    fun rescheduleAll(missed: (Reminder) -> Unit) {
        val now = System.currentTimeMillis()
        for (r in all()) {
            if (r.repeating) {
                val next = TimeParser.nextOccurrence(LocalDateTime.now(), r.hour, r.minute, TimeParser.fromMask(r.daysMask))
                val nr = r.copy(atMs = Router.millis(next)); update(nr); schedule(nr)
            } else if (r.atMs < now - 60_000) missed(r) else schedule(r)
        }
    }
}

/** Сериализация напоминаний (старые записи 2.4 без «snd» читаются как «как в настройках»). */
object ReminderJson {
    fun encode(r: Reminder): JSONObject = JSONObject().put("id", r.id).put("at", r.atMs).put("h", r.hour).put("m", r.minute)
        .put("days", r.daysMask).put("text", r.text).put("alarm", r.alarm).put("snd", r.sound)
    fun decode(o: JSONObject) = Reminder(o.getInt("id"), o.getLong("at"), o.getInt("h"), o.getInt("m"), o.optInt("days"),
        o.getString("text"), o.optBoolean("alarm"), o.optInt("snd", 0))
}

/** Решение «звенеть или нет» — чистая функция (тесты). */
object SoundPolicy {
    fun loud(settingOn: Boolean, r: Reminder): Boolean = when {
        r.alarm -> true                // резервный будильник — всегда со звуком
        r.sound == 1 -> true
        r.sound == 2 -> false
        else -> settingOn
    }
}

object ReminderNotifier {
    fun actionPi(ctx: Context, id: Int, action: String): PendingIntent = PendingIntent.getBroadcast(ctx, id * 10 + action.length,
        Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(AndroidReminders.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun alertPi(ctx: Context, id: Int, text: String, alarm: Boolean): PendingIntent = PendingIntent.getActivity(ctx, 5000 + id,
        Intent(ctx, ReminderAlertActivity::class.java).putExtra(AndroidReminders.EXTRA_ID, id).putExtra("text", text).putExtra("alarm", alarm)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun title(r: Reminder, missed: Boolean) = when { missed -> "Пропущенное напоминание"; r.alarm -> "⏰ Будильник"; else -> "🔔 Напоминание" }

    fun canFullScreen(ctx: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        (ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false)

    fun build(ctx: Context, r: Reminder, channel: String, missed: Boolean, ringing: Boolean): android.app.Notification {
        val b = android.app.Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title(r, missed))
            .setContentText(r.text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(r.text))
            .setCategory(if (r.alarm || ringing) android.app.Notification.CATEGORY_ALARM else android.app.Notification.CATEGORY_REMINDER)
            .setContentIntent(alertPi(ctx, r.id, r.text, r.alarm))
            .setDeleteIntent(actionPi(ctx, r.id, AndroidReminders.ACTION_DONE))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .addAction(android.app.Notification.Action.Builder(null, "Отложить 10 мин", actionPi(ctx, r.id, AndroidReminders.ACTION_SNOOZE)).build())
            .addAction(android.app.Notification.Action.Builder(null, "Готово", actionPi(ctx, r.id, AndroidReminders.ACTION_DONE)).build())
        if (ringing) {
            b.setOngoing(true)
            // поверх экрана блокировки, как будильник (Android 14+: только если разрешено)
            if (canFullScreen(ctx)) b.setFullScreenIntent(alertPi(ctx, r.id, r.text, r.alarm), true)
        }
        return b.build()
    }

    fun show(ctx: Context, r: Reminder, missed: Boolean = false) {
        AndroidReminders.ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val loud = SoundPolicy.loud(ReminderSound.enabled(ctx), r)
        if (loud && !missed) {
            // основной путь: служба переднего плана играет выбранный звук с USAGE_ALARM (слышно и в обычном режиме)
            try { ReminderSoundService.start(ctx, r); return } catch (t: Throwable) { android.util.Log.w("PocketReminder", "sound service: $t") }
            val n = build(ctx, r, AndroidReminders.CH_SOUND, missed, ringing = false)
            n.flags = n.flags or android.app.Notification.FLAG_INSISTENT     // повторять звук канала, пока не уберут
            try { nm.notify(1000 + r.id, n) } catch (_: SecurityException) { }
            return
        }
        val ch = if (loud) AndroidReminders.CH_SOUND else AndroidReminders.CH_QUIET
        try { nm.notify(1000 + r.id, build(ctx, r, ch, missed, ringing = false)) } catch (_: SecurityException) { }
    }
}

/** Настройки звука: вкл/выкл, мелодия (по умолчанию — звук будильника). */
object ReminderSound {
    private fun p(c: Context) = c.getSharedPreferences("reminder_sound", Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    /** "alarm" | "notification" | "custom" */
    fun kind(c: Context) = p(c).getString("kind", "alarm") ?: "alarm"
    fun customUri(c: Context) = p(c).getString("uri", null)
    fun set(c: Context, kind: String, uri: String? = null) = p(c).edit().putString("kind", kind).putString("uri", uri).apply()

    fun uri(c: Context): android.net.Uri? {
        val custom = customUri(c)?.let { android.net.Uri.parse(it) }
        return when (kind(c)) {
            "custom" -> custom
            "notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    fun label(c: Context): String = when (kind(c)) {
        "notification" -> "звук уведомления"
        "custom" -> try { RingtoneManager.getRingtone(c, uri(c))?.getTitle(c) ?: "своя мелодия" } catch (_: Throwable) { "своя мелодия" }
        else -> "звук будильника"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = AndroidReminders(ctx)
        val id = intent.getIntExtra(AndroidReminders.EXTRA_ID, -1)
        if (id == 0 && intent.action != AndroidReminders.ACTION_FIRE) {   // «Проверить звук»
            ReminderSoundService.stop(ctx); ctx.getSystemService(NotificationManager::class.java)?.cancel(1000); return
        }
        when (intent.action) {
            AndroidReminders.ACTION_FIRE -> {
                val r = store.get(id) ?: return
                ReminderNotifier.show(ctx, r)
                if (r.repeating) {
                    val next = TimeParser.nextOccurrence(LocalDateTime.now().plusMinutes(1), r.hour, r.minute, TimeParser.fromMask(r.daysMask))
                    val nr = r.copy(atMs = Router.millis(next)); store.update(nr); store.schedule(nr)
                } else {
                    // однократное: убираем из списка, но уведомление остаётся (для «Отложить» храним копию)
                    ctx.getSharedPreferences("reminders_fired", Context.MODE_PRIVATE).edit()
                        .putString(id.toString(), JSONObject().put("text", r.text).put("alarm", r.alarm).put("snd", r.sound)
                            .put("at", r.atMs).put("h", r.hour).put("m", r.minute).put("fired", System.currentTimeMillis()).toString()).apply()
                    store.forgetFired(id)
                }
            }
            AndroidReminders.ACTION_SNOOZE -> {
                ReminderSoundService.stop(ctx, id)
                ctx.getSystemService(NotificationManager::class.java)?.cancel(1000 + id)
                val fired = ctx.getSharedPreferences("reminders_fired", Context.MODE_PRIVATE).getString(id.toString(), null)
                val r = store.get(id)
                val text = r?.text ?: fired?.let { JSONObject(it).optString("text") } ?: "Напоминание"
                val alarm = r?.alarm ?: fired?.let { JSONObject(it).optBoolean("alarm") } ?: false
                val at = LocalDateTime.now().plusMinutes(10).withSecond(0).withNano(0)
                val snd = r?.sound ?: fired?.let { JSONObject(it).optInt("snd", 0) } ?: 0
                store.add(Router.millis(at), at.hour, at.minute, 0, text, alarm, snd)
                ctx.getSharedPreferences("reminders_fired", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
            }
            AndroidReminders.ACTION_DONE -> {
                ctx.getSharedPreferences("reminders_fired", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
                ReminderSoundService.stop(ctx, id)
                ctx.getSystemService(NotificationManager::class.java)?.cancel(1000 + id)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = AndroidReminders(ctx)
        store.rescheduleAll { missed ->
            ReminderNotifier.show(ctx, missed, missed = true)
            store.forgetFired(missed.id)
        }
    }
}
