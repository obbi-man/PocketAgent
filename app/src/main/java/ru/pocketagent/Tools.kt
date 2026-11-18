package ru.pocketagent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

data class ToolCall(val name: String, val args: JSONObject) {
    fun pretty(): String = "$name(${args})"
}

/** Действие, которое UI должен выполнить после инструмента (интенты нужны в главном потоке). */
sealed class UiAction {
    data class OpenUrl(val url: String) : UiAction()
    data class Timer(val seconds: Int, val label: String) : UiAction()
    data class Alarm(val hour: Int, val minute: Int, val daysMask: Int, val label: String, val atMs: Long) : UiAction()
    data class ShowAlarms(val off: Boolean) : UiAction()
    /** stopRinging — id сработавшего напоминания, у которого надо убрать звук и уведомление («отложи на 10 минут»). */
    data class ReminderSet(val reminder: Reminder, val exact: Boolean, val stopRinging: Int = 0) : UiAction()
    /** Несколько подходящих напоминаний — спросить, какое перенести/изменить (args — что сделать). */
    data class ChooseEdit(val candidates: List<Reminder>, val args: String) : UiAction()
    /** Перенос будильника в «Часах»: новый ставим, старый (если знаем время) выключаем. */
    data class MoveAlarm(val oldHour: Int?, val oldMinute: Int?, val hour: Int, val minute: Int, val daysMask: Int, val atMs: Long) : UiAction()
    object OpenLists : UiAction()
    /** Остановить таймер в «Часах» (ACTION_DISMISS_TIMER, резерв — список таймеров). */
    data class CancelTimer(val seconds: Int) : UiAction()
    /** Выключить будильник в «Часах»: по времени, подписи, все или ближайший. */
    data class CancelAlarm(val hour: Int?, val minute: Int?, val label: String, val all: Boolean) : UiAction()
    /** Несколько подходящих напоминаний — спросить, какое удалить. */
    data class ChooseReminder(val candidates: List<Reminder>) : UiAction()
}

/** Дружелюбная карточка вместо «сырого» JSON. */
data class Card(val icon: String, val title: String, val body: String = "")

data class ToolResult(val ok: Boolean, val forModel: String, val answer: String, val action: UiAction? = null, val card: Card? = null)

/** Напоминание. daysMask: Пн=бит0 … Вс=бит6; 0 — однократное. alarm — звучит как будильник (резерв без «Часов»). */
/** sound: 0 — как в настройках («Звук напоминаний»), 1 — со звуком («напомни со звуком…»), 2 — без звука. */
data class Reminder(val id: Int, val atMs: Long, val hour: Int, val minute: Int, val daysMask: Int, val text: String, val alarm: Boolean = false, val sound: Int = 0) {
    val repeating get() = daysMask != 0
    fun localAt(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault())
}

interface RemindersStore {
    /** Сохраняет и планирует; возвращает (напоминание, точный ли будильник). */
    fun add(atMs: Long, hour: Int, minute: Int, daysMask: Int, text: String, alarm: Boolean = false, sound: Int = 0): Pair<Reminder, Boolean>
    fun all(): List<Reminder>
    fun remove(id: Int)
    /** 2.6: заменить запись (новое время/текст): старый будильник отменяется, новый планируется. @return точный ли. */
    fun replace(r: Reminder): Boolean
    /** Последнее сработавшее однократное напоминание (для «отложи на 10 минут»), не старше withinMs. */
    fun lastFired(nowMs: Long, withinMs: Long = 2 * 3600_000L): Reminder? = null
    /** Забыть копию сработавшего (после «отложи»). */
    fun dropFired(id: Int) {}
}

/** Хранилище в памяти — для тестов и если Android-хранилище недоступно. */
class MemoryReminders : RemindersStore {
    val list = mutableListOf<Reminder>()
    override fun add(atMs: Long, hour: Int, minute: Int, daysMask: Int, text: String, alarm: Boolean, sound: Int): Pair<Reminder, Boolean> {
        val r = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, atMs, hour, minute, daysMask, text, alarm, sound); list += r; return r to true
    }
    override fun all() = list.toList()
    override fun remove(id: Int) { list.removeAll { it.id == id } }
    override fun replace(r: Reminder): Boolean { val i = list.indexOfFirst { it.id == r.id }; if (i >= 0) list[i] = r else list += r; return true }
    /** Для тестов: сработавшие (id → (напоминание, когда сработало)). */
    val fired = mutableMapOf<Int, Pair<Reminder, Long>>()
    override fun lastFired(nowMs: Long, withinMs: Long) = fired.values.filter { nowMs - it.second in 0..withinMs }.maxByOrNull { it.second }?.first
    override fun dropFired(id: Int) { fired.remove(id) }
}

