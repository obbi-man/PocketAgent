package ru.pocketagent.voice

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.chip.Chip
import ru.pocketagent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Режим «Цифровой помощник по умолчанию». Android показывает приложение в списке помощников, только если у него есть
 * VoiceInteractionService + SessionService + RecognitionService. Жест/кнопка помощника → компактное окно снизу поверх
 * текущего приложения (как у Gemini) с вводом, микрофоном, живым режимом и «Спросить про экран».
 */
class PocketVoiceService : VoiceInteractionService()

class PocketSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PocketSession(this)
}

class PocketSession(private val svc: VoiceInteractionSessionService) : VoiceInteractionSession(svc) {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var send: ImageView
    private lateinit var mic: ImageView
    private lateinit var answer: TextView
    private lateinit var heard: TextView
    private lateinit var scroll: ScrollView
    private lateinit var hint: TextView
    private lateinit var chipScreen: Chip
    private lateinit var chipRetell: Chip
    private lateinit var chipRead: Chip
    private var themed: Context = svc
    private var voice: VoiceInput? = null
    private var speaker: Speaker? = null
    private var listening = false
    private var busy = false
    private val cancelled = AtomicBoolean(false)

    /** Текст экрана из последнего вызова помощника (null — не получен). */
    @Volatile private var screen: String? = null
    @Volatile private var screenApp: String = ""
    private var assistRequested = false

    companion object {
        private const val TAG = "PocketSheet"
        /** Действия, которым нужно полноэкранное приложение (системные будильники/таймер/ссылки). */
        val NEEDS_APP = setOf("set_alarm", "show_alarms", "set_timer", "open_url", "cancel_alarm", "cancel_timer")
    }

    private fun themedContext(): Context {
        val night = when (Prefs.theme(svc)) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val cfg = Configuration(svc.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return ContextThemeWrapper(svc.createConfigurationContext(cfg), R.style.Theme_PocketAgent)
    }

    override fun onCreate() {
        super.onCreate()
        window?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onCreateContentView(): View {
        themed = themedContext()
        val v = LayoutInflater.from(themed).inflate(R.layout.assist_sheet, null)
        input = v.findViewById(R.id.sheetInput); send = v.findViewById(R.id.sheetSend); mic = v.findViewById(R.id.sheetMic)
        answer = v.findViewById(R.id.sheetAnswer); heard = v.findViewById(R.id.sheetHeard); scroll = v.findViewById(R.id.sheetScroll)
        hint = v.findViewById(R.id.sheetHint)
        chipScreen = v.findViewById(R.id.chipScreen); chipRetell = v.findViewById(R.id.chipRetell); chipRead = v.findViewById(R.id.chipRead)
        v.findViewById<View>(R.id.scrim).setOnClickListener { hide() }
        // окно помощника рисуется под навигационной панелью — отодвигаем поле ввода
        val sheet = v.findViewById<View>(R.id.sheet)
        val pad = sheet.paddingBottom
        val navRes = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navFallback = if (navRes > 0) svc.resources.getDimensionPixelSize(navRes) else 0
        sheet.setPadding(sheet.paddingLeft, sheet.paddingTop, sheet.paddingRight, pad + navFallback)
        v.setOnApplyWindowInsetsListener { _, ins ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()).bottom
                else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
            sheet.setPadding(sheet.paddingLeft, sheet.paddingTop, sheet.paddingRight, pad + maxOf(bottom, if (bottom == 0) navFallback else 0))
            ins
        }
        v.findViewById<View>(R.id.sheetClose).setOnClickListener { hide() }
        v.findViewById<View>(R.id.sheetExpand).setOnClickListener { openApp(null) }
        scroll.layoutParams = scroll.layoutParams.apply { }   // высота ограничивается в showAnswer
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateSend() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        send.setOnClickListener {
            when {
                busy -> { cancelled.set(true) }
                input.text.isNotBlank() -> sendTyped()
                else -> openApp(MainActivity.ACTION_LIVE)   // волна → живой режим
            }
        }
        mic.setOnClickListener { toggleMic() }
        chipScreen.setOnClickListener { askScreen(null) }
        chipRetell.setOnClickListener { askScreen("Перескажи, что на экране") }
        return v
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        assistRequested = showFlags and SHOW_WITH_ASSIST != 0
        android.util.Log.i(TAG, "show flags=$showFlags assist=$assistRequested")
        if (!::input.isInitialized) return
        cancelled.set(false); busy = false
        input.setText(""); heard.text = ""; answer.text = ""; scroll.visibility = View.GONE
        chipRead.visibility = View.GONE; chipRetell.visibility = View.GONE; hint.visibility = View.GONE
        updateSend()
    }

    override fun onHide() {
        super.onHide()
        if (listening) { voice?.stop(); listening = false; setMic(false) }
        speaker?.stop()
        cancelled.set(true)
        Engine.agent(svc).screen = null   // текст экрана не храним после закрытия окна
        screen = null
    }

    override fun onDestroy() { try { voice?.stop(); speaker?.shutdown() } catch (_: Throwable) {}; super.onDestroy() }

    // ---------- экран

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        val lines = ArrayList<String>()
        if (structure != null) {
            screenApp = try { structure.activityComponent?.packageName ?: "" } catch (_: Throwable) { "" }
            for (i in 0 until structure.windowNodeCount) collect(structure.getWindowNodeAt(i).rootViewNode, lines, 0)
        }
        val txt = if (structure == null) null else ScreenText.clean(lines)
        android.util.Log.i(TAG, "assist: structure=${structure != null} app=$screenApp lines=${lines.size} chars=${txt?.length}")
        screen = txt
        ui.post { if (::chipRead.isInitialized && txt != null && txt.isNotBlank()) chipRetell.visibility = View.VISIBLE }
    }

