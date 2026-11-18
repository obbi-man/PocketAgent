package ru.pocketagent

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Быстрый детерминированный маршрутизатор команд (русский + английский).
 * Модель 0.3B обучена в основном на китайском/английском и нестабильно выбирает
 * инструменты, поэтому очевидные команды распознаются правилами, а модель
 * вызывается как планировщик только для «пограничных» случаев.
 */
object Router {
    // ВАЖНО: на Android regex — ICU: флаг (?U) (UNICODE_CHARACTER_CLASS) там НЕ поддерживается и
    // бросает PatternSyntaxException (это и был краш на «привет»). Поэтому только (?iu) и без \\w и \\b:
    // вместо них явные классы букв, одинаково работающие на JVM и Android.
    private const val F = "(?iu)"

    private val numberWords = mapOf(
        "одну" to 1, "одна" to 1, "один" to 1, "две" to 2, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "пятнадцать" to 15,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )

    /** Длительность в секундах из фразы: «5 минут», «1 час 30 минут», «полчаса», «90 сек», «2m». */
    fun parseDuration(text: String): Int? {
        var t = " " + text.lowercase() + " "
        for ((w, n) in numberWords) t = t.replace(Regex("$F(?<=\\s)$w(?=\\s)"), n.toString())
        var total = 0.0; var found = false
        if (Regex("$F полчаса").containsMatchIn(t)) { total += 1800; found = true }
        if (Regex("$F полторы минуты").containsMatchIn(t)) { total += 90; found = true }
        val re = Regex("$F(\\d+(?:[.,]\\d+)?)\\s*(час[\\p{L}\\p{N}_]*|ч(?![\\p{L}\\p{N}_])|h(?![\\p{L}\\p{N}_])|hours?|hrs?|минут[\\p{L}\\p{N}_]*|мин[\\p{L}\\p{N}_]*|м(?![\\p{L}\\p{N}_])|m(?![\\p{L}\\p{N}_])|minutes?|mins?|секунд[\\p{L}\\p{N}_]*|сек[\\p{L}\\p{N}_]*|с(?![\\p{L}\\p{N}_])|s(?![\\p{L}\\p{N}_])|seconds?|secs?)")
        for (m in re.findAll(t)) {
            val n = m.groupValues[1].replace(',', '.').toDouble()
            val u = m.groupValues[2]
            total += when {
                u.startsWith("ч") || u.startsWith("h") -> n * 3600
                u.startsWith("м") || u.startsWith("m") -> n * 60
                else -> n
            }
            found = true
        }
        if (!found) {
            when {
                Regex("$F(?<![\\p{L}\\p{N}_])(минуту|минутку|minute)(?![\\p{L}\\p{N}_])").containsMatchIn(t) -> { total = 60.0; found = true }
                Regex("$F(?<![\\p{L}\\p{N}_])(час|hour)(?![\\p{L}\\p{N}_])").containsMatchIn(t) -> { total = 3600.0; found = true }
            }
        }
        return if (found && total >= 1) total.toInt() else null
    }

    private val siteAliases = mapOf(
        "ютуб" to "youtube.com", "youtube" to "youtube.com", "гугл" to "google.com", "google" to "google.com",
        "яндекс" to "ya.ru", "yandex" to "ya.ru", "вк" to "vk.com", "вконтакте" to "vk.com", "vk" to "vk.com",
        "википедию" to "ru.wikipedia.org", "википедия" to "ru.wikipedia.org", "wikipedia" to "wikipedia.org",
        "github" to "github.com", "гитхаб" to "github.com", "хабр" to "habr.com", "telegram" to "web.telegram.org",
        "телеграм" to "web.telegram.org", "почту" to "mail.ru", "кинопоиск" to "kinopoisk.ru", "авито" to "avito.ru"
    )

    /** Незавершённая команда: ждём время («Когда напомнить?» → «завтра в 10»). */
    data class Pending(val kind: String, val text: String, val args: String = "")

    sealed class Route {
        data class Call(val call: ToolCall) : Route()
        data class Ask(val question: String, val pending: Pending? = null) : Route()   // не хватает данных — уточнить
    }

    private const val W = "[\\p{L}\\p{N}_]"