interface NotesStore {
    fun all(): List<String>
    fun add(text: String)
    fun clear()
}

object Tools {
    /** Описание инструментов в JSON-формате (как в chat_template модели, Qwen-style). */
    val schemas: List<JSONObject> by lazy {
        fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name).put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props)
                    .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }))
        fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
        listOf(
            fn("get_datetime", "Get the current local date, time and weekday. Use only when the user asks about time or date."),
            fn("calculator", "Evaluate an arithmetic expression with + - * / ^ % ( ) sqrt. Example: (2+3)*4",
                JSONObject().put("expression", str("math expression")), listOf("expression")),
            fn("save_note", "Save a short note when the user asks to remember or write something down.",
                JSONObject().put("text", str("note text")), listOf("text")),
            fn("read_notes", "Read all saved notes."),
            fn("clear_notes", "Delete all saved notes (only if the user explicitly asks)."),
            fn("set_timer", "Start a countdown timer.",
                JSONObject().put("seconds", int("duration in seconds")).put("label", str("optional label")), listOf("seconds")),
            fn("open_url", "Open a web page in the phone browser.",
                JSONObject().put("url", str("http(s) URL or domain")), listOf("url")),
            fn("set_reminder", "Remind the user about something at a given time (\"напомни завтра в 8 выпить таблетки\").",
                JSONObject().put("text", str("what to remind about")).put("when", str("time in user's words")), listOf("text", "when")),
            fn("list_reminders", "Show the user's reminders."),
            fn("set_alarm", "Set a wake-up alarm in the Clock app (\"разбуди меня в 7\").",
                JSONObject().put("time", str("HH:MM")), listOf("time")),
            fn("show_alarms", "Show alarms in the Clock app."),
            fn("cancel_timer", "Stop/cancel a running timer."),
            fn("cancel_alarm", "Turn off/delete an alarm.", JSONObject().put("time", str("HH:MM, optional"))),
            fn("cancel_reminder", "Delete a reminder.", JSONObject().put("query", str("words from the reminder text, optional"))),
        )
    }
    val names: Set<String> get() = schemas.map { it.getJSONObject("function").getString("name") }.toSet()

    private val ruDate = Locale("ru")

    fun normalizeUrl(raw: String): String? {
        var u = raw.trim().trim('"', '\'', '«', '»', '.', ',', '!', '?', ')', '(')
        if (u.isEmpty() || u.contains(' ')) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (u.contains("://")) return null                 // только http/https
            if (!Regex("^[\\p{L}\\d-]+(\\.[\\p{L}\\d-]+)+(/.*)?$").matches(u)) return null
            u = "https://$u"
        }
        return u
    }

    /**
     * Проверка вызова от модели: известное имя, правильные аргументы и
     * правдоподобность относительно реального сообщения пользователя
     * (маленькая модель часто вызывает инструменты «на всякий случай»).
     */
    fun validate(call: ToolCall, userText: String): ToolCall? {
        val t = userText.lowercase()
        fun has(vararg keys: String) = keys.any { t.contains(it) }
        val cancelCmd = Router.isCancelCommand(t)
        // «удали таймер…» никогда не создаёт таймер (и наоборот, отмена — только по явной команде)
        if (cancelCmd && call.name in setOf("set_timer", "set_alarm", "set_reminder", "save_note")) return null
        // «перенеси напоминалку на 8» никогда не создаёт новое (2.6)
        if (Router.isEditCommand(t) && call.name in setOf("set_timer", "set_alarm", "set_reminder", "save_note")) return null
        if (call.name == "edit_reminder" || call.name == "move_alarm") return (Router.route(userText) as? Router.Route.Call)?.call?.takeIf { it.name == call.name }
        if (call.name.startsWith("cancel_")) return (Router.route(userText) as? Router.Route.Call)?.call?.takeIf { it.name == call.name }
        return when (call.name) {
            "get_datetime" -> if (Regex("(?iu)(котор[\\p{L}\\p{N}_]*\\s+(сейчас\\s+)?час|сколько\\s+(сейчас\\s+)?врем|текущ[\\p{L}\\p{N}_]*\\s+врем|врем[\\p{L}\\p{N}_]*\\s+сейчас|сейчас\\s+врем|точное\\s+время|дат[аыуе](?![\\p{L}\\p{N}_])|какое\\s+(сегодня\\s+)?числ|какой\\s+(сегодня\\s+)?день|день\\s+недели|time\\s+is\\s+it|the\\s+time|current\\s+time|date|what\\s+day|today)").containsMatchIn(t)) ToolCall(call.name, JSONObject()) else null
            "calculator" -> {
                val e = call.args.optString("expression", "")
                if (e.isBlank() || !e.any { it.isDigit() }) null
                else try { Calculator.eval(e); ToolCall(call.name, JSONObject().put("expression", e)) } catch (_: Exception) { null }
            }
            "save_note" -> {
                val txt = call.args.optString("text", "").trim()
                if (isPlausibleNote(txt, userText)) ToolCall(call.name, JSONObject().put("text", txt)) else null
            }
            // время берём только из правил: модель путает часы и даты
            "set_reminder", "set_alarm", "list_reminders", "show_alarms" ->
                (Router.route(userText) as? Router.Route.Call)?.call?.takeIf { it.name == call.name }
            "read_notes" -> if (has("заметк", "запомина", "запомнил", "note")) ToolCall(call.name, JSONObject()) else null
            "clear_notes" -> if (has("удал", "очист", "стер", "сотр", "delete", "clear", "remove") && has("заметк", "note")) ToolCall(call.name, JSONObject()) else null
            "set_timer" -> {
                // модель часто путает единицы («3 минуты» → seconds=3): берём длительность из текста пользователя, если она там есть
                val s = Router.parseDuration(userText) ?: call.args.optInt("seconds", 0)
                if (s in 1..86400 && has("таймер", "засек", "timer", "через", "минут", "секунд", "minute", "second")) ToolCall(call.name, JSONObject().put("seconds", s).put("label", call.args.optString("label", "")))
                else null
            }
            "open_url" -> {
                val u = normalizeUrl(call.args.optString("url", ""))
                if (u != null && has("откр", "сайт", "ссылк", "зайди", "перейди", "open", "http", "www", ".ru", ".com", ".org", ".net")) ToolCall(call.name, JSONObject().put("url", u)) else null
            }
            else -> null
        }
    }

    private val placeholderRe = Regex("(?iu)(пример|текст\\s+(для\\s+)?заметк|ваш[\\p{L}]*\\s+текст|какой-то\\s+текст|содержимое\\s+заметк|^заметка\\s*\\d*$|^текст$|example|sample|lorem|placeholder|note\\s+text|your\\s+text|\\.\\.\\.|<.*>)")
    private val saveVerbRe = Regex("(?iu)(запиш|запомн|сохран|напомн|заметк[\\p{L}]*\\s*[:—-]|добав[\\p{L}]*\\s+(в\\s+)?заметк|remember|note\\s+that|save\\s+(a\\s+)?note|write\\s+down|take\\s+a\\s+note)")

    private fun stems(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase().replace('ё', 'е')).map { it.value }
        .filter { it.length >= 3 || it.any(Char::isDigit) }.map { it.take(5) }.toList()

    /**
     * Модель иногда «сохраняет» выдуманный текст («Пример текста для заметки») на вопрос «что там с заметками».
     * Принимаем save_note, только если: (1) в сообщении есть глагол сохранения, (2) это не вопрос о заметках,
     * (3) текст не заглушка, (4) слова заметки действительно есть в сообщении (≥60% по основам).
     */
    fun isPlausibleNote(note: String, userText: String): Boolean {
        if (note.isBlank() || note.length > 500) return false
        if (!saveVerbRe.containsMatchIn(userText)) return false
        if (Router.isReadNotes(userText)) return false
        if (placeholderRe.containsMatchIn(note) && !placeholderRe.containsMatchIn(userText)) return false
        val ns = stems(note); if (ns.isEmpty()) return false
        val us = stems(userText).toSet()
        val present = ns.count { it in us }
        return present.toDouble() / ns.size >= 0.6
    }

    fun execute(call: ToolCall, notes: NotesStore, now: Date = Date(), reminders: RemindersStore? = null): ToolResult { return try {
        when (call.name) {
            "get_datetime" -> {
                val d = SimpleDateFormat("d MMMM yyyy, EEEE", ruDate).format(now)
                val tm = SimpleDateFormat("HH:mm", ruDate).format(now)
                val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)
                ToolResult(true, JSONObject().put("datetime", iso).put("timezone", TimeZone.getDefault().id).toString(),
                    "Сейчас $tm, $d.", card = Card("🕐", "Сейчас $tm", d))
            }
            "calculator" -> {
                val e = call.args.getString("expression")
                val v = Calculator.format(Calculator.eval(e))
                ToolResult(true, JSONObject().put("result", v).toString(), "${e.trim()} = $v", card = Card("🧮", "= $v", e.trim()))
            }
            "save_note" -> {
                val txt = call.args.getString("text").trim()
                notes.add(txt)
                ToolResult(true, "{\"ok\": true}", "Записал: «$txt». Всего заметок: ${notes.all().size}.",
                    null, Card("📝", "Заметка сохранена", txt))
            }
            "read_notes" -> {
                val all = notes.all()
                if (all.isEmpty()) ToolResult(true, "[]", "Заметок пока нет. Скажите, например: «Запиши: купить молоко».",
                    card = Card("📝", "Заметок пока нет", "Скажите: «Запиши: купить молоко»"))
                else {
                    val list = all.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                    ToolResult(true, JSONArray(all).toString(), "Ваши заметки:\n$list", card = Card("📝", "Заметки: ${all.size}", list))
                }
            }
            "clear_notes" -> { notes.clear(); ToolResult(true, "{\"ok\": true}", "Все заметки удалены.", card = Card("🗑", "Все заметки удалены")) }
            "set_timer" -> {
                val s = call.args.getInt("seconds")
                val label = call.args.optString("label", "").ifBlank { "Таймер PocketAgent" }
                ToolResult(true, "{\"ok\": true, \"seconds\": $s}", "Ставлю таймер на ${humanDuration(s)}.", UiAction.Timer(s, label),
                    Card("⏱", "Таймер на ${humanDuration(s)}", call.args.optString("label", "")))
            }
            "open_url" -> {
                val u = normalizeUrl(call.args.getString("url")) ?: throw IllegalArgumentException("плохой адрес")
                ToolResult(true, "{\"ok\": true}", "Открываю $u", UiAction.OpenUrl(u), Card("🌐", "Открыть сайт?", u))
            }
            "set_reminder" -> {
                val store = reminders ?: throw IllegalStateException("напоминания недоступны")
                val text = call.args.optString("text", "Напоминание").ifBlank { "Напоминание" }
                if (Router.isCommandOnly(text)) return ToolResult(false, "{\"error\": \"command words as text\"}",
                    "Похоже, вы хотите изменить существующее напоминание. Скажите, например: «перенеси напоминание на 8 вечера» или «удали напоминание».",
                    UiAction.OpenLists, Card("🔔", "Новое напоминание не создано", "«$text» — это команда, а не текст напоминания"))
                val mask = call.args.optInt("days", 0)
                val snd = call.args.optInt("sound", 0).coerceIn(0, 2)
                val (r, exact) = store.add(call.args.getLong("at_ms"), call.args.getInt("hour"), call.args.getInt("minute"), mask, text, sound = snd)
                val nowL = LocalDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault())
                val whenTxt = TimeParser.human(r.localAt(), nowL, TimeParser.fromMask(mask))
                ToolResult(true, JSONObject().put("ok", true).put("id", r.id).toString(),
                    "Хорошо, напомню $whenTxt${when (snd) { 1 -> " со звуком"; 2 -> " без звука"; else -> "" }}: «$text».", UiAction.ReminderSet(r, exact),
                    Card(if (snd == 2) "🔕" else "🔔", "Напоминание: $whenTxt", text + (when (snd) { 1 -> "\n🔊 Со звуком"; 2 -> "\n🔕 Без звука"; else -> "" }) +
                        if (!exact) "\n(время может сдвинуться на несколько минут — нет разрешения на точные будильники)" else ""))
            }
            "list_reminders" -> {
                val all = reminders?.all().orEmpty().sortedBy { it.atMs }
                val nowL = LocalDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault())
                if (all.isEmpty()) ToolResult(true, "[]", "Напоминаний нет. Скажите, например: «Напомни завтра в 9 позвонить врачу».",
                    UiAction.OpenLists, Card("🔔", "Напоминаний нет", "Скажите: «Напомни завтра в 9 позвонить врачу»"))
                else {
                    val list = all.joinToString("\n") { "• " + TimeParser.human(it.localAt(), nowL, TimeParser.fromMask(it.daysMask)) + " — " + it.text }
                    ToolResult(true, "{\"count\": ${all.size}}", "Ваши напоминания:\n$list", UiAction.OpenLists, Card("🔔", "Напоминания: ${all.size}", list))
                }
            }
            "set_alarm" -> {
                val h = call.args.getInt("hour"); val m = call.args.getInt("minute"); val mask = call.args.optInt("days", 0)
                val label = call.args.optString("label", "")
                val atMs = call.args.optLong("at_ms", 0)
                val nowL = LocalDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault())
                val whenTxt = if (mask != 0) TimeParser.daysText(TimeParser.fromMask(mask)) + " в %d:%02d".format(h, m)
                    else if (atMs > 0) TimeParser.human(LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault()), nowL) else "на %d:%02d".format(h, m)
                ToolResult(true, JSONObject().put("ok", true).toString(), "Ставлю будильник $whenTxt.",
                    UiAction.Alarm(h, m, mask, label, atMs), Card("⏰", "Будильник %d:%02d".format(h, m), whenTxt + if (label.isNotBlank()) "\n$label" else ""))
            }
            "show_alarms" -> {
                val off = call.args.optBoolean("off", false)
                ToolResult(true, "{\"ok\": true}", if (off) "Открываю будильники — выключить нужный можно там переключателем." else "Открываю список будильников.",
                    UiAction.ShowAlarms(off), Card("⏰", "Будильники", "Открываю приложение «Часы»"))
            }
            "cancel_timer" -> {
                // свои резервные таймеры (без «Часов») удаляем сами
                val own = reminders?.all().orEmpty().filter { it.alarm && it.text.startsWith("⏱") && it.atMs > now.time }
                own.forEach { reminders?.remove(it.id) }
                val s = call.args.optInt("seconds", 0)
                ToolResult(true, "{\"ok\": true}", "Останавливаю таймер" + (if (s > 0) " на ${humanDuration(s)}" else "") + ".",
                    UiAction.CancelTimer(s), Card("⏱", "Таймер остановлен", if (own.isNotEmpty()) "Удалено таймеров PocketAgent: ${own.size}" else "Команда передана в «Часы»"))
            }
            "cancel_alarm" -> {
                val h = if (call.args.has("hour")) call.args.getInt("hour") else null
                val m = if (call.args.has("minute")) call.args.getInt("minute") else null
                val all = call.args.optBoolean("all", false)
                val label = call.args.optString("query", "")
                // свои резервные будильники (дальше суток или без «Часов»)
                val own = reminders?.all().orEmpty().filter { it.alarm && !it.text.startsWith("⏱") && (all || h == null || (it.hour == h && it.minute == (m ?: 0))) }
                own.forEach { reminders?.remove(it.id) }
                val what = when { all -> "все будильники"; h != null -> "будильник на %d:%02d".format(h, m ?: 0); label.isNotBlank() -> "будильник «$label»"; else -> "ближайший будильник" }
                ToolResult(true, "{\"ok\": true}", "Выключаю $what.", UiAction.CancelAlarm(h, m, label, all), Card("⏰", "Выключить $what", "Команда передана в «Часы»"))
            }
            "cancel_reminder" -> {
                val store = reminders ?: throw IllegalStateException("напоминания недоступны")
                val nowL = LocalDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault())
                fun human(r: Reminder) = TimeParser.human(r.localAt(), nowL, TimeParser.fromMask(r.daysMask)) + " — " + r.text
                val list = store.all().filter { !it.alarm }.sortedBy { it.atMs }
                val among = call.args.optString("among", "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                val ids = call.args.optString("ids", "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                val matched = when {
                    call.args.has("id") -> list.filter { it.id == call.args.getInt("id") }
                    ids.isNotEmpty() -> list.filter { it.id in ids }
                    // «отмени напоминалку» без уточнений — то, о котором только что говорили в чате
                    call.args.optInt("recent", 0).let { rid -> rid > 0 && !call.args.optBoolean("all", false) && !call.args.has("hour") &&
                        call.args.optString("query", "").isBlank() && list.any { it.id == rid } } -> list.filter { it.id == call.args.getInt("recent") }
                    else -> matchReminders(list.filter { among.isEmpty() || it.id in among }, call.args)
                }
                when {
                    list.isEmpty() -> ToolResult(true, "[]", "Напоминаний нет — удалять нечего.", card = Card("🔔", "Напоминаний нет"))
                    matched.isEmpty() -> ToolResult(false, "[]", "Не нашёл такого напоминания. Ваши напоминания:\n" + list.joinToString("\n") { "• " + human(it) },
                        UiAction.OpenLists, Card("🔔", "Не нашёл такого напоминания", list.joinToString("\n") { "• " + human(it) }))
                    matched.size == 1 || call.args.optBoolean("all", false) || ids.isNotEmpty() -> {
                        matched.forEach { store.remove(it.id) }
                        val body = matched.joinToString("\n") { "• " + human(it) }
                        ToolResult(true, "{\"removed\": ${matched.size}}", if (matched.size == 1) "Удалил напоминание: ${human(matched[0])}." else "Удалил напоминаний: ${matched.size}.",
                            card = Card("🗑", if (matched.size == 1) "Напоминание удалено" else "Удалено напоминаний: ${matched.size}", body))
                    }
                    else -> {
                        val body = matched.mapIndexed { i, r -> "${i + 1}. " + human(r) }.joinToString("\n")
                        ToolResult(true, "{\"candidates\": ${matched.size}}", "Нашёл несколько напоминаний:\n$body\nКакое удалить? Скажите номер, например «первое», или «все».",
                            UiAction.ChooseReminder(matched), Card("🔔", "Какое напоминание удалить?", body))
                    }
                }
            }
            "edit_reminder" -> editReminder(call, reminders ?: throw IllegalStateException("напоминания недоступны"), now)
            "move_alarm" -> moveAlarm(call, reminders, now)
            else -> ToolResult(false, "{\"error\": \"unknown tool\"}", "Не знаю такого инструмента: ${call.name}")
        }
    } catch (e: Exception) {
        ToolResult(false, JSONObject().put("error", e.message ?: "error").toString(), "Не получилось (${call.name}): ${e.message}")
    } }

    /**
     * 2.6: перенос/изменение напоминания. Цель: id → по словам/старому времени → сработавшее (для «отложи») →
     * последнее в этом чате → единственное; иначе — выбор.
     */
    private fun editReminder(call: ToolCall, store: RemindersStore, now: Date): ToolResult {
        val a = call.args
        val nowMs = now.time
        val nowL = LocalDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault())
        fun human(r: Reminder) = TimeParser.human(r.localAt(), nowL, TimeParser.fromMask(r.daysMask))
        val list = store.all().filter { !it.alarm }.sortedBy { it.atMs }
        val mode = a.optString("mode", "")
        val newText = a.optString("new_text", "").trim()
        val among = a.optString("among", "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        val pool = list.filter { among.isEmpty() || it.id in among }
        val crit = JSONObject().put("query", a.optString("query", ""))
        if (a.has("old_hour")) crit.put("hour", a.getInt("old_hour")).put("minute", a.optInt("old_minute", 0))
        val hasCrit = crit.optString("query").isNotBlank() || crit.has("hour")
        val recent = a.optInt("recent", 0)
        val fired = if (mode == "snooze" && !a.has("id") && !hasCrit) store.lastFired(nowMs) else null
        val cands: List<Reminder> = when {
            a.has("id") -> list.filter { it.id == a.getInt("id") }
            hasCrit -> matchReminders(pool, crit)
            fired != null -> listOf(fired)
            recent > 0 && pool.any { it.id == recent } -> pool.filter { it.id == recent }
            else -> pool
        }
        val verb = if (newText.isNotEmpty()) "изменить" else if (mode == "snooze") "отложить" else "перенести"
        if (list.isEmpty() && fired == null) return ToolResult(false, "[]", "Напоминаний нет — менять нечего. Новое можно поставить так: «Напомни сегодня в 20:00 …».",
            UiAction.OpenLists, Card("🔔", "Напоминаний нет", "Скажите: «Напомни сегодня в 20:00 позвонить маме»"))
        if (cands.isEmpty()) {
            val q = a.optString("query", "")
            val body = list.joinToString("\n") { "• " + human(it) + " — " + it.text }
            return ToolResult(false, "[]", "Не нашёл напоминание" + (if (q.isNotBlank()) " «$q»" else "") + ". Ваши напоминания:\n$body",
                UiAction.OpenLists, Card("🔔", "Не нашёл такого напоминания", body))
        }
        if (cands.size > 1) {
            val body = cands.mapIndexed { i, r -> "${i + 1}. " + human(r) + " — " + r.text }.joinToString("\n")
            val op = JSONObject(a.toString()).also { it.remove("query"); it.remove("among"); it.remove("soft"); it.remove("old_hour"); it.remove("old_minute") }
            return ToolResult(true, "{\"candidates\": ${cands.size}}", "Нашёл несколько напоминаний:\n$body\nКакое $verb? Скажите номер, например «первое».",
                UiAction.ChooseEdit(cands, op.toString()), Card("🔔", "Какое напоминание $verb?", body))
        }
        val r = cands[0]
        val isFired = fired != null && r.id == fired.id

        // --- новый текст
        if (newText.isNotEmpty()) {
            if (Router.isCommandOnly(newText)) return ToolResult(false, "{}", "Какой текст поставить? Например: «измени текст напоминания на купить хлеб».")
            val nr = r.copy(text = newText)
            val exact = if (isFired) store.add(r.atMs, r.hour, r.minute, r.daysMask, newText, r.alarm, r.sound).second else store.replace(nr)
            return ToolResult(true, JSONObject().put("ok", true).put("id", nr.id).toString(), "Изменил текст напоминания ${human(nr)}: «$newText».",
                UiAction.ReminderSet(nr, exact), Card("✏️", "Текст напоминания изменён: ${human(nr)}", "$newText\n(было: ${r.text})"))
        }

        // --- новое время
        var mask = r.daysMask
        val newAt: LocalDateTime = when (mode) {
            "at" -> {
                val h = a.getInt("hour"); val m = a.getInt("minute"); val d = a.optInt("days", 0)
                when {
                    d != 0 -> { mask = d; TimeParser.nextOccurrence(nowL, h, m, TimeParser.fromMask(d)) }
                    r.repeating -> TimeParser.nextOccurrence(nowL, h, m, TimeParser.fromMask(r.daysMask))
                    else -> LocalDateTime.ofInstant(Instant.ofEpochMilli(a.getLong("at_ms")), ZoneId.systemDefault())
                }
            }
            "day" -> {
                val d = a.optInt("days", 0)
                if (d != 0) { mask = d; TimeParser.nextOccurrence(nowL, r.hour, r.minute, TimeParser.fromMask(d)) }
                else { mask = 0; java.time.LocalDate.parse(a.getString("date")).atTime(r.hour, r.minute) }
            }
            "shift" -> {
                val sec = a.optLong("seconds", 3600)
                if (r.repeating) { val t = java.time.LocalTime.of(r.hour, r.minute).plusSeconds(sec); TimeParser.nextOccurrence(nowL, t.hour, t.minute, TimeParser.fromMask(r.daysMask)) }
                else r.localAt().plusSeconds(sec)
            }
            "snooze" -> {
                val sec = a.optLong("seconds", 600)
                mask = 0
                (if (isFired || r.atMs <= nowMs || r.repeating) nowL else r.localAt()).plusSeconds(sec).withNano(0)
            }
            else -> return ToolResult(false, "{}", "На какое время перенести? Например: «перенеси напоминание на 8 вечера».")
        }.withNano(0)
        val newMs = Router.millis(newAt)
        if (newMs <= nowMs) return ToolResult(false, "{\"error\": \"past\"}", "Это время уже прошло (${TimeParser.human(newAt, nowL)}). Скажите другое время, например «на 8 вечера» или «на завтра».")
        val added = isFired || (mode == "snooze" && r.repeating)
        val (nr, exact) = if (added) store.add(newMs, newAt.hour, newAt.minute, mask, r.text, r.alarm, r.sound).also { if (isFired) store.dropFired(r.id) }
            else r.copy(atMs = newMs, hour = newAt.hour, minute = newAt.minute, daysMask = mask).let { it to store.replace(it) }
        val whenTxt = TimeParser.human(nr.localAt(), nowL, TimeParser.fromMask(mask))
        val snoozed = mode == "snooze"
        val title = if (snoozed) "Напоминание отложено: $whenTxt" else "Напоминание перенесено: $whenTxt"
        val answer = (if (snoozed) "Отложил — напомню $whenTxt" else "Перенёс напоминание на $whenTxt") + ": «${r.text}»."
        return ToolResult(true, JSONObject().put("ok", true).put("id", nr.id).toString(), answer,
            UiAction.ReminderSet(nr, exact, if (isFired) r.id else 0),
            Card("🔔", title, r.text + (if (!added && r.atMs != nr.atMs) "\n(было: ${human(r)})" else "") +
                (if (!exact) "\n(время может сдвинуться на несколько минут — нет разрешения на точные будильники)" else "")))
    }

    /** 2.6: «перенеси будильник на 7». Свои резервные будильники меняем сами, в «Часах» — новый ставим, старый выключаем. */
    private fun moveAlarm(call: ToolCall, reminders: RemindersStore?, now: Date): ToolResult {
        val a = call.args
        val h = a.getInt("hour"); val m = a.getInt("minute"); val mask = a.optInt("days", 0)
        val nowL = LocalDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault())
        val oldH = if (a.has("old_hour")) a.getInt("old_hour") else null
        val oldM = if (a.has("old_hour")) a.optInt("old_minute", 0) else null
        val atMs = a.optLong("at_ms", 0)
        val newTxt = "%d:%02d".format(h, m)
        val oldTxt = oldH?.let { "%d:%02d".format(it, oldM ?: 0) }
        val own = reminders?.all().orEmpty().filter { it.alarm && !it.text.startsWith("⏱") }
        val mine = own.filter { oldH == null || (it.hour == oldH && it.minute == (oldM ?: 0)) }
        if (reminders != null && mine.size == 1) {
            val r = mine[0]
            val at = if (mask != 0 || r.repeating) TimeParser.nextOccurrence(nowL, h, m, TimeParser.fromMask(if (mask != 0) mask else r.daysMask))
                else if (atMs > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault()) else TimeParser.nextOccurrence(nowL, h, m, emptySet())
            val nr = r.copy(atMs = Router.millis(at), hour = h, minute = m, daysMask = if (mask != 0) mask else r.daysMask)
            reminders.replace(nr)
            return ToolResult(true, "{\"ok\": true}", "Перенёс будильник на $newTxt.", null,
                Card("⏰", "Будильник перенесён на $newTxt", "Было: %d:%02d".format(r.hour, r.minute)))
        }
        val body = if (oldTxt != null) "Новый на $newTxt ставлю, старый на $oldTxt прошу «Часы» выключить. Если он остался (например, на $oldTxt их несколько) — выключите его переключателем: кнопка «Будильники»"
            else "Новый на $newTxt ставлю в «Часах». Старый выключите в списке будильников — я не знаю, на какое время он был"
        return ToolResult(true, "{\"ok\": true}", "Переношу будильник" + (if (oldTxt != null) " с $oldTxt" else "") + " на $newTxt.",
            UiAction.MoveAlarm(oldH, oldM, h, m, mask, atMs), Card("⏰", "Будильник перенесён на $newTxt", body))
    }

    /** Подбор напоминаний по времени и словам из текста. Без условий — все. */
    fun matchReminders(list: List<Reminder>, args: JSONObject): List<Reminder> {
        if (args.optBoolean("all", false)) return list
        var r = list
        if (args.has("hour")) {
            val h = args.getInt("hour"); val m = args.optInt("minute", 0)
            r = r.filter { it.hour == h && it.minute == m }.ifEmpty { r.filter { it.hour == h } }
        }
        val keys = DocIndex.keywords(args.optString("query", ""))
        if (keys.isNotEmpty()) {
            val byText = r.filter { rem -> val low = rem.text.lowercase().replace('ё', 'е'); keys.any { low.contains(it) } }
            if (byText.isNotEmpty() || !args.has("hour")) r = byText
        }
        return r
    }

    fun humanDuration(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h ${plural(h, "час", "часа", "часов")}"
        if (m > 0) parts += "$m ${plural(m, "минуту", "минуты", "минут")}"
        if (s > 0) parts += "$s ${plural(s, "секунду", "секунды", "секунд")}"
        return parts.joinToString(" ")
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = n % 100; val b = n % 10
        return if (a in 11..14) many else when (b) { 1 -> one; 2, 3, 4 -> few; else -> many }
    }
}

/** Достаёт вызов инструмента из ответа модели: <tool_call>{...}</tool_call> или «голый» JSON. */
object ToolCallParser {
    fun parse(output: String): ToolCall? {
        val tagged = Regex("<tool_call>(.*?)(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL).find(output)?.groupValues?.get(1)
        val candidates = listOfNotNull(tagged, output)
        for (c in candidates) {
            val json = firstJsonObject(c) ?: continue
            try {
                val o = JSONObject(json)
                val name = o.optString("name", "").ifEmpty { o.optJSONObject("function")?.optString("name") ?: "" }
                if (name !in Tools.names) continue
                val argsAny = o.opt("arguments") ?: o.opt("parameters")
                val args = when (argsAny) {
                    is JSONObject -> argsAny
                    is String -> try { JSONObject(argsAny) } catch (_: Exception) { JSONObject() }
                    else -> JSONObject()
                }
                return ToolCall(name, args)
            } catch (_: Exception) { }
        }
        return null
    }

    fun firstJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val ch = s[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }
}