    private fun collect(n: AssistStructure.ViewNode?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 60) return
        if (n.visibility != View.VISIBLE) return
        val pwd = (n.inputType and InputType.TYPE_MASK_VARIATION).let {
            it == InputType.TYPE_TEXT_VARIATION_PASSWORD || it == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || it == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                it == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        if (!pwd) {   // пароли не читаем
            val t = n.text?.toString()
            if (!t.isNullOrBlank()) out += t
            else n.contentDescription?.toString()?.takeIf { it.length in 2..120 && n.childCount == 0 }?.let { out += it }
        }
        for (i in 0 until n.childCount) collect(n.getChildAt(i), out, depth + 1)
    }

    /** Почему текст экрана недоступен: выключены системные настройки или помощник вызван не жестом. */
    private fun screenProblem(): String {
        val structOff = try { Settings.Secure.getInt(svc.contentResolver, "assist_structure_enabled", 1) == 0 } catch (_: Throwable) { false }
        val isAssistant = try { Settings.Secure.getString(svc.contentResolver, "voice_interaction_service")?.startsWith(svc.packageName) == true } catch (_: Throwable) { true }
        return when {
            !isAssistant -> "Чтобы читать экран, сделайте PocketAgent цифровым помощником по умолчанию (меню → «Как сделать ассистентом»)."
            structOff -> "Чтение экрана выключено. Включите: Настройки → Приложения → Выбор приложений по умолчанию → Цифровой помощник → «Анализировать текст на экране» (на других телефонах — «Использовать текст с экрана»)."
            !assistRequested -> "Экран можно прочитать, только если открыть помощника жестом или боковой кнопкой поверх нужного приложения."
            else -> "Это приложение не даёт прочитать свой экран (например, банк или защищённое окно)."
        }
    }

    private fun askScreen(question: String?) {
        val txt = screen
        if (txt == null || txt.isBlank()) {
            hint.text = screenProblem(); hint.visibility = View.VISIBLE
            return
        }
        hint.visibility = View.GONE
        chipRead.visibility = View.VISIBLE
        val agent = Engine.agent(svc)
        agent.screen = DocIndex("экран", DocIndex.Kind.SCREEN, txt)
        if (question != null) submit(question, voice = false, aboutScreen = true)
        else {
            agent.forceScreen = true
            input.hint = "Что сделать с экраном? «перескажи», «переведи»…"
            input.requestFocus(); showKeyboard()
        }
    }

