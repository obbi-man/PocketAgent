package ru.pocketagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Озвучка ответов (ru-RU) системным синтезатором речи. */
class Speaker(ctx: Context, private val onState: (Status) -> Unit = {}) {
    enum class Status { INIT, READY, NO_RUSSIAN, NO_ENGINE }
    @Volatile var status = Status.INIT; private set
    private val main = Handler(Looper.getMainLooper())
    private var doneCb: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    var rate = 0.95f

    init {
        tts = try {
            TextToSpeech(ctx.applicationContext) { code -> main.post { onInit(code) } }
        } catch (_: Throwable) { status = Status.NO_ENGINE; main.post { onState(status) }; null }
    }

    private fun onInit(code: Int) {
                status = if (code != TextToSpeech.SUCCESS) Status.NO_ENGINE else {
                    val t = tts!!
                    val r = try { t.setLanguage(Locale("ru", "RU")) } catch (_: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) Status.NO_RUSSIAN else Status.READY
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { fireDone() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { fireDone() }
                    override fun onError(id: String?, errorCode: Int) { fireDone() }
                    override fun onStop(id: String?, interrupted: Boolean) { fireDone() }
                })
                onState(status)
    }

    private fun fireDone() { main.post { val c = doneCb; doneCb = null; c?.invoke() } }

    val ready get() = status == Status.READY

    /** Произносит текст; onDone вызывается в главном потоке (и если озвучка недоступна — сразу). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = forSpeech(text)
        android.util.Log.i("PocketTTS", "speak len=${clean.length} ready=$ready")
        val t = tts
        if (!ready || t == null || clean.isBlank()) { if (onDone != null) main.post(onDone); return }
        doneCb = onDone
        t.setSpeechRate(rate)
        val r = t.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), "pa-" + System.nanoTime())
        if (r != TextToSpeech.SUCCESS) fireDone()
    }

    fun stop() {
        doneCb = null
        val t = tts ?: return
        try { if (t.isSpeaking) android.util.Log.i("PocketTTS", "stop (was speaking)"); t.stop() } catch (_: Throwable) {}
    }
    fun shutdown() { try { tts?.shutdown() } catch (_: Throwable) {} }

    companion object {
        /** Убираем смайлики, разметку и служебные пометки, «9:05» читается нормально. */
        fun forSpeech(s: String): String = s
            .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}]"), "")
            .replace(Regex("[*_#`>|]+"), " ")
            .replace(Regex("\\(Интернета нет[^)]*\\)"), "")
            .replace("«", "").replace("»", "")
            .replace(Regex("\\s+"), " ").trim()

        const val HINT = "Чтобы я говорил вслух, нужен русский голос в системном синтезаторе речи:\n" +
            "Настройки → Общие настройки (или Спец. возможности) → Синтез речи / «Преобразование текста в речь» → " +
            "выберите движок Samsung или Google → «Установить голосовые данные» → Русский."
    }
}

/**
 * Голосовой ввод с цепочкой запасных вариантов:
 * 1) распознавание на устройстве (Android 12+, если есть), 2) системный распознаватель (Google/Samsung —
 * предпочитаем офлайн, но не требуем; так работает на Android 8–11), 3) окно распознавания RecognizerIntent,
 * 4) нет ничего — подсказка.
 */
class VoiceInput(private val ctx: Context) {
    enum class Mode { ON_DEVICE, SYSTEM, INTENT, NONE }

    interface Listener {
        fun onReady() {}
        fun onRms(level: Float) {}
        fun onPartial(text: String) {}
        fun onResult(text: String)
        /** silence — пользователь ничего не сказал (таймаут / не распознано). */
        fun onFail(code: Int, silence: Boolean, message: String)
    }

    private var rec: SpeechRecognizer? = null
    private var onDeviceBroken = false     // нет русского пакета для офлайн-распознавания → дальше системный

    /** Сторонние службы распознавания (не наша собственная — она лишь посредник для режима ассистента). */
    fun systemServices(): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != ctx.packageName }
            .sortedBy { when { it.packageName.startsWith("com.google") -> 0; it.packageName.startsWith("com.samsung") -> 1; else -> 2 } }
    } catch (_: Throwable) { emptyList() }

    fun intentAvailable(): Boolean = try {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    } catch (_: Throwable) { false }

    fun onDeviceAvailable(): Boolean = !onDeviceBroken && Build.VERSION.SDK_INT >= 31 &&
        try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Throwable) { false }

    fun mode(): Mode = when {
        onDeviceAvailable() -> Mode.ON_DEVICE
        systemServices().isNotEmpty() -> Mode.SYSTEM
        intentAvailable() -> Mode.INTENT
        else -> Mode.NONE
    }

    fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)

    /** Для режимов ON_DEVICE и SYSTEM. Возвращает false, если запуск невозможен (тогда использовать INTENT/подсказку). */
    fun start(l: Listener): Boolean {
        stop()
        val m = mode()
        val r = try {
            when (m) {
                Mode.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                Mode.SYSTEM -> SpeechRecognizer.createSpeechRecognizer(ctx, systemServices().first())
                else -> return false
            }
        } catch (_: Throwable) { return false }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = l.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) = l.onRms(v)
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) l.onPartial(it) }
            }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) l.onFail(SpeechRecognizer.ERROR_NO_MATCH, true, "не расслышал") else l.onResult(t)
            }
            override fun onError(error: Int) {
                // 12/13: нет языкового пакета для офлайн-распознавания → в следующий раз системный распознаватель
                if (m == Mode.ON_DEVICE && (error == 12 || error == 13 || error == SpeechRecognizer.ERROR_CLIENT)) onDeviceBroken = true
                val silence = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                l.onFail(error, silence, errorText(error))
            }
        })
        return try { r.startListening(recognizerIntent()); true } catch (_: Throwable) { false }
    }

    fun stop() { try { rec?.cancel(); rec?.destroy() } catch (_: Throwable) {}; rec = null }

    companion object {
        fun errorText(e: Int): String = when (e) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "нужен интернет для распознавания (или скачайте офлайн-пакет русского языка)"
            SpeechRecognizer.ERROR_AUDIO -> "ошибка микрофона"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет доступа к микрофону"
            SpeechRecognizer.ERROR_NO_MATCH -> "не расслышал"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "тишина"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "распознаватель занят"
            12, 13 -> "нет русского языка для офлайн-распознавания"
            else -> "ошибка распознавания $e"
        }

        const val HINT = "На телефоне не найдена служба распознавания речи.\n\n" +
            "Что можно сделать:\n• Samsung: Настройки → Общие настройки → Язык и ввод → Голосовой ввод — выберите Samsung или Google.\n" +
            "• Установите приложение «Google» или «Распознавание и синтез речи от Google» из магазина приложений.\n" +
            "• Пока можно писать текстом — всё остальное работает."
    }
}
