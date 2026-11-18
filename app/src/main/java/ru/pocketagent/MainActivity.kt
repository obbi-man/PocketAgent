package ru.pocketagent

import android.Manifest
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.time.DayOfWeek
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Состояние, переживающее пересоздание экрана (смена темы/поворот): чат, история агента, загруженная модель. */
object Session {
    val worker = Executors.newSingleThreadExecutor { r -> Thread(null, r, "llm-worker", 64L shl 20) }   // стек 64 МБ
    val items = mutableListOf<ChatItem>()
    var agent: Agent? = null
    @Volatile var loadedPath: String? = null
}

class MainActivity : AppCompatActivity() {
    private val worker get() = Session.worker
    @Volatile private var safeMode = false
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var models: ModelManager
    private lateinit var notes: PrefsNotes
    private lateinit var reminders: AndroidReminders
    lateinit var agent: Agent
    private val items get() = Session.items
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: ImageView
    private lateinit var mic: ImageView
    private lateinit var modelPill: TextView
    private lateinit var statusChip: TextView
    private lateinit var hero: View
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var loadingPanel: View
    private lateinit var loadingText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var retry: Button
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput

    // живой режим
    private lateinit var liveLayer: View
    private lateinit var liveStatus: TextView
    private lateinit var liveHeard: TextView
    private lateinit var liveAnswer: TextView
    private lateinit var liveAction: MaterialButton
    private lateinit var orb: OrbView
    private var liveOn = false
    private var liveSilent = 0
    private var liveErrors = 0
    private var liveToken = 0      // защищает от «запоздалых» колбэков после выхода

    @Volatile private var modelReady = false
    @Volatile private var busy = false
    private val cancelled = AtomicBoolean(false)
    private val updates by lazy { UpdateUi(this) }
    private var chatListening = false

