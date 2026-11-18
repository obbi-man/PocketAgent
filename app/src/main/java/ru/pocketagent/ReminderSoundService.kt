package ru.pocketagent

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Звук напоминания: служба переднего плана + MediaPlayer с USAGE_ALARM (поток будильника — слышно в обычном режиме,
 * не зависит от громкости уведомлений). Играет по кругу до «Готово»/«Отложить», но не дольше минуты.
 * Запуск из срабатывания setAlarmClock разрешён Android 12+ (исключение для точных будильников).
 */
class ReminderSoundService : Service() {
    companion object {
        const val MAX_MS = 60_000L
        const val TEST_MS = 6_000L
        private const val A_START = "start"; private const val A_TEST = "test"
        @Volatile var currentId = -1; private set

        fun start(ctx: Context, r: Reminder) {
            val i = Intent(ctx, ReminderSoundService::class.java).setAction(A_START)
                .putExtra("id", r.id).putExtra("text", r.text).putExtra("alarm", r.alarm).putExtra("snd", r.sound)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** «Проверить звук» в настройках: 6 секунд выбранной мелодией. */
        fun test(ctx: Context) {
            val i = Intent(ctx, ReminderSoundService::class.java).setAction(A_TEST)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context, id: Int = -1) {
            if (id < 0 || id == currentId || currentId < 0) try { ctx.stopService(Intent(ctx, ReminderSoundService::class.java)) } catch (_: Throwable) {}
        }
    }

    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private val h = Handler(Looper.getMainLooper())
    private var current: Reminder? = null
    private var testing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AndroidReminders.ensureChannels(this)
        val isTest = intent?.action == A_TEST
        val r = if (isTest) Reminder(0, System.currentTimeMillis(), 0, 0, 0, "Проверка звука напоминаний", sound = 1)
            else Reminder(intent?.getIntExtra("id", -1) ?: -1, System.currentTimeMillis(), 0, 0, 0,
                intent?.getStringExtra("text") ?: "Напоминание", intent?.getBooleanExtra("alarm", false) ?: false, intent?.getIntExtra("snd", 0) ?: 0)
        // предыдущее напоминание ещё звенит — оставляем его уведомление, звук переключаем на новое
        current?.let { if (!testing) detach(it) }
        stopSound()
        current = r; testing = isTest; currentId = if (isTest) -1 else r.id
        val n = ReminderNotifier.build(this, r, AndroidReminders.CH_LOUD, missed = false, ringing = true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1000 + r.id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1000 + r.id, n)
        } catch (t: Throwable) {
            // не дали стать службой переднего плана — запасной канал со звуком и FLAG_INSISTENT
            android.util.Log.w("PocketReminder", "startForeground: $t")
            fallback(r); stopSelf(); return START_NOT_STICKY
        }
        play()
        h.postDelayed({ timeout() }, if (isTest) TEST_MS else MAX_MS)
        return START_NOT_STICKY
    }

    private fun play() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) try {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        } catch (_: Throwable) {}
        try { wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:reminder").apply { acquire(MAX_MS + 5000) } } catch (_: Throwable) {}
        val candidates = listOfNotNull(ReminderSound.uri(this), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)).distinct()
        for (u in candidates) {
            try {
                player = MediaPlayer().apply { setAudioAttributes(attrs); setDataSource(this@ReminderSoundService, u); isLooping = true; prepare(); start() }
                break
            } catch (t: Throwable) { android.util.Log.w("PocketReminder", "play $u: $t"); try { player?.release() } catch (_: Throwable) {}; player = null }
        }
        if (player == null) android.util.Log.w("PocketReminder", "no playable sound")
        try {
            vib = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 700, 500, 700, 1200)
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            else @Suppress("DEPRECATION") vib?.vibrate(pattern, 0)
        } catch (_: Throwable) {}
    }

    private fun stopSound() {
        h.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        try { vib?.cancel() } catch (_: Throwable) {}
        try { focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } } catch (_: Throwable) {}
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
    }

    /** Минута прошла: звук выключаем, уведомление оставляем (обычное, можно смахнуть). */
    private fun timeout() {
        val r = current
        stopSound()
        if (r != null && !testing) detach(r)
        current = null; currentId = -1
        if (Build.VERSION.SDK_INT >= 24) stopForeground(if (testing) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(testing)
        stopSelf()
    }

    private fun detach(r: Reminder) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(1000 + r.id,
                ReminderNotifier.build(this, r, AndroidReminders.CH_QUIET, missed = false, ringing = false).apply { flags = flags or android.app.Notification.FLAG_ONLY_ALERT_ONCE })
        } catch (_: Throwable) {}
    }

    private fun fallback(r: Reminder) {
        try {
            val n = ReminderNotifier.build(this, r, AndroidReminders.CH_SOUND, missed = false, ringing = false)
            n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
            getSystemService(NotificationManager::class.java)?.notify(1000 + r.id, n)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        stopSound(); currentId = -1
        super.onDestroy()
    }
}