    /** Вопрос о заметках («что там с заметками», «мои заметки», «что я записал», «заметки?»). */
    fun isReadNotes(t0: String): Boolean {
        val t = t0.lowercase().replace('ё', 'е').trim()
        return Regex("$F(покажи|прочитай|прочти|открой|выведи|список|show|read|list|перечисли|назови|зачитай)\\s+(мне\\s+)?(мои\\s+|my\\s+|все\\s+|свои\\s+)*(заметк|notes)").containsMatchIn(t) ||
            Regex("$F(мои\\s+заметки|что\\s+(у\\s+меня\\s+)?в\\s+заметках|что\\s+я\\s+(просил|просила)\\s+(тебя\\s+)?запомнить|что\\s+ты\\s+запомнил|my\\s+notes)").containsMatchIn(t) ||
            Regex("$F(что|как)\\s+(там\\s+|у\\s+нас\\s+|у\\s+меня\\s+)?(с|со|по|насчет)\\s+(моими\\s+|нашими\\s+)?заметк").containsMatchIn(t) ||
            Regex("$F^(какие|мои|все|есть|есть\\s+ли|сколько)\\s+(у\\s+меня\\s+)?(какие-нибудь\\s+|какие-то\\s+)?(мои\\s+)?заметк$W*").containsMatchIn(t) ||
            Regex("$F(что|чего)\\s+я\\s+(там\\s+)?(записал|записала|записывал|записывала|сохранил|сохранила|сохранял|запоминал|просил\\s+записать)").containsMatchIn(t) ||
            Regex("$F^(заметки|заметка|мои\\s+записи|записи)\\s*[?!.]*$").containsMatchIn(t)
    }

    private val reminderCmd = Regex("$F^\\s*(?:пожалуйста[,\\s]+)?(?:(?:поставь|создай|сделай|добавь|установи)\\s+)?(?:мне\\s+)?(?:напоминани[\\p{L}]*|напоминалк[\\p{L}]*|напомни(?:те)?|напоминай(?:те)?|запиши(?:те)?(?:\\s+в\\s+заметки)?|запомни|заметк[\\p{L}]*|сохрани(?:\\s+заметку)?)(?:\\s+мне)?(?:\\s+пожалуйста)?[,:\\s—-]*(?:о\\s+том[,]?\\s*)?(?:что(?:бы)?\\s+|про\\s+|об?\\s+(?=[\\p{L}]{3}))?")

    /** Текст напоминания без команды и времени. */
    fun reminderText(rest: String): String =
        rest.replace(reminderCmd, "").replace(Regex("$F^(мне|меня|пожалуйста|что|чтобы)\\s+"), "").trim().trim(',', '.', ':', '-', '—', ' ')
            .replaceFirstChar { it.uppercase() }

    // ---------- отмена / удаление (проверяется ДО команд создания)

    private val CANCEL_RE = Regex("$F(?<![\\p{L}])(удали|удалить|удалите|отмени|отменить|отмените|отмена|выключи|выключить|выключите|отключи|отключить|останови|остановить|остановите|стоп|сбрось|сбросить|убери|убрать|сотри|стереть|хватит|прекрати|cancel|stop|delete|remove|dismiss|turn\\s+off)(?![\\p{L}])")
    private val NEG_RE = Regex("$F(?<![\\p{L}])не\\s+(надо|нужно|нужен|нужна|ставь|ставить|заводи|буди|будить|напоминай|напоминать|засекай|включай|don't|делай)(?![\\p{L}])|(?<![\\p{L}])(don't|do\\s+not)\\s+(set|remind|wake)")

    /** Есть глагол отмены/удаления или отрицание — команды создания не срабатывают. */
    fun hasCancel(t: String) = CANCEL_RE.containsMatchIn(t) || NEG_RE.containsMatchIn(t)

    private val TARGETS = listOf(
        "timer" to Regex("$F(таймер|засек|timer)"),
        "alarm" to Regex("$F(будильник|разбуди|(?<![\\p{L}])буди(?![\\p{L}])|будить|побудк|alarm|wake)"),
        "reminder" to Regex("$F(напоминани|напоминалк|напоминай|напоминать|remind)"),
    )

    /** Это команда отмены (а не, например, «напомни удалить фото»)? Глагол отмены должен стоять перед целью. */
    fun isCancelCommand(t: String) = cancelTarget(t.lowercase()) != null

    private val CREATE_FIRST = Regex("$F^\\s*(пожалуйста[,\\s]+)?(запиши|запомни|напомни|сохрани|добавь|заметка|remind|note)")

    private fun cancelTarget(t: String): Pair<String, Boolean>? {
        if (CREATE_FIRST.containsMatchIn(t)) return null   // «запиши: выключить таймер на плите» — это содержание
        val verb = CANCEL_RE.find(t)
        val neg = NEG_RE.find(t)
        for ((name, re) in TARGETS) {
            val m = re.find(t) ?: continue
            if (neg != null && neg.range.first <= m.range.first + 2) return name to true
            val noun = m.value.startsWith("таймер") || m.value.startsWith("будильник") || m.value.startsWith("напоминани") || m.value.startsWith("напоминалк") || m.value == "timer" || m.value == "alarm"
            if (verb != null && (verb.range.first < m.range.first || noun &&
                    // «таймер выключи», «будильник на 7 отмени»
                    Regex("$F^\\s*(мой\\s+|этот\\s+)?${m.value}\\p{L}*(\\s+\\S+){0,3}?\\s+${verb.value}").containsMatchIn(t))) return name to false
        }
        return null
    }