    // ---------- ввод

    private fun showKeyboard() = try { (themed.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) } catch (_: Throwable) { false }

    private fun updateSend() {
        send.setImageResource(when { busy -> R.drawable.ic_stop; input.text.isNotBlank() -> R.drawable.ic_send; else -> R.drawable.ic_wave })
        send.contentDescription = when { busy -> "Остановить"; input.text.isNotBlank() -> "Отправить"; else -> "Живой режим" }
    }

    private fun setMic(on: Boolean) {
        mic.setImageResource(if (on) R.drawable.ic_mic_white else R.drawable.ic_mic)
        mic.background = if (on) themed.getDrawable(R.drawable.bg_grad_circle) else null
    }

    private fun sendTyped() {
        val t = input.text.toString().trim(); if (t.isEmpty()) return
        input.setText("")
        submit(t, voice = false, aboutScreen = false)
    }

    private fun toggleMic() {
        if (listening) { voice?.stop(); listening = false; setMic(false); return }
        if (svc.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openApp(MainActivity.ACTION_LIVE); return   // разрешение на микрофон запрашивает само приложение
        }
        val v = voice ?: VoiceInput(svc).also { voice = it }
        listening = true; setMic(true); input.hint = "Слушаю…"
        val ok = v.start(object : VoiceInput.Listener {
            override fun onPartial(text: String) { ui.post { input.setText(text) } }
            override fun onResult(text: String) { ui.post { listening = false; setMic(false); input.hint = "Спросите что-нибудь…"; input.setText(""); submit(text, voice = true, aboutScreen = false) } }
            override fun onFail(code: Int, silence: Boolean, message: String) { ui.post { listening = false; setMic(false); input.hint = if (silence) "Не расслышал — повторите" else "Голосовой ввод недоступен" } }
        })
        if (!ok) { listening = false; setMic(false); openApp(MainActivity.ACTION_LIVE) }
    }