    companion object {
        const val ACTION_LIVE = "ru.pocketagent.LIVE"
        /** Запрос из окна помощника, которому нужно полноэкранное приложение (будильник, таймер, ссылка). */
        const val ACTION_QUERY = "ru.pocketagent.QUERY"
        const val EXTRA_QUERY = "q"
        private const val REQ_FILE = 45
        private const val REQ_TONE = 46
        private const val REQ_IMPORT = 42
        private const val REQ_SPEECH_LIVE = 43
        private const val REQ_SPEECH_CHAT = 44
        private const val REQ_MIC_CHAT = 7
        private const val REQ_MIC_LIVE = 9
        private const val REQ_NOTIF = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelPill = findViewById(R.id.modelPill); statusChip = findViewById(R.id.statusChip); hero = findViewById(R.id.hero)
        applySystemBars()
        list = findViewById(R.id.list); input = findViewById(R.id.input); send = findViewById(R.id.send); mic = findViewById(R.id.mic)
        loadingPanel = findViewById(R.id.loadingPanel); loadingText = findViewById(R.id.loadingText)
        progress = findViewById(R.id.progress); retry = findViewById(R.id.retryButton)
        liveLayer = findViewById(R.id.liveLayer); liveStatus = findViewById(R.id.liveStatus); liveHeard = findViewById(R.id.liveHeard)
        liveAnswer = findViewById(R.id.liveAnswer); liveAction = findViewById(R.id.liveAction); orb = findViewById(R.id.orb)

        models = ModelManager(this)
        notes = PrefsNotes(this)
        reminders = AndroidReminders(this)
        AndroidReminders.ensureChannels(this)
        agent = Engine.agent(this)

        speaker = Speaker(this)
        voice = VoiceInput(this)

        adapter = ChatAdapter(items, { Prefs.fontScale(this) }, { Prefs.debug(this) }, { t -> speaker.speak(t) })
        adapter.onAction = { a -> a.run(this) }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        applyFontScale()

        send.setOnClickListener { if (busy) stopGeneration() else if (input.text.isNullOrBlank()) enterLive() else onSend() }
        mic.setOnClickListener { onMicPressed() }
        retry.setOnClickListener { startDownloadFallback() }
        findViewById<View>(R.id.liveStop).setOnClickListener { exitLive() }
        findViewById<View>(R.id.liveClose).setOnClickListener { exitLive() }
        modelPill.setOnClickListener { chooseModel() }
        findViewById<View>(R.id.profileBtn).setOnClickListener { v -> showMainMenu(v) }
        findViewById<View>(R.id.plus).setOnClickListener { v -> showPlusMenu(v) }
        setupHero()
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                updateSendIcon(); applyFontScale()
                if (!chatListening && !e.isNullOrEmpty()) speaker.stop()   // начал печатать — замолкаем
            }
        })
        updateSendIcon()
        watchNetwork()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (liveOn) exitLive() else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })

        val firstStart = items.isEmpty()
        if (firstStart) {
            if (DeviceInfo.isLowRam(this)) addItem(ChatItem.Kind.SYSTEM, "Телефон с малым объёмом памяти (${DeviceInfo.totalRamMb(this)} МБ): включён экономный режим.")
        }
        val report = if (firstStart) try { CrashReporter.pendingReport() } catch (_: Throwable) { null } else null
        if (report != null) showCrashDialog(report) else prepareModel()
        if (report == null) handleLaunchIntent(intent)
        updateHero()
        if (firstStart) ui.postDelayed({ if (!isFinishing) updates.autoCheck() }, 2500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(i: Intent?) {
        val a = i?.action ?: return
        if (a == ACTION_QUERY) {
            val q = i.getStringExtra(EXTRA_QUERY); i.removeExtra(EXTRA_QUERY)
            if (!q.isNullOrBlank()) ui.postDelayed({ submit(q) }, 300)
            return
        }
        if (a == Intent.ACTION_SEND) { handleShare(i); setIntent(Intent(this, MainActivity::class.java)); return }
        if (a == Intent.ACTION_ASSIST || a == ACTION_LIVE || a == "android.intent.action.VOICE_COMMAND" || a == Intent.ACTION_VOICE_COMMAND)
            ui.post {
                enterLive()
                // демонстрация состояний шара (только при включённых «Технических деталях»; для скриншотов/проверки)
                val demo = i.getStringExtra("orb_demo")
                if (demo != null && Prefs.debug(this) && liveOn) {
                    voice.stop(); liveToken++
                    liveAction.visibility = View.GONE
                    when (demo) {
                        "think" -> { orb.state = OrbView.State.THINKING; liveStatus.text = "Думаю…"; liveHeard.text = "«Какая завтра погода?»"; liveAnswer.text = "" }
                        "speak" -> { orb.state = OrbView.State.SPEAKING; liveStatus.text = "Отвечаю"; liveHeard.text = "«Разбуди меня в семь»"; liveAnswer.text = "Хорошо, будильник на 7:00 поставлен." }
                        else -> { orb.state = OrbView.State.LISTENING; liveStatus.text = "Слушаю…"; liveHeard.text = "Скажите, например: «Напомни через час позвонить сыну»"; liveAnswer.text = "" }
                    }
                }
            }
    }

    private fun applyFontScale() {
        // подсказка помещается в одну строку; набранный текст — крупный, с учётом настройки размера
        val size = if (input.text.isNullOrEmpty()) 18f else 20f * Prefs.fontScale(this)
        if (input.textSize != size * resources.displayMetrics.scaledDensity) input.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun showCrashDialog(report: String) {
        CrashReporter.clear()
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = report; textSize = 11f; setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val copy = Button(this).apply {
            text = "📋 Копировать отчёт"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("PocketAgent crash", report))
                toast("Отчёт скопирован — пришли его разработчику")
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(copy)
            addView(ScrollView(this@MainActivity).apply { addView(tv) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (280 * dp).toInt()))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Прошлый запуск завершился сбоем")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Безопасный режим") { _, _ -> startSafeMode() }
            .setPositiveButton("Обычный запуск") { _, _ -> prepareModel() }
            .show()
    }

    private fun startSafeMode() {
        safeMode = true
        addItem(ChatItem.Kind.SYSTEM, "Безопасный режим: модель не загружена автоматически.")
        setLoading("Безопасный режим. Нажми кнопку, чтобы загрузить модель (контекст 1024, 2 потока).")
        retry.text = "Загрузить модель"
        retry.visibility = View.VISIBLE
        retry.setOnClickListener {
            retry.visibility = View.GONE
            retry.setOnClickListener { startDownloadFallback() }
            prepareModel()
        }
    }

    // ---------- модель

    private fun setLoading(text: String?, frac: Float = -1f) = ui.post {
        if (text == null) { loadingPanel.visibility = View.GONE; return@post }
        loadingPanel.visibility = View.VISIBLE
        loadingText.text = text
        if (frac < 0) { if (!progress.isIndeterminate) { progress.visibility = View.INVISIBLE; progress.isIndeterminate = true; progress.visibility = View.VISIBLE } }
        else { progress.isIndeterminate = false; progress.setProgressCompat((frac * 1000).toInt(), false) }
    }

    private fun prepareModel() {
        modelReady = false
        updateSubtitle()
        worker.execute {
            try {
                // модель уже загружена (например, после смены темы) — не грузим повторно
                if (Session.loadedPath != null && Session.loadedPath == models.activeFile().absolutePath) {
                    modelReady = true; setLoading(null); ui.post { updateSubtitle() }; return@execute
                }
                if (!models.useImported && !models.builtinReady()) {
                    if (!models.hasBundledAsset()) {
                        // Лайт-версия: модели в APK нет — сразу предлагаем скачать
                        ui.post { offerDownload() }
                        return@execute
                    }
                    models.extractBuiltin { done, total ->
                        setLoading("Первый запуск: распаковка встроенной модели… ${done / 1_000_000} / ${total / 1_000_000} МБ", done.toFloat() / total)
                    }
                }
                loadActive()
            } catch (e: Throwable) {
                setLoading("Ошибка подготовки модели: ${e.message}")
                ui.post { retry.visibility = View.VISIBLE }
            }
        }
    }

    /** вызывать в worker-потоке */
    private fun loadActive() {
        val f = models.activeFile()
        setLoading("Загружаю модель в память…")
        val low = DeviceInfo.isLowRam(this)
        val threads = if (safeMode || low) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val ctxLen = if (safeMode) 1024 else if (low) 2048 else 4096
        CrashReporter.markBusy("load_model ${f.name} ctx=$ctxLen threads=$threads")
        val err = try { LlamaBridge.nativeLoad(f.absolutePath, ctxLen, threads) }
                  catch (t: Throwable) { "сбой загрузки: $t" }
                  finally { CrashReporter.clearBusy() }
        if (err != null) {
            setLoading("Не удалось загрузить модель: $err")
            if (models.useImported) ui.post { toast("Импортированная модель не загрузилась — возвращаю встроенную"); models.useImported = false; prepareModel() }
            return
        }
        modelReady = true
        Session.loadedPath = f.absolutePath
        setLoading(null)
        ui.post { retry.visibility = View.GONE; updateSubtitle() }
    }

    private fun offerDownload() {
        val m = models.spec
        val part = models.partFile.length()
        setLoading("Модель «${m.title}» ещё не скачана. Нужно один раз скачать ~${m.sizeMb} МБ (лучше по Wi-Fi), потом всё работает без интернета.")
        retry.text = if (part > 0) "Докачать модель (${part / 1_000_000} из ${m.sizeMb} МБ)" else "Скачать модель (${m.sizeMb} МБ)"
        retry.visibility = View.VISIBLE
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Скачать модель?")
            .setMessage("${m.title}, ~${m.sizeMb} МБ.\n${m.note}\n\nИсточник: Hugging Face (резерв: hf-mirror.com). " +
                    "Скачивается один раз, с докачкой и проверкой SHA-256. Потом интернет не нужен.\n\n" +
                    "Другая модель: меню ⋮ → «Выбрать модель»; свой .gguf — «Импорт модели».")
            .setPositiveButton("Скачать") { _, _ -> startDownloadFallback() }
            .setNeutralButton("Другая модель") { _, _ -> chooseModel() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun chooseModel() {
        if (busy) { toast("Подожди окончания ответа"); return }
        val cat = ModelManager.CATALOG
        val labels = cat.map { m -> (if (m.id == models.spec.id && !models.useImported) "✓ " else "") + m.title + " — ${m.sizeMb} МБ" +
                (if (models.ready(m)) " (скачана)" else if (models.hasBundledAssetFor(m)) " (встроена)" else "") }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Выбрать модель")
            .setItems(labels) { _, which ->
                val m = cat[which]
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(m.title).setMessage(m.note + "\n\nРазмер: ${m.sizeMb} МБ")
                    .setPositiveButton("Использовать") { _, _ ->
                        val ramGb = DeviceInfo.totalRamMb(this) / 1024.0
                        if (ramGb > 0 && ramGb + 0.5 < m.minRamGb) {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Мало памяти")
                                .setMessage("У телефона ~${"%.1f".format(ramGb)} ГБ ОЗУ, а для «${m.title}» рекомендуется ${m.minRamGb}+ ГБ. " +
                                        "Возможны вылеты и очень медленная работа. Всё равно использовать?")
                                .setPositiveButton("Да, попробовать") { _, _ -> switchModel(m) }
                                .setNegativeButton("Отмена", null).show()
                            return@setPositiveButton
                        }
                        switchModel(m)
                    }
                    .setNegativeButton("Отмена", null).show()
            }.show()
    }

    private fun switchModel(m: ModelSpec) {
        run {
            run {
                        models.spec = m; models.useImported = false
                        modelReady = false
                        Session.loadedPath = null
                        worker.execute { try { LlamaBridge.nativeUnload() } catch (_: Throwable) {} }
                        prepareModel()
            }
        }
    }

    private fun startDownloadFallback() {
        retry.visibility = View.GONE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute {
            try {
                models.useImported = false
                models.download({ done, total ->
                    if (done < 0) setLoading("Проверяю контрольную сумму SHA-256…")
                    else setLoading("Скачивание модели… ${done / 1_000_000} / ${total / 1_000_000} МБ", done.toFloat() / total)
                }, { isFinishing })
                loadActive()
            } catch (e: Exception) {
                setLoading("Не удалось скачать: ${e.message}. Уже скачанное сохранено — нажми, чтобы докачать.")
                ui.post {
                    retry.text = "Докачать модель (${models.partFile.length() / 1_000_000} из ${models.spec.sizeMb} МБ)"
                    retry.visibility = View.VISIBLE
                }
            } finally {
                ui.post { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private fun updateSubtitle() {
        modelPill.text = shortModelName(models.activeName()) + (if (agent.agentMode) "" else " · чат")
        modelPill.alpha = if (modelReady) 1f else 0.7f
    }

    // ---------- чат

    private fun addItem(kind: ChatItem.Kind, text: String): Int {
        items += ChatItem(kind, text)
        updateHero()
        adapter.notifyItemInserted(items.lastIndex)
        list.scrollToPosition(items.lastIndex)
        return items.lastIndex
    }

    private fun addRaw(item: ChatItem): Int {
        items += item
        updateHero()
        adapter.notifyItemInserted(items.lastIndex)
        list.scrollToPosition(items.lastIndex)
        return items.lastIndex
    }

    private fun onSend(fromVoice: Boolean = false) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!fromVoice) speaker.stop()
        if (submit(text, voice = fromVoice)) input.setText("")
    }

    /** Отправка сообщения агенту. onFinal — итоговый текст (для живого режима). false — не отправлено. */
    fun submit(text: String, live: Boolean = false, voice: Boolean = false, onFinal: (String) -> Unit = {}): Boolean {
        if (busy) { toast("Подождите, я ещё отвечаю…"); return false }
        // правила (будильники, напоминания, заметки…) работают и без загруженной модели
        val ruleOnly = !modelReady && (agent.pending != null || (agent.agentMode && (try { Router.route(text) } catch (_: Throwable) { null }) != null) ||
            (agent.file != null && DocQA.pick(text, agent.file, null, false) != null && DocQA.intent(text) in setOf(DocQA.Intent.FIND, DocQA.Intent.READ, DocQA.Intent.NUMBER)))
        if (!modelReady && !ruleOnly) {
            val msg = "Модель ещё готовится — пока я понимаю только команды: будильник, напоминание, заметки, таймер, время."
            if (live) onFinal(msg) else toast(msg)
            return false
        }
        speaker.stop()
        addItem(ChatItem.Kind.USER, text)
        setBusy(true)
        cancelled.set(false)
        var assistantIdx = -1
        var chip: String? = null
        val source = when { live -> SpeakPolicy.Source.LIVE; voice -> SpeakPolicy.Source.VOICE; else -> SpeakPolicy.Source.TYPED }
        val speakIt = SpeakPolicy.shouldSpeak(source, Prefs.tts(this), Prefs.ttsAlways(this))
        worker.execute { try {
            agent.handle(text, object : Agent.Events {
                override fun isCancelled() = cancelled.get()
                override fun onWebSearch(source: String?, url: String?) {
                    chip = if (source != null) "🌐 искал в интернете · $source" else "📴 источник не найден — факты не выдумываю"
                }
                override fun onFactsSaved(facts: List<Fact>, auto: Boolean) {
                    if (!auto) return
                    ui.post {
                        addRaw(ChatItem(ChatItem.Kind.CARD, "Запомнил", card = Card("🧠", "Запомнил", facts.joinToString("\n") { it.text }),
                            actions = listOf(CardAction("Отменить") { a -> facts.forEach { f -> agent.facts.remove(f.id) }; (a as MainActivity).toast("Хорошо, не запоминаю") },
                                CardAction("Что я о вас знаю") { a -> a.startActivity(Intent(a, MemoryActivity::class.java)) })))
                    }
                }
                override fun onDocUsed(doc: DocIndex) {
                    chip = if (doc.kind == DocIndex.Kind.SCREEN) "📱 экран прочитан на телефоне" else "📎 по файлу «${doc.name}»"
                }
                override fun onToolResult(call: ToolCall, result: ToolResult, source: String) {
                    ui.post {
                        showToolCard(call, result, source)
                        result.action?.let { performAction(it, live) }
                        setBusy(false)
                        if (speakIt && !live) speaker.speak(result.answer)
                        onFinal(result.answer)
                    }
                }
                override fun onAssistantStart() { ui.post { assistantIdx = addRaw(ChatItem(ChatItem.Kind.ASSISTANT, "…", chip = chip)) } }
                override fun onAssistantText(fullSoFar: String) {
                    ui.post {
                        if (assistantIdx >= 0 && fullSoFar.isNotEmpty()) {
                            items[assistantIdx].text = fullSoFar; adapter.notifyItemChanged(assistantIdx)
                            list.scrollToPosition(items.lastIndex)
                            if (live) liveAnswer.text = fullSoFar
                        }
                    }
                }
                override fun onAssistantDone(text: String) {
                    ui.post {
                        if (assistantIdx >= 0) { items[assistantIdx].text = text; adapter.notifyItemChanged(assistantIdx) }
                        setBusy(false)
                        if (speakIt && !live) speaker.speak(text)
                        onFinal(text)
                    }
                }
                override fun onError(message: String) { ui.post { addItem(ChatItem.Kind.SYSTEM, "Ошибка: $message"); setBusy(false); onFinal("Произошла ошибка.") } }
            }, live)
        } catch (t: Throwable) {
            ui.post { addItem(ChatItem.Kind.SYSTEM, "Внутренняя ошибка: $t"); onFinal("Произошла ошибка.") }
        } finally { ui.post { if (busy) setBusy(false) } } }
        return true
    }

    /** Карточка вместо JSON; JSON — только при включённых «Технических деталях». */
    private fun showToolCard(call: ToolCall, r: ToolResult, source: String) {
        val card = r.card ?: Card(if (r.ok) "✅" else "⚠️", r.answer)
        val actions = mutableListOf<CardAction>()
        when (call.name) {
            "edit_reminder" -> {
                (r.action as? UiAction.ReminderSet)?.let { rs ->
                    actions += CardAction("Отменить") { a -> (a as MainActivity).cancelReminder(rs.reminder.id) }
                    actions += CardAction("Все напоминания") { a -> a.startActivity(Intent(a, ListsActivity::class.java)) }
                }
                (r.action as? UiAction.ChooseEdit)?.let { ch ->
                    ch.candidates.take(4).forEachIndexed { i, _ -> actions += CardAction("${i + 1}") { a -> (a as MainActivity).submit("${i + 1}") } }
                }
                if (r.action == UiAction.OpenLists) actions += CardAction("Все напоминания") { a -> a.startActivity(Intent(a, ListsActivity::class.java)) }
            }
            "move_alarm" -> actions += CardAction("Будильники") { a -> (a as MainActivity).showAlarms() }
            "set_reminder" -> (r.action as? UiAction.ReminderSet)?.let { rs ->
                actions += CardAction("Отменить") { a -> (a as MainActivity).cancelReminder(rs.reminder.id) }
                actions += CardAction("Все напоминания") { a -> a.startActivity(Intent(a, ListsActivity::class.java)) }
            }
            "list_reminders", "read_notes" -> actions += CardAction("Открыть список") { a -> a.startActivity(Intent(a, ListsActivity::class.java)) }
            "set_alarm", "show_alarms", "cancel_alarm" -> actions += CardAction("Будильники") { a -> (a as MainActivity).showAlarms() }
            "cancel_timer" -> actions += CardAction("Открыть таймер") { a -> (a as MainActivity).showTimers() }
            "cancel_reminder" -> (r.action as? UiAction.ChooseReminder)?.let { ch ->
                ch.candidates.take(4).forEachIndexed { i, rem ->
                    actions += CardAction("Удалить ${i + 1}") { a -> (a as MainActivity).submitRemoveReminder(rem) }
                }
            }
            "save_note" -> {
                if (r.ok) noteMirrorActions(call.args.optString("text"), actions)
                actions += CardAction("Открыть список") { a -> a.startActivity(Intent(a, ListsActivity::class.java)) }
            }
        }
        val debug = "🔧 ${call.name} ${call.args}\n← ${r.forModel}\n(выбрано: $source)"
        addRaw(ChatItem(ChatItem.Kind.CARD, r.answer, card = card, debug = debug, actions = actions))
    }

    fun submitRemoveReminder(rem: Reminder) {
        agent.pending = null
        reminders.remove(rem.id)
        addItem(ChatItem.Kind.SYSTEM, "Напоминание удалено: ${rem.text}")
    }

    // ---------- заметки → системное приложение заметок

    private fun noteMirrorActions(text: String, actions: MutableList<CardAction>) {
        if (text.isBlank()) return
        val target = NoteMirror.target(this)
        if (target != null) {
            val err = NoteMirror.send(this, target, text)
            if (err != null) addItem(ChatItem.Kind.SYSTEM, "Не удалось продублировать заметку: $err")
            actions += CardAction("Открыть в Заметках") { a -> if (!NoteMirror.open(a, target)) (a as MainActivity).toast("Приложение заметок не найдено") }
            return
        }
        if (NoteMirror.asked(this)) return
        val best = NoteMirror.best(this) ?: return
        actions += CardAction("Дублировать в ${best.label}") { a ->
            NoteMirror.setTarget(a, best.pkg)
            NoteMirror.send(a, best.pkg, text)?.let { (a as MainActivity).toast(it) }
        }
        actions += CardAction("Не дублировать") { a -> NoteMirror.setAsked(a); (a as MainActivity).toast("Хорошо. Включить можно в Настройках → «Дублировать заметки в…»") }
    }

    private fun chooseNoteMirror() {
        val apps = NoteMirror.candidates(this)
        val labels = (listOf("Не дублировать") + apps.map { it.label }).toTypedArray()
        val cur = NoteMirror.target(this)
        val sel = if (cur == null) 0 else apps.indexOfFirst { it.pkg == cur } + 1
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Дублировать заметки в…")
            .setSingleChoiceItems(labels, sel) { d, w ->
                d.dismiss()
                if (w == 0) { NoteMirror.setTarget(this, null); toast("Заметки хранятся только в PocketAgent") }
                else { NoteMirror.setTarget(this, apps[w - 1].pkg); toast("Новые заметки будут копироваться в «${apps[w - 1].label}»") }
            }.setNegativeButton("Отмена", null).show()
    }

    private fun exportNotes() {
        val all = notes.all()
        if (all.isEmpty()) { toast("Заметок пока нет"); return }
        val i = NoteMirror.shareIntent(NoteMirror.exportAll(all), "Заметки PocketAgent")
        NoteMirror.target(this)?.let { i.setPackage(it) }
        try { startActivity(if (i.`package` != null) i else Intent.createChooser(i, "Экспорт заметок")) }
        catch (_: Exception) { startActivity(Intent.createChooser(i.setPackage(null), "Экспорт заметок")) }
    }

    // ---------- файлы

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/pdf", "application/json",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*"))
        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_FILE) } catch (_: Exception) { toast("Нет приложения для выбора файлов") }
    }

    private fun handleShare(i: Intent) {
        @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) { attach(uri, i.type); return }
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val title = i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() } ?: "текст из другого приложения"
        if (text.length < 280 && !text.contains('\n')) { ui.post { input.setText(text); input.setSelection(text.length) }; return }
        setDoc(DocIndex(title, DocIndex.Kind.FILE, text))
    }

    private fun attach(uri: Uri, mime0: String?) {
        var name = "файл"; var size = -1L
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        } } catch (_: Exception) {}
        val mime = mime0 ?: contentResolver.getType(uri)
        when (DocText.kindOf(name, mime)) {
            "image" -> {
                addItem(ChatItem.Kind.SYSTEM, "Текст с фотографий я пока не распознаю (для русского нужен большой модуль распознавания, он не помещается в приложение).\n" +
                    "Совет: откройте фото в Галерее Samsung, нажмите значок «T» (извлечь текст) → «Поделиться» → PocketAgent.")
                return
            }
            "unknown" -> { addItem(ChatItem.Kind.SYSTEM, "Этот тип файла не поддерживается: $name. Поддерживаются TXT, MD, CSV, JSON, PDF, DOCX."); return }
        }
        if (size > 30_000_000) { addItem(ChatItem.Kind.SYSTEM, "Файл слишком большой (${size / 1_000_000} МБ). Максимум — 30 МБ."); return }
        val idx = addItem(ChatItem.Kind.SYSTEM, "📎 Читаю «$name»…")
        Executors.newSingleThreadExecutor().execute {
            val res = try {
                val kind = DocText.kindOf(name, mime)
                val text = contentResolver.openInputStream(uri)!!.use { ins ->
                    when (kind) {
                        "pdf" -> PdfText.extract(applicationContext, ins)
                        "docx" -> DocText.docx(ins)
                        else -> DocText.readText(ins).let { if (name.endsWith(".htm", true) || name.endsWith(".html", true)) DocText.html(it) else it }
                    }
                }
                Result.success(text)
            } catch (e: Throwable) { Result.failure(e) }
            ui.post {
                if (idx in items.indices) { items.removeAt(idx); adapter.notifyItemRemoved(idx) }
                res.onSuccess { t ->
                    if (t.isBlank()) addItem(ChatItem.Kind.SYSTEM, "В файле «$name» не нашлось текста" + if (name.endsWith(".pdf", true)) " (похоже, это скан — картинки я не распознаю)." else ".")
                    else setDoc(DocIndex(name, DocIndex.Kind.FILE, t))
                }.onFailure { addItem(ChatItem.Kind.SYSTEM, "Не удалось прочитать «$name»: ${it.message ?: it}") }
            }
        }
    }

    private fun setDoc(d: DocIndex) {
        agent.file = d
        val kchars = if (d.text.length >= 1000) "${d.text.length / 1000} тыс. знаков" else "${d.text.length} знаков"
        addRaw(ChatItem(ChatItem.Kind.CARD, "Файл прикреплён: ${d.name}", card = Card("📎", d.name, "$kchars · спросите: «перескажи файл», «о чём файл?», «найди …»"),
            actions = listOf(
                CardAction("Пересказать") { a -> (a as MainActivity).submit("Перескажи файл") },
                CardAction("Убрать файл") { a -> (a as MainActivity).let { m -> m.agent.file = null; m.toast("Файл убран из разговора") } },
            )))
    }

    fun cancelReminder(id: Int) {
        reminders.remove(id)
        addItem(ChatItem.Kind.SYSTEM, "Напоминание отменено.")
    }

    // ---------- голосовой ввод (кнопка 🎤)

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showVoiceHint() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Голосовой ввод недоступен").setMessage(VoiceInput.HINT)
            .setPositiveButton("Настройки голосового ввода") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
            }
            .setNegativeButton("Понятно", null).show()
    }

    private fun onMicPressed() {
        if (chatListening) { voice.stop(); chatListening = false; setMicActive(false); return }
        if (voice.mode() == VoiceInput.Mode.NONE) { showVoiceHint(); return }
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_CHAT); return }
        startChatListening()
    }

    private fun startChatListening() {
        speaker.stop()
        val ok = voice.start(object : VoiceInput.Listener {
            override fun onReady() { toast("Говорите…") }
            override fun onPartial(text: String) { input.setText(text) }
            override fun onResult(text: String) { chatListening = false; setMicActive(false); input.setText(text); onSend(fromVoice = true) }
            override fun onFail(code: Int, silence: Boolean, message: String) {
                chatListening = false; setMicActive(false)
                if (!silence && voice.mode() == VoiceInput.Mode.NONE) showVoiceHint() else toast("Голос: $message")
            }
        })
        if (ok) { chatListening = true; setMicActive(true) }
        else if (voice.intentAvailable()) try { @Suppress("DEPRECATION") startActivityForResult(voice.recognizerIntent(), REQ_SPEECH_CHAT) } catch (_: Exception) { showVoiceHint() }
        else showVoiceHint()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC_CHAT -> if (granted) startChatListening() else toast("Без доступа к микрофону голосовой ввод недоступен")
            REQ_MIC_LIVE -> if (granted && liveOn) liveListen() else if (liveOn) liveProblem("Нужен доступ к микрофону", "Без него живой режим не может вас слышать. Разрешите микрофон в настройках приложения.", "Открыть настройки") {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
            REQ_NOTIF -> if (!granted) addItem(ChatItem.Kind.SYSTEM, "Уведомления запрещены — напоминания не будут показываться. Включить: Настройки → Приложения → PocketAgent → Уведомления.")
        }
    }

    // ---------- живой режим: слушаю → отвечаю → говорю → снова слушаю

    private val liveListener = object : VoiceInput.Listener {
        override fun onReady() { if (liveOn) { liveStatus.text = "Слушаю…"; orb.state = OrbView.State.LISTENING } }
        override fun onRms(level: Float) { orb.setRms(level) }
        override fun onPartial(text: String) { if (liveOn) liveHeard.text = text }
        override fun onResult(text: String) {
            if (!liveOn) return
            liveSilent = 0; liveErrors = 0
            liveHeard.text = "«$text»"
            if (Regex("(?iu)^\\s*(стоп|хватит|выход|выйди|закрой|всё|все|пока|до свидания|спасибо,?\\s*(всё|все|хватит)?)\\s*[.!]*\\s*$").matches(text)) {
                liveSay("До свидания!") { exitLive() }; return
            }
            liveStatus.text = "Думаю…"; orb.state = OrbView.State.THINKING
            liveAnswer.text = ""
            val token = liveToken
            val sent = submit(text, live = true) { answer -> if (liveOn && token == liveToken) { liveAnswer.text = Speaker.forSpeech(answer); liveSay(answer) { liveListen() } } }
            if (!sent && !busy) { liveSay("Модель ещё загружается. Попробуйте команду, например: разбуди меня в семь.") { liveListen() } }
        }
        override fun onFail(code: Int, silence: Boolean, message: String) {
            if (!liveOn) return
            if (silence) {
                liveSilent++
                if (liveSilent >= 2) { liveStatus.text = "Тишина — выхожу"; liveSay("Выхожу из живого режима.") { exitLive() } }
                else { liveStatus.text = "Не расслышал. Говорите…"; ui.postDelayed({ liveListen() }, 300) }
                return
            }
            liveErrors++
            when {
                code == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY && liveErrors < 4 -> ui.postDelayed({ liveListen() }, 700)
                liveErrors >= 3 -> liveProblem("Голос: $message", if (voice.mode() == VoiceInput.Mode.NONE) VoiceInput.HINT else
                    "Распознавание речи не работает: $message.\nПроверьте интернет или скачайте офлайн-пакет русского языка в настройках голосового ввода.", "Повторить") {
                    liveErrors = 0; liveAction.visibility = View.GONE; liveListen()
                }
                else -> ui.postDelayed({ liveListen() }, 600)
            }
        }
    }

    private fun liveSay(text: String, then: () -> Unit) {
        if (!liveOn) return
        val token = liveToken
        val done = { if (liveOn && token == liveToken) then() }
        if (speaker.ready) {
            liveStatus.text = "Отвечаю…"; orb.state = OrbView.State.SPEAKING
            speaker.speak(text) { done() }
        } else {
            // без русского голоса: показываем текст и даём время прочитать
            ui.postDelayed({ done() }, (1500L + text.length * 35L).coerceAtMost(9000L))
        }
    }

    private fun liveProblem(status: String, details: String, button: String, action: () -> Unit) {
        liveStatus.text = status; orb.state = OrbView.State.ERROR
        liveAnswer.text = details
        liveAction.text = button; liveAction.visibility = View.VISIBLE
        liveAction.setOnClickListener { action() }
    }

    private fun enterLive() {
        if (liveOn) return
        liveOn = true; liveSilent = 0; liveErrors = 0; liveToken++
        if (chatListening) { voice.stop(); chatListening = false; setMicActive(false) }
        speaker.stop()
        liveLayer.visibility = View.VISIBLE
        liveHeard.text = ""; liveAnswer.text = ""; liveAction.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orb.state = OrbView.State.IDLE
        if (voice.mode() == VoiceInput.Mode.NONE) {
            liveProblem("Голосовой ввод недоступен", VoiceInput.HINT, "Писать текстом") { exitLive(); input.requestFocus() }
            return
        }
        if (!hasMic()) { liveStatus.text = "Разрешите доступ к микрофону"; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_LIVE); return }
        if (speaker.status == Speaker.Status.NO_RUSSIAN || speaker.status == Speaker.Status.NO_ENGINE) {
            liveAction.text = "🔈 Как включить голос ответа"; liveAction.visibility = View.VISIBLE
            liveAction.setOnClickListener { showTtsHint() }
        }
        liveListen()
    }

    private fun liveListen() {
        if (!liveOn) return
        if (busy) { ui.postDelayed({ liveListen() }, 500); return }
        liveStatus.text = "Слушаю…"; orb.state = OrbView.State.LISTENING
        val started = voice.mode() != VoiceInput.Mode.INTENT && voice.start(liveListener)
        if (!started) {
            if (voice.intentAvailable()) try { @Suppress("DEPRECATION") startActivityForResult(voice.recognizerIntent(), REQ_SPEECH_LIVE) }
                catch (_: Exception) { liveProblem("Голосовой ввод недоступен", VoiceInput.HINT, "Писать текстом") { exitLive() } }
            else liveProblem("Голосовой ввод недоступен", VoiceInput.HINT, "Писать текстом") { exitLive(); input.requestFocus() }
        }
    }

    private fun exitLive() {
        if (!liveOn) return
        liveOn = false; liveToken++
        voice.stop(); speaker.stop()
        if (busy) stopGeneration()
        liveLayer.visibility = View.GONE
        orb.state = OrbView.State.IDLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showTtsHint() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Голос ответа").setMessage(Speaker.HINT)
            .setPositiveButton("Открыть настройки") { _, _ ->
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) { toast("Не нашёл настройки синтеза речи") } }
            }.setNegativeButton("Понятно", null).show()
    }

    private fun stopGeneration() { cancelled.set(true); LlamaBridge.nativeStop() }

    private fun setBusy(b: Boolean) { busy = b; ui.post { updateSendIcon() } }

    // ---------- действия инструментов

    fun showAlarms() {
        try { startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        catch (_: Exception) { toast("На телефоне нет приложения «Часы» со списком будильников"); startActivity(Intent(this, ListsActivity::class.java)) }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    /** Будильник через «Часы» (ACTION_SET_ALARM); если «Часов» нет или дата дальше суток — свой будильник-уведомление. */
    private fun setAlarm(a: UiAction.Alarm) {
        val farDate = a.daysMask == 0 && a.atMs - System.currentTimeMillis() > 24 * 3600_000L
        if (!farDate) {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_VIBRATE, true)
            if (a.label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
            if (a.daysMask != 0) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(TimeParser.fromMask(a.daysMask).map { calDay(it) }))
            try { startActivity(i); toast("Будильник передан в приложение «Часы»"); return }
            catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        }
        // резерв: собственный будильник через AlarmManager
        ensureNotificationPermission()
        val at = if (a.atMs > 0) a.atMs else Router.millis(TimeParser.nextOccurrence(java.time.LocalDateTime.now(), a.hour, a.minute, TimeParser.fromMask(a.daysMask)))
        reminders.add(at, a.hour, a.minute, a.daysMask, a.label.ifBlank { "Будильник" }, alarm = true)
        addItem(ChatItem.Kind.SYSTEM, if (farDate) "«Часы» ставят будильник только на ближайшие сутки, поэтому я поставил свой: в нужное время прозвучит сигнал будильника с уведомлением."
            else "Приложение «Часы» не найдено — поставил свой будильник: в нужное время прозвучит сигнал с уведомлением.")
    }

    private fun calDay(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> java.util.Calendar.MONDAY; DayOfWeek.TUESDAY -> java.util.Calendar.TUESDAY
        DayOfWeek.WEDNESDAY -> java.util.Calendar.WEDNESDAY; DayOfWeek.THURSDAY -> java.util.Calendar.THURSDAY
        DayOfWeek.FRIDAY -> java.util.Calendar.FRIDAY; DayOfWeek.SATURDAY -> java.util.Calendar.SATURDAY
        DayOfWeek.SUNDAY -> java.util.Calendar.SUNDAY
    }

    private fun performAction(a: UiAction, live: Boolean) {
        when (a) {
            is UiAction.OpenUrl -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Открыть ссылку?").setMessage(a.url)
                .setPositiveButton("Открыть") { _, _ ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
                    catch (_: ActivityNotFoundException) { toast("Нет браузера") }
                }
                .setNegativeButton("Отмена", null).show()
            is UiAction.Timer -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                try { startActivity(i); toast("Таймер передан в приложение «Часы»") }
                catch (_: Exception) {
                    // нет «Часов» — надёжный резерв через AlarmManager (сработает и при закрытом приложении)
                    ensureNotificationPermission()
                    val at = System.currentTimeMillis() + a.seconds * 1000L
                    val t = java.time.LocalDateTime.now().plusSeconds(a.seconds.toLong())
                    reminders.add(at, t.hour, t.minute, 0, "⏱ ${a.label}", alarm = true)
                    toast("Нет приложения «Часы» — таймер поставлен внутри PocketAgent")
                }
            }
            is UiAction.Alarm -> setAlarm(a)
            is UiAction.ShowAlarms -> if (!live) showAlarms()
            is UiAction.ReminderSet -> {
                if (a.stopRinging > 0) ReminderSoundService.stop(this, a.stopRinging)
                ensureNotificationPermission()
                if (!a.exact && Build.VERSION.SDK_INT >= 31) addItem(ChatItem.Kind.SYSTEM,
                    "Нет разрешения на точные будильники — напоминание может прийти с опозданием. Разрешить: Настройки → Приложения → PocketAgent → «Будильники и напоминания».")
            }
            UiAction.OpenLists -> {}
            is UiAction.CancelTimer -> cancelTimer()
            is UiAction.CancelAlarm -> cancelAlarm(a, live)
            is UiAction.ChooseReminder -> {}
            is UiAction.ChooseEdit -> {}
            is UiAction.MoveAlarm -> moveAlarm(a, live)
        }
    }

    fun showTimers() {
        try { startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS)) } catch (_: Exception) { toast("Нет приложения «Часы»") }
    }

    private fun isSamsungClock(pkg: String?) = pkg != null && (pkg.startsWith("com.sec.android.app.clock") || pkg.contains("samsung"))

    /** Остановить таймер: ACTION_DISMISS_TIMER (Android 9+); если «Часы» не умеют — открыть таймеры и попросить нажать «Стоп». */
    private fun cancelTimer() {
        val i = Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val target = if (Build.VERSION.SDK_INT >= 28) i.resolveActivity(packageManager)?.packageName else null
        if (target != null) {
            try {
                startActivity(i)
                if (isSamsungClock(target)) addItem(ChatItem.Kind.SYSTEM, "Если таймер не остановился, откройте «Часы» → «Таймер» и нажмите «Стоп» (кнопка «Открыть таймер» выше).")
                else toast("Команда остановки передана в «Часы»")
                return
            } catch (_: Exception) {}
        }
        try { startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS)) } catch (_: Exception) {}
        addItem(ChatItem.Kind.SYSTEM, "«Часы» не дают остановить таймер командой — я открыл таймеры: нажмите «Стоп» или «Сброс».")
    }

    /** Выключить будильник: ACTION_DISMISS_ALARM с режимом поиска (время / подпись / все / ближайший), резерв — список будильников. */
    /** 2.6: перенос будильника — новый ставим (без окна «Часов»), старый по времени выключаем; время неизвестно — открываем список. */
    private fun moveAlarm(a: UiAction.MoveAlarm, live: Boolean) {
        setAlarm(UiAction.Alarm(a.hour, a.minute, a.daysMask, "", a.atMs))
        if (a.oldHour != null && !(a.oldHour == a.hour && a.oldMinute == a.minute))
            ui.postDelayed({ cancelAlarm(UiAction.CancelAlarm(a.oldHour, a.oldMinute, "", false), live) }, 700)
        else if (a.oldHour == null && !live) ui.postDelayed({ showAlarms() }, 700)
    }

    private fun cancelAlarm(a: UiAction.CancelAlarm, live: Boolean) {
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        when {
            a.all -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
            a.hour != null -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, a.hour).putExtra(AlarmClock.EXTRA_MINUTES, a.minute ?: 0).putExtra(AlarmClock.EXTRA_IS_PM, a.hour >= 12)
            a.label.isNotBlank() -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
            else -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        if (i.resolveActivity(packageManager) != null) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        if (!live) showAlarms()
        addItem(ChatItem.Kind.SYSTEM, "«Часы» не принимают команду выключения — открыл список будильников: выключите нужный переключателем.")
    }

    // ---------- оформление (v2.2)

    override fun onResume() {
        super.onResume(); updateStatusChip()
        // ответы из окна помощника тоже попадают в чат
        if (::adapter.isInitialized && adapter.itemCount != items.size) { adapter.notifyDataSetChanged(); updateHero(); if (items.isNotEmpty()) list.scrollToPosition(items.lastIndex) }
        updates.resumePending()
    }

    private fun applySystemBars() {
        val light = resources.getBoolean(R.bool.pa_light)
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = light
        c.isAppearanceLightNavigationBars = light
    }

    /** «Vikhr-Qwen2.5-0.5B (русский)» → «Vikhr 0.5B». */
    private fun shortModelName(full: String): String {
        var n = full.substringBefore(" (").removeSuffix(".gguf").replace(Regex("(?i)-?Qwen-?2\\.5"), "")
        val size = Regex("(?i)(\\d+(?:\\.\\d+)?)b").find(n)?.value
        if (size != null) {
            val base = n.substringBefore(size).trim('-', '_', ' ', '.')
            n = (if (base.isEmpty()) n else "$base ${size.uppercase()}")
        }
        return if (n.length > 16) n.take(15) + "…" else n
    }

    private fun setupHero() {
        fun card(id: Int, icon: Int, title: String, label: String, onTap: () -> Unit) {
            val v = findViewById<View>(id)
            v.findViewById<ImageView>(R.id.hcIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.hcTitle).text = title
            v.findViewById<TextView>(R.id.hcLabel).text = label
            v.contentDescription = "$title. $label"
            v.setOnClickListener { onTap() }
        }
        card(R.id.cardAlarm, R.drawable.ic_alarm, "Будильник на 7:00", "Система") { submit("Будильник на 7:00") }
        card(R.id.cardTimer, R.drawable.ic_timer, "Таймер на 5 минут", "Таймер") { submit("Таймер на 5 минут") }
        card(R.id.cardRemind, R.drawable.ic_bell, "Напомни выпить витамины", "Задачи") { submit("Напомни выпить витамины") }
        card(R.id.cardNote, R.drawable.ic_edit, "Записать новую заметку", "Заметки") {
            input.setText("Запиши: "); input.setSelection(input.text.length); input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)
        }
        // «Привет!» — градиент фиолетовый → розовый
        val hello = findViewById<TextView>(R.id.heroHello)
        hello.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val tv = v as TextView
            if (r - l > 0) tv.paint.shader = android.graphics.LinearGradient(0f, 0f, (r - l).toFloat(), 0f,
                intArrayOf(android.graphics.Color.parseColor("#8B5CF6"), android.graphics.Color.parseColor("#C084FC"), android.graphics.Color.parseColor("#EC4899")),
                null, android.graphics.Shader.TileMode.CLAMP)
        }
        updateStatusChip()
    }

    private fun updateHero() {
        val empty = items.none { it.kind == ChatItem.Kind.USER }
        hero.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.INVISIBLE else View.VISIBLE
    }

    private fun updateStatusChip() {
        if (!::statusChip.isInitialized) return
        val online = Prefs.web(this) && isOnline()
        val text = if (online) "●  Онлайн: поиск включён" else "●  Офлайн-режим"
        val sp = android.text.SpannableString(text)
        val dot = if (online) getColor(R.color.pa_green) else getColor(R.color.pa_blue)
        sp.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        statusChip.text = sp
        statusChip.contentDescription = if (online) "Онлайн, поиск в интернете включён" else "Офлайн-режим, всё на телефоне"
    }

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    private fun watchNetwork() {
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { ui.post { updateStatusChip() } }
                override fun onLost(network: android.net.Network) { ui.post { updateStatusChip() } }
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) { ui.post { updateStatusChip() } }
            }
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    private fun updateSendIcon() {
        if (!::send.isInitialized) return
        when {
            busy -> { send.setImageResource(R.drawable.ic_stop); send.contentDescription = "Остановить ответ" }
            !input.text.isNullOrBlank() -> { send.setImageResource(R.drawable.ic_send); send.contentDescription = "Отправить" }
            else -> { send.setImageResource(R.drawable.ic_wave); send.contentDescription = "Живой режим" }
        }
    }

    private fun setMicActive(on: Boolean) {
        if (on) { mic.setImageResource(R.drawable.ic_mic_white); mic.setBackgroundResource(R.drawable.bg_grad_circle) }
        else { mic.setImageResource(R.drawable.ic_mic); mic.setBackgroundResource(0) }
    }

    private fun showMainMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menuInflater.inflate(R.menu.main, pm.menu)
        pm.setOnMenuItemClickListener { onOptionsItemSelected(it) }
        pm.show()
    }

    /** Настройки — диалог с крупными переключателями (длинные подписи переносятся, а не обрезаются). */
    private fun showSettings() {
        val holder = PopupMenu(this, findViewById(R.id.profileBtn)); holder.menuInflater.inflate(R.menu.settings, holder.menu)
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt()) }
        var dlg: AlertDialog? = null
        fun toggle(id: Int, value: Boolean) {
            val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                text = holder.menu.findItem(id).title; textSize = 18f; isChecked = value
                minHeight = (56 * dp).toInt(); setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                setOnCheckedChangeListener { _, _ -> onOptionsItemSelected(holder.menu.findItem(id)) }
            }
            box.addView(sw, LinearLayout.LayoutParams(-1, -2))
        }
        fun action(id: Int) {
            box.addView(TextView(this).apply {
                text = holder.menu.findItem(id).title; textSize = 18f; setTextColor(getColor(R.color.pa_text))
                minHeight = (54 * dp).toInt(); gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { dlg?.dismiss(); onOptionsItemSelected(holder.menu.findItem(id)) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        toggle(R.id.m_tts, Prefs.tts(this)); toggle(R.id.m_tts_always, Prefs.ttsAlways(this))
        toggle(R.id.m_web, Prefs.web(this)); toggle(R.id.m_agent, agent.agentMode); toggle(R.id.m_debug, Prefs.debug(this))
        if (Updater.enabled()) { toggle(R.id.m_upd_auto, Updater.autoCheck(this)); toggle(R.id.m_upd_wifi, Updater.wifiOnly(this)) }
        reminderSoundSection(box) { dlg?.dismiss() }
        for (id in intArrayOf(R.id.m_mirror, R.id.m_export, R.id.m_font, R.id.m_theme, R.id.m_models, R.id.m_guide, R.id.m_import, R.id.m_builtin)) action(id)
        dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Настройки").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Готово", null).show()
    }

    /** Настройки → «Звук напоминаний», мелодия, «Проверить звук», подсказки про разрешения. */
    private fun reminderSoundSection(box: LinearLayout, close: () -> Unit) {
        val dp = resources.displayMetrics.density
        fun row(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; setTextColor(getColor(R.color.pa_text))
            minHeight = (54 * dp).toInt(); gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        box.addView(TextView(this).apply {
            text = "Напоминания"; textSize = 15f; setTextColor(getColor(R.color.pa_sub)); setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
        })
        lateinit var tone: TextView
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Звук напоминаний"; textSize = 18f; isChecked = ReminderSound.enabled(this@MainActivity)
            minHeight = (56 * dp).toInt(); setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, v ->
                ReminderSound.setEnabled(this@MainActivity, v); tone.isEnabled = v; tone.alpha = if (v) 1f else 0.5f
                if (v) ensureReminderPermissions()
                toast(if (v) "Напоминания будут звенеть (до минуты, пока не нажмёте «Готово»)" else "Напоминания — без звука, только вибрация")
            }
        }
        box.addView(sw, LinearLayout.LayoutParams(-1, -2))
        tone = row("Мелодия: ${ReminderSound.label(this)}") { chooseReminderTone { tone.text = "Мелодия: ${ReminderSound.label(this)}" } }
        tone.isEnabled = sw.isChecked; tone.alpha = if (sw.isChecked) 1f else 0.5f
        row("▶ Проверить звук") {
            ensureReminderPermissions()
            try { ReminderSoundService.test(this); toast("Звучит 6 секунд — так прозвенит напоминание") } catch (t: Throwable) { toast("Не удалось включить звук: ${t.message}") }
        }
        if (!AndroidReminders.notificationsAllowed(this)) row("⚠ Уведомления выключены — включить") { close(); ensureReminderPermissions(force = true) }
        if (!ReminderNotifier.canFullScreen(this) && Build.VERSION.SDK_INT >= 34) row("⚠ Разрешить показ напоминаний на весь экран") {
            close()
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { toast("Откройте: Настройки → Приложения → PocketAgent → Уведомления") }
        }
    }

    private fun ensureReminderPermissions(force: Boolean = false) {
        if (AndroidReminders.notificationsAllowed(this)) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !force) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF); return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Уведомления выключены")
            .setMessage("Без уведомлений напоминание не появится на экране. Включите уведомления для PocketAgent.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
                catch (_: Exception) { toast("Откройте: Настройки → Приложения → PocketAgent → Уведомления") }
            }.setNegativeButton("Позже", null).show()
    }

    private var onTonePicked: (() -> Unit)? = null

    private fun chooseReminderTone(done: () -> Unit) {
        val items = arrayOf("Звук будильника (по умолчанию)", "Звук уведомления", "Выбрать мелодию…")
        val cur = when (ReminderSound.kind(this)) { "notification" -> 1; "custom" -> 2; else -> 0 }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Мелодия напоминаний")
            .setSingleChoiceItems(items, cur) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> { ReminderSound.set(this, "alarm"); done() }
                    1 -> { ReminderSound.set(this, "notification"); done() }
                    else -> {
                        onTonePicked = done
                        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_NOTIFICATION)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия напоминаний")
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ReminderSound.uri(this))
                        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_TONE) } catch (_: Exception) { toast("На телефоне нет выбора мелодий") }
                    }
                }
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showPlusMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menuInflater.inflate(R.menu.plus, pm.menu)
        pm.setOnMenuItemClickListener { onOptionsItemSelected(it) }
        pm.show()
    }

    // ---------- меню

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.m_agent).isChecked = agent.agentMode
        menu.findItem(R.id.m_tts).isChecked = Prefs.tts(this)
        menu.findItem(R.id.m_tts_always).isChecked = Prefs.ttsAlways(this)
        menu.findItem(R.id.m_web).isChecked = Prefs.web(this)
        menu.findItem(R.id.m_debug).isChecked = Prefs.debug(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_live -> enterLive()
            R.id.m_alarms -> showAlarms()
            R.id.m_time -> submit("Который час?")
            R.id.m_settings -> showSettings()
            R.id.m_file -> pickFile()
            R.id.m_mirror -> chooseNoteMirror()
            R.id.m_export -> exportNotes()
            R.id.m_update -> updates.checkNow()
            R.id.m_upd_auto -> { val v = !Updater.autoCheck(this); Updater.setAutoCheck(this, v); item.isChecked = v }
            R.id.m_upd_wifi -> { val v = !Updater.wifiOnly(this); Updater.setWifiOnly(this, v); item.isChecked = v }
            R.id.m_guide -> startActivity(Intent(this, GuideActivity::class.java))
            R.id.m_memory -> startActivity(Intent(this, MemoryActivity::class.java))
            R.id.m_lists -> startActivity(Intent(this, ListsActivity::class.java))
            R.id.m_tts -> {
                val v = !Prefs.tts(this); Prefs.setTts(this, v); item.isChecked = v
                if (!v) speaker.stop()
                if (v && !speaker.ready && speaker.status != Speaker.Status.INIT) showTtsHint()
            }
            R.id.m_tts_always -> {
                val v = !Prefs.ttsAlways(this); Prefs.setTtsAlways(this, v); item.isChecked = v
                if (!v) speaker.stop()
                if (v && !speaker.ready && speaker.status != Speaker.Status.INIT) showTtsHint()
            }
            R.id.m_web -> {
                val v = !Prefs.web(this); Prefs.setWeb(this, v); item.isChecked = v
                toast(if (v) "Поиск в интернете включён: вопросы вроде «кто такой…», «погода», «курс доллара» будут проверяться в сети" else "Поиск выключен — всё только на телефоне")
                updateStatusChip()
            }
            R.id.m_debug -> { val v = !Prefs.debug(this); Prefs.setDebug(this, v); item.isChecked = v; adapter.notifyDataSetChanged() }
            R.id.m_font -> {
                val labels = arrayOf("Обычный", "Крупный", "Очень крупный", "Огромный")
                val vals = floatArrayOf(1.0f, 1.15f, 1.35f, 1.6f)
                val cur = vals.indexOfFirst { kotlin.math.abs(it - Prefs.fontScale(this)) < 0.01f }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Размер текста").setSingleChoiceItems(labels, cur) { d, w ->
                    Prefs.setFontScale(this, vals[w]); applyFontScale(); adapter.notifyDataSetChanged(); d.dismiss()
                }.show()
            }
            R.id.m_theme -> {
                val labels = arrayOf("Тёмная (по умолчанию)", "Светлая", "Как в системе")
                val vals = intArrayOf(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Тема").setSingleChoiceItems(labels, vals.indexOf(Prefs.theme(this))) { d, w ->
                    d.dismiss(); Prefs.setTheme(this, vals[w]); recreate()
                }.show()
            }
            R.id.m_assistant -> startActivity(Intent(this, GuideActivity::class.java))
            R.id.m_assistant_old -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Помощник по умолчанию")
                .setMessage("Тогда жест или кнопка помощника сразу откроют живой режим PocketAgent.\n\n" +
                        "Samsung: Настройки → Приложения → Выбор приложений по умолчанию → Цифровой помощник → Приложение цифрового помощника → PocketAgent.\n" +
                        "Боковая кнопка Samsung: Настройки → Дополнительные функции → Боковая клавиша → Двойное нажатие → Открыть приложение → PocketAgent.\n" +
                        "Другие телефоны: Настройки → Приложения → Приложения по умолчанию → Цифровой помощник.\n\n" +
                        "«Окей, Google» PocketAgent не слушает — он запускается жестом/кнопкой.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                    catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) } catch (_: Exception) {} }
                }.setNegativeButton("Позже", null).show()
            R.id.m_agent -> {
                agent.agentMode = !agent.agentMode; item.isChecked = agent.agentMode
                getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("agent", agent.agentMode).apply()
                updateSubtitle()
            }
            R.id.m_clear -> {
                if (busy) return true
                agent.history.clear(); agent.pending = null; val n = items.size; items.clear(); adapter.notifyItemRangeRemoved(0, n); updateHero()
            }
            R.id.m_import -> {
                if (busy) return true
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMPORT)
            }
            R.id.m_models -> chooseModel()
            R.id.m_legal -> {
                val text = try { resources.openRawResource(R.raw.licenses).bufferedReader().readText() } catch (t: Throwable) { t.toString() }
                val tv = TextView(this).apply { this.text = text; textSize = 15f; setTextIsSelectable(true); setPadding(40, 20, 40, 20) }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Приватность и лицензии")
                    .setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("OK", null).show()
            }
            R.id.m_builtin -> {
                if (busy) return true
                models.useImported = false; Session.loadedPath = null; prepareModel()
            }
            R.id.m_about -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("PocketAgent ${BuildConfig.VERSION_NAME}" + if (BuildConfig.LITE) " Lite" else "")
                .setMessage("Офлайн-помощник на llama.cpp.\nАктивная модель: ${models.activeName()}\n" +
                        "Голосовой ввод: ${voice.mode()}  ·  Озвучка: ${speaker.status}\n" +
                        "Точные будильники: ${if (AndroidReminders.canExact(this)) "да" else "нет"}  ·  Уведомления: ${if (AndroidReminders.notificationsAllowed(this)) "да" else "нет"}\n\n" +
                        CrashReporter.deviceInfo() + "CPU (llama.cpp): " + (try { LlamaBridge.nativeSystemInfo() } catch (t: Throwable) { t.toString() }))
                .setPositiveButton("OK", null)
                .setNeutralButton("Лог движка") { _, _ ->
                    val log = CrashReporter.deviceInfo() + "\n" + (try { LlamaBridge.nativeRecentLog() } catch (t: Throwable) { t.toString() })
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PocketAgent log", log))
                    toast("Лог скопирован в буфер обмена")
                }.show()
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH_LIVE || requestCode == REQ_SPEECH_CHAT) {
            val t = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (requestCode == REQ_SPEECH_LIVE) { if (!t.isNullOrBlank()) liveListener.onResult(t) else liveListener.onFail(7, true, "тишина") }
            else if (!t.isNullOrBlank()) { input.setText(t); onSend(fromVoice = true) }
            return
        }
        if (requestCode == REQ_TONE) {
            if (resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION") val u = data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (u != null) ReminderSound.set(this, "custom", u.toString()) else ReminderSound.set(this, "alarm")
                onTonePicked?.invoke(); toast("Мелодия: ${ReminderSound.label(this)}")
            }
            onTonePicked = null; return
        }
        if (requestCode == REQ_FILE) { if (resultCode == Activity.RESULT_OK) data?.data?.let { attach(it, null) }; return }
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        var name = "model.gguf"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        modelReady = false; updateSubtitle()
        worker.execute {
            try {
                LlamaBridge.nativeUnload(); Session.loadedPath = null
                models.import(uri, name) { done, total ->
                    setLoading("Импорт $name… ${done / 1_000_000} МБ" + if (total > 0) " / ${total / 1_000_000} МБ" else "",
                        if (total > 0) done.toFloat() / total else -1f)
                }
                loadActive()
                ui.post { addItem(ChatItem.Kind.SYSTEM, "Загружена модель: $name") }
            } catch (e: Exception) {
                ui.post { toast("Импорт не удался: ${e.message}"); models.useImported = false; prepareModel() }
            }
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onPause() {
        super.onPause()
        if (chatListening) { voice.stop(); chatListening = false; setMicActive(false) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (liveOn) exitLive()
        netCallback?.let { try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        voice.stop(); speaker.shutdown()
        if (isFinishing) { Session.loadedPath = null; worker.execute { LlamaBridge.nativeUnload() } }
    }
}

/** Поиск в интернете для агента: включается в меню, проверяет наличие сети. */
class AppSearcher(private val ctx: android.content.Context) : Agent.Searcher {
    override fun enabled() = Prefs.web(ctx)
    override fun online(): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) { false }
    override fun search(q: String) = WebSearch.search(q)
    override fun lookup(q: String) = WebSearch.lookup(q)
    @Volatile private var ratesCache: Pair<Long, Map<String, Double>>? = null
    override fun rates(): Map<String, Double>? {
        ratesCache?.let { (t, r) -> if (System.currentTimeMillis() - t < 3_600_000L) return r }
        return WebSearch.cbrRates().also { ratesCache = System.currentTimeMillis() to it }
    }
}