    private fun cancelRoute(text: String, t: String, now: LocalDateTime, recent: String? = null): Route? {
        val (target, negOnly) = cancelTarget(t) ?: run {
            // «удали его», «отмени это» — про то, о чём только что говорили
            if (recent != null && recent != "timer" && CANCEL_RE.containsMatchIn(t) && PRONOUN.containsMatchIn(t) && !CREATE_FIRST.containsMatchIn(t) &&
                t.replace(CANCEL_RE, " ").replace(PRONOUN, " ").replace(FILLER, " ").replace(Regex("[^\\p{L}\\d]"), " ").isBlank())
                return Route.Call(ToolCall("cancel_$recent", JSONObject().put("all", false).put("last", true)))
            return null
        }
        val cancel = !negOnly
        val neg = negOnly
        val all = Regex("$F(?<![\\p{L}])(все|всё|всех|all)(?![\\p{L}])").containsMatchIn(t)
        if (neg && !cancel) return Route.Ask(when (target) { "timer" -> "Хорошо, таймер не ставлю."; "alarm" -> "Хорошо, будильник не ставлю."; else -> "Хорошо, напоминать не буду." })
        val w = try { TimeParser.parse(text, now, preferMorning = target == "alarm") } catch (_: Throwable) { null }
        val timed = w != null && w.hasExplicitTime && !w.relative
        val args = JSONObject().put("all", all)
        if (timed) args.put("hour", w!!.at.hour).put("minute", w.at.minute)
        if (target == "timer") parseDuration(t)?.let { args.put("seconds", it) }
        val query = (w?.rest ?: text).lowercase()
            .replace(CANCEL_RE, " ")
            .replace(Regex("$F(?<![\\p{L}])(мой|мои|моё|мое|мою|этот|эту|это|тот|который|которое|которая|были?|было|был|ставил[\\p{L}]*|поставил[\\p{L}]*|все|всё|всех|пожалуйста|мне|меня|на|в|во|про|о|об|с|что|чтобы|таймер[\\p{L}]*|будильник[\\p{L}]*|напоминани[\\p{L}]*|напоминалк[\\p{L}]*|напомни[\\p{L}]*|сегодня|завтра|утра|вечера|дня|ночи|минут[\\p{L}]*|час[\\p{L}]*|секунд[\\p{L}]*|\\d+)(?![\\p{L}])"), " ")
            .replace(Regex("[^\\p{L}\\d ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (query.isNotEmpty()) args.put("query", query)
        return Route.Call(ToolCall("cancel_$target", args))
    }

    // ---------- 2.6: перенос / изменение напоминаний и будильников (проверяется ДО создания)

    private val EDIT_VERB = Regex("$F(?<![\\p{L}])(перенеси|перенесите|перенести|перенес|сдвинь|сдвиньте|сдвинуть|подвинь|подвиньте|передвинь|передвиньте|передвинуть|переставь|переставьте|переставить|отложи|отложите|отложить|поменяй|поменяйте|поменять|измени|измените|изменить|исправь|исправьте|исправить|поправь|поправьте|перепиши|перепишите|переименуй|переименуйте|замени|замените|перенос)(?![\\p{L}])")
    private val SNOOZE_VERB = Regex("$F(?<![\\p{L}])(отложи|отложите|отложить)(?![\\p{L}])")
    private val MOVE_VERB = Regex("$F(?<![\\p{L}])(перенеси|перенесите|перенести|сдвинь|сдвиньте|сдвинуть|подвинь|подвиньте|передвинь|передвиньте|передвинуть|переставь|переставьте|отложи|отложите|отложить)(?![\\p{L}])")
    private val REM_NOUN = Regex("$F(?<![\\p{L}])(напоминани\\p{L}*|напоминалк\\p{L}*)")
    private val ALARM_NOUN = Regex("$F(?<![\\p{L}])(будильник\\p{L}*|будилк\\p{L}*)")
    private val PRONOUN = Regex("$F(?<![\\p{L}])(его|её|ее|него|неё|нее|это|эту|этот|её|ее|последнее|последнюю|последний)(?![\\p{L}])")
    private val TEXT_MARK = Regex("$F(?<![\\p{L}])(текст\\p{L}*|формулировк\\p{L}*|название|переименуй\\p{L}*|назови|подпись)(?![\\p{L}])")
    private val LATER = Regex("$F(?<![\\p{L}])(позже|попозже|вперед|вперёд|дальше)(?![\\p{L}])")
    private val EARLIER = Regex("$F(?<![\\p{L}])(раньше|пораньше|назад)(?![\\p{L}])")
    /** Слова команд — не могут быть текстом напоминания («Перенеси напоминалку» — это не напоминание). */
    private val COMMAND_WORDS = Regex("$F(?<![\\p{L}])(перенеси\\p{L}*|перенести|перенес\\p{L}*|сдвинь\\p{L}*|сдвинуть|подвинь\\p{L}*|передвинь\\p{L}*|передвинуть|переставь\\p{L}*|отложи\\p{L}*|поменяй\\p{L}*|поменять|измени\\p{L}*|изменить|исправь\\p{L}*|поправь\\p{L}*|перепиши\\p{L}*|переименуй\\p{L}*|замени\\p{L}*|удали\\p{L}*|отмени\\p{L}*|отмена|убери\\p{L}*|сотри|выключи\\p{L}*|время|время|напоминани\\p{L}*|напоминалк\\p{L}*|будильник\\p{L}*|его|её|ее|это|эту|этот|мое|моё|мою|мой|пожалуйста|мне|на|в|во|с|со|можешь|давай|ну|плиз|время|срок)(?![\\p{L}])")

    /** Текст состоит только из слов команды (перенеси/удали/измени + напоминалку/его)? */
    fun isCommandOnly(text: String): Boolean {
        val t = text.lowercase()
        if (!Regex("$F(перенес|сдвин|подвин|передвин|переставь|отлож|поменя|измен|исправ|поправ|перепиш|переименуй|замени|удали|отмени|отмена|убери|сотри|выключи)").containsMatchIn(t)) return false
        return t.replace(COMMAND_WORDS, " ").replace(Regex("[^\\p{L}\\d]"), " ").isBlank()
    }

    fun isEditCommand(t: String) = !CREATE_FIRST.containsMatchIn(t) && EDIT_VERB.containsMatchIn(t) &&
        (REM_NOUN.containsMatchIn(t) || ALARM_NOUN.containsMatchIn(t) || isCommandOnly(TimeParser.parse(t, LocalDateTime.now())?.rest ?: t))

    private val FILLER = Regex("$F(?<![\\p{L}])(мое|моё|мою|мой|мои|это|эту|этот|его|её|ее|него|неё|нее|тот|ту|которое|которую|который|про|о|об|пожалуйста|мне|меня|можешь|давай|ну|плиз|время|срок|последнее|последнюю|последний|на|в|во|с|со|к|до|у|сегодня|завтра|утра|вечера|дня|ночи|позже|попозже|раньше|пораньше|вперед|вперёд|назад|дальше|минут\\p{L}*|час\\p{L}*|секунд\\p{L}*|\\d+)(?![\\p{L}])")

    private fun leftover(s: String) = s.lowercase().replace(EDIT_VERB, " ").replace(REM_NOUN, " ").replace(ALARM_NOUN, " ")
        .replace(Regex("$F(?<![\\p{L}])(напомни\\p{L}*)(?![\\p{L}])"), " ")
        .replace(FILLER, " ").replace(Regex("[^\\p{L}\\d ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * «Перенеси напоминалку на 8 вечера», «сдвинь его на час позже», «отложи на 10 минут»,
     * «измени текст напоминания на купить хлеб», «перенеси будильник на 7».
     * recent — о чём шла речь в чате («reminder»/«alarm»), для «перенеси его».
     */
    private fun editRoute(text: String, t: String, now: LocalDateTime, recent: String?, force: Boolean = false): Route? {
        if (CREATE_FIRST.containsMatchIn(t)) return null    // «напомни перенести встречу» — это содержание
        val verb = EDIT_VERB.find(t)
        if (verb == null && !force) return null
        val alarmN = ALARM_NOUN.find(t); val remN = REM_NOUN.find(t)
        val pron = PRONOUN.containsMatchIn(t)
        val snooze = SNOOZE_VERB.containsMatchIn(t)
        // старое время: «с 18:00 на 20:00», «напоминание на 18:00 перенеси на 20»
        var body = text
        var oldH: Int? = null; var oldM = 0
        Regex("$F(?<![\\p{L}])(?:с|со)\\s+(\\d{1,2})(?:[:.](\\d{2}))?(?=\\s+(?:на|в)\\s+)").find(body)?.let {
            oldH = it.groupValues[1].toInt() % 24; oldM = it.groupValues[2].toIntOrNull() ?: 0
            body = body.removeRange(it.range)
        }
        val target = when {
            alarmN != null && (remN == null || alarmN.range.first < remN.range.first) -> "alarm"
            remN != null -> "reminder"
            else -> null
        }
        val lo = leftover(body.let { b -> TimeParser.parse(b, now, preferMorning = target == "alarm")?.rest ?: b })
        val tgt = target ?: when {
            // без существительного: «перенеси его на 8», «отложи на 10 минут», «сдвинь на час»
            (pron || snooze || force) && lo.isEmpty() && (force || parseDuration(body) != null ||
                TimeParser.parse(body, now)?.let { it.hasExplicitTime || it.relative || it.strong } == true ||
                (recent != null && MOVE_VERB.containsMatchIn(t))) -> recent ?: "reminder"
            // «перенеси встречу с Ваней на 8» — ищем напоминание по словам
            MOVE_VERB.containsMatchIn(t) && lo.isNotEmpty() && (parseDuration(body) != null || TimeParser.parse(body, now)?.let { it.hasExplicitTime || it.relative || it.strong } == true) -> "reminder_soft"
            else -> return null
        }
        if (tgt == "timer") return null
        val args = JSONObject()
        if (oldH != null) args.put("old_hour", oldH).put("old_minute", oldM)

        // --- изменить текст
        if (tgt != "alarm" && TEXT_MARK.containsMatchIn(t)) {
            val m = Regex("$F(?<![\\p{L}])(?:на|в)\\s+[«\"“']?(.+?)[»\"”']?\\s*[.!]*$").find(body.substring((TEXT_MARK.find(body.lowercase())?.range?.last ?: 0)))
            val newText = m?.groupValues?.get(1)?.trim()?.replaceFirstChar { it.uppercase() }
            if (newText.isNullOrBlank()) return Route.Ask("На какой текст изменить напоминание? Например: «измени текст напоминания на купить хлеб».", Pending("edit_text", "", args.toString()))
            val before = body.lowercase().substring(0, body.lowercase().indexOf(m!!.value.lowercase()).coerceAtLeast(0))
            leftover(before.replace(TEXT_MARK, " ")).takeIf { it.isNotEmpty() }?.let { args.put("query", it) }
            return Route.Call(ToolCall("edit_reminder", args.put("new_text", newText)))
        }

        // --- новое время
        val w = try { TimeParser.parse(body, now, preferMorning = tgt == "alarm") } catch (_: Throwable) { null }
        val dur = parseDuration(body)
        val sign = if (EARLIER.containsMatchIn(t)) -1 else 1
        when {
            snooze && (w == null || w.relative || !w.hasExplicitTime) -> {
                val sec = if (w != null && w.relative) java.time.Duration.between(now, w.at).seconds.toInt() else dur ?: 600
                args.put("mode", "snooze").put("seconds", sec)
            }
            w != null && w.relative -> args.put("mode", "at").put("at_ms", millis(w.at)).put("hour", w.at.hour).put("minute", w.at.minute)
            w != null && w.hasExplicitTime -> args.put("mode", "at").put("at_ms", millis(w.at)).put("hour", w.at.hour).put("minute", w.at.minute).put("days", TimeParser.toMask(w.days))
            dur != null -> args.put("mode", "shift").put("seconds", sign * dur)
            w != null && (w.strong || w.days.isNotEmpty()) -> args.put("mode", "day").put("date", w.at.toLocalDate().toString()).put("days", TimeParser.toMask(w.days))
            LATER.containsMatchIn(t) || EARLIER.containsMatchIn(t) -> args.put("mode", "shift").put("seconds", sign * 3600)
            else -> {
                lo.takeIf { it.isNotEmpty() && tgt != "alarm" }?.let { args.put("query", it) }
                return if (tgt == "alarm") Route.Ask("На какое время перенести будильник? Например: «на 7:30».", Pending("move_alarm", "", args.toString()))
                else Route.Ask("На какое время перенести напоминание? Например: «на 8 вечера», «на завтра», «на час позже».", Pending("edit_reminder_time", "", args.toString()))
            }
        }
        if (tgt == "alarm") {
            if (w == null || !(w.hasExplicitTime || w.relative)) return Route.Ask("На какое время перенести будильник? Например: «на 7:30».", Pending("move_alarm", "", args.toString()))
            args.put("mode", "at")
            return Route.Call(ToolCall("move_alarm", args))
        }
        if (lo.isNotEmpty()) args.put("query", lo)
        if (tgt == "reminder_soft") args.put("soft", true)
        return Route.Call(ToolCall("edit_reminder", args))
    }

    fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun reminderCall(w: TimeParser.When, text: String) = Route.Call(ToolCall("set_reminder", JSONObject()
        .put("text", text.ifBlank { "Напоминание" }).put("at", w.at.toString()).put("at_ms", millis(w.at))
        .put("hour", w.at.hour).put("minute", w.at.minute).put("days", TimeParser.toMask(w.days))))

    private fun alarmCall(w: TimeParser.When, label: String) = Route.Call(ToolCall("set_alarm", JSONObject()
        .put("hour", w.at.hour).put("minute", w.at.minute).put("days", TimeParser.toMask(w.days))
        .put("label", label).put("at", w.at.toString()).put("at_ms", millis(w.at))))

    /** Слова команд с отложенным действием (для страховки от «модель сказала, но не сделала»). */
    val ACTION_WORDS = Regex("$F(напомин|напомни|будильник|разбуди|таймер|засеки|remind|alarm|timer)")

    /**
     * Мягкое повторное распознавание: приводим разговорные формы к каноническим
     * («напоминалку» → «напоминание», «будилку» → «будильник») и прогоняем правила ещё раз. Только вызовы.
     */
    fun relaxed(raw: String, now: LocalDateTime = LocalDateTime.now()): Route.Call? {
        val t = raw.lowercase()
            .replace(Regex("$F(?<![\\p{L}])напоминалк\\p{L}*"), "напоминание")
            .replace(Regex("$F(?<![\\p{L}])будилк\\p{L}*"), "будильник")
            .replace(Regex("$F(?<![\\p{L}])(можешь|могли\\s+бы|можно|пожалуйста|ну|давай|плиз)(?![\\p{L}])"), " ")
            .replace(Regex("\\s+"), " ").trim()
        (route(t, now) as? Route.Call)?.let { return it }
        val prefix = when {
            Regex("$F(напомин|напомни|remind)").containsMatchIn(t) -> "напомни "
            Regex("$F(будильник|разбуди|alarm)").containsMatchIn(t) -> "разбуди меня "
            Regex("$F(таймер|засеки|timer)").containsMatchIn(t) -> "таймер "
            else -> return null
        }
        val body = t.replace(Regex("$F(?<![\\p{L}])(поставь|поставить|создай|сделай|установи|заведи|напоминание|напомни|будильник|таймер)(?![\\p{L}])"), " ").replace(Regex("\\s+"), " ").trim()
        return route(prefix + body, now) as? Route.Call
    }

    /** «первое», «2», «второе» → номер. */
    fun ordinal(raw: String): Int? {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')
        Regex("^(?:номер\\s+)?(\\d{1,2})(?:-?(?:е|ое|й|ой|ю|ую))?(?:\\s+\\p{L}+)?$").find(t)?.let { return it.groupValues[1].toInt() }
        val words = listOf("перв", "втор", "трет", "четв", "пят", "шест", "седьм", "восьм")
        val i = words.indexOfFirst { Regex("$F(?<![\\p{L}])$it").containsMatchIn(t) }
        if (i >= 0) return i + 1
        if (Regex("$F(?<![\\p{L}])последн").containsMatchIn(t)) return -1
        return null
    }

    /** Ответ на уточнение («Когда напомнить?» → «завтра в 10»). */
    fun followUp(p: Pending, raw: String, now: LocalDateTime = LocalDateTime.now()): Route? {
        when (p.kind) {
            // «Какое перенести?» → «второе» / «1»
            "edit_reminder" -> {
                val ids = p.text.split(',').mapNotNull { it.trim().toIntOrNull() }
                val n = ordinal(raw)?.let { if (it == -1) ids.size else it }
                val base = JSONObject(p.args.ifBlank { "{}" })
                if (n != null && n in 1..ids.size) return Route.Call(ToolCall("edit_reminder", base.put("id", ids[n - 1]).also { it.remove("query"); it.remove("soft") }))
                val q = leftover(raw)
                if (q.isNotEmpty()) return Route.Call(ToolCall("edit_reminder", base.put("query", q).put("among", ids.joinToString(","))))
                return null
            }
            // «На какое время перенести?» → «на 8 вечера»
            "edit_reminder_time", "move_alarm", "edit_text" -> {
                val base = JSONObject(p.args.ifBlank { "{}" })
                val prefix = when (p.kind) { "move_alarm" -> "перенеси будильник "; "edit_text" -> "измени текст напоминания на "; else -> "перенеси напоминание " }
                val r = editRoute(prefix + raw, (prefix + raw).lowercase(), now, null) as? Route.Call ?: return null
                for (k in base.keys()) if (!r.call.args.has(k)) r.call.args.put(k, base.get(k))
                return r
            }
        }
        if (p.kind == "cancel_reminder") {
            val ids = p.text.split(',').mapNotNull { it.trim().toIntOrNull() }
            val n = ordinal(raw)?.let { if (it == -1) ids.size else it }
            if (n != null && n in 1..ids.size) return Route.Call(ToolCall("cancel_reminder", JSONObject().put("id", ids[n - 1])))
            if (Regex("$F(все|всё|оба|обе|all)").containsMatchIn(raw)) return Route.Call(ToolCall("cancel_reminder", JSONObject().put("ids", ids.joinToString(","))))
            val r = route("удали напоминание $raw", now)
            return (r as? Route.Call)?.let { c -> Route.Call(ToolCall("cancel_reminder", c.call.args.put("among", ids.joinToString(",")))) }
        }
        val w = TimeParser.parse(raw, now, preferMorning = p.kind == "alarm") ?: return null
        if (!w.hasExplicitTime && !w.relative && w.days.isEmpty() && w.rest.isNotBlank()) return null
        return when (p.kind) {
            "alarm" -> alarmCall(w, p.text)
            else -> reminderCall(w, p.text.ifBlank { reminderText(w.rest) })
        }
    }

    /** «со звуком» / «без звука» в напоминании: 1 — со звуком, 2 — без, 0 — не сказано. */
    private val SOUND_ON = Regex("$F(?<![\\p{L}])(со\\s+звук(ом|овым\\s+(сигналом|уведомлением|оповещением))|с\\s+сигналом|громко|погромче|с\\s+мелодией|чтобы\\s+(было\\s+)?(слышно|звенел[ао]?|прозвенел[ао]?))(?![\\p{L}])")
    private val SOUND_OFF = Regex("$F(?<![\\p{L}])(без\\s+звука|беззвучно|тихо|тихое|без\\s+сигнала)(?![\\p{L}])")

    fun soundMod(t: String): Int = when { SOUND_OFF.containsMatchIn(t) -> 2; SOUND_ON.containsMatchIn(t) -> 1; else -> 0 }

    fun route(raw: String, now: LocalDateTime = LocalDateTime.now(), recent: String? = null): Route? {
        val snd = soundMod(raw)
        if (snd == 0) return route0(raw, now, recent)
        // убираем «со звуком» из текста, чтобы он не попал в напоминание; если это не напоминание — как было
        val stripped = raw.replace(if (snd == 1) SOUND_ON else SOUND_OFF, " ").replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?])"), "$1").trim()
        val r = route0(stripped, now, recent)
        val c = (r as? Route.Call)?.call
        if (c != null && c.name == "set_reminder") { c.args.put("sound", snd); return r }
        return route0(raw, now, recent)
    }

    private fun route0(raw: String, now: LocalDateTime, recent: String? = null): Route? {
        val text = raw.trim()
        val t = text.lowercase()

        // --- 0) отмена/удаление таймера, будильника, напоминания — раньше любых команд создания
        cancelRoute(text, t, now, recent)?.let { return it }
        // --- 0а) перенос/изменение («перенеси напоминалку на 8 вечера») — тоже раньше создания
        editRoute(text, t, now, recent)?.let { return it }
        val noCreate = NEG_RE.containsMatchIn(t)   // «не надо…»: ничего не создаём

        // --- будильники: показать
        if (Regex("$F((покажи|открой|какие|мои|список|все)\\s+(у\\s+меня\\s+)?(мои\\s+)?будильник|будильники\\s*[?]*$)").containsMatchIn(t))
            return Route.Call(ToolCall("show_alarms", JSONObject().put("off", false)))

        // --- будильник
        if (!noCreate && Regex("$F(будильник|разбуди|подними\\s+меня|побудк|wake\\s+me)").containsMatchIn(t)) {
            val w = TimeParser.parse(text, now, preferMorning = true)
            if (w == null || (!w.hasExplicitTime && !w.relative))
                return Route.Ask("На какое время поставить будильник? Например: «Разбуди меня в 7:30» или «будильник по будням в 7».", Pending("alarm", ""))
            val label = w.rest.replace(Regex("$F(поставь|поставить|установи|заведи|включи|сделай|создай|пожалуйста|разбуди|подними|меня|мне|будильник[\\p{L}]*|на|в|wake|me|up)(?![\\p{L}])"), " ")
                .replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', '-', '—')
            return alarmCall(w, label.replaceFirstChar { it.uppercase() })
        }

        // --- напоминания: список / удалить
        if (Regex("$F((покажи|какие|мои|список|все|открой|есть\\s+ли)\\s+(у\\s+меня\\s+)?(мои\\s+)?(напоминани|напоминалк)|(напоминания|напоминалки)\\s*[?]*$)").containsMatchIn(t))
            return Route.Call(ToolCall("list_reminders", JSONObject()))

        // --- заметки: прочитать (раньше «запиши» и раньше модели — иначе модель «сохраняет» выдуманную заметку)
        if (isReadNotes(t)) return Route.Call(ToolCall("read_notes", JSONObject()))

        // --- напоминание («напомни…», или «запиши на 23:00 …» с явным временем)
        val isRemind = Regex("$F(напомни|напоминай|напоминани|напоминалк|remind)").containsMatchIn(t)
        val isNoteVerb = Regex("$F^\\s*(пожалуйста[,\\s]+)?(запиши|запомни|сохрани|заметка|добавь)").containsMatchIn(t)
        if (!noCreate && (isRemind || isNoteVerb)) {
            val w = TimeParser.parse(text, now)
            val usable = w != null && (w.hasExplicitTime || w.relative || w.days.isNotEmpty() || w.strong)
            if (w != null && usable && (isRemind || w.strong)) {
                val what = reminderText(w.rest)
                // страховка: «Перенеси напоминалку»/«Удали» не может быть текстом нового напоминания
                if (isCommandOnly(what)) return editRoute(text, t, now, recent, force = true)
                    ?: Route.Ask("Изменить существующее напоминание? Скажите, например: «перенеси напоминание на 8 вечера» или «удали напоминание».")
                return reminderCall(w, what)
            }
            if (isRemind && !isNoteVerb) {
                val what = reminderText(w?.rest ?: text)
                if (isCommandOnly(what)) return editRoute(text, t, now, recent, force = true)
                    ?: Route.Ask("Что сделать с напоминанием? Например: «перенеси напоминание на 8 вечера», «удали напоминание», «покажи напоминания».")
                return Route.Ask("Когда напомнить${if (what.isNotBlank()) " «$what»" else ""}? Например: «через 10 минут», «завтра в 8 утра», «в 23:00».",
                    Pending("reminder", what))
            }
        }

        // --- таймер
        if (!noCreate && (Regex("$F(таймер|засеки|timer)").containsMatchIn(t) ||
            Regex("$F^(поставь|засеки)\\s+через").containsMatchIn(t))) {
            val s = parseDuration(t) ?: return Route.Ask("На сколько поставить таймер? Например: «таймер на 5 минут».")
            if (s > 86400) return Route.Ask("Таймер можно поставить максимум на 24 часа.")
            val label = Regex("$F(?:на|для|чтобы|label)\\s+([\\p{L} ]{3,40})$").find(t)?.groupValues?.get(1)
                ?.takeIf { parseDuration(it) == null } ?: ""
            return Route.Call(ToolCall("set_timer", JSONObject().put("seconds", s).put("label", label)))
        }

        // --- заметки: удалить
        if (Regex("$F(удали|очисти|сотри|delete|clear)\\s+(все\\s+|all\\s+|my\\s+|мои\\s+)*(заметк|notes)").containsMatchIn(t))
            return Route.Call(ToolCall("clear_notes", JSONObject()))


        // --- заметки: сохранить
        Regex("$F^(?:пожалуйста[,\\s]+)?(?:(?:запомни|запиши|сохрани|добавь)\\s+(?:в\\s+)?заметк[\\p{L}\\p{N}_]*|запомни|запиши|сохрани(?:\\s+заметку)?|заметка|remember(?:\\s+that)?|note(?:\\s+that)?|save\\s+(?:a\\s+)?note)\\s*(?:что\\s+|,|:|-|—)?\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.let { m ->
                val note = m.groupValues[1].trim().trimEnd('.')
                if (note.length >= 2) return Route.Call(ToolCall("save_note", JSONObject().put("text", note)))
                return Route.Ask("Что именно запомнить? Например: «Запомни: купить молоко».")
            }

        // --- дата / время
        if (Regex("$F(котор[\\p{L}\\p{N}_]*\\s+(сейчас\\s+)?час|сколько\\s+(сейчас\\s+)?времени|какое\\s+(сегодня\\s+)?число|какой\\s+(сегодня\\s+)?день(\\s+недели)?|какая\\s+(сегодня\\s+)?дата|текущ[\\p{L}\\p{N}_]*\\s+(время|дата)|время\\s+сейчас|сейчас\\s+время|what\\s+time|what('s|\\s+is)\\s+(the\\s+)?date|what\\s+day\\s+is|today'?s\\s+date)").containsMatchIn(t))
            return Route.Call(ToolCall("get_datetime", JSONObject()))

        // --- открыть сайт
        Regex("$F^(?:открой|открыть|зайди\\s+на|перейди\\s+на|запусти\\s+сайт|open)\\s+(?:сайт\\s+|страницу\\s+|ссылку\\s+|the\\s+site\\s+|website\\s+)?(\\S+)\\s*$").find(t)?.let { m ->
            val target = m.groupValues[1].trim('.', '!', '?')
            val url = Tools.normalizeUrl(siteAliases[target] ?: target)
            if (url != null) return Route.Call(ToolCall("open_url", JSONObject().put("url", url)))
        }

        // --- калькулятор: «15% от 240»
        Regex("$F(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:от|of)\\s*(\\d+(?:[.,]\\d+)?)").find(t)?.let { m ->
            val a = m.groupValues[1].replace(',', '.'); val b = m.groupValues[2].replace(',', '.')
            return Route.Call(ToolCall("calculator", JSONObject().put("expression", "$a/100*$b")))
        }
        // --- калькулятор: выражение
        val mathBody = Regex("$F^(?:сколько\\s+будет|посчитай|вычисли|подсчитай|реши|чему\\s+равно|calculate|compute|what\\s+is|what's)?\\s*[:,]?\\s*(.+?)\\s*[?=!.]*$").find(t)?.groupValues?.get(1) ?: ""
        if (mathBody.isNotEmpty()) {
            val norm = Calculator.normalize(mathBody)
            val looksMath = Regex("^[-+*/^%().\\d sqrtpi]+$").matches(norm) && norm.any { it.isDigit() } &&
                    (Regex("[-+*/^%]").containsMatchIn(norm.trimStart('-')) || norm.startsWith("sqrt"))
            if (looksMath) {
                try { Calculator.eval(mathBody); return Route.Call(ToolCall("calculator", JSONObject().put("expression", mathBody))) }
                catch (_: Exception) { }
            }
        }
        return null
    }

    /** Есть ли в сообщении намёк на инструмент — тогда стоит спросить модель-планировщик. */
    fun mightNeedTool(text: String): Boolean {
        val t = text.lowercase()
        return listOf("будильник", "разбуди", "напомн", "таймер", "засек", "запомн", "запиш", "заметк", "сохран", "открой", "сайт", "ссылк",
            "посчитай", "вычисли", "сколько будет", "время", "который час", "дата", "число",
            "timer", "remember", "note", "open", "calculate", "time", "date", "http", "www.")
            .any { t.contains(it) } || Regex("\\d\\s*[-+*/^x×÷]\\s*\\d").containsMatchIn(t)
    }
}