    private fun showAnswer(q: String, a: String) {
        scroll.visibility = View.VISIBLE
        heard.text = "«$q»"; answer.text = a
        val max = (svc.resources.displayMetrics.heightPixels * 0.42).toInt()
        scroll.post {
            val lp = scroll.layoutParams
            val want = if (scroll.getChildAt(0).height > max) max else -2
            if (lp.height != want) { lp.height = want; scroll.layoutParams = lp }
            scroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun submit(text: String, voice: Boolean, aboutScreen: Boolean) {
        if (busy) return
        val agent = Engine.agent(svc)
        // будильники, таймеры и ссылки выполняет полноэкранное приложение
        val routed = if (aboutScreen || agent.forceScreen) null else try { Router.route(text) } catch (_: Throwable) { null }
        if (routed is Router.Route.Call && routed.call.name in NEEDS_APP) { openApp(MainActivity.ACTION_QUERY, text); return }
        busy = true; cancelled.set(false); updateSend()
        showAnswer(text, if (Session.loadedPath == null && routed == null) "Загружаю модель…" else "…")
        Session.items += ChatItem(ChatItem.Kind.USER, text)
        val speak = SpeakPolicy.shouldSpeak(if (voice) SpeakPolicy.Source.VOICE else SpeakPolicy.Source.TYPED, Prefs.tts(svc), Prefs.ttsAlways(svc))
        var chip: String? = null
        fun done(a: String, item: ChatItem) {
            ui.post {
                busy = false; updateSend(); showAnswer(text, a)
                Session.items += item
                if (speak) (speaker ?: Speaker(svc).also { speaker = it }).speak(a)
            }
        }
        Session.worker.execute {
            try {
                agent.handle(if (aboutScreen) text else text, object : Agent.Events {
                    override fun isCancelled() = cancelled.get()
                    override fun onWebSearch(source: String?, url: String?) { chip = if (source != null) "🌐 искал в интернете · $source" else null }
                    override fun onDocUsed(doc: DocIndex) {
                        chip = if (doc.kind == DocIndex.Kind.SCREEN) "📱 экран прочитан на телефоне" else "📎 ${doc.name}"
                        if (doc.kind == DocIndex.Kind.SCREEN) ui.post { chipRead.visibility = View.VISIBLE }
                    }
                    override fun onToolResult(call: ToolCall, result: ToolResult, source: String) {
                        done(result.answer, ChatItem(ChatItem.Kind.CARD, result.answer, card = result.card ?: Card(if (result.ok) "✅" else "⚠️", result.answer)))
                        if (call.name == "save_note" && result.ok) ui.post { mirrorNote(call.args.optString("text")) }
                    }
                    override fun onAssistantStart() {}
                    override fun onAssistantText(fullSoFar: String) { ui.post { if (fullSoFar.isNotBlank()) showAnswer(text, fullSoFar) } }
                    override fun onAssistantDone(text: String) { done(text, ChatItem(ChatItem.Kind.ASSISTANT, text, chip = chip)) }
                    override fun onError(message: String) { done("Ошибка: $message", ChatItem(ChatItem.Kind.SYSTEM, "Ошибка: $message")) }
                })
            } catch (t: Throwable) {
                done("Внутренняя ошибка: $t", ChatItem(ChatItem.Kind.SYSTEM, "Внутренняя ошибка: $t"))
            }
        }
    }

    private fun mirrorNote(text: String) {
        val pkg = NoteMirror.target(svc) ?: return
        val err = NoteMirror.send(svc, pkg, text)
        if (err != null) Toast.makeText(svc, err, Toast.LENGTH_LONG).show() else hide()
    }

    private fun openApp(action: String?, query: String? = null) {
        val i = Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.action = action
        if (query != null) i.putExtra(MainActivity.EXTRA_QUERY, query)
        try { if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i) else startVoiceActivity(i) }
        catch (_: Throwable) { try { svc.startActivity(i) } catch (_: Throwable) {} }
        hide()
    }
}

/**
 * Обязательная для помощника служба распознавания. Своего распознавателя у нас нет, поэтому она
 * пересылает запрос установленному системному (Google/Samsung). Если такого нет — честная ошибка.
 */
class PocketRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    private fun target(): ComponentName? = try {
        packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != packageName }
            .sortedBy { when { it.packageName.startsWith("com.google") -> 0; it.packageName.startsWith("com.samsung") -> 1; else -> 2 } }
            .firstOrNull()
    } catch (_: Throwable) { null }

    override fun onStartListening(intent: Intent?, cb: Callback) {
        inner?.destroy(); inner = null
        val cn = target()
        val r = try {
            when {
                cn != null -> SpeechRecognizer.createSpeechRecognizer(this, cn)
                Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                else -> null
            }
        } catch (_: Throwable) { null }
        if (r == null) { try { cb.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Throwable) {}; return }
        inner = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { try { cb.readyForSpeech(p ?: Bundle()) } catch (_: Throwable) {} }
            override fun onBeginningOfSpeech() { try { cb.beginningOfSpeech() } catch (_: Throwable) {} }
            override fun onRmsChanged(v: Float) { try { cb.rmsChanged(v) } catch (_: Throwable) {} }
            override fun onBufferReceived(b: ByteArray?) { try { if (b != null) cb.bufferReceived(b) } catch (_: Throwable) {} }
            override fun onEndOfSpeech() { try { cb.endOfSpeech() } catch (_: Throwable) {} }
            override fun onError(e: Int) { try { cb.error(e) } catch (_: Throwable) {} }
            override fun onResults(b: Bundle?) { try { cb.results(b ?: Bundle()) } catch (_: Throwable) {} }
            override fun onPartialResults(b: Bundle?) { try { cb.partialResults(b ?: Bundle()) } catch (_: Throwable) {} }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        try { r.startListening(intent ?: Intent()) } catch (_: Throwable) { try { cb.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Throwable) {} }
    }

    override fun onStopListening(cb: Callback) { try { inner?.stopListening() } catch (_: Throwable) {} }
    override fun onCancel(cb: Callback) { try { inner?.cancel() } catch (_: Throwable) {} }
    override fun onDestroy() { try { inner?.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}
